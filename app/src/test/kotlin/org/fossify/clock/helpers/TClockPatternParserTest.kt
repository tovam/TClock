package org.fossify.clock.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TClockPatternParserTest {
    @Test
    fun unsignedAndNegativeOffsetsAreBeforeEvent() {
        assertEquals(setOf(-30), TClockPatternParser.parseOffsets("alarm:30min"))
        assertEquals(setOf(-45), TClockPatternParser.parseOffsets("alarms:-45min"))
    }

    @Test
    fun positiveAndZeroOffsetsAreHandled() {
        assertEquals(setOf(10), TClockPatternParser.parseOffsets("alarm:+10min"))
        assertEquals(setOf(0), TClockPatternParser.parseOffsets("alarms:0min"))
    }

    @Test
    fun markerIsFoundBetweenDescriptionLines() {
        assertEquals(
            setOf(-60),
            TClockPatternParser.parseOffsets("xxx\nALARM:-60min\nxxxx")
        )
    }

    @Test
    fun singularPluralUnitAliasesAndCaseInsensitivityAreHandled() {
        listOf("m", "min", "mins", "minute", "minutes").forEach { unit ->
            assertEquals(setOf(-2), TClockPatternParser.parseOffsets("alarm:2$unit"))
        }
        listOf("h", "hr", "hrs", "hour", "hours", "heure", "heures").forEach { unit ->
            assertEquals(setOf(-120), TClockPatternParser.parseOffsets("alarms:2$unit"))
        }
        listOf("d", "day", "days", "j", "jour", "jours").forEach { unit ->
            assertEquals(
                setOf(-2 * 24 * 60),
                TClockPatternParser.parseOffsets("alarm:2$unit")
            )
        }
        assertEquals(setOf(30), TClockPatternParser.parseOffsets("AlArMs:+30MiNuTeS"))
    }

    @Test
    fun equivalentUnitAliasesAreDeduplicated() {
        assertEquals(
            setOf(-30),
            TClockPatternParser.parseOffsets(
                "alarm:30m, alarms:30min, ALARM:30minutes"
            )
        )
    }

    @Test
    fun multipleMarkersAreDeduplicated() {
        val offsets = TClockPatternParser.parseOffsets(
            "alarm:30min, ALARMS:+1h and Alarm:30min"
        )

        assertEquals(setOf(-30, 60), offsets)
    }

    @Test
    fun delayAndSupportedUnitAreMandatory() {
        assertTrue(TClockPatternParser.parseOffsets("alarm:").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarms:30").isEmpty())
        listOf("mn", "sec", "seconds", "week", "semaine", "minutee", "hrss", "joursx")
            .forEach { unit ->
                assertTrue(TClockPatternParser.parseOffsets("alarm:30$unit").isEmpty())
            }
    }

    @Test
    fun whitespaceInsideThePatternIsRejected() {
        assertTrue(TClockPatternParser.parseOffsets("alarm: 30min").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarms:- 30min").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarm:30 min").isEmpty())
    }

    @Test
    fun malformedOrOversizedMarkersAreIgnored() {
        assertTrue(TClockPatternParser.parseOffsets("alarm:{30min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarms:30minextra").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarm:30min}").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("xalarm:30min").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarm:1.5h").isEmpty())
        assertEquals(
            setOf(-2_147_483_640),
            TClockPatternParser.parseOffsets("alarm:35791394h")
        )
        assertTrue(TClockPatternParser.parseOffsets("alarms:35791395h").isEmpty())
        assertTrue(TClockPatternParser.parseOffsets("alarm:999999999999999999999d").isEmpty())
    }

    @Test
    fun invalidMarkerDoesNotHideValidMarker() {
        assertEquals(
            setOf(-15),
            TClockPatternParser.parseOffsets("alarm:30seconds then alarms:15min")
        )
    }

    @Test
    fun legacyTclockKeywordIsRejected() {
        assertTrue(TClockPatternParser.parseOffsets("tclock:30min").isEmpty())
    }
}
