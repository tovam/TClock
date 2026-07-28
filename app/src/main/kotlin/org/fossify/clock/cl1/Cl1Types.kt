@file:Suppress("MagicNumber")

package org.fossify.clock.cl1

import java.util.Locale

class Cl1Bytes private constructor(
    private val value: ByteArray,
) : Comparable<Cl1Bytes> {
    val size: Int
        get() = value.size

    fun toByteArray(): ByteArray = value.copyOf()

    fun toHex(): String = buildString(value.size * 2) {
        value.forEach { byte ->
            append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff))
        }
    }

    override fun compareTo(other: Cl1Bytes): Int {
        val commonSize = minOf(size, other.size)
        for (index in 0 until commonSize) {
            val left = value[index].toInt() and 0xff
            val right = other.value[index].toInt() and 0xff
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return size.compareTo(other.size)
    }

    override fun equals(other: Any?): Boolean {
        return other is Cl1Bytes && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = toHex()

    companion object {
        fun copyOf(value: ByteArray): Cl1Bytes = Cl1Bytes(value.copyOf())

        fun fromHex(value: String): Cl1Bytes {
            require(value.length % 2 == 0) { "Hex input must contain complete bytes" }
            return copyOf(
                ByteArray(value.length / 2) { index ->
                    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            )
        }
    }
}

data class Cl1SourceRecord(
    val slot: Cl1Bytes,
    val emailCiphertext: Cl1Bytes,
    val gcmTag: Cl1Bytes,
) {
    init {
        require(slot.size == Cl1Limits.SLOT_BYTES)
        require(emailCiphertext.size in 1..Cl1Limits.EMAIL_ENCODED_BYTES)
        require(gcmTag.size == Cl1Limits.GCM_TAG_BYTES)
    }
}

sealed interface Cl1TitleOverride {
    data object Inherited : Cl1TitleOverride

    data class Replacement(val value: String) : Cl1TitleOverride

    data class Template(val value: String) : Cl1TitleOverride
}

sealed interface Cl1DurationOverride {
    data object Inherited : Cl1DurationOverride

    data class Fixed(val seconds: ULong) : Cl1DurationOverride

    data class Delta(val seconds: Long) : Cl1DurationOverride
}

sealed interface Cl1Payload {
    data class Source(
        val records: List<Cl1SourceRecord>,
        val hasDuplicateSlots: Boolean = false,
    ) : Cl1Payload

    data class Mirror(
        val secret: Cl1Bytes,
        val revision: Cl1Bytes,
        val titleOverride: Cl1TitleOverride,
        val startOffsetSeconds: Long?,
        val durationOverride: Cl1DurationOverride,
    ) : Cl1Payload {
        init {
            require(secret.size == Cl1Limits.SECRET_BYTES)
            require(revision.size == Cl1Limits.REVISION_BYTES)
        }
    }
}

sealed interface Cl1Description {
    val originalDescription: String

    data class None(
        override val originalDescription: String,
    ) : Cl1Description

    data class Valid(
        override val originalDescription: String,
        val userDescription: String,
        val rawArmor: String,
        val payload: Cl1Payload,
    ) : Cl1Description

    data class UnsupportedVersion(
        override val originalDescription: String,
        val userDescription: String,
        val rawArmor: String,
        val version: Int,
    ) : Cl1Description

    data class Corrupt(
        override val originalDescription: String,
        val reason: Cl1CorruptReason,
    ) : Cl1Description
}

enum class Cl1CorruptReason {
    MARKERS,
    SEPARATOR,
    TRAILING_CONTENT,
    BASE64,
    PAYLOAD_TOO_LARGE,
    HEADER,
    STRUCTURE,
    RECORD_ORDER,
    UTF8,
    NFC,
    VARINT,
    DEFLATE,
    LIMIT,
}

internal class Cl1FormatException(
    val reason: Cl1CorruptReason,
) : IllegalArgumentException(reason.name)

object Cl1Limits {
    const val PAYLOAD_BYTES = 65_535
    const val SOURCE_RECORDS = 255
    const val EMAIL_ENCODED_BYTES = 255
    const val TITLE_BYTES = 4_096
    const val SECRET_BYTES = 16
    const val SLOT_BYTES = 12
    const val REVISION_BYTES = 8
    const val GCM_TAG_BYTES = 16
    const val OFFSET_SECONDS = 2_592_000L
}
