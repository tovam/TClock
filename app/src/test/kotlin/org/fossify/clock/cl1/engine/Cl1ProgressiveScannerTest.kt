package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEmail
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1Crypto
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1Revision
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

class Cl1ProgressiveScannerTest {
    @Test
    fun `a block expands discovery by thirty days until its counterpart is found`() {
        val sourceStart = 10_000L
        val mirrorStart = sourceStart + 29L * DAY_MILLIS
        val pair = pair(sourceStart, mirrorStart)
        val storage = MemoryStorage()
        val scanner = Cl1ProgressiveScanner(FakeAdapter(pair), storage)

        val result = scanner.scan(
            beginMillis = sourceStart - 1,
            endMillis = sourceStart + 1,
            capturedAtMillis = 123
        )

        assertEquals(2, result.discovery.events.size)
        assertEquals(Cl1RelationState.ACTIVE, result.discovery.relations.single().state)
        assertTrue(result.queriedWindows.size >= 2)
        assertEquals(result.discovery, storage.saved)
    }

    private fun pair(
        sourceStartMillis: Long,
        mirrorStartMillis: Long,
    ): List<Cl1EventSnapshot> {
        val encrypted = Cl1Crypto.encryptEmail(SECRET, EMAIL)
        val sourcePayload = Cl1Payload.Source(listOf(encrypted.toSourceRecord()))
        val canonicalMirror = Cl1CanonicalEvent.fromMillis(
            title = "Meeting",
            startUnixMillis = mirrorStartMillis,
            endUnixMillis = mirrorStartMillis + HOUR_MILLIS,
            startIanaTimeZone = "UTC",
            endIanaTimeZone = "UTC",
            location = "",
            userDescription = "notes",
            userUrl = ""
        )
        val offsetSeconds = (mirrorStartMillis - sourceStartMillis) / 1_000
        val mirrorPayload = Cl1Payload.Mirror(
            secret = SECRET,
            revision = Cl1Revision.calculate(SECRET, canonicalMirror),
            titleOverride = Cl1TitleOverride.Inherited,
            startOffsetSeconds = offsetSeconds,
            durationOverride = Cl1DurationOverride.Inherited
        )
        return listOf(
            event(
                eventId = 1,
                calendarId = 10,
                startMillis = sourceStartMillis,
                description = Cl1Armor.compose("notes", sourcePayload)
            ),
            event(
                eventId = 2,
                calendarId = 20,
                startMillis = mirrorStartMillis,
                description = Cl1Armor.compose("notes", mirrorPayload)
            )
        )
    }

    private fun event(
        eventId: Long,
        calendarId: Long,
        startMillis: Long,
        description: String,
    ): Cl1EventSnapshot {
        return Cl1EventSnapshot(
            ref = Cl1EventRef(eventId, calendarId),
            calendar = calendar(calendarId),
            title = "Meeting",
            startMillis = startMillis,
            endMillis = startMillis + HOUR_MILLIS,
            startTimeZone = "UTC",
            endTimeZone = "UTC",
            location = "",
            description = description,
            userUrl = null,
            uid2445 = null,
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

    private fun calendar(id: Long): Cl1CalendarDescriptor {
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

    private class FakeAdapter(
        private val events: List<Cl1EventSnapshot>,
    ) : Cl1CalendarAdapter {
        override fun listCalendars(): List<Cl1CalendarDescriptor> {
            return events.map { it.calendar }.distinctBy { it.ref }
        }

        override fun listEvents(
            beginMillis: Long,
            endMillis: Long,
        ): List<Cl1EventSnapshot> {
            return events.filter { it.startMillis in beginMillis..endMillis }
        }

        override fun readEvent(ref: Cl1EventRef): Cl1EventSnapshot? {
            return events.singleOrNull { it.ref == ref }
        }

        override fun createEvent(
            calendar: Cl1CalendarDescriptor,
            createToken: String,
            value: Cl1EventWrite,
        ): Cl1CreateResult = Cl1CreateResult.Failed("unused")

        override fun updateEvent(
            expected: Cl1EventSnapshot,
            value: Cl1EventWrite,
        ): Cl1MutationResult = Cl1MutationResult.Failed("unused")

        override fun deleteEvent(
            expected: Cl1EventSnapshot,
        ): Cl1MutationResult = Cl1MutationResult.Failed("unused")
    }

    private class MemoryStorage : Cl1Storage {
        var saved: Cl1DiscoverySnapshot? = null

        override fun listCachedBindings(): List<Cl1CachedBinding> = emptyList()

        override fun listCachedRelations(): List<Cl1CachedRelation> = emptyList()

        override fun listCachedEventIssues(): List<Cl1CachedEventIssue> = emptyList()

        override fun saveDiscovery(snapshot: Cl1DiscoverySnapshot) {
            saved = snapshot
        }

        override fun putOperation(operation: Cl1PendingOperation) = Unit

        override fun listPendingOperations(): List<Cl1PendingOperation> = emptyList()

        override fun removeOperation(operationId: String) = Unit
    }

    private companion object {
        val SECRET = Cl1Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
        val EMAIL = Cl1CanonicalEmail("me@example.com")
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        const val DAY_MILLIS = 24L * HOUR_MILLIS
    }
}
