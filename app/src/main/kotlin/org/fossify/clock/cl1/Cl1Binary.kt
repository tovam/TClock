@file:Suppress("MagicNumber")

package org.fossify.clock.cl1

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal class Cl1BinaryWriter {
    private val output = ByteArrayOutputStream()

    fun writeByte(value: Int) {
        require(value in 0..255)
        output.write(value)
    }

    fun writeBytes(value: Cl1Bytes) {
        output.write(value.toByteArray())
    }

    fun writeBytes(value: ByteArray) {
        output.write(value)
    }

    fun writeUVar(value: ULong) {
        var remaining = value
        do {
            var next = (remaining and 0x7fuL).toInt()
            remaining = remaining shr 7
            if (remaining != 0uL) {
                next = next or 0x80
            }
            output.write(next)
        } while (remaining != 0uL)
    }

    fun writeSVar(value: Long) {
        val zigZag = ((value shl 1) xor (value shr (Long.SIZE_BITS - 1))).toULong()
        writeUVar(zigZag)
    }

    fun writeString(value: String) {
        val normalized = Cl1Text.normalize(value)
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        writeUVar(bytes.size.toULong())
        writeBytes(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

internal class Cl1BinaryReader(
    private val value: ByteArray,
) {
    private var position = 0

    val remaining: Int
        get() = value.size - position

    fun readByte(): Int {
        if (remaining < 1) {
            throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
        }
        return value[position++].toInt() and 0xff
    }

    fun readBytes(size: Int): ByteArray {
        if (size < 0 || remaining < size) {
            throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
        }
        return value.copyOfRange(position, position + size).also {
            position += size
        }
    }

    fun readUVar(): ULong {
        var result = 0uL
        for (index in 0 until MAX_VARINT_BYTES) {
            val next = try {
                readByte()
            } catch (_: Cl1FormatException) {
                throw Cl1FormatException(Cl1CorruptReason.VARINT)
            }
            val payload = next and 0x7f
            if (index == MAX_VARINT_BYTES - 1 && payload > 1) {
                throw Cl1FormatException(Cl1CorruptReason.VARINT)
            }
            result = result or (payload.toULong() shl (index * 7))
            if (next and 0x80 == 0) {
                if (index > 0 && payload == 0) {
                    throw Cl1FormatException(Cl1CorruptReason.VARINT)
                }
                return result
            }
        }
        throw Cl1FormatException(Cl1CorruptReason.VARINT)
    }

    fun readSVar(): Long {
        val encoded = readUVar()
        return (encoded shr 1).toLong() xor -((encoded and 1uL).toLong())
    }

    fun readLength(limit: Int): Int {
        val length = readUVar()
        if (length > limit.toULong() || length > Int.MAX_VALUE.toULong()) {
            throw Cl1FormatException(Cl1CorruptReason.LIMIT)
        }
        return length.toInt()
    }

    fun requireFinished() {
        if (remaining != 0) {
            throw Cl1FormatException(Cl1CorruptReason.STRUCTURE)
        }
    }

    companion object {
        private const val MAX_VARINT_BYTES = 10
    }
}

internal object Cl1Text {
    fun normalize(value: String): String {
        requireWellFormedUnicode(value)
        val newlines = value.replace("\r\n", "\n").replace('\r', '\n')
        return Normalizer.normalize(newlines, Normalizer.Form.NFC)
    }

    fun decodeNfc(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw Cl1FormatException(Cl1CorruptReason.UTF8)
        }
        if (!Normalizer.isNormalized(decoded, Normalizer.Form.NFC)) {
            throw Cl1FormatException(Cl1CorruptReason.NFC)
        }
        if ('\r' in decoded) {
            throw Cl1FormatException(Cl1CorruptReason.NFC)
        }
        return decoded
    }

    private fun requireWellFormedUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (
                        index + 1 >= value.length ||
                        !Character.isLowSurrogate(value[index + 1])
                    ) {
                        throw Cl1IncompatibleException("unicode")
                    }
                    index += 2
                }

                Character.isLowSurrogate(character) -> {
                    throw Cl1IncompatibleException("unicode")
                }

                else -> index++
            }
        }
    }
}

internal object Cl1Deflate {
    fun compressIfSmaller(value: ByteArray): Pair<ByteArray, Boolean> {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(value)
            deflater.finish()
            val buffer = ByteArray(value.size + DEFLATE_OVERHEAD)
            val size = deflater.deflate(buffer)
            if (deflater.finished() && size < value.size) {
                buffer.copyOf(size) to true
            } else {
                value to false
            }
        } finally {
            deflater.end()
        }
    }

    fun decompress(value: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(value)
            val output = ByteArray(Cl1Limits.TITLE_BYTES + 1)
            var size = 0
            while (!inflater.finished() && size < output.size) {
                val read = inflater.inflate(output, size, output.size - size)
                if (read == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        break
                    }
                    throw Cl1FormatException(Cl1CorruptReason.DEFLATE)
                }
                size += read
            }
            if (
                !inflater.finished() ||
                inflater.remaining != 0 ||
                size > Cl1Limits.TITLE_BYTES
            ) {
                throw Cl1FormatException(Cl1CorruptReason.DEFLATE)
            }
            output.copyOf(size)
        } catch (_: DataFormatException) {
            throw Cl1FormatException(Cl1CorruptReason.DEFLATE)
        } finally {
            inflater.end()
        }
    }

    private const val DEFLATE_OVERHEAD = 64
}
