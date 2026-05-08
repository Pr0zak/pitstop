package com.pitstop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF2A1C00),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF002233),
    tertiary = Color(0xFF86EFAC),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1B1E24),
    onSurface = Color(0xFFE6E1E5),
    error = Color(0xFFEF4444),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFFB923C),
    secondary = Color(0xFF0EA5E9),
    tertiary = Color(0xFF22C55E),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun PitstopTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
