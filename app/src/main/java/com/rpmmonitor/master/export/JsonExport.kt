package com.rpmmonitor.master.export

import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.state.ListenerStats
import com.rpmmonitor.master.state.NodeState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serialising the session to JSON, and the file name it is written under.
 *
 * Free of Android types apart from `org.json`, so the document can be built and
 * asserted on without a device.
 */
object JsonExport {

    /**
     * File name stamp: sortable, no separators that a file system objects to, and in
     * the phone's own time zone — a file named in UTC is hard to match against the
     * moment you remember pressing the button.
     */
    private const val FILE_STAMP = "yyyyMMdd-HHmmss"

    /** Timestamps inside the document, in UTC so two captures can be compared. */
    private const val ISO_STAMP = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    /**
     * The document format version.
     *
     * Bumped whenever a field changes meaning, so a reader can refuse a file it does
     * not understand rather than misinterpreting it.
     */
    const val SCHEMA_VERSION = 1

    fun fileName(wallClockMs: Long): String =
        "rpm-${format(FILE_STAMP, wallClockMs, utc = false)}.json"

    /**
     * Build the whole session as one JSON document.
     *
     * @param nowElapsedMs the monotonic clock at the moment of export, used to turn
     *   each sample's elapsed stamp into a wall-clock one. Both are written: the
     *   elapsed figure is the one the app actually reasons about, and the wall-clock
     *   one is what makes the file comparable with anything else.
     * @param wallClockMs the wall clock at that same moment.
     */
    fun build(
        nodes: Collection<NodeState>,
        stats: ListenerStats,
        sessionUptimeMs: Long?,
        nowElapsedMs: Long,
        wallClockMs: Long,
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("savedAt", format(ISO_STAMP, wallClockMs))
        root.put("savedAtEpochMs", wallClockMs)
        root.put("sessionUptimeMs", sessionUptimeMs ?: JSONObject.NULL)
        root.put("udpPort", RpmCodec.UDP_PORT)

        root.put(
            "listener",
            JSONObject()
                .put("accepted", stats.accepted)
                .put("unknownVersion", stats.unknownVersion)
                .put("lastUnknownVersion", stats.lastUnknownVersion ?: JSONObject.NULL)
                .put("ignored", stats.ignored),
        )

        val nodeArray = JSONArray()
        for (node in nodes.sortedBy { it.nodeId }) {
            nodeArray.put(nodeJson(node, nowElapsedMs, wallClockMs))
        }
        root.put("nodes", nodeArray)

        return root.toString(2)
    }

    private fun nodeJson(node: NodeState, nowElapsedMs: Long, wallClockMs: Long): JSONObject {
        val senders = JSONArray()
        node.senderIps.forEach { senders.put(it) }

        val samples = JSONArray()
        for (sample in node.history) {
            // The elapsed clock is monotonic and the wall clock is not, so the wall
            // time is derived at export rather than recorded per sample: a clock step
            // mid-session would otherwise leave the trace with a discontinuity that
            // never happened.
            samples.put(
                JSONObject()
                    .put("elapsedMs", sample.elapsedMs)
                    .put("epochMs", wallClockMs - (nowElapsedMs - sample.elapsedMs))
                    .put("rpm", sample.rpm),
            )
        }

        return JSONObject()
            .put("nodeId", node.nodeId)
            .put("senderIp", node.senderIp)
            .put("senderIps", senders)
            .put("collision", node.collision)
            .put("freshness", node.freshness.name)
            .put("ageMs", node.ageMs)
            .put("staleAtMs", node.thresholds.staleMs)
            .put("offlineAtMs", node.thresholds.offlineMs)
            .put("observedIntervalMs", node.observedIntervalMs ?: JSONObject.NULL)
            .put("observedRateHz", node.observedRateHz ?: JSONObject.NULL)
            .put("packetsReceived", node.packetsReceived)
            .put("linkLost", node.linkLost)
            .put("windowLossFraction", node.windowLossFraction ?: JSONObject.NULL)
            .put("lifetimeLossFraction", node.lifetimeLossFraction ?: JSONObject.NULL)
            .put("rebootCount", node.rebootCount)
            // Both peaks, and neither is what the dial shows: the readout clamps to
            // the instrument's end stop, while the export is the unclamped truth.
            // The first is the mark a reset rebases, the second the node's own
            // monotonic figure, which nothing here can reset.
            .put("peakRpmSinceReset", node.peakRpm)
            .put("peakRpmReportedByNode", node.last.rpmPeak)
            .put(
                "lastPacket",
                JSONObject()
                    .put("seq", node.last.seq)
                    .put("uptimeMs", node.last.uptimeMs)
                    .put("rpm", node.last.rpm)
                    .put("rpmPeak", node.last.rpmPeak),
            )
            .put("sampleCount", node.history.size)
            .put("samples", samples)
    }

    private fun format(pattern: String, epochMs: Long, utc: Boolean = true): String {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        if (utc) fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }
}
