package org.fossify.clock.activities

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.clock.R
import org.fossify.clock.adapters.ScheduledAlarmsAdapter
import org.fossify.clock.databinding.ActivityScheduledAlarmsBinding
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarSyncScheduler
import org.fossify.clock.helpers.getTimeOfNextAlarm
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread

class ScheduledAlarmsActivity : SimpleActivity() {
    private val binding: ActivityScheduledAlarmsBinding by viewBinding(
        ActivityScheduledAlarmsBinding::inflate
    )
    private lateinit var adapter: ScheduledAlarmsAdapter

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            config.calendarPermissionAsked = true
            if (granted) {
                CalendarSyncScheduler.schedule(this)
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.scheduledAlarmsHolder))

        adapter = ScheduledAlarmsAdapter(getProperTextColor())
        binding.scheduledAlarmsList.adapter = adapter
        binding.scheduledAlarmsGrantPermission.setOnClickListener {
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
        binding.scheduledAlarmsRefresh.setOnClickListener {
            if (CalendarAlarmSync.hasCalendarPermission(this)) {
                refresh()
            } else {
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.scheduledAlarmsAppbar, NavigationIcon.Arrow)
        binding.root.setBackgroundColor(getProperBackgroundColor())
        updateTextColors(binding.scheduledAlarmsHolder)
        refresh()
    }

    private fun refresh() {
        binding.scheduledAlarmsProgress.beVisibleIf(true)
        binding.scheduledAlarmsSyncError.beVisibleIf(false)
        binding.scheduledAlarmsPermissionMessage.beVisibleIf(
            !CalendarAlarmSync.hasCalendarPermission(this)
        )
        binding.scheduledAlarmsGrantPermission.beVisibleIf(
            !CalendarAlarmSync.hasCalendarPermission(this)
        )

        ensureBackgroundThread {
            val syncResult = CalendarAlarmSync.sync(this)
            if (!syncResult.permissionMissing) {
                CalendarSyncScheduler.schedule(this)
            }
            val now = System.currentTimeMillis()
            val items = dbHelper.getEnabledAlarms().mapNotNull { alarm ->
                val trigger = getTimeOfNextAlarm(alarm)?.timeInMillis ?: return@mapNotNull null
                if (trigger <= now) {
                    null
                } else {
                    ScheduledAlarmsAdapter.Item(alarm, trigger)
                }
            }.sortedBy { it.triggerAtMillis }

            runOnUiThread {
                binding.scheduledAlarmsProgress.beVisibleIf(false)
                binding.scheduledAlarmsSyncError.beVisibleIf(syncResult.failed)
                binding.scheduledAlarmsPlaceholder.beVisibleIf(items.isEmpty())
                adapter.updateItems(items)
            }
        }
    }
}
