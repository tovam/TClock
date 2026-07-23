package org.fossify.clock.helpers

import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

internal object CalendarAlarmWindow {
    const val WINDOW_DAYS = 15L
    private const val MAX_OFFSET_DAYS = WINDOW_DAYS
    private const val STALE_TRIGGER_GRACE_HOURS = 24L

    data class Range(
        val queryBeginMillis: Long,
        val queryEndMillis: Long,
        val triggerBeginMillis: Long,
        val triggerEndMillis: Long,
    )

    fun rangeAt(now: Long): Range {
        val triggerEnd = now + TimeUnit.DAYS.toMillis(WINDOW_DAYS)
        val queryMargin = TimeUnit.DAYS.toMillis(MAX_OFFSET_DAYS)
        return Range(
            queryBeginMillis = now - queryMargin,
            queryEndMillis = triggerEnd + queryMargin,
            triggerBeginMillis = now,
            triggerEndMillis = triggerEnd
        )
    }

    fun supportsOffset(offsetMinutes: Int): Boolean {
        return offsetMinutes.toLong().absoluteValue <=
            TimeUnit.DAYS.toMinutes(MAX_OFFSET_DAYS)
    }

    fun shouldRemoveStaleAlarm(triggerAtMillis: Long, now: Long): Boolean {
        val staleTriggerCutoff =
            now - TimeUnit.HOURS.toMillis(STALE_TRIGGER_GRACE_HOURS)
        return triggerAtMillis > now || triggerAtMillis < staleTriggerCutoff
    }
}
