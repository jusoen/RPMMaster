package com.rpmmonitor.master.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.proto.RpmPacket
import com.rpmmonitor.master.state.Freshness
import com.rpmmonitor.master.state.FreshnessThresholds
import com.rpmmonitor.master.state.NodeRegistry
import com.rpmmonitor.master.state.NodeState
import com.rpmmonitor.master.state.RpmSample
import com.rpmmonitor.master.ui.theme.InstrumentAmber
import com.rpmmonitor.master.ui.theme.InstrumentCream
import com.rpmmonitor.master.ui.theme.InstrumentGreen
import com.rpmmonitor.master.ui.theme.InstrumentGrey
import com.rpmmonitor.master.ui.theme.RPMMasterTheme
import kotlin.math.roundToInt

/**
 * A strip chart of the recent readings: newest at the right, scrolling left as
 * packets arrive.
 *
 * The vertical scale is the dial's, 0 to [TACH_MAX_RPM], with the same redline band.
 * The trace and the needle are two views of one instrument, so a reading above the
 * end stop flat-tops here exactly as it pins there.
 *
 * The x axis is the master's monotonic clock, not the node's `uptime_ms`, so a reboot
 * does not send the trace backwards. It is anchored on *now* rather than on the newest
 * sample, which is what makes a node that has gone quiet visibly drain off the left
 * edge instead of freezing.
 */
@Composable
fun RpmGraph(
    node: NodeState?,
    listening: Boolean,
    modifier: Modifier = Modifier,
    onResetGraph: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (node == null) {
            Spacer(Modifier.height(48.dp))
            Text(
                if (listening) "Nothing to plot yet" else "Listener stopped",
                color = InstrumentCream,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (listening) "The trace starts with the first packet." else "Press Start to open the socket.",
                color = InstrumentGrey,
                fontSize = 13.sp,
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "NODE ${node.nodeId}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Clears the trace only. Nothing derived from the packets — the
                // counters, the peak, the measured rate — is touched, so this cannot
                // be mistaken for a way to restart the session.
                // Default metrics throughout, so it is the same size as Save and Stop
                // in the bar above rather than a smaller variant of them.
                OutlinedButton(
                    onClick = onResetGraph,
                    enabled = node.history.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InstrumentAmber),
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                }
                StatusPill(node.freshness.label(), node.freshness.colour())
            }
        }

        Spacer(Modifier.height(12.dp))

        // Held in the UI, not in the registry, and applied to the whole retained
        // history on every redraw. A filter running in the accumulator would only
        // affect samples taken after a change, so moving the slider would leave a
        // step in the line where the old smoothing met the new.
        var alpha by rememberSaveable { mutableFloatStateOf(DEFAULT_ALPHA) }

        // The visible span, which is a view setting only: the registry keeps its full
        // sixty seconds whatever this says, so widening it again costs nothing and
        // loses nothing. Every span is anchored on now, so the freshest data is on
        // screen at all three.
        var windowMs by rememberSaveable { mutableLongStateOf(WINDOW_CHOICES.last()) }

        // Vertical zoom, and the centre it was taken about. The centre is captured at
        // the tap and then left alone: a band that followed the average would slide
        // the scale under the trace, and a trace that never leaves the middle of the
        // plot shows nothing.
        var zoom by rememberSaveable { mutableIntStateOf(0) }
        var zoomCentre by rememberSaveable { mutableFloatStateOf(0f) }

        val measurer = rememberTextMeasurer()
        // Anchored on the snapshot's own idea of now: the freshness timer republishes
        // every 250 ms, so this advances whether or not packets are still arriving.
        val nowMs = node.lastSeenElapsedMs + node.ageMs

        // A gap wider than a few packet slots is a dropout, not a slope. Falls back to
        // a fixed budget until a rate has been measured.
        val gapMs = (node.observedIntervalMs?.times(5) ?: 2_000L).coerceAtLeast(500L)
        val raw = remember(node.history) { filterSeries(node.history, null, gapMs) }
        val averaged = remember(node.history, alpha) { filterSeries(node.history, alpha, gapMs) }
        val view = viewRange(zoom, zoomCentre)

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // Takes whatever the caption below it does not, so the plot is as
                // tall as the screen allows rather than a fixed box with dead space
                // under it.
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                // No ripple: it would flash across the whole plot and obscure the very
                // thing the tap is meant to reveal. The scale changing is the feedback.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Zoom the rpm axis",
                ) {
                    val next = (zoom + 1) % ZOOM_BANDS.size
                    // Re-centred on each tap that enters a zoom, on the average as it
                    // stands at that moment. Widening a stale centre could leave the
                    // trace outside the band it was supposed to be framing.
                    if (next != 0) zoomCentre = averaged.lastOrNull() ?: node.last.rpm.toFloat()
                    zoom = next
                }
                .padding(start = 8.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawGrid(measurer, view)
                // Raw first, average over the top: the smoothed line is the one being
                // read against the scale, and the raw trace is the evidence behind it.
                drawTrace(node.history, raw, nowMs, windowMs, gapMs, view, averaged = false)
                drawTrace(node.history, averaged, nowMs, windowMs, gapMs, view, averaged = true)
            }

            // Read out here: inside the inner Box these names resolve to its own
            // scope, not to the constraints this one was measured with.
            val axisInset = maxWidth * GUTTER_FRACTION
            val footerHeight = maxHeight * FOOTER_FRACTION

            // Inset by the same gutter the canvas reserves for the rpm labels, so
            // everything in here is centred on the axis rather than on the plot.
            Box(Modifier.fillMaxSize().padding(start = axisInset)) {
                // The live value as a watermark, sitting on the zero line: readings
                // live above roughly 900 even at idle, so the bottom of the plot is
                // the one band the traces reliably leave clear. Faint enough to read
                // them through, and clamped like every other view of the reading —
                // the graph and the dial cannot show past the end stop, so nor does
                // this.
                Text(
                    "${node.last.rpm.coerceAtMost(TACH_MAX_RPM.toLong())}",
                    color = InstrumentCream.copy(alpha = 0.16f),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = footerHeight),
                )

                // The span, in the strip the canvas leaves below the zero line. A real
                // composable rather than canvas text, because it has to be tappable.
                Text(
                    "${windowMs / 1000}s",
                    color = InstrumentCream,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Change the time span shown") {
                            val next = WINDOW_CHOICES.indexOf(windowMs) + 1
                            windowMs = WINDOW_CHOICES[next % WINDOW_CHOICES.size]
                        }
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        AlphaControl(alpha = alpha, onAlphaChange = { alpha = it })

        Text(
            // Named rather than left as a bare figure: this is the packet arrival
            // rate, not anything the engine is doing, and the two are easy to confuse
            // on a screen that is otherwise all engine.
            "data rate @ " + (node.observedRateHz?.let { "%.1fHz".format(it) } ?: "measuring"),
            color = InstrumentGrey,
            fontSize = 12.sp,
        )

        if (node.history.size < 2) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (node.history.isEmpty()) "Filling — the trace starts with the next packet."
                else "Filling — one sample so far.",
                color = InstrumentGrey,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Smoothing at rest.
 *
 * 0.2 settles to within a few percent of a step in about fifteen samples, which at
 * the firmware's 100 ms cadence is a second and a half — slow enough to be worth
 * drawing beside the raw trace, fast enough to still follow a real change.
 */
private const val DEFAULT_ALPHA = 0.2f

/**
 * The floor on the slider and on the filter.
 *
 * At 0.02 the average lags roughly fifty samples. Below that it stops tracking within
 * the sixty seconds the plot holds, so the line would say nothing about the engine.
 */
private const val MIN_ALPHA = 0.02f

/**
 * The spans the label cycles through, shortest first.
 *
 * Bounded by what the registry keeps: asking for more than
 * [NodeRegistry.HISTORY_WINDOW_MS] would draw an empty left half that looks like a
 * dropout.
 */
private val WINDOW_CHOICES = listOf(5_000L, 10_000L, 30_000L, NodeRegistry.HISTORY_WINDOW_MS)

/**
 * Half-widths the vertical axis cycles through. 0 is the full scale.
 *
 * Tapping the plot steps through these in order and wraps back to the full scale, so
 * there is always a way out of a zoom with the same gesture that got into it.
 */
private val ZOOM_BANDS = listOf(0f, 500f, 1000f)

/** The rpm range the vertical axis covers. */
private data class ViewRange(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/**
 * The visible rpm range for a zoom step.
 *
 * A band that would run off either end of the scale is slid back inside rather than
 * truncated: the instrument cannot show negative rpm or anything past its end stop,
 * and a half-height band would make the zoom step look like it had failed.
 */
private fun viewRange(zoom: Int, centre: Float): ViewRange {
    val half = ZOOM_BANDS.getOrElse(zoom) { 0f }
    if (half <= 0f) return ViewRange(0f, TACH_MAX_RPM)

    var min = centre - half
    var max = centre + half
    if (min < 0f) { max -= min; min = 0f }
    if (max > TACH_MAX_RPM) { min -= max - TACH_MAX_RPM; max = TACH_MAX_RPM }
    return ViewRange(min.coerceAtLeast(0f), max.coerceAtMost(TACH_MAX_RPM))
}

/**
 * Run the exponential filter over the whole history, one output per sample.
 *
 * A null [alpha] returns the readings unchanged, so the raw and averaged series come
 * from one piece of code and cannot drift apart. The filter is seeded with the first
 * sample of each unbroken run: seeding from zero would draw a climb out of the bottom
 * of the plot that never happened, and carrying state across a dropout would drag a
 * value from before the outage into the samples after it.
 */
private fun filterSeries(history: List<RpmSample>, alpha: Float?, gapMs: Long): FloatArray {
    val out = FloatArray(history.size)
    // Clamped rather than trusted: a zero would freeze the filter on its seed and draw
    // a flat line that looks like a reading.
    val a = alpha?.coerceIn(MIN_ALPHA, 1f)
    var previous: RpmSample? = null
    var filtered = 0f
    for ((i, sample) in history.withIndex()) {
        val broken = previous?.let { sample.elapsedMs - it.elapsedMs > gapMs } ?: true
        filtered = if (a == null || broken) sample.rpm.toFloat()
                   else a * sample.rpm + (1f - a) * filtered
        out[i] = filtered
        previous = sample
    }
    return out
}

private val RAW_COLOUR = Color(0xFFE8642F)

/**
 * The average is green, thinner and dashed: three differences, so it stays separable
 * from the raw trace where the two coincide and for anyone who cannot tell the two
 * hues apart.
 */
private val AVERAGE_COLOUR = InstrumentGreen

/** The filter's weight, beneath the plot it applies to. */
@Composable
private fun AlphaControl(alpha: Float, onAlphaChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("average alpha", color = InstrumentGrey, fontSize = 12.sp)
        Slider(
            value = alpha,
            onValueChange = onAlphaChange,
            valueRange = MIN_ALPHA..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                // Tied to the line it governs, so which of the two is being adjusted
                // needs no legend.
                thumbColor = AVERAGE_COLOUR,
                activeTrackColor = AVERAGE_COLOUR,
            ),
        )
        Text(
            "%.2f".format(alpha),
            color = AVERAGE_COLOUR,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * Width reserved on the left for the rpm labels, and height reserved beneath the zero
 * line for the span label, both as a fraction of the plot.
 *
 * Shared with the composables that overlay the canvas: anything meant to be centred on
 * the axis has to be inset by the same gutter the canvas uses, or it ends up centred
 * on the whole plot and sitting visibly left of the line it belongs to.
 */
private const val GUTTER_FRACTION = 0.10f
private const val FOOTER_FRACTION = 0.07f

private fun DrawScope.gutter(): Float = size.width * GUTTER_FRACTION

private fun DrawScope.plotWidth(): Float = size.width - gutter()

private fun DrawScope.footer(): Float = size.height * FOOTER_FRACTION

private fun DrawScope.plotHeight(): Float = size.height - footer()

/**
 * rpm to a y coordinate. Clamped, so a reading outside the visible range flat-tops at
 * the edge it left through rather than being drawn off the plot.
 */
private fun DrawScope.yFor(rpm: Float, view: ViewRange): Float {
    val fraction = ((rpm - view.min) / view.span).coerceIn(0f, 1f)
    return plotHeight() * (1f - fraction)
}

/** Grid step chosen to give a handful of lines whatever the zoom. */
private fun gridStep(span: Float): Int = when {
    span <= 1_200f -> 250
    span <= 2_500f -> 500
    else -> 1000
}

private fun DrawScope.drawGrid(measurer: TextMeasurer, view: ViewRange) {
    val left = gutter()
    // Cream at full strength rather than grey: this is a scale, and a scale that
    // cannot be read outdoors is not a scale. Bold and larger for the same reason.
    val labelStyle = TextStyle(
        color = InstrumentCream,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )

    // The redline band, so the same reading reads the same way here as on the dial.
    // Both edges go through the same clamped mapping, so a band wholly outside the
    // visible range collapses to nothing rather than being drawn across the plot.
    val redTop = yFor(TACH_MAX_RPM, view)
    val redBottom = yFor(TACH_REDLINE_RPM, view)
    if (redBottom > redTop) {
        drawRect(
            color = Color(0xFFD1402A).copy(alpha = 0.16f),
            topLeft = Offset(left, redTop),
            size = androidx.compose.ui.geometry.Size(plotWidth(), redBottom - redTop),
        )
    }

    // Zoomed in, the thousands labels would repeat or vanish, so the lines carry their
    // actual rpm. At full scale they stay as the thousands a dial is marked in.
    val full = view.min <= 0f && view.max >= TACH_MAX_RPM
    val step = gridStep(view.span)
    var rpm = (view.min / step).toInt() * step
    while (rpm <= view.max.toInt() + step) {
        if (rpm >= view.min - 1f && rpm <= view.max + 1f) {
            val y = yFor(rpm.toFloat(), view)
            drawLine(
                color = InstrumentGrey.copy(alpha = if (rpm == 0) 0.75f else 0.32f),
                start = Offset(left, y),
                end = Offset(size.width, y),
                strokeWidth = size.minDimension * 0.003f,
            )
            val layout = measurer.measure(if (full) "${rpm / 1000}" else "$rpm", labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(left - layout.size.width - 4f, y - layout.size.height / 2f),
            )
        }
        rpm += step
    }
}

/**
 * Draw one series over the history.
 *
 * @param values one value per sample, from [filterSeries]. The filter runs over the
 *   whole retained history rather than the visible span, so neither a shorter window
 *   nor a zoom draws a settling transient that is an artefact of the view.
 * @param averaged which series this is, which decides its colour, weight and dash.
 */
private fun DrawScope.drawTrace(
    history: List<RpmSample>,
    values: FloatArray,
    nowMs: Long,
    windowMs: Long,
    gapMs: Long,
    view: ViewRange,
    averaged: Boolean,
) {
    if (history.size < 2 || values.size != history.size) return

    val left = gutter()
    val window = windowMs.toFloat()

    fun xFor(elapsedMs: Long): Float {
        // Age zero sits at the right edge, a full window old at the left.
        val age = (nowMs - elapsedMs).toFloat().coerceIn(0f, window)
        return left + plotWidth() * (1f - age / window)
    }

    val path = Path()
    var drawing = false
    var previous: RpmSample? = null
    var lastY = 0f
    for ((i, sample) in history.withIndex()) {
        // A gap wider than a few packet slots is a dropout, not a slope. Breaking the
        // path there stops a straight line being drawn across data never received.
        val broken = previous?.let { sample.elapsedMs - it.elapsedMs > gapMs } ?: false
        previous = sample

        // Older than the window: nothing is drawn, and the next visible sample starts
        // a new subpath rather than being joined across the edge.
        if (nowMs - sample.elapsedMs > windowMs) {
            drawing = false
            continue
        }

        val x = xFor(sample.elapsedMs)
        val y = yFor(values[i], view)
        if (!drawing || broken) path.moveTo(x, y) else path.lineTo(x, y)
        drawing = true
        lastY = y
    }

    val colour = if (averaged) AVERAGE_COLOUR else RAW_COLOUR

    // Off the narrower dimension: a tall plot scaled off its height draws a stroke
    // wide enough to swallow the detail it is there to show.
    val width = size.minDimension * (if (averaged) 0.0035f else 0.006f)
    drawPath(
        path = path,
        color = colour,
        style = Stroke(
            width = width,
            // Dash lengths in multiples of the stroke, so the pattern stays in
            // proportion on any screen rather than turning to a dotted line on a
            // large one and a solid one on a small.
            pathEffect = if (!averaged) null
            else PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 3f)),
        ),
    )

    // The leading dot: where the trace is being written from, which is otherwise hard
    // to pick out once the line reaches the right edge.
    history.lastOrNull()?.let {
        drawCircle(
            color = colour,
            radius = size.minDimension * (if (averaged) 0.009f else 0.014f),
            center = Offset(xFor(it.elapsedMs), lastY),
        )
    }
}

// ---- previews ------------------------------------------------------------

private fun previewHistory(now: Long, count: Int): List<RpmSample> =
    (0 until count).map { i ->
        val t = now - (count - 1 - i) * 100L
        val phase = i / 40.0
        RpmSample(t, (3000 + 2200 * kotlin.math.sin(phase)).roundToInt().toLong())
    }

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 640)
@Composable
private fun PreviewGraph() = RPMMasterTheme(darkTheme = true) {
    val now = 600_000L
    RpmGraph(
        node = NodeState(
            nodeId = 1,
            last = RpmPacket(1, 4321, 600_000, 4200, 6100),
            peakRpm = 5200,
            history = previewHistory(now, 600),
            senderIp = "192.168.43.20",
            senderIps = setOf("192.168.43.20"),
            lastSeenElapsedMs = now,
            ageMs = 40,
            freshness = Freshness.LIVE,
            thresholds = FreshnessThresholds.forInterval(100),
            observedIntervalMs = 100,
            packetsReceived = 6_000,
            linkLost = 3,
            windowLossFraction = 0.0005,
            rebootCount = 0,
            collision = false,
        ),
        listening = true,
        modifier = Modifier.background(Color(0xFF0B0B0C)),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0C, heightDp = 400)
@Composable
private fun PreviewGraphEmpty() = RPMMasterTheme(darkTheme = true) {
    RpmGraph(node = null, listening = true, modifier = Modifier.background(Color(0xFF0B0B0C)))
}
