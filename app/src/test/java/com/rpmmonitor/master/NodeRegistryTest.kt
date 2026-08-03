package com.rpmmonitor.master

import com.rpmmonitor.master.proto.RpmPacket
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.FreshnessThresholds
import com.rpmmonitor.master.state.NodeRegistry
import com.rpmmonitor.master.state.NodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRegistryTest {

    private val reg = NodeRegistry()
    private var now = 1_000L

    private fun node(id: Int = 1): NodeState = reg.nodes.value.getValue(id)

    /** Feed one packet, advancing the fake clock by [advanceMs] first. */
    private fun send(
        seq: Int,
        rpm: Long = 3000,
        uptimeMs: Long = 0,
        nodeId: Int = 1,
        ip: String = "192.168.43.20",
        advanceMs: Long = 100,
        peak: Long = 5000,
    ) {
        now += advanceMs
        reg.onPacket(RpmPacket(nodeId, seq, uptimeMs, rpm, peak), ip, now)
    }

    /** A clean run of [count] packets at [intervalMs], starting from seq [from]. */
    private fun stream(count: Int, intervalMs: Long = 100, from: Int = 0, nodeId: Int = 1, ip: String = "192.168.43.20") {
        var uptime = from * intervalMs
        for (i in 0 until count) {
            send(seq = (from + i) and 0xFFFF, uptimeMs = uptime, nodeId = nodeId, ip = ip, advanceMs = intervalMs)
            uptime += intervalMs
        }
    }

    // ---- basic bookkeeping ------------------------------------------------

    @Test
    fun `a node appears on its first packet`() {
        assertTrue(reg.nodes.value.isEmpty())
        send(seq = 0)
        assertEquals(setOf(1), reg.nodes.value.keys)
        assertEquals(1L, node().packetsReceived)
        assertEquals(0L, node().linkLost)
        assertEquals("192.168.43.20", node().senderIp)
    }

    @Test
    fun `nodes are kept apart by node_id`() {
        stream(5, nodeId = 1)
        stream(5, nodeId = 2, ip = "192.168.43.21")
        assertEquals(setOf(1, 2), reg.nodes.value.keys)
        assertEquals(5L, reg.nodes.value.getValue(1).packetsReceived)
        assertEquals(5L, reg.nodes.value.getValue(2).packetsReceived)
    }

    @Test
    fun `listener stats count each outcome separately`() {
        send(seq = 0)
        reg.onUnknownVersion(2)
        reg.onUnknownVersion(2)
        reg.onIgnored()
        val s = reg.stats.value
        assertEquals(1L, s.accepted)
        assertEquals(2L, s.unknownVersion)
        assertEquals(2, s.lastUnknownVersion)
        assertEquals(1L, s.ignored)
    }

    // ---- loss maths -------------------------------------------------------

    @Test
    fun `consecutive sequence numbers lose nothing`() {
        stream(50)
        assertEquals(50L, node().packetsReceived)
        assertEquals(0L, node().linkLost)
        assertEquals(0.0, node().lifetimeLossFraction!!, 1e-9)
    }

    @Test
    fun `a gap counts the missing packets`() {
        send(seq = 0, uptimeMs = 0)
        send(seq = 4, uptimeMs = 400)   // 1, 2 and 3 never arrived
        assertEquals(3L, node().linkLost)
        assertEquals(2L, node().packetsReceived)
    }

    @Test
    fun `the u16 wrap is not counted as catastrophic loss`() {
        send(seq = 65535, uptimeMs = 0)
        send(seq = 0, uptimeMs = 100)
        assertEquals(0L, node().linkLost)
        // And a gap across the wrap counts only the gap.
        send(seq = 3, uptimeMs = 200)
        assertEquals(2L, node().linkLost)
    }

    @Test
    fun `a duplicate sequence number adds no loss`() {
        send(seq = 5, uptimeMs = 500)
        send(seq = 5, uptimeMs = 500)
        assertEquals(0L, node().linkLost)
    }

    @Test
    fun `window loss fraction needs enough samples before it reports`() {
        stream(10)
        assertNull("too few samples to quote a percentage", node().windowLossFraction)
        stream(30, from = 10)
        assertNotNull(node().windowLossFraction)
        assertEquals(0.0, node().windowLossFraction!!, 1e-9)
    }

    @Test
    fun `window loss fraction reflects a lossy stretch`() {
        // 40 arrivals, every other seq missing: 40 received, 40 lost.
        var seq = 0
        var uptime = 0L
        repeat(40) {
            send(seq = seq and 0xFFFF, uptimeMs = uptime)
            seq += 2
            uptime += 200
        }
        assertEquals(0.5, node().windowLossFraction!!, 0.02)
    }

    // ---- reboot -----------------------------------------------------------

    @Test
    fun `a seq reset with an uptime drop is a reboot and resets the statistics`() {
        send(seq = 0, uptimeMs = 0)
        send(seq = 9, uptimeMs = 900)      // 8 lost
        assertEquals(8L, node().linkLost)
        assertEquals(0, node().rebootCount)

        send(seq = 0, uptimeMs = 30)       // restarted
        assertEquals(1, node().rebootCount)
        assertEquals(0L, node().linkLost)
        assertEquals(1L, node().packetsReceived)
    }

    @Test
    fun `the reboot count survives the reset it triggers`() {
        send(seq = 0, uptimeMs = 0)
        send(seq = 5, uptimeMs = 500)
        send(seq = 0, uptimeMs = 10)
        send(seq = 5, uptimeMs = 500)
        send(seq = 0, uptimeMs = 10)
        assertEquals(2, node().rebootCount)
        assertEquals(1L, node().packetsReceived)
    }

    @Test
    fun `an uptime drop alone at a clean seq step is not a reboot`() {
        // Guards the case where the two conditions are not required together: an
        // uptime wrap at ~49.7 days arrives with seq continuing cleanly.
        send(seq = 100, uptimeMs = 4_294_967_200L)
        send(seq = 101, uptimeMs = 96L)
        assertEquals(0, node().rebootCount)
    }

    @Test
    fun `a plain gap with rising uptime is loss not a reboot`() {
        send(seq = 0, uptimeMs = 0)
        send(seq = 300, uptimeMs = 30_000)
        assertEquals(0, node().rebootCount)
        assertEquals(299L, node().linkLost)
    }

    // ---- observed rate ----------------------------------------------------

    @Test
    fun `observed interval tracks a 100ms node`() {
        stream(20, intervalMs = 100)
        assertEquals(100L, node().observedIntervalMs)
        assertEquals(10.0, node().observedRateHz!!, 0.2)
    }

    @Test
    fun `observed interval tracks a 250ms node`() {
        stream(20, intervalMs = 250)
        assertEquals(250L, node().observedIntervalMs)
    }

    @Test
    fun `observed interval tracks a 750ms node`() {
        stream(20, intervalMs = 750)
        assertEquals(750L, node().observedIntervalMs)
    }

    @Test
    fun `a lost packet does not halve the measured rate`() {
        // The gap spans two packet slots, so it must be divided by the seq delta.
        stream(12, intervalMs = 100)
        now += 200
        reg.onPacket(RpmPacket(1, 13, 1300, 3000, 5000), "192.168.43.20", now)
        assertEquals(100L, node().observedIntervalMs)
    }

    @Test
    fun `the median ignores a single outlying interval`() {
        stream(12, intervalMs = 100)
        now += 1500
        reg.onPacket(RpmPacket(1, 12, 1200, 3000, 5000), "192.168.43.20", now)
        stream(6, intervalMs = 100, from = 13)
        assertEquals(100L, node().observedIntervalMs)
    }

    @Test
    fun `observed interval is null until enough samples exist`() {
        send(seq = 0, uptimeMs = 0)
        send(seq = 1, uptimeMs = 100)
        assertNull(node().observedIntervalMs)
    }

    // ---- freshness --------------------------------------------------------

    @Test
    fun `a 100ms node does not flap`() {
        stream(20, intervalMs = 100)
        assertEquals(Freshness.LIVE, node().freshness)
        // A whole second of silence is nine missed packets at this rate, but the
        // floor keeps it live rather than flickering on ordinary loss.
        reg.evaluateFreshness(now + 900)
        assertEquals(Freshness.LIVE, node().freshness)
    }

    @Test
    fun `a 250ms node does not flap`() {
        // This is the A.1 case, and the one the original 500ms boundary got wrong:
        // two missed packets at 250ms is 750ms, which must still read as live.
        stream(20, intervalMs = 250)
        reg.evaluateFreshness(now + 750)
        assertEquals(Freshness.LIVE, node().freshness)
    }

    @Test
    fun `a 750ms node does not flap`() {
        stream(20, intervalMs = 750)
        reg.evaluateFreshness(now + 1_500)
        assertEquals(Freshness.LIVE, node().freshness)
        assertEquals(3_000L, node().thresholds.staleMs)
    }

    @Test
    fun `silence walks live to stale to offline`() {
        stream(20, intervalMs = 100)
        val t = node().thresholds
        assertEquals(Freshness.LIVE, node().freshness)

        reg.evaluateFreshness(now + t.staleMs)
        assertEquals(Freshness.STALE, node().freshness)

        reg.evaluateFreshness(now + t.offlineMs)
        assertEquals(Freshness.OFFLINE, node().freshness)

        // And a packet brings it straight back.
        send(seq = 21, uptimeMs = 100_000, advanceMs = 10)
        assertEquals(Freshness.LIVE, node().freshness)
    }

    @Test
    fun `thresholds are floored and capped`() {
        // A very fast node still gets an absolute floor.
        assertEquals(
            FreshnessThresholds(1_000L, 5_000L),
            FreshnessThresholds.forInterval(50L),
        )
        // A very slow one is capped rather than needing half a minute to go offline.
        assertEquals(
            FreshnessThresholds(5_000L, 20_000L),
            FreshnessThresholds.forInterval(5_000L),
        )
        // The 750ms build lands between the two.
        assertEquals(
            FreshnessThresholds(3_000L, 15_000L),
            FreshnessThresholds.forInterval(750L),
        )
    }

    @Test
    fun `freshness before any rate is known uses the assumed interval`() {
        send(seq = 0, uptimeMs = 0)
        assertNull(node().observedIntervalMs)
        assertEquals(FreshnessThresholds.forInterval(null), node().thresholds)
        reg.evaluateFreshness(now + 6_000)
        assertEquals(Freshness.OFFLINE, node().freshness)
    }

    @Test
    fun `evaluating freshness with no nodes is harmless`() {
        reg.evaluateFreshness(now + 10_000)
        assertTrue(reg.nodes.value.isEmpty())
    }

    // ---- collision --------------------------------------------------------

    @Test
    fun `two senders sharing a node_id are flagged and not merged`() {
        stream(10, from = 0, ip = "192.168.43.20")
        assertFalse(node().collision)
        val lostBefore = node().linkLost

        // A second box using the same id, with its own unrelated seq stream.
        send(seq = 40_000, uptimeMs = 900_000, ip = "192.168.43.99")
        assertTrue(node().collision)
        assertEquals(setOf("192.168.43.20", "192.168.43.99"), node().senderIps)
        // The 40000-packet "gap" between the two streams is not booked as loss.
        assertEquals(lostBefore, node().linkLost)
        assertNull("a merged loss figure would be a lie", node().windowLossFraction)
    }

    @Test
    fun `collision stays flagged once seen`() {
        send(seq = 0, uptimeMs = 0, ip = "10.0.0.1")
        send(seq = 1, uptimeMs = 100, ip = "10.0.0.2")
        send(seq = 2, uptimeMs = 200, ip = "10.0.0.1")
        assertTrue(node().collision)
    }

    // ---- reading semantics -----------------------------------------------

    @Test
    fun `zero rpm reads as stalled not as out of range`() {
        send(seq = 0, rpm = 0)
        assertTrue(node().stalled)
        assertFalse(node().outOfRange)
    }

    @Test
    fun `an implausible reading is flagged`() {
        send(seq = 0, rpm = 9000)
        assertTrue(node().outOfRange)
        assertFalse(node().stalled)
    }

    @Test
    fun `clear removes every node and every counter`() {
        stream(10)
        reg.onIgnored()
        reg.clear()
        assertTrue(reg.nodes.value.isEmpty())
        assertEquals(0L, reg.stats.value.accepted)
        assertEquals(0L, reg.stats.value.ignored)
    }
}
