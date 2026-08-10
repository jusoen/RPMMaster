package com.rpmmonitor.master.state

import com.rpmmonitor.master.proto.RpmPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Listener-wide counters, independent of any one node. */
data class ListenerStats(
    /** Datagrams that parsed as a version-1 packet. */
    val accepted: Long = 0,
    /**
     * Datagrams with the right magic and length but a version this build does not
     * know. Surfaced rather than dropped silently — a v2 node on the network should
     * be visible.
     */
    val unknownVersion: Long = 0,
    /** Most recent unknown version seen, for the diagnostics line. */
    val lastUnknownVersion: Int? = null,
    /** Datagrams that were not ours at all: wrong length, wrong magic, noise. */
    val ignored: Long = 0,
)

/**
 * Everything the master knows, keyed by `node_id`.
 *
 * Free of Android types on purpose, so the loss, reboot and freshness maths — the
 * only genuinely fiddly logic in the app — is unit-testable on the JVM.
 *
 * **Threading.** Every mutator is `@Synchronized`. Packets arrive on the listener's
 * IO thread and [evaluateFreshness] is driven from a separate timer, so the two do
 * race. State is published through [StateFlow]s, which are safe to read from any
 * thread.
 */
class NodeRegistry {

    companion object {
        /** How much history the graph is given. */
        const val HISTORY_WINDOW_MS = 60_000L

        /**
         * Hard ceiling on retained samples, so the window is not the only bound.
         * 60 s at the firmware's fastest 100 ms cadence is 600, and this is twice
         * that: a node sending faster than its own spec cannot exhaust memory here.
         */
        const val MAX_HISTORY_SAMPLES = 1_200
    }

    private val _nodes = MutableStateFlow<Map<Int, NodeState>>(emptyMap())
    val nodes: StateFlow<Map<Int, NodeState>> = _nodes.asStateFlow()

    private val _stats = MutableStateFlow(ListenerStats())
    val stats: StateFlow<ListenerStats> = _stats.asStateFlow()

    private val accumulators = HashMap<Int, Accumulator>()

    /**
     * Record an accepted packet.
     *
     * @param nowElapsedMs a monotonic clock in milliseconds. Wall-clock is not used
     *   anywhere — a clock step must not read as a burst of lost packets.
     */
    @Synchronized
    fun onPacket(packet: RpmPacket, senderIp: String, nowElapsedMs: Long) {
        val acc = accumulators.getOrPut(packet.nodeId) { Accumulator(packet.nodeId, nowElapsedMs) }
        acc.update(packet, senderIp, nowElapsedMs)
        _stats.value = _stats.value.copy(accepted = _stats.value.accepted + 1)
        publish(nowElapsedMs)
    }

    @Synchronized
    fun onUnknownVersion(version: Int) {
        _stats.value = _stats.value.copy(
            unknownVersion = _stats.value.unknownVersion + 1,
            lastUnknownVersion = version,
        )
    }

    @Synchronized
    fun onIgnored() {
        _stats.value = _stats.value.copy(ignored = _stats.value.ignored + 1)
    }

    /**
     * Recompute freshness for every node.
     *
     * This must be driven from a timer as well as from packet arrival: a node that
     * stops sending generates no event, so nothing else would ever notice it going
     * quiet.
     */
    @Synchronized
    fun evaluateFreshness(nowElapsedMs: Long) {
        if (accumulators.isEmpty()) return
        publish(nowElapsedMs)
    }

    /**
     * Reset one node's displayed high-water mark.
     *
     * The node cannot be told to reset its own `rpm_peak` — the protocol is one-way —
     * so this rebases the master's figure to the current reading and it climbs again
     * from there. An unknown [nodeId] is a no-op: the caller is the UI, and a node can
     * go away between a tap and its delivery.
     */
    @Synchronized
    fun resetPeak(nodeId: Int, nowElapsedMs: Long) {
        val acc = accumulators[nodeId] ?: return
        acc.resetPeak()
        publish(nowElapsedMs)
    }

    /**
     * Discard one node's trace, so the graph starts again from the next packet.
     *
     * Only the history goes: the counters, the peak and the measured rate are not
     * derived from it and a request to clear a plot is not a request to lose them.
     * An unknown [nodeId] is a no-op, for the same reason as [resetPeak].
     */
    @Synchronized
    fun clearHistory(nodeId: Int, nowElapsedMs: Long) {
        val acc = accumulators[nodeId] ?: return
        acc.clearHistory()
        publish(nowElapsedMs)
    }

    /** Drop everything. Used when the listener is stopped and restarted. */
    @Synchronized
    fun clear() {
        accumulators.clear()
        _nodes.value = emptyMap()
        _stats.value = ListenerStats()
    }

    private fun publish(nowElapsedMs: Long) {
        val next = HashMap<Int, NodeState>(accumulators.size)
        for ((id, acc) in accumulators) next[id] = acc.snapshot(nowElapsedMs)
        // StateFlow deduplicates by equality, so an unchanged snapshot on a timer
        // tick costs no recomposition.
        _nodes.value = next
    }

    /** Mutable per-node working state. Only ever touched under the registry lock. */
    private class Accumulator(val nodeId: Int, firstSeenElapsedMs: Long) {

        private companion object {
            /** Intervals kept for the rolling median. */
            const val INTERVAL_WINDOW = 10

            /**
             * A gap wider than this many packets is treated as an outage rather than
             * a measurable interval — dividing a 30 s silence by its seq delta would
             * poison the median.
             */
            const val MAX_DELTA_FOR_INTERVAL = 8

            /** Sliding loss window. */
            const val LOSS_WINDOW_MS = 30_000L

            /** Below this many window samples the loss percentage is not shown. */
            const val MIN_WINDOW_SAMPLES = 20
        }

        private lateinit var lastPacket: RpmPacket
        private var haveLast = false
        private var lastSeenMs = firstSeenElapsedMs
        private var senderIp = ""
        private val senderIps = LinkedHashSet<String>()
        private var collision = false

        private var packetsReceived = 0L
        private var linkLost = 0L
        private var rebootCount = 0
        private var peakRpm = 0L

        private val intervals = ArrayDeque<Long>()
        private val history = ArrayDeque<RpmSample>()
        /** (timestamp, received, lost) triples, trimmed to [LOSS_WINDOW_MS]. */
        private val lossWindow = ArrayDeque<LongArray>()

        fun update(packet: RpmPacket, ip: String, nowMs: Long) {
            if (senderIps.add(ip) && senderIps.size > 1) {
                // Same node_id from a second address. Flag it and stop deriving
                // link loss — two interleaved seq streams make the figure a lie.
                collision = true
            }
            senderIp = ip

            // Recorded before the branches below, both of which return early. A
            // first packet and the packet that reveals a reboot are exactly the two
            // the trace must not be missing.
            noteSample(nowMs, packet)

            if (!haveLast) {
                haveLast = true
                lastPacket = packet
                lastSeenMs = nowMs
                packetsReceived = 1
                // Seeded from the node's own figure the first time only, so the
                // display covers the run before this master was listening.
                peakRpm = maxOf(packet.rpmPeak, packet.rpm)
                noteWindow(nowMs, received = 1, lost = 0)
                return
            }

            // Wrap-aware: 65535 -> 0 is a delta of 1, not a 65535-packet loss.
            val delta = (packet.seq - lastPacket.seq) and 0xFFFF
            val uptimeDropped = packet.uptimeMs < lastPacket.uptimeMs

            if (uptimeDropped && delta != 1) {
                // A backwards seq jump together with an uptime drop: the node
                // rebooted. Reset the per-node statistics but keep the reboot count,
                // which is the one figure that must survive.
                rebootCount++
                packetsReceived = 1
                linkLost = 0
                // The node's own peak restarted at the reboot, so the display does
                // too. Carrying the old figure across would attribute a previous
                // run's maximum to this one.
                peakRpm = maxOf(packet.rpmPeak, packet.rpm)
                intervals.clear()
                lossWindow.clear()
                lastPacket = packet
                lastSeenMs = nowMs
                noteWindow(nowMs, received = 1, lost = 0)
                return
            }

            val elapsed = nowMs - lastSeenMs
            val lost = if (collision || delta == 0) 0L else (delta - 1).toLong()

            // Only the live reading feeds the mark from here on. A reset must survive
            // the next packet, and the node's rpm_peak never falls.
            peakRpm = maxOf(peakRpm, packet.rpm)

            packetsReceived++
            if (!collision) linkLost += lost
            noteWindow(nowMs, received = 1, lost = lost)

            // Normalise the gap by the number of packet slots it spans, so one lost
            // datagram does not read as the node having halved its rate.
            if (delta in 1..MAX_DELTA_FOR_INTERVAL && elapsed in 1..10_000) {
                intervals.addLast(elapsed / delta)
                while (intervals.size > INTERVAL_WINDOW) intervals.removeFirst()
            }

            lastPacket = packet
            lastSeenMs = nowMs
        }

        /**
         * Rebase the mark on the current reading, not on 0. The engine is still
         * turning at whatever it was turning at, and a mark below the live value
         * would be contradicted by the needle standing beyond it.
         */
        fun resetPeak() {
            peakRpm = if (haveLast) lastPacket.rpm else 0
        }

        fun clearHistory() = history.clear()

        /**
         * Append one sample and trim the trace.
         *
         * Bounded on both axes and on every path: the deque grows by exactly one per
         * packet, the age loop stops as soon as the oldest entry is inside the
         * window, and the count loop stops at the ceiling. History deliberately
         * survives a reboot — the dropout either side of it is the most interesting
         * thing the trace can show.
         */
        private fun noteSample(nowMs: Long, packet: RpmPacket) {
            history.addLast(RpmSample(nowMs, packet.rpm, packet.interval))
            while (history.isNotEmpty() && nowMs - history.first().elapsedMs > HISTORY_WINDOW_MS) {
                history.removeFirst()
            }
            while (history.size > MAX_HISTORY_SAMPLES) history.removeFirst()
        }

        private fun noteWindow(nowMs: Long, received: Long, lost: Long) {
            lossWindow.addLast(longArrayOf(nowMs, received, lost))
            // Bounded on every path: the deque only ever grows by one per packet and
            // this trims from the front until the oldest entry is inside the window.
            while (lossWindow.isNotEmpty() && nowMs - lossWindow.first()[0] > LOSS_WINDOW_MS) {
                lossWindow.removeFirst()
            }
        }

        /** Median of the interval window. Null until at least three samples exist. */
        private fun medianIntervalMs(): Long? {
            if (intervals.size < 3) return null
            val sorted = intervals.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        }

        private fun windowLossFraction(): Double? {
            if (collision) return null
            var received = 0L
            var lost = 0L
            for (e in lossWindow) { received += e[1]; lost += e[2] }
            val expected = received + lost
            return if (expected < MIN_WINDOW_SAMPLES) null else lost.toDouble() / expected
        }

        fun snapshot(nowMs: Long): NodeState {
            val interval = medianIntervalMs()
            val thresholds = FreshnessThresholds.forInterval(interval)
            val age = (nowMs - lastSeenMs).coerceAtLeast(0)
            val freshness = when {
                age >= thresholds.offlineMs -> Freshness.OFFLINE
                age >= thresholds.staleMs -> Freshness.STALE
                else -> Freshness.LIVE
            }
            return NodeState(
                nodeId = nodeId,
                last = lastPacket,
                peakRpm = peakRpm,
                // A copy, so a consumer holding this snapshot is never reading a
                // deque the listener thread is mutating underneath it. The samples
                // themselves are immutable and shared.
                history = history.toList(),
                senderIp = senderIp,
                senderIps = senderIps.toSet(),
                lastSeenElapsedMs = lastSeenMs,
                ageMs = age,
                freshness = freshness,
                thresholds = thresholds,
                observedIntervalMs = interval,
                packetsReceived = packetsReceived,
                linkLost = linkLost,
                windowLossFraction = windowLossFraction(),
                rebootCount = rebootCount,
                collision = collision,
            )
        }
    }
}
