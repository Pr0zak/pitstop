package com.pitstop.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * The user's "imperial" / "metric" preference, provided once at the app
 * root (MainActivity) off SettingsRepository. Screens read this instead of
 * each ViewModel re-collecting the setting, so a toggle in Settings
 * re-renders every visible number in the same frame — and a stateless
 * `XxxContent` composable can be rendered in a screenshot test by wrapping
 * it in a provider rather than threading a parameter through.
 *
 * `compositionLocalOf` (not static): the value does change at runtime, and
 * only the readers should recompose when it does.
 */
val LocalUnitSystem = compositionLocalOf { "imperial" }
