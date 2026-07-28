package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.provider.Cl1EventSnapshot

enum class Cl1RelationState {
    ACTIVE,
    SOURCE_MODIFIED,
    COPY_MODIFIED,
    CONCURRENT_CONFLICT,
    MISSING_OR_INACCESSIBLE,
    UNRESOLVED,
    ORPHAN,
    RECORD_CORRUPT,
    RELATION_CONFLICT,
    INCOMPATIBLE,
}

enum class Cl1EventIssueState {
    UNSUPPORTED_VERSION,
    BLOCK_CORRUPT,
}

data class Cl1RelationKey(
    val slot: Cl1Bytes,
)

data class Cl1RelationSnapshot(
    val key: Cl1RelationKey,
    val state: Cl1RelationState,
    val source: Cl1EventSnapshot?,
    val mirror: Cl1EventSnapshot?,
    val sourcePayload: Cl1Payload.Source?,
    val sourceRecordIndex: Int?,
    val mirrorPayload: Cl1Payload.Mirror?,
    val expectedMirror: Cl1CanonicalEvent?,
    val expectedRevision: Cl1Bytes?,
    val actualRevision: Cl1Bytes?,
    val needsRevisionRefresh: Boolean = false,
    val detail: String? = null,
) {
    val suppressMirrorAlarm: Boolean
        get() = state == Cl1RelationState.ACTIVE && source != null && mirror != null
}

data class Cl1EventIssue(
    val event: Cl1EventSnapshot,
    val state: Cl1EventIssueState,
    val detail: String?,
)

data class Cl1DiscoverySnapshot(
    val capturedAtMillis: Long,
    val events: List<Cl1EventSnapshot>,
    val relations: List<Cl1RelationSnapshot>,
    val eventIssues: List<Cl1EventIssue>,
) {
    val mirrorAlarmSuppressions: Set<org.fossify.clock.cl1.provider.Cl1EventRef>
        get() = relations
            .asSequence()
            .filter(Cl1RelationSnapshot::suppressMirrorAlarm)
            .mapNotNull { it.mirror?.ref }
            .toSet()
}

internal data class Cl1SourceCandidate(
    val event: Cl1EventSnapshot,
    val payload: Cl1Payload.Source,
    val recordIndex: Int,
)

internal data class Cl1MirrorCandidate(
    val event: Cl1EventSnapshot,
    val payload: Cl1Payload.Mirror,
)

internal fun Cl1EventSnapshot.validSourcePayload(): Cl1Payload.Source? {
    return (parsedDescription as? Cl1Description.Valid)?.payload as? Cl1Payload.Source
}

internal fun Cl1EventSnapshot.validMirrorPayload(): Cl1Payload.Mirror? {
    return (parsedDescription as? Cl1Description.Valid)?.payload as? Cl1Payload.Mirror
}
