package com.rpmmonitor.master.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.ui.theme.InstrumentGreen
import com.rpmmonitor.master.ui.theme.InstrumentGrey
import com.rpmmonitor.master.ui.theme.InstrumentRed

/** Colour for a freshness state. Always paired with [label] — never used alone. */
fun Freshness.colour(): Color = when (this) {
    Freshness.LIVE -> InstrumentGreen
    Freshness.STALE -> InstrumentRed
    Freshness.OFFLINE -> InstrumentGrey
}

fun Freshness.label(): String = when (this) {
    Freshness.LIVE -> "LIVE"
    Freshness.STALE -> "STALE"
    Freshness.OFFLINE -> "OFFLINE"
}

/**
 * A pill carrying both the colour and the word. The pairing is a requirement, not a
 * style choice — colour alone is not an accessible state indicator.
 */
@Composable
fun StatusPill(
    text: String,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(colour.copy(alpha = 0.18f), RoundedCornerShape(50))) {
        Text(
            text = text,
            color = colour,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Format milliseconds as a human uptime, e.g. `2h 14m 03s`. */
fun formatUptime(ms: Long): String {
    if (ms < 0) return "—"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, s)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}

/** Format a 0..1 fraction as a percentage, or a dash when it is not known. */
fun formatPercent(fraction: Double?): String =
    if (fraction == null) "—" else "%.1f%%".format(fraction * 100.0)
