package org.fossify.clock.helpers

object TClockPatternParser {
    private val pattern = Regex(
        pattern = """(?<![\p{L}\p{N}_])alarms?:([+-]?)([0-9]+)(minutes?|mins?|min|m|heures?|hours?|hrs?|h|jours?|days?|d|j)(?![\p{L}\p{N}_{}])""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Returns offsets in minutes relative to the event start.
     *
     * Unsigned values and negative values mean "before the event".
     * Positive values mean "after the event".
     */
    fun parseOffsets(description: String): Set<Int> {
        return pattern.findAll(description).mapNotNull { match ->
            val sign = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val unit = match.groupValues[3].lowercase()
            val multiplier = when (unit) {
                "m", "min", "mins", "minute", "minutes" -> 1L
                "h", "hr", "hrs", "hour", "hours", "heure", "heures" -> 60L
                "d", "day", "days", "j", "jour", "jours" -> 24L * 60L
                else -> return@mapNotNull null
            }
            if (value > Int.MAX_VALUE.toLong() / multiplier) {
                null
            } else {
                val minutes = (value * multiplier).toInt()
                when (sign) {
                    "+" -> minutes
                    else -> -minutes
                }
            }
        }.toSet()
    }
}
