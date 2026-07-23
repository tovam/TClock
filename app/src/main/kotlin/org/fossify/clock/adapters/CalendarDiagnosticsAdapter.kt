package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.databinding.ItemCalendarDiagnosticsAlarmBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsDayBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEventBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsSectionBinding
import org.fossify.clock.helpers.CalendarAlarmDiagnostic
import org.fossify.clock.helpers.CalendarAlarmLinkStatus
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarEventDiagnostic
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

        data class Event(
            val diagnostic: CalendarEventDiagnostic,
            val plannedAlarmCount: Int,
        ) : Row

        data class Alarm(
            val diagnostic: CalendarAlarmDiagnostic,
            val event: CalendarEventDiagnostic?,
        ) : Row
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
            is Row.Event -> (holder as EventViewHolder).bind(row)
            is Row.Alarm -> (holder as AlarmViewHolder).bind(row)
        }
    }

    private fun buildRows(snapshot: CalendarDiagnosticsSnapshot): List<Row> = buildList {
        val plannedAlarms = buildList {
            snapshot.events.forEach { event ->
                event.alarms.forEach { diagnostic ->
                    add(Row.Alarm(diagnostic, event))
                }
            }
            snapshot.unlinkedAlarms.forEach { diagnostic ->
                add(Row.Alarm(diagnostic, null))
            }
        }.filter { row ->
            row.diagnostic.alarm.isEnabled &&
                row.diagnostic.alarm.oneShot &&
                row.diagnostic.alarm.triggerAtMillis > snapshot.capturedAtMillis
        }.sortedWith(
            compareBy<Row.Alarm> { it.diagnostic.alarm.triggerAtMillis }
                .thenBy { it.diagnostic.alarm.id }
        )
        val plannedAlarmIds = plannedAlarms.mapTo(mutableSetOf()) {
            it.diagnostic.alarm.id
        }

        add(
            Row.Section(
                title = context.getString(
                    R.string.calendar_diagnostics_planned_alarms_section,
                    plannedAlarms.size
                ),
                details = context.getString(
                    if (plannedAlarms.isEmpty()) {
                        R.string.calendar_diagnostics_no_planned_alarms
                    } else {
                        R.string.calendar_diagnostics_planned_alarms_explanation
                    }
                )
            )
        )
        appendAlarmRows(plannedAlarms)

        val displayWindowEvents = snapshot.events.filter { it.isInDisplayWindow }
        val relatedEventsOutsideWindow = snapshot.events.filterNot { it.isInDisplayWindow }
        appendEventSection(
            title = context.getString(
                R.string.calendar_diagnostics_window_events_section,
                displayWindowEvents.size
            ),
            details = context.getString(
                R.string.calendar_diagnostics_compact_events_explanation
            ),
            events = displayWindowEvents,
            plannedAlarmIds = plannedAlarmIds
        )
        appendEventSection(
            title = context.getString(
                R.string.calendar_diagnostics_related_events_section,
                relatedEventsOutsideWindow.size
            ),
            details = context.getString(
                R.string.calendar_diagnostics_related_events_explanation
            ),
            events = relatedEventsOutsideWindow,
            plannedAlarmIds = plannedAlarmIds
        )
    }

    private fun MutableList<Row>.appendAlarmRows(alarms: List<Row.Alarm>) {
        var previousDay: Long? = null
        alarms.forEach { alarmRow ->
            val triggerAtMillis = alarmRow.diagnostic.alarm.triggerAtMillis
            val day = alarmDayKey(triggerAtMillis)
            if (day != previousDay) {
                add(Row.Day(formatDay(triggerAtMillis, false)))
                previousDay = day
            }
            add(alarmRow)
        }
    }

    private fun MutableList<Row>.appendEventSection(
        title: String,
        details: String,
        events: List<CalendarEventDiagnostic>,
        plannedAlarmIds: Set<Int>,
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
            add(
                Row.Event(
                    diagnostic = event,
                    plannedAlarmCount = event.alarms.count {
                        it.alarm.id in plannedAlarmIds
                    }
                )
            )
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
        fun bind(row: Row.Event) = binding.apply {
            val event = row.diagnostic
            val accentColor = event.displayColor ?: primaryColor
            root.setCardBackgroundColor(backgroundColor)
            root.strokeColor = accentColor
            calendarDiagnosticsEventColor.setBackgroundColor(accentColor)
            calendarDiagnosticsEventTitle.apply {
                text = event.title
                setTextColor(textColor)
            }
            calendarDiagnosticsEventTime.apply {
                text = formatEventRange(event)
                setTextColor(textColor)
            }
            calendarDiagnosticsEventAlarmCount.apply {
                text = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_alarm_count,
                    row.plannedAlarmCount,
                    row.plannedAlarmCount
                )
                setTextColor(textColor)
            }
        }
    }

    private inner class AlarmViewHolder(
        private val binding: ItemCalendarDiagnosticsAlarmBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Alarm) = binding.apply {
            val diagnostic = row.diagnostic
            val alarm = diagnostic.alarm
            val accentColor = row.event?.displayColor ?: primaryColor
            root.setCardBackgroundColor(backgroundColor)
            root.strokeColor = accentColor
            calendarDiagnosticsAlarmColor.setBackgroundColor(accentColor)
            calendarDiagnosticsAlarmTitle.apply {
                text = row.event?.title ?: alarm.label.ifBlank {
                    context.getString(R.string.unnamed_alarm)
                }
                setTextColor(textColor)
            }
            calendarDiagnosticsAlarmTrigger.apply {
                text = formatDateTime(alarm.triggerAtMillis)
                setTextColor(textColor)
            }
            calendarDiagnosticsAlarmDetails.apply {
                text = buildList {
                    add(formatOffset(alarm.calendarOffsetMinutes))
                    row.event?.let { event ->
                        add(
                            context.getString(
                                R.string.calendar_diagnostics_event_starts,
                                formatDateTime(event.beginMillis)
                            )
                        )
                    }
                }.joinToString(SEPARATOR)
                setTextColor(textColor)
            }
            calendarDiagnosticsAlarmWarning.apply {
                text = buildList {
                    if (diagnostic.linkStatus != CalendarAlarmLinkStatus.EXACT) {
                        add(context.getString(diagnostic.linkStatus.labelResource()))
                    }
                    if (diagnostic.hasDuplicateKey) {
                        add(context.getString(R.string.calendar_diagnostics_duplicate_short))
                    }
                }.joinToString(SEPARATOR)
                setTextColor(textColor)
                beVisibleIf(text.isNotBlank())
            }
        }
    }

    private fun formatEventRange(event: CalendarEventDiagnostic): String {
        if (!event.isAllDay) {
            val start = formatDateTime(event.beginMillis)
            return if (event.endMillis > event.beginMillis) {
                val zone = ZoneId.systemDefault()
                val startDay = Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalDate()
                val endDay = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
                val end = if (startDay == endDay) {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.endMillis))
                } else {
                    formatDateTime(event.endMillis)
                }
                "$start – $end"
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
        return Instant.ofEpochMilli(triggerAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
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

    private companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_DAY = 1
        const val VIEW_TYPE_EVENT = 2
        const val VIEW_TYPE_ALARM = 3
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        const val SEPARATOR = " · "
    }
}
