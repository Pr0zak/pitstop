package com.pitstop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * claude.ai/design palette mirrored into Compose. Single warm-coral
 * accent (#FF5B3A) as primary; the secondary slot is intentionally a
 * cool ink so it doesn't fight the accent. Surface ramp matches the
 * web's bg0..bg4 — page is pure-dark, cards step up by ~5% L*.
 *
 * tertiary = amber, used sparingly for update banners and warnings
 * adjacent to the redline.
 *
 * EVERY M3 colour role is set, including the surfaceContainer* ladder,
 * surfaceDim/Bright and the inverse roles. Leaving one to the library
 * default drops a stock purple-tinted Material baseline value into an
 * otherwise hand-tuned palette — that is how a date picker or snackbar
 * ends up the one lavender thing on the screen.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5B3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3A1308),
    onPrimaryContainer = Color(0xFFFFD3C7),
    inversePrimary = Color(0xFFB8391C),

    secondary = Color(0xFFE7E9EE),
    onSecondary = Color(0xFF14171D),
    secondaryContainer = Color(0xFF2A303B),
    onSecondaryContainer = Color(0xFFE7E9EE),

    tertiary = Color(0xFFFFB020),
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF3A2A0A),
    onTertiaryContainer = Color(0xFFFFE4B0),

    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF1A1E26),
    onSurfaceVariant = Color(0xFF9AA0AA),
    surfaceTint = Color(0xFFFF5B3A),
    inverseSurface = Color(0xFFE7E9EE),
    inverseOnSurface = Color(0xFF14171D),

    surfaceDim = Color(0xFF0A0C10),
    surfaceBright = Color(0xFF2A303B),
    surfaceContainerLowest = Color(0xFF07090C),
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Color(0xFF14171D),
    surfaceContainerHigh = Color(0xFF1A1E26),
    surfaceContainerHighest = Color(0xFF21262F),

    outline = Color(0xFF3D434D),
    outlineVariant = Color(0xFF21262F),
    scrim = Color(0xFF000000),

    error = Color(0xFFFF3A2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A0A05),
    onErrorContainer = Color(0xFFFFC4BE),
)

// Light scheme is a stub — pitstop ships dark-only by design. Compose
// still wants something here in case the host forces a light theme,
// so we hand back a desaturated mirror of dark with surfaces flipped.
private val LightColors = lightColorScheme(
    primary = Color(0xFFD8401F),
    secondary = Color(0xFF14171D),
    tertiary = Color(0xFFB07000),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB02018),
)

/**
 * Status colours M3 has no role for. `good` / `warn` / `bad` are the
 * pill + delta + gauge tones (web Pill.vue's green / amber / red); `tow`
 * and `gps` are the two trip-card provenance chips; `info` is the slate
 * "Local-only" tone. Each has a translucent container for chip fills.
 *
 * Read through [MaterialTheme.ext] — never inline a Color(0x…) for one
 * of these in a screen, or the next palette tweak misses it.
 */
@Immutable
data class ExtendedColors(
    val good: Color,
    val goodContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val tow: Color,
    val gps: Color,
)

private val DarkExtended = ExtendedColors(
    good = Color(0xFF4ADE80),
    goodContainer = Color(0x1A4ADE80),
    warn = Color(0xFFFFB020),
    warnContainer = Color(0x1AFFB020),
    bad = Color(0xFFFF3A2E),
    badContainer = Color(0x1AFF3A2E),
    info = Color(0xFF8AA4C9),
    infoContainer = Color(0x1A8AA4C9),
    tow = Color(0xFFFFB020),
    gps = Color(0xFF8AA4C9),
)

private val LightExtended = ExtendedColors(
    good = Color(0xFF15803D),
    goodContainer = Color(0x1A15803D),
    warn = Color(0xFFB07000),
    warnContainer = Color(0x1AB07000),
    bad = Color(0xFFB02018),
    badContainer = Color(0x1AB02018),
    info = Color(0xFF4A6285),
    infoContainer = Color(0x1A4A6285),
    tow = Color(0xFFB07000),
    gps = Color(0xFF4A6285),
)

private val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

/** Pitstop's extra colour roles; see [ExtendedColors]. */
val MaterialTheme.ext: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

@Composable
fun PitstopTheme(
    // pitstop is dark-only by design. Defaulting to the system setting
    // dropped light-mode phones onto the half-stubbed LightColors (white
    // bg, pink cards). Force the intended dark scheme regardless of the
    // system theme. (Caller can still pass false to preview the stub.)
    useDarkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalExtendedColors provides if (useDarkTheme) DarkExtended else LightExtended,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = PitstopTypography,
            content = content,
        )
    }
}
