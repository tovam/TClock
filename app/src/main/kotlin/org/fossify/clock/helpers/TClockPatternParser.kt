package org.fossify.clock.helpers

object TClockPatternParser {
    data class Marker(
        val offsetMinutes: Int,
        val name: String?,
    )

    data class Result(
        val declarationCount: Int,
        val parsedCount: Int,
        val markers: List<Marker>,
    ) {
        val offsets: Set<Int> = markers
            .mapTo(linkedSetOf()) { it.offsetMinutes }
    }

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
     *
     * A marker can optionally be named with a second colon until the end of its physical line:
     * `ALARM:-2h:Prepare the bag`.
     *
     * The former `| Name` separator remains accepted for compatibility.
     */
    fun parse(description: String): Result {
        val matches = pattern.findAll(description).toList()
        val markers = matches.mapIndexedNotNull { index, match ->
            val sign = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull()
                ?: return@mapIndexedNotNull null
            val unit = match.groupValues[3].lowercase()
            val multiplier = when (unit) {
                "m", "min", "mins", "minute", "minutes" -> 1L
                "h", "hr", "hrs", "hour", "hours", "heure", "heures" -> 60L
                "d", "day", "days", "j", "jour", "jours" -> 24L * 60L
                else -> return@mapIndexedNotNull null
            }
            if (value > Int.MAX_VALUE.toLong() / multiplier) {
                return@mapIndexedNotNull null
            }
            val minutes = (value * multiplier).toInt()
            val offsetMinutes = when (sign) {
                "+" -> minutes
                else -> -minutes
            }
            Marker(
                offsetMinutes = offsetMinutes,
                name = parseName(
                    description = description,
                    markerMatch = match,
                    nextMarkerStart = matches.getOrNull(index + 1)?.range?.first
                )
            )
        }
        return Result(
            declarationCount = declarationPattern.findAll(description).count(),
            parsedCount = markers.size,
            markers = markers
        )
    }

    private fun parseName(
        description: String,
        markerMatch: MatchResult,
        nextMarkerStart: Int?,
    ): String? {
        val suffixStart = markerMatch.range.last + 1
        val physicalLineEnd = description.indexOfAny(
            chars = charArrayOf('\r', '\n'),
            startIndex = suffixStart
        ).takeIf { it >= 0 } ?: description.length
        val lineEnd = minOf(physicalLineEnd, nextMarkerStart ?: description.length)
        val suffix = description.substring(suffixStart, lineEnd)
        val separatorIndex = suffix.indexOfFirst { !it.isHorizontalWhitespace() }
        if (
            separatorIndex < 0 ||
            suffix[separatorIndex] != ':' && suffix[separatorIndex] != '|'
        ) {
            return null
        }
        return suffix
            .substring(separatorIndex + 1)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun Char.isHorizontalWhitespace(): Boolean {
        return this == ' ' || this == '\t'
    }
}
