package org.fossify.clock.helpers

import org.fossify.clock.models.Alarm
import java.util.concurrent.TimeUnit

data class CalendarOccurrenceKey(
    val eventId: Long,
    val beginMillis: Long,
)

data class CalendarAlarmKey(
    val occurrence: CalendarOccurrenceKey,
    val offsetMinutes: Int,
) {
    val persistedValue: String
        get() = "${occurrence.eventId}:${occurrence.beginMillis}:$offsetMinutes"
}

enum class CalendarDiagnosticsProviderState {
    AVAILABLE,
    PERMISSION_MISSING,
    PROVIDER_ERROR,
}

enum class CalendarMarkerParseState {
    NONE,
    INVALID_MENTION,
    VALID,
}

enum class CalendarMarkerDisposition {
    ELIGIBLE,
    ALL_DAY_EVENT,
    CANCELED_EVENT,
    UNSUPPORTED_OFFSET,
    TRIGGER_NOT_FUTURE,
    TRIGGER_AFTER_WINDOW,
}

enum class CalendarAlarmLinkStatus {
    EXACT,
    METADATA_DRIFT,
    MARKER_MISSING,
    EVENT_MISSING,
    UNVERIFIABLE,
}

enum class CalendarAlarmDriftField {
    EVENT_ID,
    EVENT_START,
    OFFSET,
    TRIGGER,
    LABEL,
    DAYS,
    ONE_SHOT,
    ENABLED,
}

data class CalendarAlarmDiagnostic(
    val alarm: Alarm,
    val linkStatus: CalendarAlarmLinkStatus,
    val driftFields: Set<CalendarAlarmDriftField> = emptySet(),
    val hasDuplicateKey: Boolean = false,
)

data class CalendarMarkerDiagnostic(
    val key: CalendarAlarmKey,
    val triggerAtMillis: Long,
    val disposition: CalendarMarkerDisposition,
    val alarms: List<CalendarAlarmDiagnostic>,
)

data class CalendarEventDiagnostic(
    val key: CalendarOccurrenceKey,
    val calendarId: Long,
    val calendarDisplayName: String,
    val displayColor: Int?,
    val title: String,
    val description: String,
    val beginMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val isCanceled: Boolean,
    val isInDisplayWindow: Boolean,
    val markerParseState: CalendarMarkerParseState,
    val markers: List<CalendarMarkerDiagnostic>,
    val markerMissingAlarms: List<CalendarAlarmDiagnostic>,
) {
    val alarms: List<CalendarAlarmDiagnostic>
        get() = markers.flatMap { it.alarms } + markerMissingAlarms
}

data class CalendarDiagnosticsCounts(
    val displayWindowEvents: Int,
    val relatedEventsOutsideWindow: Int,
    val calendarAlarms: Int,
    val exactAlarms: Int,
    val driftedAlarms: Int,
    val duplicateAlarms: Int,
    val eligibleMarkersWithoutAlarm: Int,
    val markerMissingAlarms: Int,
    val eventMissingAlarms: Int,
    val invalidMarkerEvents: Int,
    val unverifiableAlarms: Int,
)

data class CalendarDiagnosticsSnapshot(
    val capturedAtMillis: Long,
    val displayBeginMillis: Long,
    val displayEndMillis: Long,
    val queryBeginMillis: Long,
    val queryEndMillis: Long,
    val providerState: CalendarDiagnosticsProviderState,
    val events: List<CalendarEventDiagnostic>,
    val unlinkedAlarms: List<CalendarAlarmDiagnostic>,
    val counts: CalendarDiagnosticsCounts,
)

internal data class CalendarEventRecord(
    val eventId: Long,
    val calendarId: Long,
    val calendarDisplayName: String,
    val displayColor: Int?,
    val title: String?,
    val description: String,
    val beginMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val isCanceled: Boolean,
)

internal object CalendarDiagnosticsBuilder {
    private val markerMentionPattern = Regex(
        pattern = """(?<![\p{L}\p{N}_])tclock(?![\p{L}\p{N}_])""",
        option = RegexOption.IGNORE_CASE
    )

    fun build(
        capturedAtMillis: Long,
        window: CalendarAlarmWindow.Range,
        providerState: CalendarDiagnosticsProviderState,
        records: List<CalendarEventRecord>,
        alarms: List<Alarm>,
        untitledEventLabel: String,
    ): CalendarDiagnosticsSnapshot {
        val duplicateKeys = alarms
            .filter { it.calendarKey.isNotBlank() }
            .groupingBy { it.calendarKey }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (providerState != CalendarDiagnosticsProviderState.AVAILABLE) {
            return buildUnavailableSnapshot(
                capturedAtMillis = capturedAtMillis,
                window = window,
                providerState = providerState,
                alarms = alarms,
                duplicateKeys = duplicateKeys
            )
        }

        return buildAvailableSnapshot(
            capturedAtMillis = capturedAtMillis,
            window = window,
            records = records,
            alarms = alarms,
            untitledEventLabel = untitledEventLabel,
            duplicateKeys = duplicateKeys
        )
    }

    private fun buildUnavailableSnapshot(
        capturedAtMillis: Long,
        window: CalendarAlarmWindow.Range,
        providerState: CalendarDiagnosticsProviderState,
        alarms: List<Alarm>,
        duplicateKeys: Set<String>,
    ): CalendarDiagnosticsSnapshot {
        val unverifiableAlarms = alarms
            .map { alarm ->
                CalendarAlarmDiagnostic(
                    alarm = alarm,
                    linkStatus = CalendarAlarmLinkStatus.UNVERIFIABLE,
                    hasDuplicateKey = alarm.calendarKey in duplicateKeys
                )
            }
            .sortedWith(alarmDiagnosticComparator)
        return createSnapshot(
            capturedAtMillis = capturedAtMillis,
            window = window,
            providerState = providerState,
            events = emptyList(),
            unlinkedAlarms = unverifiableAlarms,
            totalAlarmCount = alarms.size
        )
    }

    private fun buildAvailableSnapshot(
        capturedAtMillis: Long,
        window: CalendarAlarmWindow.Range,
        records: List<CalendarEventRecord>,
        alarms: List<Alarm>,
        untitledEventLabel: String,
        duplicateKeys: Set<String>,
    ): CalendarDiagnosticsSnapshot {
        val alarmsByKey = alarms.groupBy { it.calendarKey }
        val recordsByOccurrence = records
            .distinctBy { CalendarOccurrenceKey(it.eventId, it.beginMillis) }
            .associateBy { CalendarOccurrenceKey(it.eventId, it.beginMillis) }
        val matchedAlarmIds = mutableSetOf<Int>()
        val eventDiagnostics = recordsByOccurrence.map { (occurrenceKey, record) ->
            buildEventDiagnostic(
                occurrenceKey = occurrenceKey,
                record = record,
                window = window,
                alarmsByKey = alarmsByKey,
                duplicateKeys = duplicateKeys,
                matchedAlarmIds = matchedAlarmIds,
                untitledEventLabel = untitledEventLabel
            )
        }
        val (eventsWithMissingMarkers, eventMissingAlarms) = attachUnmatchedAlarms(
            events = eventDiagnostics,
            alarms = alarms,
            matchedAlarmIds = matchedAlarmIds,
            duplicateKeys = duplicateKeys
        )
        val includedEvents = eventsWithMissingMarkers
            .filter { it.shouldBeIncluded(window) }
            .sortedWith(eventDiagnosticComparator)

        return createSnapshot(
            capturedAtMillis = capturedAtMillis,
            window = window,
            providerState = CalendarDiagnosticsProviderState.AVAILABLE,
            events = includedEvents,
            unlinkedAlarms = eventMissingAlarms.sortedWith(alarmDiagnosticComparator),
            totalAlarmCount = alarms.size
        )
    }

    private fun buildEventDiagnostic(
        occurrenceKey: CalendarOccurrenceKey,
        record: CalendarEventRecord,
        window: CalendarAlarmWindow.Range,
        alarmsByKey: Map<String, List<Alarm>>,
        duplicateKeys: Set<String>,
        matchedAlarmIds: MutableSet<Int>,
        untitledEventLabel: String,
    ): CalendarEventDiagnostic {
        val title = record.title?.takeIf { it.isNotBlank() } ?: untitledEventLabel
        val offsets = TClockPatternParser.parseOffsets(record.description)
        val markers = offsets
            .map { offsetMinutes ->
                buildMarkerDiagnostic(
                    record = record,
                    occurrenceKey = occurrenceKey,
                    offsetMinutes = offsetMinutes,
                    title = title,
                    window = window,
                    alarmsByKey = alarmsByKey,
                    duplicateKeys = duplicateKeys,
                    matchedAlarmIds = matchedAlarmIds
                )
            }
            .sortedWith(markerDiagnosticComparator)
        return CalendarEventDiagnostic(
            key = occurrenceKey,
            calendarId = record.calendarId,
            calendarDisplayName = record.calendarDisplayName,
            displayColor = record.displayColor,
            title = title,
            description = record.description,
            beginMillis = record.beginMillis,
            endMillis = record.endMillis,
            isAllDay = record.isAllDay,
            isCanceled = record.isCanceled,
            isInDisplayWindow = record.overlapsDisplayWindow(window),
            markerParseState = markerParseState(record.description, offsets),
            markers = markers,
            markerMissingAlarms = emptyList()
        )
    }

    private fun buildMarkerDiagnostic(
        record: CalendarEventRecord,
        occurrenceKey: CalendarOccurrenceKey,
        offsetMinutes: Int,
        title: String,
        window: CalendarAlarmWindow.Range,
        alarmsByKey: Map<String, List<Alarm>>,
        duplicateKeys: Set<String>,
        matchedAlarmIds: MutableSet<Int>,
    ): CalendarMarkerDiagnostic {
        val markerKey = CalendarAlarmKey(occurrenceKey, offsetMinutes)
        val triggerAtMillis = record.beginMillis +
            TimeUnit.MINUTES.toMillis(offsetMinutes.toLong())
        val linkedAlarms = alarmsByKey[markerKey.persistedValue].orEmpty()
        linkedAlarms.forEach { matchedAlarmIds.add(it.id) }
        return CalendarMarkerDiagnostic(
            key = markerKey,
            triggerAtMillis = triggerAtMillis,
            disposition = markerDisposition(
                record = record,
                offsetMinutes = offsetMinutes,
                triggerAtMillis = triggerAtMillis,
                window = window
            ),
            alarms = linkedAlarms
                .map { alarm ->
                    alarm.toLinkedDiagnostic(
                        record = record,
                        markerKey = markerKey,
                        triggerAtMillis = triggerAtMillis,
                        title = title,
                        hasDuplicateKey = markerKey.persistedValue in duplicateKeys
                    )
                }
                .sortedWith(alarmDiagnosticComparator)
        )
    }

    private fun attachUnmatchedAlarms(
        events: List<CalendarEventDiagnostic>,
        alarms: List<Alarm>,
        matchedAlarmIds: Set<Int>,
        duplicateKeys: Set<String>,
    ): Pair<List<CalendarEventDiagnostic>, List<CalendarAlarmDiagnostic>> {
        val eventKeys = events.mapTo(mutableSetOf()) { it.key }
        val markerMissingByOccurrence =
            mutableMapOf<CalendarOccurrenceKey, MutableList<CalendarAlarmDiagnostic>>()
        val eventMissingAlarms = mutableListOf<CalendarAlarmDiagnostic>()
        alarms.filterNot { it.id in matchedAlarmIds }.forEach { alarm ->
            val occurrenceKey = CalendarOccurrenceKey(
                eventId = alarm.calendarEventId,
                beginMillis = alarm.calendarEventStartMillis
            )
            val linkStatus = if (occurrenceKey in eventKeys) {
                CalendarAlarmLinkStatus.MARKER_MISSING
            } else {
                CalendarAlarmLinkStatus.EVENT_MISSING
            }
            val diagnostic = CalendarAlarmDiagnostic(
                alarm = alarm,
                linkStatus = linkStatus,
                hasDuplicateKey = alarm.calendarKey in duplicateKeys
            )
            if (linkStatus == CalendarAlarmLinkStatus.MARKER_MISSING) {
                markerMissingByOccurrence.getOrPut(occurrenceKey, ::mutableListOf).add(diagnostic)
            } else {
                eventMissingAlarms.add(diagnostic)
            }
        }
        val eventsWithMissingMarkers = events.map { event ->
            event.copy(
                markerMissingAlarms = markerMissingByOccurrence[event.key]
                    .orEmpty()
                    .sortedWith(alarmDiagnosticComparator)
            )
        }
        return eventsWithMissingMarkers to eventMissingAlarms
    }

    private fun markerParseState(
        description: String,
        offsets: Set<Int>,
    ): CalendarMarkerParseState {
        return when {
            offsets.isNotEmpty() -> CalendarMarkerParseState.VALID
            markerMentionPattern.containsMatchIn(description) ->
                CalendarMarkerParseState.INVALID_MENTION
            else -> CalendarMarkerParseState.NONE
        }
    }

    private fun markerDisposition(
        record: CalendarEventRecord,
        offsetMinutes: Int,
        triggerAtMillis: Long,
        window: CalendarAlarmWindow.Range,
    ): CalendarMarkerDisposition {
        return when {
            record.isAllDay -> CalendarMarkerDisposition.ALL_DAY_EVENT
            record.isCanceled -> CalendarMarkerDisposition.CANCELED_EVENT
            !CalendarAlarmWindow.supportsOffset(offsetMinutes) ->
                CalendarMarkerDisposition.UNSUPPORTED_OFFSET
            triggerAtMillis <= window.triggerBeginMillis ->
                CalendarMarkerDisposition.TRIGGER_NOT_FUTURE
            triggerAtMillis > window.triggerEndMillis ->
                CalendarMarkerDisposition.TRIGGER_AFTER_WINDOW
            else -> CalendarMarkerDisposition.ELIGIBLE
        }
    }

    private fun Alarm.toLinkedDiagnostic(
        record: CalendarEventRecord,
        markerKey: CalendarAlarmKey,
        triggerAtMillis: Long,
        title: String,
        hasDuplicateKey: Boolean,
    ): CalendarAlarmDiagnostic {
        val driftFields = buildSet {
            if (calendarEventId != record.eventId) {
                add(CalendarAlarmDriftField.EVENT_ID)
            }
            if (calendarEventStartMillis != record.beginMillis) {
                add(CalendarAlarmDriftField.EVENT_START)
            }
            if (calendarOffsetMinutes != markerKey.offsetMinutes) {
                add(CalendarAlarmDriftField.OFFSET)
            }
            if (this@toLinkedDiagnostic.triggerAtMillis != triggerAtMillis) {
                add(CalendarAlarmDriftField.TRIGGER)
            }
            if (label != title) {
                add(CalendarAlarmDriftField.LABEL)
            }
            if (days != 0) {
                add(CalendarAlarmDriftField.DAYS)
            }
            if (!oneShot) {
                add(CalendarAlarmDriftField.ONE_SHOT)
            }
            if (!isEnabled) {
                add(CalendarAlarmDriftField.ENABLED)
            }
        }
        return CalendarAlarmDiagnostic(
            alarm = this,
            linkStatus = if (driftFields.isEmpty()) {
                CalendarAlarmLinkStatus.EXACT
            } else {
                CalendarAlarmLinkStatus.METADATA_DRIFT
            },
            driftFields = driftFields,
            hasDuplicateKey = hasDuplicateKey
        )
    }

    private fun CalendarEventRecord.overlapsDisplayWindow(
        window: CalendarAlarmWindow.Range,
    ): Boolean {
        return endMillis >= window.triggerBeginMillis &&
            beginMillis <= window.triggerEndMillis
    }

    private fun Long.isInTriggerWindow(window: CalendarAlarmWindow.Range): Boolean {
        return this > window.triggerBeginMillis && this <= window.triggerEndMillis
    }

    private fun CalendarEventDiagnostic.shouldBeIncluded(
        window: CalendarAlarmWindow.Range,
    ): Boolean {
        return isInDisplayWindow ||
            markers.any { marker -> marker.triggerAtMillis.isInTriggerWindow(window) } ||
            alarms.isNotEmpty()
    }

    private fun createSnapshot(
        capturedAtMillis: Long,
        window: CalendarAlarmWindow.Range,
        providerState: CalendarDiagnosticsProviderState,
        events: List<CalendarEventDiagnostic>,
        unlinkedAlarms: List<CalendarAlarmDiagnostic>,
        totalAlarmCount: Int,
    ): CalendarDiagnosticsSnapshot {
        val allAlarmDiagnostics = events.flatMap { it.alarms } + unlinkedAlarms
        return CalendarDiagnosticsSnapshot(
            capturedAtMillis = capturedAtMillis,
            displayBeginMillis = window.triggerBeginMillis,
            displayEndMillis = window.triggerEndMillis,
            queryBeginMillis = window.queryBeginMillis,
            queryEndMillis = window.queryEndMillis,
            providerState = providerState,
            events = events,
            unlinkedAlarms = unlinkedAlarms,
            counts = CalendarDiagnosticsCounts(
                displayWindowEvents = events.count { it.isInDisplayWindow },
                relatedEventsOutsideWindow = events.count { !it.isInDisplayWindow },
                calendarAlarms = totalAlarmCount,
                exactAlarms = allAlarmDiagnostics.count {
                    it.linkStatus == CalendarAlarmLinkStatus.EXACT
                },
                driftedAlarms = allAlarmDiagnostics.count {
                    it.linkStatus == CalendarAlarmLinkStatus.METADATA_DRIFT
                },
                duplicateAlarms = allAlarmDiagnostics.count { it.hasDuplicateKey },
                eligibleMarkersWithoutAlarm = events
                    .flatMap { it.markers }
                    .count {
                        it.disposition == CalendarMarkerDisposition.ELIGIBLE &&
                            it.alarms.isEmpty()
                    },
                markerMissingAlarms = allAlarmDiagnostics.count {
                    it.linkStatus == CalendarAlarmLinkStatus.MARKER_MISSING
                },
                eventMissingAlarms = allAlarmDiagnostics.count {
                    it.linkStatus == CalendarAlarmLinkStatus.EVENT_MISSING
                },
                invalidMarkerEvents = events.count {
                    it.markerParseState == CalendarMarkerParseState.INVALID_MENTION
                },
                unverifiableAlarms = allAlarmDiagnostics.count {
                    it.linkStatus == CalendarAlarmLinkStatus.UNVERIFIABLE
                }
            )
        )
    }

    private val alarmDiagnosticComparator =
        compareBy<CalendarAlarmDiagnostic> { it.alarm.triggerAtMillis }
            .thenBy { it.alarm.id }

    private val markerDiagnosticComparator =
        compareBy<CalendarMarkerDiagnostic> { it.triggerAtMillis }
            .thenBy { it.key.offsetMinutes }

    private val eventDiagnosticComparator =
        compareBy<CalendarEventDiagnostic> { it.beginMillis }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.key.eventId }
}
