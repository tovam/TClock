package org.fossify.clock.fragments

import android.Manifest
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
import org.fossify.clock.databinding.FragmentCalendarDiagnosticsBinding
import org.fossify.clock.extensions.config
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarDiagnosticsSnapshot
import org.fossify.clock.helpers.CalendarSyncScheduler
import org.fossify.commons.extensions.beVisibleIf
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
        hasRenderedSnapshot = false
        adapter = CalendarDiagnosticsAdapter(
            context = requireContext(),
            textColor = requireContext().getProperTextColor(),
            backgroundColor = requireContext().getProperBackgroundColor(),
            primaryColor = requireContext().getProperPrimaryColor(),
            onRefresh = ::requestCalendarRefresh,
            onGrantCalendarPermission = {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
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
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
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

    private data class LoadOutcome(
        val snapshot: CalendarDiagnosticsSnapshot,
        val syncFailed: Boolean,
    )

    private companion object {
        const val TAG = "CalendarDiagnosticsUI"
    }
}
