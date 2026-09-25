package com.pitstop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The system 12h/24h clock setting, for [com.pitstop.util.DateLabel].
 * Falls back to 12h where the platform can't answer (layoutlib in the
 * screenshot tests).
 */
@Composable
fun is24HourClock(): Boolean {
    val context = LocalContext.current
    return runCatching { android.text.format.DateFormat.is24HourFormat(context) }.getOrDefault(false)
}
