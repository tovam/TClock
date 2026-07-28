package org.fossify.clock.cl1.ui

import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEmail
import org.fossify.clock.cl1.engine.Cl1RelationKey
import org.fossify.clock.cl1.engine.Cl1RelationSnapshot
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.provider.Cl1CalendarCapability
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Cl1UiPolicyTest {
    @Test
    fun `copy conflict actions never overwrite automatically`() {
        assertEquals(
            listOf(
                Cl1RelationUiAction.UNLINK,
                Cl1RelationUiAction.DELETE_SOURCE_AND_COPIES
            ),
            relation(Cl1RelationState.CONCURRENT_CONFLICT)
                .availableUiActions(canWrite = true)
        )
        assertEquals(
            listOf(
                Cl1RelationUiAction.RESTORE_FROM_SOURCE,
                Cl1RelationUiAction.APPLY_COPY_TO_SOURCE,
                Cl1RelationUiAction.CONVERT_TO_OVERRIDES,
                Cl1RelationUiAction.UNLINK,
                Cl1RelationUiAction.DELETE_SOURCE_AND_COPIES
            ),
            relation(Cl1RelationState.COPY_MODIFIED)
                .availableUiActions(canWrite = true)
        )
    }

    @Test
    fun `missing copy can only be repaired`() {
        assertEquals(
            listOf(Cl1RelationUiAction.REPAIR),
            relation(Cl1RelationState.MISSING_OR_INACCESSIBLE)
                .availableUiActions(canWrite = true)
        )
    }

    @Test
    fun `unsafe or read only states expose no mutation`() {
        Cl1RelationState.entries.forEach { state ->
            assertTrue(relation(state).availableUiActions(canWrite = false).isEmpty())
        }
        listOf(
            Cl1RelationState.UNRESOLVED,
            Cl1RelationState.ORPHAN,
            Cl1RelationState.RECORD_CORRUPT,
            Cl1RelationState.RELATION_CONFLICT,
            Cl1RelationState.INCOMPATIBLE
        ).forEach { state ->
            assertTrue(relation(state).availableUiActions(canWrite = true).isEmpty())
        }
    }

    @Test
    fun `copy creation requires write permission capabilities and positive duration`() {
        assertTrue(event().canCreateCl1Copy(canWrite = true))
        assertFalse(event().canCreateCl1Copy(canWrite = false))
        assertFalse(
            event(
                calendarCapabilities = setOf(Cl1CalendarCapability.READ)
            ).canCreateCl1Copy(canWrite = true)
        )
        assertFalse(
            event(
                startMillis = 2_000,
                endMillis = 2_000
            ).canCreateCl1Copy(canWrite = true)
        )
    }

    private fun relation(state: Cl1RelationState): Cl1RelationSnapshot {
        return Cl1RelationSnapshot(
            key = Cl1RelationKey(SLOT),
            state = state,
            source = null,
            mirror = null,
            sourcePayload = null,
            sourceRecordIndex = null,
            mirrorPayload = null,
            expectedMirror = null,
            expectedRevision = null,
            actualRevision = null
        )
    }

    private fun event(
        startMillis: Long = 1_000,
        endMillis: Long = 2_000,
        calendarCapabilities: Set<Cl1CalendarCapability> =
            Cl1CalendarCapability.entries.toSet(),
    ): Cl1EventSnapshot {
        val calendar = Cl1CalendarDescriptor(
            ref = Cl1CalendarRef(20),
            displayName = "Calendar",
            color = null,
            accountName = EMAIL.value,
            accountType = "test",
            canonicalAccountEmail = EMAIL,
            visible = true,
            accessLevel = 700,
            capabilities = calendarCapabilities
        )
        return Cl1EventSnapshot(
            ref = Cl1EventRef(eventId = 10, calendarId = 20),
            calendar = calendar,
            title = "Event",
            startMillis = startMillis,
            endMillis = endMillis,
            startTimeZone = "UTC",
            endTimeZone = "UTC",
            location = "",
            description = "notes",
            userUrl = null,
            uid2445 = null,
            allDay = false,
            recurrenceRule = null,
            recurrenceDate = null,
            exceptionRule = null,
            exceptionDate = null,
            originalEventId = null,
            rawStatus = null,
            recurring = false,
            canceled = false,
            deleted = false
        )
    }

    private companion object {
        val SLOT = Cl1Bytes.fromHex("000102030405060708090a0b")
        val EMAIL = Cl1CanonicalEmail("me@example.com")
    }
}
