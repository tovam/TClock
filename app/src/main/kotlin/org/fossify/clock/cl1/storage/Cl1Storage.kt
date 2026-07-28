package org.fossify.clock.cl1.storage

import org.fossify.clock.cl1.engine.Cl1DiscoverySnapshot
import org.fossify.clock.cl1.engine.Cl1EventIssueState
import org.fossify.clock.cl1.engine.Cl1RelationState
import org.fossify.clock.cl1.provider.Cl1EventRef

enum class Cl1BindingRole {
    SOURCE,
    MIRROR,
    ISSUE,
}

data class Cl1CachedBinding(
    val slotHex: String?,
    val role: Cl1BindingRole,
    val ref: Cl1EventRef,
    val startMillis: Long,
    val lastSeenMillis: Long,
)

data class Cl1CachedRelation(
    val slotHex: String,
    val state: Cl1RelationState,
    val sourceRef: Cl1EventRef?,
    val mirrorRef: Cl1EventRef?,
    val sourceTitle: String?,
    val mirrorTitle: String?,
    val sourceStartMillis: Long?,
    val mirrorStartMillis: Long?,
    val expectedRevisionHex: String?,
    val actualRevisionHex: String?,
    val needsRevisionRefresh: Boolean,
    val detail: String?,
    val lastSeenMillis: Long,
)

data class Cl1CachedEventIssue(
    val ref: Cl1EventRef,
    val state: Cl1EventIssueState,
    val title: String?,
    val startMillis: Long,
    val detail: String?,
    val lastSeenMillis: Long,
)

data class Cl1PendingOperation(
    val operationId: String,
    val slotHex: String?,
    val type: String,
    val phase: String,
    val payload: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val attempts: Int,
    val lastError: String?,
)

interface Cl1Storage {
    fun listCachedBindings(): List<Cl1CachedBinding>

    fun listCachedRelations(): List<Cl1CachedRelation>

    fun listCachedEventIssues(): List<Cl1CachedEventIssue>

    fun saveDiscovery(snapshot: Cl1DiscoverySnapshot)

    fun putOperation(operation: Cl1PendingOperation)

    fun listPendingOperations(): List<Cl1PendingOperation>

    fun removeOperation(operationId: String)
}
