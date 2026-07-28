@file:Suppress("MagicNumber")

package org.fossify.clock.cl1

import java.nio.charset.StandardCharsets

object Cl1Codec {
    fun encode(payload: Cl1Payload): ByteArray {
        val encoded = when (payload) {
            is Cl1Payload.Source -> encodeSource(payload)
            is Cl1Payload.Mirror -> encodeMirror(payload)
        }
        require(encoded.size <= Cl1Limits.PAYLOAD_BYTES)
        return encoded
    }

    internal fun decode(value: ByteArray): Cl1Payload {
        if (value.isEmpty()) {
            throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
        }
        if (value.size > Cl1Limits.PAYLOAD_BYTES) {
            throw Cl1FormatException(Cl1CorruptReason.PAYLOAD_TOO_LARGE)
        }
        return if (value.first().toInt() and 0xff == SOURCE_HEADER) {
            decodeSource(value)
        } else {
            decodeMirror(value)
        }
    }

    private fun encodeSource(source: Cl1Payload.Source): ByteArray {
        require(source.records.isNotEmpty())
        require(source.records.size <= Cl1Limits.SOURCE_RECORDS)
        var previousSlot: Cl1Bytes? = null
        val writer = Cl1BinaryWriter()
        writer.writeByte(SOURCE_HEADER)
        source.records.forEach { record ->
            val previous = previousSlot
            require(previous == null || previous < record.slot)
            writer.writeBytes(record.slot)
            writer.writeByte(record.emailCiphertext.size)
            writer.writeBytes(record.emailCiphertext)
            writer.writeBytes(record.gcmTag)
            previousSlot = record.slot
        }
        return writer.toByteArray()
    }

    private fun decodeSource(value: ByteArray): Cl1Payload.Source {
        val reader = Cl1BinaryReader(value)
        if (reader.readByte() != SOURCE_HEADER) {
            throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
        val records = ArrayList<Cl1SourceRecord>()
        var previousSlot: Cl1Bytes? = null
        var hasDuplicateSlots = false
        while (reader.remaining > 0) {
            if (records.size == Cl1Limits.SOURCE_RECORDS) {
                throw Cl1FormatException(Cl1CorruptReason.LIMIT)
            }
            val slot = Cl1Bytes.copyOf(reader.readBytes(Cl1Limits.SLOT_BYTES))
            val ciphertextLength = reader.readByte()
            if (ciphertextLength == 0) {
                throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
            }
            val record = Cl1SourceRecord(
                slot = slot,
                emailCiphertext = Cl1Bytes.copyOf(reader.readBytes(ciphertextLength)),
                gcmTag = Cl1Bytes.copyOf(reader.readBytes(Cl1Limits.GCM_TAG_BYTES))
            )
            val previous = previousSlot
            if (previous != null) {
                when {
                    previous > slot -> {
                        throw Cl1FormatException(Cl1CorruptReason.RECORD_ORDER)
                    }

                    previous == slot -> hasDuplicateSlots = true
                }
            }
            records.add(record)
            previousSlot = slot
        }
        if (records.isEmpty()) {
            throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
        }
        return Cl1Payload.Source(
            records = records,
            hasDuplicateSlots = hasDuplicateSlots
        )
    }

    private fun encodeMirror(mirror: Cl1Payload.Mirror): ByteArray {
        val title = encodeTitle(mirror.titleOverride)
        val offset = mirror.startOffsetSeconds
        require(offset == null || offset in -Cl1Limits.OFFSET_SECONDS..Cl1Limits.OFFSET_SECONDS)

        var header = MIRROR_HEADER
        header = header or title.modeBits
        if (offset != null) {
            header = header or OFFSET_PRESENT
        }
        header = header or when (val duration = mirror.durationOverride) {
            Cl1DurationOverride.Inherited -> 0
            is Cl1DurationOverride.Fixed -> {
                require(duration.seconds > 0uL)
                DURATION_FIXED
            }

            is Cl1DurationOverride.Delta -> DURATION_DELTA
        }
        if (title.compressed) {
            header = header or TITLE_COMPRESSED
        }

        return Cl1BinaryWriter().apply {
            writeByte(header)
            writeBytes(mirror.secret)
            writeBytes(mirror.revision)
            title.bytes?.let { bytes ->
                writeUVar(bytes.size.toULong())
                writeBytes(bytes)
            }
            offset?.let(::writeSVar)
            when (val duration = mirror.durationOverride) {
                Cl1DurationOverride.Inherited -> Unit
                is Cl1DurationOverride.Fixed -> writeUVar(duration.seconds)
                is Cl1DurationOverride.Delta -> writeSVar(duration.seconds)
            }
        }.toByteArray()
    }

    private fun decodeMirror(value: ByteArray): Cl1Payload.Mirror {
        val reader = Cl1BinaryReader(value)
        val header = reader.readByte()
        if (header and MIRROR_HEADER == 0 || header and RESERVED_BIT != 0) {
            throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
        val titleMode = header and TITLE_MODE_MASK
        val durationMode = header and DURATION_MODE_MASK
        val compressed = header and TITLE_COMPRESSED != 0
        if (
            titleMode == TITLE_INVALID ||
            durationMode == DURATION_INVALID ||
            compressed && titleMode == TITLE_INHERITED
        ) {
            throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }

        val secret = Cl1Bytes.copyOf(reader.readBytes(Cl1Limits.SECRET_BYTES))
        val revision = Cl1Bytes.copyOf(reader.readBytes(Cl1Limits.REVISION_BYTES))
        val titleOverride = decodeTitle(reader, titleMode, compressed)
        val offset = if (header and OFFSET_PRESENT != 0) {
            reader.readSVar().also { value ->
                if (value !in -Cl1Limits.OFFSET_SECONDS..Cl1Limits.OFFSET_SECONDS) {
                    throw Cl1FormatException(Cl1CorruptReason.LIMIT)
                }
            }
        } else {
            null
        }
        val durationOverride = when (durationMode) {
            DURATION_INHERITED -> Cl1DurationOverride.Inherited
            DURATION_FIXED -> {
                val seconds = reader.readUVar()
                if (seconds == 0uL) {
                    throw Cl1FormatException(Cl1CorruptReason.HEADER)
                }
                Cl1DurationOverride.Fixed(seconds)
            }

            DURATION_DELTA -> Cl1DurationOverride.Delta(reader.readSVar())
            else -> throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
        reader.requireFinished()
        return Cl1Payload.Mirror(
            secret = secret,
            revision = revision,
            titleOverride = titleOverride,
            startOffsetSeconds = offset,
            durationOverride = durationOverride
        )
    }

    private fun encodeTitle(override: Cl1TitleOverride): EncodedTitle {
        if (override == Cl1TitleOverride.Inherited) {
            return EncodedTitle(TITLE_INHERITED, null, compressed = false)
        }
        val normalized = when (override) {
            Cl1TitleOverride.Inherited -> error("Handled above")
            is Cl1TitleOverride.Replacement -> Cl1Text.normalize(override.value)
            is Cl1TitleOverride.Template -> Cl1Text.normalize(override.value)
        }
        val raw = normalized.toByteArray(StandardCharsets.UTF_8)
        require(raw.isNotEmpty())
        require(raw.size <= Cl1Limits.TITLE_BYTES)
        val (encoded, compressed) = Cl1Deflate.compressIfSmaller(raw)
        val mode = when (override) {
            Cl1TitleOverride.Inherited -> error("Handled above")
            is Cl1TitleOverride.Replacement -> TITLE_REPLACEMENT
            is Cl1TitleOverride.Template -> TITLE_TEMPLATE
        }
        return EncodedTitle(mode, encoded, compressed)
    }

    private fun decodeTitle(
        reader: Cl1BinaryReader,
        mode: Int,
        compressed: Boolean,
    ): Cl1TitleOverride {
        if (mode == TITLE_INHERITED) {
            return Cl1TitleOverride.Inherited
        }
        val encoded = reader.readBytes(reader.readLength(Cl1Limits.PAYLOAD_BYTES))
        if (encoded.isEmpty()) {
            throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
        val raw = if (compressed) {
            Cl1Deflate.decompress(encoded)
        } else {
            if (encoded.size > Cl1Limits.TITLE_BYTES) {
                throw Cl1FormatException(Cl1CorruptReason.LIMIT)
            }
            encoded
        }
        if (raw.isEmpty()) {
            throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
        val title = Cl1Text.decodeNfc(raw)
        return when (mode) {
            TITLE_REPLACEMENT -> Cl1TitleOverride.Replacement(title)
            TITLE_TEMPLATE -> Cl1TitleOverride.Template(title)
            else -> throw Cl1FormatException(Cl1CorruptReason.HEADER)
        }
    }

    private data class EncodedTitle(
        val modeBits: Int,
        val bytes: ByteArray?,
        val compressed: Boolean,
    )

    private const val SOURCE_HEADER = 0x00
    private const val MIRROR_HEADER = 0x80
    private const val TITLE_MODE_MASK = 0x60
    private const val TITLE_INHERITED = 0x00
    private const val TITLE_REPLACEMENT = 0x20
    private const val TITLE_TEMPLATE = 0x40
    private const val TITLE_INVALID = 0x60
    private const val OFFSET_PRESENT = 0x10
    private const val DURATION_MODE_MASK = 0x0c
    private const val DURATION_INHERITED = 0x00
    private const val DURATION_FIXED = 0x04
    private const val DURATION_DELTA = 0x08
    private const val DURATION_INVALID = 0x0c
    private const val TITLE_COMPRESSED = 0x02
    private const val RESERVED_BIT = 0x01
}
