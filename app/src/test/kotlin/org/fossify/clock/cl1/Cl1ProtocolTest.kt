package org.fossify.clock.cl1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.text.Normalizer

class Cl1ProtocolTest {
    @Test
    fun `source vector parses and composes exactly`() {
        val description = "\n\n$SOURCE_ARMOR"
        val parsed = Cl1Armor.parse(description) as Cl1Description.Valid
        val source = parsed.payload as Cl1Payload.Source

        assertEquals("", parsed.userDescription)
        assertEquals(1, source.records.size)
        assertFalse(source.hasDuplicateSlots)
        assertEquals(SLOT_HEX, source.records.single().slot.toHex())
        assertEquals("1cfbab524f", source.records.single().emailCiphertext.toHex())
        assertEquals("2bce61901c19c8ec0b12addd24d899d3", source.records.single().gcmTag.toHex())
        assertEquals(description, Cl1Armor.compose("", source))
    }

    @Test
    fun `mirror vector parses and composes exactly`() {
        val description = "\n\n$MIRROR_ARMOR"
        val parsed = Cl1Armor.parse(description) as Cl1Description.Valid
        val mirror = parsed.payload as Cl1Payload.Mirror

        assertEquals(SECRET_HEX, mirror.secret.toHex())
        assertEquals("1122334455667788", mirror.revision.toHex())
        assertEquals(
            Cl1TitleOverride.Replacement("Indisponible"),
            mirror.titleOverride
        )
        assertEquals(-1_800L, mirror.startOffsetSeconds)
        assertEquals(Cl1DurationOverride.Fixed(3_600uL), mirror.durationOverride)
        assertEquals(description, Cl1Armor.compose("", mirror))
    }

    @Test
    fun `email encryption vector matches and decrypts`() {
        val secret = Cl1Bytes.fromHex(SECRET_HEX)
        val email = Cl1Email.canonicalize("toto@gmail.com")
        val encrypted = Cl1Crypto.encryptEmail(secret, email)

        assertEquals(SLOT_HEX, encrypted.slot.toHex())
        assertEquals("1cfbab524f", encrypted.ciphertext.toHex())
        assertEquals("2bce61901c19c8ec0b12addd24d899d3", encrypted.tag.toHex())
        assertEquals(
            email,
            Cl1Crypto.decryptEmail(secret, encrypted.toSourceRecord())
        )
    }

    @Test
    fun `canonical event revision vector matches`() {
        val event = Cl1CanonicalEvent.fromSeconds(
            title = "Lunch",
            startUnixSeconds = 1_785_153_600,
            endUnixSeconds = 1_785_157_200,
            startIanaTimeZone = "UTC",
            endIanaTimeZone = "UTC",
            location = "",
            userDescription = "Bring coffee",
            userUrl = ""
        )

        assertEquals(CANONICAL_EVENT_HEX, Cl1CanonicalEventCodec.encode(event).toHex())
        assertEquals(
            "048cef7d5e03a34d",
            Cl1Revision.calculate(Cl1Bytes.fromHex(SECRET_HEX), event).toHex()
        )
    }

    @Test
    fun `canonical event requires IANA time zone identifiers`() {
        val valid = canonicalEvent(startTimeZone = "Europe/Paris")
        assertEquals("Europe/Paris", valid.startIanaTimeZone)

        assertEquals(
            "timeZone",
            assertThrows(Cl1IncompatibleException::class.java) {
                canonicalEvent(startTimeZone = "+02:00")
            }.field
        )
    }

    @Test
    fun `armor preserves the exact user description ending`() {
        val source = Cl1Payload.Source(listOf(testRecord(1)))
        val userDescription = "first\r\nsecond\n"
        val composed = Cl1Armor.compose(userDescription, source)
        val parsed = Cl1Armor.parse(composed) as Cl1Description.Valid

        assertEquals(userDescription, parsed.userDescription)
        assertEquals(composed, parsed.originalDescription)
    }

    @Test
    fun `unknown version remains untouched`() {
        val description = "notes\n\n-----BEGIN CL2-----\nAA\n-----END CL2-----"
        val parsed = Cl1Armor.parse(description) as Cl1Description.UnsupportedVersion

        assertEquals(2, parsed.version)
        assertEquals("notes", parsed.userDescription)
        assertEquals(description, parsed.originalDescription)
    }

    @Test
    fun `reader accepts all specified ASCII whitespace inside Base64`() {
        val lines = SOURCE_ARMOR.lines()
        val body = lines[1].chunked(4).joinToString(" \t\r\n")
        val description = "\n\n${lines.first()}\n$body\n${lines.last()}"

        assertTrue(Cl1Armor.parse(description) is Cl1Description.Valid)
    }

    @Test
    fun `unknown version still requires valid Base64`() {
        val description = "notes\n\n-----BEGIN CL2-----\nA\n-----END CL2-----"

        assertEquals(
            Cl1CorruptReason.BASE64,
            (Cl1Armor.parse(description) as Cl1Description.Corrupt).reason
        )
    }

    @Test
    fun `oversized CL1 payload is rejected before decoding`() {
        val oversizedBytes = Cl1Limits.PAYLOAD_BYTES + 1
        val encodedLength = (oversizedBytes * 4 + 2) / 3
        val description = buildString {
            append("\n\n-----BEGIN CL1-----\n")
            repeat(encodedLength) { append('A') }
            append("\n-----END CL1-----")
        }

        assertEquals(
            Cl1CorruptReason.PAYLOAD_TOO_LARGE,
            (Cl1Armor.parse(description) as Cl1Description.Corrupt).reason
        )
    }

    @Test
    fun `very long marker-like lines are rejected without copying them`() {
        val description = buildString {
            append("-----BEGIN CL")
            repeat(100_000) { append('1') }
        }

        assertEquals(
            Cl1CorruptReason.MARKERS,
            (Cl1Armor.parse(description) as Cl1Description.Corrupt).reason
        )
    }

    @Test
    fun `malformed or multiple markers are corrupt`() {
        val missingEnd = "notes\n\n-----BEGIN CL1-----\nAA"
        val multiple = "\n\n$SOURCE_ARMOR\n-----END CL1-----"

        assertEquals(
            Cl1CorruptReason.MARKERS,
            (Cl1Armor.parse(missingEnd) as Cl1Description.Corrupt).reason
        )
        assertEquals(
            Cl1CorruptReason.MARKERS,
            (Cl1Armor.parse(multiple) as Cl1Description.Corrupt).reason
        )
    }

    @Test
    fun `reader rejects non minimal and overflowing varints`() {
        val nonMinimal = Cl1BinaryReader(byteArrayOf(0x80.toByte(), 0x00))
        val overflow = Cl1BinaryReader(
            ByteArray(10) { 0xff.toByte() }.also { it[9] = 0x02 }
        )

        assertEquals(
            Cl1CorruptReason.VARINT,
            assertThrows(Cl1FormatException::class.java) { nonMinimal.readUVar() }.reason
        )
        assertEquals(
            Cl1CorruptReason.VARINT,
            assertThrows(Cl1FormatException::class.java) { overflow.readUVar() }.reason
        )
    }

    @Test
    fun `signed varints round trip their full range`() {
        val values = listOf(
            Long.MIN_VALUE,
            -1_800L,
            -1L,
            0L,
            1L,
            3_600L,
            Long.MAX_VALUE
        )
        values.forEach { value ->
            val bytes = Cl1BinaryWriter().apply { writeSVar(value) }.toByteArray()
            assertEquals(value, Cl1BinaryReader(bytes).readSVar())
        }
    }

    @Test
    fun `source duplicate is a relation conflict while descending order is corrupt`() {
        val duplicatePayload = Cl1Payload.Source(
            records = listOf(testRecord(1), testRecord(1)),
            hasDuplicateSlots = true
        )
        val duplicateBytes = encodeSourceWithoutCanonicalValidation(duplicatePayload)
        val parsed = Cl1Codec.decode(duplicateBytes) as Cl1Payload.Source
        assertTrue(parsed.hasDuplicateSlots)

        val descendingBytes = encodeSourceWithoutCanonicalValidation(
            Cl1Payload.Source(listOf(testRecord(2), testRecord(1)))
        )
        assertEquals(
            Cl1CorruptReason.RECORD_ORDER,
            assertThrows(Cl1FormatException::class.java) {
                Cl1Codec.decode(descendingBytes)
            }.reason
        )
    }

    @Test
    fun `compressed template title round trips within its bound`() {
        val title = "Busy {source} ".repeat(100)
        val mirror = Cl1Payload.Mirror(
            secret = Cl1Bytes.fromHex(SECRET_HEX),
            revision = Cl1Bytes.fromHex("1122334455667788"),
            titleOverride = Cl1TitleOverride.Template(title),
            startOffsetSeconds = null,
            durationOverride = Cl1DurationOverride.Inherited
        )

        val encoded = Cl1Codec.encode(mirror)
        assertTrue(encoded.first().toInt() and 0x02 != 0)
        assertEquals(mirror, Cl1Codec.decode(encoded))
    }

    @Test
    fun `wire title must already be NFC`() {
        val decomposed = "e\u0301"
        assertFalse(Normalizer.isNormalized(decomposed, Normalizer.Form.NFC))
        val raw = decomposed.toByteArray(StandardCharsets.UTF_8)
        val writer = Cl1BinaryWriter().apply {
            writeByte(0xa0)
            writeBytes(Cl1Bytes.fromHex(SECRET_HEX))
            writeBytes(Cl1Bytes.fromHex("1122334455667788"))
            writeUVar(raw.size.toULong())
            writeBytes(raw)
        }

        assertEquals(
            Cl1CorruptReason.NFC,
            assertThrows(Cl1FormatException::class.java) {
                Cl1Codec.decode(writer.toByteArray())
            }.reason
        )
    }

    @Test
    fun `email keeps local case and rejects whitespace`() {
        assertEquals(
            "Tom@xn--bcher-kva.example",
            Cl1Email.canonicalize("Tom@bücher.example").value
        )
        assertThrows(Cl1EmailException::class.java) {
            Cl1Email.canonicalize(" Tom@example.com")
        }
    }

    @Test
    fun `transform applies template offset and delta in seconds`() {
        val source = Cl1CanonicalEvent.fromMillis(
            title = "Lunch",
            startUnixMillis = 1_000_999,
            endUnixMillis = 4_600_999,
            startIanaTimeZone = "UTC",
            endIanaTimeZone = "UTC",
            location = "Kitchen",
            userDescription = "alarm:-30min",
            userUrl = ""
        )
        val mirror = Cl1Payload.Mirror(
            secret = Cl1Bytes.fromHex(SECRET_HEX),
            revision = Cl1Bytes.fromHex("1122334455667788"),
            titleOverride = Cl1TitleOverride.Template("Private — {source}"),
            startOffsetSeconds = -1_800,
            durationOverride = Cl1DurationOverride.Delta(600)
        )

        val transformed = Cl1Transform.apply(source, mirror)
        assertEquals("Private — Lunch", transformed.title)
        assertEquals(-800L, transformed.startUnixSeconds)
        assertEquals(3_400L, transformed.endUnixSeconds)
        assertEquals(source.userDescription, transformed.userDescription)
    }

    private fun testRecord(slotLastByte: Int): Cl1SourceRecord {
        val slot = ByteArray(Cl1Limits.SLOT_BYTES)
        slot[slot.lastIndex] = slotLastByte.toByte()
        return Cl1SourceRecord(
            slot = Cl1Bytes.copyOf(slot),
            emailCiphertext = Cl1Bytes.copyOf(byteArrayOf(0x01)),
            gcmTag = Cl1Bytes.copyOf(ByteArray(Cl1Limits.GCM_TAG_BYTES))
        )
    }

    private fun encodeSourceWithoutCanonicalValidation(source: Cl1Payload.Source): ByteArray {
        return Cl1BinaryWriter().apply {
            writeByte(0)
            source.records.forEach { record ->
                writeBytes(record.slot)
                writeByte(record.emailCiphertext.size)
                writeBytes(record.emailCiphertext)
                writeBytes(record.gcmTag)
            }
        }.toByteArray()
    }

    private fun ByteArray.toHex(): String = Cl1Bytes.copyOf(this).toHex()

    private companion object {
        const val SECRET_HEX = "000102030405060708090a0b0c0d0e0f"
        const val SLOT_HEX = "7844700a37e164c692376c4f"
        const val CANONICAL_EVENT_HEX =
            "01054c756e636880a1baa60da0d9baa60d0355544303555443000c4272696e6720636f6666656500"
        const val SOURCE_ARMOR = "-----BEGIN CL1-----\n" +
            "AHhEcAo34WTGkjdsTwUc-6tSTyvOYZAcGcjsCxKt3STYmdM\n" +
            "-----END CL1-----"
        const val MIRROR_ARMOR = "-----BEGIN CL1-----\n" +
            "tAABAgMEBQYHCAkKCwwNDg8RIjNEVWZ3iAxJbmRpc3BvbmlibGWPHJAc\n" +
            "-----END CL1-----"
    }

    private fun canonicalEvent(startTimeZone: String): Cl1CanonicalEvent {
        return Cl1CanonicalEvent.fromSeconds(
            title = "Event",
            startUnixSeconds = 1_000,
            endUnixSeconds = 2_000,
            startIanaTimeZone = startTimeZone,
            endIanaTimeZone = "UTC",
            location = "",
            userDescription = "",
            userUrl = ""
        )
    }
}
