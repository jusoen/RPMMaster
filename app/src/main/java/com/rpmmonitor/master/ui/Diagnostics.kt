package com.rpmmonitor.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.net.ListenerState
import com.rpmmonitor.master.proto.RpmCodec
import com.rpmmonitor.master.proto.RpmPacket
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.FreshnessThresholds
import com.rpmmonitor.master.state.ListenerStats
import com.rpmmonitor.master.state.NodeState
import com.rpmmonitor.master.ui.theme.InstrumentAmber
import com.rpmmonitor.master.ui.theme.InstrumentGreen
import com.rpmmonitor.master.ui.theme.InstrumentGrey
import com.rpmmonitor.master.ui.theme.InstrumentRed
import com.rpmmonitor.master.ui.theme.RPMMasterTheme

/** Counters, the measured rate, and the hotspot setup the whole system depends on. */
@Composable
fun Diagnostics(
    node: NodeState?,
    stats: ListenerStats,
    listenerState: ListenerState,
    sessionUptimeMs: Long?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Listener") {
            val (text, colour) = when (listenerState) {
                ListenerState.Listening -> "listening on 0.0.0.0:${RpmCodec.UDP_PORT}" to InstrumentGreen
                ListenerState.Stopped -> "stopped" to InstrumentGrey
                is ListenerState.Failed -> "failed: ${listenerState.message}" to InstrumentRed
            }
            RowItem("State", text, colour)
            RowItem("Session uptime", sessionUptimeMs?.let(::formatUptime) ?: "—")
            RowItem("Datagrams accepted", "${stats.accepted}")
            RowItem(
                "Unknown version",
                buildString {
                    append(stats.unknownVersion)
                    stats.lastUnknownVersion?.let { append(" (last saw v$it)") }
                },
                if (stats.unknownVersion > 0) InstrumentAmber else null,
            )
            RowItem("Datagrams ignored", "${stats.ignored}")
        }

        Section("Selected node") {
            if (node == null) {
                Text("No node seen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                RowItem("Node id", "${node.nodeId}")
                RowItem("Sender IP", node.senderIp)
                if (node.collision) {
                    RowItem("ID collision", node.senderIps.joinToString(", "), InstrumentRed)
                    Note(
                        "Two senders are using node_id ${node.nodeId}. Loss statistics are " +
                            "suppressed rather than interleaved — a figure derived from two " +
                            "independent sequence streams would be meaningless."
                    )
                }
                RowItem("Freshness", node.freshness.label(), node.freshness.colour())
                RowItem("Last packet", "${node.ageMs} ms ago")
                RowItem(
                    "Measured rate",
                    node.observedRateHz?.let { "%.2f Hz (%d ms)".format(it, node.observedIntervalMs) } ?: "—",
                )
                RowItem("Freshness thresholds", "stale ${node.thresholds.staleMs} ms · offline ${node.thresholds.offlineMs} ms")
                RowItem("Packets received", "${node.packetsReceived}")
                RowItem("Link loss (lifetime)", "${node.linkLost} · ${formatPercent(node.lifetimeLossFraction)}")
                RowItem("Link loss (30 s window)", formatPercent(node.windowLossFraction))
                RowItem("Reboots", "${node.rebootCount}")
                RowItem("Node uptime", formatUptime(node.last.uptimeMs))
                RowItem("Sequence", "${node.last.seq}")
                RowItem("Peak RPM", "${node.last.rpmPeak}")
            }
        }

        Section("What link loss means") {
            Note(
                "A sequence gap means the datagram was lost on the air or at this " +
                    "receiver. The node increments its sequence number only after a " +
                    "successful hand-off to its network stack, so a node-side drop " +
                    "advances nothing and is invisible here. This figure measures the " +
                    "link, never the node."
            )
        }

        Section("Hotspot setup") {
            Note(
                "The phone is the access point and the DHCP server. Configure the " +
                    "ordinary Android hotspot in system settings — the app deliberately " +
                    "does not use startLocalOnlyHotspot, whose random credentials cannot " +
                    "match the credentials compiled into the node."
            )
            Spacer(Modifier.height(4.dp))
            RowItem("Network name (SSID)", "must match the node's WIFI_SSID")
            RowItem("Password", "must match the node's WIFI_PASS")
            RowItem("Security", "WPA2-PSK (or WPA2/WPA3 transition)")
            RowItem("Band", "2.4 GHz — the node's radio has no 5 GHz", InstrumentAmber)
            Spacer(Modifier.height(4.dp))
            Note(
                "Placeholder credentials in the firmware are rpm-master / changeme. " +
                    "Treat the pair as coordinated configuration: whatever the hotspot " +
                    "is set to must be flashed into the node, or the other way round."
            )
        }

        Section("Expected timings") {
            RowItem("Cold start", "up to 60 s before calling it a failure")
            RowItem("Hotspot off and on", "a few seconds — slower than that is a defect")
            Note(
                "A cold start composes a 15 s join timeout with a retry backoff that " +
                    "doubles to a 30 s cap, so ~45 s is a normal worst case. After a link " +
                    "the node had already joined drops, it resets its backoff and retries " +
                    "at once, so recovery there should be quick."
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title.uppercase(),
            color = InstrumentAmber,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun RowItem(label: String, value: String, valueColour: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            color = valueColour ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
}

// ---- preview -------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 1400)
@Composable
private fun PreviewDiagnostics() = RPMMasterTheme(darkTheme = true) {
    Diagnostics(
        node = NodeState(
            nodeId = 1,
            last = RpmPacket(1, 41234, 3_754_000, 4200, 6100),
            peakRpm = 6100,
            history = emptyList(),
            senderIp = "192.168.43.20",
            senderIps = setOf("192.168.43.20"),
            lastSeenElapsedMs = 0,
            ageMs = 43,
            freshness = Freshness.LIVE,
            thresholds = FreshnessThresholds.forInterval(250),
            observedIntervalMs = 250,
            packetsReceived = 15_012,
            linkLost = 37,
            windowLossFraction = 0.0031,
            rebootCount = 2,
            collision = false,
        ),
        stats = ListenerStats(accepted = 15_012, unknownVersion = 4, lastUnknownVersion = 2, ignored = 88),
        listenerState = ListenerState.Listening,
        sessionUptimeMs = 3_755_000,
    )
}
