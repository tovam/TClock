package org.fossify.clock.helpers

import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.engine.Cl1AppSnapshot
import org.fossify.clock.cl1.engine.Cl1DiscoverySnapshot
import org.fossify.clock.cl1.engine.Cl1RelationKey
import org.fossify.clock.cl1.engine.Cl1RelationSnapshot
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.engine.Cl1ScanResult
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarCl1AlarmIntegrationTest {
    private val now = 1_800_000_000_000L
    private val calendar = Cl1CalendarDescriptor(
        ref = Cl1CalendarRef(10),
        displayName = "Personal",
        color = null,
        accountName = "me@example.com",
        accountType = "test",
        canonicalAccountEmail = null,
        visible = true,
        accessLevel = 700,
        capabilities = emptySet()
    )
    private val source = cl1Event(eventId = 1)
    private val mirror = cl1Event(eventId = 2)

    @Test
    fun `active visible source and mirror suppress only the mirror marker`() {
        val snapshot = diagnostics(Cl1RelationState.ACTIVE)

        assertEquals(
            CalendarMarkerDisposition.SUPPRESSED_CL1_MIRROR,
            snapshot.events.single().markers.single().disposition
        )
        assertEquals(0, snapshot.counts.eligibleMarkersWithoutAlarm)
    }

    @Test
    fun `non active relation leaves the mirror autonomous`() {
        val snapshot = diagnostics(Cl1RelationState.COPY_MODIFIED)

        assertEquals(
            CalendarMarkerDisposition.ELIGIBLE,
            snapshot.events.single().markers.single().disposition
        )
        assertEquals(1, snapshot.counts.eligibleMarkersWithoutAlarm)
    }

    private fun diagnostics(state: Cl1RelationState): CalendarDiagnosticsSnapshot {
        val discovery = Cl1DiscoverySnapshot(
            capturedAtMillis = now,
            events = listOf(source, mirror),
            relations = listOf(
                Cl1RelationSnapshot(
                    key = Cl1RelationKey(SLOT),
                    state = state,
                    source = source,
                    mirror = mirror,
                    sourcePayload = null,
                    sourceRecordIndex = null,
                    mirrorPayload = null,
                    expectedMirror = null,
                    expectedRevision = null,
                    actualRevision = null
                )
            ),
            eventIssues = emptyList()
        )
        val cl1 = Cl1AppSnapshot(
            scan = Cl1ScanResult(
                discovery = discovery,
                queriedWindows = emptyList(),
                unavailableCachedEvents = emptySet()
            ),
            calendars = listOf(calendar),
            pendingOperations = emptyList()
        )
        val record = CalendarEventRecord(
            eventId = mirror.ref.eventId,
            calendarId = mirror.ref.calendarId,
            calendarDisplayName = calendar.displayName,
            displayColor = null,
            title = "Meeting",
            description = "ALARM:30min",
            beginMillis = now + HOUR_MILLIS,
            endMillis = now + 2 * HOUR_MILLIS,
            isAllDay = false,
            isCanceled = false
        )
        return CalendarDiagnosticsBuilder.build(
            capturedAtMillis = now,
            window = CalendarAlarmWindow.rangeAt(now),
            providerState = CalendarDiagnosticsProviderState.AVAILABLE,
            records = listOf(record),
            alarms = emptyList(),
            untitledEventLabel = "Untitled",
            cl1 = cl1
        )
    }

    private fun cl1Event(eventId: Long): Cl1EventSnapshot {
        return Cl1EventSnapshot(
            ref = Cl1EventRef(eventId, calendar.ref.calendarId),
            calendar = calendar,
            title = "Meeting",
            startMillis = now + HOUR_MILLIS,
            endMillis = now + 2 * HOUR_MILLIS,
            startTimeZone = "UTC",
            endTimeZone = "UTC",
            location = "",
            description = "ALARM:30min",
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

    private companion object {
        val SLOT = Cl1Bytes.fromHex("000102030405060708090a0b")
        const val HOUR_MILLIS = 60L * 60L * 1_000L
    }
}
