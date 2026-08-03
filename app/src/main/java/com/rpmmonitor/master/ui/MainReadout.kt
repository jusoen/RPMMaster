package com.rpmmonitor.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.proto.RpmPacket
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.FreshnessThresholds
import com.rpmmonitor.master.state.NodeState
import com.rpmmonitor.master.ui.theme.InstrumentAmber
import com.rpmmonitor.master.ui.theme.InstrumentCream
import com.rpmmonitor.master.ui.theme.InstrumentGrey
import com.rpmmonitor.master.ui.theme.InstrumentRed
import com.rpmmonitor.master.ui.theme.RPMMasterTheme

/**
 * The dial and the digital value, shown together — the dial is a requirement in its
 * own right and the number is not an alternative to it.
 */
@Composable
fun MainReadout(node: NodeState?, listening: Boolean, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (node == null) {
            NoNode(listening)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "NODE ${node.nodeId}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            StatusPill(node.freshness.label(), node.freshness.colour())
            if (node.collision) StatusPill("ID CLASH", InstrumentRed)
        }

        Spacer(Modifier.height(8.dp))

        // Sized off the narrower screen dimension so portrait and a larger screen
        // both work from one layout.
        val config = LocalConfiguration.current
        val dialWidth = minOf(config.screenWidthDp, config.screenHeightDp).dp - 40.dp
        RetroTachometer(
            rpm = node.last.rpm,
            freshness = node.freshness,
            modifier = Modifier.widthIn(max = dialWidth.coerceAtLeast(160.dp)),
        )

        Spacer(Modifier.height(12.dp))
        DigitalValue(node)
        Spacer(Modifier.height(10.dp))
        PeakAndAge(node)
    }
}

@Composable
private fun DigitalValue(node: NodeState) {
    // A demoted value must still be readable — it is dimmed, not hidden.
    val demote = node.freshness != Freshness.LIVE
    val colour = when {
        node.stalled -> InstrumentAmber
        node.outOfRange -> InstrumentRed
        else -> InstrumentCream
    }.let { if (demote) it.copy(alpha = 0.45f) else it }

    Text(
        text = if (node.stalled) "STALL/0" else node.last.rpm.toString(),
        color = colour,
        style = MaterialTheme.typography.displayLarge,
        // STALL/0 is seven characters where a reading is four, so it has to shrink
        // to stay on one line on a narrow screen.
        fontSize = if (node.stalled) 56.sp else 84.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "RPM",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 8.sp,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    if (node.outOfRange) {
        Spacer(Modifier.height(8.dp))
        Text(
            "OUT OF RANGE — outside the 300–7500 sensor window",
            color = InstrumentRed,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PeakAndAge(node: NodeState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Field("PEAK", "${node.last.rpmPeak}")
        Field("LAST SEEN", if (node.ageMs < 1000) "${node.ageMs} ms" else formatUptime(node.ageMs))
        Field("NODE UPTIME", formatUptime(node.last.uptimeMs))
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.sp)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun NoNode(listening: Boolean) {
    Spacer(Modifier.height(48.dp))
    RetroTachometer(0, Freshness.OFFLINE, Modifier.widthIn(max = 260.dp))
    Spacer(Modifier.height(24.dp))
    Text(
        if (listening) "Listening on UDP 4210" else "Listener stopped",
        color = InstrumentCream,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (listening) {
            "No node has broadcast yet. A cold start can take up to 60 seconds — " +
                "the node's join timeout and retry backoff compose to about 45 s worst case."
        } else {
            "Press Start to open the socket."
        },
        color = InstrumentGrey,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

// ---- previews ------------------------------------------------------------

private fun previewNode(
    rpm: Long,
    freshness: Freshness = Freshness.LIVE,
    collision: Boolean = false,
) = NodeState(
    nodeId = 1,
    last = RpmPacket(nodeId = 1, seq = 1234, uptimeMs = 754_000, rpm = rpm, rpmPeak = 6100),
    senderIp = "192.168.43.20",
    senderIps = setOf("192.168.43.20"),
    lastSeenElapsedMs = 0,
    ageMs = 40,
    freshness = freshness,
    thresholds = FreshnessThresholds.forInterval(100),
    observedIntervalMs = 100,
    packetsReceived = 7_540,
    linkLost = 12,
    windowLossFraction = 0.004,
    rebootCount = 1,
    collision = collision,
)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 720)
@Composable
private fun PreviewLive() = RPMMasterTheme(darkTheme = true) {
    MainReadout(previewNode(4200), listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 720)
@Composable
private fun PreviewStall() = RPMMasterTheme(darkTheme = true) {
    MainReadout(previewNode(0), listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 720)
@Composable
private fun PreviewOutOfRange() = RPMMasterTheme(darkTheme = true) {
    MainReadout(previewNode(9000), listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 720)
@Composable
private fun PreviewOffline() = RPMMasterTheme(darkTheme = true) {
    MainReadout(previewNode(4200, Freshness.OFFLINE), listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 720)
@Composable
private fun PreviewWaiting() = RPMMasterTheme(darkTheme = true) {
    MainReadout(null, listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}
