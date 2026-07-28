package org.fossify.clock.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.clock.R
import org.fossify.clock.adapters.CalendarDiagnosticsAdapter
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.engine.AndroidCl1Coordinator
import org.fossify.clock.cl1.engine.Cl1Coordinator
import org.fossify.clock.cl1.engine.Cl1DurationConversion
import org.fossify.clock.cl1.engine.Cl1OperationResult
import org.fossify.clock.cl1.engine.Cl1OverrideConversion
import org.fossify.clock.cl1.engine.Cl1RelationSnapshot
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.ui.Cl1RelationUiAction
import org.fossify.clock.cl1.ui.availableUiActions
import org.fossify.clock.databinding.FragmentCalendarDiagnosticsBinding
import org.fossify.clock.dialogs.Cl1CopyOptionsDialog
import org.fossify.clock.extensions.config
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarSyncScheduler
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors

class CalendarDiagnosticsFragment : Fragment() {
    private var _binding: FragmentCalendarDiagnosticsBinding? = null
    private val binding: FragmentCalendarDiagnosticsBinding
        get() = checkNotNull(_binding)

    private lateinit var adapter: CalendarDiagnosticsAdapter
    private var loadJob: Job? = null
    private var hasRenderedSnapshot = false
    private var currentSnapshot: CalendarDiagnosticsSnapshot? = null

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            context?.config?.calendarPermissionAsked = true
            context?.config?.calendarWritePermissionAsked = true
            if (_binding == null) {
                return@registerForActivityResult
            }
            if (context?.let { CalendarAlarmSync.hasCalendarPermission(it) } == true) {
                loadDiagnostics(syncFirst = true)
            } else {
                loadDiagnostics(syncFirst = false)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCalendarDiagnosticsBinding.inflate(inflater, container, false)
        hasRenderedSnapshot = false
        adapter = CalendarDiagnosticsAdapter(
            context = requireContext(),
            textColor = requireContext().getProperTextColor(),
            backgroundColor = requireContext().getProperBackgroundColor(),
            primaryColor = requireContext().getProperPrimaryColor(),
            onRefresh = ::requestCalendarRefresh,
            onGrantCalendarPermission = {
                calendarPermissionLauncher.launch(CalendarAlarmSync.REQUIRED_PERMISSIONS)
            },
            onCreateCl1Copy = ::showCreateCl1Copy,
            onCl1RelationActions = ::showCl1RelationActions
        )
        binding.calendarDiagnosticsList.adapter = adapter
        binding.calendarDiagnosticsStateRetry.setOnClickListener {
            requestCalendarRefresh()
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateColors()
        if (loadJob?.isActive != true) {
            loadDiagnostics(syncFirst = false)
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        currentSnapshot = null
        binding.calendarDiagnosticsList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun updateColors() {
        val safeContext = context ?: return
        val safeBinding = _binding ?: return
        safeBinding.root.setBackgroundColor(safeContext.getProperBackgroundColor())
        safeContext.updateTextColors(safeBinding.root)
        adapter.updateColors(
            newTextColor = safeContext.getProperTextColor(),
            newBackgroundColor = safeContext.getProperBackgroundColor(),
            newPrimaryColor = safeContext.getProperPrimaryColor()
        )
    }

    private fun loadDiagnostics(syncFirst: Boolean) {
        if (_binding == null) {
            return
        }
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            showLoading()
            val applicationContext = requireContext().applicationContext
            val outcome = withContext(Dispatchers.IO) {
                loadInBackground(applicationContext, syncFirst)
            }
            if (outcome == null) {
                showUnexpectedError()
            } else {
                render(outcome)
            }
        }
    }

    private fun requestCalendarRefresh() {
        if (CalendarAlarmSync.hasCalendarPermission(requireContext())) {
            loadDiagnostics(syncFirst = true)
        } else {
            calendarPermissionLauncher.launch(CalendarAlarmSync.REQUIRED_PERMISSIONS)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadInBackground(
        applicationContext: android.content.Context,
        syncFirst: Boolean,
    ): LoadOutcome? {
        return try {
            val syncFailed = if (syncFirst) {
                val syncResult = CalendarAlarmSync.sync(applicationContext)
                if (!syncResult.permissionMissing) {
                    CalendarSyncScheduler.schedule(applicationContext)
                }
                syncResult.failed
            } else {
                false
            }
            LoadOutcome(
                snapshot = CalendarAlarmSync.loadDiagnostics(applicationContext),
                syncFailed = syncFailed
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to load calendar diagnostics", exception)
            null
        }
    }

    private fun showLoading() = binding.apply {
        adapter.setRefreshing(true)
        calendarDiagnosticsProgress.beVisibleIf(!hasRenderedSnapshot)
        calendarDiagnosticsState.beVisibleIf(false)
        if (!hasRenderedSnapshot) {
            calendarDiagnosticsList.beVisibleIf(false)
        }
    }

    private fun render(outcome: LoadOutcome) = binding.apply {
        val snapshot = outcome.snapshot

        calendarDiagnosticsProgress.beVisibleIf(false)
        calendarDiagnosticsState.beVisibleIf(false)
        currentSnapshot = snapshot
        adapter.submitSnapshot(snapshot, outcome.syncFailed)
        calendarDiagnosticsList.beVisibleIf(true)
        hasRenderedSnapshot = true
    }

    private fun showUnexpectedError() = binding.apply {
        adapter.setRefreshing(false)
        calendarDiagnosticsProgress.beVisibleIf(false)
        if (hasRenderedSnapshot) {
            Toast.makeText(
                requireContext(),
                R.string.calendar_diagnostics_unexpected_error,
                Toast.LENGTH_LONG
            ).show()
        } else {
            calendarDiagnosticsList.beVisibleIf(false)
            calendarDiagnosticsStateMessage.apply {
                text = getString(R.string.calendar_diagnostics_unexpected_error)
            }
            calendarDiagnosticsState.beVisibleIf(true)
        }
    }

    private fun showCreateCl1Copy(source: Cl1EventSnapshot) {
        showDestinationPicker { destination ->
            Cl1CopyOptionsDialog(
                activity = simpleActivity(),
                source = source,
                titleId = R.string.cl1_copy_options_title
            ) { overrides ->
                executeCl1Operation { coordinator, _ ->
                    coordinator.createRelation(
                        sourceRef = source.ref,
                        destinationRef = destination.ref,
                        overrides = overrides
                    )
                }
            }
        }
    }

    private fun showCl1RelationActions(relation: Cl1RelationSnapshot) {
        val cl1 = currentSnapshot?.cl1 ?: return
        val actions = relation.availableUiActions(cl1.mutationsAllowed)
        if (actions.isEmpty()) {
            return
        }
        simpleActivity().getAlertDialogBuilder()
            .setTitle(R.string.cl1_relation_actions_title)
            .setItems(
                actions.map { getString(it.labelResource()) }.toTypedArray()
            ) { _, index ->
                performCl1RelationAction(relation, actions[index])
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun performCl1RelationAction(
        relation: Cl1RelationSnapshot,
        action: Cl1RelationUiAction,
    ) {
        when (action) {
            Cl1RelationUiAction.REPAIR -> showRepairRelation(relation)
            Cl1RelationUiAction.RESTORE_FROM_SOURCE -> confirmCl1Operation(
                messageId = R.string.cl1_confirm_restore
            ) {
                executeCl1Operation { coordinator, _ ->
                    coordinator.restoreFromSource(relation)
                }
            }

            Cl1RelationUiAction.APPLY_COPY_TO_SOURCE -> confirmCl1Operation(
                messageId = R.string.cl1_confirm_apply_copy
            ) {
                executeCl1Operation { coordinator, _ ->
                    coordinator.applyCopyToSource(relation)
                }
            }

            Cl1RelationUiAction.CONVERT_TO_OVERRIDES ->
                showConvertCopyToOverrides(relation)

            Cl1RelationUiAction.CHANGE_DESTINATION ->
                showChangeDestination(relation)

            Cl1RelationUiAction.UNLINK -> confirmCl1Operation(
                messageId = R.string.cl1_confirm_unlink
            ) {
                executeCl1Operation { coordinator, _ ->
                    coordinator.unlink(relation)
                }
            }

            Cl1RelationUiAction.DELETE_SOURCE_AND_COPIES -> confirmCl1Operation(
                messageId = R.string.cl1_confirm_delete_source
            ) {
                val sourceRef = relation.source?.ref
                if (sourceRef == null) {
                    showCl1OperationFailure()
                } else {
                    executeCl1Operation { coordinator, snapshot ->
                        val discovery = checkNotNull(snapshot.cl1).discovery
                        coordinator.deleteSource(sourceRef, discovery)
                    }
                }
            }
        }
    }

    private fun showRepairRelation(relation: Cl1RelationSnapshot) {
        val source = relation.source
        if (source == null) {
            showCl1OperationFailure()
            return
        }
        showDestinationPicker { destination ->
            Cl1CopyOptionsDialog(
                activity = simpleActivity(),
                source = source,
                titleId = R.string.cl1_repair_options_title
            ) { overrides ->
                executeCl1Operation { coordinator, _ ->
                    coordinator.repairRelation(
                        relation = relation,
                        destinationRef = destination.ref,
                        overrides = overrides
                    )
                }
            }
        }
    }

    private fun showChangeDestination(relation: Cl1RelationSnapshot) {
        showDestinationPicker(
            excludedCalendarId = relation.mirror?.ref?.calendarId
        ) { destination ->
            executeCl1Operation { coordinator, _ ->
                coordinator.changeDestination(relation, destination.ref)
            }
        }
    }

    private fun showConvertCopyToOverrides(relation: Cl1RelationSnapshot) {
        val source = relation.source?.canonicalEventSafely()
        val mirror = relation.mirror?.canonicalEventSafely()
        if (source == null || mirror == null) {
            showCl1OperationFailure()
            return
        }

        if (source.title == mirror.title) {
            chooseDurationConversion(
                relation = relation,
                source = source,
                mirror = mirror,
                titleOverride = null
            )
            return
        }

        val titleChoices = buildList {
            add(
                getString(R.string.cl1_convert_title_exact) to
                    Cl1TitleOverride.Replacement(mirror.title)
            )
            if (
                source.title.isNotEmpty() &&
                mirror.title.contains(source.title)
            ) {
                add(
                    getString(R.string.cl1_convert_title_template) to
                        Cl1TitleOverride.Template(
                            mirror.title.replace(source.title, SOURCE_TITLE_TOKEN)
                        )
                )
            }
        }
        if (titleChoices.size == 1) {
            chooseDurationConversion(
                relation = relation,
                source = source,
                mirror = mirror,
                titleOverride = titleChoices.single().second
            )
            return
        }
        simpleActivity().getAlertDialogBuilder()
            .setTitle(R.string.cl1_convert_title_choice)
            .setItems(titleChoices.map { it.first }.toTypedArray()) { _, index ->
                chooseDurationConversion(
                    relation = relation,
                    source = source,
                    mirror = mirror,
                    titleOverride = titleChoices[index].second
                )
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun chooseDurationConversion(
        relation: Cl1RelationSnapshot,
        source: Cl1CanonicalEvent,
        mirror: Cl1CanonicalEvent,
        titleOverride: Cl1TitleOverride?,
    ) {
        val sourceDuration = source.durationSafely()
        val mirrorDuration = mirror.durationSafely()
        if (sourceDuration == null || mirrorDuration == null) {
            showCl1OperationFailure()
            return
        }
        if (sourceDuration == mirrorDuration) {
            executeOverrideConversion(
                relation = relation,
                titleOverride = titleOverride,
                durationMode = null
            )
            return
        }
        val choices = listOf(
            getString(R.string.cl1_convert_duration_fixed) to
                Cl1DurationConversion.FIXED,
            getString(R.string.cl1_convert_duration_delta) to
                Cl1DurationConversion.DELTA
        )
        simpleActivity().getAlertDialogBuilder()
            .setTitle(R.string.cl1_convert_duration_choice)
            .setItems(choices.map { it.first }.toTypedArray()) { _, index ->
                executeOverrideConversion(
                    relation = relation,
                    titleOverride = titleOverride,
                    durationMode = choices[index].second
                )
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun executeOverrideConversion(
        relation: Cl1RelationSnapshot,
        titleOverride: Cl1TitleOverride?,
        durationMode: Cl1DurationConversion?,
    ) {
        executeCl1Operation { coordinator, _ ->
            coordinator.convertCopyToOverrides(
                relation = relation,
                conversion = Cl1OverrideConversion(
                    titleOverride = titleOverride,
                    durationMode = durationMode
                )
            )
        }
    }

    private fun showDestinationPicker(
        excludedCalendarId: Long? = null,
        callback: (Cl1CalendarDescriptor) -> Unit,
    ) {
        val destinations = currentSnapshot?.cl1?.calendars
            .orEmpty()
            .filter {
                it.supportsMirrorRelations &&
                    it.ref.calendarId != excludedCalendarId
            }
            .sortedBy { it.displayName.lowercase() }
        if (destinations.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.cl1_no_destination,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = destinations.map { destination ->
            getString(
                R.string.cl1_destination_item,
                destination.displayName,
                destination.canonicalAccountEmail?.value
                    ?: destination.accountName
            )
        }
        simpleActivity().getAlertDialogBuilder()
            .setTitle(R.string.cl1_select_destination)
            .setItems(labels.toTypedArray()) { _, index ->
                callback(destinations[index])
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun confirmCl1Operation(
        messageId: Int,
        callback: () -> Unit,
    ) {
        ConfirmationDialog(
            activity = simpleActivity(),
            messageId = messageId,
            positive = R.string.cl1_confirm_destructive,
            negative = org.fossify.commons.R.string.cancel
        ) {
            callback()
        }
    }

    private fun executeCl1Operation(
        operation: (
            coordinator: Cl1Coordinator,
            snapshot: CalendarDiagnosticsSnapshot,
        ) -> Cl1OperationResult,
    ) {
        val baseline = currentSnapshot ?: return
        val applicationContext = requireContext().applicationContext
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            showLoading()
            val outcome = withContext(Dispatchers.IO) {
                runCl1Operation(
                    applicationContext = applicationContext,
                    baseline = baseline,
                    operation = operation
                )
            }
            if (_binding == null) {
                return@launch
            }
            if (outcome == null) {
                showUnexpectedError()
            } else {
                render(outcome.loadOutcome)
                showCl1OperationResult(outcome.result)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runCl1Operation(
        applicationContext: android.content.Context,
        baseline: CalendarDiagnosticsSnapshot,
        operation: (
            coordinator: Cl1Coordinator,
            snapshot: CalendarDiagnosticsSnapshot,
        ) -> Cl1OperationResult,
    ): Cl1ActionOutcome? {
        return try {
            val result = operation(
                AndroidCl1Coordinator.from(applicationContext),
                baseline
            )
            val syncResult = CalendarAlarmSync.sync(applicationContext)
            if (!syncResult.permissionMissing) {
                CalendarSyncScheduler.schedule(applicationContext)
            }
            Cl1ActionOutcome(
                result = result,
                loadOutcome = LoadOutcome(
                    snapshot = CalendarAlarmSync.loadDiagnostics(applicationContext),
                    syncFailed = syncResult.failed
                )
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to perform CL1 operation", exception)
            null
        }
    }

    private fun showCl1OperationResult(result: Cl1OperationResult) {
        val message = when (result) {
            is Cl1OperationResult.Completed ->
                getString(R.string.cl1_operation_completed)

            is Cl1OperationResult.Pending ->
                getString(R.string.cl1_operation_pending, result.reason)

            is Cl1OperationResult.Conflict ->
                getString(R.string.cl1_operation_conflict, result.reason)

            is Cl1OperationResult.Rejected ->
                getString(R.string.cl1_operation_rejected, result.reason)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showCl1OperationFailure() {
        Toast.makeText(
            requireContext(),
            R.string.cl1_operation_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun simpleActivity(): BaseSimpleActivity {
        return requireActivity() as BaseSimpleActivity
    }

    private fun Cl1EventSnapshot.canonicalEventSafely(): Cl1CanonicalEvent? {
        return try {
            canonicalEvent()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Cl1CanonicalEvent.durationSafely(): Long? {
        return try {
            Math.subtractExact(endUnixSeconds, startUnixSeconds)
                .takeIf { it > 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun Cl1RelationUiAction.labelResource(): Int {
        return when (this) {
            Cl1RelationUiAction.REPAIR -> R.string.cl1_action_repair
            Cl1RelationUiAction.RESTORE_FROM_SOURCE -> R.string.cl1_action_restore
            Cl1RelationUiAction.APPLY_COPY_TO_SOURCE ->
                R.string.cl1_action_apply_copy

            Cl1RelationUiAction.CONVERT_TO_OVERRIDES ->
                R.string.cl1_action_convert

            Cl1RelationUiAction.CHANGE_DESTINATION ->
                R.string.cl1_action_change_destination

            Cl1RelationUiAction.UNLINK -> R.string.cl1_action_unlink
            Cl1RelationUiAction.DELETE_SOURCE_AND_COPIES ->
                R.string.cl1_action_delete_source
        }
    }

    private data class LoadOutcome(
        val snapshot: CalendarDiagnosticsSnapshot,
        val syncFailed: Boolean,
    )

    private data class Cl1ActionOutcome(
        val result: Cl1OperationResult,
        val loadOutcome: LoadOutcome,
    )

    private companion object {
        const val TAG = "CalendarDiagnosticsUI"
        const val SOURCE_TITLE_TOKEN = "{source}"
    }
}
