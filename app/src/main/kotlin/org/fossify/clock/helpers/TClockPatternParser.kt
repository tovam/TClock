package org.fossify.clock.helpers

object TClockPatternParser {
    private val pattern = Regex(
        pattern = """(?i)\bTCLOCK\s*:\s*\{?\s*([+-]?)\s*(\d{1,6})\s*(minutes?|mins?|min|m|heures?|hours?|hrs?|h|jours?|days?|d|j)?\s*\}?(?![\p{L}\p{N}_])"""
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
            val multiplier = when {
                unit.startsWith("h") -> 60L
                unit.startsWith("d") || unit.startsWith("j") -> 24L * 60L
                else -> 1L
            }
            val minutes = value * multiplier
            if (minutes > Int.MAX_VALUE) {
                null
            } else {
                when (sign) {
                    "+" -> minutes.toInt()
                    else -> -minutes.toInt()
                }
            }
        }.toSet()
    }
}
