package com.rpmmonitor.master

import com.rpmmonitor.master.proto.IntervalStats
import com.rpmmonitor.master.proto.ParseResult
import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.proto.RpmPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RpmCodecTest {

    /**
     * Hand-built rather than round-tripped through [RpmCodec.encode], so a fault in
     * the encoder cannot hide the same fault in the parser. These are the literal
     * bytes the firmware puts on the wire.
     */
    private fun goodBytes(
        version: Int = 1,
        nodeId: Int = 1,
        seq: Int = 0x1234,
        uptimeMs: Long = 0x0000_2710L,
        rpm: Long = 3000L,
        rpmPeak: Long = 5500L,
        magic: Int = RpmCodec.MAGIC,
    ): ByteArray = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(magic)
        .put(version.toByte())
        .put(nodeId.toByte())
        .putShort(seq.toShort())
        .putInt(uptimeMs.toInt())
        .putInt(rpm.toInt())
        .putInt(rpmPeak.toInt())
        .array()

    @Test
    fun `magic is R P M 1 with R lowest on the wire`() {
        val b = goodBytes()
        assertEquals('R'.code.toByte(), b[0])
        assertEquals('P'.code.toByte(), b[1])
        assertEquals('M'.code.toByte(), b[2])
        assertEquals('1'.code.toByte(), b[3])
    }

    @Test
    fun `parses a known good packet`() {
        val r = RpmCodec.parse(goodBytes(), 20)
        assertTrue(r is ParseResult.Ok)
        val p = (r as ParseResult.Ok).packet
        assertEquals(1, p.nodeId)
        assertEquals(0x1234, p.seq)
        assertEquals(10_000L, p.uptimeMs)
        assertEquals(3000L, p.rpm)
        assertEquals(5500L, p.rpmPeak)
    }

    @Test
    fun `every field is treated as unsigned`() {
        // node_id 255, seq 65535, and three u32 fields with the top bit set. A signed
        // read of any of these produces a negative number.
        val r = RpmCodec.parse(
            goodBytes(
                nodeId = 0xFF,
                seq = 0xFFFF,
                uptimeMs = 0xFFFF_FFFFL,
                rpm = 0xFFFF_FFFFL,
                rpmPeak = 0x8000_0000L,
            ),
            20,
        )
        val p = (r as ParseResult.Ok).packet
        assertEquals(255, p.nodeId)
        assertEquals(65535, p.seq)
        assertEquals(4_294_967_295L, p.uptimeMs)
        assertEquals(4_294_967_295L, p.rpm)
        assertEquals(2_147_483_648L, p.rpmPeak)
    }

    @Test
    fun `rpm of zero parses as a real value not an error`() {
        val p = (RpmCodec.parse(goodBytes(rpm = 0), 20) as ParseResult.Ok).packet
        assertEquals(0L, p.rpm)
    }

    @Test
    fun `short datagram is rejected`() {
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(goodBytes(), 19))
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(ByteArray(4), 4))
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(ByteArray(0), 0))
    }

    @Test
    fun `oversized datagram is rejected`() {
        // A 64-byte datagram whose first 20 bytes are a perfectly valid packet must
        // still be dropped: the length is part of the contract.
        val big = ByteArray(64)
        goodBytes().copyInto(big)
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(big, 64))
    }

    @Test
    fun `length overrunning the buffer is rejected rather than thrown`() {
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(ByteArray(8), 20))
    }

    @Test
    fun `bad magic is rejected`() {
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(goodBytes(magic = 0x314D5053), 20))
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(goodBytes(magic = 0), 20))
        // The big-endian spelling of the magic: the classic byte-order mistake.
        assertEquals(ParseResult.NotOurs, RpmCodec.parse(goodBytes(magic = 0x52504D31), 20))
    }

    @Test
    fun `unknown version is distinguished from garbage`() {
        val r = RpmCodec.parse(goodBytes(version = 2), 20)
        assertEquals(ParseResult.UnknownVersion(2), r)
        // Version 0 and 255 are equally "not ours to parse" but equally countable.
        assertEquals(ParseResult.UnknownVersion(0), RpmCodec.parse(goodBytes(version = 0), 20))
        assertEquals(ParseResult.UnknownVersion(255), RpmCodec.parse(goodBytes(version = 255), 20))
    }

    @Test
    fun `garbage of the right length does not crash`() {
        val rnd = java.util.Random(42)
        repeat(2000) {
            val b = ByteArray(20).also { a -> rnd.nextBytes(a) }
            RpmCodec.parse(b, 20)   // must simply not throw
        }
    }

    @Test
    fun `garbage of every length up to 128 does not crash`() {
        val rnd = java.util.Random(7)
        for (len in 0..128) {
            val b = ByteArray(len).also { a -> rnd.nextBytes(a) }
            RpmCodec.parse(b, len)
        }
    }

    @Test
    fun `encode round-trips through parse`() {
        val original = RpmPacket(nodeId = 7, seq = 65535, uptimeMs = 4_294_967_295L, rpm = 7400, rpmPeak = 9000)
        val r = RpmCodec.parse(RpmCodec.encode(original), 20)
        assertEquals(original, (r as ParseResult.Ok).packet)
    }

    /** The 28-byte version-2 layout, hand-built for the same reason as [goodBytes]. */
    private fun v2Bytes(
        version: Int = 2,
        rpm: Long = 3000L,
        revCount: Int = 5,
        sdRpmX10: Int = 1414,
        minRpm: Int = 2900,
        maxRpm: Int = 3100,
        magic: Int = RpmCodec.MAGIC,
    ): ByteArray = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(magic)
        .put(version.toByte())
        .put(1.toByte())
        .putShort(0x1234.toShort())
        .putInt(10_000)
        .putInt(rpm.toInt())
        .putInt(5500)
        .putShort(revCount.toShort())
        .putShort(sdRpmX10.toShort())
        .putShort(minRpm.toShort())
        .putShort(maxRpm.toShort())
        .array()

    @Test
    fun `parses a version 2 packet including the interval statistics`() {
        val p = (RpmCodec.parse(v2Bytes(), 28) as ParseResult.Ok).packet
        // The first twenty bytes must read exactly as they do on a v1 packet.
        assertEquals(1, p.nodeId)
        assertEquals(0x1234, p.seq)
        assertEquals(10_000L, p.uptimeMs)
        assertEquals(3000L, p.rpm)
        assertEquals(5500L, p.rpmPeak)
        val s = p.interval!!
        assertEquals(5, s.revCount)
        assertEquals(1414, s.sdRpmX10)
        assertEquals(141.4, s.sdRpm!!, 1e-9)
        assertEquals(2900L, s.minRpm)
        assertEquals(3100L, s.maxRpm)
    }

    @Test
    fun `a version 1 packet carries no interval statistics`() {
        assertNull((RpmCodec.parse(goodBytes(), 20) as ParseResult.Ok).packet.interval)
    }

    @Test
    fun `the new fields are treated as unsigned`() {
        // 0xFFFF in every u16. A signed read of any of them gives -1.
        val s = (RpmCodec.parse(
            v2Bytes(revCount = 0xFFFF, sdRpmX10 = 0xFFFF, minRpm = 0xFFFF, maxRpm = 0xFFFF),
            28,
        ) as ParseResult.Ok).packet.interval!!
        assertEquals(65535, s.revCount)
        assertEquals(65535, s.sdRpmX10)
        assertEquals(65535L, s.minRpm)
        assertEquals(65535L, s.maxRpm)
    }

    @Test
    fun `length and version must agree in both directions`() {
        // 20 bytes claiming v2 is already asserted above. The mirror case is a 28-byte
        // payload claiming v1: countable, because it means a node is sending something
        // this build cannot read, but not parseable.
        assertEquals(ParseResult.UnknownVersion(1), RpmCodec.parse(v2Bytes(version = 1), 28))
        assertEquals(ParseResult.UnknownVersion(3), RpmCodec.parse(v2Bytes(version = 3), 28))
    }

    @Test
    fun `a length between the two is not ours`() {
        val b = ByteArray(28)
        v2Bytes().copyInto(b)
        for (len in listOf(21, 24, 27, 29, 36)) {
            assertEquals(ParseResult.NotOurs, RpmCodec.parse(ByteArray(64).also { b.copyInto(it) }, len))
        }
    }

    @Test
    fun `sigma is withheld rather than reported as zero when there was nothing to measure`() {
        // A one-revolution interval has no spread, which is not the same as a spread of
        // zero. Reporting 0 here would read as a perfectly steady engine.
        val one = (RpmCodec.parse(v2Bytes(revCount = 1, sdRpmX10 = 0, minRpm = 3000, maxRpm = 3000), 28)
            as ParseResult.Ok).packet.interval!!
        assertNull(one.sdRpm)
        val none = (RpmCodec.parse(v2Bytes(rpm = 0, revCount = 0, sdRpmX10 = 0, minRpm = 0, maxRpm = 0), 28)
            as ParseResult.Ok).packet.interval!!
        assertNull(none.sdRpm)
        // Two is the point at which a sample standard deviation exists.
        val two = (RpmCodec.parse(v2Bytes(revCount = 2), 28) as ParseResult.Ok).packet.interval!!
        assertEquals(141.4, two.sdRpm!!, 1e-9)
    }

    @Test
    fun `interval consistency brackets the mean`() {
        val s = IntervalStats(revCount = 5, sdRpmX10 = 1414, minRpm = 2900, maxRpm = 3100)
        assertTrue(s.consistentWith(3000))
        assertTrue(s.consistentWith(2900))   // the bounds are inclusive
        assertTrue(s.consistentWith(3100))
        assertFalse(s.consistentWith(2899))
        assertFalse(s.consistentWith(3101))
        // An inverted pair cannot bracket anything, so it can never be consistent.
        assertFalse(IntervalStats(5, 0, 3100, 2900).consistentWith(3000))
    }

    @Test
    fun `an empty interval must be zero throughout`() {
        assertTrue(IntervalStats(0, 0, 0, 0).consistentWith(0))
        // No revolutions but a non-zero reading is a contradiction, in either field.
        assertFalse(IntervalStats(0, 0, 0, 0).consistentWith(3000))
        assertFalse(IntervalStats(0, 0, 2900, 3100).consistentWith(0))
        assertFalse(IntervalStats(0, 120, 0, 0).consistentWith(0))
    }

    @Test
    fun `a version 2 packet is not parsed against the statistics it carries`() {
        // Framing only: an impossible interval still yields a usable reading, because
        // losing the dial over a fault in a subsidiary figure is the worse failure.
        val p = (RpmCodec.parse(v2Bytes(rpm = 3000, minRpm = 4000, maxRpm = 100), 28)
            as ParseResult.Ok).packet
        assertEquals(3000L, p.rpm)
        assertFalse(p.interval!!.consistentWith(p.rpm))
    }

    @Test
    fun `version 2 encode round-trips through parse`() {
        val original = RpmPacket(
            nodeId = 7, seq = 65535, uptimeMs = 4_294_967_295L, rpm = 7400, rpmPeak = 9000,
            interval = IntervalStats(revCount = 65535, sdRpmX10 = 65535, minRpm = 65535, maxRpm = 65535),
        )
        val bytes = RpmCodec.encode(original)
        assertEquals(28, bytes.size)
        assertEquals(original, (RpmCodec.parse(bytes, 28) as ParseResult.Ok).packet)
    }

    @Test
    fun `garbage of the v2 length does not crash`() {
        val rnd = java.util.Random(1963)
        repeat(2000) {
            val b = ByteArray(28).also { a -> rnd.nextBytes(a) }
            RpmCodec.parse(b, 28)
        }
    }

    @Test
    fun `out of range flags only implausible non-zero values`() {
        assertFalse(RpmCodec.isOutOfRange(0))       // stall, not out of range
        assertFalse(RpmCodec.isOutOfRange(300))
        assertFalse(RpmCodec.isOutOfRange(3000))
        assertFalse(RpmCodec.isOutOfRange(7500))
        assertTrue(RpmCodec.isOutOfRange(299))
        assertTrue(RpmCodec.isOutOfRange(7501))
        assertTrue(RpmCodec.isOutOfRange(9000))
    }
}
