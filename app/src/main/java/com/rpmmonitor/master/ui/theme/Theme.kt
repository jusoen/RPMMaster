package com.rpmmonitor.master.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Instrument palette. The dial owns its own colours (see [com.rpmmonitor.master.ui.DialColors])
 * because a period tachometer face is not a Material surface — these are for the
 * chrome around it.
 */
val InstrumentAmber = Color(0xFFE8A33D)
val InstrumentRed = Color(0xFFD1402A)
val InstrumentCream = Color(0xFFF3E6D8)
val InstrumentGreen = Color(0xFF4CAF50)
val InstrumentGrey = Color(0xFF8A8A8A)

private val DarkColors = darkColorScheme(
    primary = InstrumentAmber,
    onPrimary = Color.Black,
    secondary = InstrumentCream,
    background = Color(0xFF0B0B0C),
    onBackground = InstrumentCream,
    surface = Color(0xFF141416),
    onSurface = InstrumentCream,
    surfaceVariant = Color(0xFF1E1E21),
    onSurfaceVariant = Color(0xFFBFB6AC),
    error = InstrumentRed,
)

// Provided so a user with the system in light mode is not left with an unreadable
// app, but dark is the default the brief asks for and the one the dial is drawn for.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A10),
    secondary = Color(0xFF4A4A4A),
    error = InstrumentRed,
)

private val InstrumentTypography = Typography(
    // The one style the readout really depends on: a wide, monospaced figure set so
    // the digits do not shuffle sideways as the value changes.
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 84.sp,
        letterSpacing = 2.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
    ),
)

/**
 * Dark is the **default**, not the system preference. This is an instrument read at
 * a glance next to a running engine, and the dial's face is drawn for a dark
 * surround — a white background around a black dial is the worst case for it. The
 * parameter exists so previews can force either scheme.
 */
@Composable
fun RPMMasterTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = InstrumentTypography,
        content = content,
    )
}
