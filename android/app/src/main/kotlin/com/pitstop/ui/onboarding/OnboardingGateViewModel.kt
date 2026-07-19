package com.pitstop.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides whether the first-run setup wizard should gate the pager. Kept
 * deliberately tiny — it injects only [SettingsRepository] so instantiating it
 * at the app root (where the gate lives) doesn't drag in BLE/MQTT/etc. the way
 * the full ConfigViewModel would.
 *
 * The decision is LATCHED at startup, not reactive: the wizard shows when, at
 * launch, onboarding hasn't been completed AND no server is configured — then
 * it stays up until [markComplete] (Finish / Skip). Latching matters because
 * the wizard itself writes the server URL (via paste / manual entry); a reactive
 * gate would vanish the instant that write landed and dump the user out of the
 * wizard mid-flow. The apiBaseUrl check at launch is only there so an existing,
 * already-configured user (upgraded from a build without the flag) never sees
 * the wizard.
 *
 * `showWizard` is nullable: `null` means "not yet determined" (settings still
 * loading), letting the root render a blank frame instead of flashing the pager
 * before swapping to the wizard.
 */
@HiltViewModel
class OnboardingGateViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _showWizard = MutableStateFlow<Boolean?>(null)
    val showWizard: StateFlow<Boolean?> = _showWizard.asStateFlow()

    init {
        viewModelScope.launch {
            val done = settingsRepository.onboardingComplete.first()
            val hasServer = settingsRepository.settings.first().apiBaseUrl.isNotBlank()
            _showWizard.value = !done && !hasServer
        }
    }

    fun markComplete() {
        viewModelScope.launch {
            settingsRepository.setOnboardingComplete(true)
            _showWizard.value = false
        }
    }
}
