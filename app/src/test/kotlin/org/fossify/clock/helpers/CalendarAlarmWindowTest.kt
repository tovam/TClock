package org.fossify.clock.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CalendarAlarmWindowTest {
    private val now = 1_800_000_000_000L

    @Test
    fun queryRangeIncludesEventsAroundTheTriggerWindow() {
        val range = CalendarAlarmWindow.rangeAt(now)
        val positiveOffsetEventStart = now - TimeUnit.MINUTES.toMillis(30)
        val positiveOffsetTrigger =
            positiveOffsetEventStart + TimeUnit.MINUTES.toMillis(60)
        val negativeOffsetEventStart = now + TimeUnit.DAYS.toMillis(16)
        val negativeOffsetTrigger =
            negativeOffsetEventStart - TimeUnit.DAYS.toMillis(2)

        assertTrue(positiveOffsetEventStart in range.queryBeginMillis..range.queryEndMillis)
        assertTrue(positiveOffsetTrigger in range.triggerBeginMillis..range.triggerEndMillis)
        assertTrue(negativeOffsetEventStart in range.queryBeginMillis..range.queryEndMillis)
        assertTrue(negativeOffsetTrigger in range.triggerBeginMillis..range.triggerEndMillis)
    }

    @Test
    fun offsetsOutsideTheSupportedMarginAreRejected() {
        val supported = TimeUnit.DAYS.toMinutes(CalendarAlarmWindow.WINDOW_DAYS).toInt()

        assertTrue(CalendarAlarmWindow.supportsOffset(supported))
        assertTrue(CalendarAlarmWindow.supportsOffset(-supported))
        assertFalse(CalendarAlarmWindow.supportsOffset(supported + 1))
    }

    @Test
    fun recentlyTriggeredAlarmIsKeptForSnoozeOrDismissal() {
        assertFalse(
            CalendarAlarmWindow.shouldRemoveStaleAlarm(
                triggerAtMillis = now - TimeUnit.HOURS.toMillis(1),
                now = now
            )
        )
        assertTrue(
            CalendarAlarmWindow.shouldRemoveStaleAlarm(
                triggerAtMillis = now - TimeUnit.DAYS.toMillis(2),
                now = now
            )
        )
    }
}
