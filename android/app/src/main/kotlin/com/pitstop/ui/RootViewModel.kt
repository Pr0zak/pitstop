package com.pitstop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-root state that every tab reads: today just the unit system, which
 * MainActivity provides as [com.pitstop.ui.theme.LocalUnitSystem]. Kept
 * separate from OnboardingGateViewModel on purpose — that one's contract
 * is "latched at startup", and the unit system is live.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val unitSystem: StateFlow<String> = settingsRepository.settings
        .map { it.unitSystem }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "imperial")
}
