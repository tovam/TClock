package org.fossify.clock.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTest {
    private val now = 1_800_000_000_000L

    @Test
    fun onlyCalendarAlarmsAtOrBeforeNowAreExpired() {
        assertTrue(calendarAlarm(now - 1L).isExpiredCalendarAlarm(now))
        assertTrue(calendarAlarm(now).isExpiredCalendarAlarm(now))
        assertFalse(calendarAlarm(now + 1L).isExpiredCalendarAlarm(now))
        assertFalse(calendarAlarm(0L).isExpiredCalendarAlarm(now))
        assertFalse(calendarAlarm(now - 1L).copy(oneShot = false).isExpiredCalendarAlarm(now))
        assertFalse(
            calendarAlarm(now - 1L)
                .copy(source = Alarm.SOURCE_MANUAL)
                .isExpiredCalendarAlarm(now)
        )
    }

    @Test
    fun relativeAlarmKeepsItsActionAndEventTitlesSeparate() {
        val named = calendarAlarm(now).copy(
            label = "Préparer le sac",
            calendarAlarmName = "Préparer le sac",
            calendarEventTitle = "Train régional"
        )
        val legacy = calendarAlarm(now).copy(label = "Train régional")

        assertEquals("Préparer le sac", named.relativeAlarmName())
        assertEquals("Train régional", named.relativeEventTitle())
        assertEquals("Train régional", legacy.relativeAlarmName())
        assertEquals("Train régional", legacy.relativeEventTitle())
    }

    private fun calendarAlarm(triggerAtMillis: Long) = Alarm(
        id = 1,
        timeInMinutes = 0,
        days = 0,
        isEnabled = true,
        vibrate = false,
        soundTitle = "",
        soundUri = "",
        label = "",
        oneShot = true,
        triggerAtMillis = triggerAtMillis,
        source = Alarm.SOURCE_CALENDAR
    )
}
