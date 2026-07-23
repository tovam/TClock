package org.fossify.clock.helpers

import org.fossify.clock.models.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CalendarDiagnosticsBuilderTest {
    private val now = 1_800_000_000_000L
    private val window = CalendarAlarmWindow.rangeAt(now)

    @Test
    fun multiOffsetNoMarkerAndInvalidMentionEventsArePreserved() {
        val records = listOf(
            event(
                eventId = 1L,
                beginMillis = now + hours(4),
                description = "tclock:30min and TCLOCK:+2h"
            ),
            event(
                eventId = 2L,
                beginMillis = now + hours(5),
                description = "ordinary calendar note"
            ),
            event(
                eventId = 3L,
                beginMillis = now + hours(6),
                description = "malformed tclock reminder"
            )
        )

        val snapshot = build(records = records)

        assertEquals(3, snapshot.events.size)
        assertEquals(
            listOf(-30, 120),
            snapshot.events.first { it.key.eventId == 1L }
                .markers
                .map { it.key.offsetMinutes }
        )
        assertEquals(
            CalendarMarkerParseState.NONE,
            snapshot.events.first { it.key.eventId == 2L }.markerParseState
        )
        assertEquals(
            CalendarMarkerParseState.INVALID_MENTION,
            snapshot.events.first { it.key.eventId == 3L }.markerParseState
        )
        assertEquals(2, snapshot.counts.eligibleMarkersWithoutAlarm)
        assertEquals(1, snapshot.counts.invalidMarkerEvents)
    }

    @Test
    fun duplicateAlarmKeysArePreservedAndMetadataDriftIsDetailed() {
        val record = event(
            eventId = 10L,
            beginMillis = now + hours(8),
            title = "Flight",
            description = "tclock:30min"
        )
        val triggerAtMillis = record.beginMillis - minutes(30)
        val key = CalendarAlarmKey(
            occurrence = CalendarOccurrenceKey(record.eventId, record.beginMillis),
            offsetMinutes = -30
        ).persistedValue
        val exact = alarm(
            id = 101,
            key = key,
            eventId = record.eventId,
            eventStartMillis = record.beginMillis,
            offsetMinutes = -30,
            triggerAtMillis = triggerAtMillis,
            label = "Flight"
        )
        val drifted = exact.copy(
            id = 102,
            triggerAtMillis = triggerAtMillis + 1L,
            isEnabled = false
        )

        val snapshot = build(records = listOf(record), alarms = listOf(exact, drifted))
        val diagnostics = snapshot.events.single().markers.single().alarms

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.hasDuplicateKey })
        assertEquals(CalendarAlarmLinkStatus.EXACT, diagnostics[0].linkStatus)
        assertEquals(CalendarAlarmLinkStatus.METADATA_DRIFT, diagnostics[1].linkStatus)
        assertEquals(
            setOf(
                CalendarAlarmDriftField.TRIGGER,
                CalendarAlarmDriftField.ENABLED
            ),
            diagnostics[1].driftFields
        )
        assertEquals(2, snapshot.counts.duplicateAlarms)
    }

    @Test
    fun recurringOccurrencesWithTheSameEventIdRemainSeparate() {
        val firstStart = now + days(1)
        val secondStart = now + days(8)
        val records = listOf(
            event(
                eventId = 42L,
                beginMillis = firstStart,
                description = "tclock:1h"
            ),
            event(
                eventId = 42L,
                beginMillis = secondStart,
                description = "tclock:1h"
            )
        )

        val snapshot = build(records = records)

        assertEquals(
            setOf(
                CalendarOccurrenceKey(42L, firstStart),
                CalendarOccurrenceKey(42L, secondStart)
            ),
            snapshot.events.map { it.key }.toSet()
        )
    }

    @Test
    fun markerMissingAlarmStaysAttachedToItsOccurrence() {
        val record = event(
            eventId = 50L,
            beginMillis = now + hours(2),
            description = "marker was removed"
        )
        val staleAlarm = alarm(
            id = 500,
            key = "${record.eventId}:${record.beginMillis}:-15",
            eventId = record.eventId,
            eventStartMillis = record.beginMillis,
            offsetMinutes = -15,
            triggerAtMillis = record.beginMillis - minutes(15)
        )

        val snapshot = build(records = listOf(record), alarms = listOf(staleAlarm))
        val diagnostic = snapshot.events.single().markerMissingAlarms.single()

        assertEquals(CalendarAlarmLinkStatus.MARKER_MISSING, diagnostic.linkStatus)
        assertTrue(snapshot.unlinkedAlarms.isEmpty())
    }

    @Test
    fun occurrenceOutsideDisplayWindowIsIncludedWhenItsTriggerIsInside() {
        val record = event(
            eventId = 60L,
            beginMillis = now + days(16),
            description = "tclock:2d"
        )

        val snapshot = build(records = listOf(record))
        val diagnostic = snapshot.events.single()

        assertFalse(diagnostic.isInDisplayWindow)
        assertEquals(CalendarMarkerDisposition.ELIGIBLE, diagnostic.markers.single().disposition)
        assertEquals(1, snapshot.counts.relatedEventsOutsideWindow)
    }

    @Test
    fun markerDispositionsMirrorCalendarSyncRulesAndBoundaries() {
        val allDay = event(
            eventId = 61L,
            beginMillis = now + hours(1),
            description = "tclock:0min",
            isAllDay = true
        )
        val canceled = event(
            eventId = 62L,
            beginMillis = now + hours(2),
            description = "tclock:0min",
            isCanceled = true
        )
        val unsupported = event(
            eventId = 63L,
            beginMillis = now + days(1),
            description = "tclock:16d"
        )
        val past = event(
            eventId = 64L,
            beginMillis = now + minutes(10),
            description = "tclock:30min"
        )
        val exactNow = event(
            eventId = 65L,
            beginMillis = now,
            description = "tclock:0min"
        )
        val afterNow = event(
            eventId = 66L,
            beginMillis = now + 1L,
            description = "tclock:0min"
        )
        val exactEnd = event(
            eventId = 67L,
            beginMillis = window.triggerEndMillis,
            description = "tclock:0min"
        )
        val afterEnd = event(
            eventId = 68L,
            beginMillis = window.triggerEndMillis + 1L,
            description = "tclock:0min"
        )
        val afterEndAlarm = alarm(
            id = 680,
            key = "${afterEnd.eventId}:${afterEnd.beginMillis}:0",
            eventId = afterEnd.eventId,
            eventStartMillis = afterEnd.beginMillis,
            offsetMinutes = 0,
            triggerAtMillis = afterEnd.beginMillis,
            label = "Event 68"
        )

        val snapshot = build(
            records = listOf(
                allDay,
                canceled,
                unsupported,
                past,
                exactNow,
                afterNow,
                exactEnd,
                afterEnd
            ),
            alarms = listOf(afterEndAlarm)
        )

        fun disposition(eventId: Long): CalendarMarkerDisposition {
            return snapshot.events.first { it.key.eventId == eventId }
                .markers
                .single()
                .disposition
        }

        assertEquals(CalendarMarkerDisposition.ALL_DAY_EVENT, disposition(61L))
        assertEquals(CalendarMarkerDisposition.CANCELED_EVENT, disposition(62L))
        assertEquals(CalendarMarkerDisposition.UNSUPPORTED_OFFSET, disposition(63L))
        assertEquals(CalendarMarkerDisposition.TRIGGER_NOT_FUTURE, disposition(64L))
        assertEquals(CalendarMarkerDisposition.TRIGGER_NOT_FUTURE, disposition(65L))
        assertEquals(CalendarMarkerDisposition.ELIGIBLE, disposition(66L))
        assertEquals(CalendarMarkerDisposition.ELIGIBLE, disposition(67L))
        assertEquals(CalendarMarkerDisposition.TRIGGER_AFTER_WINDOW, disposition(68L))
    }

    @Test
    fun missingEventIsTopLevelWhenProviderDataIsAvailable() {
        val orphan = alarm(
            id = 700,
            key = "70:${now + days(2)}:-30",
            eventId = 70L,
            eventStartMillis = now + days(2),
            offsetMinutes = -30,
            triggerAtMillis = now + days(2) - minutes(30)
        )

        val snapshot = build(records = emptyList(), alarms = listOf(orphan))

        assertEquals(
            CalendarAlarmLinkStatus.EVENT_MISSING,
            snapshot.unlinkedAlarms.single().linkStatus
        )
        assertEquals(1, snapshot.counts.eventMissingAlarms)
    }

    @Test
    fun alarmsAreUnverifiableWhenPermissionOrProviderIsUnavailable() {
        val alarm = alarm(
            id = 800,
            key = "80:${now + days(3)}:-30",
            eventId = 80L,
            eventStartMillis = now + days(3),
            offsetMinutes = -30,
            triggerAtMillis = now + days(3) - minutes(30)
        )

        listOf(
            CalendarDiagnosticsProviderState.PERMISSION_MISSING,
            CalendarDiagnosticsProviderState.PROVIDER_ERROR
        ).forEach { providerState ->
            val snapshot = build(
                records = emptyList(),
                alarms = listOf(alarm),
                providerState = providerState
            )

            assertTrue(snapshot.events.isEmpty())
            assertEquals(
                CalendarAlarmLinkStatus.UNVERIFIABLE,
                snapshot.unlinkedAlarms.single().linkStatus
            )
            assertEquals(1, snapshot.counts.unverifiableAlarms)
        }
    }

    private fun build(
        records: List<CalendarEventRecord>,
        alarms: List<Alarm> = emptyList(),
        providerState: CalendarDiagnosticsProviderState =
            CalendarDiagnosticsProviderState.AVAILABLE,
    ): CalendarDiagnosticsSnapshot {
        return CalendarDiagnosticsBuilder.build(
            capturedAtMillis = now,
            window = window,
            providerState = providerState,
            records = records,
            alarms = alarms,
            untitledEventLabel = "Untitled"
        )
    }

    private fun event(
        eventId: Long,
        beginMillis: Long,
        title: String = "Event $eventId",
        description: String,
        isAllDay: Boolean = false,
        isCanceled: Boolean = false,
    ): CalendarEventRecord {
        return CalendarEventRecord(
            eventId = eventId,
            calendarId = 1L,
            calendarDisplayName = "Personal",
            displayColor = null,
            title = title,
            description = description,
            beginMillis = beginMillis,
            endMillis = beginMillis + hours(1),
            isAllDay = isAllDay,
            isCanceled = isCanceled
        )
    }

    private fun alarm(
        id: Int,
        key: String,
        eventId: Long,
        eventStartMillis: Long,
        offsetMinutes: Int,
        triggerAtMillis: Long,
        label: String = "",
    ): Alarm {
        return Alarm(
            id = id,
            timeInMinutes = 0,
            days = 0,
            isEnabled = true,
            vibrate = false,
            soundTitle = "",
            soundUri = "",
            label = label,
            oneShot = true,
            triggerAtMillis = triggerAtMillis,
            source = Alarm.SOURCE_CALENDAR,
            calendarKey = key,
            calendarEventId = eventId,
            calendarEventStartMillis = eventStartMillis,
            calendarOffsetMinutes = offsetMinutes
        )
    }

    private fun minutes(value: Long) = TimeUnit.MINUTES.toMillis(value)

    private fun hours(value: Long) = TimeUnit.HOURS.toMillis(value)

    private fun days(value: Long) = TimeUnit.DAYS.toMillis(value)
}
