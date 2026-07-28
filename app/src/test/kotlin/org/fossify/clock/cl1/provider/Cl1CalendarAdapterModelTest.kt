package org.fossify.clock.cl1.provider

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1TitleOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Cl1CalendarAdapterModelTest {
    @Test
    fun `canonical event excludes the CL1 block and floors milliseconds`() {
        val payload = Cl1Payload.Mirror(
            secret = Cl1Bytes.fromHex("000102030405060708090a0b0c0d0e0f"),
            revision = Cl1Bytes.fromHex("1122334455667788"),
            titleOverride = Cl1TitleOverride.Inherited,
            startOffsetSeconds = null,
            durationOverride = Cl1DurationOverride.Inherited
        )
        val event = event(
            description = Cl1Armor.compose("notes", payload),
            startMillis = 1_999,
            endMillis = 3_001
        )

        val canonical = event.canonicalEvent()
        assertEquals("notes", canonical.userDescription)
        assertEquals(1L, canonical.startUnixSeconds)
        assertEquals(3L, canonical.endUnixSeconds)
    }

    @Test
    fun `all day recurring canceled and corrupt events are incompatible`() {
        assertEquals(
            "allDay",
            assertThrows(Cl1CalendarIncompatibleException::class.java) {
                event(allDay = true).canonicalEvent()
            }.field
        )
        assertEquals(
            "recurrence",
            assertThrows(Cl1CalendarIncompatibleException::class.java) {
                event(recurring = true).canonicalEvent()
            }.field
        )
        assertEquals(
            "status",
            assertThrows(Cl1CalendarIncompatibleException::class.java) {
                event(canceled = true).canonicalEvent()
            }.field
        )
        assertEquals(
            "description",
            assertThrows(Cl1CalendarIncompatibleException::class.java) {
                event(description = "\n\n-----BEGIN CL1-----\nbad\n").canonicalEvent()
            }.field
        )
    }

    private fun event(
        description: String = "notes",
        startMillis: Long = 1_000,
        endMillis: Long = 2_000,
        allDay: Boolean = false,
        recurring: Boolean = false,
        canceled: Boolean = false,
    ): Cl1EventSnapshot {
        return Cl1EventSnapshot(
            ref = Cl1EventRef(eventId = 10, calendarId = 20),
            calendar = calendar(),
            title = "Title",
            startMillis = startMillis,
            endMillis = endMillis,
            startTimeZone = "UTC",
            endTimeZone = "UTC",
            location = "",
            description = description,
            userUrl = null,
            uid2445 = null,
            allDay = allDay,
            recurrenceRule = if (recurring) "FREQ=DAILY" else null,
            recurrenceDate = null,
            exceptionRule = null,
            exceptionDate = null,
            originalEventId = null,
            rawStatus = null,
            recurring = recurring,
            canceled = canceled,
            deleted = false
        )
    }

    private fun calendar(): Cl1CalendarDescriptor {
        return Cl1CalendarDescriptor(
            ref = Cl1CalendarRef(20),
            displayName = "Calendar",
            color = null,
            accountName = "me@example.com",
            accountType = "test",
            canonicalAccountEmail = null,
            visible = true,
            accessLevel = 0,
            capabilities = setOf(Cl1CalendarCapability.READ)
        )
    }
}
