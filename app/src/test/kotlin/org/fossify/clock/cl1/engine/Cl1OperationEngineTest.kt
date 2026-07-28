package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1CanonicalEmail
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

    private class MemoryCalendarAdapter(
        private var lostFirstCreateResponse: Boolean = false,
        private val sourceTitleAfterCreate: String? = null,
    ) : Cl1CalendarAdapter {
        private val sourceCalendar = calendar(SOURCE_CALENDAR_ID)
        private val mirrorCalendar = calendar(MIRROR_CALENDAR_ID)
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

        fun allEvents(): List<Cl1EventSnapshot> = events.values.toList()

        fun editSourceTitle(title: String) {
            events[SOURCE_REF] = requireNotNull(events[SOURCE_REF]).copy(title = title)
        }

        override fun listCalendars(): List<Cl1CalendarDescriptor> {
            return listOf(sourceCalendar, mirrorCalendar)
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
            return Cl1MutationResult.Applied(null)
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

        override fun listCachedBindings(): List<Cl1CachedBinding> = emptyList()

        override fun listCachedRelations(): List<Cl1CachedRelation> = emptyList()

        override fun listCachedEventIssues(): List<Cl1CachedEventIssue> = emptyList()

        override fun saveDiscovery(snapshot: Cl1DiscoverySnapshot) = Unit

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
        val SOURCE_REF = Cl1EventRef(1, SOURCE_CALENDAR_ID)
        val MIRROR_CALENDAR_REF = Cl1CalendarRef(MIRROR_CALENDAR_ID)
        val EMAIL = Cl1CanonicalEmail("me@example.com")

        fun calendar(id: Long): Cl1CalendarDescriptor {
            return Cl1CalendarDescriptor(
                ref = Cl1CalendarRef(id),
                displayName = "Calendar $id",
                color = null,
                accountName = EMAIL.value,
                accountType = "test",
                canonicalAccountEmail = EMAIL,
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
    }
}
