package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.databinding.ItemCalendarDiagnosticsAlarmBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsDayBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEventBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsMarkerBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsSectionBinding
import org.fossify.clock.helpers.CalendarAlarmDiagnostic
import org.fossify.clock.helpers.CalendarAlarmDriftField
import org.fossify.clock.helpers.CalendarAlarmLinkStatus
import org.fossify.clock.helpers.CalendarDiagnosticsProviderState
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarEventDiagnostic
import org.fossify.clock.helpers.CalendarMarkerDiagnostic
import org.fossify.clock.helpers.CalendarMarkerDisposition
import org.fossify.clock.helpers.CalendarMarkerParseState
import org.fossify.commons.extensions.beVisibleIf
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import kotlin.math.absoluteValue

class CalendarDiagnosticsAdapter(
    private val context: Context,
    private var textColor: Int,
    private var backgroundColor: Int,
    private var primaryColor: Int,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data class Section(
            val title: String,
            val details: String,
        ) : Row

        data class Day(val title: String) : Row

        data class Event(val diagnostic: CalendarEventDiagnostic) : Row

        data class Alarm(val diagnostic: CalendarAlarmDiagnostic) : Row
    }

    private var rows: List<Row> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitSnapshot(snapshot: CalendarDiagnosticsSnapshot) {
        rows = buildRows(snapshot)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateColors(
        newTextColor: Int,
        newBackgroundColor: Int,
        newPrimaryColor: Int,
    ) {
        textColor = newTextColor
        backgroundColor = newBackgroundColor
        primaryColor = newPrimaryColor
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Day -> VIEW_TYPE_DAY
            is Row.Event -> VIEW_TYPE_EVENT
            is Row.Alarm -> VIEW_TYPE_ALARM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionViewHolder(
                ItemCalendarDiagnosticsSectionBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_DAY -> DayViewHolder(
                ItemCalendarDiagnosticsDayBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EVENT -> EventViewHolder(
                ItemCalendarDiagnosticsEventBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_ALARM -> AlarmViewHolder(
                ItemCalendarDiagnosticsAlarmBinding.inflate(inflater, parent, false)
            )

            else -> error("Unknown calendar diagnostics row type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionViewHolder).bind(row)
            is Row.Day -> (holder as DayViewHolder).bind(row)
            is Row.Event -> (holder as EventViewHolder).bind(row.diagnostic)
            is Row.Alarm -> (holder as AlarmViewHolder).bind(row.diagnostic)
        }
    }

    private fun buildRows(snapshot: CalendarDiagnosticsSnapshot): List<Row> = buildList {
        val displayWindowEvents = snapshot.events.filter { it.isInDisplayWindow }
        val relatedEventsOutsideWindow = snapshot.events.filterNot { it.isInDisplayWindow }

        appendEventSection(
            title = context.getString(
                R.string.calendar_diagnostics_window_events_section,
                displayWindowEvents.size
            ),
            details = context.getString(R.string.calendar_diagnostics_window_events_explanation),
            events = displayWindowEvents
        )
        appendEventSection(
            title = context.getString(
                R.string.calendar_diagnostics_related_events_section,
                relatedEventsOutsideWindow.size
            ),
            details = context.getString(R.string.calendar_diagnostics_related_events_explanation),
            events = relatedEventsOutsideWindow
        )

        if (snapshot.unlinkedAlarms.isNotEmpty()) {
            val providerUnavailable =
                snapshot.providerState != CalendarDiagnosticsProviderState.AVAILABLE
            add(
                Row.Section(
                    title = context.getString(
                        if (providerUnavailable) {
                            R.string.calendar_diagnostics_unverifiable_alarms_section
                        } else {
                            R.string.calendar_diagnostics_unlinked_alarms_section
                        },
                        snapshot.unlinkedAlarms.size
                    ),
                    details = context.getString(
                        if (providerUnavailable) {
                            R.string.calendar_diagnostics_unverifiable_alarms_explanation
                        } else {
                            R.string.calendar_diagnostics_unlinked_alarms_explanation
                        }
                    )
                )
            )
            var previousDay: Long? = null
            snapshot.unlinkedAlarms.forEach { diagnostic ->
                val triggerAtMillis = diagnostic.alarm.triggerAtMillis
                val day = alarmDayKey(triggerAtMillis)
                if (day != previousDay) {
                    add(
                        Row.Day(
                            if (triggerAtMillis > 0L) {
                                formatDay(triggerAtMillis, false)
                            } else {
                                context.getString(R.string.calendar_diagnostics_unknown_date)
                            }
                        )
                    )
                    previousDay = day
                }
                add(Row.Alarm(diagnostic))
            }
        }
    }

    private fun MutableList<Row>.appendEventSection(
        title: String,
        details: String,
        events: List<CalendarEventDiagnostic>,
    ) {
        if (events.isEmpty()) {
            return
        }
        add(Row.Section(title, details))
        var previousDay: Long? = null
        events.forEach { event ->
            val day = eventDayKey(event)
            if (day != previousDay) {
                add(Row.Day(formatDay(event.beginMillis, event.isAllDay)))
                previousDay = day
            }
            add(Row.Event(event))
        }
    }

    private inner class SectionViewHolder(
        private val binding: ItemCalendarDiagnosticsSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Section) {
            binding.calendarDiagnosticsSectionTitle.apply {
                text = row.title
                setTextColor(primaryColor)
            }
            binding.calendarDiagnosticsSectionDetails.apply {
                text = row.details
                setTextColor(textColor)
            }
        }
    }

    private inner class DayViewHolder(
        private val binding: ItemCalendarDiagnosticsDayBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Day) {
            binding.calendarDiagnosticsDay.apply {
                text = row.title
                setTextColor(textColor)
            }
        }
    }

    private inner class EventViewHolder(
        private val binding: ItemCalendarDiagnosticsEventBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(event: CalendarEventDiagnostic) = binding.apply {
            root.setCardBackgroundColor(backgroundColor)
            root.strokeColor = event.displayColor ?: primaryColor
            calendarDiagnosticsEventColor.setBackgroundColor(event.displayColor ?: primaryColor)

            calendarDiagnosticsEventTitle.apply {
                text = event.title
                setTextColor(textColor)
            }
            calendarDiagnosticsEventCalendar.apply {
                text = if (event.calendarDisplayName.isBlank()) {
                    context.getString(
                        R.string.calendar_diagnostics_calendar_fallback,
                        event.calendarId
                    )
                } else {
                    context.getString(
                        R.string.calendar_diagnostics_calendar_name,
                        event.calendarDisplayName,
                        event.calendarId
                    )
                }
                setTextColor(textColor)
            }
            calendarDiagnosticsEventTime.apply {
                text = formatEventRange(event)
                setTextColor(textColor)
            }
            calendarDiagnosticsEventFlags.apply {
                text = formatEventFlags(event)
                setTextColor(textColor)
            }
            calendarDiagnosticsEventMarkerState.apply {
                text = formatMarkerParseState(event)
                setTextColor(textColor)
            }
            calendarDiagnosticsEventTechnical.apply {
                text = context.getString(
                    R.string.calendar_diagnostics_event_technical,
                    event.key.eventId,
                    formatDateTime(event.key.beginMillis)
                )
                setTextColor(textColor)
            }

            val hasDescription = event.description.isNotBlank()
            calendarDiagnosticsEventDescriptionHolder.beVisibleIf(hasDescription)
            calendarDiagnosticsEventDescriptionLabel.setTextColor(textColor)
            calendarDiagnosticsEventDescription.setTextColor(textColor)
            calendarDiagnosticsEventDescription.text = event.description

            calendarDiagnosticsEventRelations.removeAllViews()
            calendarDiagnosticsEventRelationsLabel.setTextColor(textColor)
            event.markers.forEach { marker ->
                addMarker(calendarDiagnosticsEventRelations, marker)
            }
            if (event.markerMissingAlarms.isNotEmpty()) {
                addMarkerMissingAlarms(
                    calendarDiagnosticsEventRelations,
                    event.markerMissingAlarms
                )
            }
            calendarDiagnosticsEventRelationsHolder.beVisibleIf(
                event.markers.isNotEmpty() || event.markerMissingAlarms.isNotEmpty()
            )
        }

        private fun addMarker(parent: ViewGroup, marker: CalendarMarkerDiagnostic) {
            val markerBinding = ItemCalendarDiagnosticsMarkerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            markerBinding.apply {
                calendarDiagnosticsMarkerTitle.apply {
                    text = formatOffset(marker.key.offsetMinutes)
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerDisposition.apply {
                    text = context.getString(marker.disposition.labelResource())
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerTrigger.apply {
                    text = context.getString(
                        R.string.calendar_diagnostics_expected_trigger,
                        formatDateTime(marker.triggerAtMillis)
                    )
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerKey.apply {
                    text = context.getString(
                        R.string.calendar_diagnostics_marker_key,
                        marker.key.persistedValue
                    )
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerNoAlarm.apply {
                    text = context.getString(
                        if (marker.disposition == CalendarMarkerDisposition.ELIGIBLE) {
                            R.string.calendar_diagnostics_linked_alarm_missing
                        } else {
                            R.string.calendar_diagnostics_alarm_not_expected
                        }
                    )
                    setTextColor(textColor)
                    beVisibleIf(marker.alarms.isEmpty())
                }
                calendarDiagnosticsMarkerAlarms.removeAllViews()
                marker.alarms.forEach { diagnostic ->
                    addAlarm(
                        parent = calendarDiagnosticsMarkerAlarms,
                        diagnostic = diagnostic,
                        expectedTriggerAtMillis = marker.triggerAtMillis
                    )
                }
            }
            parent.addView(markerBinding.root)
        }

        private fun addMarkerMissingAlarms(
            parent: ViewGroup,
            alarms: List<CalendarAlarmDiagnostic>,
        ) {
            val markerBinding = ItemCalendarDiagnosticsMarkerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            markerBinding.apply {
                calendarDiagnosticsMarkerTitle.apply {
                    text = context.getString(
                        R.string.calendar_diagnostics_marker_missing_group
                    )
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerDisposition.apply {
                    text = context.getString(
                        R.string.calendar_diagnostics_marker_missing_explanation
                    )
                    setTextColor(textColor)
                }
                calendarDiagnosticsMarkerTrigger.visibility = View.GONE
                calendarDiagnosticsMarkerKey.visibility = View.GONE
                calendarDiagnosticsMarkerNoAlarm.visibility = View.GONE
                calendarDiagnosticsMarkerAlarms.removeAllViews()
                alarms.forEach { diagnostic ->
                    addAlarm(calendarDiagnosticsMarkerAlarms, diagnostic, null)
                }
            }
            parent.addView(markerBinding.root)
        }
    }

    private inner class AlarmViewHolder(
        private val binding: ItemCalendarDiagnosticsAlarmBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(diagnostic: CalendarAlarmDiagnostic) {
            bindAlarm(binding, diagnostic, null)
        }
    }

    private fun addAlarm(
        parent: ViewGroup,
        diagnostic: CalendarAlarmDiagnostic,
        expectedTriggerAtMillis: Long?,
    ) {
        val alarmBinding = ItemCalendarDiagnosticsAlarmBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        bindAlarm(alarmBinding, diagnostic, expectedTriggerAtMillis)
        parent.addView(alarmBinding.root)
    }

    private fun bindAlarm(
        binding: ItemCalendarDiagnosticsAlarmBinding,
        diagnostic: CalendarAlarmDiagnostic,
        expectedTriggerAtMillis: Long?,
    ) = binding.apply {
        val alarm = diagnostic.alarm
        root.setCardBackgroundColor(backgroundColor)
        root.strokeColor = primaryColor

        calendarDiagnosticsAlarmTitle.apply {
            text = context.getString(
                R.string.calendar_diagnostics_alarm_title,
                alarm.id,
                context.getString(diagnostic.linkStatus.labelResource())
            )
            setTextColor(textColor)
        }
        calendarDiagnosticsAlarmTrigger.apply {
            text = if (alarm.triggerAtMillis > 0L) {
                context.getString(
                    R.string.calendar_diagnostics_stored_trigger,
                    formatDateTime(alarm.triggerAtMillis)
                )
            } else {
                context.getString(R.string.calendar_diagnostics_no_stored_trigger)
            }
            setTextColor(textColor)
        }
        calendarDiagnosticsAlarmState.apply {
            text = context.getString(
                R.string.calendar_diagnostics_alarm_state,
                context.getString(
                    if (alarm.isEnabled) {
                        R.string.calendar_diagnostics_enabled
                    } else {
                        R.string.calendar_diagnostics_disabled
                    }
                ),
                context.getString(
                    if (alarm.oneShot) {
                        R.string.calendar_diagnostics_one_shot
                    } else {
                        R.string.calendar_diagnostics_not_one_shot
                    }
                )
            )
            setTextColor(textColor)
        }
        calendarDiagnosticsAlarmLabel.apply {
            text = context.getString(
                R.string.calendar_diagnostics_stored_label,
                alarm.label.ifBlank {
                    context.getString(R.string.unnamed_alarm)
                }
            )
            setTextColor(textColor)
        }
        calendarDiagnosticsAlarmDrift.apply {
            text = formatDrift(diagnostic, expectedTriggerAtMillis)
            setTextColor(textColor)
            beVisibleIf(diagnostic.driftFields.isNotEmpty())
        }
        calendarDiagnosticsAlarmDuplicate.apply {
            text = context.getString(R.string.calendar_diagnostics_duplicate_alarm)
            setTextColor(textColor)
            beVisibleIf(diagnostic.hasDuplicateKey)
        }
        calendarDiagnosticsAlarmMetadata.apply {
            text = context.getString(
                R.string.calendar_diagnostics_alarm_metadata,
                alarm.calendarEventId,
                if (alarm.calendarEventStartMillis > 0L) {
                    formatDateTime(alarm.calendarEventStartMillis)
                } else {
                    context.getString(R.string.calendar_diagnostics_unknown_date)
                },
                alarm.calendarOffsetMinutes,
                alarm.days
            )
            setTextColor(textColor)
        }
        calendarDiagnosticsAlarmKey.apply {
            text = context.getString(
                R.string.calendar_diagnostics_alarm_key,
                alarm.calendarKey.ifBlank {
                    context.getString(R.string.calendar_diagnostics_empty_key)
                }
            )
            setTextColor(textColor)
        }
    }

    private fun formatMarkerParseState(event: CalendarEventDiagnostic): String {
        return when (event.markerParseState) {
            CalendarMarkerParseState.NONE ->
                context.getString(R.string.calendar_diagnostics_marker_none)

            CalendarMarkerParseState.INVALID_MENTION ->
                context.getString(R.string.calendar_diagnostics_marker_invalid)

            CalendarMarkerParseState.VALID ->
                context.getString(
                    R.string.calendar_diagnostics_marker_valid,
                    event.markers.size
                )
        }
    }

    private fun formatEventFlags(event: CalendarEventDiagnostic): String {
        val flags = buildList {
            add(
                context.getString(
                    if (event.isInDisplayWindow) {
                        R.string.calendar_diagnostics_in_window
                    } else {
                        R.string.calendar_diagnostics_outside_window
                    }
                )
            )
            if (event.isAllDay) {
                add(context.getString(R.string.calendar_diagnostics_all_day))
            }
            if (event.isCanceled) {
                add(context.getString(R.string.calendar_diagnostics_canceled))
            }
        }
        return flags.joinToString(SEPARATOR)
    }

    private fun formatEventRange(event: CalendarEventDiagnostic): String {
        if (!event.isAllDay) {
            val start = formatDateTime(event.beginMillis)
            return if (event.endMillis > event.beginMillis) {
                "$start – ${formatDateTime(event.endMillis)}"
            } else {
                start
            }
        }

        val start = formatDate(event.beginMillis, true)
        val inclusiveEndMillis = if (event.endMillis > event.beginMillis) {
            event.endMillis - 1L
        } else {
            event.beginMillis
        }
        val end = formatDate(inclusiveEndMillis, true)
        return if (start == end) start else "$start – $end"
    }

    private fun formatOffset(offsetMinutes: Int): String {
        return when {
            offsetMinutes < 0 -> context.getString(
                R.string.calendar_diagnostics_offset_before,
                formatMinutes(offsetMinutes.toLong().absoluteValue)
            )

            offsetMinutes > 0 -> context.getString(
                R.string.calendar_diagnostics_offset_after,
                formatMinutes(offsetMinutes.toLong())
            )

            else -> context.getString(R.string.calendar_diagnostics_offset_at_start)
        }
    }

    private fun formatDrift(
        diagnostic: CalendarAlarmDiagnostic,
        expectedTriggerAtMillis: Long?,
    ): String {
        val fields = diagnostic.driftFields
            .sortedBy { it.ordinal }
            .joinToString(", ") { context.getString(it.labelResource()) }
        val lines = mutableListOf(
            context.getString(R.string.calendar_diagnostics_drift_fields, fields)
        )
        if (
            expectedTriggerAtMillis != null &&
            CalendarAlarmDriftField.TRIGGER in diagnostic.driftFields
        ) {
            val delta = diagnostic.alarm.triggerAtMillis - expectedTriggerAtMillis
            lines.add(
                context.getString(
                    R.string.calendar_diagnostics_trigger_drift,
                    formatSignedDuration(delta)
                )
            )
        }
        return lines.joinToString("\n")
    }

    private fun formatSignedDuration(durationMillis: Long): String {
        val prefix = when {
            durationMillis > 0L -> "+"
            durationMillis < 0L -> "−"
            else -> ""
        }
        val absoluteSeconds = maxOf(1L, durationMillis.absoluteValue / 1_000L)
        val value = when {
            absoluteSeconds % SECONDS_PER_DAY == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_days_short,
                    absoluteSeconds / SECONDS_PER_DAY
                )

            absoluteSeconds % SECONDS_PER_HOUR == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_hours_short,
                    absoluteSeconds / SECONDS_PER_HOUR
                )

            absoluteSeconds % SECONDS_PER_MINUTE == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_minutes_short,
                    absoluteSeconds / SECONDS_PER_MINUTE
                )

            else -> context.getString(
                R.string.calendar_diagnostics_seconds_short,
                absoluteSeconds
            )
        }
        return "$prefix$value"
    }

    private fun formatMinutes(minutes: Long): String {
        return when {
            minutes % MINUTES_PER_DAY == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_days_short,
                    minutes / MINUTES_PER_DAY
                )

            minutes % MINUTES_PER_HOUR == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_hours_short,
                    minutes / MINUTES_PER_HOUR
                )

            else -> context.getString(R.string.calendar_diagnostics_minutes_short, minutes)
        }
    }

    private fun formatDateTime(timestamp: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(timestamp))
    }

    private fun formatDate(timestamp: Long, allDay: Boolean): String {
        return DateFormat.getDateInstance(DateFormat.FULL).apply {
            if (allDay) {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }.format(Date(timestamp))
    }

    private fun formatDay(timestamp: Long, allDay: Boolean): String {
        return formatDate(timestamp, allDay)
    }

    private fun eventDayKey(event: CalendarEventDiagnostic): Long {
        val zone = if (event.isAllDay) ZoneOffset.UTC else ZoneId.systemDefault()
        return Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalDate().toEpochDay()
    }

    private fun alarmDayKey(triggerAtMillis: Long): Long {
        if (triggerAtMillis <= 0L) {
            return Long.MIN_VALUE
        }
        return Instant.ofEpochMilli(triggerAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
    }

    private fun CalendarMarkerDisposition.labelResource(): Int {
        return when (this) {
            CalendarMarkerDisposition.ELIGIBLE ->
                R.string.calendar_diagnostics_disposition_eligible

            CalendarMarkerDisposition.ALL_DAY_EVENT ->
                R.string.calendar_diagnostics_disposition_all_day

            CalendarMarkerDisposition.CANCELED_EVENT ->
                R.string.calendar_diagnostics_disposition_canceled

            CalendarMarkerDisposition.UNSUPPORTED_OFFSET ->
                R.string.calendar_diagnostics_disposition_unsupported

            CalendarMarkerDisposition.TRIGGER_NOT_FUTURE ->
                R.string.calendar_diagnostics_disposition_past

            CalendarMarkerDisposition.TRIGGER_AFTER_WINDOW ->
                R.string.calendar_diagnostics_disposition_after_window
        }
    }

    private fun CalendarAlarmLinkStatus.labelResource(): Int {
        return when (this) {
            CalendarAlarmLinkStatus.EXACT -> R.string.calendar_diagnostics_link_exact
            CalendarAlarmLinkStatus.METADATA_DRIFT ->
                R.string.calendar_diagnostics_link_drift

            CalendarAlarmLinkStatus.MARKER_MISSING ->
                R.string.calendar_diagnostics_link_marker_missing

            CalendarAlarmLinkStatus.EVENT_MISSING ->
                R.string.calendar_diagnostics_link_event_missing

            CalendarAlarmLinkStatus.UNVERIFIABLE ->
                R.string.calendar_diagnostics_link_unverifiable
        }
    }

    private fun CalendarAlarmDriftField.labelResource(): Int {
        return when (this) {
            CalendarAlarmDriftField.EVENT_ID ->
                R.string.calendar_diagnostics_drift_event_id

            CalendarAlarmDriftField.EVENT_START ->
                R.string.calendar_diagnostics_drift_event_start

            CalendarAlarmDriftField.OFFSET ->
                R.string.calendar_diagnostics_drift_offset

            CalendarAlarmDriftField.TRIGGER ->
                R.string.calendar_diagnostics_drift_trigger

            CalendarAlarmDriftField.LABEL ->
                R.string.calendar_diagnostics_drift_label

            CalendarAlarmDriftField.DAYS ->
                R.string.calendar_diagnostics_drift_days

            CalendarAlarmDriftField.ONE_SHOT ->
                R.string.calendar_diagnostics_drift_one_shot

            CalendarAlarmDriftField.ENABLED ->
                R.string.calendar_diagnostics_drift_enabled
        }
    }

    private companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_DAY = 1
        const val VIEW_TYPE_EVENT = 2
        const val VIEW_TYPE_ALARM = 3
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
        const val SEPARATOR = " · "
    }
}
