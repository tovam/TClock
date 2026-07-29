package org.fossify.clock.helpers

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1SourceRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAlarmDescriptionTest {
    @Test
    fun `alarm parser sees only the user description of a valid CL1 block`() {
        val description = Cl1Armor.compose(
            userDescription = "notes\nALARM:-60min:Préparer le sac\nmore notes",
            payload = Cl1Payload.Source(
                listOf(
                    Cl1SourceRecord(
                        slot = Cl1Bytes.fromHex("000102030405060708090a0b"),
                        emailCiphertext = Cl1Bytes.fromHex("01"),
                        gcmTag = Cl1Bytes.fromHex(
                            "000102030405060708090a0b0c0d0e0f"
                        )
                    )
                )
            )
        )

        assertEquals(
            setOf(-60),
            TClockPatternParser.parseOffsets(alarmPatternDescription(description))
        )
        assertEquals(
            "Préparer le sac",
            TClockPatternParser.parse(alarmPatternDescription(description))
                .markers
                .single()
                .name
        )
        assertEquals(
            "notes\nALARM:-60min:Préparer le sac\nmore notes",
            alarmPatternDescription(description)
        )
    }

    @Test
    fun `unsupported CL version still exposes its unambiguous user description`() {
        val description =
            "ALARM:30min\n\n-----BEGIN CL2-----\nAA\n-----END CL2-----"

        assertEquals("ALARM:30min", alarmPatternDescription(description))
    }

    @Test
    fun `ordinary and ambiguously delimited descriptions are never discarded`() {
        val ordinary = "ALARM:+5min"
        val ambiguous = "ALARM:10min\n\n-----BEGIN CL1-----\nbad"

        assertEquals(ordinary, alarmPatternDescription(ordinary))
        assertEquals(ambiguous, alarmPatternDescription(ambiguous))
    }

    @Test
    fun `alarm parser excludes a safely delimited but corrupt CL1 body`() {
        val corrupt =
            "notes\n\n-----BEGIN CL1-----\nALARM:10min\n-----END CL1-----"

        assertEquals("notes", alarmPatternDescription(corrupt))
        assertEquals(
            emptySet<Int>(),
            TClockPatternParser.parseOffsets(alarmPatternDescription(corrupt))
        )
    }
}
