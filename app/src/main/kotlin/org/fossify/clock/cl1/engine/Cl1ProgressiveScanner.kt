package org.fossify.clock.cl1.engine

import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.Cl1DomainToAscii
import org.fossify.clock.cl1.Cl1JdkDomainToAscii
import org.fossify.clock.cl1.provider.Cl1CalendarAdapter
import org.fossify.clock.cl1.provider.Cl1EventRef
import org.fossify.clock.cl1.provider.Cl1EventSnapshot
import org.fossify.clock.cl1.storage.Cl1Storage
import java.util.ArrayDeque

data class Cl1ScanWindow(
    val beginMillis: Long,
    val endMillis: Long,
)

data class Cl1ScanResult(
    val discovery: Cl1DiscoverySnapshot,
    val queriedWindows: List<Cl1ScanWindow>,
    val unavailableCachedEvents: Set<Cl1EventRef>,
)

class Cl1ProgressiveScanner(
    private val adapter: Cl1CalendarAdapter,
    private val storage: Cl1Storage,
    private val domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
) {
    fun scan(
        beginMillis: Long,
        endMillis: Long,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): Cl1ScanResult {
        require(beginMillis <= endMillis)
        val events = LinkedHashMap<Cl1EventRef, Cl1EventSnapshot>()
        val unavailable = LinkedHashSet<Cl1EventRef>()
        val windows = ArrayList<Cl1ScanWindow>()
        val queuedWindows = HashSet<Cl1ScanWindow>()
        val queue = ArrayDeque<Cl1ScanWindow>()

        fun addWindow(window: Cl1ScanWindow) {
            if (queuedWindows.add(window)) {
                queue.addLast(window)
            }
        }

        fun addEvent(event: Cl1EventSnapshot) {
            if (events.putIfAbsent(event.ref, event) == null && event.hasCl1Block()) {
                addWindow(event.discoveryWindow())
            }
        }

        addWindow(Cl1ScanWindow(beginMillis, endMillis))
        storage.listCachedBindings()
            .map { it.ref }
            .distinct()
            .forEach { ref ->
                adapter.readEvent(ref)?.let(::addEvent) ?: unavailable.add(ref)
            }

        while (queue.isNotEmpty()) {
            val window = queue.removeFirst()
            windows.add(window)
            adapter.listEvents(window.beginMillis, window.endMillis).forEach(::addEvent)
        }

        val discovery = Cl1Discovery.build(
            events = events.values.toList(),
            capturedAtMillis = capturedAtMillis,
            domainToAscii = domainToAscii
        )
        storage.saveDiscovery(discovery)
        return Cl1ScanResult(
            discovery = discovery,
            queriedWindows = windows,
            unavailableCachedEvents = unavailable
        )
    }

    private fun Cl1EventSnapshot.hasCl1Block(): Boolean {
        return parsedDescription !is Cl1Description.None
    }

    private fun Cl1EventSnapshot.discoveryWindow(): Cl1ScanWindow {
        return Cl1ScanWindow(
            beginMillis = startMillis.saturatedMinus(DISCOVERY_RADIUS_MILLIS),
            endMillis = maxOf(startMillis, endMillis ?: startMillis)
                .saturatedPlus(DISCOVERY_RADIUS_MILLIS)
        )
    }

    private fun Long.saturatedMinus(value: Long): Long {
        return if (this < Long.MIN_VALUE + value) Long.MIN_VALUE else this - value
    }

    private fun Long.saturatedPlus(value: Long): Long {
        return if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
    }

    private companion object {
        const val DISCOVERY_RADIUS_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    }
}
