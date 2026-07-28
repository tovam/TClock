package org.fossify.clock.cl1.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.fossify.clock.cl1.Cl1DomainToAscii
import org.fossify.clock.cl1.provider.AndroidCalendarContractAdapter
import org.fossify.clock.cl1.provider.AndroidCl1DomainToAscii
import org.fossify.clock.cl1.provider.Cl1CalendarAdapter
import org.fossify.clock.cl1.provider.Cl1CalendarDescriptor
import org.fossify.clock.cl1.provider.Cl1CalendarRef
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.storage.AndroidCl1Storage
import org.fossify.clock.cl1.storage.Cl1PendingOperation
import org.fossify.clock.cl1.storage.Cl1Storage

data class Cl1AppSnapshot(
    val scan: Cl1ScanResult,
    val calendars: List<Cl1CalendarDescriptor>,
    val pendingOperations: List<Cl1PendingOperation>,
    val operationResults: List<Cl1OperationResult> = emptyList(),
) {
    val discovery: Cl1DiscoverySnapshot
        get() = scan.discovery
}

/**
 * Serializes provider scans and CL1 mutations so the alarm synchronizer, background jobs and
 * diagnostics screen cannot race each other.
 */
class Cl1Coordinator(
    private val adapter: Cl1CalendarAdapter,
    private val storage: Cl1Storage,
    domainToAscii: Cl1DomainToAscii,
    private val mutationsAllowed: () -> Boolean = { true },
) {
    private val scanner = Cl1ProgressiveScanner(adapter, storage, domainToAscii)
    private val operations = Cl1OperationEngine(adapter, storage, domainToAscii)

    @Synchronized
    fun scan(
        beginMillis: Long,
        endMillis: Long,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): Cl1AppSnapshot {
        return snapshot(
            scanner.scan(beginMillis, endMillis, capturedAtMillis)
        )
    }

    /**
     * Resumes journalled work first, discovers every reachable relation, automatically propagates
     * safe source changes, then scans again after any attempted mutation.
     */
    @Synchronized
    fun synchronize(
        beginMillis: Long,
        endMillis: Long,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): Cl1AppSnapshot {
        val resumed = if (mutationsAllowed()) {
            operations.resumePending()
        } else {
            emptyList()
        }
        var scan = scanner.scan(beginMillis, endMillis, capturedAtMillis)
        val reconciled = if (mutationsAllowed()) {
            operations.reconcile(scan.discovery)
        } else {
            emptyList()
        }
        val results = resumed + reconciled
        if (results.isNotEmpty()) {
            scan = scanner.scan(
                beginMillis = beginMillis,
                endMillis = endMillis,
                capturedAtMillis = System.currentTimeMillis()
            )
        }
        return snapshot(scan, results)
    }

    @Synchronized
    fun createRelation(
        sourceRef: Cl1EventRef,
        destinationRef: Cl1CalendarRef,
        overrides: Cl1MirrorOverrides = Cl1MirrorOverrides(),
    ): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.createRelation(sourceRef, destinationRef, overrides)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun repairRelation(
        relation: Cl1RelationSnapshot,
        destinationRef: Cl1CalendarRef,
        overrides: Cl1MirrorOverrides = Cl1MirrorOverrides(),
    ): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.repairRelation(relation, destinationRef, overrides)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun restoreFromSource(relation: Cl1RelationSnapshot): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.restoreFromSource(relation)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun applyCopyToSource(relation: Cl1RelationSnapshot): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.applyCopyToSource(relation)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun convertCopyToOverrides(
        relation: Cl1RelationSnapshot,
        conversion: Cl1OverrideConversion,
    ): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.convertCopyToOverrides(relation, conversion)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun unlink(relation: Cl1RelationSnapshot): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.unlink(relation)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun changeDestination(
        relation: Cl1RelationSnapshot,
        destinationRef: Cl1CalendarRef,
    ): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.changeDestination(relation, destinationRef)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    @Synchronized
    fun deleteSource(
        sourceRef: Cl1EventRef,
        discovery: Cl1DiscoverySnapshot,
    ): Cl1OperationResult {
        return if (mutationsAllowed()) {
            operations.deleteSource(sourceRef, discovery)
        } else {
            Cl1OperationResult.Rejected(null, WRITE_PERMISSION_REASON)
        }
    }

    private fun snapshot(
        scan: Cl1ScanResult,
        operationResults: List<Cl1OperationResult> = emptyList(),
    ): Cl1AppSnapshot {
        return Cl1AppSnapshot(
            scan = scan,
            calendars = adapter.listCalendars(),
            pendingOperations = storage.listPendingOperations(),
            operationResults = operationResults
        )
    }

    private companion object {
        const val WRITE_PERMISSION_REASON = "writePermission"
    }
}

object AndroidCl1Coordinator {
    @Volatile
    private var instance: Cl1Coordinator? = null

    fun from(context: Context): Cl1Coordinator {
        val applicationContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: Cl1Coordinator(
                adapter = AndroidCalendarContractAdapter(applicationContext),
                storage = AndroidCl1Storage.from(applicationContext),
                domainToAscii = AndroidCl1DomainToAscii,
                mutationsAllowed = {
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.WRITE_CALENDAR
                    ) == PackageManager.PERMISSION_GRANTED
                }
            ).also { instance = it }
        }
    }
}
