package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEmail
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Limits
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1SourceRecord
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.provider.Cl1CalendarAdapter
import org.fossify.clock.cl1.provider.Cl1CalendarCapability
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1CreateResult
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.provider.Cl1EventWrite
import org.fossify.clock.cl1.provider.Cl1MutationResult
import org.fossify.clock.cl1.storage.Cl1CachedBinding
import org.fossify.clock.cl1.storage.Cl1CachedEventIssue
import org.fossify.clock.cl1.storage.Cl1CachedRelation
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.storage.Cl1Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Cl1OperationEngineTest {
    @Test
    fun `a lost create response resumes with the same token without a duplicate`() {
        val adapter = MemoryCalendarAdapter(lostFirstCreateResponse = true)
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage, nowMillis = { 123L })

        val first = engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
        assertTrue(first is Cl1OperationResult.Pending)
        assertEquals(2, adapter.allEvents().size)
        assertEquals(1, storage.listPendingOperations().size)

        val resumed = engine.resumePending().single()

        assertTrue(resumed is Cl1OperationResult.Completed)
        assertEquals(2, adapter.allEvents().size)
        assertTrue(storage.listPendingOperations().isEmpty())
        val relation = Cl1Discovery.build(adapter.allEvents()).relations.single()
        assertEquals(Cl1RelationState.ACTIVE, relation.state)
        assertTrue(!relation.needsRevisionRefresh)
    }

    @Test
    fun `an ambiguous create token becomes a durable conflict`() {
        val adapter = MemoryCalendarAdapter(createConflict = true)
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage)

        val result = engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)

        assertEquals(
            "mirrorCreate:createTokenAmbiguous",
            (result as Cl1OperationResult.Conflict).reason
        )
        assertEquals(1, adapter.allEvents().size)
        assertEquals(1, storage.listPendingOperations().size)
    }

    @Test
    fun `creation rejects invalid overrides and a full source before journalling`() {
        run {
            val adapter = MemoryCalendarAdapter()
            val storage = MemoryStorage()
            val engine = Cl1OperationEngine(adapter, storage)

            val result = engine.createRelation(
                sourceRef = SOURCE_REF,
                destinationRef = MIRROR_CALENDAR_REF,
                overrides = Cl1MirrorOverrides(
                    title = Cl1TitleOverride.Replacement("")
                )
            )

            assertEquals(
                "invalidOverrides",
                (result as Cl1OperationResult.Rejected).reason
            )
            assertTrue(storage.listPendingOperations().isEmpty())
            assertEquals(1, adapter.allEvents().size)
        }

        run {
            val adapter = MemoryCalendarAdapter()
            val storage = MemoryStorage()
            val records = List(Cl1Limits.SOURCE_RECORDS) { index ->
                sourceRecord(index)
            }
            adapter.replaceSourceDescription(
                Cl1Armor.compose("notes", Cl1Payload.Source(records))
            )
            val engine = Cl1OperationEngine(adapter, storage)

            val result = engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)

            assertEquals(
                "sourceRecordLimit",
                (result as Cl1OperationResult.Rejected).reason
            )
            assertTrue(storage.listPendingOperations().isEmpty())
            assertEquals(1, adapter.allEvents().size)
        }
    }

    @Test
    fun `a concurrent source edit is incorporated before commit then auto synced`() {
        val adapter = MemoryCalendarAdapter(
            sourceTitleAfterCreate = "Edited during creation"
        )
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage, nowMillis = { 456L })

        val created = engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)

        assertTrue(created is Cl1OperationResult.Completed)
        var discovery = Cl1Discovery.build(adapter.allEvents())
        assertEquals(Cl1RelationState.ACTIVE, discovery.relations.single().state)
        assertEquals(
            "Edited during creation",
            discovery.relations.single().mirror?.title
        )

        adapter.editSourceTitle("Edited after commit")
        discovery = Cl1Discovery.build(adapter.allEvents())
        assertEquals(
            Cl1RelationState.SOURCE_MODIFIED,
            discovery.relations.single().state
        )

        val synchronized = engine.reconcile(discovery).single()

        assertTrue(synchronized is Cl1OperationResult.Completed)
        val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
        assertEquals(Cl1RelationState.ACTIVE, active.state)
        assertEquals("Edited after commit", active.mirror?.title)
    }

    @Test
    fun `repair replaces the old record only after the new mirror is verified`() {
        val adapter = MemoryCalendarAdapter()
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage, nowMillis = { 789L })
        assertTrue(
            engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF) is
                Cl1OperationResult.Completed
        )
        val oldRelation = Cl1Discovery.build(adapter.allEvents())
            .relations
            .single()
        val oldSlot = oldRelation.key.slot
        val oldMirror = requireNotNull(oldRelation.mirror)
        adapter.removeMirrorEvents()
        val missing = Cl1Discovery.build(adapter.allEvents()).relations.single()
        assertEquals(Cl1RelationState.MISSING_OR_INACCESSIBLE, missing.state)

        val repaired = engine.repairRelation(missing, MIRROR_CALENDAR_REF)

        assertTrue(repaired is Cl1OperationResult.Completed)
        val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
        assertEquals(Cl1RelationState.ACTIVE, active.state)
        assertTrue(active.key.slot != oldSlot)
        assertTrue(oldSlot.toHex() in storage.listConfirmedOrphanSlots())
        val resurrected = Cl1Discovery.build(
            events = listOf(oldMirror),
            confirmedOrphanSlots = setOf(oldSlot)
        ).relations.single()
        assertEquals(Cl1RelationState.ORPHAN, resurrected.state)
        assertTrue(storage.listPendingOperations().isEmpty())
    }

    @Test
    fun `copy edits can be restored converted or applied without blind overwrite`() {
        run {
            val adapter = MemoryCalendarAdapter()
            val engine = Cl1OperationEngine(adapter, MemoryStorage())
            engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
            adapter.editMirror(title = "Copy edit")
            val modified = Cl1Discovery.build(adapter.allEvents()).relations.single()

            assertTrue(
                engine.restoreFromSource(modified) is Cl1OperationResult.Completed
            )
            val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
            assertEquals(Cl1RelationState.ACTIVE, active.state)
            assertEquals("Initial", active.mirror?.title)
        }

        run {
            val adapter = MemoryCalendarAdapter()
            val engine = Cl1OperationEngine(adapter, MemoryStorage())
            engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
            adapter.editMirror(
                title = "Custom",
                startDeltaMillis = 30 * 60 * 1_000L,
                durationMillis = 2 * 60 * 1_000L
            )
            val modified = Cl1Discovery.build(adapter.allEvents()).relations.single()
            val conversion = Cl1OverrideConversion(
                titleOverride = Cl1TitleOverride.Replacement("Custom"),
                durationMode = Cl1DurationConversion.FIXED
            )

            assertTrue(
                engine.convertCopyToOverrides(modified, conversion) is
                    Cl1OperationResult.Completed
            )
            val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
            assertEquals(Cl1RelationState.ACTIVE, active.state)
            val payload = requireNotNull(active.mirrorPayload)
            assertEquals(
                Cl1TitleOverride.Replacement("Custom"),
                payload.titleOverride
            )
            assertEquals(
                Cl1DurationOverride.Fixed(120uL),
                payload.durationOverride
            )
            assertEquals(30 * 60L, payload.startOffsetSeconds)
        }

        run {
            val adapter = MemoryCalendarAdapter()
            val engine = Cl1OperationEngine(adapter, MemoryStorage())
            engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
            adapter.editMirror(title = "Copy wins", location = "New room")
            val modified = Cl1Discovery.build(adapter.allEvents()).relations.single()

            assertTrue(
                engine.applyCopyToSource(modified) is Cl1OperationResult.Completed
            )
            val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
            assertEquals(Cl1RelationState.ACTIVE, active.state)
            assertEquals("Copy wins", active.source?.title)
            assertEquals("New room", active.source?.location)
            assertEquals(Cl1TitleOverride.Inherited, active.mirrorPayload?.titleOverride)
            assertEquals(null, active.mirrorPayload?.startOffsetSeconds)
            assertEquals(
                Cl1DurationOverride.Inherited,
                active.mirrorPayload?.durationOverride
            )
        }
    }

    @Test
    fun `unlink removes both protocol blocks but preserves both events`() {
        val adapter = MemoryCalendarAdapter()
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage)
        engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
        val active = Cl1Discovery.build(adapter.allEvents()).relations.single()
        val oldMirror = requireNotNull(active.mirror)

        val result = engine.unlink(active)

        assertTrue(result is Cl1OperationResult.Completed)
        assertEquals(2, adapter.allEvents().size)
        assertTrue(Cl1Discovery.build(adapter.allEvents()).relations.isEmpty())
        assertTrue(
            active.key.slot.toHex() in storage.listConfirmedOrphanSlots()
        )
        assertEquals(
            Cl1RelationState.ORPHAN,
            Cl1Discovery.build(
                events = listOf(oldMirror),
                confirmedOrphanSlots = setOf(active.key.slot)
            ).relations.single().state
        )
        adapter.allEvents().forEach {
            assertTrue(it.parsedDescription is Cl1Description.None)
            assertEquals("notes", it.description)
        }
    }

    @Test
    fun `destination change rotates the secret moves the mirror then replaces record`() {
        val adapter = MemoryCalendarAdapter()
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage)
        engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
        val before = Cl1Discovery.build(adapter.allEvents()).relations.single()

        val changed = engine.changeDestination(before, NEW_MIRROR_CALENDAR_REF)

        assertTrue(changed is Cl1OperationResult.Completed)
        val after = Cl1Discovery.build(adapter.allEvents()).relations.single()
        assertEquals(Cl1RelationState.ACTIVE, after.state)
        assertTrue(after.key.slot != before.key.slot)
        assertEquals(NEW_MIRROR_CALENDAR_ID, after.mirror?.ref?.calendarId)
        assertEquals(OTHER_EMAIL, after.mirror?.calendar?.canonicalAccountEmail)
        assertTrue(
            before.key.slot.toHex() in storage.listConfirmedOrphanSlots()
        )
        assertTrue(storage.listPendingOperations().isEmpty())
    }

    @Test
    fun `destination change refuses a copy edited after its baseline was read`() {
        val adapter = MemoryCalendarAdapter()
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage)
        engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
        val before = Cl1Discovery.build(adapter.allEvents()).relations.single()
        adapter.editMirrorBeforeNextCalendarList()

        val changed = engine.changeDestination(before, NEW_MIRROR_CALENDAR_REF)

        assertTrue(changed is Cl1OperationResult.Conflict)
        val mirror = adapter.allEvents().single {
            it.ref.calendarId == MIRROR_CALENDAR_ID
        }
        assertEquals("Concurrent copy edit", mirror.title)
        assertTrue(
            adapter.allEvents().none {
                it.ref.calendarId == NEW_MIRROR_CALENDAR_ID
            }
        )
        assertEquals(
            Cl1RelationState.COPY_MODIFIED,
            Cl1Discovery.build(adapter.allEvents()).relations.single().state
        )
    }

    @Test
    fun `source deletion removes every known mirror before deleting the source`() {
        val adapter = MemoryCalendarAdapter()
        val storage = MemoryStorage()
        val engine = Cl1OperationEngine(adapter, storage)
        engine.createRelation(SOURCE_REF, MIRROR_CALENDAR_REF)
        val discovery = Cl1Discovery.build(adapter.allEvents())

        val deleted = engine.deleteSource(SOURCE_REF, discovery)

        assertTrue(deleted is Cl1OperationResult.Completed)
        assertTrue(adapter.allEvents().isEmpty())
        assertEquals(
            listOf(MIRROR_CALENDAR_ID, SOURCE_CALENDAR_ID),
            adapter.deletionOrder.map { it.calendarId }
        )
        assertEquals(
            discovery.relations.map { it.key.slot.toHex() }.toSet(),
            storage.listConfirmedOrphanSlots()
        )
        assertTrue(storage.listPendingOperations().isEmpty())
    }

    private class MemoryCalendarAdapter(
        private var lostFirstCreateResponse: Boolean = false,
        private val sourceTitleAfterCreate: String? = null,
        private val createConflict: Boolean = false,
    ) : Cl1CalendarAdapter {
        private val sourceCalendar = calendar(SOURCE_CALENDAR_ID)
        private val mirrorCalendar = calendar(MIRROR_CALENDAR_ID)
        private val newMirrorCalendar = calendar(
            NEW_MIRROR_CALENDAR_ID,
            OTHER_EMAIL
        )
        private val events = linkedMapOf(
            SOURCE_REF to event(
                ref = SOURCE_REF,
                calendar = sourceCalendar,
                title = "Initial",
                description = "notes"
            )
        )
        private val createdByToken = HashMap<String, Cl1EventRef>()
        private var nextId = 100L
        private var editMirrorBeforeCalendarList = false
        val deletionOrder = ArrayList<Cl1EventRef>()

        fun allEvents(): List<Cl1EventSnapshot> = events.values.toList()

        fun editSourceTitle(title: String) {
            events[SOURCE_REF] = requireNotNull(events[SOURCE_REF]).copy(title = title)
        }

        fun replaceSourceDescription(description: String) {
            events[SOURCE_REF] = requireNotNull(events[SOURCE_REF]).copy(
                description = description
            )
        }

        fun removeMirrorEvents() {
            events.keys.filter { it.calendarId == MIRROR_CALENDAR_ID }
                .forEach(events::remove)
        }

        fun editMirror(
            title: String,
            startDeltaMillis: Long = 0,
            durationMillis: Long? = null,
            location: String? = null,
        ) {
            val ref = events.keys.single { it.calendarId == MIRROR_CALENDAR_ID }
            val current = requireNotNull(events[ref])
            val start = current.startMillis + startDeltaMillis
            val end = durationMillis?.let { start + it }
                ?: requireNotNull(current.endMillis) + startDeltaMillis
            events[ref] = current.copy(
                title = title,
                startMillis = start,
                endMillis = end,
                location = location ?: current.location
            )
        }

        fun editMirrorBeforeNextCalendarList() {
            editMirrorBeforeCalendarList = true
        }

        override fun listCalendars(): List<Cl1CalendarDescriptor> {
            if (editMirrorBeforeCalendarList) {
                editMirrorBeforeCalendarList = false
                editMirror("Concurrent copy edit")
            }
            return listOf(sourceCalendar, mirrorCalendar, newMirrorCalendar)
        }

        override fun listEvents(
            beginMillis: Long,
            endMillis: Long,
        ): List<Cl1EventSnapshot> {
            return events.values.filter {
                it.startMillis <= endMillis &&
                    (it.endMillis ?: it.startMillis) >= beginMillis
            }
        }

        override fun readEvent(ref: Cl1EventRef): Cl1EventSnapshot? = events[ref]

        override fun findCreatedEvent(
            calendar: Cl1CalendarDescriptor,
            createToken: String,
        ): Cl1EventSnapshot? {
            return createdByToken[createToken]?.let(events::get)
        }

        override fun createEvent(
            calendar: Cl1CalendarDescriptor,
            createToken: String,
            value: Cl1EventWrite,
        ): Cl1CreateResult {
            if (createConflict) {
                return Cl1CreateResult.Conflict("createTokenAmbiguous")
            }
            createdByToken[createToken]?.let { ref ->
                return Cl1CreateResult.Existing(requireNotNull(events[ref]))
            }
            val ref = Cl1EventRef(nextId++, calendar.ref.calendarId)
            val created = eventFromWrite(
                ref = ref,
                calendar = calendar,
                uid = createToken,
                value = value
            )
            events[ref] = created
            createdByToken[createToken] = ref
            sourceTitleAfterCreate?.let(::editSourceTitle)
            return if (lostFirstCreateResponse) {
                lostFirstCreateResponse = false
                Cl1CreateResult.Failed("lostResponse")
            } else {
                Cl1CreateResult.Created(created)
            }
        }

        override fun updateEvent(
            expected: Cl1EventSnapshot,
            value: Cl1EventWrite,
        ): Cl1MutationResult {
            val current = events[expected.ref] ?: return Cl1MutationResult.Missing
            if (current != expected) {
                return Cl1MutationResult.PreconditionFailed
            }
            val updated = eventFromWrite(
                ref = current.ref,
                calendar = current.calendar,
                uid = current.uid2445,
                value = value
            )
            events[current.ref] = updated
            return Cl1MutationResult.Applied(updated)
        }

        override fun deleteEvent(
            expected: Cl1EventSnapshot,
        ): Cl1MutationResult {
            val current = events[expected.ref] ?: return Cl1MutationResult.Missing
            if (current != expected) {
                return Cl1MutationResult.PreconditionFailed
            }
            events.remove(expected.ref)
            deletionOrder.add(expected.ref)
            return Cl1MutationResult.Applied(null)
        }

        override fun moveEvent(
            expected: Cl1EventSnapshot,
            destination: Cl1CalendarDescriptor,
            value: Cl1EventWrite,
        ): Cl1MutationResult {
            val current = events[expected.ref] ?: return Cl1MutationResult.Missing
            if (current != expected) {
                return Cl1MutationResult.PreconditionFailed
            }
            val movedRef = Cl1EventRef(
                current.ref.eventId,
                destination.ref.calendarId
            )
            val moved = eventFromWrite(
                ref = movedRef,
                calendar = destination,
                uid = current.uid2445,
                value = value
            )
            events.remove(current.ref)
            events[movedRef] = moved
            createdByToken.entries.forEach {
                if (it.value == current.ref) it.setValue(movedRef)
            }
            return Cl1MutationResult.Applied(moved)
        }

        private fun eventFromWrite(
            ref: Cl1EventRef,
            calendar: Cl1CalendarDescriptor,
            uid: String?,
            value: Cl1EventWrite,
        ): Cl1EventSnapshot {
            val event = value.canonicalEvent
            return event(
                ref = ref,
                calendar = calendar,
                title = event.title,
                description = value.description,
                startMillis = event.startUnixSeconds * 1_000,
                endMillis = event.endUnixSeconds * 1_000,
                startTimeZone = event.startIanaTimeZone,
                endTimeZone = event.endIanaTimeZone,
                location = event.location,
                uid = uid
            )
        }
    }

    private class MemoryStorage : Cl1Storage {
        private val operations = LinkedHashMap<String, Cl1PendingOperation>()
        private val confirmedOrphans = LinkedHashSet<String>()

        override fun listCachedBindings(): List<Cl1CachedBinding> = emptyList()

        override fun listCachedRelations(): List<Cl1CachedRelation> = emptyList()

        override fun listCachedEventIssues(): List<Cl1CachedEventIssue> = emptyList()

        override fun listConfirmedOrphanSlots(): Set<String> =
            confirmedOrphans.toSet()

        override fun saveDiscovery(snapshot: Cl1DiscoverySnapshot) = Unit

        override fun markConfirmedOrphan(slotHex: String) {
            confirmedOrphans.add(slotHex)
        }

        override fun putOperation(operation: Cl1PendingOperation) {
            operations[operation.operationId] = operation
        }

        override fun listPendingOperations(): List<Cl1PendingOperation> {
            return operations.values.toList()
        }

        override fun removeOperation(operationId: String) {
            operations.remove(operationId)
        }
    }

    private companion object {
        const val SOURCE_CALENDAR_ID = 10L
        const val MIRROR_CALENDAR_ID = 20L
        const val NEW_MIRROR_CALENDAR_ID = 30L
        val SOURCE_REF = Cl1EventRef(1, SOURCE_CALENDAR_ID)
        val MIRROR_CALENDAR_REF = Cl1CalendarRef(MIRROR_CALENDAR_ID)
        val NEW_MIRROR_CALENDAR_REF = Cl1CalendarRef(NEW_MIRROR_CALENDAR_ID)
        val EMAIL = Cl1CanonicalEmail("me@example.com")
        val OTHER_EMAIL = Cl1CanonicalEmail("other@example.com")

        fun calendar(
            id: Long,
            email: Cl1CanonicalEmail = EMAIL,
        ): Cl1CalendarDescriptor {
            return Cl1CalendarDescriptor(
                ref = Cl1CalendarRef(id),
                displayName = "Calendar $id",
                color = null,
                accountName = email.value,
                accountType = "test",
                canonicalAccountEmail = email,
                visible = true,
                accessLevel = 700,
                capabilities = Cl1CalendarCapability.entries.toSet()
            )
        }

        fun event(
            ref: Cl1EventRef,
            calendar: Cl1CalendarDescriptor,
            title: String,
            description: String,
            startMillis: Long = 1_000_000,
            endMillis: Long = 1_060_000,
            startTimeZone: String = "UTC",
            endTimeZone: String = "UTC",
            location: String = "",
            uid: String? = null,
        ): Cl1EventSnapshot {
            return Cl1EventSnapshot(
                ref = ref,
                calendar = calendar,
                title = title,
                startMillis = startMillis,
                endMillis = endMillis,
                startTimeZone = startTimeZone,
                endTimeZone = endTimeZone,
                location = location,
                description = description,
                userUrl = null,
                uid2445 = uid,
                allDay = false,
                recurrenceRule = null,
                recurrenceDate = null,
                exceptionRule = null,
                exceptionDate = null,
                originalEventId = null,
                rawStatus = null,
                recurring = false,
                canceled = false,
                deleted = false
            )
        }

        fun sourceRecord(index: Int): Cl1SourceRecord {
            val slot = ByteArray(Cl1Limits.SLOT_BYTES)
            slot[slot.lastIndex - 1] = (index ushr Byte.SIZE_BITS).toByte()
            slot[slot.lastIndex] = index.toByte()
            return Cl1SourceRecord(
                slot = Cl1Bytes.copyOf(slot),
                emailCiphertext = Cl1Bytes.copyOf(byteArrayOf(1)),
                gcmTag = Cl1Bytes.copyOf(
                    ByteArray(Cl1Limits.GCM_TAG_BYTES)
                )
            )
        }
    }
}
