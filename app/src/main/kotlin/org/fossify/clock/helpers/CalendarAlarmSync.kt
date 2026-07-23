package org.fossify.clock.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
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
    const val WINDOW_DAYS = 15L

    private val lock = Any()

    data class Result(
        val created: Int = 0,
        val updated: Int = 0,
        val removed: Int = 0,
        val total: Int = 0,
        val permissionMissing: Boolean = false,
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

    fun sync(context: Context): Result = synchronized(lock) {
        if (!hasCalendarPermission(context)) {
            return@synchronized Result(permissionMissing = true)
        }

        val now = System.currentTimeMillis()
        val windowEnd = now + TimeUnit.DAYS.toMillis(WINDOW_DAYS)
        val candidates = readCandidates(context, now, windowEnd)
        val db = context.dbHelper
        val existingByKey = db.getCalendarAlarms().associateBy { it.calendarKey }.toMutableMap()
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
                context.cancelAlarmClock(existing)
                existing.apply {
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = candidate.triggerAtMillis
                    }
                    timeInMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
                        calendar.get(Calendar.MINUTE)
                    isEnabled = true
                    triggerAtMillis = candidate.triggerAtMillis
                    calendarEventId = candidate.eventId
                    calendarEventStartMillis = candidate.eventStartMillis
                    calendarOffsetMinutes = candidate.offsetMinutes
                    label = candidate.label
                }
                if (db.updateAlarm(existing)) {
                    context.setupAlarmClock(existing, existing.triggerAtMillis)
                    updated++
                }
            }
        }

        existingByKey.values.forEach { staleAlarm ->
            db.deleteAlarms(arrayListOf(staleAlarm))
            removed++
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

    private fun Alarm.needsUpdate(candidate: Candidate): Boolean {
        return triggerAtMillis != candidate.triggerAtMillis ||
            calendarEventStartMillis != candidate.eventStartMillis ||
            calendarOffsetMinutes != candidate.offsetMinutes ||
            label != candidate.label ||
            !isEnabled
    }

    private fun readCandidates(
        context: Context,
        beginMillis: Long,
        endMillis: Long,
    ): Map<String, Candidate> {
        val result = LinkedHashMap<String, Candidate>()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.STATUS
        )

        CalendarContract.Instances.query(
            context.contentResolver,
            projection,
            beginMillis,
            endMillis
        )?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descriptionIndex =
                cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val statusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.STATUS)

            while (cursor.moveToNext()) {
                if (cursor.getInt(allDayIndex) == 1) {
                    continue
                }
                if (
                    !cursor.isNull(statusIndex) &&
                    cursor.getInt(statusIndex) == CalendarContract.Events.STATUS_CANCELED
                ) {
                    continue
                }

                val description =
                    if (cursor.isNull(descriptionIndex)) "" else cursor.getString(descriptionIndex)
                val offsets = TClockPatternParser.parseOffsets(description)
                if (offsets.isEmpty()) {
                    continue
                }

                val eventId = cursor.getLong(eventIdIndex)
                val eventStartMillis = cursor.getLong(beginIndex)
                val title = if (cursor.isNull(titleIndex)) {
                    context.getString(R.string.calendar_untitled_event)
                } else {
                    cursor.getString(titleIndex).ifBlank {
                        context.getString(R.string.calendar_untitled_event)
                    }
                }

                offsets.forEach { offsetMinutes ->
                    val triggerAtMillis =
                        eventStartMillis + TimeUnit.MINUTES.toMillis(offsetMinutes.toLong())
                    if (triggerAtMillis in (beginMillis + 1)..endMillis) {
                        val key = "$eventId:$eventStartMillis:$offsetMinutes"
                        result[key] = Candidate(
                            key = key,
                            eventId = eventId,
                            eventStartMillis = eventStartMillis,
                            triggerAtMillis = triggerAtMillis,
                            offsetMinutes = offsetMinutes,
                            label = title
                        )
                    }
                }
            }
        }
        return result
    }
}
