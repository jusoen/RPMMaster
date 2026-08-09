package com.rpmmonitor.master.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpmmonitor.master.state.Freshness
import kotlin.math.cos
import kotlin.math.sin

/**
 * A period mechanical tachometer of the Smiths / Jaeger sort — the kind fitted to a
 * 1960s-70s sports car, not a modern flat-design arc.
 *
 * Drawn entirely with a [Canvas] and no image assets, so it scales to any screen off
 * a single radius. Everything below is expressed as a fraction of that radius.
 */

// ---- geometry ------------------------------------------------------------

const val TACH_MAX_RPM = 7000f
const val TACH_REDLINE_RPM = 5500f

/**
 * Sweep in degrees.
 *
 * The specification asks for 240 degrees and separately describes it as "7 o'clock
 * to 5 o'clock", which is a 300 degree span, so the two never agreed. 250 is the
 * chosen figure. Centred on 12 o'clock, it puts the zero stop 125 degrees anti-
 * clockwise of vertical and the end stop the same distance the other way.
 */
const val TACH_SWEEP_DEG = 250f

/** Canvas angles: 0 is 3 o'clock and positive runs clockwise. */
private const val TWELVE_O_CLOCK_DEG = -90f
private const val TACH_START_DEG = TWELVE_O_CLOCK_DEG - TACH_SWEEP_DEG / 2f

/** RPM to canvas angle. Clamped: above the top the needle pins, it never wraps. */
fun rpmToAngle(rpm: Float): Float =
    TACH_START_DEG + TACH_SWEEP_DEG * (rpm / TACH_MAX_RPM).coerceIn(0f, 1f)

// ---- colours -------------------------------------------------------------

data class DialColors(
    val face: Color = Color(0xFF0E0E10),
    val faceEdge: Color = Color(0xFF000000),
    val bezelLight: Color = Color(0xFFD8DCE0),
    val bezelDark: Color = Color(0xFF4A4E52),
    val tick: Color = Color(0xFFF3E6D8),
    val minorTick: Color = Color(0xFFB9AEA2),
    val label: Color = Color(0xFFF3E6D8),
    val caption: Color = Color(0xFFB9AEA2),
    val redline: Color = Color(0xFFD1402A),
    val needle: Color = Color(0xFFE8642F),
    /** The high-water marker. Amber, so it never reads as a second needle. */
    val peak: Color = Color(0xFFE8A33D),
    val boss: Color = Color(0xFFBFC3C7),
    val bossShadow: Color = Color(0xFF2A2C2E),
)

/** Pull [c] towards its own luminance. 0 leaves it alone, 1 makes it grey. */
private fun Color.desaturate(amount: Float): Color {
    if (amount <= 0f) return this
    val l = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return lerp(this, Color(l, l, l, alpha), amount.coerceIn(0f, 1f))
}

private fun DialColors.forFreshness(freshness: Freshness): DialColors {
    // Colour is never the only carrier of state — the caller pairs this with a
    // label. Desaturation here is the supporting signal, not the signal.
    val amount = when (freshness) {
        Freshness.LIVE -> 0f
        Freshness.STALE -> 0.55f
        Freshness.OFFLINE -> 0.85f
    }
    if (amount == 0f) return this
    return DialColors(
        face = face,
        faceEdge = faceEdge,
        bezelLight = bezelLight.desaturate(amount),
        bezelDark = bezelDark.desaturate(amount),
        tick = tick.desaturate(amount),
        minorTick = minorTick.desaturate(amount),
        label = label.desaturate(amount),
        caption = caption.desaturate(amount),
        redline = redline.desaturate(amount),
        needle = needle.desaturate(amount),
        peak = peak.desaturate(amount),
        boss = boss.desaturate(amount),
        bossShadow = bossShadow,
    )
}

// ---- the composable ------------------------------------------------------

/**
 * @param rpm the reading to point at. Above [TACH_MAX_RPM] the needle pins at the
 *   end stop and the digital value beside the dial carries the real number.
 * @param peakRpm the high-water mark, drawn as a tell-tale marker on the scale. Null
 *   means nothing has been recorded yet and no marker is drawn. Zero is a mark like
 *   any other and *is* drawn: after a reset taken while the engine is stopped, the
 *   highest reading since is genuinely zero, and hiding the marker there would read
 *   as the reset having failed. Like the needle it pins at the end stop rather than
 *   wrapping.
 * @param freshness drives desaturation. When it is not [Freshness.LIVE] the needle
 *   **holds its last angle** rather than dropping to zero, so a stalled engine
 *   (needle at the zero stop, live) and a lost node (needle held, offline) can never
 *   look the same.
 */
@Composable
fun RetroTachometer(
    rpm: Long,
    freshness: Freshness,
    modifier: Modifier = Modifier,
    peakRpm: Long? = null,
    colors: DialColors = DialColors(),
) {
    val target = rpmToAngle(rpm.toFloat())
    val angle = remember { Animatable(target) }

    LaunchedEffect(target, freshness) {
        // Only a live reading moves the needle. A stale or offline node holds the
        // last angle it was given.
        if (freshness == Freshness.LIVE) {
            angle.animateTo(
                targetValue = target,
                // A real needle lags and overshoots slightly. Kept brisk on purpose:
                // this settles in roughly 200 ms, so the damping never masks a
                // genuine change or visibly lags the digital value beside it.
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val measurer = rememberTextMeasurer()
    val shown = colors.forFreshness(freshness)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawFace(centre, radius, shown, measurer)
            // Under the needle on purpose: at the moment the peak is being set the two
            // coincide, and the live reading must stay the thing you read first.
            if (peakRpm != null) drawPeakMarker(centre, radius, rpmToAngle(peakRpm.toFloat()), shown)
            drawNeedle(centre, radius, angle.value, shown)
        }
    }
}

// ---- face ----------------------------------------------------------------

private fun DrawScope.drawFace(
    centre: Offset,
    radius: Float,
    c: DialColors,
    measurer: TextMeasurer,
) {
    val bezelWidth = radius * 0.055f
    val faceRadius = radius - bezelWidth

    // Face, with a subtle radial vignette towards the rim. Restrained on purpose —
    // a heavy skeuomorphic treatment reads as pastiche.
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to c.face,
            0.72f to c.face,
            1.0f to c.faceEdge,
            center = centre,
            radius = faceRadius,
        ),
        radius = faceRadius,
        center = centre,
    )

    // Chrome-effect bezel: a two-stop linear gradient with the light from the
    // top-left, which is what makes a flat ring read as turned metal.
    drawCircle(
        brush = Brush.linearGradient(
            listOf(c.bezelLight, c.bezelDark),
            start = Offset(centre.x - radius, centre.y - radius),
            end = Offset(centre.x + radius, centre.y + radius),
        ),
        radius = radius - bezelWidth / 2f,
        center = centre,
        style = Stroke(width = bezelWidth),
    )

    // Redline: a thick band just inside the tick ring, from 5500 to the top of the
    // scale, which is also the end stop. Nothing is drawn beyond the scale, so the
    // band can never imply a reading the dial cannot show.
    val redlineRadius = faceRadius * 0.88f
    val redlineWidth = faceRadius * 0.075f
    val redlineStart = rpmToAngle(TACH_REDLINE_RPM)
    drawArc(
        color = c.redline,
        startAngle = redlineStart,
        sweepAngle = rpmToAngle(TACH_MAX_RPM) - redlineStart,
        useCenter = false,
        topLeft = Offset(centre.x - redlineRadius, centre.y - redlineRadius),
        size = Size(redlineRadius * 2f, redlineRadius * 2f),
        style = Stroke(width = redlineWidth),
    )

    // Ticks. Minor every 250, slightly heavier at each 500, major every 1000.
    val tickOuter = faceRadius * 0.95f
    var rpm = 0
    while (rpm <= TACH_MAX_RPM.toInt()) {
        val major = rpm % 1000 == 0
        val half = rpm % 500 == 0
        val (len, width, colour) = when {
            major -> Triple(faceRadius * 0.15f, faceRadius * 0.028f, c.tick)
            half -> Triple(faceRadius * 0.085f, faceRadius * 0.016f, c.tick)
            else -> Triple(faceRadius * 0.06f, faceRadius * 0.010f, c.minorTick)
        }
        val a = Math.toRadians(rpmToAngle(rpm.toFloat()).toDouble())
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        drawLine(
            color = colour,
            start = Offset(centre.x + cosA * tickOuter, centre.y + sinA * tickOuter),
            end = Offset(centre.x + cosA * (tickOuter - len), centre.y + sinA * (tickOuter - len)),
            strokeWidth = width,
        )
        rpm += 250
    }

    // Numerals: the thousands, as a classic dial labels them. Upright rather than
    // rotated, which is how Smiths faces are printed.
    val labelRadius = tickOuter - faceRadius * 0.28f
    val numeralStyle = TextStyle(
        color = c.label,
        fontSize = (faceRadius * 0.17f).toSp(),
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
    )
    for (thousand in 0..7) {
        val a = Math.toRadians(rpmToAngle(thousand * 1000f).toDouble())
        val layout = measurer.measure(thousand.toString(), numeralStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                centre.x + cos(a).toFloat() * labelRadius - layout.size.width / 2f,
                centre.y + sin(a).toFloat() * labelRadius - layout.size.height / 2f,
            ),
        )
    }

    // The multiplier caption. The scale is labelled 0-7 for 0-7000, so this reads
    // x1000 — the brief's "RPM x100" does not match its own labelling.
    val captionStyle = TextStyle(
        color = c.caption,
        fontSize = (faceRadius * 0.085f).toSp(),
        fontFamily = FontFamily.Serif,
        letterSpacing = (faceRadius * 0.012f).toSp(),
    )
    val caption = measurer.measure("RPM ×1000", captionStyle)
    drawText(
        textLayoutResult = caption,
        topLeft = Offset(
            centre.x - caption.size.width / 2f,
            centre.y + faceRadius * 0.34f,
        ),
    )
}

// ---- peak marker ---------------------------------------------------------

/**
 * The tell-tale: a short radial blade on the scale at the highest reading seen, the
 * mechanical equivalent of the pushed pointer on a period tachometer.
 *
 * Deliberately not a full needle. It reaches inwards only as far as the numerals, so
 * at a glance the dial still has exactly one thing pointing at the centre.
 */
private fun DrawScope.drawPeakMarker(
    centre: Offset,
    radius: Float,
    angleDeg: Float,
    c: DialColors,
) {
    val faceRadius = radius * 0.945f
    val outer = faceRadius * 0.98f
    val inner = faceRadius * 0.66f
    val a = Math.toRadians(angleDeg.toDouble())
    val cosA = cos(a).toFloat()
    val sinA = sin(a).toFloat()

    drawLine(
        color = c.peak,
        start = Offset(centre.x + cosA * outer, centre.y + sinA * outer),
        end = Offset(centre.x + cosA * inner, centre.y + sinA * inner),
        strokeWidth = faceRadius * 0.022f,
    )
    // A blob at the rim end, which is what makes it read as a marker parked against
    // the scale rather than as a stray tick.
    drawCircle(c.peak, radius = faceRadius * 0.035f, center = Offset(centre.x + cosA * outer, centre.y + sinA * outer))
}

// ---- needle --------------------------------------------------------------

private fun DrawScope.drawNeedle(
    centre: Offset,
    radius: Float,
    angleDeg: Float,
    c: DialColors,
) {
    val faceRadius = radius * 0.945f
    val tip = faceRadius * 0.80f
    val tail = faceRadius * 0.20f          // the counterweight past the pivot
    val baseHalf = faceRadius * 0.028f
    val tailHalf = faceRadius * 0.019f
    val bossRadius = faceRadius * 0.075f

    val needle = Path().apply {
        moveTo(centre.x + tip, centre.y)
        lineTo(centre.x + bossRadius * 0.5f, centre.y - baseHalf)
        lineTo(centre.x - tail, centre.y - tailHalf)
        lineTo(centre.x - tail, centre.y + tailHalf)
        lineTo(centre.x + bossRadius * 0.5f, centre.y + baseHalf)
        close()
    }

    rotate(degrees = angleDeg, pivot = centre) {
        // Soft drop shadow, offset a little down-right so the needle floats above
        // the face rather than being printed on it.
        val shadowOffset = radius * 0.018f
        translate(shadowOffset, shadowOffset) {
            drawPath(needle, Color.Black.copy(alpha = 0.45f))
            drawCircle(
                Color.Black.copy(alpha = 0.45f),
                radius = faceRadius * 0.045f,
                center = Offset(centre.x - tail, centre.y),
            )
        }
        drawPath(needle, c.needle)
        // Counterweight blob on the tail. This one detail does more for the retro
        // read than anything else on the face.
        drawCircle(c.needle, radius = faceRadius * 0.045f, center = Offset(centre.x - tail, centre.y))
    }

    // Chrome centre boss, drawn last so it caps the needle root.
    drawCircle(c.bossShadow, radius = bossRadius * 1.12f, center = centre)
    drawCircle(
        brush = Brush.linearGradient(
            listOf(c.boss, c.bossShadow),
            start = Offset(centre.x - bossRadius, centre.y - bossRadius),
            end = Offset(centre.x + bossRadius, centre.y + bossRadius),
        ),
        radius = bossRadius,
        center = centre,
    )
}

// ---- previews ------------------------------------------------------------
// The face can be iterated without a device or a running node.

@Preview(name = "0 — zero stop", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewZero() = RetroTachometer(0, Freshness.LIVE, Modifier.size(280.dp))

@Preview(name = "3000 — mid scale", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewMid() = RetroTachometer(3000, Freshness.LIVE, Modifier.size(280.dp))

@Preview(name = "5500 — start of the redline", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewRedlineStart() = RetroTachometer(5500, Freshness.LIVE, Modifier.size(280.dp))

@Preview(name = "9000 — pinned at the end stop", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewPinned() = RetroTachometer(9000, Freshness.LIVE, Modifier.size(280.dp))

@Preview(name = "3000, peak 6100 — tell-tale", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewPeak() = RetroTachometer(3000, Freshness.LIVE, Modifier.size(280.dp), peakRpm = 6100)

@Preview(name = "3000 — stale", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewStale() = RetroTachometer(3000, Freshness.STALE, Modifier.size(280.dp))

@Preview(name = "3000 — offline", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewOffline() = RetroTachometer(3000, Freshness.OFFLINE, Modifier.size(280.dp))
