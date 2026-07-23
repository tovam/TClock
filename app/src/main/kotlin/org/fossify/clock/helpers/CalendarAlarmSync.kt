package org.fossify.clock.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.clock.R
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.setupAlarmClock
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.greenrobot.eventbus.EventBus
import java.util.Calendar
import java.util.concurrent.TimeUnit

object CalendarAlarmSync {
    const val WINDOW_DAYS = CalendarAlarmWindow.WINDOW_DAYS

    private val lock = Any()

    data class Result(
        val created: Int = 0,
        val updated: Int = 0,
        val removed: Int = 0,
        val total: Int = 0,
        val permissionMissing: Boolean = false,
        val failed: Boolean = false,
    )

    private data class Candidate(
        val key: String,
        val eventId: Long,
        val eventStartMillis: Long,
        val triggerAtMillis: Long,
        val offsetMinutes: Int,
        val label: String,
    )

    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun loadDiagnostics(context: Context): CalendarDiagnosticsSnapshot = synchronized(lock) {
        CalendarDiagnosticsRepository.load(context)
    }

    @Suppress("TooGenericExceptionCaught")
    fun sync(context: Context): Result = synchronized(lock) {
        if (!hasCalendarPermission(context)) {
            CalendarSyncScheduler.cancel(context)
            val removed = removeCalendarAlarms(context)
            return@synchronized Result(
                removed = removed,
                permissionMissing = true
            )
        }

        val now = System.currentTimeMillis()
        val window = CalendarAlarmWindow.rangeAt(now)
        val db = context.dbHelper
        val existingAlarms = db.getCalendarAlarms()
        val candidates = try {
            readCandidates(
                context = context,
                queryBeginMillis = window.queryBeginMillis,
                queryEndMillis = window.queryEndMillis,
                triggerBeginMillis = window.triggerBeginMillis,
                triggerEndMillis = window.triggerEndMillis
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "Calendar permission was lost during synchronization", exception)
            if (!hasCalendarPermission(context)) {
                CalendarSyncScheduler.cancel(context)
                val removed = removeCalendarAlarms(context, existingAlarms)
                return@synchronized Result(
                    removed = removed,
                    permissionMissing = true
                )
            }
            return@synchronized Result(total = existingAlarms.size, failed = true)
        } catch (exception: Exception) {
            Log.e(TAG, "Calendar synchronization failed", exception)
            return@synchronized Result(total = existingAlarms.size, failed = true)
        }
        val existingByKey = existingAlarms.associateBy { it.calendarKey }.toMutableMap()
        var created = 0
        var updated = 0
        var removed = 0

        candidates.values.forEach { candidate ->
            val existing = existingByKey.remove(candidate.key)
            if (existing == null) {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = candidate.triggerAtMillis
                }
                val alarm = context.createNewAlarm(
                    timeInMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
                        calendar.get(Calendar.MINUTE),
                    weekDays = 0
                ).apply {
                    isEnabled = true
                    oneShot = true
                    triggerAtMillis = candidate.triggerAtMillis
                    source = Alarm.SOURCE_CALENDAR
                    calendarKey = candidate.key
                    calendarEventId = candidate.eventId
                    calendarEventStartMillis = candidate.eventStartMillis
                    calendarOffsetMinutes = candidate.offsetMinutes
                    label = candidate.label
                }
                alarm.id = db.insertAlarm(alarm)
                if (alarm.id > 0) {
                    context.setupAlarmClock(alarm, alarm.triggerAtMillis)
                    created++
                }
            } else if (existing.needsUpdate(candidate)) {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = candidate.triggerAtMillis
                }
                val updatedAlarm = existing.copy(
                    timeInMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
                        calendar.get(Calendar.MINUTE),
                    days = 0,
                    isEnabled = true,
                    oneShot = true,
                    triggerAtMillis = candidate.triggerAtMillis,
                    calendarKey = candidate.key,
                    calendarEventId = candidate.eventId,
                    calendarEventStartMillis = candidate.eventStartMillis,
                    calendarOffsetMinutes = candidate.offsetMinutes,
                    label = candidate.label
                )
                if (db.updateAlarm(updatedAlarm)) {
                    context.cancelAlarmClock(existing)
                    context.setupAlarmClock(updatedAlarm, updatedAlarm.triggerAtMillis)
                    updated++
                }
            }
        }

        val staleAlarms = existingByKey.values.filter { alarm ->
            CalendarAlarmWindow.shouldRemoveStaleAlarm(alarm.triggerAtMillis, now)
        }
        if (staleAlarms.isNotEmpty()) {
            db.deleteAlarms(ArrayList(staleAlarms))
            removed = staleAlarms.size
        }

        context.updateWidgets()
        EventBus.getDefault().post(AlarmEvent.Refresh)
        Result(
            created = created,
            updated = updated,
            removed = removed,
            total = candidates.size
        )
    }

    private fun removeCalendarAlarms(
        context: Context,
        alarms: List<Alarm> = context.dbHelper.getCalendarAlarms(),
    ): Int {
        if (alarms.isEmpty()) {
            return 0
        }

        context.dbHelper.deleteAlarms(ArrayList(alarms))
        context.updateWidgets()
        EventBus.getDefault().post(AlarmEvent.Refresh)
        return alarms.size
    }

    private fun Alarm.needsUpdate(candidate: Candidate): Boolean {
        return triggerAtMillis != candidate.triggerAtMillis ||
            calendarKey != candidate.key ||
            calendarEventId != candidate.eventId ||
            calendarEventStartMillis != candidate.eventStartMillis ||
            calendarOffsetMinutes != candidate.offsetMinutes ||
            label != candidate.label ||
            days != 0 ||
            !oneShot ||
            !isEnabled
    }

    private fun readCandidates(
        context: Context,
        queryBeginMillis: Long,
        queryEndMillis: Long,
        triggerBeginMillis: Long,
        triggerEndMillis: Long,
    ): Map<String, Candidate> {
        val result = LinkedHashMap<String, Candidate>()
        val records = CalendarDiagnosticsRepository.readVisibleInstances(
            context = context,
            queryBeginMillis = queryBeginMillis,
            queryEndMillis = queryEndMillis
        )
        records.forEach recordLoop@{ record ->
            if (record.isAllDay || record.isCanceled) {
                return@recordLoop
            }

            val offsets = TClockPatternParser.parseOffsets(record.description)
            val title = record.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.calendar_untitled_event)
            offsets.forEach offsetLoop@{ offsetMinutes ->
                if (!CalendarAlarmWindow.supportsOffset(offsetMinutes)) {
                    return@offsetLoop
                }
                val triggerAtMillis =
                    record.beginMillis + TimeUnit.MINUTES.toMillis(offsetMinutes.toLong())
                if (triggerAtMillis in (triggerBeginMillis + 1)..triggerEndMillis) {
                    val key = CalendarAlarmKey(
                        occurrence = CalendarOccurrenceKey(
                            eventId = record.eventId,
                            beginMillis = record.beginMillis
                        ),
                        offsetMinutes = offsetMinutes
                    ).persistedValue
                    result[key] = Candidate(
                        key = key,
                        eventId = record.eventId,
                        eventStartMillis = record.beginMillis,
                        triggerAtMillis = triggerAtMillis,
                        offsetMinutes = offsetMinutes,
                        label = title
                    )
                }
            }
        }
        return result
    }

    private const val TAG = "CalendarAlarmSync"
}
