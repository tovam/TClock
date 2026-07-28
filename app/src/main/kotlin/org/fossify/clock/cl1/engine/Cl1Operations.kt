package org.fossify.clock.cl1.engine

import kotlinx.serialization.Serializable
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef

data class Cl1MirrorOverrides(
    val title: Cl1TitleOverride = Cl1TitleOverride.Inherited,
    val startOffsetSeconds: Long? = null,
    val duration: Cl1DurationOverride = Cl1DurationOverride.Inherited,
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

internal object Cl1OperationTypes {
    const val CREATE = "create"
    const val REPAIR = "repair"
    const val SYNC = "sync"
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
