package org.fossify.clock.helpers

object TClockPatternParser {
    data class Result(
        val declarationCount: Int,
        val parsedCount: Int,
        val offsets: Set<Int>,
    )

    private val declarationPattern = Regex(
        pattern = """(?<![\p{L}\p{N}_])alarms?(?![\p{L}\p{N}_])\s*:""",
        option = RegexOption.IGNORE_CASE
    )

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
        return parse(description).offsets
    }

    /**
     * Keeps separate counts for declarations, valid parsed markers, and unique offsets.
     *
     * A declaration is an ALARM: or ALARMS: prefix, even when the delay is malformed.
     * Parsed markers include valid duplicates, while [Result.offsets] is deduplicated.
     */
    fun parse(description: String): Result {
        val offsets = linkedSetOf<Int>()
        var parsedCount = 0
        pattern.findAll(description).forEach { match ->
            val sign = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull() ?: return@forEach
            val unit = match.groupValues[3].lowercase()
            val multiplier = when (unit) {
                "m", "min", "mins", "minute", "minutes" -> 1L
                "h", "hr", "hrs", "hour", "hours", "heure", "heures" -> 60L
                "d", "day", "days", "j", "jour", "jours" -> 24L * 60L
                else -> return@forEach
            }
            if (value > Int.MAX_VALUE.toLong() / multiplier) {
                return@forEach
            } else {
                val minutes = (value * multiplier).toInt()
                offsets += when (sign) {
                    "+" -> minutes
                    else -> -minutes
                }
                parsedCount++
            }
        }
        return Result(
            declarationCount = declarationPattern.findAll(description).count(),
            parsedCount = parsedCount,
            offsets = offsets
        )
    }
}
