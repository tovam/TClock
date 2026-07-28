package org.fossify.clock.cl1

import java.time.DateTimeException
import java.time.ZoneId

data class Cl1CanonicalEvent private constructor(
    val title: String,
    val startUnixSeconds: Long,
    val endUnixSeconds: Long,
    val startIanaTimeZone: String,
    val endIanaTimeZone: String,
    val location: String,
    val userDescription: String,
    val userUrl: String,
) {
    companion object {
        fun fromMillis(
            title: String?,
            startUnixMillis: Long,
            endUnixMillis: Long,
            startIanaTimeZone: String?,
            endIanaTimeZone: String?,
            location: String?,
            userDescription: String?,
            userUrl: String?,
        ): Cl1CanonicalEvent {
            return fromSeconds(
                title = title,
                startUnixSeconds = Math.floorDiv(startUnixMillis, MILLIS_PER_SECOND),
                endUnixSeconds = Math.floorDiv(endUnixMillis, MILLIS_PER_SECOND),
                startIanaTimeZone = startIanaTimeZone,
                endIanaTimeZone = endIanaTimeZone,
                location = location,
                userDescription = userDescription,
                userUrl = userUrl
            )
        }

        fun fromSeconds(
            title: String?,
            startUnixSeconds: Long,
            endUnixSeconds: Long,
            startIanaTimeZone: String?,
            endIanaTimeZone: String?,
            location: String?,
            userDescription: String?,
            userUrl: String?,
        ): Cl1CanonicalEvent {
            val normalizedStartZone = normalizeTimeZone(startIanaTimeZone)
            val normalizedEndZone = normalizeTimeZone(endIanaTimeZone)
            return Cl1CanonicalEvent(
                title = Cl1Text.normalize(title.orEmpty()),
                startUnixSeconds = startUnixSeconds,
                endUnixSeconds = endUnixSeconds,
                startIanaTimeZone = normalizedStartZone,
                endIanaTimeZone = normalizedEndZone,
                location = Cl1Text.normalize(location.orEmpty()),
                userDescription = Cl1Text.normalize(userDescription.orEmpty()),
                userUrl = Cl1Text.normalize(userUrl.orEmpty())
            )
        }

        private fun normalizeTimeZone(value: String?): String {
            val normalized = Cl1Text.normalize(value.orEmpty())
            if (normalized.isEmpty()) {
                return ""
            }
            if (normalized.any { it.code > ASCII_MAX }) {
                throw Cl1IncompatibleException("timeZone")
            }
            try {
                ZoneId.of(normalized)
            } catch (_: DateTimeException) {
                throw Cl1IncompatibleException("timeZone")
            }
            return normalized
        }

        private const val MILLIS_PER_SECOND = 1_000L
        private const val ASCII_MAX = 0x7f
    }
}

object Cl1CanonicalEventCodec {
    fun encode(event: Cl1CanonicalEvent): ByteArray {
        return Cl1BinaryWriter().apply {
            writeByte(CANONICAL_EVENT_VERSION)
            writeString(event.title)
            writeSVar(event.startUnixSeconds)
            writeSVar(event.endUnixSeconds)
            writeString(event.startIanaTimeZone)
            writeString(event.endIanaTimeZone)
            writeString(event.location)
            writeString(event.userDescription)
            writeString(event.userUrl)
        }.toByteArray()
    }

    private const val CANONICAL_EVENT_VERSION = 0x01
}

object Cl1Transform {
    fun apply(
        source: Cl1CanonicalEvent,
        mirror: Cl1Payload.Mirror,
    ): Cl1CanonicalEvent {
        val title = when (val override = mirror.titleOverride) {
            Cl1TitleOverride.Inherited -> source.title
            is Cl1TitleOverride.Replacement -> override.value
            is Cl1TitleOverride.Template -> override.value.replace(SOURCE_TOKEN, source.title)
        }
        val start = addExact(
            source.startUnixSeconds,
            mirror.startOffsetSeconds ?: 0L,
            "start"
        )
        val sourceDuration = subtractExact(
            source.endUnixSeconds,
            source.startUnixSeconds,
            "duration"
        )
        val duration = when (val override = mirror.durationOverride) {
            Cl1DurationOverride.Inherited -> sourceDuration
            is Cl1DurationOverride.Fixed -> {
                if (override.seconds > Long.MAX_VALUE.toULong()) {
                    throw Cl1IncompatibleException("duration")
                }
                override.seconds.toLong()
            }

            is Cl1DurationOverride.Delta -> {
                addExact(sourceDuration, override.seconds, "duration")
            }
        }
        if (duration <= 0L) {
            throw Cl1IncompatibleException("duration")
        }
        val end = addExact(start, duration, "end")
        return Cl1CanonicalEvent.fromSeconds(
            title = title,
            startUnixSeconds = start,
            endUnixSeconds = end,
            startIanaTimeZone = source.startIanaTimeZone,
            endIanaTimeZone = source.endIanaTimeZone,
            location = source.location,
            userDescription = source.userDescription,
            userUrl = source.userUrl
        )
    }

    private fun addExact(left: Long, right: Long, field: String): Long {
        return try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            throw Cl1IncompatibleException(field)
        }
    }

    private fun subtractExact(left: Long, right: Long, field: String): Long {
        return try {
            Math.subtractExact(left, right)
        } catch (_: ArithmeticException) {
            throw Cl1IncompatibleException(field)
        }
    }

    private const val SOURCE_TOKEN = "{source}"
}

object Cl1Revision {
    fun calculate(
        secret: Cl1Bytes,
        event: Cl1CanonicalEvent,
    ): Cl1Bytes {
        return Cl1Crypto.revision(secret, Cl1CanonicalEventCodec.encode(event))
    }
}

class Cl1IncompatibleException(
    val field: String,
) : IllegalArgumentException(field)
