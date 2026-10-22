package com.pitstop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pitstop.domain.WicanSetup
import com.pitstop.ui.theme.ext

/** What every WiCAN-setup surface renders from. */
data class WicanSetupUi(
    val inputs: WicanSetup.Inputs,
    val state: WicanSetup.State = WicanSetup.evaluate(inputs),
    /** An action (bond / auto-start link) is running. */
    val busy: Boolean = false,
)

/**
 * The single "WiCAN" card: status pill, one sentence, the two steps
 * (Bluetooth, Auto-start) with their state, and ONE primary button that runs
 * whichever step is missing. Vocabulary lives in [WicanSetup] so this card,
 * the top-bar pill, the bridge sheet, Home and the wizard all say the same.
 */
@Composable
fun WicanSetupCard(
    ui: WicanSetupUi,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = WicanSetup.pill(ui.state)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "WiCAN",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                StatusPill(
                    tone = pill?.second?.let(::toneOf) ?: PillTone.Healthy,
                    label = pill?.first ?: "Ready",
                    compact = true,
                    subject = "WiCAN",
                )
            }
            WicanSetup.message(ui.state)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            StepLine(
                done = ui.state != WicanSetup.State.BluetoothRejected &&
                    ui.state != WicanSetup.State.BluetoothNeeded &&
                    ui.state != WicanSetup.State.BluetoothInProgress &&
                    ui.state != WicanSetup.State.NoDevice &&
                    (ui.inputs.bond == WicanSetup.Bond.Bonded || !ui.inputs.bondRequired),
                text = "1. " + WicanSetup.bluetoothStep(ui.inputs),
            )
            StepLine(
                done = ui.inputs.autoStartOn && (!ui.inputs.cdmSupported || ui.inputs.cdmAssociated),
                text = "2. " + WicanSetup.autoStartStep(ui.inputs),
            )
            WicanSetup.action(ui.state)?.let { label ->
                Button(onClick = onAction, enabled = !ui.busy) {
                    if (ui.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun StepLine(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) MaterialTheme.ext.good else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Domain tone → pill tone (same mapping as the top-bar chip). */
fun toneOf(t: WicanSetup.Tone): PillTone = when (t) {
    WicanSetup.Tone.Neutral -> PillTone.Neutral
    WicanSetup.Tone.Working -> PillTone.Connecting
    WicanSetup.Tone.Warn -> PillTone.Degraded
    WicanSetup.Tone.Bad -> PillTone.Offline
}
