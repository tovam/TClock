@file:Suppress("LongMethod", "ReturnCount", "TooManyFunctions")

package org.fossify.clock.cl1.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1CanonicalEventCodec
import org.fossify.clock.cl1.Cl1Codec
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
import org.fossify.clock.cl1.provider.Cl1CreateResult
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.provider.Cl1EventWrite
import org.fossify.clock.cl1.provider.Cl1MutationResult
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.storage.Cl1Storage
import java.util.UUID

class Cl1OperationEngine(
    private val adapter: Cl1CalendarAdapter,
    private val storage: Cl1Storage,
    private val domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val resolutions = Cl1ResolutionEngine(
        adapter = adapter,
        storage = storage,
        domainToAscii = domainToAscii,
        nowMillis = nowMillis
    )
    private val lifecycle = Cl1LifecycleEngine(
        adapter = adapter,
        storage = storage,
        domainToAscii = domainToAscii,
        nowMillis = nowMillis
    )

    fun createRelation(
        sourceRef: Cl1EventRef,
        destinationRef: Cl1CalendarRef,
        overrides: Cl1MirrorOverrides = Cl1MirrorOverrides(),
    ): Cl1OperationResult {
        return prepareCreation(
            sourceRef = sourceRef,
            destinationRef = destinationRef,
            overrides = overrides,
            replacedSlot = null,
            operationType = Cl1OperationTypes.CREATE
        )
    }

    fun repairRelation(
        relation: Cl1RelationSnapshot,
        destinationRef: Cl1CalendarRef,
        overrides: Cl1MirrorOverrides = Cl1MirrorOverrides(),
    ): Cl1OperationResult {
        if (relation.state != Cl1RelationState.MISSING_OR_INACCESSIBLE) {
            return Cl1OperationResult.Rejected(null, "relationNotMissing")
        }
        val source = relation.source
            ?: return Cl1OperationResult.Rejected(null, "sourceMissingOrInaccessible")
        if (
            relation.sourcePayload?.records?.count {
                it.slot == relation.key.slot
            } != 1
        ) {
            return Cl1OperationResult.Rejected(null, "sourceRelationConflict")
        }
        return prepareCreation(
            sourceRef = source.ref,
            destinationRef = destinationRef,
            overrides = overrides,
            replacedSlot = relation.key.slot,
            operationType = Cl1OperationTypes.REPAIR
        )
    }

    private fun prepareCreation(
        sourceRef: Cl1EventRef,
        destinationRef: Cl1CalendarRef,
        overrides: Cl1MirrorOverrides,
        replacedSlot: Cl1Bytes?,
        operationType: String,
    ): Cl1OperationResult {
        val source = adapter.readEvent(sourceRef)
            ?: return Cl1OperationResult.Rejected(null, "sourceMissingOrInaccessible")
        val sourceView = source.sourceView()
            ?: return Cl1OperationResult.Rejected(null, source.sourceRejection())
        if (!source.calendar.supportsSourceRelations) {
            return Cl1OperationResult.Rejected(null, "sourceCalendarCapabilities")
        }
        if (
            replacedSlot == null &&
            sourceView.payload.records.size >= Cl1Limits.SOURCE_RECORDS
        ) {
            return Cl1OperationResult.Rejected(null, "sourceRecordLimit")
        }
        val sourceCanonical = try {
            source.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return Cl1OperationResult.Rejected(null, "sourceIncompatible")
        }
        val validationPayload = Cl1Payload.Mirror(
            secret = Cl1Bytes.copyOf(ByteArray(Cl1Limits.SECRET_BYTES)),
            revision = Cl1Bytes.copyOf(ByteArray(Cl1Limits.REVISION_BYTES)),
            titleOverride = overrides.title,
            startOffsetSeconds = overrides.startOffsetSeconds,
            durationOverride = overrides.duration
        )
        try {
            Cl1Codec.encode(validationPayload)
            Cl1Transform.apply(sourceCanonical, validationPayload)
        } catch (_: IllegalArgumentException) {
            return Cl1OperationResult.Rejected(null, "invalidOverrides")
        }

        val destination = adapter.listCalendars()
            .singleOrNull { it.ref == destinationRef }
            ?: return Cl1OperationResult.Rejected(null, "destinationUnavailable")
        if (!destination.supportsMirrorRelations) {
            return Cl1OperationResult.Rejected(null, "destinationCalendarCapabilities")
        }
        val destinationEmail = destination.canonicalAccountEmail
            ?: return Cl1OperationResult.Rejected(null, "destinationEmail")

        val secret = generateUnusedSecret(sourceView.payload.records)
            ?: return Cl1OperationResult.Rejected(null, "slotGeneration")
        val token = Cl1Crypto.generateCreateToken()
        val journal = Cl1CreateJournal.from(
            sourceRef = sourceRef,
            destination = destination,
            destinationEmail = destinationEmail.value,
            secret = secret,
            createToken = token,
            overrides = overrides,
            replacedSlot = replacedSlot
        ) ?: return Cl1OperationResult.Rejected(null, "invalidOverrides")

        val operation = newOperation(
            slotHex = Cl1Crypto.deriveSlot(secret).toHex(),
            type = operationType,
            phase = Cl1CreatePhases.PREPARED,
            payload = encodeCreate(journal)
        )
        storage.putOperation(operation)
        return resumeCreate(operation)
    }

    fun resumePending(): List<Cl1OperationResult> {
        return storage.listPendingOperations().map(::resumeOperation)
    }

    fun restoreFromSource(
        relation: Cl1RelationSnapshot,
    ): Cl1OperationResult = resolutions.restoreFromSource(relation)

    fun applyCopyToSource(
        relation: Cl1RelationSnapshot,
    ): Cl1OperationResult = resolutions.applyCopyToSource(relation)

    fun convertCopyToOverrides(
        relation: Cl1RelationSnapshot,
        conversion: Cl1OverrideConversion,
    ): Cl1OperationResult = resolutions.convertToOverrides(relation, conversion)

    fun unlink(
        relation: Cl1RelationSnapshot,
    ): Cl1OperationResult = resolutions.unlink(relation)

    fun changeDestination(
        relation: Cl1RelationSnapshot,
        destinationRef: Cl1CalendarRef,
    ): Cl1OperationResult = lifecycle.changeDestination(relation, destinationRef)

    fun deleteSource(
        sourceRef: Cl1EventRef,
        discovery: Cl1DiscoverySnapshot,
    ): Cl1OperationResult = lifecycle.deleteSource(sourceRef, discovery)

    fun reconcile(discovery: Cl1DiscoverySnapshot): List<Cl1OperationResult> {
        val pendingSlots = storage.listPendingOperations()
            .asSequence()
            .mapNotNull { it.slotHex }
            .toSet()
        return discovery.relations
            .asSequence()
            .filter {
                it.state == Cl1RelationState.SOURCE_MODIFIED ||
                    it.state == Cl1RelationState.ACTIVE && it.needsRevisionRefresh
            }
            .filter { it.key.slot.toHex() !in pendingSlots }
            .mapNotNull(::newSyncOperation)
            .map(::resumeSync)
            .toList()
    }

    private fun resumeOperation(operation: Cl1PendingOperation): Cl1OperationResult {
        return when (operation.type) {
            Cl1OperationTypes.CREATE,
            Cl1OperationTypes.REPAIR,
            -> resumeCreate(operation)
            Cl1OperationTypes.SYNC -> resumeSync(operation)
            Cl1OperationTypes.RESTORE,
            Cl1OperationTypes.APPLY_COPY,
            Cl1OperationTypes.CONVERT_OVERRIDES,
            Cl1OperationTypes.UNLINK,
            -> resolutions.resume(operation)
            Cl1OperationTypes.CHANGE_DESTINATION,
            Cl1OperationTypes.DELETE_SOURCE,
            -> lifecycle.resume(operation)
            else -> Cl1OperationResult.Rejected(
                operation.operationId,
                "unknownOperationType"
            )
        }
    }

    private fun resumeCreate(
        initialOperation: Cl1PendingOperation,
    ): Cl1OperationResult {
        if (initialOperation.phase == Cl1CreatePhases.CONFLICT) {
            return Cl1OperationResult.Conflict(
                initialOperation.operationId,
                initialOperation.lastError ?: "creationConflict"
            )
        }
        var operation = initialOperation
        var journal = try {
            decodeCreate(operation.payload)
        } catch (_: SerializationException) {
            return conflict(operation, Cl1CreatePhases.CONFLICT, "journalCorrupt")
        } catch (_: IllegalArgumentException) {
            return conflict(operation, Cl1CreatePhases.CONFLICT, "journalCorrupt")
        }

        repeat(MAX_CREATE_RETRIES) {
            val source = adapter.readEvent(journal.source.toDomain())
            val sourceView = source?.sourceView()
            if (source == null || sourceView == null) {
                return rollbackCreate(
                    operation,
                    journal,
                    source?.sourceRejection() ?: "sourceMissingOrInaccessible"
                )
            }
            if (!source.calendar.supportsSourceRelations) {
                return rollbackCreate(
                    operation,
                    journal,
                    "sourceCalendarCapabilities"
                )
            }

            val destination = findDestination(journal)
                ?: return pending(operation, "destinationUnavailable")
            val desired = try {
                desiredMirror(source, sourceView, journal)
            } catch (_: IllegalArgumentException) {
                return rollbackCreate(operation, journal, "sourceIncompatible")
            }
            val sourceRecordState = sourceView.commitState(
                desired.record,
                journal.replacedSlot()
            )
            if (sourceRecordState == SourceRecordState.CONFLICT) {
                return conflict(operation, Cl1CreatePhases.CONFLICT, "slotConflict")
            }

            var mirror = journal.mirror?.toDomain()?.let(adapter::readEvent)
                ?: adapter.findCreatedEvent(destination, journal.createTokenHex)
            if (mirror == null) {
                val slotMatches = findMirrorsBySlot(
                    destination = destination,
                    slot = desired.record.slot,
                    expected = desired.write.canonicalEvent
                )
                if (slotMatches.size > 1) {
                    return conflict(
                        operation,
                        Cl1CreatePhases.CONFLICT,
                        "mirrorCreate:slotAmbiguous"
                    )
                }
                mirror = slotMatches.singleOrNull()
            }
            if (mirror != null && journal.mirror?.toDomain() != mirror.ref) {
                journal = journal.copy(mirror = Cl1EventRefDto.from(mirror.ref))
                operation = checkpoint(
                    operation,
                    Cl1CreatePhases.MIRROR_CREATING,
                    encodeCreate(journal)
                )
            }
            if (mirror == null) {
                operation = checkpoint(
                    operation,
                    Cl1CreatePhases.MIRROR_CREATING,
                    encodeCreate(journal)
                )
                when (
                    val result = adapter.createEvent(
                        destination,
                        journal.createTokenHex,
                        desired.write
                    )
                ) {
                    is Cl1CreateResult.Created -> {
                        mirror = result.event
                        journal = journal.copy(
                            mirror = Cl1EventRefDto.from(result.event.ref),
                            appliedRevisionHex = desired.revision.toHex()
                        )
                        operation = checkpoint(
                            operation,
                            Cl1CreatePhases.MIRROR_CREATING,
                            encodeCreate(journal)
                        )
                    }

                    is Cl1CreateResult.Existing -> {
                        mirror = result.event
                        journal = journal.copy(
                            mirror = Cl1EventRefDto.from(result.event.ref)
                        )
                        operation = checkpoint(
                            operation,
                            Cl1CreatePhases.MIRROR_CREATING,
                            encodeCreate(journal)
                        )
                    }

                    is Cl1CreateResult.Conflict -> {
                        return conflict(
                            operation,
                            Cl1CreatePhases.CONFLICT,
                            "mirrorCreate:${result.reason}"
                        )
                    }

                    is Cl1CreateResult.Ineligible -> {
                        val created = result.event
                        if (created != null) {
                            journal = journal.copy(
                                mirror = Cl1EventRefDto.from(created.ref)
                            )
                            operation = checkpoint(
                                operation,
                                Cl1CreatePhases.MIRROR_CREATING,
                                encodeCreate(journal)
                            )
                            return rollbackCreate(
                                operation,
                                journal,
                                "mirrorIncompatible:${result.reason}"
                            )
                        }
                        return pending(operation, "mirrorIneligible:${result.reason}")
                    }

                    is Cl1CreateResult.Failed -> {
                        return pending(operation, "mirrorCreate:${result.reason}")
                    }
                }
            }

            val currentMirror = mirror
                ?: return pending(operation, "mirrorMissingOrInaccessible")
            val owned = currentMirror.ownedMirror(journal)
                ?: return conflict(
                    operation,
                    Cl1CreatePhases.CONFLICT,
                    "createTokenCollisionOrMirrorModified"
                )
            val actualRevision = try {
                Cl1Revision.calculate(
                    owned.payload.secret,
                    currentMirror.canonicalEvent()
                )
            } catch (_: IllegalArgumentException) {
                return rollbackCreate(operation, journal, "mirrorIncompatible")
            }
            val canonicalMatches = canonicalEquals(
                currentMirror.canonicalEvent(),
                desired.write.canonicalEvent
            )
            val payloadMatches = owned.payload == desired.payload

            if (!canonicalMatches || !payloadMatches) {
                val baseline = journal.appliedRevisionHex?.let(Cl1Bytes::fromHex)
                    ?: owned.payload.revision
                val safeToUpdate = canonicalMatches ||
                    Cl1Crypto.constantTimeEquals(actualRevision, baseline) &&
                    Cl1Crypto.constantTimeEquals(owned.payload.revision, baseline)
                if (!safeToUpdate) {
                    return conflict(
                        operation,
                        Cl1CreatePhases.CONFLICT,
                        "mirrorChangedDuringCreation"
                    )
                }
                when (val result = adapter.updateEvent(currentMirror, desired.write)) {
                    is Cl1MutationResult.Applied -> {
                        journal = journal.copy(
                            mirror = Cl1EventRefDto.from(
                                result.event?.ref ?: currentMirror.ref
                            ),
                            appliedRevisionHex = desired.revision.toHex()
                        )
                        operation = checkpoint(
                            operation,
                            Cl1CreatePhases.MIRROR_CREATING,
                            encodeCreate(journal)
                        )
                        return@repeat
                    }

                    Cl1MutationResult.PreconditionFailed -> return@repeat
                    Cl1MutationResult.Missing -> {
                        return pending(operation, "mirrorMissingOrInaccessible")
                    }

                    is Cl1MutationResult.Ineligible -> {
                        return pending(operation, "mirrorIneligible:${result.reason}")
                    }

                    is Cl1MutationResult.Failed -> {
                        return pending(operation, "mirrorUpdate:${result.reason}")
                    }
                }
            }

            if (journal.appliedRevisionHex != desired.revision.toHex()) {
                journal = journal.copy(appliedRevisionHex = desired.revision.toHex())
            }
            operation = checkpoint(
                operation,
                Cl1CreatePhases.MIRROR_VERIFIED,
                encodeCreate(journal)
            )

            if (sourceRecordState == SourceRecordState.EXACT) {
                return completeCreate(operation, journal)
            }
            val updatedSource = sourceView.withCommittedRecord(
                desired.record,
                journal.replacedSlot()
            )
            val sourceWrite = Cl1EventWrite(
                canonicalEvent = source.canonicalEvent(),
                description = Cl1Armor.compose(
                    sourceView.userDescription,
                    updatedSource
                )
            )
            when (val result = adapter.updateEvent(source, sourceWrite)) {
                is Cl1MutationResult.Applied -> {
                    val verified = result.event?.ref?.let(adapter::readEvent)
                        ?: adapter.readEvent(source.ref)
                    if (
                        verified?.sourceView()?.commitState(
                            desired.record,
                            journal.replacedSlot()
                        ) ==
                        SourceRecordState.EXACT
                    ) {
                        return completeCreate(operation, journal)
                    }
                    return pending(operation, "sourceCommitNotVerified")
                }

                Cl1MutationResult.PreconditionFailed -> return@repeat
                Cl1MutationResult.Missing -> {
                    return rollbackCreate(
                        operation,
                        journal,
                        "sourceMissingOrInaccessible"
                    )
                }

                is Cl1MutationResult.Ineligible -> {
                    return rollbackCreate(
                        operation,
                        journal,
                        "sourceIneligible:${result.reason}"
                    )
                }

                is Cl1MutationResult.Failed -> {
                    return pending(operation, "sourceCommit:${result.reason}")
                }
            }
        }
        return pending(operation, "concurrentSourceChanges")
    }

    private fun resumeSync(
        operation: Cl1PendingOperation,
    ): Cl1OperationResult {
        if (operation.phase == Cl1SyncPhases.CONFLICT) {
            return Cl1OperationResult.Conflict(
                operation.operationId,
                operation.lastError ?: "syncConflict"
            )
        }
        val journal = try {
            decodeSync(operation.payload)
        } catch (_: SerializationException) {
            return conflict(operation, Cl1SyncPhases.CONFLICT, "journalCorrupt")
        } catch (_: IllegalArgumentException) {
            return conflict(operation, Cl1SyncPhases.CONFLICT, "journalCorrupt")
        }
        val source = adapter.readEvent(journal.source.toDomain())
            ?: return pending(operation, "sourceMissingOrInaccessible")
        val mirror = adapter.readEvent(journal.mirror.toDomain())
            ?: return pending(operation, "mirrorMissingOrInaccessible")
        val relation = Cl1Discovery.build(
            events = listOf(source, mirror),
            domainToAscii = domainToAscii
        ).relations.singleOrNull { it.key.slot.toHex() == journal.slotHex }
            ?: return pending(operation, "relationUnresolved")

        if (
            relation.state == Cl1RelationState.ACTIVE &&
            !relation.needsRevisionRefresh
        ) {
            storage.removeOperation(operation.operationId)
            return Cl1OperationResult.Completed(
                operation.operationId,
                journal.slotHex
            )
        }
        if (
            relation.state != Cl1RelationState.SOURCE_MODIFIED &&
            relation.state != Cl1RelationState.ACTIVE
        ) {
            return conflict(
                operation,
                Cl1SyncPhases.CONFLICT,
                "relation:${relation.state.name}"
            )
        }
        val expected = relation.expectedMirror
            ?: return pending(operation, "expectedMirrorUnavailable")
        val revision = relation.expectedRevision
            ?: return pending(operation, "expectedRevisionUnavailable")
        val payload = relation.mirrorPayload
            ?.copy(revision = revision)
            ?: return pending(operation, "mirrorPayloadUnavailable")
        val write = Cl1EventWrite(
            canonicalEvent = expected,
            description = Cl1Armor.compose(expected.userDescription, payload)
        )
        val applying = checkpoint(
            operation,
            Cl1SyncPhases.APPLYING,
            operation.payload
        )
        return when (val result = adapter.updateEvent(mirror, write)) {
            is Cl1MutationResult.Applied -> {
                val verifiedMirror = result.event?.ref?.let(adapter::readEvent)
                    ?: adapter.readEvent(mirror.ref)
                if (verifiedMirror == null) {
                    pending(applying, "mirrorUpdateNotVerified")
                } else {
                    val verified = Cl1Discovery.build(
                        listOf(source, verifiedMirror),
                        domainToAscii = domainToAscii
                    ).relations.singleOrNull {
                        it.key.slot.toHex() == journal.slotHex
                    }
                    if (
                        verified?.state == Cl1RelationState.ACTIVE &&
                        !verified.needsRevisionRefresh
                    ) {
                        storage.removeOperation(operation.operationId)
                        Cl1OperationResult.Completed(
                            operation.operationId,
                            journal.slotHex
                        )
                    } else {
                        pending(applying, "mirrorUpdateNotVerified")
                    }
                }
            }

            Cl1MutationResult.PreconditionFailed -> {
                pending(applying, "mirrorChangedConcurrently")
            }

            Cl1MutationResult.Missing -> {
                pending(applying, "mirrorMissingOrInaccessible")
            }

            is Cl1MutationResult.Ineligible -> {
                pending(applying, "mirrorIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(applying, "mirrorUpdate:${result.reason}")
            }
        }
    }

    private fun newSyncOperation(
        relation: Cl1RelationSnapshot,
    ): Cl1PendingOperation? {
        val source = relation.source ?: return null
        val mirror = relation.mirror ?: return null
        val journal = Cl1SyncJournal(
            slotHex = relation.key.slot.toHex(),
            source = Cl1EventRefDto.from(source.ref),
            mirror = Cl1EventRefDto.from(mirror.ref)
        )
        val operation = newOperation(
            slotHex = journal.slotHex,
            type = Cl1OperationTypes.SYNC,
            phase = Cl1SyncPhases.PREPARED,
            payload = encodeSync(journal)
        )
        storage.putOperation(operation)
        return operation
    }

    private fun rollbackCreate(
        operation: Cl1PendingOperation,
        journal: Cl1CreateJournal,
        reason: String,
    ): Cl1OperationResult {
        val destination = findDestination(journal)
            ?: return pending(operation, "$reason:destinationUnavailable")
        val mirror = journal.mirror?.toDomain()?.let(adapter::readEvent)
            ?: adapter.findCreatedEvent(destination, journal.createTokenHex)
        if (mirror == null) {
            storage.removeOperation(operation.operationId)
            return Cl1OperationResult.Rejected(operation.operationId, reason)
        }
        if (mirror.ownedMirror(journal) == null) {
            return conflict(
                operation,
                Cl1CreatePhases.CONFLICT,
                "$reason:mirrorChanged"
            )
        }
        return when (val result = adapter.deleteEvent(mirror)) {
            is Cl1MutationResult.Applied,
            Cl1MutationResult.Missing,
            -> {
                storage.removeOperation(operation.operationId)
                Cl1OperationResult.Rejected(operation.operationId, reason)
            }

            Cl1MutationResult.PreconditionFailed -> {
                conflict(
                    operation,
                    Cl1CreatePhases.CONFLICT,
                    "$reason:rollbackConflict"
                )
            }

            is Cl1MutationResult.Ineligible -> {
                pending(operation, "$reason:rollbackIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(operation, "$reason:rollbackFailed:${result.reason}")
            }
        }
    }

    private fun completeCreate(
        operation: Cl1PendingOperation,
        journal: Cl1CreateJournal,
    ): Cl1OperationResult {
        checkpoint(
            operation,
            Cl1CreatePhases.SOURCE_COMMITTED,
            encodeCreate(journal)
        )
        journal.replacedSlotHex?.let(storage::markConfirmedOrphan)
        storage.removeOperation(operation.operationId)
        return Cl1OperationResult.Completed(
            operation.operationId,
            Cl1Crypto.deriveSlot(Cl1Bytes.fromHex(journal.secretHex)).toHex()
        )
    }

    private fun desiredMirror(
        source: Cl1EventSnapshot,
        sourceView: SourceView,
        journal: Cl1CreateJournal,
    ): DesiredMirror {
        val secret = Cl1Bytes.fromHex(journal.secretHex)
        val email = Cl1Email.canonicalize(journal.destinationEmail, domainToAscii)
        val record = Cl1Crypto.encryptEmail(secret, email).toSourceRecord()
        val preliminary = Cl1Payload.Mirror(
            secret = secret,
            revision = Cl1Bytes.copyOf(ByteArray(Cl1Limits.REVISION_BYTES)),
            titleOverride = journal.titleOverride(),
            startOffsetSeconds = journal.startOffsetSeconds,
            durationOverride = journal.durationOverride()
        )
        val expected = Cl1Transform.apply(source.canonicalEvent(), preliminary)
        val revision = Cl1Revision.calculate(secret, expected)
        val payload = preliminary.copy(revision = revision)
        val write = Cl1EventWrite(
            canonicalEvent = expected,
            description = Cl1Armor.compose(expected.userDescription, payload)
        )
        check(sourceView.recordState(record) != SourceRecordState.CONFLICT)
        return DesiredMirror(record, payload, revision, write)
    }

    private fun findDestination(
        journal: Cl1CreateJournal,
    ): Cl1CalendarDescriptor? {
        val destination = adapter.listCalendars()
            .singleOrNull { it.ref == journal.destinationRef() }
            ?: return null
        if (!destination.supportsMirrorRelations) {
            return null
        }
        val actualEmail = destination.canonicalAccountEmail ?: return null
        return if (
            Cl1Crypto.constantTimeEquals(actualEmail.value, journal.destinationEmail)
        ) {
            destination
        } else {
            null
        }
    }

    private fun findMirrorsBySlot(
        destination: Cl1CalendarDescriptor,
        slot: Cl1Bytes,
        expected: Cl1CanonicalEvent,
    ): List<Cl1EventSnapshot> {
        val begin = secondsToMillisSaturated(
            minOf(expected.startUnixSeconds, expected.endUnixSeconds)
        ).saturatedMinus(CREATE_DISCOVERY_RADIUS_MILLIS)
        val end = secondsToMillisSaturated(
            maxOf(expected.startUnixSeconds, expected.endUnixSeconds)
        ).saturatedPlus(CREATE_DISCOVERY_RADIUS_MILLIS)
        return adapter.listEvents(begin, end)
            .asSequence()
            .filter { it.ref.calendarId == destination.ref.calendarId }
            .filter { event ->
                val payload = event.validMirrorPayload() ?: return@filter false
                Cl1Crypto.deriveSlot(payload.secret) == slot
            }
            .toList()
    }

    private fun secondsToMillisSaturated(seconds: Long): Long {
        return when {
            seconds > Long.MAX_VALUE / MILLIS_PER_SECOND -> Long.MAX_VALUE
            seconds < Long.MIN_VALUE / MILLIS_PER_SECOND -> Long.MIN_VALUE
            else -> seconds * MILLIS_PER_SECOND
        }
    }

    private fun Long.saturatedMinus(value: Long): Long {
        return if (this < Long.MIN_VALUE + value) Long.MIN_VALUE else this - value
    }

    private fun Long.saturatedPlus(value: Long): Long {
        return if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
    }

    private fun Cl1EventSnapshot.sourceView(): SourceView? {
        return when (val parsed = parsedDescription) {
            is Cl1Description.None -> SourceView(
                userDescription = parsed.originalDescription,
                payload = Cl1Payload.Source(emptyList())
            )

            is Cl1Description.Valid -> {
                val payload = parsed.payload as? Cl1Payload.Source ?: return null
                if (payload.hasDuplicateSlots) null else {
                    SourceView(parsed.userDescription, payload)
                }
            }

            is Cl1Description.UnsupportedVersion,
            is Cl1Description.Corrupt,
            -> null
        }
    }

    private fun Cl1EventSnapshot.sourceRejection(): String {
        return when (val parsed = parsedDescription) {
            is Cl1Description.None -> "sourceIncompatible"
            is Cl1Description.Valid -> when {
                parsed.payload is Cl1Payload.Mirror -> "sourceIsMirror"
                (parsed.payload as Cl1Payload.Source).hasDuplicateSlots -> {
                    "sourceRelationConflict"
                }

                else -> "sourceIncompatible"
            }

            is Cl1Description.UnsupportedVersion -> "sourceUnsupportedVersion"
            is Cl1Description.Corrupt -> "sourceBlockCorrupt"
        }
    }

    private fun Cl1EventSnapshot.ownedMirror(
        journal: Cl1CreateJournal,
    ): OwnedMirror? {
        if (ref.calendarId != journal.destinationCalendarId) {
            return null
        }
        val payload = (
            parsedDescription as? Cl1Description.Valid
            )?.payload as? Cl1Payload.Mirror ?: return null
        val secret = Cl1Bytes.fromHex(journal.secretHex)
        if (!Cl1Crypto.constantTimeEquals(payload.secret, secret)) {
            return null
        }
        if (
            payload.titleOverride != journal.titleOverride() ||
            payload.startOffsetSeconds != journal.startOffsetSeconds ||
            payload.durationOverride != journal.durationOverride()
        ) {
            return null
        }
        val accountEmail = calendar.canonicalAccountEmail ?: return null
        if (
            !Cl1Crypto.constantTimeEquals(
                accountEmail.value,
                journal.destinationEmail
            )
        ) {
            return null
        }
        return OwnedMirror(payload)
    }

    private fun generateUnusedSecret(
        records: List<Cl1SourceRecord>,
    ): Cl1Bytes? {
        repeat(SECRET_GENERATION_RETRIES) {
            val secret = Cl1Crypto.generateSecret()
            val slot = Cl1Crypto.deriveSlot(secret)
            if (records.none { it.slot == slot }) {
                return secret
            }
        }
        return null
    }

    private fun newOperation(
        slotHex: String?,
        type: String,
        phase: String,
        payload: String,
    ): Cl1PendingOperation {
        val now = nowMillis()
        return Cl1PendingOperation(
            operationId = UUID.randomUUID().toString(),
            slotHex = slotHex,
            type = type,
            phase = phase,
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
        payload: String,
    ): Cl1PendingOperation {
        val updated = operation.copy(
            phase = phase,
            payload = payload,
            updatedAtMillis = nowMillis(),
            lastError = null
        )
        storage.putOperation(updated)
        return updated
    }

    private fun pending(
        operation: Cl1PendingOperation,
        reason: String,
    ): Cl1OperationResult.Pending {
        storage.putOperation(
            operation.copy(
                updatedAtMillis = nowMillis(),
                attempts = operation.attempts + 1,
                lastError = reason
            )
        )
        return Cl1OperationResult.Pending(operation.operationId, reason)
    }

    private fun conflict(
        operation: Cl1PendingOperation,
        phase: String,
        reason: String,
    ): Cl1OperationResult.Conflict {
        storage.putOperation(
            operation.copy(
                phase = phase,
                updatedAtMillis = nowMillis(),
                attempts = operation.attempts + 1,
                lastError = reason
            )
        )
        return Cl1OperationResult.Conflict(operation.operationId, reason)
    }

    private fun encodeCreate(value: Cl1CreateJournal): String {
        return JSON.encodeToString(Cl1CreateJournal.serializer(), value)
    }

    private fun decodeCreate(value: String): Cl1CreateJournal {
        return JSON.decodeFromString(Cl1CreateJournal.serializer(), value).also {
            require(Cl1Bytes.fromHex(it.secretHex).size == Cl1Limits.SECRET_BYTES)
            require(Cl1Bytes.fromHex(it.createTokenHex).size == CREATE_TOKEN_BYTES)
            Cl1Email.canonicalize(it.destinationEmail, domainToAscii)
            it.titleOverride()
            it.durationOverride()
            it.replacedSlot()?.let { slot ->
                require(slot.size == Cl1Limits.SLOT_BYTES)
            }
            val offset = it.startOffsetSeconds
            require(
                offset == null ||
                    offset in -Cl1Limits.OFFSET_SECONDS..Cl1Limits.OFFSET_SECONDS
            )
        }
    }

    private fun encodeSync(value: Cl1SyncJournal): String {
        return JSON.encodeToString(Cl1SyncJournal.serializer(), value)
    }

    private fun decodeSync(value: String): Cl1SyncJournal {
        return JSON.decodeFromString(Cl1SyncJournal.serializer(), value).also {
            require(Cl1Bytes.fromHex(it.slotHex).size == Cl1Limits.SLOT_BYTES)
        }
    }

    private data class SourceView(
        val userDescription: String,
        val payload: Cl1Payload.Source,
    ) {
        fun recordState(record: Cl1SourceRecord): SourceRecordState {
            val existing = payload.records.filter { it.slot == record.slot }
            return when {
                existing.isEmpty() -> SourceRecordState.ABSENT
                existing.size == 1 && existing.single() == record -> {
                    SourceRecordState.EXACT
                }

                else -> SourceRecordState.CONFLICT
            }
        }

        fun commitState(
            record: Cl1SourceRecord,
            replacedSlot: Cl1Bytes?,
        ): SourceRecordState {
            if (replacedSlot == null) {
                return recordState(record)
            }
            val oldRecords = payload.records.filter { it.slot == replacedSlot }
            val newState = recordState(record)
            return when {
                oldRecords.size == 1 && newState == SourceRecordState.ABSENT -> {
                    SourceRecordState.ABSENT
                }

                oldRecords.isEmpty() && newState == SourceRecordState.EXACT -> {
                    SourceRecordState.EXACT
                }

                else -> SourceRecordState.CONFLICT
            }
        }

        fun withCommittedRecord(
            record: Cl1SourceRecord,
            replacedSlot: Cl1Bytes?,
        ): Cl1Payload.Source {
            check(commitState(record, replacedSlot) == SourceRecordState.ABSENT)
            val retained = if (replacedSlot == null) {
                payload.records
            } else {
                payload.records.filterNot { it.slot == replacedSlot }
            }
            return Cl1Payload.Source((retained + record).sortedBy { it.slot })
        }
    }

    private enum class SourceRecordState {
        ABSENT,
        EXACT,
        CONFLICT,
    }

    private data class DesiredMirror(
        val record: Cl1SourceRecord,
        val payload: Cl1Payload.Mirror,
        val revision: Cl1Bytes,
        val write: Cl1EventWrite,
    )

    private data class OwnedMirror(
        val payload: Cl1Payload.Mirror,
    )

    private companion object {
        const val MAX_CREATE_RETRIES = 8
        const val SECRET_GENERATION_RETRIES = 8
        const val CREATE_TOKEN_BYTES = 16
        const val MILLIS_PER_SECOND = 1_000L
        const val CREATE_DISCOVERY_RADIUS_MILLIS =
            30L * 24L * 60L * 60L * MILLIS_PER_SECOND
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun canonicalEquals(
            left: Cl1CanonicalEvent,
            right: Cl1CanonicalEvent,
        ): Boolean {
            return Cl1CanonicalEventCodec.encode(left).contentEquals(
                Cl1CanonicalEventCodec.encode(right)
            )
        }

        fun Cl1CreateJournal.titleOverride(): Cl1TitleOverride {
            return when (titleMode) {
                "inherited" -> Cl1TitleOverride.Inherited
                "replacement" -> Cl1TitleOverride.Replacement(
                    requireNotNull(titleValue)
                )

                "template" -> Cl1TitleOverride.Template(requireNotNull(titleValue))
                else -> throw IllegalArgumentException("titleMode")
            }
        }

        fun Cl1CreateJournal.durationOverride(): Cl1DurationOverride {
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

        fun Cl1CreateJournal.replacedSlot(): Cl1Bytes? {
            return replacedSlotHex?.let(Cl1Bytes::fromHex)
        }

        fun Cl1CreateJournal.Companion.from(
            sourceRef: Cl1EventRef,
            destination: Cl1CalendarDescriptor,
            destinationEmail: String,
            secret: Cl1Bytes,
            createToken: Cl1Bytes,
            overrides: Cl1MirrorOverrides,
            replacedSlot: Cl1Bytes?,
        ): Cl1CreateJournal? {
            val title = when (val value = overrides.title) {
                Cl1TitleOverride.Inherited -> "inherited" to null
                is Cl1TitleOverride.Replacement -> "replacement" to value.value
                is Cl1TitleOverride.Template -> "template" to value.value
            }
            val duration = when (val value = overrides.duration) {
                Cl1DurationOverride.Inherited -> "inherited" to null
                is Cl1DurationOverride.Fixed -> "fixed" to value.seconds.toString()
                is Cl1DurationOverride.Delta -> "delta" to value.seconds.toString()
            }
            val offset = overrides.startOffsetSeconds
            if (
                offset != null &&
                (offset < -Cl1Limits.OFFSET_SECONDS ||
                    offset > Cl1Limits.OFFSET_SECONDS)
            ) {
                return null
            }
            return Cl1CreateJournal(
                source = Cl1EventRefDto.from(sourceRef),
                destinationCalendarId = destination.ref.calendarId,
                destinationEmail = destinationEmail,
                secretHex = secret.toHex(),
                createTokenHex = createToken.toHex(),
                titleMode = title.first,
                titleValue = title.second,
                startOffsetSeconds = offset,
                durationMode = duration.first,
                durationValue = duration.second,
                replacedSlotHex = replacedSlot?.toHex()
            )
        }
    }
}
