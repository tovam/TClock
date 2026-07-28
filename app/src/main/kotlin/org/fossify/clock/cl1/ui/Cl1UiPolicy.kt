package org.fossify.clock.cl1.ui

import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1Limits
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.engine.Cl1RelationSnapshot
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.provider.Cl1EventSnapshot

enum class Cl1RelationUiAction {
    REPAIR,
    RESTORE_FROM_SOURCE,
    APPLY_COPY_TO_SOURCE,
    CONVERT_TO_OVERRIDES,
    CHANGE_DESTINATION,
    UNLINK,
    DELETE_SOURCE_AND_COPIES,
}

fun Cl1RelationSnapshot.availableUiActions(
    canWrite: Boolean,
): List<Cl1RelationUiAction> {
    if (!canWrite) {
        return emptyList()
    }
    return when (state) {
        Cl1RelationState.ACTIVE -> buildList {
            if (!needsRevisionRefresh) {
                add(Cl1RelationUiAction.CHANGE_DESTINATION)
            }
            add(Cl1RelationUiAction.UNLINK)
            add(Cl1RelationUiAction.DELETE_SOURCE_AND_COPIES)
        }

        Cl1RelationState.SOURCE_MODIFIED -> listOf(
            Cl1RelationUiAction.UNLINK
        )

        Cl1RelationState.COPY_MODIFIED -> listOf(
            Cl1RelationUiAction.RESTORE_FROM_SOURCE,
            Cl1RelationUiAction.APPLY_COPY_TO_SOURCE,
            Cl1RelationUiAction.CONVERT_TO_OVERRIDES,
            Cl1RelationUiAction.UNLINK
        )

        Cl1RelationState.CONCURRENT_CONFLICT -> listOf(
            Cl1RelationUiAction.UNLINK
        )

        Cl1RelationState.MISSING_OR_INACCESSIBLE -> listOf(
            Cl1RelationUiAction.REPAIR
        )

        Cl1RelationState.UNRESOLVED,
        Cl1RelationState.ORPHAN,
        Cl1RelationState.RECORD_CORRUPT,
        Cl1RelationState.RELATION_CONFLICT,
        Cl1RelationState.INCOMPATIBLE,
        -> emptyList()
    }
}

fun Cl1EventSnapshot.canCreateCl1Copy(canWrite: Boolean): Boolean {
    if (!canWrite || !calendar.supportsSourceRelations) {
        return false
    }
    val sourceRoleAvailable = when (val parsed = parsedDescription) {
        is Cl1Description.None -> true
        is Cl1Description.Valid -> {
            val source = parsed.payload as? Cl1Payload.Source
            source != null && source.records.size < Cl1Limits.SOURCE_RECORDS
        }

        is Cl1Description.UnsupportedVersion,
        is Cl1Description.Corrupt,
        -> false
    }
    if (!sourceRoleAvailable) {
        return false
    }
    return try {
        val canonical = canonicalEvent()
        canonical.endUnixSeconds > canonical.startUnixSeconds
    } catch (_: IllegalArgumentException) {
        false
    }
}
