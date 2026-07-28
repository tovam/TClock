package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.engine.Cl1EventIssue
import org.fossify.clock.cl1.engine.Cl1EventIssueState
import org.fossify.clock.cl1.engine.Cl1RelationSnapshot
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.ui.availableUiActions
import org.fossify.clock.cl1.ui.canCreateCl1Copy
import org.fossify.clock.databinding.ItemCalendarDiagnosticsAlarmBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsCl1NoticeBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsCl1RelationBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEmptyBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEventBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsEventAlarmStatusBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsFooterBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsOverviewBinding
import org.fossify.clock.databinding.ItemCalendarDiagnosticsSectionBinding
import org.fossify.clock.helpers.CalendarAlarmDiagnostic
import org.fossify.clock.helpers.CalendarAlarmLinkStatus
import org.fossify.clock.helpers.CalendarDiagnosticsProviderState
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarEventAlarmSummary
import org.fossify.clock.helpers.CalendarEventDiagnostic
import org.fossify.clock.helpers.CalendarMarkerDiagnostic
import org.fossify.clock.helpers.CalendarMarkerDisposition
import org.fossify.clock.helpers.CalendarMarkerParseState
import org.fossify.clock.helpers.CalendarOccurrenceKey
import org.fossify.clock.helpers.alarmSummary
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
    private val onCreateCl1Copy: (Cl1EventSnapshot) -> Unit,
    private val onCl1RelationActions: (Cl1RelationSnapshot) -> Unit,
    private val onReconcileCl1: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data class Overview(
            val snapshot: CalendarDiagnosticsSnapshot,
            val plannedAlarmCount: Int,
            val syncFailed: Boolean,
            val issues: OverviewIssues,
        ) : Row

        data class Section(
            val title: String,
            val showCl1Reconcile: Boolean = false,
        ) : Row

        data class Event(
            val diagnostic: CalendarEventDiagnostic,
            val summary: CalendarEventAlarmSummary,
            val capturedAtMillis: Long,
            val cl1Event: Cl1EventSnapshot?,
            val cl1Relations: List<Cl1RelationSnapshot>,
            val cl1Issue: Cl1EventIssue?,
            val cl1CanWrite: Boolean,
        ) : Row

        data class Cl1Relation(
            val relation: Cl1RelationSnapshot,
            val pendingOperations: List<Cl1PendingOperation>,
            val canWrite: Boolean,
        ) : Row

        data class Cl1Notice(
            val title: String,
            val details: String,
        ) : Row

        data class Alarm(
            val diagnostic: CalendarAlarmDiagnostic,
            val event: CalendarEventDiagnostic?,
            val isExpired: Boolean,
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
        val expiredAlarms: Int,
        val eventMissingAlarms: Int,
        val syncableItems: Int,
        val invalidPatternEvents: Int,
        val duplicateAlarms: Int,
        val cl1WritePermissionMissing: Boolean,
    )

    private var rows: List<Row> = emptyList()
    private var isRefreshing = false
    private var isFooterExpanded = false
    private val expandedEvents = mutableSetOf<CalendarOccurrenceKey>()
    private val expandedRelations = mutableSetOf<String>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitSnapshot(
        snapshot: CalendarDiagnosticsSnapshot,
        syncFailed: Boolean,
    ) {
        isRefreshing = false
        expandedEvents.retainAll(snapshot.events.mapTo(mutableSetOf()) { it.key })
        expandedRelations.retainAll(
            snapshot.cl1?.discovery?.relations
                ?.mapTo(mutableSetOf()) { it.key.slot.toHex() }
                .orEmpty()
        )
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
            is Row.Cl1Relation -> VIEW_TYPE_CL1_RELATION
            is Row.Cl1Notice -> VIEW_TYPE_CL1_NOTICE
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

            VIEW_TYPE_CL1_RELATION -> Cl1RelationViewHolder(
                ItemCalendarDiagnosticsCl1RelationBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            VIEW_TYPE_CL1_NOTICE -> Cl1NoticeViewHolder(
                ItemCalendarDiagnosticsCl1NoticeBinding.inflate(inflater, parent, false)
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
            is Row.Cl1Relation -> (holder as Cl1RelationViewHolder).bind(row)
            is Row.Cl1Notice -> (holder as Cl1NoticeViewHolder).bind(row)
            is Row.Alarm -> (holder as AlarmViewHolder).bind(row)
            is Row.Empty -> (holder as EmptyViewHolder).bind(row)
            Row.Footer -> (holder as FooterViewHolder).bind()
        }
    }

    private fun buildRows(
        snapshot: CalendarDiagnosticsSnapshot,
        syncFailed: Boolean,
    ): List<Row> = buildList {
        val storedAlarms = collectStoredAlarms(snapshot)
        val plannedAlarms = storedAlarms.filter {
            !it.isExpired &&
                it.diagnostic.alarm.triggerAtMillis > snapshot.capturedAtMillis
        }
        val expiredAlarms = storedAlarms.filter { it.isExpired }
        add(
            Row.Overview(
                snapshot = snapshot,
                plannedAlarmCount = plannedAlarms.size,
                syncFailed = syncFailed,
                issues = buildOverviewIssues(
                    snapshot = snapshot,
                    plannedAlarms = plannedAlarms,
                    expiredAlarmCount = expiredAlarms.size
                )
            )
        )

        snapshot.cl1?.let { cl1 ->
            val relations = cl1.discovery.relations.sortedWith(
                compareBy<Cl1RelationSnapshot> {
                    it.source?.startMillis ?: it.mirror?.startMillis ?: Long.MAX_VALUE
                }.thenBy { it.key.slot.toHex() }
            )
            add(
                Row.Section(
                    title = context.getString(
                        R.string.cl1_relations_section,
                        relations.size
                    ),
                    showCl1Reconcile = true
                )
            )
            if (relations.isEmpty()) {
                add(Row.Empty(context.getString(R.string.cl1_no_relations)))
            } else {
                relations.forEach { relation ->
                    add(
                        Row.Cl1Relation(
                            relation = relation,
                            pendingOperations = cl1.pendingOperations.filter {
                                it.slotHex == relation.key.slot.toHex()
                            },
                            canWrite = cl1.mutationsAllowed
                        )
                    )
                }
            }

            if (cl1.discovery.eventIssues.isNotEmpty()) {
                add(
                    Row.Section(
                        context.getString(
                            R.string.cl1_notices_section,
                            cl1.discovery.eventIssues.size
                        )
                    )
                )
                cl1.discovery.eventIssues
                    .sortedBy { it.event.startMillis }
                    .forEach { issue ->
                        add(
                            Row.Cl1Notice(
                                title = context.getString(issue.state.titleResource()),
                                details = formatCl1Issue(issue)
                            )
                        )
                    }
            }

            if (cl1.pendingOperations.isNotEmpty()) {
                add(
                    Row.Section(
                        context.getString(
                            R.string.cl1_pending_section,
                            cl1.pendingOperations.size
                        )
                    )
                )
                cl1.pendingOperations.forEach { operation ->
                    add(
                        Row.Cl1Notice(
                            title = context.getString(
                                R.string.cl1_pending_title,
                                operation.type.operationLabel()
                            ),
                            details = formatPendingOperation(operation)
                        )
                    )
                }
            }
        }

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

        if (expiredAlarms.isNotEmpty()) {
            add(
                Row.Section(
                    context.getString(
                        R.string.calendar_diagnostics_expired_alarms_section,
                        expiredAlarms.size
                    )
                )
            )
            addAll(expiredAlarms)
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
                appendEventRows(displayWindowEvents, snapshot)
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
                appendEventRows(relatedEventsOutsideWindow, snapshot)
            }
        }

        add(Row.Footer)
    }

    private fun collectStoredAlarms(
        snapshot: CalendarDiagnosticsSnapshot,
    ): List<Row.Alarm> {
        return buildList {
            snapshot.events.forEach { event ->
                event.alarms.forEach { diagnostic ->
                    add(
                        Row.Alarm(
                            diagnostic = diagnostic,
                            event = event,
                            isExpired = diagnostic.alarm.isExpiredCalendarAlarm(
                                snapshot.capturedAtMillis
                            )
                        )
                    )
                }
            }
            snapshot.unlinkedAlarms.forEach { diagnostic ->
                add(
                    Row.Alarm(
                        diagnostic = diagnostic,
                        event = null,
                        isExpired = diagnostic.alarm.isExpiredCalendarAlarm(
                            snapshot.capturedAtMillis
                        )
                    )
                )
            }
        }.filter { row ->
            row.diagnostic.alarm.isEnabled &&
                row.diagnostic.alarm.oneShot
        }.sortedWith(
            compareBy<Row.Alarm> { it.diagnostic.alarm.triggerAtMillis }
                .thenBy { it.diagnostic.alarm.id }
        )
    }

    private fun buildOverviewIssues(
        snapshot: CalendarDiagnosticsSnapshot,
        plannedAlarms: List<Row.Alarm>,
        expiredAlarmCount: Int,
    ): OverviewIssues {
        return OverviewIssues(
            expiredAlarms = expiredAlarmCount,
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
            },
            cl1WritePermissionMissing =
                snapshot.cl1 != null && !snapshot.cl1.mutationsAllowed
        )
    }

    private fun MutableList<Row>.appendEventRows(
        events: List<CalendarEventDiagnostic>,
        snapshot: CalendarDiagnosticsSnapshot,
    ) {
        val cl1Discovery = snapshot.cl1?.discovery
        val cl1Events = cl1Discovery?.events?.associateBy { it.ref }.orEmpty()
        val cl1Issues = cl1Discovery?.eventIssues?.associateBy { it.event.ref }.orEmpty()
        events.forEach { event ->
            val ref = Cl1EventRef(
                eventId = event.key.eventId,
                calendarId = event.calendarId
            )
            add(
                Row.Event(
                    diagnostic = event,
                    summary = event.alarmSummary(snapshot.capturedAtMillis),
                    capturedAtMillis = snapshot.capturedAtMillis,
                    cl1Event = cl1Events[ref],
                    cl1Relations = cl1Discovery?.relations
                        ?.filter { it.source?.ref == ref || it.mirror?.ref == ref }
                        .orEmpty(),
                    cl1Issue = cl1Issues[ref],
                    cl1CanWrite = snapshot.cl1?.mutationsAllowed == true
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
                text = buildList {
                    add(alarmSummary)
                    add(eventSummary)
                    val relationCount = snapshot.cl1?.discovery?.relations?.size ?: 0
                    if (relationCount > 0) {
                        add(
                            context.resources.getQuantityString(
                                R.plurals.cl1_relation_label,
                                relationCount,
                                relationCount
                            )
                        )
                    }
                }.joinToString(SEPARATOR)
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
            binding.root.setBackgroundColor(backgroundColor)
            binding.calendarDiagnosticsSectionTitle.apply {
                text = row.title
                setTextColor(textColor)
            }
            binding.calendarDiagnosticsSectionAction.apply {
                beVisibleIf(row.showCl1Reconcile)
                setTextColor(primaryColor)
                setOnClickListener(
                    if (row.showCl1Reconcile) {
                        View.OnClickListener { onReconcileCl1() }
                    } else {
                        null
                    }
                )
            }
        }
    }

    private inner class EventViewHolder(
        private val binding: ItemCalendarDiagnosticsEventBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Event) = binding.apply {
            val event = row.diagnostic
            val accentColor = event.displayColor ?: primaryColor
            val isExpanded = event.key in expandedEvents
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
                    row.summary.active,
                    row.summary.active
                )
                setTextColor(primaryColor)
            }
            calendarDiagnosticsEventExpand.apply {
                imageTintList = ColorStateList.valueOf(primaryColor)
                rotation = if (isExpanded) 180f else 0f
                contentDescription = context.getString(
                    if (isExpanded) {
                        R.string.calendar_diagnostics_hide_event_details
                    } else {
                        R.string.calendar_diagnostics_show_event_details
                    }
                )
            }
            calendarDiagnosticsEventHeader.apply {
                contentDescription = calendarDiagnosticsEventExpand.contentDescription
                setOnClickListener {
                    if (!expandedEvents.add(event.key)) {
                        expandedEvents.remove(event.key)
                    }
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position)
                    }
                }
            }
            calendarDiagnosticsEventDetails.beVisibleIf(isExpanded)
            if (isExpanded) {
                bindExpandedDetails(row, accentColor)
            } else {
                calendarDiagnosticsEventDetailAlarmList.removeAllViews()
            }
            calendarDiagnosticsEventDivider.setBackgroundColor(textColor)
        }

        private fun bindExpandedDetails(
            row: Row.Event,
            accentColor: Int,
        ) = binding.apply {
            val event = row.diagnostic
            val summary = row.summary
            calendarDiagnosticsEventDetails.apply {
                setCardBackgroundColor(backgroundColor)
                strokeColor = accentColor
            }
            listOf(
                calendarDiagnosticsEventDetailTitle,
                calendarDiagnosticsEventDetailMetadata,
                calendarDiagnosticsEventDetailTime,
                calendarDiagnosticsEventDetailFlags,
                calendarDiagnosticsEventDetailCl1,
                calendarDiagnosticsEventDetailCurrentStateNote,
                calendarDiagnosticsEventDetailAlarmsTitle,
                calendarDiagnosticsEventDetailNoAlarms,
                calendarDiagnosticsEventDetailDescriptionLabel,
                calendarDiagnosticsEventDetailDescription
            ).forEach { it.setTextColor(textColor) }
            listOf(
                calendarDiagnosticsEventDetailDeclarations,
                calendarDiagnosticsEventDetailParsed,
                calendarDiagnosticsEventDetailCreated,
                calendarDiagnosticsEventDetailActive,
                calendarDiagnosticsEventDetailPassed,
                calendarDiagnosticsEventDetailNotCreated
            ).forEach { it.setTextColor(primaryColor) }

            val calendarName = event.calendarDisplayName.ifBlank {
                context.getString(
                    R.string.calendar_diagnostics_calendar_fallback,
                    event.calendarId
                )
            }
            calendarDiagnosticsEventDetailMetadata.text = context.getString(
                R.string.calendar_diagnostics_event_detail_metadata,
                calendarName,
                event.key.eventId
            )
            calendarDiagnosticsEventDetailTime.text = formatEventRange(event)
            calendarDiagnosticsEventDetailFlags.text = formatEventFlags(event)
            calendarDiagnosticsEventDetailCl1.apply {
                text = formatEventCl1Status(row)
                beVisibleIf(text.isNotBlank())
            }
            val cl1Event = row.cl1Event
            val canCreateCl1Copy =
                cl1Event?.canCreateCl1Copy(row.cl1CanWrite) == true
            calendarDiagnosticsEventCreateCl1Copy.apply {
                beVisibleIf(canCreateCl1Copy)
                setTextColor(primaryColor)
                setOnClickListener(
                    if (canCreateCl1Copy) {
                        View.OnClickListener {
                            onCreateCl1Copy(checkNotNull(cl1Event))
                        }
                    } else {
                        null
                    }
                )
            }
            calendarDiagnosticsEventDetailDeclarations.text = context.getString(
                R.string.calendar_diagnostics_event_detail_declarations,
                summary.declarations
            )
            calendarDiagnosticsEventDetailParsed.text = context.getString(
                R.string.calendar_diagnostics_event_detail_parsed,
                summary.parsed
            )
            calendarDiagnosticsEventDetailCreated.text = context.getString(
                R.string.calendar_diagnostics_event_detail_created,
                summary.created
            )
            calendarDiagnosticsEventDetailActive.text = context.getString(
                R.string.calendar_diagnostics_event_detail_active,
                summary.active
            )
            calendarDiagnosticsEventDetailPassed.text = context.getString(
                R.string.calendar_diagnostics_event_detail_passed,
                summary.passed
            )
            calendarDiagnosticsEventDetailNotCreated.text = context.getString(
                R.string.calendar_diagnostics_event_detail_not_created,
                summary.notCreated
            )

            calendarDiagnosticsEventDetailAlarmList.removeAllViews()
            event.markers.forEach { marker ->
                addMarkerStatus(
                    parent = calendarDiagnosticsEventDetailAlarmList,
                    marker = marker,
                    capturedAtMillis = row.capturedAtMillis
                )
            }
            event.markerMissingAlarms.forEach { diagnostic ->
                addMarkerMissingStatus(
                    parent = calendarDiagnosticsEventDetailAlarmList,
                    diagnostic = diagnostic,
                    capturedAtMillis = row.capturedAtMillis
                )
            }
            calendarDiagnosticsEventDetailNoAlarms.beVisibleIf(
                event.markers.isEmpty() && event.markerMissingAlarms.isEmpty()
            )

            val hasDescription = event.description.isNotBlank()
            calendarDiagnosticsEventDetailDescriptionHolder.beVisibleIf(hasDescription)
            calendarDiagnosticsEventDetailDescription.text = event.description
        }
    }

    private inner class Cl1RelationViewHolder(
        private val binding: ItemCalendarDiagnosticsCl1RelationBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Cl1Relation) = binding.apply {
            val relation = row.relation
            val relationId = relation.key.slot.toHex()
            val isExpanded = relationId in expandedRelations
            val stateLabel = context.getString(relation.state.labelResource())
            val alarmPolicyShort = context.getString(
                if (relation.suppressMirrorAlarm) {
                    R.string.cl1_alarm_policy_source_short
                } else {
                    R.string.cl1_alarm_policy_autonomous_short
                }
            )
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsCl1RelationColor.apply {
                setCardBackgroundColor(
                    relation.mirror?.calendar?.color
                        ?: relation.source?.calendar?.color
                        ?: primaryColor
                )
                strokeColor = textColor
            }
            calendarDiagnosticsCl1RelationTitle.apply {
                text = context.getString(
                    R.string.cl1_relation_title,
                    relation.source?.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.cl1_source_unavailable),
                    relation.mirror?.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.cl1_copy_unavailable)
                )
                setTextColor(textColor)
            }
            calendarDiagnosticsCl1RelationSummary.apply {
                text = context.getString(
                    R.string.cl1_relation_summary,
                    stateLabel,
                    alarmPolicyShort
                )
                setTextColor(
                    if (relation.state == Cl1RelationState.ACTIVE) {
                        textColor
                    } else {
                        primaryColor
                    }
                )
            }
            calendarDiagnosticsCl1RelationExpand.apply {
                imageTintList = ColorStateList.valueOf(primaryColor)
                rotation = if (isExpanded) 180f else 0f
                contentDescription = context.getString(
                    if (isExpanded) {
                        R.string.cl1_hide_relation_details
                    } else {
                        R.string.cl1_show_relation_details
                    }
                )
            }
            calendarDiagnosticsCl1RelationHeader.apply {
                contentDescription = calendarDiagnosticsCl1RelationExpand.contentDescription
                setOnClickListener {
                    if (!expandedRelations.add(relationId)) {
                        expandedRelations.remove(relationId)
                    }
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position)
                    }
                }
            }
            calendarDiagnosticsCl1RelationDetails.beVisibleIf(isExpanded)
            if (isExpanded) {
                calendarDiagnosticsCl1RelationDetails.apply {
                    setCardBackgroundColor(backgroundColor)
                    strokeColor = primaryColor
                }
                listOf(
                    calendarDiagnosticsCl1RelationExplanation,
                    calendarDiagnosticsCl1RelationSource,
                    calendarDiagnosticsCl1RelationMirror,
                    calendarDiagnosticsCl1RelationAlarmPolicy,
                    calendarDiagnosticsCl1RelationOverrides,
                    calendarDiagnosticsCl1RelationTechnical
                ).forEach { it.setTextColor(textColor) }
                calendarDiagnosticsCl1RelationExplanation.text =
                    context.getString(relation.state.explanationResource())
                calendarDiagnosticsCl1RelationSource.text = formatRelationEvent(
                    R.string.cl1_source_label,
                    relation.source
                )
                calendarDiagnosticsCl1RelationMirror.text = formatRelationEvent(
                    R.string.cl1_copy_label,
                    relation.mirror
                )
                calendarDiagnosticsCl1RelationAlarmPolicy.text = context.getString(
                    if (relation.suppressMirrorAlarm) {
                        R.string.cl1_alarm_policy_source
                    } else {
                        R.string.cl1_alarm_policy_autonomous
                    }
                )
                calendarDiagnosticsCl1RelationOverrides.text =
                    formatOverrides(relation)
                calendarDiagnosticsCl1RelationTechnical.text =
                    formatRelationTechnical(row)
                val actions = relation.availableUiActions(row.canWrite)
                calendarDiagnosticsCl1RelationActions.apply {
                    beVisibleIf(actions.isNotEmpty())
                    setTextColor(primaryColor)
                    setOnClickListener(
                        if (actions.isNotEmpty()) {
                            View.OnClickListener {
                                onCl1RelationActions(relation)
                            }
                        } else {
                            null
                        }
                    )
                }
            } else {
                calendarDiagnosticsCl1RelationActions.apply {
                    beVisibleIf(false)
                    setOnClickListener(null)
                }
            }
            calendarDiagnosticsCl1RelationDivider.setBackgroundColor(textColor)
        }
    }

    private inner class Cl1NoticeViewHolder(
        private val binding: ItemCalendarDiagnosticsCl1NoticeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Cl1Notice) = binding.apply {
            root.setBackgroundColor(backgroundColor)
            calendarDiagnosticsCl1NoticeTitle.apply {
                text = row.title
                setTextColor(primaryColor)
            }
            calendarDiagnosticsCl1NoticeDetails.apply {
                text = row.details
                setTextColor(textColor)
            }
            calendarDiagnosticsCl1NoticeDivider.setBackgroundColor(textColor)
        }
    }

    private fun addMarkerStatus(
        parent: ViewGroup,
        marker: CalendarMarkerDiagnostic,
        capturedAtMillis: Long,
    ) {
        val itemBinding = ItemCalendarDiagnosticsEventAlarmStatusBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        val state = if (marker.alarms.isEmpty()) {
            context.getString(
                if (marker.disposition == CalendarMarkerDisposition.ELIGIBLE) {
                    R.string.calendar_diagnostics_alarm_missing_short
                } else {
                    marker.disposition.labelResource()
                }
            )
        } else {
            buildAlarmRecordState(
                diagnostics = marker.alarms,
                capturedAtMillis = capturedAtMillis,
                disposition = marker.disposition
            )
        }
        bindEventAlarmStatus(
            binding = itemBinding,
            title = formatOffset(marker.key.offsetMinutes),
            trigger = context.getString(
                R.string.calendar_diagnostics_expected_trigger,
                formatDateTime(marker.triggerAtMillis)
            ),
            state = state
        )
        parent.addView(itemBinding.root)
    }

    private fun addMarkerMissingStatus(
        parent: ViewGroup,
        diagnostic: CalendarAlarmDiagnostic,
        capturedAtMillis: Long,
    ) {
        val itemBinding = ItemCalendarDiagnosticsEventAlarmStatusBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        val alarm = diagnostic.alarm
        val recordState = buildAlarmRecordState(
            diagnostics = listOf(diagnostic),
            capturedAtMillis = capturedAtMillis,
            disposition = null
        )
        bindEventAlarmStatus(
            binding = itemBinding,
            title = formatOffset(alarm.calendarOffsetMinutes),
            trigger = if (alarm.triggerAtMillis > 0L) {
                context.getString(
                    R.string.calendar_diagnostics_stored_trigger,
                    formatDateTime(alarm.triggerAtMillis)
                )
            } else {
                context.getString(R.string.calendar_diagnostics_no_stored_trigger)
            },
            state = listOf(
                context.getString(R.string.calendar_diagnostics_marker_removed_short),
                recordState
            ).joinToString(SEPARATOR)
        )
        parent.addView(itemBinding.root)
    }

    private fun bindEventAlarmStatus(
        binding: ItemCalendarDiagnosticsEventAlarmStatusBinding,
        title: String,
        trigger: String,
        state: String,
    ) = binding.apply {
        calendarDiagnosticsEventAlarmStatusTitle.apply {
            text = title
            setTextColor(textColor)
        }
        calendarDiagnosticsEventAlarmStatusTrigger.apply {
            text = trigger
            setTextColor(textColor)
        }
        calendarDiagnosticsEventAlarmStatusState.apply {
            text = state
            setTextColor(primaryColor)
        }
        calendarDiagnosticsEventAlarmStatusDivider.setBackgroundColor(textColor)
    }

    private fun buildAlarmRecordState(
        diagnostics: List<CalendarAlarmDiagnostic>,
        capturedAtMillis: Long,
        disposition: CalendarMarkerDisposition?,
    ): String {
        val active = diagnostics.count { diagnostic ->
            diagnostic.alarm.isEnabled &&
                diagnostic.alarm.oneShot &&
                diagnostic.alarm.triggerAtMillis > capturedAtMillis
        }
        val passed = diagnostics.count { diagnostic ->
            diagnostic.alarm.triggerAtMillis > 0L &&
                diagnostic.alarm.triggerAtMillis <= capturedAtMillis
        }
        val disabled = diagnostics.count { !it.alarm.isEnabled }
        return buildList {
            add(
                context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_event_detail_created_count,
                    diagnostics.size,
                    diagnostics.size
                )
            )
            if (active > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.calendar_diagnostics_event_detail_active_count,
                        active,
                        active
                    )
                )
            }
            if (passed > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.calendar_diagnostics_event_detail_passed_count,
                        passed,
                        passed
                    )
                )
            }
            if (disabled > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.calendar_diagnostics_event_detail_disabled_count,
                        disabled,
                        disabled
                    )
                )
            }
            if (
                disposition != null &&
                disposition != CalendarMarkerDisposition.ELIGIBLE
            ) {
                add(context.getString(disposition.labelResource()))
            }
            if (diagnostics.any { it.linkStatus == CalendarAlarmLinkStatus.METADATA_DRIFT }) {
                add(context.getString(R.string.calendar_diagnostics_link_drift))
            }
            if (diagnostics.any { it.hasDuplicateKey }) {
                add(context.getString(R.string.calendar_diagnostics_duplicate_short))
            }
        }.joinToString(SEPARATOR)
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
                text = buildAlarmWarnings(row)
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

    private fun formatEventCl1Status(row: Row.Event): String {
        row.cl1Issue?.let { issue ->
            return context.getString(
                R.string.cl1_event_issue_status,
                context.getString(issue.state.titleResource())
            )
        }
        return when (
            val parsed = row.cl1Event?.parsedDescription
        ) {
            is Cl1Description.Valid -> when (val payload = parsed.payload) {
                is Cl1Payload.Source -> context.getString(
                    R.string.cl1_event_source_status,
                    payload.records.size
                )

                is Cl1Payload.Mirror -> {
                    val state = row.cl1Relations.firstOrNull()?.state
                        ?: Cl1RelationState.UNRESOLVED
                    context.getString(
                        R.string.cl1_event_copy_status,
                        context.getString(state.labelResource())
                    )
                }
            }

            is Cl1Description.UnsupportedVersion -> context.getString(
                R.string.cl1_event_issue_status,
                context.getString(R.string.cl1_issue_unsupported_title)
            )

            is Cl1Description.Corrupt -> context.getString(
                R.string.cl1_event_issue_status,
                context.getString(R.string.cl1_issue_corrupt_title)
            )

            is Cl1Description.None,
            null,
            -> ""
        }
    }

    private fun formatCl1Issue(issue: Cl1EventIssue): String {
        val event = issue.event
        val title = event.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.calendar_untitled_event)
        val calendar = event.calendar.displayName.ifBlank {
            context.getString(
                R.string.calendar_diagnostics_calendar_fallback,
                event.ref.calendarId
            )
        }
        return context.getString(
            R.string.cl1_issue_details,
            title,
            calendar,
            formatDateTime(event.startMillis),
            issue.detail.orEmpty()
        )
    }

    private fun formatPendingOperation(operation: Cl1PendingOperation): String {
        val error = operation.lastError?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.cl1_pending_error, it)
        }.orEmpty()
        return context.getString(
            R.string.cl1_pending_operation_summary,
            operation.type.operationLabel(),
            operation.phase,
            operation.attempts,
            error
        )
    }

    private fun formatRelationEvent(
        roleResource: Int,
        event: Cl1EventSnapshot?,
    ): String {
        val role = context.getString(roleResource)
        if (event == null) {
            return context.getString(R.string.cl1_relation_event_unavailable, role)
        }
        val calendar = event.calendar.displayName.ifBlank {
            context.getString(
                R.string.calendar_diagnostics_calendar_fallback,
                event.ref.calendarId
            )
        }
        return context.getString(
            R.string.cl1_relation_event,
            role,
            calendar,
            formatDateTime(event.startMillis),
            event.ref.eventId
        )
    }

    private fun formatOverrides(relation: Cl1RelationSnapshot): String {
        val payload = relation.mirrorPayload
        if (payload == null) {
            return context.getString(
                R.string.cl1_overrides_summary,
                context.getString(R.string.cl1_title_inherited),
                context.getString(R.string.cl1_start_inherited),
                context.getString(R.string.cl1_duration_inherited)
            )
        }
        val title = when (val override = payload.titleOverride) {
            Cl1TitleOverride.Inherited ->
                context.getString(R.string.cl1_title_inherited)

            is Cl1TitleOverride.Replacement -> context.getString(
                R.string.cl1_title_replacement,
                override.value.compactForDiagnostics()
            )

            is Cl1TitleOverride.Template -> context.getString(
                R.string.cl1_title_template,
                override.value.compactForDiagnostics()
            )
        }
        val start = payload.startOffsetSeconds?.let {
            context.getString(
                R.string.cl1_start_offset,
                formatSignedSeconds(it)
            )
        } ?: context.getString(R.string.cl1_start_inherited)
        val duration = when (val override = payload.durationOverride) {
            Cl1DurationOverride.Inherited ->
                context.getString(R.string.cl1_duration_inherited)

            is Cl1DurationOverride.Fixed -> context.getString(
                R.string.cl1_duration_fixed,
                formatUnsignedSeconds(override.seconds)
            )

            is Cl1DurationOverride.Delta -> context.getString(
                R.string.cl1_duration_delta,
                formatSignedSeconds(override.seconds)
            )
        }
        return context.getString(
            R.string.cl1_overrides_summary,
            title,
            start,
            duration
        )
    }

    private fun formatRelationTechnical(row: Row.Cl1Relation): String {
        val relation = row.relation
        return buildList {
            add(
                context.getString(
                    R.string.cl1_relation_identifier,
                    "${relation.key.slot.toHex().take(12)}…"
                )
            )
            relation.detail?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.cl1_relation_detail, it))
            }
            if (row.pendingOperations.isEmpty()) {
                add(context.getString(R.string.cl1_no_pending_operation))
            } else {
                row.pendingOperations.forEach {
                    add(formatPendingOperation(it))
                }
            }
        }.joinToString("\n")
    }

    private fun formatSignedSeconds(value: Long): String {
        val prefix = if (value > 0) "+" else if (value < 0) "−" else ""
        return prefix + formatSecondsMagnitude(value.absoluteValue)
    }

    private fun formatUnsignedSeconds(value: ULong): String {
        return if (value <= Long.MAX_VALUE.toULong()) {
            formatSecondsMagnitude(value.toLong())
        } else {
            "$value s"
        }
    }

    private fun formatSecondsMagnitude(seconds: Long): String {
        return when {
            seconds == 0L -> context.getString(
                R.string.calendar_diagnostics_seconds_short,
                0
            )

            seconds % SECONDS_PER_DAY == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_days_short,
                    seconds / SECONDS_PER_DAY
                )

            seconds % SECONDS_PER_HOUR == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_hours_short,
                    seconds / SECONDS_PER_HOUR
                )

            seconds % SECONDS_PER_MINUTE == 0L ->
                context.getString(
                    R.string.calendar_diagnostics_minutes_short,
                    seconds / SECONDS_PER_MINUTE
                )

            else -> context.getString(
                R.string.calendar_diagnostics_seconds_short,
                seconds
            )
        }
    }

    private fun String.compactForDiagnostics(): String {
        return replace('\n', ' ').take(CL1_VALUE_PREVIEW_LENGTH)
    }

    private fun String.operationLabel(): String {
        val resource = when (this) {
            "create" -> R.string.cl1_operation_create
            "repair" -> R.string.cl1_operation_repair
            "sync" -> R.string.cl1_operation_sync
            "restore" -> R.string.cl1_operation_restore
            "applyCopy" -> R.string.cl1_operation_apply_copy
            "convertOverrides" -> R.string.cl1_operation_convert
            "unlink" -> R.string.cl1_operation_unlink
            "changeDestination" -> R.string.cl1_operation_change_destination
            "deleteSource" -> R.string.cl1_operation_delete_source
            else -> null
        }
        return if (resource == null) this else context.getString(resource)
    }

    private fun Cl1EventIssueState.titleResource(): Int {
        return when (this) {
            Cl1EventIssueState.UNSUPPORTED_VERSION ->
                R.string.cl1_issue_unsupported_title

            Cl1EventIssueState.BLOCK_CORRUPT ->
                R.string.cl1_issue_corrupt_title
        }
    }

    private fun Cl1RelationState.labelResource(): Int {
        return when (this) {
            Cl1RelationState.ACTIVE -> R.string.cl1_state_active
            Cl1RelationState.SOURCE_MODIFIED -> R.string.cl1_state_source_modified
            Cl1RelationState.COPY_MODIFIED -> R.string.cl1_state_copy_modified
            Cl1RelationState.CONCURRENT_CONFLICT ->
                R.string.cl1_state_concurrent_conflict

            Cl1RelationState.MISSING_OR_INACCESSIBLE -> R.string.cl1_state_missing
            Cl1RelationState.UNRESOLVED -> R.string.cl1_state_unresolved
            Cl1RelationState.ORPHAN -> R.string.cl1_state_orphan
            Cl1RelationState.RECORD_CORRUPT -> R.string.cl1_state_record_corrupt
            Cl1RelationState.RELATION_CONFLICT ->
                R.string.cl1_state_relation_conflict

            Cl1RelationState.INCOMPATIBLE -> R.string.cl1_state_incompatible
        }
    }

    private fun Cl1RelationState.explanationResource(): Int {
        return when (this) {
            Cl1RelationState.ACTIVE -> R.string.cl1_state_active_explanation
            Cl1RelationState.SOURCE_MODIFIED ->
                R.string.cl1_state_source_modified_explanation

            Cl1RelationState.COPY_MODIFIED ->
                R.string.cl1_state_copy_modified_explanation

            Cl1RelationState.CONCURRENT_CONFLICT ->
                R.string.cl1_state_concurrent_conflict_explanation

            Cl1RelationState.MISSING_OR_INACCESSIBLE ->
                R.string.cl1_state_missing_explanation

            Cl1RelationState.UNRESOLVED -> R.string.cl1_state_unresolved_explanation
            Cl1RelationState.ORPHAN -> R.string.cl1_state_orphan_explanation
            Cl1RelationState.RECORD_CORRUPT ->
                R.string.cl1_state_record_corrupt_explanation

            Cl1RelationState.RELATION_CONFLICT ->
                R.string.cl1_state_relation_conflict_explanation

            Cl1RelationState.INCOMPATIBLE ->
                R.string.cl1_state_incompatible_explanation
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

            issues.cl1WritePermissionMissing ->
                OverviewStatus(
                    title = context.getString(R.string.cl1_write_permission_title),
                    details = context.getString(R.string.cl1_write_permission_details),
                    action = OverviewAction.GRANT_PERMISSION
                )

            issues.expiredAlarms > 0 -> OverviewStatus(
                title = context.getString(
                    R.string.calendar_diagnostics_expired_title
                ),
                details = context.resources.getQuantityString(
                    R.plurals.calendar_diagnostics_expired_alarm_issue,
                    issues.expiredAlarms,
                    issues.expiredAlarms
                ),
                action = null
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

    private fun buildAlarmWarnings(row: Row.Alarm): String {
        val diagnostic = row.diagnostic
        return buildList {
            if (row.isExpired) {
                add(context.getString(R.string.calendar_diagnostics_expired_alarm_warning))
            }
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

    private fun formatEventFlags(event: CalendarEventDiagnostic): String {
        return buildList {
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
        }.joinToString(SEPARATOR)
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

            CalendarMarkerDisposition.SUPPRESSED_CL1_MIRROR ->
                R.string.calendar_diagnostics_disposition_cl1_mirror
        }
    }

    private companion object {
        const val VIEW_TYPE_OVERVIEW = 0
        const val VIEW_TYPE_SECTION = 1
        const val VIEW_TYPE_EVENT = 2
        const val VIEW_TYPE_CL1_RELATION = 3
        const val VIEW_TYPE_CL1_NOTICE = 4
        const val VIEW_TYPE_ALARM = 5
        const val VIEW_TYPE_EMPTY = 6
        const val VIEW_TYPE_FOOTER = 7
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
        const val CL1_VALUE_PREVIEW_LENGTH = 80
        const val SEPARATOR = " · "
    }
}
