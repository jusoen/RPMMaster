package com.rpmmonitor.master.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wire contract, mirroring `rpm_proto.h` in the slave firmware.
 *
 * 20 bytes, packed, **little-endian** — the sender is a Cortex-M and performs no
 * network-byte-order conversion, so [ByteOrder.LITTLE_ENDIAN] must be set explicitly
 * (Java's default is big-endian). Every field on the wire is unsigned, hence the
 * widening and the masks in [RpmCodec.parse].
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
    /** Live reading. 0 is meaningful (stalled or no signal), not an error. */
    val rpm: Long,
    /** Highest reading since the node booted. Monotonic until reboot. */
    val rpmPeak: Long,
)

/** Outcome of parsing one datagram payload. */
sealed interface ParseResult {
    /** A well-formed version-1 packet. */
    data class Ok(val packet: RpmPacket) : ParseResult

    /**
     * Correct magic and length, but a version this build does not know. Counted and
     * surfaced rather than discarded silently — the field exists so the protocol can
     * evolve, and a v2 node appearing on the network should be visible, not invisible.
     */
    data class UnknownVersion(val version: Int) : ParseResult

    /** Wrong length, wrong magic, or otherwise not ours. Dropped. */
    data object NotOurs : ParseResult
}

object RpmCodec {
    /** "RPM1" with 'R' as the lowest byte on the wire. */
    const val MAGIC = 0x314D5052
    const val VERSION = 1
    const val PACKET_BYTES = 20

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
     * Returns [ParseResult.NotOurs] for any length other than exactly 20, for a
     * [len] that overruns [buf], and for a wrong magic. Nothing here throws on
     * hostile input — a datagram from anything else on the subnet is an ordinary
     * event, not an error.
     */
    fun parse(buf: ByteArray, len: Int): ParseResult {
        if (len != PACKET_BYTES) return ParseResult.NotOurs
        if (len > buf.size) return ParseResult.NotOurs

        val b = ByteBuffer.wrap(buf, 0, PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) return ParseResult.NotOurs

        val version = b.get().toInt() and 0xFF
        if (version != VERSION) return ParseResult.UnknownVersion(version)

        return ParseResult.Ok(
            RpmPacket(
                nodeId = b.get().toInt() and 0xFF,
                seq = b.short.toInt() and 0xFFFF,
                uptimeMs = b.int.toLong() and 0xFFFFFFFFL,
                rpm = b.int.toLong() and 0xFFFFFFFFL,
                rpmPeak = b.int.toLong() and 0xFFFFFFFFL,
            )
        )
    }

    /** True when [rpm] is non-zero and outside the sensor design window. */
    fun isOutOfRange(rpm: Long): Boolean =
        rpm != 0L && (rpm < PLAUSIBLE_MIN_RPM || rpm > PLAUSIBLE_MAX_RPM)

    /**
     * Encode a packet. Used only by tests and by any future host-side tooling —
     * **the app never transmits to a node**, which has no receive path.
     */
    fun encode(
        packet: RpmPacket,
        version: Int = VERSION,
        magic: Int = MAGIC,
    ): ByteArray = ByteBuffer.allocate(PACKET_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(magic)
        .put((version and 0xFF).toByte())
        .put((packet.nodeId and 0xFF).toByte())
        .putShort((packet.seq and 0xFFFF).toShort())
        .putInt(packet.uptimeMs.toInt())
        .putInt(packet.rpm.toInt())
        .putInt(packet.rpmPeak.toInt())
        .array()
}
