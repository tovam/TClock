package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEmail
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1Crypto
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1Revision
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.provider.Cl1CalendarCapability
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Cl1DiscoveryTest {
    @Test
    fun `revision matrix classifies every synchronized and changed state`() {
        val active = pair(sourceTitle = "Old", mirrorTitle = "Old", baselineTitle = "Old")
        val sourceModified = pair(
            sourceTitle = "New",
            mirrorTitle = "Old",
            baselineTitle = "Old"
        )
        val copyModified = pair(
            sourceTitle = "Old",
            mirrorTitle = "Copy edit",
            baselineTitle = "Old"
        )
        val conflict = pair(
            sourceTitle = "Source edit",
            mirrorTitle = "Copy edit",
            baselineTitle = "Old"
        )

        assertEquals(Cl1RelationState.ACTIVE, discover(active).state)
        assertEquals(
            Cl1RelationState.SOURCE_MODIFIED,
            discover(sourceModified).state
        )
        assertEquals(Cl1RelationState.COPY_MODIFIED, discover(copyModified).state)
        assertEquals(
            Cl1RelationState.CONCURRENT_CONFLICT,
            discover(conflict).state
        )
    }

    @Test
    fun `only an active complete pair suppresses the mirror alarm`() {
        val active = pair(sourceTitle = "Old", mirrorTitle = "Old", baselineTitle = "Old")
        val changed = pair(
            sourceTitle = "Old",
            mirrorTitle = "Copy edit",
            baselineTitle = "Old",
            sourceEventId = 11,
            mirrorEventId = 12,
            secret = SECOND_SECRET
        )
        val discovery = Cl1Discovery.build(active + changed)

        assertEquals(setOf(active.last().ref), discovery.mirrorAlarmSuppressions)
        assertTrue(discovery.relations.single { it.state == Cl1RelationState.ACTIVE }
            .suppressMirrorAlarm)
        assertFalse(discovery.relations.single {
            it.state == Cl1RelationState.COPY_MODIFIED
        }.suppressMirrorAlarm)
    }

    @Test
    fun `missing unresolved corrupt and duplicate relations remain non destructive`() {
        val complete = pair(sourceTitle = "Old", mirrorTitle = "Old", baselineTitle = "Old")
        val source = complete.first()
        val mirror = complete.last()
        assertEquals(
            Cl1RelationState.MISSING_OR_INACCESSIBLE,
            discover(listOf(source)).state
        )
        assertEquals(Cl1RelationState.UNRESOLVED, discover(listOf(mirror)).state)

        val corruptPair = pair(
            sourceTitle = "Old",
            mirrorTitle = "Old",
            baselineTitle = "Old",
            encryptedEmail = Cl1CanonicalEmail("other@example.com")
        )
        assertEquals(
            Cl1RelationState.RECORD_CORRUPT,
            discover(corruptPair).state
        )

        val duplicateMirror = mirror.copy(
            ref = Cl1EventRef(eventId = 99, calendarId = mirror.ref.calendarId)
        )
        assertEquals(
            Cl1RelationState.RELATION_CONFLICT,
            discover(listOf(source, mirror, duplicateMirror)).state
        )
    }

    @Test
    fun `unsupported and corrupt blocks are reported without producing relations`() {
        val unsupported = plainEvent(
            eventId = 1,
            description = "\n\n-----BEGIN CL2-----\nAA\n-----END CL2-----"
        )
        val corrupt = plainEvent(
            eventId = 2,
            description = "\n\n-----BEGIN CL1-----\nbad\n-----END CL1-----"
        )

        val discovery = Cl1Discovery.build(listOf(unsupported, corrupt))

        assertTrue(discovery.relations.isEmpty())
        assertEquals(
            setOf(
                Cl1EventIssueState.UNSUPPORTED_VERSION,
                Cl1EventIssueState.BLOCK_CORRUPT
            ),
            discovery.eventIssues.map { it.state }.toSet()
        )
    }

    private fun discover(events: List<Cl1EventSnapshot>): Cl1RelationSnapshot {
        return Cl1Discovery.build(events).relations.single()
    }

    private fun pair(
        sourceTitle: String,
        mirrorTitle: String,
        baselineTitle: String,
        encryptedEmail: Cl1CanonicalEmail = ACCOUNT_EMAIL,
        sourceEventId: Long = 1,
        mirrorEventId: Long = 2,
        secret: Cl1Bytes = SECRET,
    ): List<Cl1EventSnapshot> {
        val encrypted = Cl1Crypto.encryptEmail(secret, encryptedEmail)
        val sourcePayload = Cl1Payload.Source(listOf(encrypted.toSourceRecord()))
        val baseline = canonical(baselineTitle)
        val mirrorPayload = Cl1Payload.Mirror(
            secret = secret,
            revision = Cl1Revision.calculate(secret, baseline),
            titleOverride = Cl1TitleOverride.Inherited,
            startOffsetSeconds = null,
            durationOverride = Cl1DurationOverride.Inherited
        )
        return listOf(
            plainEvent(
                eventId = sourceEventId,
                calendarId = 10,
                title = sourceTitle,
                description = Cl1Armor.compose(USER_DESCRIPTION, sourcePayload)
            ),
            plainEvent(
                eventId = mirrorEventId,
                calendarId = 20,
                title = mirrorTitle,
                description = Cl1Armor.compose(USER_DESCRIPTION, mirrorPayload)
            )
        )
    }

    private fun canonical(title: String): Cl1CanonicalEvent {
        return Cl1CanonicalEvent.fromSeconds(
            title = title,
            startUnixSeconds = START_SECONDS,
            endUnixSeconds = END_SECONDS,
            startIanaTimeZone = "UTC",
            endIanaTimeZone = "UTC",
            location = "",
            userDescription = USER_DESCRIPTION,
            userUrl = ""
        )
    }

    private fun plainEvent(
        eventId: Long,
        calendarId: Long = 10,
        title: String = "Title",
        description: String,
    ): Cl1EventSnapshot {
        return Cl1EventSnapshot(
            ref = Cl1EventRef(eventId, calendarId),
            calendar = calendar(calendarId),
            title = title,
            startMillis = START_SECONDS * 1_000,
            endMillis = END_SECONDS * 1_000,
            startTimeZone = "UTC",
            endTimeZone = "UTC",
            location = "",
            description = description,
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

    private fun calendar(calendarId: Long): Cl1CalendarDescriptor {
        return Cl1CalendarDescriptor(
            ref = Cl1CalendarRef(calendarId),
            displayName = "Calendar $calendarId",
            color = null,
            accountName = ACCOUNT_EMAIL.value,
            accountType = "test",
            canonicalAccountEmail = ACCOUNT_EMAIL,
            visible = true,
            accessLevel = 700,
            capabilities = Cl1CalendarCapability.entries.toSet()
        )
    }

    private companion object {
        val SECRET = Cl1Bytes.fromHex("000102030405060708090a0b0c0d0e0f")
        val SECOND_SECRET = Cl1Bytes.fromHex("101112131415161718191a1b1c1d1e1f")
        val ACCOUNT_EMAIL = Cl1CanonicalEmail("me@example.com")
        const val USER_DESCRIPTION = "notes"
        const val START_SECONDS = 1_000L
        const val END_SECONDS = 2_000L
    }
}
