package com.pitstop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * claude.ai/design palette mirrored into Compose. Single warm-coral
 * accent (#FF5B3A) as primary; the secondary slot is intentionally a
 * cool ink so it doesn't fight the accent. Surface ramp matches the
 * web's bg0..bg4 — page is pure-dark, cards step up by ~5% L*.
 *
 * tertiary = amber, used sparingly for update banners and warnings
 * adjacent to the redline.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5B3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3A1308),
    onPrimaryContainer = Color(0xFFFFD3C7),

    secondary = Color(0xFFE7E9EE),
    onSecondary = Color(0xFF14171D),
    secondaryContainer = Color(0xFF21262F),
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
    surfaceContainer = Color(0xFF14171D),
    surfaceContainerHigh = Color(0xFF1A1E26),
    surfaceContainerHighest = Color(0xFF21262F),

    outline = Color(0xFF3D434D),
    outlineVariant = Color(0xFF21262F),

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
    MaterialTheme(
        colorScheme = colors,
        typography = PitstopTypography,
        content = content,
    )
}
