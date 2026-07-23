package org.fossify.clock.fragments

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.TimeZonesAdapter
import org.fossify.clock.databinding.FragmentClockBinding
import org.fossify.clock.dialogs.AddTimeZonesDialog
import org.fossify.clock.dialogs.EditTimeZoneDialog
import org.fossify.clock.extensions.colorCompoundDrawable
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getAllTimeZonesModified
import org.fossify.clock.extensions.getFormattedDate
import org.fossify.clock.helpers.FORMAT_12H
import org.fossify.clock.helpers.FORMAT_12H_WITH_SECONDS
import org.fossify.clock.helpers.FORMAT_24H
import org.fossify.clock.helpers.FORMAT_24H_WITH_SECONDS
import org.fossify.clock.helpers.UpcomingAlarmCalculator
import org.fossify.clock.helpers.UpcomingAlarmOccurrence
import org.fossify.clock.helpers.getPassedSeconds
import org.fossify.clock.models.AlarmEvent
import org.fossify.clock.models.MyTimeZone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ClockFragment : Fragment() {
    private val ONE_SECOND = 1000L

    private var passedSeconds = 0
    private var calendar = Calendar.getInstance()
    private var alarmLoadJob: Job? = null
    private val updateHandler = Handler(Looper.getMainLooper())

    private var _binding: FragmentClockBinding? = null
    private val binding: FragmentClockBinding
        get() = checkNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentClockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        setupDateTime()

        val safeContext = context ?: return
        binding.clockDate.setTextColor(safeContext.getProperTextColor())
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        updateHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        alarmLoadJob?.cancel()
        alarmLoadJob = null
        updateHandler.removeCallbacksAndMessages(null)
        _binding?.clockUpcomingList?.removeAllViews()
        _binding = null
        super.onDestroyView()
    }

    private fun setupDateTime() {
        calendar = Calendar.getInstance()
        passedSeconds = getPassedSeconds()
        updateCurrentTime()
        updateDate()
        updateAlarm()
        setupViews()
    }

    private fun setupViews() {
        val safeContext = context ?: return
        binding.apply {
            safeContext.updateTextColors(clockFragment)
            clockTime.setTextColor(safeContext.getProperTextColor())
            val clockFormat = if (safeContext.config.use24HourFormat) {
                FORMAT_24H_WITH_SECONDS
            } else {
                FORMAT_12H_WITH_SECONDS
            }

            clockTime.format24Hour = clockFormat
            clockTime.format12Hour = clockFormat
            clockFab.setOnClickListener {
                fabClicked()
            }

            updateTimeZones()
        }
    }

    private fun updateCurrentTime() {
        val hours = (passedSeconds / 3600) % 24
        val minutes = (passedSeconds / 60) % 60
        val seconds = passedSeconds % 60
        if (seconds == 0) {
            if (hours == 0 && minutes == 0) {
                updateDate()
            }

            (binding.timeZonesList.adapter as? TimeZonesAdapter)?.updateTimes()
            updateAlarm()
        }

        updateHandler.postDelayed({
            passedSeconds++
            updateCurrentTime()
        }, ONE_SECOND)
    }

    private fun updateDate() {
        calendar = Calendar.getInstance()
        val formattedDate = requireContext().getFormattedDate(calendar)
        (binding.timeZonesList.adapter as? TimeZonesAdapter)?.todayDateString = formattedDate
    }

    fun updateAlarm() {
        val safeContext = context ?: return
        val lifecycleOwner = viewLifecycleOwnerLiveData.value ?: return
        alarmLoadJob?.cancel()
        alarmLoadJob = lifecycleOwner.lifecycleScope.launch {
            val upcomingAlarms = withContext(Dispatchers.IO) {
                loadUpcomingAlarms(safeContext)
            }
            renderUpcomingAlarms(safeContext, upcomingAlarms)
        }
    }

    private fun loadUpcomingAlarms(context: Context): List<UpcomingAlarmOccurrence> {
        return UpcomingAlarmCalculator.collect(
            alarms = context.dbHelper.getEnabledAlarms(),
            limit = MAX_UPCOMING_ALARMS
        )
    }

    private fun renderUpcomingAlarms(
        context: Context,
        upcomingAlarms: List<UpcomingAlarmOccurrence>,
    ) = binding.apply {
        val closestAlarm = upcomingAlarms.firstOrNull()
        clockAlarm.beVisibleIf(closestAlarm != null)
        if (closestAlarm != null) {
            clockAlarm.text = formatClosestAlarm(context, closestAlarm.triggerAtMillis)
            clockAlarm.colorCompoundDrawable(context.getProperTextColor())
        }

        clockUpcomingTitle.setTextColor(context.getProperTextColor())
        clockUpcomingList.removeAllViews()
        if (upcomingAlarms.isEmpty()) {
            addUpcomingAlarmLine(
                context = context,
                text = context.getString(R.string.clock_upcoming_alarms_empty)
            )
        } else {
            upcomingAlarms.forEach { upcomingAlarm ->
                addUpcomingAlarmLine(
                    context = context,
                    text = formatUpcomingAlarm(context, upcomingAlarm)
                )
            }
        }
    }

    private fun addUpcomingAlarmLine(context: Context, text: String) {
        val itemView = layoutInflater.inflate(
            R.layout.item_clock_upcoming_alarm,
            binding.clockUpcomingList,
            false
        ) as TextView
        itemView.text = text
        itemView.setTextColor(context.getProperTextColor())
        binding.clockUpcomingList.addView(itemView)
    }

    private fun formatClosestAlarm(context: Context, triggerAtMillis: Long): String {
        val closestAlarmTime = Calendar.getInstance().apply {
            timeInMillis = triggerAtMillis
        }
        val dayOfWeekIndex = (closestAlarmTime.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val dayOfWeek =
            context.resources.getStringArray(
                org.fossify.commons.R.array.week_days_short
            )[dayOfWeekIndex]
        val pattern = if (context.config.use24HourFormat) FORMAT_24H else FORMAT_12H
        val formattedTime = SimpleDateFormat(pattern, Locale.getDefault()).format(
            closestAlarmTime.time
        )
        return "$dayOfWeek $formattedTime"
    }

    private fun formatUpcomingAlarm(
        context: Context,
        upcomingAlarm: UpcomingAlarmOccurrence,
    ): String {
        val alarm = upcomingAlarm.alarm
        val dateTimeSkeleton =
            if (context.config.use24HourFormat) UPCOMING_24H_SKELETON else UPCOMING_12H_SKELETON
        val dateTimePattern = DateFormat.getBestDateTimePattern(
            Locale.getDefault(),
            dateTimeSkeleton
        )
        val dateTime = SimpleDateFormat(dateTimePattern, Locale.getDefault()).format(
            Date(upcomingAlarm.triggerAtMillis)
        )
        val source = if (alarm.isCalendarAlarm()) {
            context.getString(
                R.string.clock_upcoming_calendar_source,
                alarm.label.ifBlank {
                    context.getString(R.string.calendar_untitled_event)
                }
            )
        } else if (alarm.label.isBlank()) {
            context.getString(R.string.clock_upcoming_manual_source_without_label)
        } else {
            context.getString(R.string.clock_upcoming_manual_source, alarm.label)
        }
        return context.getString(R.string.clock_upcoming_alarm_line, dateTime, source)
    }

    private fun updateTimeZones() {
        val safeContext = activity as? SimpleActivity ?: return
        val selectedTimeZones = safeContext.config.selectedTimeZones
        binding.timeZonesList.visibility =
            if (selectedTimeZones.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        if (selectedTimeZones.isEmpty()) {
            return
        }

        val selectedTimeZoneIDs = selectedTimeZones.map { it.toInt() }
        val timeZones = ArrayList(
            safeContext.getAllTimeZonesModified()
                .filter { selectedTimeZoneIDs.contains(it.id) }
        )
        val currAdapter = binding.timeZonesList.adapter
        if (currAdapter == null) {
            TimeZonesAdapter(safeContext, timeZones, binding.timeZonesList) {
                EditTimeZoneDialog(safeContext, it as MyTimeZone) {
                    updateTimeZones()
                }
            }.apply {
                this@ClockFragment.binding.timeZonesList.adapter = this
            }
        } else {
            (currAdapter as TimeZonesAdapter).apply {
                updatePrimaryColor()
                updateBackgroundColor(safeContext.getProperBackgroundColor())
                updateTextColor(safeContext.getProperTextColor())
                updateItems(timeZones)
            }
        }
    }

    private fun fabClicked() {
        val safeContext = activity as? SimpleActivity ?: return
        AddTimeZonesDialog(safeContext) {
            updateTimeZones()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        updateAlarm()
    }

    private companion object {
        const val MAX_UPCOMING_ALARMS = 10
        const val UPCOMING_24H_SKELETON = "EEEdMMMHHmm"
        const val UPCOMING_12H_SKELETON = "EEEdMMMhmma"
    }
}
