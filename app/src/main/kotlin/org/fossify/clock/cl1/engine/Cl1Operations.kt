package org.fossify.clock.cl1.engine

import kotlinx.serialization.Serializable
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef

data class Cl1MirrorOverrides(
    val title: Cl1TitleOverride = Cl1TitleOverride.Inherited,
    val startOffsetSeconds: Long? = null,
    val duration: Cl1DurationOverride = Cl1DurationOverride.Inherited,
)

enum class Cl1DurationConversion {
    FIXED,
    DELTA,
}

data class Cl1OverrideConversion(
    val titleOverride: Cl1TitleOverride? = null,
    val durationMode: Cl1DurationConversion? = null,
)

sealed interface Cl1OperationResult {
    val operationId: String?

    data class Completed(
        override val operationId: String,
        val slotHex: String?,
    ) : Cl1OperationResult

    data class Pending(
        override val operationId: String,
        val reason: String,
    ) : Cl1OperationResult

    data class Conflict(
        override val operationId: String,
        val reason: String,
    ) : Cl1OperationResult

    data class Rejected(
        override val operationId: String?,
        val reason: String,
    ) : Cl1OperationResult
}

@Serializable
internal data class Cl1EventRefDto(
    val eventId: Long,
    val calendarId: Long,
) {
    fun toDomain(): Cl1EventRef = Cl1EventRef(eventId, calendarId)

    companion object {
        fun from(value: Cl1EventRef): Cl1EventRefDto {
            return Cl1EventRefDto(value.eventId, value.calendarId)
        }
    }
}

@Serializable
internal data class Cl1CreateJournal(
    val source: Cl1EventRefDto,
    val destinationCalendarId: Long,
    val destinationEmail: String,
    val secretHex: String,
    val createTokenHex: String,
    val titleMode: String,
    val titleValue: String? = null,
    val startOffsetSeconds: Long? = null,
    val durationMode: String,
    val durationValue: String? = null,
    val mirror: Cl1EventRefDto? = null,
    val appliedRevisionHex: String? = null,
    val replacedSlotHex: String? = null,
) {
    fun destinationRef(): Cl1CalendarRef = Cl1CalendarRef(destinationCalendarId)

    companion object {
    }
}

@Serializable
internal data class Cl1SyncJournal(
    val slotHex: String,
    val source: Cl1EventRefDto,
    val mirror: Cl1EventRefDto,
)

@Serializable
internal data class Cl1PairJournal(
    val slotHex: String,
    val source: Cl1EventRefDto,
    val mirror: Cl1EventRefDto,
    val expectedRevisionHex: String,
    val actualRevisionHex: String,
)

@Serializable
internal data class Cl1ApplyCopyJournal(
    val pair: Cl1PairJournal,
    val target: Cl1CanonicalEventDto,
)

@Serializable
internal data class Cl1ConvertJournal(
    val pair: Cl1PairJournal,
    val titleMode: String,
    val titleValue: String? = null,
    val startOffsetSeconds: Long?,
    val durationMode: String,
    val durationValue: String? = null,
) {
    companion object {
    }
}

@Serializable
internal data class Cl1UnlinkJournal(
    val slotHex: String,
    val secretHex: String,
    val source: Cl1EventRefDto,
    val mirror: Cl1EventRefDto,
)

@Serializable
internal data class Cl1CanonicalEventDto(
    val title: String,
    val startUnixSeconds: Long,
    val endUnixSeconds: Long,
    val startIanaTimeZone: String,
    val endIanaTimeZone: String,
    val location: String,
    val userDescription: String,
    val userUrl: String,
) {
    fun toDomain(): Cl1CanonicalEvent {
        return Cl1CanonicalEvent.fromSeconds(
            title = title,
            startUnixSeconds = startUnixSeconds,
            endUnixSeconds = endUnixSeconds,
            startIanaTimeZone = startIanaTimeZone,
            endIanaTimeZone = endIanaTimeZone,
            location = location,
            userDescription = userDescription,
            userUrl = userUrl
        )
    }

    companion object {
        fun from(value: Cl1CanonicalEvent): Cl1CanonicalEventDto {
            return Cl1CanonicalEventDto(
                title = value.title,
                startUnixSeconds = value.startUnixSeconds,
                endUnixSeconds = value.endUnixSeconds,
                startIanaTimeZone = value.startIanaTimeZone,
                endIanaTimeZone = value.endIanaTimeZone,
                location = value.location,
                userDescription = value.userDescription,
                userUrl = value.userUrl
            )
        }
    }
}

internal object Cl1OperationTypes {
    const val CREATE = "create"
    const val REPAIR = "repair"
    const val SYNC = "sync"
    const val RESTORE = "restore"
    const val APPLY_COPY = "applyCopy"
    const val CONVERT_OVERRIDES = "convertOverrides"
    const val UNLINK = "unlink"
}

internal object Cl1CreatePhases {
    const val PREPARED = "prepared"
    const val MIRROR_CREATING = "mirrorCreating"
    const val MIRROR_VERIFIED = "mirrorVerified"
    const val SOURCE_COMMITTED = "sourceCommitted"
    const val CONFLICT = "conflict"
}

internal object Cl1SyncPhases {
    const val PREPARED = "prepared"
    const val APPLYING = "applying"
    const val CONFLICT = "conflict"
}

internal object Cl1ResolutionPhases {
    const val PREPARED = "prepared"
    const val APPLYING = "applying"
    const val SOURCE_DETACHED = "sourceDetached"
    const val SOURCE_APPLIED = "sourceApplied"
    const val MIRROR_APPLYING = "mirrorApplying"
    const val CONFLICT = "conflict"
}
