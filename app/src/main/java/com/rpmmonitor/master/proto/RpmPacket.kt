package com.rpmmonitor.master.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-interval revolution statistics, present only on a version-2 packet.
 *
 * These describe the individual revolutions that *completed inside the reporting
 * interval this packet covers*, each one measured from its own period. They are what
 * lets the master separate combustion roughness (the spread within an interval) from
 * wander and hunting (the movement of one interval's mean to the next). See
 * `docs/rpm-proto-v2.md` for the node's side of the contract.
 *
 * Offsets 20-27: rev_count 20-21, rpm_sd_x10 22-23, rpm_min 24-25, rpm_max 26-27.
 * All four are u16 on the wire and the node saturates rather than wraps, so a value
 * of 65535 means "at least this much" and not a small number that overflowed.
 */
data class IntervalStats(
    /**
     * Revolutions measured in this interval. Legitimately 0 (stopped, or turning too
     * slowly for one to complete) and legitimately 1, which is the common case below
     * about 600 rpm at the 100 ms cadence.
     */
    val revCount: Int,
    /**
     * Sample standard deviation of those revolutions, in units of 0.1 rpm. Meaningless
     * unless [revCount] is 2 or more, which is why [sdRpm] is the accessor to use.
     */
    val sdRpmX10: Int,
    /** Lowest single-revolution rpm in the interval. */
    val minRpm: Long,
    /** Highest single-revolution rpm in the interval. */
    val maxRpm: Long,
) {
    /**
     * The spread in rpm, or null when there were too few revolutions to have one.
     *
     * Deliberately null rather than 0 for a one-revolution interval: a zero sigma
     * reads as a perfectly steady engine, which is the opposite of "not measured".
     */
    val sdRpm: Double? get() = if (revCount >= 2) sdRpmX10 / 10.0 else null

    /**
     * True when this interval is self-consistent against the packet's [rpm], which is
     * the interval mean.
     *
     * Checked at the point of use rather than in [RpmCodec.parse], which does framing
     * only. A node whose statistics accumulator is broken should still drive the dial
     * from its `rpm` field: losing the reading over a fault in a subsidiary figure
     * would be a worse failure than the fault itself.
     */
    fun consistentWith(rpm: Long): Boolean = when {
        revCount == 0 -> rpm == 0L && sdRpmX10 == 0 && minRpm == 0L && maxRpm == 0L
        else -> minRpm <= rpm && rpm <= maxRpm
    }
}

/**
 * The wire contract, mirroring `rpm_proto.h` in the slave firmware.
 *
 * Packed, **little-endian** — the sender is a Cortex-M and performs no network-byte-order
 * conversion, so [ByteOrder.LITTLE_ENDIAN] must be set explicitly (Java's default is
 * big-endian). Every field on the wire is unsigned, hence the widening and the masks
 * in [RpmCodec.parse].
 *
 * Version 1 is 20 bytes. Version 2 is 28 and appends [IntervalStats]. The first 20
 * bytes of a v2 packet have the same layout as a v1 packet, so the header parse is
 * shared.
 *
 * Offsets: magic 0-3, version 4, node_id 5, seq 6-7, uptime_ms 8-11, rpm 12-15,
 * rpm_peak 16-19.
 *
 * This file deliberately contains no Android types, so the parser is unit-testable
 * on the JVM without an emulator.
 */
data class RpmPacket(
    /** Which engine node sent this. Key all per-node state by it. */
    val nodeId: Int,
    /** u16, increments per packet sent, wraps 65535 -> 0. Gaps mean link loss. */
    val seq: Int,
    /** Sender milliseconds since boot. Wraps at ~49.7 days. Never a synchronised clock. */
    val uptimeMs: Long,
    /**
     * The reading. 0 is meaningful (stalled or no signal), not an error.
     *
     * On version 1 this is the node's live figure. On version 2 it is the mean of the
     * revolutions in [interval], which is a slightly different quantity and is why the
     * two are read together rather than separately.
     */
    val rpm: Long,
    /** Highest reading since the node booted. Monotonic until reboot. */
    val rpmPeak: Long,
    /** Version-2 statistics, or null from a version-1 node. */
    val interval: IntervalStats? = null,
)

/** Outcome of parsing one datagram payload. */
sealed interface ParseResult {
    /** A well-formed packet of a version this build understands. */
    data class Ok(val packet: RpmPacket) : ParseResult

    /**
     * Correct magic and a length we recognise, but the version does not match the
     * layout that length implies. Counted and surfaced rather than discarded silently
     * — the field exists so the protocol can evolve, and a node speaking something
     * newer should be visible, not invisible.
     *
     * Length and version have to agree, so this also covers the two disagreements:
     * a 20-byte payload claiming version 2, and a 28-byte one claiming version 1.
     * Neither can be parsed, and both mean a node is sending something this build
     * cannot read, which is exactly what the counter is for.
     */
    data class UnknownVersion(val version: Int) : ParseResult

    /** Wrong length, wrong magic, or otherwise not ours. Dropped. */
    data object NotOurs : ParseResult
}

object RpmCodec {
    /** "RPM1" with 'R' as the lowest byte on the wire. The family, not the version. */
    const val MAGIC = 0x314D5052
    const val VERSION = 1
    const val VERSION_2 = 2
    const val PACKET_BYTES = 20
    const val PACKET_V2_BYTES = 28

    /** The port both ends agree on. A fixed contract value. */
    const val UDP_PORT = 4210

    /**
     * The sensor design window. Values outside it are displayable but must be
     * flagged — see the UI requirements.
     */
    const val PLAUSIBLE_MIN_RPM = 300L
    const val PLAUSIBLE_MAX_RPM = 7500L

    /**
     * Parse the first [len] bytes of [buf].
     *
     * Returns [ParseResult.NotOurs] for any length other than exactly 20 or 28, for a
     * [len] that overruns [buf], and for a wrong magic. Nothing here throws on
     * hostile input — a datagram from anything else on the subnet is an ordinary
     * event, not an error.
     *
     * Framing only. The statistics on a version-2 packet are read as sent and are not
     * checked for self-consistency here: see [IntervalStats.consistentWith].
     */
    fun parse(buf: ByteArray, len: Int): ParseResult {
        if (len > buf.size) return ParseResult.NotOurs
        val expectedVersion = when (len) {
            PACKET_BYTES -> VERSION
            PACKET_V2_BYTES -> VERSION_2
            else -> return ParseResult.NotOurs
        }

        val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) return ParseResult.NotOurs

        val version = b.get().toInt() and 0xFF
        if (version != expectedVersion) return ParseResult.UnknownVersion(version)

        val nodeId = b.get().toInt() and 0xFF
        val seq = b.short.toInt() and 0xFFFF
        val uptimeMs = b.int.toLong() and 0xFFFFFFFFL
        val rpm = b.int.toLong() and 0xFFFFFFFFL
        val rpmPeak = b.int.toLong() and 0xFFFFFFFFL

        return ParseResult.Ok(
            RpmPacket(
                nodeId = nodeId,
                seq = seq,
                uptimeMs = uptimeMs,
                rpm = rpm,
                rpmPeak = rpmPeak,
                interval = if (version < VERSION_2) null else IntervalStats(
                    revCount = b.short.toInt() and 0xFFFF,
                    sdRpmX10 = b.short.toInt() and 0xFFFF,
                    minRpm = (b.short.toInt() and 0xFFFF).toLong(),
                    maxRpm = (b.short.toInt() and 0xFFFF).toLong(),
                ),
            )
        )
    }

    /** True when [rpm] is non-zero and outside the sensor design window. */
    fun isOutOfRange(rpm: Long): Boolean =
        rpm != 0L && (rpm < PLAUSIBLE_MIN_RPM || rpm > PLAUSIBLE_MAX_RPM)

    /**
     * Encode a packet. Used only by tests and by any future host-side tooling —
     * **the app never transmits to a node**, which has no receive path.
     *
     * The length follows the payload: 28 bytes when [RpmPacket.interval] is present,
     * 20 when it is not. [version] defaults to match but is separately overridable, so
     * a test can build the length/version disagreements the parser has to reject.
     */
    fun encode(
        packet: RpmPacket,
        version: Int = if (packet.interval == null) VERSION else VERSION_2,
        magic: Int = MAGIC,
    ): ByteArray {
        val stats = packet.interval
        val b = ByteBuffer
            .allocate(if (stats == null) PACKET_BYTES else PACKET_V2_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic)
            .put((version and 0xFF).toByte())
            .put((packet.nodeId and 0xFF).toByte())
            .putShort((packet.seq and 0xFFFF).toShort())
            .putInt(packet.uptimeMs.toInt())
            .putInt(packet.rpm.toInt())
            .putInt(packet.rpmPeak.toInt())
        if (stats != null) {
            b.putShort((stats.revCount and 0xFFFF).toShort())
                .putShort((stats.sdRpmX10 and 0xFFFF).toShort())
                .putShort((stats.minRpm.toInt() and 0xFFFF).toShort())
                .putShort((stats.maxRpm.toInt() and 0xFFFF).toShort())
        }
        return b.array()
    }
}
