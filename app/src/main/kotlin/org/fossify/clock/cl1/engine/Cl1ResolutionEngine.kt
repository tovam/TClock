@file:Suppress("LongMethod", "ReturnCount", "TooManyFunctions")

package org.fossify.clock.cl1.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1CanonicalEvent
import org.fossify.clock.cl1.Cl1CanonicalEventCodec
import org.fossify.clock.cl1.Cl1Crypto
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DomainToAscii
import org.fossify.clock.cl1.Cl1DurationOverride
import org.fossify.clock.cl1.Cl1JdkDomainToAscii
import org.fossify.clock.cl1.Cl1Limits
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1Revision
import org.fossify.clock.cl1.Cl1SourceRecord
import org.fossify.clock.cl1.Cl1TitleOverride
import org.fossify.clock.cl1.Cl1Transform
import org.fossify.clock.cl1.provider.Cl1CalendarAdapter
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.provider.Cl1EventWrite
import org.fossify.clock.cl1.provider.Cl1MutationResult
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.storage.Cl1Storage
import java.util.UUID

internal class Cl1ResolutionEngine(
    private val adapter: Cl1CalendarAdapter,
    private val storage: Cl1Storage,
    private val domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restoreFromSource(relation: Cl1RelationSnapshot): Cl1OperationResult {
        val pair = relation.copyModifiedPair()
            ?: return Cl1OperationResult.Rejected(null, "relationNotCopyModified")
        val operation = newOperation(
            slotHex = pair.slotHex,
            type = Cl1OperationTypes.RESTORE,
            payload = JSON.encodeToString(Cl1PairJournal.serializer(), pair)
        )
        storage.putOperation(operation)
        return resumeRestore(operation)
    }

    fun applyCopyToSource(relation: Cl1RelationSnapshot): Cl1OperationResult {
        val pair = relation.copyModifiedPair()
            ?: return Cl1OperationResult.Rejected(null, "relationNotCopyModified")
        val mirror = relation.mirror
            ?: return Cl1OperationResult.Rejected(null, "mirrorUnavailable")
        val target = try {
            mirror.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return Cl1OperationResult.Rejected(null, "mirrorIncompatible")
        }
        val journal = Cl1ApplyCopyJournal(
            pair = pair,
            target = Cl1CanonicalEventDto.from(target)
        )
        val operation = newOperation(
            slotHex = pair.slotHex,
            type = Cl1OperationTypes.APPLY_COPY,
            payload = JSON.encodeToString(Cl1ApplyCopyJournal.serializer(), journal)
        )
        storage.putOperation(operation)
        return resumeApplyCopy(operation)
    }

    fun convertToOverrides(
        relation: Cl1RelationSnapshot,
        conversion: Cl1OverrideConversion,
    ): Cl1OperationResult {
        val pair = relation.copyModifiedPair()
            ?: return Cl1OperationResult.Rejected(null, "relationNotCopyModified")
        val source = relation.source
            ?: return Cl1OperationResult.Rejected(null, "sourceUnavailable")
        val mirror = relation.mirror
            ?: return Cl1OperationResult.Rejected(null, "mirrorUnavailable")
        val mirrorPayload = relation.mirrorPayload
            ?: return Cl1OperationResult.Rejected(null, "mirrorPayloadUnavailable")
        val converted = try {
            buildConversion(
                source = source.canonicalEvent(),
                mirror = mirror.canonicalEvent(),
                mirrorPayload = mirrorPayload,
                conversion = conversion
            )
        } catch (exception: Cl1ResolutionException) {
            return Cl1OperationResult.Rejected(null, exception.reason)
        } catch (_: IllegalArgumentException) {
            return Cl1OperationResult.Rejected(null, "conversionIncompatible")
        }
        val journal = Cl1ConvertJournal.from(pair, converted)
        val operation = newOperation(
            slotHex = pair.slotHex,
            type = Cl1OperationTypes.CONVERT_OVERRIDES,
            payload = JSON.encodeToString(Cl1ConvertJournal.serializer(), journal)
        )
        storage.putOperation(operation)
        return resumeConvert(operation)
    }

    fun unlink(relation: Cl1RelationSnapshot): Cl1OperationResult {
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
        val payload = freshRelation.mirrorPayload
            ?: return Cl1OperationResult.Rejected(null, "mirrorPayloadUnavailable")
        if (
            freshRelation.state == Cl1RelationState.RELATION_CONFLICT ||
            freshRelation.state == Cl1RelationState.RECORD_CORRUPT ||
            freshRelation.state == Cl1RelationState.INCOMPATIBLE
        ) {
            return Cl1OperationResult.Rejected(null, "relationNotSafeToUnlink")
        }
        val sourceRecord = freshRelation.sourcePayload
            ?.records
            ?.getOrNull(
                freshRelation.sourceRecordIndex
                    ?: return Cl1OperationResult.Rejected(
                        null,
                        "sourceRecordUnavailable"
                    )
            )
            ?: return Cl1OperationResult.Rejected(null, "sourceRecordUnavailable")
        if (sourceRecord.slot != freshRelation.key.slot) {
            return Cl1OperationResult.Rejected(null, "sourceRecordUnavailable")
        }
        val journal = Cl1UnlinkJournal(
            slotHex = freshRelation.key.slot.toHex(),
            secretHex = payload.secret.toHex(),
            source = Cl1EventRefDto.from(source.ref),
            mirror = Cl1EventRefDto.from(mirror.ref),
            sourceEmailCiphertextHex = sourceRecord.emailCiphertext.toHex(),
            sourceGcmTagHex = sourceRecord.gcmTag.toHex(),
            sourceDescription = source.description,
            mirrorDescription = mirror.description
        )
        val operation = newOperation(
            slotHex = journal.slotHex,
            type = Cl1OperationTypes.UNLINK,
            payload = JSON.encodeToString(Cl1UnlinkJournal.serializer(), journal)
        )
        storage.putOperation(operation)
        return resumeUnlink(operation)
    }

    fun resume(operation: Cl1PendingOperation): Cl1OperationResult {
        return when (operation.type) {
            Cl1OperationTypes.RESTORE -> resumeRestore(operation)
            Cl1OperationTypes.APPLY_COPY -> resumeApplyCopy(operation)
            Cl1OperationTypes.CONVERT_OVERRIDES -> resumeConvert(operation)
            Cl1OperationTypes.UNLINK -> resumeUnlink(operation)
            else -> Cl1OperationResult.Rejected(
                operation.operationId,
                "unknownResolutionOperation"
            )
        }
    }

    private fun resumeRestore(
        operation: Cl1PendingOperation,
    ): Cl1OperationResult {
        operation.conflictResult()?.let { return it }
        val pair = decodePair(operation) ?: return corruptJournal(operation)
        val relation = freshRelation(pair)
            ?: return pending(operation, "relationUnresolved")
        if (relation.isFullyActive()) {
            return complete(operation, pair.slotHex)
        }
        if (!relation.matchesCopyModifiedBaseline(pair)) {
            return conflict(operation, "relationChangedBeforeRestore")
        }
        val expected = relation.expectedMirror
            ?: return pending(operation, "expectedMirrorUnavailable")
        val revision = relation.expectedRevision
            ?: return pending(operation, "expectedRevisionUnavailable")
        val mirror = relation.mirror
            ?: return pending(operation, "mirrorUnavailable")
        val payload = relation.mirrorPayload
            ?.copy(revision = revision)
            ?: return pending(operation, "mirrorPayloadUnavailable")
        val applying = checkpoint(operation, Cl1ResolutionPhases.APPLYING)
        val write = Cl1EventWrite(
            canonicalEvent = expected,
            description = Cl1Armor.compose(expected.userDescription, payload)
        )
        return when (val result = adapter.updateEvent(mirror, write)) {
            is Cl1MutationResult.Applied -> {
                if (freshRelation(pair)?.isFullyActive() == true) {
                    complete(applying, pair.slotHex)
                } else {
                    pending(applying, "restoreNotVerified")
                }
            }

            Cl1MutationResult.PreconditionFailed -> {
                conflict(applying, "mirrorChangedConcurrently")
            }

            Cl1MutationResult.Missing -> pending(applying, "mirrorUnavailable")
            is Cl1MutationResult.Ineligible -> {
                pending(applying, "mirrorIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(applying, "mirrorUpdate:${result.reason}")
            }
        }
    }

    private fun resumeConvert(
        operation: Cl1PendingOperation,
    ): Cl1OperationResult {
        operation.conflictResult()?.let { return it }
        val journal = decodeConvert(operation) ?: return corruptJournal(operation)
        val relation = freshRelation(journal.pair)
            ?: return pending(operation, "relationUnresolved")
        val source = relation.source
            ?: return pending(operation, "sourceUnavailable")
        val mirror = relation.mirror
            ?: return pending(operation, "mirrorUnavailable")
        val currentPayload = relation.mirrorPayload
            ?: return pending(operation, "mirrorPayloadUnavailable")
        val desiredPayload = try {
            journal.toPayload(currentPayload.secret)
        } catch (_: IllegalArgumentException) {
            return corruptJournal(operation)
        }

        if (
            relation.isFullyActive() &&
            currentPayload == desiredPayload
        ) {
            return complete(operation, journal.pair.slotHex)
        }
        if (!relation.matchesCopyModifiedBaseline(journal.pair)) {
            return conflict(operation, "relationChangedBeforeConversion")
        }
        val actual = try {
            mirror.canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return conflict(operation, "mirrorIncompatible")
        }
        val transformed = try {
            Cl1Transform.apply(source.canonicalEvent(), desiredPayload)
        } catch (_: IllegalArgumentException) {
            return conflict(operation, "conversionNoLongerMatches")
        }
        if (!canonicalEquals(transformed, actual)) {
            return conflict(operation, "conversionNoLongerMatches")
        }
        val applying = checkpoint(operation, Cl1ResolutionPhases.APPLYING)
        val write = Cl1EventWrite(
            canonicalEvent = actual,
            description = Cl1Armor.compose(actual.userDescription, desiredPayload)
        )
        return when (val result = adapter.updateEvent(mirror, write)) {
            is Cl1MutationResult.Applied -> {
                val verified = freshRelation(journal.pair)
                if (
                    verified?.isFullyActive() == true &&
                    verified.mirrorPayload == desiredPayload
                ) {
                    complete(applying, journal.pair.slotHex)
                } else {
                    pending(applying, "conversionNotVerified")
                }
            }

            Cl1MutationResult.PreconditionFailed -> {
                conflict(applying, "mirrorChangedConcurrently")
            }

            Cl1MutationResult.Missing -> pending(applying, "mirrorUnavailable")
            is Cl1MutationResult.Ineligible -> {
                pending(applying, "mirrorIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(applying, "mirrorUpdate:${result.reason}")
            }
        }
    }

    private fun resumeApplyCopy(
        initialOperation: Cl1PendingOperation,
    ): Cl1OperationResult {
        initialOperation.conflictResult()?.let { return it }
        var operation = initialOperation
        val journal = decodeApplyCopy(operation) ?: return corruptJournal(operation)
        val target = try {
            journal.target.toDomain()
        } catch (_: IllegalArgumentException) {
            return corruptJournal(operation)
        }
        var source = adapter.readEvent(journal.pair.source.toDomain())
            ?: return pending(operation, "sourceUnavailable")
        val sourceAlreadyApplied = source.matchesTargetSource(
            target,
            journal.pair.slotHex
        )
        val canApplySource =
            operation.phase == Cl1ResolutionPhases.PREPARED ||
                operation.phase == Cl1ResolutionPhases.APPLYING
        if (canApplySource && !sourceAlreadyApplied) {
            val relation = freshRelation(journal.pair)
                ?: return pending(operation, "relationUnresolved")
            if (!relation.matchesCopyModifiedBaseline(journal.pair)) {
                return conflict(operation, "relationChangedBeforeApply")
            }
            val mirror = relation.mirror
                ?: return pending(operation, "mirrorUnavailable")
            if (!canonicalEquals(mirror.canonicalEvent(), target)) {
                return conflict(operation, "mirrorChangedBeforeApply")
            }
            val sourceBlock = source.sourceBlock()
                ?: return conflict(operation, "sourceBlockChanged")
            val sourceWrite = Cl1EventWrite(
                canonicalEvent = target,
                description = Cl1Armor.compose(
                    target.userDescription,
                    sourceBlock.payload
                )
            )
            operation = checkpoint(operation, Cl1ResolutionPhases.APPLYING)
            when (val result = adapter.updateEvent(source, sourceWrite)) {
                is Cl1MutationResult.Applied -> {
                    source = result.event?.ref?.let(adapter::readEvent)
                        ?: adapter.readEvent(source.ref)
                        ?: return pending(operation, "sourceApplyNotVerified")
                    if (!source.matchesTargetSource(target, journal.pair.slotHex)) {
                        return pending(operation, "sourceApplyNotVerified")
                    }
                }

                Cl1MutationResult.PreconditionFailed -> {
                    return conflict(operation, "sourceChangedConcurrently")
                }

                Cl1MutationResult.Missing -> return pending(operation, "sourceUnavailable")
                is Cl1MutationResult.Ineligible -> {
                    return pending(operation, "sourceIneligible:${result.reason}")
                }

                is Cl1MutationResult.Failed -> {
                    return pending(operation, "sourceUpdate:${result.reason}")
                }
            }
        } else if (!sourceAlreadyApplied) {
            return conflict(operation, "sourceChangedAfterApply")
        }

        operation = checkpoint(operation, Cl1ResolutionPhases.SOURCE_APPLIED)
        val mirror = adapter.readEvent(journal.pair.mirror.toDomain())
            ?: return pending(operation, "mirrorUnavailable")
        val mirrorBlock = mirror.mirrorBlock()
            ?: return conflict(operation, "mirrorBlockChanged")
        if (!canonicalEquals(mirror.canonicalEvent(), target)) {
            return conflict(operation, "mirrorChangedAfterSourceApply")
        }
        val clearedPayload = Cl1Payload.Mirror(
            secret = mirrorBlock.payload.secret,
            revision = Cl1Revision.calculate(mirrorBlock.payload.secret, target),
            titleOverride = Cl1TitleOverride.Inherited,
            startOffsetSeconds = null,
            durationOverride = Cl1DurationOverride.Inherited
        )
        if (mirrorBlock.payload == clearedPayload) {
            val relation = freshRelation(journal.pair)
            return if (relation?.isFullyActive() == true) {
                complete(operation, journal.pair.slotHex)
            } else {
                pending(operation, "applyCopyNotVerified")
            }
        }
        operation = checkpoint(operation, Cl1ResolutionPhases.MIRROR_APPLYING)
        val mirrorWrite = Cl1EventWrite(
            canonicalEvent = target,
            description = Cl1Armor.compose(target.userDescription, clearedPayload)
        )
        return when (val result = adapter.updateEvent(mirror, mirrorWrite)) {
            is Cl1MutationResult.Applied -> {
                if (freshRelation(journal.pair)?.isFullyActive() == true) {
                    complete(operation, journal.pair.slotHex)
                } else {
                    pending(operation, "applyCopyNotVerified")
                }
            }

            Cl1MutationResult.PreconditionFailed -> {
                conflict(operation, "mirrorChangedConcurrently")
            }

            Cl1MutationResult.Missing -> pending(operation, "mirrorUnavailable")
            is Cl1MutationResult.Ineligible -> {
                pending(operation, "mirrorIneligible:${result.reason}")
            }

            is Cl1MutationResult.Failed -> {
                pending(operation, "mirrorUpdate:${result.reason}")
            }
        }
    }

    private fun resumeUnlink(
        initialOperation: Cl1PendingOperation,
    ): Cl1OperationResult {
        initialOperation.conflictResult()?.let { return it }
        var operation = initialOperation
        val journal = decodeUnlink(operation) ?: return corruptJournal(operation)
        val slot = Cl1Bytes.fromHex(journal.slotHex)
        val secret = Cl1Bytes.fromHex(journal.secretHex)
        if (Cl1Crypto.deriveSlot(secret) != slot) {
            return corruptJournal(operation)
        }
        val source = adapter.readEvent(journal.source.toDomain())
            ?: return pending(operation, "sourceUnavailable")
        if (operation.phase != Cl1ResolutionPhases.SOURCE_DETACHED) {
            when (val parsed = source.parsedDescription) {
                is Cl1Description.None -> {
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.SOURCE_DETACHED
                    )
                }

                is Cl1Description.Valid -> {
                    val payload = parsed.payload as? Cl1Payload.Source
                        ?: return conflict(operation, "sourceRoleChanged")
                    val matching = payload.records.count { it.slot == slot }
                    if (matching > 1) {
                        return conflict(operation, "sourceRelationConflict")
                    }
                    if (matching == 1) {
                        val expectedRecord = journal.sourceRecord()
                        val currentRecord = payload.records.single {
                            it.slot == slot
                        }
                        if (
                            expectedRecord != null &&
                            currentRecord != expectedRecord
                        ) {
                            return conflict(operation, "sourceRecordChanged")
                        }
                        if (
                            journal.sourceDescription != null &&
                            source.description != journal.sourceDescription
                        ) {
                            return conflict(operation, "sourceBlockChanged")
                        }
                        val remaining = payload.records.filterNot { it.slot == slot }
                        val description = if (remaining.isEmpty()) {
                            parsed.userDescription
                        } else {
                            Cl1Armor.compose(
                                parsed.userDescription,
                                Cl1Payload.Source(remaining)
                            )
                        }
                        operation = checkpoint(
                            operation,
                            Cl1ResolutionPhases.APPLYING
                        )
                        val sourceCanonical = try {
                            source.canonicalEvent()
                        } catch (_: IllegalArgumentException) {
                            return conflict(operation, "sourceIncompatible")
                        }
                        when (
                            val result = adapter.updateEvent(
                                source,
                                Cl1EventWrite(sourceCanonical, description)
                            )
                        ) {
                            is Cl1MutationResult.Applied -> {
                                val verified = result.event?.ref?.let(adapter::readEvent)
                                    ?: adapter.readEvent(source.ref)
                                if (verified?.containsSourceSlot(slot) == true) {
                                    return pending(operation, "sourceDetachNotVerified")
                                }
                            }

                            Cl1MutationResult.PreconditionFailed -> {
                                return conflict(operation, "sourceChangedConcurrently")
                            }

                            Cl1MutationResult.Missing -> {
                                return pending(operation, "sourceUnavailable")
                            }

                            is Cl1MutationResult.Ineligible -> {
                                return pending(
                                    operation,
                                    "sourceIneligible:${result.reason}"
                                )
                            }

                            is Cl1MutationResult.Failed -> {
                                return pending(
                                    operation,
                                    "sourceUpdate:${result.reason}"
                                )
                            }
                        }
                    }
                    operation = checkpoint(
                        operation,
                        Cl1ResolutionPhases.SOURCE_DETACHED
                    )
                }

                is Cl1Description.UnsupportedVersion,
                is Cl1Description.Corrupt,
                -> return conflict(operation, "sourceBlockChanged")
            }
        }

        val mirror = adapter.readEvent(journal.mirror.toDomain())
            ?: return pending(operation, "mirrorUnavailable")
        return when (val parsed = mirror.parsedDescription) {
            is Cl1Description.None -> complete(
                operation = operation,
                slotHex = journal.slotHex,
                confirmedOrphan = true
            )
            is Cl1Description.Valid -> {
                val payload = parsed.payload as? Cl1Payload.Mirror
                    ?: return conflict(operation, "mirrorRoleChanged")
                if (!Cl1Crypto.constantTimeEquals(payload.secret, secret)) {
                    return conflict(operation, "mirrorRelationChanged")
                }
                if (
                    journal.mirrorDescription != null &&
                    mirror.description != journal.mirrorDescription
                ) {
                    return conflict(operation, "mirrorBlockChanged")
                }
                operation = checkpoint(
                    operation,
                    Cl1ResolutionPhases.MIRROR_APPLYING
                )
                val mirrorCanonical = try {
                    mirror.canonicalEvent()
                } catch (_: IllegalArgumentException) {
                    return conflict(operation, "mirrorIncompatible")
                }
                when (
                    val result = adapter.updateEvent(
                        mirror,
                        Cl1EventWrite(
                            mirrorCanonical,
                            parsed.userDescription
                        )
                    )
                ) {
                    is Cl1MutationResult.Applied -> {
                        val verified = result.event?.ref?.let(adapter::readEvent)
                            ?: adapter.readEvent(mirror.ref)
                        if (verified?.parsedDescription is Cl1Description.None) {
                            complete(
                                operation = operation,
                                slotHex = journal.slotHex,
                                confirmedOrphan = true
                            )
                        } else {
                            pending(operation, "mirrorDetachNotVerified")
                        }
                    }

                    Cl1MutationResult.PreconditionFailed -> {
                        conflict(operation, "mirrorChangedConcurrently")
                    }

                    Cl1MutationResult.Missing -> pending(operation, "mirrorUnavailable")
                    is Cl1MutationResult.Ineligible -> {
                        pending(operation, "mirrorIneligible:${result.reason}")
                    }

                    is Cl1MutationResult.Failed -> {
                        pending(operation, "mirrorUpdate:${result.reason}")
                    }
                }
            }

            is Cl1Description.UnsupportedVersion,
            is Cl1Description.Corrupt,
            -> conflict(operation, "mirrorBlockChanged")
        }
    }

    private fun buildConversion(
        source: Cl1CanonicalEvent,
        mirror: Cl1CanonicalEvent,
        mirrorPayload: Cl1Payload.Mirror,
        conversion: Cl1OverrideConversion,
    ): Cl1Payload.Mirror {
        if (
            source.startIanaTimeZone != mirror.startIanaTimeZone ||
            source.endIanaTimeZone != mirror.endIanaTimeZone ||
            source.location != mirror.location ||
            source.userDescription != mirror.userDescription ||
            source.userUrl != mirror.userUrl
        ) {
            throw Cl1ResolutionException("differencesOutsideOverrides")
        }
        val titleOverride = if (source.title == mirror.title) {
            if (conversion.titleOverride != null) {
                throw Cl1ResolutionException("titleChoiceNotNeeded")
            }
            Cl1TitleOverride.Inherited
        } else {
            val selected = conversion.titleOverride
                ?: throw Cl1ResolutionException("titleChoiceRequired")
            if (selected is Cl1TitleOverride.Inherited) {
                throw Cl1ResolutionException("titleChoiceRequired")
            }
            selected
        }
        val offset = subtractExact(
            mirror.startUnixSeconds,
            source.startUnixSeconds,
            "startOffset"
        ).takeUnless { it == 0L }
        if (
            offset != null &&
            offset !in -Cl1Limits.OFFSET_SECONDS..Cl1Limits.OFFSET_SECONDS
        ) {
            throw Cl1ResolutionException("startOffsetOutOfRange")
        }
        val sourceDuration = duration(source)
        val mirrorDuration = duration(mirror)
        val durationOverride = if (sourceDuration == mirrorDuration) {
            if (conversion.durationMode != null) {
                throw Cl1ResolutionException("durationChoiceNotNeeded")
            }
            Cl1DurationOverride.Inherited
        } else {
            when (
                conversion.durationMode
                    ?: throw Cl1ResolutionException("durationChoiceRequired")
            ) {
                Cl1DurationConversion.FIXED -> {
                    Cl1DurationOverride.Fixed(mirrorDuration.toULong())
                }

                Cl1DurationConversion.DELTA -> {
                    Cl1DurationOverride.Delta(
                        subtractExact(
                            mirrorDuration,
                            sourceDuration,
                            "durationDelta"
                        )
                    )
                }
            }
        }
        val actualRevision = Cl1Revision.calculate(mirrorPayload.secret, mirror)
        val converted = Cl1Payload.Mirror(
            secret = mirrorPayload.secret,
            revision = actualRevision,
            titleOverride = titleOverride,
            startOffsetSeconds = offset,
            durationOverride = durationOverride
        )
        if (!canonicalEquals(Cl1Transform.apply(source, converted), mirror)) {
            throw Cl1ResolutionException("conversionDoesNotMatch")
        }
        Cl1Armor.compose("", converted)
        return converted
    }

    private fun freshRelation(pair: Cl1PairJournal): Cl1RelationSnapshot? {
        val source = adapter.readEvent(pair.source.toDomain()) ?: return null
        val mirror = adapter.readEvent(pair.mirror.toDomain()) ?: return null
        return Cl1Discovery.build(
            listOf(source, mirror),
            domainToAscii = domainToAscii
        ).relations.singleOrNull { it.key.slot.toHex() == pair.slotHex }
    }

    private fun Cl1RelationSnapshot.copyModifiedPair(): Cl1PairJournal? {
        if (state != Cl1RelationState.COPY_MODIFIED) {
            return null
        }
        val source = source ?: return null
        val mirror = mirror ?: return null
        val expected = expectedRevision ?: return null
        val actual = actualRevision ?: return null
        return Cl1PairJournal(
            slotHex = key.slot.toHex(),
            source = Cl1EventRefDto.from(source.ref),
            mirror = Cl1EventRefDto.from(mirror.ref),
            expectedRevisionHex = expected.toHex(),
            actualRevisionHex = actual.toHex()
        )
    }

    private fun Cl1RelationSnapshot.matchesCopyModifiedBaseline(
        pair: Cl1PairJournal,
    ): Boolean {
        return state == Cl1RelationState.COPY_MODIFIED &&
            expectedRevision?.toHex() == pair.expectedRevisionHex &&
            actualRevision?.toHex() == pair.actualRevisionHex
    }

    private fun Cl1RelationSnapshot.isFullyActive(): Boolean {
        return state == Cl1RelationState.ACTIVE && !needsRevisionRefresh
    }

    private fun Cl1EventSnapshot.sourceBlock(): SourceBlock? {
        val parsed = parsedDescription as? Cl1Description.Valid ?: return null
        val payload = parsed.payload as? Cl1Payload.Source ?: return null
        if (payload.hasDuplicateSlots) return null
        return SourceBlock(payload)
    }

    private fun Cl1EventSnapshot.mirrorBlock(): MirrorBlock? {
        val parsed = parsedDescription as? Cl1Description.Valid ?: return null
        val payload = parsed.payload as? Cl1Payload.Mirror ?: return null
        return MirrorBlock(payload)
    }

    private fun Cl1EventSnapshot.matchesTargetSource(
        target: Cl1CanonicalEvent,
        slotHex: String,
    ): Boolean {
        val block = sourceBlock() ?: return false
        val actual = try {
            canonicalEvent()
        } catch (_: IllegalArgumentException) {
            return false
        }
        return canonicalEquals(actual, target) &&
            block.payload.records.count { it.slot.toHex() == slotHex } == 1
    }

    private fun Cl1EventSnapshot.containsSourceSlot(slot: Cl1Bytes): Boolean {
        val valid = parsedDescription as? Cl1Description.Valid ?: return false
        val source = valid.payload as? Cl1Payload.Source ?: return false
        return source.records.any { it.slot == slot }
    }

    private fun decodePair(operation: Cl1PendingOperation): Cl1PairJournal? {
        return decode {
            JSON.decodeFromString(Cl1PairJournal.serializer(), operation.payload)
                .also(::validatePair)
        }
    }

    private fun decodeApplyCopy(
        operation: Cl1PendingOperation,
    ): Cl1ApplyCopyJournal? {
        return decode {
            JSON.decodeFromString(
                Cl1ApplyCopyJournal.serializer(),
                operation.payload
            ).also { validatePair(it.pair) }
        }
    }

    private fun decodeConvert(operation: Cl1PendingOperation): Cl1ConvertJournal? {
        return decode {
            JSON.decodeFromString(
                Cl1ConvertJournal.serializer(),
                operation.payload
            ).also {
                validatePair(it.pair)
                val payload = it.toPayload(
                    Cl1Bytes.copyOf(ByteArray(Cl1Limits.SECRET_BYTES))
                )
                Cl1Armor.compose("", payload)
            }
        }
    }

    private fun decodeUnlink(operation: Cl1PendingOperation): Cl1UnlinkJournal? {
        return decode {
            JSON.decodeFromString(
                Cl1UnlinkJournal.serializer(),
                operation.payload
            ).also {
                require(Cl1Bytes.fromHex(it.slotHex).size == Cl1Limits.SLOT_BYTES)
                require(Cl1Bytes.fromHex(it.secretHex).size == Cl1Limits.SECRET_BYTES)
                require(
                    (it.sourceEmailCiphertextHex == null) ==
                        (it.sourceGcmTagHex == null)
                )
                it.sourceRecord()?.let { record ->
                    require(record.slot == Cl1Bytes.fromHex(it.slotHex))
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

    private fun validatePair(pair: Cl1PairJournal) {
        require(Cl1Bytes.fromHex(pair.slotHex).size == Cl1Limits.SLOT_BYTES)
        require(
            Cl1Bytes.fromHex(pair.expectedRevisionHex).size ==
                Cl1Limits.REVISION_BYTES
        )
        require(
            Cl1Bytes.fromHex(pair.actualRevisionHex).size ==
                Cl1Limits.REVISION_BYTES
        )
    }

    private fun newOperation(
        slotHex: String,
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
    ): Cl1PendingOperation {
        return operation.copy(
            phase = phase,
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
    ): Cl1OperationResult.Conflict {
        return conflict(operation, "journalCorrupt")
    }

    private fun complete(
        operation: Cl1PendingOperation,
        slotHex: String,
        confirmedOrphan: Boolean = false,
    ): Cl1OperationResult.Completed {
        if (confirmedOrphan) {
            storage.markConfirmedOrphan(slotHex)
        }
        storage.removeOperation(operation.operationId)
        return Cl1OperationResult.Completed(operation.operationId, slotHex)
    }

    private fun Cl1PendingOperation.conflictResult(): Cl1OperationResult.Conflict? {
        return if (phase == Cl1ResolutionPhases.CONFLICT) {
            Cl1OperationResult.Conflict(
                operationId,
                lastError ?: "resolutionConflict"
            )
        } else {
            null
        }
    }

    private data class SourceBlock(
        val payload: Cl1Payload.Source,
    )

    private data class MirrorBlock(
        val payload: Cl1Payload.Mirror,
    )

    private class Cl1ResolutionException(
        val reason: String,
    ) : IllegalArgumentException(reason)

    private companion object {
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

        fun duration(event: Cl1CanonicalEvent): Long {
            val value = subtractExact(
                event.endUnixSeconds,
                event.startUnixSeconds,
                "duration"
            )
            if (value <= 0) throw Cl1ResolutionException("invalidDuration")
            return value
        }

        fun subtractExact(left: Long, right: Long, field: String): Long {
            return try {
                Math.subtractExact(left, right)
            } catch (_: ArithmeticException) {
                throw Cl1ResolutionException(field)
            }
        }

        fun Cl1ConvertJournal.Companion.from(
            pair: Cl1PairJournal,
            payload: Cl1Payload.Mirror,
        ): Cl1ConvertJournal {
            val title = when (val value = payload.titleOverride) {
                Cl1TitleOverride.Inherited -> "inherited" to null
                is Cl1TitleOverride.Replacement -> "replacement" to value.value
                is Cl1TitleOverride.Template -> "template" to value.value
            }
            val duration = when (val value = payload.durationOverride) {
                Cl1DurationOverride.Inherited -> "inherited" to null
                is Cl1DurationOverride.Fixed -> "fixed" to value.seconds.toString()
                is Cl1DurationOverride.Delta -> "delta" to value.seconds.toString()
            }
            return Cl1ConvertJournal(
                pair = pair,
                titleMode = title.first,
                titleValue = title.second,
                startOffsetSeconds = payload.startOffsetSeconds,
                durationMode = duration.first,
                durationValue = duration.second
            )
        }

        fun Cl1ConvertJournal.toPayload(secret: Cl1Bytes): Cl1Payload.Mirror {
            val title = when (titleMode) {
                "inherited" -> Cl1TitleOverride.Inherited
                "replacement" -> Cl1TitleOverride.Replacement(
                    requireNotNull(titleValue)
                )

                "template" -> Cl1TitleOverride.Template(requireNotNull(titleValue))
                else -> throw IllegalArgumentException("titleMode")
            }
            val duration = when (durationMode) {
                "inherited" -> Cl1DurationOverride.Inherited
                "fixed" -> Cl1DurationOverride.Fixed(
                    requireNotNull(durationValue).toULong()
                )

                "delta" -> Cl1DurationOverride.Delta(
                    requireNotNull(durationValue).toLong()
                )

                else -> throw IllegalArgumentException("durationMode")
            }
            return Cl1Payload.Mirror(
                secret = secret,
                revision = Cl1Bytes.fromHex(pair.actualRevisionHex),
                titleOverride = title,
                startOffsetSeconds = startOffsetSeconds,
                durationOverride = duration
            )
        }

        fun Cl1UnlinkJournal.sourceRecord(): Cl1SourceRecord? {
            val ciphertext = sourceEmailCiphertextHex ?: return null
            val tag = sourceGcmTagHex ?: return null
            return Cl1SourceRecord(
                slot = Cl1Bytes.fromHex(slotHex),
                emailCiphertext = Cl1Bytes.fromHex(ciphertext),
                gcmTag = Cl1Bytes.fromHex(tag)
            )
        }
    }
}
