package org.fossify.clock.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TClockPatternParserTest {
    @Test
    fun unsignedAndNegativeOffsetsAreBeforeEvent() {
        assertEquals(setOf(-30), TClockPatternParser.parseOffsets("tclock:{30min}"))
        assertEquals(setOf(-45), TClockPatternParser.parseOffsets("tclock:{-45min}"))
    }

    @Test
    fun positiveAndZeroOffsetsAreHandled() {
        assertEquals(setOf(10), TClockPatternParser.parseOffsets("tclock:{+10min}"))
        assertEquals(setOf(0), TClockPatternParser.parseOffsets("tclock:{0min}"))
    }

    @Test
    fun supportedUnitsAndCaseInsensitivityAreHandled() {
        assertEquals(setOf(-30), TClockPatternParser.parseOffsets("TCLOCK:{30MIN}"))
        assertEquals(setOf(-120), TClockPatternParser.parseOffsets("TcLoCk:{2H}"))
        assertEquals(setOf(-24 * 60), TClockPatternParser.parseOffsets("tclock:{1d}"))
        assertEquals(setOf(24 * 60), TClockPatternParser.parseOffsets("TCLOCK:{+1J}"))
    }

    @Test
    fun multipleMarkersAreDeduplicated() {
        val offsets = TClockPatternParser.parseOffsets(
            "tclock:{30min}, TCLOCK:{+1h} and TClock:{30min}"
        )

        assertEquals(setOf(-30, 60), offsets)
    }

    @Test
    fun bracesAndSupportedUnitAreMandatory() {
        assertTrue(TClockPatternParser.parseOffsets("tclock:30min").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30m}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30minutes}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30hours}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30days}").isEmpty())
    }

    @Test
    fun whitespaceInsideThePatternIsRejected() {
        assertTrue(TClockPatternParser.parseOffsets("tclock: {30min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{ 30min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30 min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30min }").isEmpty())
    }

    @Test
    fun malformedOrOversizedMarkersAreIgnored() {
        assertTrue(TClockPatternParser.parseOffsets("tclock:{}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30min}extra").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{{30min}}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{30min}}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("xtclock:{30min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{1.5h}").isEmpty())
        assertEquals(
            setOf(-2_147_483_640),
            TClockPatternParser.parseOffsets("tclock:{35791394h}")
        )
        assertTrue(TClockPatternParser.parseOffsets("tclock:{35791395h}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("tclock:{999999999999999999999d}").isEmpty())
    }

    @Test
    fun invalidMarkerDoesNotHideValidMarker() {
        assertEquals(
            setOf(-15),
            TClockPatternParser.parseOffsets("tclock:{30minutes} then tclock:{15min}")
        )
    }
}
