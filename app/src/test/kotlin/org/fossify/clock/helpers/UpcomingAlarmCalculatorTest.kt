package org.fossify.clock.helpers

import org.fossify.clock.models.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingAlarmCalculatorTest {
    private val now = 1_800_000_000_000L

    @Test
    fun pastAndDisabledAlarmsAreExcludedAndTiesUseTheId() {
        val alarms = listOf(
            alarm(id = 8, triggerAtMillis = now + 2_000L),
            alarm(id = 3, triggerAtMillis = now + 1_000L),
            alarm(id = 2, triggerAtMillis = now + 1_000L),
            alarm(id = 1, triggerAtMillis = now),
            alarm(id = 4, triggerAtMillis = now - 1L),
            alarm(id = 5, triggerAtMillis = now + 500L, isEnabled = false)
        )

        val result = UpcomingAlarmCalculator.collect(alarms, nowMillis = now)

        assertEquals(listOf(2, 3, 8), result.map { it.alarm.id })
        assertEquals(
            listOf(now + 1_000L, now + 1_000L, now + 2_000L),
            result.map { it.triggerAtMillis }
        )
    }

    @Test
    fun resultIsLimitedAfterChronologicalSorting() {
        val alarms = (1..14).map { id ->
            alarm(
                id = id,
                triggerAtMillis = now + (15L - id) * 1_000L
            )
        }

        val result = UpcomingAlarmCalculator.collect(
            alarms = alarms,
            nowMillis = now,
            limit = 10
        )

        assertEquals(10, result.size)
        assertEquals((14 downTo 5).toList(), result.map { it.alarm.id })
    }

    private fun alarm(
        id: Int,
        triggerAtMillis: Long,
        isEnabled: Boolean = true,
    ) = Alarm(
        id = id,
        timeInMinutes = 0,
        days = 0,
        isEnabled = isEnabled,
        vibrate = false,
        soundTitle = "",
        soundUri = "",
        label = "Alarm $id",
        oneShot = true,
        triggerAtMillis = triggerAtMillis
    )
}
