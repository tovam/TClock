package org.fossify.clock.models

import androidx.annotation.Keep
import org.fossify.clock.helpers.TODAY_BIT
import org.fossify.clock.helpers.TOMORROW_BIT

@Keep
@kotlinx.serialization.Serializable
data class Alarm(
    var id: Int,
    var timeInMinutes: Int,
    var days: Int,
    var isEnabled: Boolean,
    var vibrate: Boolean,
    var soundTitle: String,
    var soundUri: String,
    var label: String,
    var oneShot: Boolean = false,
    var triggerAtMillis: Long = 0L,
    var source: String = SOURCE_MANUAL,
    var calendarKey: String = "",
    var calendarEventId: Long = 0L,
    var calendarEventStartMillis: Long = 0L,
    var calendarOffsetMinutes: Int = 0,
) {
    fun isRecurring() = days > 0

    fun isToday() = days == TODAY_BIT

    fun isTomorrow() = days == TOMORROW_BIT

    fun isCalendarAlarm() = source == SOURCE_CALENDAR

    fun isExpiredCalendarAlarm(nowMillis: Long = System.currentTimeMillis()) =
        isCalendarAlarm() && oneShot && triggerAtMillis > 0L && triggerAtMillis <= nowMillis

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_CALENDAR = "calendar"
    }
}

@Keep
data class ObfuscatedAlarm(
    var a: Int,
    var b: Int,
    var c: Int,
    var d: Boolean,
    var e: Boolean,
    var f: String,
    var g: String,
    var h: String,
    var i: Boolean = false,
) {
    fun toAlarm() = Alarm(a, b, c, d, e, f, g, h, i)
}
