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
                RowItem(
                    "Protocol",
                    if (node.reportsIntervalStats) "v2 — 28 bytes, per-interval statistics"
                    else "v1 — 20 bytes, reading only",
                )
                RowItem("Packets received", "${node.packetsReceived}")
                // The counter is the same arithmetic either way. What it is evidence
                // of is not, so the label follows the version rather than asserting
                // the v1 meaning at a v2 node.
                val lossLabel = if (node.reportsIntervalStats) "Intervals missed" else "Link loss"
                RowItem("$lossLabel (lifetime)", "${node.linkLost} · ${formatPercent(node.lifetimeLossFraction)}")
                RowItem("$lossLabel (30 s window)", formatPercent(node.windowLossFraction))
                RowItem("Reboots", "${node.rebootCount}")
                RowItem("Node uptime", formatUptime(node.last.uptimeMs))
                RowItem("Sequence", "${node.last.seq}")
                RowItem("Peak RPM", "${node.last.rpmPeak}")
            }
        }

        Section(if (node?.reportsIntervalStats == true) "What a missed interval means" else "What link loss means") {
            if (node?.reportsIntervalStats == true) {
                Note(
                    "A version-2 node advances its sequence number once per reporting " +
                        "interval, whether or not the datagram reached its network " +
                        "stack, because the interval has been consumed either way. A gap " +
                        "therefore means the interval never arrived, and cannot separate " +
                        "loss on the air from a send that failed on the node. The " +
                        "readings themselves are unaffected: each interval stands alone, " +
                        "so a missed one costs that interval and corrupts nothing."
                )
            } else {
                Note(
                    "A sequence gap means the datagram was lost on the air or at this " +
                        "receiver. A version-1 node increments its sequence number only " +
                        "after a successful hand-off to its network stack, so a node-side " +
                        "drop advances nothing and is invisible here. This figure " +
                        "measures the link, never the node."
                )
            }
        }

        Section("Reading the dial") {
            Definition(
                "Scale",
                "The face runs 0 to 7000 rpm, with the redline from 5500 to the end " +
                    "stop. Nothing on the readout shows more than that: above 7000 the " +
                    "needle pins, the digital value holds at 7000 and a PEGGED banner " +
                    "appears. The figure the node actually sent is above as Peak RPM, " +
                    "and in the JSON export.",
            )
            Definition(
                "Peak marker",
                "The amber blade on the scale is the highest reading seen since the " +
                    "node was first heard, its last reboot, or the last time you tapped " +
                    "PEAK. Tapping PEAK rebases it on the current reading. The node " +
                    "cannot be told to reset its own peak, because the protocol is one " +
                    "way, so the mark is kept here instead.",
            )
            Definition(
                "The needle when a node goes quiet",
                "A stale or offline node holds its last angle rather than dropping to " +
                    "zero, so a stopped engine and a lost node never look the same.",
            )
        }

        Section("Reading the graph") {
            Definition(
                "The two traces",
                "Solid orange is the reading as received. Dashed green is an " +
                    "exponential average of it, thinner and dashed so the two stay " +
                    "apart where they coincide.",
            )
            Definition(
                "Average alpha",
                "How much weight each new reading carries in that average, from 0.02 " +
                    "to 1.00. At 1.00 the average is the raw trace. Lower values smooth " +
                    "harder and lag further behind. It changes the picture only, never " +
                    "the recorded data and never the stability figure.",
            )
            Definition(
                "Time span",
                "Tap the figure under the axis to cycle 5, 10, 30 and 60 seconds. A " +
                    "full 60 seconds is always kept, so narrowing and widening again " +
                    "loses nothing. The newest reading is at the right edge at every " +
                    "span, and a node that goes quiet drains off the left.",
            )
            Definition(
                "Full scale",
                "The top of the rpm axis. An engine that never passes 3000 wastes more " +
                    "than half the plot at 7000, so this trades headroom for " +
                    "resolution. The dial's own face does not change.",
            )
            Definition(
                "Tap the plot to zoom",
                "Zooms the rpm axis to the average at that moment, plus or minus 500, " +
                    "then 1000, then back to full scale. The centre is taken at the tap " +
                    "and does not follow the engine afterwards. A band that tracked the " +
                    "average would slide the scale under the trace and the line would " +
                    "never appear to move.",
            )
            Definition(
                "Data rate",
                "Packets arriving per second, not anything the engine is doing. It " +
                    "measures the air link and this receiver.",
            )
            Definition(
                "Breaks in the line",
                "A gap wider than about five packets is drawn as a break rather than a " +
                    "straight line, so nothing is joined across data that never arrived.",
            )
        }

        Section("Stability") {
            Note(
                "Over the n readings in the window, with x the reading and t its own " +
                    "arrival time in seconds:",
            )
            Spacer(Modifier.height(2.dp))
            Formula("trend   x(t) = a + b*t          fitted by least squares")
            Formula("sigma   sqrt( SUM( (x[i] - (a + b*t[i]))^2 ) / (n - 2) )")
            Formula("CoV %   sigma / mean(x) * 100")
            Spacer(Modifier.height(2.dp))
            Note(
                "t is each reading's own arrival time rather than its position in the " +
                    "list, so a dropped packet does not bend the line. The divisor is " +
                    "n minus 2 because two parameters, a and b, were fitted before the " +
                    "residuals were taken.",
            )
            Spacer(Modifier.height(6.dp))
            Note(
                "\"±42 rpm · 1.20%\" reads as: over the window on screen, the reading " +
                    "scatters about its own trend by 42 rpm, one standard deviation, " +
                    "and that is 1.20 per cent of the mean.",
            )
            Spacer(Modifier.height(4.dp))
            Definition(
                "Why the trend is removed first",
                "A plain standard deviation counts a deliberate change in speed as " +
                    "instability: open the throttle and it climbs while the engine runs " +
                    "perfectly. A straight line is fitted over the window and the " +
                    "spread is measured about that line, so a steady pull measures zero " +
                    "and only the roughness is left.",
            )
            Definition(
                "Why a percentage as well",
                "40 rpm of wander is a serious fault at idle and nothing at 6000 rpm. " +
                    "The percentage is the figure to compare across speeds.",
            )
            Definition(
                "Keep the window short",
                "A straight line removes a ramp exactly, but not a curve. Over 60 " +
                    "seconds a real change in engine speed stays in the residual and " +
                    "inflates the figure. 5 or 10 seconds reads roughness. A minute " +
                    "reads roughness plus whatever the engine actually did.",
            )
            Definition(
                "When no figure is given",
                "Under 20 samples in the window there is too little to measure, which a " +
                    "5 second window at the slowest node cadence will hit. If the " +
                    "engine stopped anywhere in the window the figure is withheld too: " +
                    "a stall is not roughness, and a mean dragged toward zero would " +
                    "report a healthy engine as wildly unstable.",
            )
            Definition(
                "What it cannot see",
                "One already-averaged reading arrives per packet, at best every 100 " +
                    "ms, so nothing faster than about 5 Hz survives to be measured. " +
                    "This is a measure of wander, surge and hunting. Cycle-to-cycle " +
                    "combustion variation would need the node to report statistics per " +
                    "revolution, not a faster packet rate.",
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

/**
 * A term and what it means.
 *
 * [RowItem] puts its value in a right-aligned monospace column, which suits a figure
 * and not a sentence, so the glossary entries stack their prose under the term
 * instead.
 */
@Composable
private fun Definition(term: String, text: String) {
    Spacer(Modifier.height(2.dp))
    Text(
        term,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
    Note(text)
}

/**
 * One line of maths.
 *
 * Monospace so the three lines' labels and expressions align, and written in plain
 * characters rather than typeset symbols: a combining circumflex or a subscript that
 * a device lacks a glyph for renders as a blank box, which is worse than `x[i]`.
 */
@Composable
private fun Formula(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
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
