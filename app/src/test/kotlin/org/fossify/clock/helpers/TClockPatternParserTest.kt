package org.fossify.clock.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TClockPatternParserTest {
    @Test
    fun unsignedAndNegativeOffsetsAreBeforeEvent() {
        assertEquals(setOf(-30), TClockPatternParser.parseOffsets("TCLOCK:30min"))
        assertEquals(setOf(-45), TClockPatternParser.parseOffsets("TCLOCK:-45 minutes"))
    }

    @Test
    fun positiveAndZeroOffsetsAreHandled() {
        assertEquals(setOf(10), TClockPatternParser.parseOffsets("TCLOCK:+10m"))
        assertEquals(setOf(0), TClockPatternParser.parseOffsets("TCLOCK:0"))
    }

    @Test
    fun bracesUnitsAndCaseAreHandled() {
        assertEquals(setOf(-120), TClockPatternParser.parseOffsets("tclock:{2 heures}"))
        assertEquals(setOf(24 * 60), TClockPatternParser.parseOffsets("TCLOCK:{+1j}"))
    }

    @Test
    fun multipleMarkersAreDeduplicated() {
        val offsets = TClockPatternParser.parseOffsets(
            "TCLOCK:30min, TCLOCK:+1h and TCLOCK:30min"
        )

        assertEquals(setOf(-30, 60), offsets)
    }

    @Test
    fun malformedOrOverflowingMarkersAreIgnored() {
        assertTrue(TClockPatternParser.parseOffsets("TCLOCK:").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("TCLOCK:30minutesExtra").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("TCLOCK:1000000days").isEmpty())
    }
}
