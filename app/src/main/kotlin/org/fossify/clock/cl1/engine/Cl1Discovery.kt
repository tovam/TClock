package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Bytes
import org.fossify.clock.cl1.Cl1Crypto
import org.fossify.clock.cl1.Cl1CryptoException
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DomainToAscii
import org.fossify.clock.cl1.Cl1IncompatibleException
import org.fossify.clock.cl1.Cl1JdkDomainToAscii
import org.fossify.clock.cl1.Cl1Payload
import org.fossify.clock.cl1.Cl1Revision
import org.fossify.clock.cl1.Cl1Transform
import org.fossify.clock.cl1.provider.Cl1CalendarIncompatibleException
import org.fossify.clock.cl1.provider.Cl1EventSnapshot

object Cl1Discovery {
    fun build(
        events: List<Cl1EventSnapshot>,
        capturedAtMillis: Long = System.currentTimeMillis(),
        domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    ): Cl1DiscoverySnapshot {
        val uniqueEvents = events.distinctBy { it.ref }
        val sourceCandidates = LinkedHashMap<Cl1Bytes, MutableList<Cl1SourceCandidate>>()
        val mirrorCandidates = LinkedHashMap<Cl1Bytes, MutableList<Cl1MirrorCandidate>>()
        val issues = ArrayList<Cl1EventIssue>()

        uniqueEvents.forEach { event ->
            when (val parsed = event.parsedDescription) {
                is Cl1Description.None -> Unit
                is Cl1Description.UnsupportedVersion -> {
                    issues.add(
                        Cl1EventIssue(
                            event,
                            Cl1EventIssueState.UNSUPPORTED_VERSION,
                            "CL${parsed.version}"
                        )
                    )
                }

                is Cl1Description.Corrupt -> {
                    issues.add(
                        Cl1EventIssue(
                            event,
                            Cl1EventIssueState.BLOCK_CORRUPT,
                            parsed.reason.name
                        )
                    )
                }

                is Cl1Description.Valid -> {
                    when (val payload = parsed.payload) {
                        is Cl1Payload.Source -> payload.records.forEachIndexed { index, record ->
                            sourceCandidates.getOrPut(record.slot) { ArrayList() }.add(
                                Cl1SourceCandidate(event, payload, index)
                            )
                        }

                        is Cl1Payload.Mirror -> {
                            val slot = Cl1Crypto.deriveSlot(payload.secret)
                            mirrorCandidates.getOrPut(slot) { ArrayList() }.add(
                                Cl1MirrorCandidate(event, payload)
                            )
                        }
                    }
                }
            }
        }

        val slots = (sourceCandidates.keys + mirrorCandidates.keys).sorted()
        val relations = slots.map { slot ->
            buildRelation(
                slot = slot,
                sources = sourceCandidates[slot].orEmpty(),
                mirrors = mirrorCandidates[slot].orEmpty(),
                domainToAscii = domainToAscii
            )
        }
        return Cl1DiscoverySnapshot(
            capturedAtMillis = capturedAtMillis,
            events = uniqueEvents,
            relations = relations,
            eventIssues = issues
        )
    }

    private fun buildRelation(
        slot: Cl1Bytes,
        sources: List<Cl1SourceCandidate>,
        mirrors: List<Cl1MirrorCandidate>,
        domainToAscii: Cl1DomainToAscii,
    ): Cl1RelationSnapshot {
        if (sources.size > 1 || mirrors.size > 1) {
            return relation(
                slot = slot,
                state = Cl1RelationState.RELATION_CONFLICT,
                source = sources.firstOrNull(),
                mirror = mirrors.firstOrNull(),
                detail = "cardinality:${sources.size}:${mirrors.size}"
            )
        }
        val source = sources.singleOrNull()
        val mirror = mirrors.singleOrNull()
        if (source == null) {
            return relation(
                slot = slot,
                state = Cl1RelationState.UNRESOLVED,
                mirror = mirror
            )
        }
        if (source.payload.hasDuplicateSlots) {
            return relation(
                slot = slot,
                state = Cl1RelationState.RELATION_CONFLICT,
                source = source,
                mirror = mirror,
                detail = "duplicateSlot"
            )
        }
        if (mirror == null) {
            return relation(
                slot = slot,
                state = Cl1RelationState.MISSING_OR_INACCESSIBLE,
                source = source
            )
        }
        return validatePair(slot, source, mirror, domainToAscii)
    }

    private fun validatePair(
        slot: Cl1Bytes,
        source: Cl1SourceCandidate,
        mirror: Cl1MirrorCandidate,
        domainToAscii: Cl1DomainToAscii,
    ): Cl1RelationSnapshot {
        val record = source.payload.records[source.recordIndex]
        val accountEmail = mirror.event.calendar.canonicalAccountEmail
            ?: return relation(
                slot,
                Cl1RelationState.RECORD_CORRUPT,
                source,
                mirror,
                detail = "accountEmail"
            )
        val decryptedEmail = try {
            Cl1Crypto.decryptEmail(mirror.payload.secret, record, domainToAscii)
        } catch (_: Cl1CryptoException) {
            return relation(
                slot,
                Cl1RelationState.RECORD_CORRUPT,
                source,
                mirror,
                detail = "emailAuthentication"
            )
        }
        if (!Cl1Crypto.constantTimeEquals(decryptedEmail.value, accountEmail.value)) {
            return relation(
                slot,
                Cl1RelationState.RECORD_CORRUPT,
                source,
                mirror,
                detail = "accountMismatch"
            )
        }

        return try {
            val expectedEvent = Cl1Transform.apply(
                source.event.canonicalEvent(),
                mirror.payload
            )
            val actualEvent = mirror.event.canonicalEvent()
            val expectedRevision = Cl1Revision.calculate(
                mirror.payload.secret,
                expectedEvent
            )
            val actualRevision = Cl1Revision.calculate(
                mirror.payload.secret,
                actualEvent
            )
            classifyRevisions(
                slot,
                source,
                mirror,
                expectedEvent,
                expectedRevision,
                actualRevision
            )
        } catch (exception: Cl1CalendarIncompatibleException) {
            relation(
                slot,
                Cl1RelationState.INCOMPATIBLE,
                source,
                mirror,
                detail = exception.field
            )
        } catch (exception: Cl1IncompatibleException) {
            relation(
                slot,
                Cl1RelationState.INCOMPATIBLE,
                source,
                mirror,
                detail = exception.field
            )
        }
    }

    private fun classifyRevisions(
        slot: Cl1Bytes,
        source: Cl1SourceCandidate,
        mirror: Cl1MirrorCandidate,
        expectedEvent: org.fossify.clock.cl1.Cl1CanonicalEvent,
        expectedRevision: Cl1Bytes,
        actualRevision: Cl1Bytes,
    ): Cl1RelationSnapshot {
        val previousRevision = mirror.payload.revision
        val expectedMatchesActual = Cl1Crypto.constantTimeEquals(
            expectedRevision,
            actualRevision
        )
        val expectedMatchesPrevious = Cl1Crypto.constantTimeEquals(
            expectedRevision,
            previousRevision
        )
        val actualMatchesPrevious = Cl1Crypto.constantTimeEquals(
            actualRevision,
            previousRevision
        )
        val state = when {
            expectedMatchesActual -> Cl1RelationState.ACTIVE
            !expectedMatchesPrevious && actualMatchesPrevious -> {
                Cl1RelationState.SOURCE_MODIFIED
            }

            expectedMatchesPrevious && !actualMatchesPrevious -> {
                Cl1RelationState.COPY_MODIFIED
            }

            else -> Cl1RelationState.CONCURRENT_CONFLICT
        }
        return Cl1RelationSnapshot(
            key = Cl1RelationKey(slot),
            state = state,
            source = source.event,
            mirror = mirror.event,
            sourcePayload = source.payload,
            sourceRecordIndex = source.recordIndex,
            mirrorPayload = mirror.payload,
            expectedMirror = expectedEvent,
            expectedRevision = expectedRevision,
            actualRevision = actualRevision,
            needsRevisionRefresh = state == Cl1RelationState.ACTIVE &&
                !expectedMatchesPrevious
        )
    }

    private fun relation(
        slot: Cl1Bytes,
        state: Cl1RelationState,
        source: Cl1SourceCandidate? = null,
        mirror: Cl1MirrorCandidate? = null,
        detail: String? = null,
    ): Cl1RelationSnapshot {
        return Cl1RelationSnapshot(
            key = Cl1RelationKey(slot),
            state = state,
            source = source?.event,
            mirror = mirror?.event,
            sourcePayload = source?.payload,
            sourceRecordIndex = source?.recordIndex,
            mirrorPayload = mirror?.payload,
            expectedMirror = null,
            expectedRevision = null,
            actualRevision = null,
            detail = detail
        )
    }
}
