@file:Suppress("LongMethod", "ReturnCount", "TooManyFunctions")

package org.fossify.clock.cl1.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1Crypto
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DomainToAscii
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1Email
import org.fossify.clock.cl1.Cl1JdkDomainToAscii
import org.fossify.clock.cl1.Cl1Limits
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1Revision
import org.fossify.clock.cl1.Cl1SourceRecord
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.Cl1Transform
import org.fossify.clock.cl1.provider.Cl1CalendarAdapter
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.provider.Cl1EventWrite
import org.fossify.clock.cl1.provider.Cl1MutationResult
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.storage.Cl1Storage
import java.util.UUID

internal class Cl1LifecycleEngine(
    private val adapter: Cl1CalendarAdapter,
    private val storage: Cl1Storage,
    private val domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun changeDestination(
        relation: Cl1RelationSnapshot,
        destinationRef: Cl1CalendarRef,
    ): Cl1OperationResult {
        if (
            relation.state != Cl1RelationState.ACTIVE ||
            relation.needsRevisionRefresh
        ) {
            return Cl1OperationResult.Rejected(null, "relationNotActive")
        }
        val sourceRef = relation.source?.ref
            ?: return Cl1OperationResult.Rejected(null, "sourceUnavailable")
        val mirrorRef = relation.mirror?.ref
            ?: return Cl1OperationResult.Rejected(null, "mirrorUnavailable")
        val source = adapter.readEvent(sourceRef)
            ?: return Cl1OperationResult.Rejected(null, "sourceUnavailable")
        val mirror = adapter.readEvent(mirrorRef)
            ?: return Cl1OperationResult.Rejected(null, "mirrorUnavailable")
        val freshRelation = Cl1Discovery.build(
            events = listOf(source, mirror),
            domainToAscii = domainToAscii
        ).relations.singleOrNull { it.key == relation.key }
            ?: return Cl1OperationResult.Rejected(null, "relationUnavailable")
        if (
            freshRelation.state != Cl1RelationState.ACTIVE ||
            freshRelation.needsRevisionRefresh
        ) {
            return Cl1OperationResult.Rejected(null, "relationNotActive")
        }
        val oldPayload = freshRelation.mirrorPayload
            ?: return Cl1OperationResult.Rejected(null, "mirrorPayloadUnavailable")
        val sourcePayload = freshRelation.sourcePayload
            ?: return Cl1OperationResult.Rejected(null, "sourcePayloadUnavailable")
        if (!source.calendar.supportsSourceRelations) {
            return Cl1OperationResult.Rejected(null, "sourceCalendarCapabilities")
        }
        val destination = adapter.listCalendars()
            .singleOrNull { it.ref == destinationRef }
            ?: return Cl1OperationResult.Rejected(null, "destinationUnavailable")
        if (!destination.supportsMirrorRelations) {
            return Cl1OperationResult.Rejected(null, "destinationCapabilities")
        }
        val destinationEmail = destination.canonicalAccountEmail
            ?: return Cl1OperationResult.Rejected(null, "destinationEmail")
        val newSecret = generateUnusedSecret(sourcePayload.records)
            ?: return Cl1OperationResult.Rejected(null, "slotGeneration")
        val journal = Cl1ChangeDestinationJournal.from(
            relation = freshRelation,
            destination = destination,
            destinationEmail = destinationEmail.value,
            newSecret = newSecret,
            oldPayload = oldPayload,
            source = source,
            mirror = mirror
        )
        val operation = newOperation(
            slotHex = relation.key.slot.toHex(),
            type = Cl1OperationTypes.CHANGE_DESTINATION,
            payload = JSON.encodeToString(
                Cl1ChangeDestinationJournal.serializer(),
                journal
            )
        )
        storage.putOperation(operation)
        return resumeChangeDestination(operation)
    }

    fun deleteSource(
        sourceRef: Cl1EventRef,
        discovery: Cl1DiscoverySnapshot,
    ): Cl1OperationResult {
        val source = adapter.readEvent(sourceRef)
            ?: return Cl1OperationResult.Rejected(null, "sourceUnavailable")
        val sourceBlock = source.sourceBlock()
            ?: return Cl1OperationResult.Rejected(null, "sourceBlockUnavailable")
        if (sourceBlock.payload.records.isEmpty()) {
            return Cl1OperationResult.Rejected(null, "sourceHasNoCopies")
        }
        val entries = sourceBlock.payload.records.map { record ->
            val matches = discovery.relations.filter {
                it.key.slot == record.slot && it.source?.ref == sourceRef
            }
            val relation = matches.singleOrNull()
                ?: return Cl1OperationResult.Rejected(null, "copyUnavailable")
            val mirror = relation.mirror
                ?: return Cl1OperationResult.Rejected(null, "copyUnavailable")
            val payload = relation.mirrorPayload
                ?: return Cl1OperationResult.Rejected(null, "copyUnavailable")
            if (
                relation.state == Cl1RelationState.RELATION_CONFLICT ||
                relation.state == Cl1RelationState.RECORD_CORRUPT ||
                relation.state == Cl1RelationState.INCOMPATIBLE ||
                Cl1Crypto.deriveSlot(payload.secret) != record.slot
            ) {
                return Cl1OperationResult.Rejected(null, "copyConflict")
            }
            Cl1DeleteMirrorJournal(
                slotHex = record.slot.toHex(),
                secretHex = payload.secret.toHex(),
                mirror = Cl1EventRefDto.from(mirror.ref)
            )
        }
        val journal = Cl1DeleteSourceJournal(
            source = Cl1EventRefDto.from(sourceRef),
            mirrors = entries
        )
        val operation = newOperation(
            slotHex = null,
            type = Cl1OperationTypes.DELETE_SOURCE,
            payload = JSON.encodeToString(Cl1DeleteSourceJournal.serializer(), journal)
        )
        storage.putOperation(operation)
        return resumeDeleteSource(operation)
    }

    fun resume(operation: Cl1PendingOperation): Cl1OperationResult {
        return when (operation.type) {
            Cl1OperationTypes.CHANGE_DESTINATION -> {
                resumeChangeDestination(operation)
            }

            Cl1OperationTypes.DELETE_SOURCE -> resumeDeleteSource(operation)
            else -> Cl1OperationResult.Rejected(
                operation.operationId,
                "unknownLifecycleOperation"
            )
        }
    }

    private fun resumeChangeDestination(
        initialOperation: Cl1PendingOperation,
    ): Cl1OperationResult {
        initialOperation.conflictResult()?.let { return it }
        var operation = initialOperation
        var journal = decodeChange(operation) ?: return corruptJournal(operation)
        val destination = destination(journal)
            ?: return pending(operation, "destinationUnavailable")
        val source = adapter.readEvent(journal.source.toDomain())
            ?: return pending(operation, "sourceUnavailable")
        val sourceBlock = source.sourceBlock()
            ?: return conflict(operation, "sourceBlockChanged")
        val newSecret = Cl1Bytes.fromHex(journal.newSecretHex)
        val destinationEmail = Cl1Email.canonicalize(
            journal.destinationEmail,
            domainToAscii
        )
        val newRecord = Cl1Crypto.encryptEmail(
            newSecret,
            destinationEmail
        ).toSourceRecord()
        val oldSlot = Cl1Bytes.fromHex(journal.oldSlotHex)
        var oldRecord = journal.oldRecord()
        val sourceState = sourceBlock.replacementState(
            oldSlot = oldSlot,
            newRecord = newRecord,
            expectedOldRecord = oldRecord
        )
        if (sourceState == ReplacementState.CONFLICT) {
            return conflict(operation, "sourceRecordsChanged")
        }

        val originalRef = Cl1EventRef(
            journal.mirrorEventId,
            journal.originalMirrorCalendarId
        )
        val destinationMirrorRef = Cl1EventRef(
            journal.mirrorEventId,
            journal.destinationCalendarId
        )
        val originalMirror = adapter.readEvent(originalRef)
        val destinationMirror = adapter.readEvent(destinationMirrorRef)
        if (
            originalMirror != null &&
            destinationMirror != null &&
            originalMirror.ref != destinationMirror.ref
        ) {
            return conflict(operation, "mirrorDuplicatedDuringMove")
        }
        var mirror = destinationMirror ?: originalMirror
            ?: return pending(operation, "mirrorUnavailable")
        val mirrorBlock = mirror.mirrorBlock()
            ?: return conflict(operation, "mirrorBlockChanged")
        val oldSecret = Cl1Bytes.fromHex(journal.oldSecretHex)
        val hasOldSecret = Cl1Crypto.constantTimeEquals(
            mirrorBlock.payload.secret,
            oldSecret
        )
        val hasNewSecret = Cl1Crypto.constantTimeEquals(
            mirrorBlock.payload.secret,
            newSecret
        )
        if (!hasOldSecret && !hasNewSecret) {
            return conflict(operation, "mirrorSecretChanged")
        }

        if (hasOldSecret) {
            if (sourceState != ReplacementState.PREPARED) {
                return conflict(operation, "sourceCommittedBeforeMirror")
            }
            val oldRevision = journal.oldRevisionHex?.let(Cl1Bytes::fromHex)
            if (oldRevision == null) {
                val active = Cl1Discovery.build(
                    events = listOf(source, mirror),
                    domainToAscii = domainToAscii
                ).relations.singleOrNull { it.key.slot == oldSlot }
                if (
                    active == null ||
                    active.state != Cl1RelationState.ACTIVE ||
                    active.needsRevisionRefresh
                ) {
                    return conflict(operation, "relationChangedBeforeMove")
                }
                journal = journal.withOldBaseline(active)
                oldRecord = journal.oldRecord()
                operation = checkpoint(operation, operation.phase, journal)
            } else if (!journal.matchesOldMirrorPayload(mirrorBlock.payload)) {
                return conflict(operation, "mirrorBlockChangedBeforeMove")
            }
            val actual = try {
                mirror.canonicalEvent()
            } catch (_: IllegalArgumentException) {
                return conflict(operation, "mirrorIncompatible")
            }
            val baselineRevision = journal.oldRevisionHex
                ?.let(Cl1Bytes::fromHex)
                ?: return corruptJournal(operation)
            val actualRevision = Cl1Revision.calculate(oldSecret, actual)
            val expectedRevision = try {
                Cl1Revision.calculate(
                    oldSecret,
                    Cl1Transform.apply(source.canonicalEvent(), mirrorBlock.payload)
                )
            } catch (_: IllegalArgumentException) {
                return conflict(operation, "sourceIncompatible")
            }
            if (
                !Cl1Crypto.constantTimeEquals(actualRevision, baselineRevision) ||
                !Cl1Crypto.constantTimeEquals(expectedRevision, baselineRevision)
            ) {
                return conflict(operation, "relationChangedBeforeMove")
            }
            val newPayload = journal.newMirrorPayload(
                secret = newSecret,
                revision = Cl1Revision.calculate(newSecret, actual)
            )
            val write = Cl1EventWrite(
                canonicalEvent = actual,
                description = Cl1Armor.compose(
                    mirrorBlock.userDescription,
                    newPayload
                )
            )
            operation = checkpoint(
                operation,
                Cl1ResolutionPhases.MIRROR_APPLYING,
                journal
            )
            val result = if (mirror.ref.calendarId == destination.ref.calendarId) {
                adapter.updateEvent(mirror, write)
            } else {
                adapter.moveEvent(mirror, destination, write)
            }
            when (result) {
                is Cl1MutationResult.Applied -> {
                    mirror = result.event?.ref?.let(adapter::readEvent)
                        ?: adapter.readEvent(destinationMirrorRef)
                        ?: return pending(operation, "mirrorMoveNotVerified")
                }

                Cl1MutationResult.PreconditionFailed -> {
                    return conflict(operation, "mirrorChangedConcurrently")
                }

                Cl1MutationResult.Missing -> {
                    return pending(operation, "mirrorUnavailable")
                }

                is Cl1MutationResult.Ineligible -> {
                    return pending(operation, "mirrorMoveIneligible:${result.reason}")
                }

                is Cl1MutationResult.Failed -> {
                    return pending(operation, "mirrorMove:${result.reason}")
                }
            }
        }

        val verifiedMirror = mirror.mirrorBlock()
            ?: return conflict(operation, "mirrorMoveNotVerified")
        val verifiedCanonical = try {
            mirror.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return conflict(operation, "mirrorIncompatible")
        }
        val verifiedRevision = Cl1Revision.calculate(newSecret, verifiedCanonical)
        if (
            mirror.ref.calendarId != destination.ref.calendarId ||
            !Cl1Crypto.constantTimeEquals(
                verifiedMirror.payload.secret,
                newSecret
            ) ||
            !Cl1Crypto.constantTimeEquals(
                verifiedMirror.payload.revision,
                verifiedRevision
            ) ||
            verifiedMirror.payload.titleOverride != journal.titleOverride() ||
            verifiedMirror.payload.startOffsetSeconds != journal.startOffsetSeconds ||
            verifiedMirror.payload.durationOverride != journal.durationOverride()
        ) {
            return conflict(operation, "mirrorMoveNotVerified")
        }
        val mirrorAccount = mirror.calendar.canonicalAccountEmail
            ?: return conflict(operation, "destinationEmailUnavailable")
        if (
            !Cl1Crypto.constantTimeEquals(
                mirrorAccount.value,
                journal.destinationEmail
            )
        ) {
            return conflict(operation, "destinationEmailMismatch")
        }
        operation = checkpoint(
            operation,
            Cl1ResolutionPhases.MIRROR_UPDATED,
            journal
        )

        if (sourceState == ReplacementState.COMMITTED) {
            return complete(
                operation = operation,
                slotHex = newRecord.slot.toHex(),
                confirmedOrphanSlots = listOf(journal.oldSlotHex)
            )
        }
        val replacement = sourceBlock.replace(
            oldSlot = oldSlot,
            newRecord = newRecord,
            expectedOldRecord = oldRecord
        )
        val sourceCanonical = try {
            source.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return conflict(operation, "sourceIncompatible")
        }
        val sourceWrite = Cl1EventWrite(
            canonicalEvent = sourceCanonical,
            description = Cl1Armor.compose(
                sourceBlock.userDescription,
                replacement
            )
        )
        when (val result = adapter.updateEvent(source, sourceWrite)) {
            is Cl1MutationResult.Applied -> {
                val verified = result.event?.ref?.let(adapter::readEvent)
                    ?: adapter.readEvent(source.ref)
                if (
                    verified?.sourceBlock()?.replacementState(
                        oldSlot,
                        newRecord,
                        journal.oldRecord()
                    ) ==
                    ReplacementState.COMMITTED
                ) {
                    return complete(
                        operation = operation,
                        slotHex = newRecord.slot.toHex(),
                        confirmedOrphanSlots = listOf(journal.oldSlotHex)
                    )
                }
                return pending(operation, "sourceReplacementNotVerified")
            }

            Cl1MutationResult.PreconditionFailed -> {
                return pending(operation, "sourceChangedConcurrently")
            }

            Cl1MutationResult.Missing -> return pending(operation, "sourceUnavailable")
            is Cl1MutationResult.Ineligible -> {
                return pending(operation, "sourceIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                return pending(operation, "sourceUpdate:${result.reason}")
            }
        }
    }

    private fun resumeDeleteSource(
        initialOperation: Cl1PendingOperation,
    ): Cl1OperationResult {
        initialOperation.conflictResult()?.let { return it }
        var operation = initialOperation
        var journal = decodeDelete(operation) ?: return corruptJournal(operation)

        journal.mirrors.forEachIndexed { index, entry ->
            if (entry.deleted) return@forEachIndexed
            val mirror = adapter.readEvent(entry.mirror.toDomain())
            if (mirror == null) {
                if (isCalendarAccessible(entry.mirror.calendarId)) {
                    journal = journal.markDeleted(index)
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.COPIES_DELETING,
                        journal
                    )
                    return@forEachIndexed
                }
                return pending(operation, "copyUnavailable:${entry.slotHex}")
            }
            val current = mirror
            if (!current.matchesMirrorSecret(entry.secretHex)) {
                return conflict(operation, "copyChanged:${entry.slotHex}")
            }
            journal = journal.copy(deletingSlotHex = entry.slotHex)
            operation = checkpoint(
                operation,
                Cl1ResolutionPhases.COPIES_DELETING,
                journal
            )
            when (val result = adapter.deleteEvent(current)) {
                is Cl1MutationResult.Applied,
                Cl1MutationResult.Missing,
                -> {
                    journal = journal.markDeleted(index)
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.COPIES_DELETING,
                        journal
                    )
                }

                Cl1MutationResult.PreconditionFailed -> {
                    return conflict(operation, "copyChanged:${entry.slotHex}")
                }

                is Cl1MutationResult.Ineligible -> {
                    journal = journal.copy(deletingSlotHex = null)
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.COPIES_DELETING,
                        journal
                    )
                    return pending(
                        operation,
                        "copyDeleteIneligible:${result.reason}"
                    )
                }

                is Cl1MutationResult.Failed -> {
                    journal = journal.copy(deletingSlotHex = null)
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.COPIES_DELETING,
                        journal
                    )
                    return pending(operation, "copyDelete:${result.reason}")
                }
            }
        }

        if (journal.mirrors.any { !it.deleted }) {
            return pending(operation, "copiesDeletionIncomplete")
        }
        val source = adapter.readEvent(journal.source.toDomain())
        if (source == null) {
            return if (isCalendarAccessible(journal.source.calendarId)) {
                complete(
                    operation = operation,
                    slotHex = null,
                    confirmedOrphanSlots = journal.mirrors.map { it.slotHex }
                )
            } else {
                pending(operation, "sourceUnavailable")
            }
        }
        val sourceBlock = source.sourceBlock()
            ?: return conflict(operation, "sourceBlockChanged")
        val expectedSlots = journal.mirrors.map { it.slotHex }.sorted()
        val actualSlots = sourceBlock.payload.records.map { it.slot.toHex() }.sorted()
        if (actualSlots != expectedSlots) {
            return conflict(operation, "sourceCopiesChanged")
        }
        operation = checkpoint(
            operation,
            Cl1ResolutionPhases.SOURCE_DELETING,
            journal
        )
        return when (val result = adapter.deleteEvent(source)) {
            is Cl1MutationResult.Applied,
            Cl1MutationResult.Missing,
            -> complete(
                operation = operation,
                slotHex = null,
                confirmedOrphanSlots = journal.mirrors.map { it.slotHex }
            )

            Cl1MutationResult.PreconditionFailed -> {
                conflict(operation, "sourceChangedConcurrently")
            }

            is Cl1MutationResult.Ineligible -> {
                pending(operation, "sourceDeleteIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(operation, "sourceDelete:${result.reason}")
            }
        }
    }

    private fun destination(
        journal: Cl1ChangeDestinationJournal,
    ): Cl1CalendarDescriptor? {
        val calendar = adapter.listCalendars().singleOrNull {
            it.ref.calendarId == journal.destinationCalendarId
        } ?: return null
        val email = calendar.canonicalAccountEmail ?: return null
        return if (
            calendar.supportsMirrorRelations &&
            Cl1Crypto.constantTimeEquals(email.value, journal.destinationEmail)
        ) {
            calendar
        } else {
            null
        }
    }

    private fun isCalendarAccessible(calendarId: Long): Boolean {
        return adapter.listCalendars().any { it.ref.calendarId == calendarId }
    }

    private fun Cl1EventSnapshot.sourceBlock(): SourceBlock? {
        val parsed = parsedDescription as? Cl1Description.Valid ?: return null
        val payload = parsed.payload as? Cl1Payload.Source ?: return null
        if (payload.hasDuplicateSlots) return null
        return SourceBlock(parsed.userDescription, payload)
    }

    private fun Cl1EventSnapshot.mirrorBlock(): MirrorBlock? {
        val parsed = parsedDescription as? Cl1Description.Valid ?: return null
        val payload = parsed.payload as? Cl1Payload.Mirror ?: return null
        return MirrorBlock(parsed.userDescription, payload)
    }

    private fun Cl1EventSnapshot.matchesMirrorSecret(secretHex: String): Boolean {
        val payload = mirrorBlock()?.payload ?: return false
        return Cl1Crypto.constantTimeEquals(
            payload.secret,
            Cl1Bytes.fromHex(secretHex)
        )
    }

    private fun SourceBlock.replacementState(
        oldSlot: Cl1Bytes,
        newRecord: Cl1SourceRecord,
        expectedOldRecord: Cl1SourceRecord?,
    ): ReplacementState {
        val oldRecords = payload.records.filter { it.slot == oldSlot }
        val newMatches = payload.records.filter { it.slot == newRecord.slot }
        return when {
            oldRecords.size == 1 &&
                (expectedOldRecord == null || oldRecords.single() == expectedOldRecord) &&
                newMatches.isEmpty() -> ReplacementState.PREPARED

            oldRecords.isEmpty() &&
                newMatches.size == 1 &&
                newMatches.single() == newRecord -> ReplacementState.COMMITTED

            else -> ReplacementState.CONFLICT
        }
    }

    private fun SourceBlock.replace(
        oldSlot: Cl1Bytes,
        newRecord: Cl1SourceRecord,
        expectedOldRecord: Cl1SourceRecord?,
    ): Cl1Payload.Source {
        check(
            replacementState(oldSlot, newRecord, expectedOldRecord) ==
                ReplacementState.PREPARED
        )
        return Cl1Payload.Source(
            (payload.records.filterNot { it.slot == oldSlot } + newRecord)
                .sortedBy { it.slot }
        )
    }

    private fun generateUnusedSecret(records: List<Cl1SourceRecord>): Cl1Bytes? {
        repeat(SECRET_GENERATION_RETRIES) {
            val secret = Cl1Crypto.generateSecret()
            val slot = Cl1Crypto.deriveSlot(secret)
            if (records.none { it.slot == slot }) return secret
        }
        return null
    }

    private fun decodeChange(
        operation: Cl1PendingOperation,
    ): Cl1ChangeDestinationJournal? {
        return decode {
            JSON.decodeFromString(
                Cl1ChangeDestinationJournal.serializer(),
                operation.payload
            ).also {
                require(
                    Cl1Bytes.fromHex(it.oldSlotHex).size == Cl1Limits.SLOT_BYTES
                )
                require(
                    Cl1Bytes.fromHex(it.oldSecretHex).size ==
                        Cl1Limits.SECRET_BYTES
                )
                require(
                    (it.oldEmailCiphertextHex == null) ==
                        (it.oldGcmTagHex == null)
                )
                require(
                    (it.oldRevisionHex == null) ==
                        (it.oldEmailCiphertextHex == null)
                )
                it.oldRecord()?.let { record ->
                    require(record.slot == Cl1Bytes.fromHex(it.oldSlotHex))
                }
                it.oldRevisionHex?.let { revision ->
                    require(
                        Cl1Bytes.fromHex(revision).size ==
                            Cl1Limits.REVISION_BYTES
                    )
                }
                require(
                    Cl1Bytes.fromHex(it.newSecretHex).size ==
                        Cl1Limits.SECRET_BYTES
                )
                require(
                    Cl1Crypto.deriveSlot(Cl1Bytes.fromHex(it.oldSecretHex)) ==
                        Cl1Bytes.fromHex(it.oldSlotHex)
                )
                val newSecret = Cl1Bytes.fromHex(it.newSecretHex)
                require(
                    Cl1Crypto.deriveSlot(newSecret) !=
                        Cl1Bytes.fromHex(it.oldSlotHex)
                )
                Cl1Email.canonicalize(it.destinationEmail, domainToAscii)
                Cl1Armor.compose(
                    "",
                    it.newMirrorPayload(
                        secret = newSecret,
                        revision = Cl1Bytes.copyOf(
                            ByteArray(Cl1Limits.REVISION_BYTES)
                        )
                    )
                )
            }
        }
    }

    private fun decodeDelete(
        operation: Cl1PendingOperation,
    ): Cl1DeleteSourceJournal? {
        return decode {
            JSON.decodeFromString(
                Cl1DeleteSourceJournal.serializer(),
                operation.payload
            ).also { journal ->
                require(journal.mirrors.isNotEmpty())
                require(
                    journal.mirrors.map { it.slotHex }.distinct().size ==
                        journal.mirrors.size
                )
                journal.mirrors.forEach {
                    val slot = Cl1Bytes.fromHex(it.slotHex)
                    val secret = Cl1Bytes.fromHex(it.secretHex)
                    require(slot.size == Cl1Limits.SLOT_BYTES)
                    require(secret.size == Cl1Limits.SECRET_BYTES)
                    require(Cl1Crypto.deriveSlot(secret) == slot)
                }
            }
        }
    }

    private inline fun <T> decode(block: () -> T): T? {
        return try {
            block()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun newOperation(
        slotHex: String?,
        type: String,
        payload: String,
    ): Cl1PendingOperation {
        val now = nowMillis()
        return Cl1PendingOperation(
            operationId = UUID.randomUUID().toString(),
            slotHex = slotHex,
            type = type,
            phase = Cl1ResolutionPhases.PREPARED,
            payload = payload,
            createdAtMillis = now,
            updatedAtMillis = now,
            attempts = 0,
            lastError = null
        )
    }

    private fun checkpoint(
        operation: Cl1PendingOperation,
        phase: String,
        journal: Cl1ChangeDestinationJournal,
    ): Cl1PendingOperation {
        return checkpoint(
            operation,
            phase,
            JSON.encodeToString(Cl1ChangeDestinationJournal.serializer(), journal)
        )
    }

    private fun checkpoint(
        operation: Cl1PendingOperation,
        phase: String,
        journal: Cl1DeleteSourceJournal,
    ): Cl1PendingOperation {
        return checkpoint(
            operation,
            phase,
            JSON.encodeToString(Cl1DeleteSourceJournal.serializer(), journal)
        )
    }

    private fun checkpoint(
        operation: Cl1PendingOperation,
        phase: String,
        payload: String,
    ): Cl1PendingOperation {
        return operation.copy(
            phase = phase,
            payload = payload,
            updatedAtMillis = nowMillis(),
            lastError = null
        ).also(storage::putOperation)
    }

    private fun pending(
        operation: Cl1PendingOperation,
        reason: String,
    ): Cl1OperationResult.Pending {
        operation.copy(
            updatedAtMillis = nowMillis(),
            attempts = operation.attempts + 1,
            lastError = reason
        ).also(storage::putOperation)
        return Cl1OperationResult.Pending(operation.operationId, reason)
    }

    private fun conflict(
        operation: Cl1PendingOperation,
        reason: String,
    ): Cl1OperationResult.Conflict {
        operation.copy(
            phase = Cl1ResolutionPhases.CONFLICT,
            updatedAtMillis = nowMillis(),
            attempts = operation.attempts + 1,
            lastError = reason
        ).also(storage::putOperation)
        return Cl1OperationResult.Conflict(operation.operationId, reason)
    }

    private fun corruptJournal(
        operation: Cl1PendingOperation,
    ): Cl1OperationResult.Conflict = conflict(operation, "journalCorrupt")

    private fun complete(
        operation: Cl1PendingOperation,
        slotHex: String?,
        confirmedOrphanSlots: List<String> = emptyList(),
    ): Cl1OperationResult.Completed {
        confirmedOrphanSlots.forEach(storage::markConfirmedOrphan)
        storage.removeOperation(operation.operationId)
        return Cl1OperationResult.Completed(operation.operationId, slotHex)
    }

    private fun Cl1PendingOperation.conflictResult(): Cl1OperationResult.Conflict? {
        return if (phase == Cl1ResolutionPhases.CONFLICT) {
            Cl1OperationResult.Conflict(
                operationId,
                lastError ?: "lifecycleConflict"
            )
        } else {
            null
        }
    }

    private fun Cl1DeleteSourceJournal.markDeleted(
        index: Int,
    ): Cl1DeleteSourceJournal {
        return copy(
            mirrors = mirrors.mapIndexed { current, entry ->
                if (current == index) entry.copy(deleted = true) else entry
            },
            deletingSlotHex = null
        )
    }

    private data class SourceBlock(
        val userDescription: String,
        val payload: Cl1Payload.Source,
    )

    private data class MirrorBlock(
        val userDescription: String,
        val payload: Cl1Payload.Mirror,
    )

    private enum class ReplacementState {
        PREPARED,
        COMMITTED,
        CONFLICT,
    }

    private companion object {
        const val SECRET_GENERATION_RETRIES = 8
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun Cl1ChangeDestinationJournal.Companion.from(
            relation: Cl1RelationSnapshot,
            destination: Cl1CalendarDescriptor,
            destinationEmail: String,
            newSecret: Cl1Bytes,
            oldPayload: Cl1Payload.Mirror,
            source: Cl1EventSnapshot,
            mirror: Cl1EventSnapshot,
        ): Cl1ChangeDestinationJournal {
            val oldRecord = relation.sourcePayload
                ?.records
                ?.getOrNull(requireNotNull(relation.sourceRecordIndex))
                ?: throw IllegalArgumentException("sourceRecord")
            require(oldRecord.slot == relation.key.slot)
            val title = when (val value = oldPayload.titleOverride) {
                Cl1TitleOverride.Inherited -> "inherited" to null
                is Cl1TitleOverride.Replacement -> "replacement" to value.value
                is Cl1TitleOverride.Template -> "template" to value.value
            }
            val duration = when (val value = oldPayload.durationOverride) {
                Cl1DurationOverride.Inherited -> "inherited" to null
                is Cl1DurationOverride.Fixed -> "fixed" to value.seconds.toString()
                is Cl1DurationOverride.Delta -> "delta" to value.seconds.toString()
            }
            return Cl1ChangeDestinationJournal(
                oldSlotHex = relation.key.slot.toHex(),
                oldSecretHex = oldPayload.secret.toHex(),
                oldEmailCiphertextHex = oldRecord.emailCiphertext.toHex(),
                oldGcmTagHex = oldRecord.gcmTag.toHex(),
                oldRevisionHex = oldPayload.revision.toHex(),
                newSecretHex = newSecret.toHex(),
                destinationCalendarId = destination.ref.calendarId,
                destinationEmail = destinationEmail,
                source = Cl1EventRefDto.from(source.ref),
                mirrorEventId = mirror.ref.eventId,
                originalMirrorCalendarId = mirror.ref.calendarId,
                titleMode = title.first,
                titleValue = title.second,
                startOffsetSeconds = oldPayload.startOffsetSeconds,
                durationMode = duration.first,
                durationValue = duration.second
            )
        }

        fun Cl1ChangeDestinationJournal.titleOverride(): Cl1TitleOverride {
            return when (titleMode) {
                "inherited" -> Cl1TitleOverride.Inherited
                "replacement" -> Cl1TitleOverride.Replacement(
                    requireNotNull(titleValue)
                )

                "template" -> Cl1TitleOverride.Template(requireNotNull(titleValue))
                else -> throw IllegalArgumentException("titleMode")
            }
        }

        fun Cl1ChangeDestinationJournal.durationOverride(): Cl1DurationOverride {
            return when (durationMode) {
                "inherited" -> Cl1DurationOverride.Inherited
                "fixed" -> Cl1DurationOverride.Fixed(
                    requireNotNull(durationValue).toULong()
                )

                "delta" -> Cl1DurationOverride.Delta(
                    requireNotNull(durationValue).toLong()
                )

                else -> throw IllegalArgumentException("durationMode")
            }
        }

        fun Cl1ChangeDestinationJournal.newMirrorPayload(
            secret: Cl1Bytes,
            revision: Cl1Bytes,
        ): Cl1Payload.Mirror {
            return Cl1Payload.Mirror(
                secret = secret,
                revision = revision,
                titleOverride = titleOverride(),
                startOffsetSeconds = startOffsetSeconds,
                durationOverride = durationOverride()
            )
        }

        fun Cl1ChangeDestinationJournal.oldRecord(): Cl1SourceRecord? {
            val ciphertext = oldEmailCiphertextHex ?: return null
            val tag = oldGcmTagHex ?: return null
            return Cl1SourceRecord(
                slot = Cl1Bytes.fromHex(oldSlotHex),
                emailCiphertext = Cl1Bytes.fromHex(ciphertext),
                gcmTag = Cl1Bytes.fromHex(tag)
            )
        }

        fun Cl1ChangeDestinationJournal.matchesOldMirrorPayload(
            payload: Cl1Payload.Mirror,
        ): Boolean {
            val revision = oldRevisionHex?.let(Cl1Bytes::fromHex) ?: return false
            return Cl1Crypto.constantTimeEquals(
                payload.secret,
                Cl1Bytes.fromHex(oldSecretHex)
            ) &&
                Cl1Crypto.constantTimeEquals(payload.revision, revision) &&
                payload.titleOverride == titleOverride() &&
                payload.startOffsetSeconds == startOffsetSeconds &&
                payload.durationOverride == durationOverride()
        }

        fun Cl1ChangeDestinationJournal.withOldBaseline(
            relation: Cl1RelationSnapshot,
        ): Cl1ChangeDestinationJournal {
            val payload = requireNotNull(relation.mirrorPayload)
            val record = requireNotNull(relation.sourcePayload)
                .records
                .getOrNull(requireNotNull(relation.sourceRecordIndex))
                ?: throw IllegalArgumentException("sourceRecord")
            require(record.slot.toHex() == oldSlotHex)
            require(
                Cl1Crypto.constantTimeEquals(
                    payload.secret,
                    Cl1Bytes.fromHex(oldSecretHex)
                )
            )
            return copy(
                oldEmailCiphertextHex = record.emailCiphertext.toHex(),
                oldGcmTagHex = record.gcmTag.toHex(),
                oldRevisionHex = payload.revision.toHex()
            )
        }
    }
}
