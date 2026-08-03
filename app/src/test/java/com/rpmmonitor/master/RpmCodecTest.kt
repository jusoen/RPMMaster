package com.rpmmonitor.master

import com.rpmmonitor.master.proto.ParseResult
import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.proto.RpmPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
