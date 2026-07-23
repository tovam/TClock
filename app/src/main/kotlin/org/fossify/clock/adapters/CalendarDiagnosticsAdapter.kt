package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.databinding.ItemCalendarDiagnosticsAlarmBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEmptyBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEventBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsFooterBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsOverviewBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsSectionBinding
import org.fossify.clock.helpers.CalendarAlarmDiagnostic
import org.fossify.clock.helpers.CalendarAlarmLinkStatus
import org.fossify.clock.helpers.CalendarDiagnosticsProviderState
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarEventDiagnostic
import org.fossify.clock.helpers.CalendarMarkerDisposition
import org.fossify.clock.helpers.CalendarMarkerParseState
import org.fossify.commons.extensions.beVisibleIf
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone
import kotlin.math.absoluteValue

class CalendarDiagnosticsAdapter(
    private val context: Context,
    private var textColor: Int,
    private var backgroundColor: Int,
    private var primaryColor: Int,
    private val onRefresh: () -> Unit,
    private val onGrantCalendarPermission: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data class Overview(
            val snapshot: CalendarDiagnosticsSnapshot,
            val plannedAlarmCount: Int,
            val syncFailed: Boolean,
            val issues: OverviewIssues,
        ) : Row

        data class Section(val title: String) : Row

        data class Event(
            val diagnostic: CalendarEventDiagnostic,
            val plannedAlarmCount: Int,
        ) : Row

        data class Alarm(
            val diagnostic: CalendarAlarmDiagnostic,
            val event: CalendarEventDiagnostic?,
        ) : Row

        data class Empty(val message: String) : Row

        data object Footer : Row
    }

    private enum class OverviewAction {
        REFRESH,
        GRANT_PERMISSION,
    }

    private data class OverviewStatus(
        val title: String,
        val details: String,
        val action: OverviewAction?,
    )

    private data class OverviewIssues(
        val eventMissingAlarms: Int,
        val syncableItems: Int,
        val invalidPatternEvents: Int,
        val duplicateAlarms: Int,
    )

    private var rows: List<Row> = emptyList()
    private var isRefreshing = false
    private var isFooterExpanded = false

    @SuppressLint("NotifyDataSetChanged")
    fun submitSnapshot(
        snapshot: CalendarDiagnosticsSnapshot,
        syncFailed: Boolean,
    ) {
        isRefreshing = false
        rows = buildRows(snapshot, syncFailed)
        notifyDataSetChanged()
    }

    fun setRefreshing(refreshing: Boolean) {
        if (isRefreshing == refreshing) {
            return
        }
        isRefreshing = refreshing
        val overviewIndex = rows.indexOfFirst { it is Row.Overview }
        if (overviewIndex >= 0) {
            notifyItemChanged(overviewIndex)
        }
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
            is Row.Overview -> VIEW_TYPE_OVERVIEW
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.Event -> VIEW_TYPE_EVENT
            is Row.Alarm -> VIEW_TYPE_ALARM
            is Row.Empty -> VIEW_TYPE_EMPTY
            Row.Footer -> VIEW_TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_OVERVIEW -> OverviewViewHolder(
                ItemCalendarDiagnosticsOverviewBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_SECTION -> SectionViewHolder(
                ItemCalendarDiagnosticsSectionBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EVENT -> EventViewHolder(
                ItemCalendarDiagnosticsEventBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_ALARM -> AlarmViewHolder(
                ItemCalendarDiagnosticsAlarmBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EMPTY -> EmptyViewHolder(
                ItemCalendarDiagnosticsEmptyBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_FOOTER -> FooterViewHolder(
                ItemCalendarDiagnosticsFooterBinding.inflate(inflater, parent, false)
            )

            else -> error("Unknown calendar diagnostics row type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Overview -> (holder as OverviewViewHolder).bind(row)
            is Row.Section -> (holder as SectionViewHolder).bind(row)
            is Row.Event -> (holder as EventViewHolder).bind(row)
            is Row.Alarm -> (holder as AlarmViewHolder).bind(row)
            is Row.Empty -> (holder as EmptyViewHolder).bind(row)
            Row.Footer -> (holder as FooterViewHolder).bind()
        }
    }

    private fun buildRows(
        snapshot: CalendarDiagnosticsSnapshot,
        syncFailed: Boolean,
    ): List<Row> = buildList {
        val plannedAlarms = collectPlannedAlarms(snapshot)
        val plannedAlarmIds = plannedAlarms.mapTo(mutableSetOf()) {
            it.diagnostic.alarm.id
        }
        add(
            Row.Overview(
                snapshot = snapshot,
                plannedAlarmCount = plannedAlarms.size,
                syncFailed = syncFailed,
                issues = buildOverviewIssues(snapshot, plannedAlarms)
            )
        )

        add(
            Row.Section(
                context.getString(
                    R.string.calendar_diagnostics_planned_alarms_section,
                    plannedAlarms.size
                )
            )
        )
        if (plannedAlarms.isEmpty()) {
            add(Row.Empty(context.getString(R.string.calendar_diagnostics_no_planned_alarms)))
        } else {
            addAll(plannedAlarms)
        }

        if (snapshot.providerState == CalendarDiagnosticsProviderState.AVAILABLE) {
            val displayWindowEvents = snapshot.events.filter { it.isInDisplayWindow }
            add(
                Row.Section(
                    context.getString(
                        R.string.calendar_diagnostics_window_events_section,
                        displayWindowEvents.size
                    )
                )
            )
            if (displayWindowEvents.isEmpty()) {
                add(Row.Empty(context.getString(R.string.calendar_diagnostics_empty)))
            } else {
                appendEventRows(displayWindowEvents, plannedAlarmIds)
            }

            val relatedEventsOutsideWindow = snapshot.events.filterNot { it.isInDisplayWindow }
            if (relatedEventsOutsideWindow.isNotEmpty()) {
                add(
                    Row.Section(
                        context.getString(
                            R.string.calendar_diagnostics_related_events_section,
                            relatedEventsOutsideWindow.size
                        )
                    )
                )
                appendEventRows(relatedEventsOutsideWindow, plannedAlarmIds)
            }
        }

        add(Row.Footer)
    }

    private fun collectPlannedAlarms(
        snapshot: CalendarDiagnosticsSnapshot,
    ): List<Row.Alarm> {
        return buildList {
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
    }

    private fun buildOverviewIssues(
        snapshot: CalendarDiagnosticsSnapshot,
        plannedAlarms: List<Row.Alarm>,
    ): OverviewIssues {
        return OverviewIssues(
            eventMissingAlarms = plannedAlarms.count {
                it.diagnostic.linkStatus == CalendarAlarmLinkStatus.EVENT_MISSING
            },
            syncableItems = plannedAlarms.count {
                it.diagnostic.linkStatus == CalendarAlarmLinkStatus.METADATA_DRIFT ||
                    it.diagnostic.linkStatus == CalendarAlarmLinkStatus.MARKER_MISSING
            } + snapshot.counts.eligibleMarkersWithoutAlarm,
            invalidPatternEvents = snapshot.events.count {
                it.markerParseState == CalendarMarkerParseState.INVALID_MENTION
            },
            duplicateAlarms = plannedAlarms.count {
                it.diagnostic.hasDuplicateKey
            }
        )
    }

    private fun MutableList<Row>.appendEventRows(
        events: List<CalendarEventDiagnostic>,
        plannedAlarmIds: Set<Int>,
    ) {
        events.forEach { event ->
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

    private inner class OverviewViewHolder(
        private val binding: ItemCalendarDiagnosticsOverviewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Overview) = binding.apply {
            val snapshot = row.snapshot
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsOverviewTitle.setTextColor(textColor)
            calendarDiagnosticsOverviewSummary.apply {
                val alarmSummary = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_planned_alarm_label,
                    row.plannedAlarmCount,
                    row.plannedAlarmCount
                )
                val eventSummary =
                    if (snapshot.providerState == CalendarDiagnosticsProviderState.AVAILABLE) {
                        context.resources.getQuantityString(
                            R.plurals.calendar_diagnostics_event_label,
                            snapshot.counts.displayWindowEvents,
                            snapshot.counts.displayWindowEvents
                        )
                    } else {
                        context.getString(
                            R.string.calendar_diagnostics_events_unavailable
                        )
                    }
                text = "$alarmSummary$SEPARATOR$eventSummary"
                setTextColor(textColor)
            }
            calendarDiagnosticsOverviewMeta.apply {
                text = context.getString(
                    R.string.calendar_diagnostics_overview_meta,
                    formatOverviewDate(snapshot.displayBeginMillis),
                    formatOverviewDate(snapshot.displayEndMillis),
                    formatTime(snapshot.capturedAtMillis)
                )
                setTextColor(textColor)
            }

            calendarDiagnosticsOverviewRefresh.apply {
                imageTintList = ColorStateList.valueOf(primaryColor)
                isEnabled = !isRefreshing
                beVisibleIf(!isRefreshing)
                setOnClickListener {
                    if (!isRefreshing) {
                        onRefresh()
                    }
                }
            }
            calendarDiagnosticsOverviewProgress.apply {
                indeterminateTintList = ColorStateList.valueOf(primaryColor)
                beVisibleIf(isRefreshing)
            }

            val status = buildOverviewStatus(snapshot, row.syncFailed, row.issues)
            calendarDiagnosticsOverviewStatus.beVisibleIf(status != null)
            if (status != null) {
                calendarDiagnosticsOverviewStatus.apply {
                    setCardBackgroundColor(backgroundColor)
                    strokeColor = primaryColor
                }
                calendarDiagnosticsOverviewStatusTitle.apply {
                    text = status.title
                    setTextColor(primaryColor)
                }
                calendarDiagnosticsOverviewStatusDetails.apply {
                    text = status.details
                    setTextColor(textColor)
                }
                calendarDiagnosticsOverviewStatusAction.apply {
                    val action = status.action
                    beVisibleIf(action != null && !isRefreshing)
                    if (action != null && !isRefreshing) {
                        text = context.getString(
                            when (action) {
                                OverviewAction.REFRESH ->
                                    R.string.calendar_diagnostics_sync_short
                                OverviewAction.GRANT_PERMISSION ->
                                    R.string.calendar_diagnostics_allow_short
                            }
                        )
                        setTextColor(primaryColor)
                        isEnabled = !isRefreshing
                        setOnClickListener {
                            when (action) {
                                OverviewAction.REFRESH -> onRefresh()
                                OverviewAction.GRANT_PERMISSION ->
                                    onGrantCalendarPermission()
                            }
                        }
                    } else {
                        setOnClickListener(null)
                    }
                }
            } else {
                calendarDiagnosticsOverviewStatusAction.setOnClickListener(null)
            }
        }
    }

    private inner class SectionViewHolder(
        private val binding: ItemCalendarDiagnosticsSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Section) {
            binding.root.apply {
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
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsEventColor.apply {
                setCardBackgroundColor(accentColor)
                strokeColor = textColor
            }
            calendarDiagnosticsEventTitle.apply {
                text = event.title
                setTextColor(textColor)
            }
            calendarDiagnosticsEventTime.apply {
                text = formatEventRange(event)
                setTextColor(textColor)
            }
            calendarDiagnosticsEventWarning.apply {
                text = buildEventWarnings(event)
                setTextColor(primaryColor)
                beVisibleIf(text.isNotBlank())
            }
            calendarDiagnosticsEventAlarmCount.apply {
                text = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_alarm_count,
                    row.plannedAlarmCount,
                    row.plannedAlarmCount
                )
                setTextColor(primaryColor)
            }
            calendarDiagnosticsEventDivider.setBackgroundColor(textColor)
        }
    }

    private inner class AlarmViewHolder(
        private val binding: ItemCalendarDiagnosticsAlarmBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Alarm) = binding.apply {
            val diagnostic = row.diagnostic
            val alarm = diagnostic.alarm
            val accentColor = row.event?.displayColor ?: primaryColor
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsAlarmColor.apply {
                setCardBackgroundColor(accentColor)
                strokeColor = textColor
            }
            calendarDiagnosticsAlarmTitle.apply {
                text = row.event?.title ?: alarm.label.ifBlank {
                    context.getString(R.string.unnamed_alarm)
                }
                setTextColor(textColor)
            }
            calendarDiagnosticsAlarmTrigger.apply {
                text = formatTime(alarm.triggerAtMillis)
                setTextColor(primaryColor)
            }
            calendarDiagnosticsAlarmDetails.apply {
                text = buildList {
                    add(formatCompactDate(alarm.triggerAtMillis))
                    add(formatOffset(alarm.calendarOffsetMinutes))
                    row.event?.let { event ->
                        add(
                            context.getString(
                                R.string.calendar_diagnostics_event_starts,
                                formatTime(event.beginMillis)
                            )
                        )
                    }
                }.joinToString(SEPARATOR)
                setTextColor(textColor)
            }
            calendarDiagnosticsAlarmWarning.apply {
                text = buildAlarmWarnings(diagnostic)
                setTextColor(primaryColor)
                beVisibleIf(text.isNotBlank())
            }
            calendarDiagnosticsAlarmDivider.setBackgroundColor(textColor)
        }
    }

    private inner class EmptyViewHolder(
        private val binding: ItemCalendarDiagnosticsEmptyBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Empty) {
            binding.root.apply {
                text = row.message
                setTextColor(textColor)
            }
        }
    }

    private inner class FooterViewHolder(
        private val binding: ItemCalendarDiagnosticsFooterBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() = binding.apply {
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsFooterDivider.setBackgroundColor(textColor)
            calendarDiagnosticsFooterTitle.setTextColor(textColor)
            calendarDiagnosticsFooterToggle.apply {
                text = context.getString(
                    if (isFooterExpanded) {
                        R.string.calendar_diagnostics_hide_details
                    } else {
                        R.string.calendar_diagnostics_show_details
                    }
                )
                setTextColor(primaryColor)
            }
            calendarDiagnosticsFooterDetails.apply {
                setTextColor(textColor)
                beVisibleIf(isFooterExpanded)
            }
            calendarDiagnosticsFooterHeader.setOnClickListener {
                isFooterExpanded = !isFooterExpanded
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }
        }
    }

    private fun buildOverviewStatus(
        snapshot: CalendarDiagnosticsSnapshot,
        syncFailed: Boolean,
        issues: OverviewIssues,
    ): OverviewStatus? {
        return when {
            snapshot.providerState == CalendarDiagnosticsProviderState.PERMISSION_MISSING ->
                OverviewStatus(
                    title = context.getString(
                        R.string.calendar_diagnostics_permission_title
                    ),
                    details = context.getString(
                        R.string.calendar_diagnostics_permission_message
                    ),
                    action = OverviewAction.GRANT_PERMISSION
                )

            snapshot.providerState == CalendarDiagnosticsProviderState.PROVIDER_ERROR ->
                OverviewStatus(
                    title = context.getString(
                        R.string.calendar_diagnostics_provider_error_title
                    ),
                    details = context.getString(
                        R.string.calendar_diagnostics_provider_error
                    ),
                    action = OverviewAction.REFRESH
                )

            syncFailed ->
                OverviewStatus(
                    title = context.getString(
                        R.string.calendar_diagnostics_sync_error_title
                    ),
                    details = context.getString(
                        R.string.calendar_diagnostics_sync_error
                    ),
                    action = OverviewAction.REFRESH
                )

            issues.eventMissingAlarms > 0 -> OverviewStatus(
                title = context.getString(
                    R.string.calendar_diagnostics_event_missing_title
                ),
                details = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_event_missing_issue,
                    issues.eventMissingAlarms,
                    issues.eventMissingAlarms
                ),
                action = OverviewAction.REFRESH
            )

            issues.syncableItems > 0 -> OverviewStatus(
                title = context.getString(
                    R.string.calendar_diagnostics_sync_needed_title
                ),
                details = context.getString(
                    R.string.calendar_diagnostics_sync_needed_details
                ),
                action = OverviewAction.REFRESH
            )

            issues.invalidPatternEvents > 0 -> OverviewStatus(
                title = context.getString(
                    R.string.calendar_diagnostics_invalid_pattern_title
                ),
                details = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_invalid_pattern_issue,
                    issues.invalidPatternEvents,
                    issues.invalidPatternEvents
                ),
                action = null
            )

            issues.duplicateAlarms > 0 -> OverviewStatus(
                title = context.getString(
                    R.string.calendar_diagnostics_duplicate_title
                ),
                details = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_duplicate_issue,
                    issues.duplicateAlarms,
                    issues.duplicateAlarms
                ),
                action = null
            )

            else -> null
        }
    }

    private fun buildEventWarnings(event: CalendarEventDiagnostic): String {
        return when {
            event.markerParseState == CalendarMarkerParseState.INVALID_MENTION ->
                context.getString(R.string.calendar_diagnostics_invalid_pattern_short)

            event.markerMissingAlarms.isNotEmpty() ->
                context.getString(R.string.calendar_diagnostics_marker_removed_short)

            event.markers.any {
                    it.disposition == CalendarMarkerDisposition.ELIGIBLE &&
                        it.alarms.isEmpty()
                } ->
                context.getString(R.string.calendar_diagnostics_alarm_missing_short)

            else -> ""
        }
    }

    private fun buildAlarmWarnings(diagnostic: CalendarAlarmDiagnostic): String {
        return buildList {
            when (diagnostic.linkStatus) {
                CalendarAlarmLinkStatus.EXACT -> Unit
                CalendarAlarmLinkStatus.EVENT_MISSING -> add(
                    context.getString(
                        R.string.calendar_diagnostics_event_missing_alarm_warning
                    )
                )

                CalendarAlarmLinkStatus.MARKER_MISSING -> add(
                    context.getString(
                        R.string.calendar_diagnostics_marker_missing_alarm_warning
                    )
                )

                else -> add(context.getString(diagnostic.linkStatus.labelResource()))
            }
            if (diagnostic.hasDuplicateKey) {
                add(context.getString(R.string.calendar_diagnostics_duplicate_short))
            }
        }.joinToString("\n")
    }

    private fun formatEventRange(event: CalendarEventDiagnostic): String {
        if (!event.isAllDay) {
            val start = formatDateTime(event.beginMillis)
            return if (event.endMillis > event.beginMillis) {
                val zone = ZoneId.systemDefault()
                val startDay = Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalDate()
                val endDay = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
                val end = if (startDay == endDay) {
                    formatTime(event.endMillis)
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

    private fun formatOverviewDate(timestamp: Long): String {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
    }

    private fun formatCompactDate(timestamp: Long): String {
        return DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_ABBREV_MONTH or
                DateUtils.FORMAT_NO_YEAR
        )
    }

    private fun formatTime(timestamp: Long): String {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
    }

    private fun formatDate(timestamp: Long, allDay: Boolean): String {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).apply {
            if (allDay) {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }.format(Date(timestamp))
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
        const val VIEW_TYPE_OVERVIEW = 0
        const val VIEW_TYPE_SECTION = 1
        const val VIEW_TYPE_EVENT = 2
        const val VIEW_TYPE_ALARM = 3
        const val VIEW_TYPE_EMPTY = 4
        const val VIEW_TYPE_FOOTER = 5
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        const val SEPARATOR = " · "
    }
}
