package com.rpmmonitor.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.proto.RpmPacket
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.FreshnessThresholds
import com.rpmmonitor.master.state.NodeState
import com.rpmmonitor.master.ui.theme.InstrumentAmber
import com.rpmmonitor.master.ui.theme.InstrumentRed
import com.rpmmonitor.master.ui.theme.RPMMasterTheme

/**
 * Picks which node the main readout follows.
 *
 * Only reachable once a second `node_id` has been seen — with one node there is
 * nothing to choose and the tab is not offered.
 */
@Composable
fun NodeList(
    nodes: List<NodeState>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(nodes, key = { it.nodeId }) { node ->
            NodeRow(node, node.nodeId == selectedId, onSelect)
        }
    }
}

@Composable
private fun NodeRow(node: NodeState, selected: Boolean, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .then(
                if (selected) Modifier.border(2.dp, InstrumentAmber, shape) else Modifier
            )
            .clickable { onSelect(node.nodeId) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "NODE ${node.nodeId}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                node.senderIp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            if (node.collision) {
                Text(
                    "two senders: ${node.senderIps.joinToString(", ")}",
                    color = InstrumentRed,
                    fontSize = 11.sp,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${node.last.rpm}",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            StatusPill(node.freshness.label(), node.freshness.colour())
        }
    }
}

// ---- preview -------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C)
@Composable
private fun PreviewNodeList() = RPMMasterTheme(darkTheme = true) {
    fun n(id: Int, rpm: Long, f: Freshness, ip: String, collision: Boolean = false) = NodeState(
        nodeId = id,
        last = RpmPacket(id, 100, 60_000, rpm, 6100),
        peakRpm = 6100,
        history = emptyList(),
        senderIp = ip,
        senderIps = if (collision) setOf(ip, "192.168.43.99") else setOf(ip),
        lastSeenElapsedMs = 0,
        ageMs = 30,
        freshness = f,
        thresholds = FreshnessThresholds.forInterval(100),
        observedIntervalMs = 100,
        packetsReceived = 500,
        linkLost = 2,
        windowLossFraction = 0.004,
        rebootCount = 0,
        collision = collision,
    )
    NodeList(
        nodes = listOf(
            n(1, 4200, Freshness.LIVE, "192.168.43.20"),
            n(2, 0, Freshness.STALE, "192.168.43.21"),
            n(3, 3100, Freshness.OFFLINE, "192.168.43.22", collision = true),
        ),
        selectedId = 1,
        onSelect = {},
    )
}
