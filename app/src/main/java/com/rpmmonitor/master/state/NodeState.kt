package com.rpmmonitor.master.state

import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.proto.RpmPacket

/**
 * How recently a node was heard from.
 *
 * The boundaries are derived from the node's *observed* cadence, not from a
 * compiled-in 100 ms (Appendix A.1) — the firmware sends at 100 ms, 250 ms or
 * 750 ms depending on its build switches, and a fixed 500 ms / 3 s pair flaps at
 * the slower ones.
 */
enum class Freshness { LIVE, STALE, OFFLINE }

/**
 * Per-node freshness boundaries in milliseconds, derived from the observed
 * inter-packet interval.
 *
 * Floors keep a fast node from being called stale over one dropped packet. Ceilings
 * keep a slow node from taking a third of a minute to be called offline.
 */
data class FreshnessThresholds(val staleMs: Long, val offlineMs: Long) {
    companion object {
        const val STALE_MULTIPLE = 4
        const val OFFLINE_MULTIPLE = 20

        /** Floors: the sanctioned fixed fallback from A.1, used as a lower bound. */
        const val STALE_FLOOR_MS = 1_000L
        const val OFFLINE_FLOOR_MS = 5_000L

        /** Ceilings, so a 750 ms node does not need 15 s to read as offline. */
        const val STALE_CEIL_MS = 5_000L
        const val OFFLINE_CEIL_MS = 20_000L

        /** Used until enough intervals have been seen to measure a rate. */
        const val ASSUMED_INTERVAL_MS = 250L

        fun forInterval(observedIntervalMs: Long?): FreshnessThresholds {
            val interval = observedIntervalMs ?: ASSUMED_INTERVAL_MS
            return FreshnessThresholds(
                staleMs = (interval * STALE_MULTIPLE).coerceIn(STALE_FLOOR_MS, STALE_CEIL_MS),
                offlineMs = (interval * OFFLINE_MULTIPLE).coerceIn(OFFLINE_FLOOR_MS, OFFLINE_CEIL_MS),
            )
        }
    }
}

/**
 * One reading, timestamped on the master's monotonic clock.
 *
 * The node's own `uptime_ms` is not used as the time base: it restarts at a reboot,
 * and a trace whose x axis jumps backwards is worse than no trace.
 */
data class RpmSample(val elapsedMs: Long, val rpm: Long)

/**
 * An immutable snapshot of one node, as published by [NodeRegistry].
 *
 * No history is kept — but the shape here is deliberately a snapshot of a
 * separately-held accumulator, so adding a ring buffer of recent samples later means
 * adding one field and one writer, not reshaping the model.
 */
data class NodeState(
    val nodeId: Int,
    /** The most recent packet. Never null: a node exists only once one has arrived. */
    val last: RpmPacket,
    /**
     * The displayed high-water mark: the highest reading seen since this node was
     * first heard, its last reboot, or the last [NodeRegistry.resetPeak].
     *
     * Seeded from the node's own `rpm_peak`, so it covers the run before the master
     * was listening. After a reset it tracks only what this master has seen — folding
     * the node's monotonic figure back in would undo the reset on the next packet.
     * `last.rpmPeak` remains the unmodified wire value.
     */
    val peakRpm: Long,
    /**
     * Recent readings, oldest first, trimmed to [NodeRegistry.HISTORY_WINDOW_MS] and
     * to a hard sample count so a fast node cannot grow it without bound.
     *
     * Every accepted packet contributes one sample, including the first and the one
     * that reveals a reboot — a trace with the interesting packets missing from it is
     * the wrong trace.
     */
    val history: List<RpmSample>,
    /** Source address of the most recent packet — evidence the DHCP lease worked. */
    val senderIp: String,
    /** Every source address seen for this id. More than one means [collision]. */
    val senderIps: Set<String>,
    /** Elapsed-clock milliseconds when the most recent packet arrived. */
    val lastSeenElapsedMs: Long,
    /** Milliseconds since the most recent packet, as of the last evaluation. */
    val ageMs: Long,
    val freshness: Freshness,
    val thresholds: FreshnessThresholds,

    /** Rolling median inter-packet interval, or null until enough samples exist. */
    val observedIntervalMs: Long?,

    /** Packets accepted since the node was first seen, or since its last reboot. */
    val packetsReceived: Long,
    /**
     * Datagrams lost between here and the node, from wrap-aware seq deltas.
     *
     * Node-side drops never produce a gap (Appendix A.2) — the firmware increments
     * `seq` only after a successful hand-off to the stack — so this figure measures
     * the air link and the receiver, nothing on the node.
     */
    val linkLost: Long,
    /** Link loss over a short sliding window, 0..1, or null with too few samples. */
    val windowLossFraction: Double?,
    /** Reboots seen. Survives the statistics reset that each reboot triggers. */
    val rebootCount: Int,

    /**
     * Two senders are using this `node_id`. Statistics are frozen rather than
     * interleaved — a merged loss figure from two independent seq streams is worse
     * than no figure at all.
     */
    val collision: Boolean,
) {
    /** Lifetime link loss as a fraction, or null before anything could be lost. */
    val lifetimeLossFraction: Double?
        get() {
            val expected = packetsReceived + linkLost
            return if (expected <= 0) null else linkLost.toDouble() / expected
        }

    /** Measured packets per second, the single most useful diagnostics number. */
    val observedRateHz: Double?
        get() = observedIntervalMs?.takeIf { it > 0 }?.let { 1000.0 / it }

    /** True when the reading is non-zero but outside the sensor design window. */
    val outOfRange: Boolean get() = RpmCodec.isOutOfRange(last.rpm)

    /** True when the node is reporting a stall or no sensor signal. */
    val stalled: Boolean get() = last.rpm == 0L
}
