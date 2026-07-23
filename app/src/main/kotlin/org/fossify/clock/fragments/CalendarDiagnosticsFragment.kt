package org.fossify.clock.fragments

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.clock.R
import org.fossify.clock.adapters.CalendarDiagnosticsAdapter
import org.fossify.clock.databinding.FragmentCalendarDiagnosticsBinding
import org.fossify.clock.extensions.config
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarDiagnosticsProviderState
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarSyncScheduler
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import java.text.DateFormat
import java.util.Date

class CalendarDiagnosticsFragment : Fragment() {
    private var _binding: FragmentCalendarDiagnosticsBinding? = null
    private val binding: FragmentCalendarDiagnosticsBinding
        get() = checkNotNull(_binding)

    private lateinit var adapter: CalendarDiagnosticsAdapter
    private var loadJob: Job? = null
    private var hasRenderedSnapshot = false

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            context?.config?.calendarPermissionAsked = true
            if (_binding == null) {
                return@registerForActivityResult
            }
            if (granted) {
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
        adapter = CalendarDiagnosticsAdapter(
            context = requireContext(),
            textColor = requireContext().getProperTextColor(),
            backgroundColor = requireContext().getProperBackgroundColor(),
            primaryColor = requireContext().getProperPrimaryColor()
        )
        binding.calendarDiagnosticsList.adapter = adapter
        binding.calendarDiagnosticsRefresh.setOnClickListener {
            if (CalendarAlarmSync.hasCalendarPermission(requireContext())) {
                loadDiagnostics(syncFirst = true)
            } else {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
        binding.calendarDiagnosticsGrantPermission.setOnClickListener {
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
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
        calendarDiagnosticsProgress.beVisibleIf(true)
        calendarDiagnosticsRefresh.isEnabled = false
        calendarDiagnosticsGrantPermission.isEnabled = false
        calendarDiagnosticsEmpty.beVisibleIf(false)
        if (!hasRenderedSnapshot) {
            calendarDiagnosticsSummary.beVisibleIf(false)
            calendarDiagnosticsList.beVisibleIf(false)
            calendarDiagnosticsPermissionMessage.beVisibleIf(false)
            calendarDiagnosticsGrantPermission.beVisibleIf(false)
            calendarDiagnosticsProviderError.beVisibleIf(false)
        }
    }

    private fun render(outcome: LoadOutcome) = binding.apply {
        val snapshot = outcome.snapshot
        val permissionMissing =
            snapshot.providerState == CalendarDiagnosticsProviderState.PERMISSION_MISSING
        val providerError =
            snapshot.providerState == CalendarDiagnosticsProviderState.PROVIDER_ERROR
        val hasRows = snapshot.events.isNotEmpty() || snapshot.unlinkedAlarms.isNotEmpty()

        calendarDiagnosticsProgress.beVisibleIf(false)
        calendarDiagnosticsRefresh.isEnabled = true
        calendarDiagnosticsGrantPermission.isEnabled = true
        calendarDiagnosticsPermissionMessage.beVisibleIf(permissionMissing)
        calendarDiagnosticsGrantPermission.beVisibleIf(permissionMissing)
        calendarDiagnosticsProviderError.apply {
            text = getString(
                when {
                    providerError -> R.string.calendar_diagnostics_provider_error
                    outcome.syncFailed -> R.string.calendar_diagnostics_sync_error
                    else -> R.string.calendar_diagnostics_provider_error
                }
            )
            beVisibleIf(providerError || outcome.syncFailed)
        }

        calendarDiagnosticsSummary.beVisibleIf(true)
        calendarDiagnosticsWindow.text = getString(
            R.string.calendar_diagnostics_window_summary,
            formatDateTime(snapshot.displayBeginMillis),
            formatDateTime(snapshot.displayEndMillis)
        )
        calendarDiagnosticsQueryWindow.text = getString(
            R.string.calendar_diagnostics_query_summary,
            formatDateTime(snapshot.queryBeginMillis),
            formatDateTime(snapshot.queryEndMillis)
        )
        calendarDiagnosticsCounts.text = getString(
            R.string.calendar_diagnostics_counts_summary,
            snapshot.counts.displayWindowEvents,
            snapshot.counts.relatedEventsOutsideWindow,
            snapshot.counts.calendarAlarms
        )
        calendarDiagnosticsHealth.text = getString(
            R.string.calendar_diagnostics_health_summary,
            snapshot.counts.exactAlarms,
            snapshot.counts.driftedAlarms,
            snapshot.counts.duplicateAlarms,
            snapshot.counts.eligibleMarkersWithoutAlarm,
            snapshot.counts.markerMissingAlarms,
            snapshot.counts.eventMissingAlarms,
            snapshot.counts.invalidMarkerEvents,
            snapshot.counts.unverifiableAlarms
        )
        calendarDiagnosticsCaptured.text = getString(
            R.string.calendar_diagnostics_captured_summary,
            formatDateTime(snapshot.capturedAtMillis)
        )

        adapter.submitSnapshot(snapshot)
        calendarDiagnosticsList.beVisibleIf(hasRows)
        calendarDiagnosticsEmpty.beVisibleIf(
            snapshot.providerState == CalendarDiagnosticsProviderState.AVAILABLE && !hasRows
        )
        hasRenderedSnapshot = true
    }

    private fun showUnexpectedError() = binding.apply {
        calendarDiagnosticsProgress.beVisibleIf(false)
        calendarDiagnosticsRefresh.isEnabled = true
        calendarDiagnosticsGrantPermission.isEnabled = true
        calendarDiagnosticsProviderError.apply {
            text = getString(R.string.calendar_diagnostics_unexpected_error)
            beVisibleIf(true)
        }
        if (!hasRenderedSnapshot) {
            calendarDiagnosticsSummary.beVisibleIf(false)
            calendarDiagnosticsList.beVisibleIf(false)
            calendarDiagnosticsEmpty.beVisibleIf(false)
            calendarDiagnosticsPermissionMessage.beVisibleIf(false)
            calendarDiagnosticsGrantPermission.beVisibleIf(false)
        }
    }

    private fun formatDateTime(timestamp: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(timestamp))
    }

    private data class LoadOutcome(
        val snapshot: CalendarDiagnosticsSnapshot,
        val syncFailed: Boolean,
    )

    private companion object {
        const val TAG = "CalendarDiagnosticsUI"
    }
}
