package org.fossify.clock.cl1

import java.util.Base64

object Cl1Armor {
    fun parse(description: String): Cl1Description {
        val markers = findMarkers(description)
        if (markers.isEmpty()) {
            return Cl1Description.None(description)
        }
        if (markers.size != EXPECTED_MARKERS || markers.tooMany) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.MARKERS)
        }

        val begin = requireNotNull(markers.first)
        val end = requireNotNull(markers.second)
        val beginVersion = markerVersion(description, begin, MarkerKind.BEGIN)
        val endVersion = markerVersion(description, end, MarkerKind.END)
        if (
            begin.kind != MarkerKind.BEGIN ||
            end.kind != MarkerKind.END ||
            beginVersion == null ||
            endVersion == null ||
            begin.start >= end.start ||
            beginVersion != endVersion
        ) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.MARKERS)
        }
        if (begin.end >= description.length || description[begin.end] != '\n') {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.MARKERS)
        }
        if (end.start <= begin.end + 1 || description[end.start - 1] != '\n') {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.MARKERS)
        }
        val validEnd = end.end == description.length ||
            end.end == description.length - 1 && description.last() == '\n'
        if (!validEnd) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.TRAILING_CONTENT)
        }
        if (
            begin.start < SEPARATOR.length ||
            description[begin.start - 2] != '\n' ||
            description[begin.start - 1] != '\n'
        ) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.SEPARATOR)
        }

        val userDescription = description.substring(
            0,
            begin.start - SEPARATOR.length
        )
        val bodyStart = begin.end + 1
        val bodyEnd = end.start - 1
        val body = inspectBody(description, bodyStart, bodyEnd)
            ?: return Cl1Description.Corrupt(description, Cl1CorruptReason.BASE64)
        if (beginVersion != SUPPORTED_VERSION) {
            return Cl1Description.UnsupportedVersion(
                originalDescription = description,
                userDescription = userDescription,
                version = beginVersion
            )
        }
        if (body.decodedLength > Cl1Limits.PAYLOAD_BYTES) {
            return Cl1Description.Corrupt(
                description,
                Cl1CorruptReason.PAYLOAD_TOO_LARGE
            )
        }
        val decoded = decodeBody(description, bodyStart, bodyEnd, body.encodedLength)
            ?: return Cl1Description.Corrupt(description, Cl1CorruptReason.BASE64)
        return try {
            Cl1Description.Valid(
                originalDescription = description,
                userDescription = userDescription,
                payload = Cl1Codec.decode(decoded)
            )
        } catch (exception: Cl1FormatException) {
            Cl1Description.Corrupt(description, exception.reason)
        }
    }

    fun compose(userDescription: String, payload: Cl1Payload): String {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(Cl1Codec.encode(payload))
        val body = encoded.chunked(BODY_LINE_LENGTH).joinToString("\n")
        return buildString {
            append(userDescription)
            append(SEPARATOR)
            append(BEGIN_CL1)
            append('\n')
            append(body)
            append('\n')
            append(END_CL1)
        }
    }

    private fun inspectBody(
        description: String,
        start: Int,
        end: Int,
    ): BodyInfo? {
        var encodedLength = 0
        for (index in start until end) {
            when (description[index]) {
                ' ', '\t', '\r', '\n' -> Unit
                in 'A'..'Z',
                in 'a'..'z',
                in '0'..'9',
                '-',
                '_',
                -> encodedLength++

                else -> return null
            }
        }
        if (encodedLength == 0 || encodedLength % BASE64_GROUP == 1) {
            return null
        }
        val decodedLength = encodedLength / BASE64_GROUP * BASE64_TRIPLET +
            when (encodedLength % BASE64_GROUP) {
                2 -> 1
                3 -> 2
                else -> 0
            }
        return BodyInfo(encodedLength, decodedLength)
    }

    private fun decodeBody(
        description: String,
        start: Int,
        end: Int,
        encodedLength: Int,
    ): ByteArray? {
        val compact = CharArray(encodedLength)
        var outputIndex = 0
        for (index in start until end) {
            when (val character = description[index]) {
                ' ', '\t', '\r', '\n' -> Unit
                in 'A'..'Z',
                in 'a'..'z',
                in '0'..'9',
                '-',
                '_',
                -> compact[outputIndex++] = character

                else -> return null
            }
        }
        return try {
            Base64.getUrlDecoder().decode(String(compact))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun findMarkers(description: String): MarkerSearch {
        var first: MarkerLine? = null
        var second: MarkerLine? = null
        var tooMany = false
        var start = 0
        while (start <= description.length) {
            val newline = description.indexOf('\n', start)
            val end = if (newline == -1) description.length else newline
            val kind = when {
                description.regionMatches(start, BEGIN_PREFIX, 0, BEGIN_PREFIX.length) ->
                    MarkerKind.BEGIN

                description.regionMatches(start, END_PREFIX, 0, END_PREFIX.length) ->
                    MarkerKind.END

                else -> null
            }
            if (kind != null) {
                val marker = MarkerLine(start, end, kind)
                when {
                    first == null -> first = marker
                    second == null -> second = marker
                    else -> tooMany = true
                }
            }
            if (newline == -1) {
                break
            }
            start = newline + 1
        }
        return MarkerSearch(first, second, tooMany)
    }

    private fun markerVersion(
        description: String,
        marker: MarkerLine,
        expectedKind: MarkerKind,
    ): Int? {
        if (marker.kind != expectedKind) {
            return null
        }
        val prefix = if (expectedKind == MarkerKind.BEGIN) BEGIN_PREFIX else END_PREFIX
        val versionStart = marker.start + prefix.length
        val versionEnd = marker.end - MARKER_SUFFIX.length
        val versionLength = versionEnd - versionStart
        if (
            versionLength !in MIN_VERSION_DIGITS..MAX_VERSION_DIGITS ||
            !description.regionMatches(
                versionEnd,
                MARKER_SUFFIX,
                0,
                MARKER_SUFFIX.length
            ) ||
            description[versionStart] !in '1'..'9'
        ) {
            return null
        }
        var version = 0
        for (index in versionStart until versionEnd) {
            val digit = description[index]
            if (digit !in '0'..'9') {
                return null
            }
            version = version * DECIMAL_BASE + (digit - '0')
        }
        return version
    }

    private data class MarkerLine(
        val start: Int,
        val end: Int,
        val kind: MarkerKind,
    )

    private data class MarkerSearch(
        val first: MarkerLine?,
        val second: MarkerLine?,
        val tooMany: Boolean,
    ) {
        val size: Int
            get() = when {
                second != null -> EXPECTED_MARKERS
                first != null -> 1
                else -> 0
            }

        fun isEmpty(): Boolean = first == null
    }

    private data class BodyInfo(
        val encodedLength: Int,
        val decodedLength: Int,
    )

    private enum class MarkerKind {
        BEGIN,
        END,
    }

    private const val SUPPORTED_VERSION = 1
    private const val EXPECTED_MARKERS = 2
    private const val BODY_LINE_LENGTH = 64
    private const val BASE64_GROUP = 4
    private const val BASE64_TRIPLET = 3
    private const val MIN_VERSION_DIGITS = 1
    private const val MAX_VERSION_DIGITS = 9
    private const val DECIMAL_BASE = 10
    private const val SEPARATOR = "\n\n"
    private const val BEGIN_PREFIX = "-----BEGIN CL"
    private const val END_PREFIX = "-----END CL"
    private const val MARKER_SUFFIX = "-----"
    private const val BEGIN_CL1 = "-----BEGIN CL1-----"
    private const val END_CL1 = "-----END CL1-----"
}
