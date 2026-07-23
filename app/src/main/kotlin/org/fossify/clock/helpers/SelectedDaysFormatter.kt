package org.fossify.clock.helpers

private const val DAYS_PER_WEEK = 7

fun formatSelectedDays(
    bitMask: Int,
    weekDays: List<String>,
    firstDayOfWeek: Int,
): String {
    require(weekDays.size == DAYS_PER_WEEK)

    val firstDayIndex = Math.floorMod(firstDayOfWeek - 1, DAYS_PER_WEEK)
    return (0 until DAYS_PER_WEEK)
        .map { offset -> (firstDayIndex + offset) % DAYS_PER_WEEK }
        .filter { dayIndex -> bitMask and (1 shl dayIndex) != 0 }
        .joinToString(", ") { dayIndex -> weekDays[dayIndex] }
}
