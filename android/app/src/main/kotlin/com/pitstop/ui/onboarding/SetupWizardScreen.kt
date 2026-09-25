package com.pitstop.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.VehicleDto
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.StatusPill
import com.pitstop.ui.config.ConfigFormState
import com.pitstop.ui.config.ConfigViewModel
import com.pitstop.ui.config.ConnTest
import com.pitstop.ui.config.ImportConfirmDialog
import com.pitstop.ui.config.SecretField
import com.pitstop.ui.config.extractCompanionResult
import kotlinx.coroutines.launch

/** The wizard's three steps, in order. */
internal enum class WizardStep(val title: String) {
    Connect("Connect to your server"),
    Vehicle("Pick this phone's vehicle"),
    Permissions("Permissions & WiCAN"),
}

/**
 * First-run wizard (task #12). Shown by the app root instead of the pager
 * while the app is un-configured, so a brand-new install can't land on an
 * empty Home with no idea what to do. Three steps with a progress bar:
 *
 *   1. Connect     — paste a setup link or enter URL + tokens (masked,
 *                    same field as Settings), then Test
 *   2. Vehicle     — pick from the server's list (or type a slug)
 *   3. Permissions — each explained BEFORE its system dialog, then the
 *                    optional WiCAN companion pairing for auto-start
 *
 * Reuses the shared [ConfigViewModel] so what it captures is the exact form
 * Settings edits. [onDone] marks onboarding complete; Skip calls it too —
 * the Home "Finish setup" card then nudges.
 */
@Composable
fun SetupWizardScreen(
    onDone: () -> Unit,
    pendingSetupLinkFlow: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val connTest by viewModel.connTest.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val companionAssociated by viewModel.companionAssociated.collectAsStateWithLifecycle()
    val pairing by viewModel.pairingInProgress.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(WizardStep.Connect) }
    var permissionsAsked by rememberSaveable { mutableStateOf(false) }

    // Even on first-run, a deep-linked / pasted setup link is confirmed before
    // it's applied — a link is reachable from any app/QR/message.
    pendingImport?.let { payload ->
        ImportConfirmDialog(
            payload = payload,
            onConfirm = { viewModel.confirmImport() },
            onCancel = { viewModel.cancelImport() },
        )
    }

    // A pitstop://setup?… deep link that landed on a fresh (un-onboarded)
    // install imports here instead of on the (not-yet-composed) Settings screen.
    val pendingSetupLink = pendingSetupLinkFlow?.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSetupLink?.value) {
        val link = pendingSetupLink?.value
        if (!link.isNullOrBlank()) {
            viewModel.importSetupLink(link)
            pendingSetupLinkFlow?.value = null
        }
    }

    // Runtime permissions, asked from step 3 AFTER the reasons are on screen.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionsAsked = true }
    // CDM companion pairing — same consent flow Settings hosts.
    val companionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        extractCompanionResult(result.resultCode, result.data)?.let { (id, mac) ->
            viewModel.onCompanionConfirmed(id, mac)
        } ?: viewModel.refreshCompanionState()
        viewModel.restoreBridgeAfterPairingIfNeeded()
    }
    LaunchedEffect(Unit) {
        viewModel.companionIntentSender.collect { sender ->
            runCatching {
                companionLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                )
            }
        }
    }

    BackHandler(enabled = step != WizardStep.Connect) {
        step = WizardStep.entries[step.ordinal - 1]
    }

    SetupWizardContent(
        step = step,
        form = form,
        connTest = connTest,
        permissionsAsked = permissionsAsked,
        companionSupported = viewModel.companionPresenceSupported,
        companionPaired = companionAssociated,
        pairing = pairing,
        snackbarHostState = snackbarHostState,
        onPasteLink = {
            val pasted = clipboard.getText()?.text
            if (pasted.isNullOrBlank()) {
                scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
            } else {
                viewModel.importSetupLink(pasted)
            }
        },
        onFormChange = { transform ->
            viewModel.update(transform)
            viewModel.resetConnTest()
        },
        onSlugChange = { slug -> viewModel.update { it.copy(vehicleSlug = slug) } },
        onTest = { viewModel.testConnection() },
        onRequestPermissions = { permissionLauncher.launch(wizardPermissions()) },
        onPair = { viewModel.pairCompanion() },
        onNext = {
            viewModel.save()
            if (step == WizardStep.Permissions) onDone() else step = WizardStep.entries[step.ordinal + 1]
        },
        onSkip = {
            viewModel.save()
            onDone()
        },
    )
}

private fun wizardPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** Stateless wizard body — screenshot tests render each step from this. */
@Composable
internal fun SetupWizardContent(
    step: WizardStep,
    form: ConfigFormState,
    connTest: ConnTest,
    permissionsAsked: Boolean,
    companionSupported: Boolean,
    companionPaired: Boolean,
    pairing: Boolean,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPasteLink: () -> Unit = {},
    onFormChange: ((ConfigFormState) -> ConfigFormState) -> Unit = {},
    onSlugChange: (String) -> Unit = {},
    onTest: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onPair: () -> Unit = {},
    onNext: () -> Unit = {},
    onSkip: () -> Unit = {},
) {
    val canAdvance = when (step) {
        WizardStep.Connect -> connTest is ConnTest.Ok ||
            (form.apiBaseUrl.isNotBlank() && form.queryToken.isNotBlank())
        WizardStep.Vehicle -> form.vehicleSlug.isNotBlank()
        WizardStep.Permissions -> true
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) { Text("Skip") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onNext, enabled = canAdvance) {
                        Text(if (step == WizardStep.Permissions) "Finish" else "Next")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { (step.ordinal + 1f) / WizardStep.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Step ${step.ordinal + 1} of ${WizardStep.entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                step.title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (step) {
                WizardStep.Connect -> ConnectStep(form, connTest, onPasteLink, onFormChange, onTest)
                WizardStep.Vehicle -> VehicleStep(form, connTest, onSlugChange, onTest)
                WizardStep.Permissions -> PermissionsStep(
                    permissionsAsked = permissionsAsked,
                    companionSupported = companionSupported,
                    companionPaired = companionPaired,
                    pairing = pairing,
                    onRequestPermissions = onRequestPermissions,
                    onPair = onPair,
                )
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ConnectStep(
    form: ConfigFormState,
    connTest: ConnTest,
    onPasteLink: () -> Unit,
    onFormChange: ((ConfigFormState) -> ConfigFormState) -> Unit,
    onTest: () -> Unit,
) {
    Text(
        "pitstop logs drives, fuel and economy to your own Pitstop server. " +
            "It takes about a minute.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // ── Fast path: paste a setup link ──────────────────────────────
    FilledTonalButton(onClick = onPasteLink, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Paste a setup link")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  or enter it manually  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
    OutlinedTextField(
        value = form.apiBaseUrl,
        onValueChange = { v -> onFormChange { it.copy(apiBaseUrl = v) } },
        label = { Text("Server URL") },
        placeholder = { Text("http://10.0.0.x:8080") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Tokens are secrets: masked, with a visibility toggle — the same
    // field Settings uses (they used to show in plain text here).
    SecretField(
        label = "Ingest token",
        value = form.ingestToken,
        onValueChange = { v -> onFormChange { it.copy(ingestToken = v) } },
    )
    SecretField(
        label = "Query token",
        value = form.queryToken,
        onValueChange = { v -> onFormChange { it.copy(queryToken = v) } },
    )
    if (connTest == ConnTest.Idle) {
        Text(
            "Test reads your vehicle list with the Query token — it proves the URL " +
                "and token work before you move on. Nothing is written.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TestRow(connTest, onTest)
}

@Composable
private fun TestRow(connTest: ConnTest, onTest: () -> Unit) {
    val (pillState, pillLabel) = connTestPill(connTest)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(tone = pillState, label = pillLabel, compact = true, subject = "Connection")
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onTest, enabled = connTest != ConnTest.InProgress) {
            if (connTest == ConnTest.InProgress) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Test connection")
        }
    }
}

@Composable
private fun VehicleStep(
    form: ConfigFormState,
    connTest: ConnTest,
    onSlugChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    Text(
        "Drives and fillups from this phone are filed under this vehicle.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val vehicles = (connTest as? ConnTest.Ok)?.vehicles.orEmpty()
    if (vehicles.isNotEmpty()) {
        vehicles.forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = form.vehicleSlug == v.slug,
                        onClick = { onSlugChange(v.slug) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = form.vehicleSlug == v.slug, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(vehicleLabel(v), style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        // Not tested (or the server has none yet): type the slug, or go
        // back a step and Test to get the zero-typo list.
        OutlinedTextField(
            value = form.vehicleSlug,
            onValueChange = onSlugChange,
            label = { Text("Vehicle slug") },
            placeholder = { Text("e.g. my-car") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TestRow(connTest, onTest)
    }
}

@Composable
private fun PermissionsStep(
    permissionsAsked: Boolean,
    companionSupported: Boolean,
    companionPaired: Boolean,
    pairing: Boolean,
    onRequestPermissions: () -> Unit,
    onPair: () -> Unit,
) {
    Text(
        "Android asks for each of these separately. Here is why pitstop needs them:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReasonRow(Icons.Outlined.LocationOn, "Location", "Maps your drives and tags fillups with the station.")
    ReasonRow(Icons.Outlined.Bluetooth, "Nearby devices", "Talks to the WiCAN dongle over Bluetooth.")
    ReasonRow(Icons.Outlined.Notifications, "Notifications", "Shows the ongoing \"recording\" notification Android requires.")
    FilledTonalButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
        Text(if (permissionsAsked) "Review permissions again" else "Allow permissions")
    }
    if (companionSupported) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Pair the WiCAN (optional)", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Pairing lets drives start logging on their own when the dongle wakes — even " +
                        "with the app closed. Plug it in and turn the ignition on first; Android " +
                        "then shows a pairing dialog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (companionPaired) {
                    StatusPill(tone = PillTone.Healthy, label = "Paired", compact = true, subject = "WiCAN")
                } else {
                    OutlinedButton(onClick = onPair, enabled = !pairing) {
                        if (pairing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Pair WiCAN")
                    }
                }
            }
        }
    }
    Text(
        "You can change any of this later in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReasonRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun connTestPill(connTest: ConnTest): Pair<PillTone, String> = when (val c = connTest) {
    ConnTest.Idle -> PillTone.Neutral to "Not tested"
    ConnTest.InProgress -> PillTone.Connecting to "Testing…"
    is ConnTest.Ok -> PillTone.Healthy to "Connected · ${c.vehicles.size} vehicle" +
        if (c.vehicles.size == 1) "" else "s"
    ConnTest.BadUrl -> PillTone.Offline to "Check the URL"
    ConnTest.BadToken -> PillTone.Offline to "Check the Query token"
    is ConnTest.Unreachable -> PillTone.Offline to "Can't reach the server"
    is ConnTest.ServerError -> PillTone.Degraded to "Server error ${c.code}"
}

private fun vehicleLabel(v: VehicleDto): String {
    val detail = listOfNotNull(v.year?.toString(), v.make, v.model)
        .joinToString(" ")
        .trim()
    return if (detail.isBlank()) v.name else "${v.name}  ·  $detail"
}
