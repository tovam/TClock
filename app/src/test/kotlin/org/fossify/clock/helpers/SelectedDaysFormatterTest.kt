package org.fossify.clock.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Arrays

class SelectedDaysFormatterTest {
    private val arraysBackedWeekDays = Arrays.asList(
        "Mon",
        "Tue",
        "Wed",
        "Thu",
        "Fri",
        "Sat",
        "Sun"
    )

    @Test
    fun formatsArraysBackedListWithoutConcreteListCast() {
        val mondayAndWednesday = (1 shl 0) or (1 shl 2)

        assertEquals(
            "Mon, Wed",
            formatSelectedDays(
                bitMask = mondayAndWednesday,
                weekDays = arraysBackedWeekDays,
                firstDayOfWeek = 1
            )
        )
    }

    @Test
    fun respectsSundayAsFirstDayOfWeek() {
        val sundayMondayAndWednesday = (1 shl 6) or (1 shl 0) or (1 shl 2)

        assertEquals(
            "Sun, Mon, Wed",
            formatSelectedDays(
                bitMask = sundayMondayAndWednesday,
                weekDays = arraysBackedWeekDays,
                firstDayOfWeek = 7
            )
        )
    }

    @Test
    fun formatsEmptySelection() {
        assertEquals(
            "",
            formatSelectedDays(
                bitMask = 0,
                weekDays = arraysBackedWeekDays,
                firstDayOfWeek = 1
            )
        )
    }
}
