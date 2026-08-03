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

        private val intervals = ArrayDeque<Long>()
        /** (timestamp, received, lost) triples, trimmed to [LOSS_WINDOW_MS]. */
        private val lossWindow = ArrayDeque<LongArray>()

        fun update(packet: RpmPacket, ip: String, nowMs: Long) {
            if (senderIps.add(ip) && senderIps.size > 1) {
                // Same node_id from a second address. Flag it and stop deriving
                // link loss — two interleaved seq streams make the figure a lie.
                collision = true
            }
            senderIp = ip

            if (!haveLast) {
                haveLast = true
                lastPacket = packet
                lastSeenMs = nowMs
                packetsReceived = 1
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
                intervals.clear()
                lossWindow.clear()
                lastPacket = packet
                lastSeenMs = nowMs
                noteWindow(nowMs, received = 1, lost = 0)
                return
            }

            val elapsed = nowMs - lastSeenMs
            val lost = if (collision || delta == 0) 0L else (delta - 1).toLong()

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
