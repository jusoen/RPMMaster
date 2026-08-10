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
                    "the recorded data and never the figures beneath it.",
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

        Section("Stability: wander and roughness") {
            Note(
                "Two different faults live at two different speeds, so they are two " +
                    "different figures. Wander is the engine speed moving about over " +
                    "tenths of a second: surge, hunting, drift. Roughness is the " +
                    "variation between individual revolutions: combustion, a misfire, a " +
                    "weak cylinder. An engine can hold a rock-steady average while " +
                    "running badly, so one combined number would hide exactly the fault " +
                    "worth finding.",
            )
            Spacer(Modifier.height(4.dp))
            Definition(
                "Which you get",
                "A version-1 node sends one reading per packet, so only the slow half " +
                    "can be measured and it is labelled \"stability\". A version-2 node " +
                    "also sends the statistics of the revolutions inside each reporting " +
                    "interval, and the line splits into \"wander\" and \"roughness\". " +
                    "The Protocol row above says which node you have.",
            )
            Spacer(Modifier.height(6.dp))
            Note(
                "Wander, over the n readings in the window, with x the reading and t " +
                    "its own arrival time in seconds:",
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
                "Roughness, pooled over the intervals in the window, with n[i] " +
                    "revolutions in interval i and sd[i] their standard deviation as " +
                    "the node measured it:",
            )
            Spacer(Modifier.height(2.dp))
            Formula("sigma   sqrt( SUM( (n[i]-1) * sd[i]^2 ) / SUM( n[i]-1 ) )")
            Formula("CoV %   sigma / mean(x) * 100")
            Spacer(Modifier.height(2.dp))
            Note(
                "The weight is n minus 1, not n, because the node sends a sample " +
                    "standard deviation. An interval holding one revolution has no " +
                    "spread and drops out on its own, its weight being zero.",
            )
            Spacer(Modifier.height(6.dp))
            Note(
                "\"wander ±42 rpm · 1.20%\" reads as: over the window on screen, the " +
                    "reading scatters about its own trend by 42 rpm, one standard " +
                    "deviation, and that is 1.20 per cent of the mean. \"roughness " +
                    "±81.1 rpm\" reads the same way, but about the mean of each " +
                    "interval rather than about the trend.",
            )
            Spacer(Modifier.height(4.dp))
            Definition(
                "Why the trend is removed first",
                "A plain standard deviation counts a deliberate change in speed as " +
                    "instability: open the throttle and it climbs while the engine runs " +
                    "perfectly. A straight line is fitted over the window and the " +
                    "spread is measured about that line, so a steady pull measures zero " +
                    "and only the wander is left.",
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
                    "inflates the wander. 5 or 10 seconds reads the wander alone. A " +
                    "minute reads it plus whatever the engine actually did. Roughness " +
                    "is unaffected, being measured inside each interval.",
            )
            Definition(
                "When no figure is given",
                "Under 20 samples in the window there is too little to measure. If the " +
                    "engine stopped anywhere in the window both figures are withheld: a " +
                    "stall is not instability, and a mean dragged toward zero would " +
                    "report a healthy engine as wildly unstable.",
            )
            Definition(
                "When roughness alone is missing",
                "It needs 20 pooled degrees of freedom, which is a handful of intervals " +
                    "at 3000 rpm and most of the window at idle. Below about 600 rpm a " +
                    "revolution cannot always finish inside one reporting interval, so " +
                    "an interval reports none at all and reads as a stall.",
            )
            Definition(
                "At the measurement floor",
                "The node times pulses to a microsecond, which sets a smallest " +
                    "measurable spread. It climbs with the square of engine speed: " +
                    "nothing at idle, about 0.4 rpm at 7500. A roughness reading at or " +
                    "under it is the clock, not the engine, and says so.",
            )
            Definition(
                "Intervals discarded",
                "An interval whose own minimum and maximum do not bracket its own mean " +
                    "is contradicting itself. It is dropped from the roughness figure " +
                    "and counted in amber. The reading is kept regardless, because a " +
                    "fault in the node's statistics should not take the dial with it. " +
                    "A healthy node holds this at zero.",
            )
        }

        Section("What counts as a good figure") {
            Note(
                "There is no universal answer, and the bands below are the shape of one " +
                    "rather than the thing itself. What is good depends on the engine: " +
                    "cylinder count, capacity, state of tune, ignition and fuelling, " +
                    "flywheel mass, whether idle is controlled or simply set, load, and " +
                    "how worn it is. Two examples of the same engine will not agree " +
                    "either.",
            )
            Spacer(Modifier.height(4.dp))
            Note(
                "So log your own. Press Save with the engine running as you want it, " +
                    "warm, at idle and at a steady cruise, and keep the JSON. That " +
                    "baseline beats any published figure, because the useful question " +
                    "is not \"is 3 per cent good\" but \"is it what this engine did last " +
                    "month\". Collect a few and a change stands out long before it is " +
                    "audible.",
            )
            Spacer(Modifier.height(4.dp))
            Definition(
                "At idle",
                "Wander of 2 to 4 per cent is a healthy idle on most engines. Past " +
                    "about 6 per cent something is moving it: a vacuum leak, fuelling, " +
                    "or ignition. Roughness under 2 per cent is good, 3 to 5 is " +
                    "marginal, and above 5 usually means a cylinder is down. A light " +
                    "flywheel raises both without anything being wrong.",
            )
            Definition(
                "Held at a cruise speed",
                "Both figures should fall well under 1 per cent. Idle is the hard case " +
                    "for any engine, so anything that stays as bad at 3000 as it was at " +
                    "idle is not an idle-speed or mixture problem and wants looking at " +
                    "differently.",
            )
            Definition(
                "What roughness can and cannot tell you",
                "A four-stroke fires each cylinder once every two revolutions, so on a " +
                    "multi-cylinder engine every measured revolution already averages " +
                    "several firings. A weak cylinder lifts the figure but cannot be " +
                    "named by it. It tells you one is down, not which one.",
            )
            Definition(
                "Compare like with like",
                "A cold engine is always rougher, and it is still settling for some " +
                    "minutes after it sounds warm. Idle figures also shift with " +
                    "electrical load and with a gearbox in or out of gear. Take the " +
                    "baseline and the comparison under the same conditions, or the " +
                    "difference you measure will be the conditions.",
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
