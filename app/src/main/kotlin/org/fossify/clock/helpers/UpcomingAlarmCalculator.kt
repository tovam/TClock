package org.fossify.clock.helpers

import org.fossify.clock.models.Alarm

internal data class UpcomingAlarmOccurrence(
    val alarm: Alarm,
    val triggerAtMillis: Long,
)

internal object UpcomingAlarmCalculator {
    fun collect(
        alarms: List<Alarm>,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 10,
    ): List<UpcomingAlarmOccurrence> {
        require(limit >= 0)
        return alarms.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { alarm ->
                val triggerAtMillis = getTimeOfNextAlarm(alarm)?.timeInMillis
                    ?: return@mapNotNull null
                if (triggerAtMillis <= nowMillis) {
                    null
                } else {
                    UpcomingAlarmOccurrence(alarm, triggerAtMillis)
                }
            }
            .sortedWith(
                compareBy<UpcomingAlarmOccurrence> { it.triggerAtMillis }
                    .thenBy { it.alarm.id }
            )
            .take(limit)
            .toList()
    }
}
