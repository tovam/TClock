package org.fossify.clock.models

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
