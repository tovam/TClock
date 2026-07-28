package org.fossify.clock.cl1.provider

import org.fossify.clock.cl1.Cl1CanonicalEmail
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1Description

data class Cl1CalendarRef(
    val calendarId: Long,
)

data class Cl1EventRef(
    val eventId: Long,
    val calendarId: Long,
)

enum class Cl1CalendarCapability {
    READ,
    WRITE,
    PRESERVE_DESCRIPTION,
    ACCOUNT_EMAIL,
    IDEMPOTENT_CREATE,
    CONDITIONAL_UPDATE,
    CONDITIONAL_DELETE,
}

data class Cl1CalendarDescriptor(
    val ref: Cl1CalendarRef,
    val displayName: String,
    val color: Int?,
    val accountName: String,
    val accountType: String,
    val canonicalAccountEmail: Cl1CanonicalEmail?,
    val visible: Boolean,
    val accessLevel: Int,
    val capabilities: Set<Cl1CalendarCapability>,
) {
    val supportsSourceRelations: Boolean
        get() = capabilities.containsAll(SOURCE_RELATION_CAPABILITIES)

    val supportsMirrorRelations: Boolean
        get() = capabilities.containsAll(MIRROR_RELATION_CAPABILITIES)

    val supportsCompleteRelations: Boolean
        get() = supportsMirrorRelations

    companion object {
        val SOURCE_RELATION_CAPABILITIES = setOf(
            Cl1CalendarCapability.READ,
            Cl1CalendarCapability.WRITE,
            Cl1CalendarCapability.PRESERVE_DESCRIPTION,
            Cl1CalendarCapability.CONDITIONAL_UPDATE,
            Cl1CalendarCapability.CONDITIONAL_DELETE
        )
        val MIRROR_RELATION_CAPABILITIES = Cl1CalendarCapability.entries.toSet()
    }
}

data class Cl1EventSnapshot(
    val ref: Cl1EventRef,
    val calendar: Cl1CalendarDescriptor,
    val title: String?,
    val startMillis: Long,
    val endMillis: Long?,
    val startTimeZone: String?,
    val endTimeZone: String?,
    val location: String?,
    val description: String,
    val userUrl: String?,
    val uid2445: String?,
    val allDay: Boolean,
    val recurrenceRule: String?,
    val recurrenceDate: String?,
    val exceptionRule: String?,
    val exceptionDate: String?,
    val originalEventId: Long?,
    val rawStatus: Int?,
    val recurring: Boolean,
    val canceled: Boolean,
    val deleted: Boolean,
    val hasAttendees: Boolean = false,
) {
    val parsedDescription: Cl1Description by lazy(LazyThreadSafetyMode.NONE) {
        org.fossify.clock.cl1.Cl1Armor.parse(description)
    }

    fun canonicalEvent(): Cl1CanonicalEvent {
        if (allDay) {
            throw Cl1CalendarIncompatibleException("allDay")
        }
        if (recurring) {
            throw Cl1CalendarIncompatibleException("recurrence")
        }
        if (canceled) {
            throw Cl1CalendarIncompatibleException("status")
        }
        if (deleted) {
            throw Cl1CalendarIncompatibleException("deleted")
        }
        if (hasAttendees) {
            throw Cl1CalendarIncompatibleException("attendees")
        }
        val safeEndMillis = endMillis
            ?: throw Cl1CalendarIncompatibleException("end")
        val userDescription = when (val parsed = parsedDescription) {
            is Cl1Description.None -> parsed.originalDescription
            is Cl1Description.Valid -> parsed.userDescription
            is Cl1Description.UnsupportedVersion -> parsed.userDescription
            is Cl1Description.Corrupt -> {
                throw Cl1CalendarIncompatibleException("description")
            }
        }
        return Cl1CanonicalEvent.fromMillis(
            title = title,
            startUnixMillis = startMillis,
            endUnixMillis = safeEndMillis,
            startIanaTimeZone = startTimeZone,
            endIanaTimeZone = endTimeZone,
            location = location,
            userDescription = userDescription,
            userUrl = userUrl
        )
    }
}

data class Cl1EventWrite(
    val canonicalEvent: Cl1CanonicalEvent,
    val description: String,
)

sealed interface Cl1CreateResult {
    data class Created(val event: Cl1EventSnapshot) : Cl1CreateResult

    data class Existing(val event: Cl1EventSnapshot) : Cl1CreateResult

    data class Conflict(val reason: String) : Cl1CreateResult

    data class Ineligible(
        val reason: String,
        val event: Cl1EventSnapshot? = null,
    ) : Cl1CreateResult

    data class Failed(
        val reason: String,
        val event: Cl1EventSnapshot? = null,
    ) : Cl1CreateResult
}

sealed interface Cl1MutationResult {
    data class Applied(val event: Cl1EventSnapshot?) : Cl1MutationResult

    data object PreconditionFailed : Cl1MutationResult

    data object Missing : Cl1MutationResult

    data class Ineligible(val reason: String) : Cl1MutationResult

    data class Failed(val reason: String) : Cl1MutationResult
}

interface Cl1CalendarAdapter {
    fun listCalendars(): List<Cl1CalendarDescriptor>

    fun listEvents(
        beginMillis: Long,
        endMillis: Long,
    ): List<Cl1EventSnapshot>

    fun readEvent(ref: Cl1EventRef): Cl1EventSnapshot?

    fun findCreatedEvent(
        calendar: Cl1CalendarDescriptor,
        createToken: String,
    ): Cl1EventSnapshot? = null

    fun createEvent(
        calendar: Cl1CalendarDescriptor,
        createToken: String,
        value: Cl1EventWrite,
    ): Cl1CreateResult

    fun updateEvent(
        expected: Cl1EventSnapshot,
        value: Cl1EventWrite,
    ): Cl1MutationResult

    fun moveEvent(
        expected: Cl1EventSnapshot,
        destination: Cl1CalendarDescriptor,
        value: Cl1EventWrite,
    ): Cl1MutationResult = Cl1MutationResult.Ineligible("move")

    fun deleteEvent(expected: Cl1EventSnapshot): Cl1MutationResult
}

class Cl1CalendarIncompatibleException(
    val field: String,
) : IllegalArgumentException(field)
