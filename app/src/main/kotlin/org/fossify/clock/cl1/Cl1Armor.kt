package org.fossify.clock.cl1

import java.util.Base64

object Cl1Armor {
    fun parse(description: String): Cl1Description {
        val markers = findMarkers(description)
        if (markers.isEmpty()) {
            return Cl1Description.None(description)
        }
        if (markers.size != EXPECTED_MARKERS) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.MARKERS)
        }

        val begin = markers.first()
        val end = markers.last()
        val beginMatch = BEGIN_PATTERN.matchEntire(begin.text)
        val endMatch = END_PATTERN.matchEntire(end.text)
        if (
            beginMatch == null ||
            endMatch == null ||
            begin.start >= end.start ||
            beginMatch.groupValues[1] != endMatch.groupValues[1]
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
        if (begin.start < SEPARATOR.length || description.substring(
                begin.start - SEPARATOR.length,
                begin.start
            ) != SEPARATOR
        ) {
            return Cl1Description.Corrupt(description, Cl1CorruptReason.SEPARATOR)
        }

        val userDescription = description.substring(
            0,
            begin.start - SEPARATOR.length
        )
        val rawArmor = description.substring(
            begin.start,
            if (end.end == description.length) end.end else end.end + 1
        )
        val body = description.substring(begin.end + 1, end.start - 1)
        val decoded = decodeBody(body)
            ?: return Cl1Description.Corrupt(description, Cl1CorruptReason.BASE64)
        val version = beginMatch.groupValues[1].toInt()
        if (version != SUPPORTED_VERSION) {
            return Cl1Description.UnsupportedVersion(
                originalDescription = description,
                userDescription = userDescription,
                rawArmor = rawArmor,
                version = version
            )
        }
        if (decoded.size > Cl1Limits.PAYLOAD_BYTES) {
            return Cl1Description.Corrupt(
                description,
                Cl1CorruptReason.PAYLOAD_TOO_LARGE
            )
        }
        return try {
            Cl1Description.Valid(
                originalDescription = description,
                userDescription = userDescription,
                rawArmor = rawArmor,
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

    private fun decodeBody(body: String): ByteArray? {
        val compact = StringBuilder(body.length)
        body.forEach { character ->
            when (character) {
                ' ', '\t', '\r', '\n' -> Unit
                in 'A'..'Z',
                in 'a'..'z',
                in '0'..'9',
                '-',
                '_',
                -> compact.append(character)

                else -> return null
            }
        }
        if (compact.isEmpty() || compact.length % BASE64_GROUP == 1) {
            return null
        }
        return try {
            Base64.getUrlDecoder().decode(compact.toString())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun findMarkers(description: String): List<MarkerLine> {
        val result = ArrayList<MarkerLine>()
        var start = 0
        while (start <= description.length) {
            val newline = description.indexOf('\n', start)
            val end = if (newline == -1) description.length else newline
            val line = description.substring(start, end)
            if (line.startsWith(BEGIN_PREFIX) || line.startsWith(END_PREFIX)) {
                result.add(MarkerLine(start, end, line))
            }
            if (newline == -1) {
                break
            }
            start = newline + 1
        }
        return result
    }

    private data class MarkerLine(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private const val SUPPORTED_VERSION = 1
    private const val EXPECTED_MARKERS = 2
    private const val BODY_LINE_LENGTH = 64
    private const val BASE64_GROUP = 4
    private const val SEPARATOR = "\n\n"
    private const val BEGIN_PREFIX = "-----BEGIN CL"
    private const val END_PREFIX = "-----END CL"
    private const val BEGIN_CL1 = "-----BEGIN CL1-----"
    private const val END_CL1 = "-----END CL1-----"
    private val BEGIN_PATTERN = Regex("""-----BEGIN CL([1-9][0-9]{0,8})-----""")
    private val END_PATTERN = Regex("""-----END CL([1-9][0-9]{0,8})-----""")
}
