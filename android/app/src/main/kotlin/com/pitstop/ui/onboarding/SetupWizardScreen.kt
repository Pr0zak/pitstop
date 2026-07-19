package com.pitstop.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pitstop.http.VehicleDto
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.StatusPill
import com.pitstop.ui.config.ConfigViewModel
import com.pitstop.ui.config.ConnTest
import com.pitstop.ui.config.ImportConfirmDialog

/**
 * First-run wizard (task #12). Shown by the app root instead of the pager while
 * the app is un-configured, so a brand-new install can't land on an empty Home
 * with no idea what to do. Single scrollable screen — reuses the shared
 * [ConfigViewModel] so the server/token/vehicle it captures is the exact same
 * form the Settings screen edits, and everything built for Settings (paste-link
 * import, the inline connection test, the vehicle picker) is reused here.
 *
 * [onDone] marks onboarding complete and drops the user into the pager. A "Skip"
 * path calls it too — the Home "Finish setup" card then nudges them.
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
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

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

    val configured = form.apiBaseUrl.isNotBlank() && form.vehicleSlug.isNotBlank()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(12.dp))
            Text(
                "Welcome to pitstop",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Connect this phone to your Pitstop server so it can log drives, " +
                    "fuel and MPG. It takes about a minute.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Fast path: paste a setup link ──────────────────────────
            OutlinedButton(
                onClick = {
                    val pasted = clipboard.getText()?.text
                    if (pasted.isNullOrBlank()) return@OutlinedButton
                    viewModel.importSetupLink(pasted)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
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

            // ── Manual entry ───────────────────────────────────────────
            OutlinedTextField(
                value = form.apiBaseUrl,
                onValueChange = { v ->
                    viewModel.update { it.copy(apiBaseUrl = v) }
                    viewModel.resetConnTest()
                },
                label = { Text("Server URL") },
                placeholder = { Text("http://10.0.0.x:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.ingestToken,
                onValueChange = { v ->
                    viewModel.update { it.copy(ingestToken = v) }
                    viewModel.resetConnTest()
                },
                label = { Text("Ingest token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.queryToken,
                onValueChange = { v ->
                    viewModel.update { it.copy(queryToken = v) }
                    viewModel.resetConnTest()
                },
                label = { Text("Query token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Test + status ──────────────────────────────────────────
            val (pillState, pillLabel) = connTestPill(connTest)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(state = pillState, label = pillLabel, compact = true)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = connTest != ConnTest.InProgress,
                ) {
                    if (connTest == ConnTest.InProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test connection")
                }
            }

            // ── Vehicle picker (once the test succeeds) ────────────────
            val vehicles = (connTest as? ConnTest.Ok)?.vehicles.orEmpty()
            if (vehicles.isNotEmpty()) {
                Text(
                    "Which vehicle is this phone?",
                    style = MaterialTheme.typography.titleSmall,
                )
                vehicles.forEach { v ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = form.vehicleSlug == v.slug,
                                onClick = { viewModel.update { it.copy(vehicleSlug = v.slug) } },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = form.vehicleSlug == v.slug,
                            onClick = { viewModel.update { it.copy(vehicleSlug = v.slug) } },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(vehicleLabel(v), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Spacer(Modifier.size(4.dp))
            Button(
                onClick = { viewModel.save(); onDone() },
                enabled = configured,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Finish setup")
            }
            TextButton(
                onClick = { viewModel.save(); onDone() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip for now")
            }
            Text(
                "You can change any of this later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

private fun connTestPill(connTest: ConnTest): Pair<PillState, String> = when (val c = connTest) {
    ConnTest.Idle -> PillState.Neutral to "Not tested"
    ConnTest.InProgress -> PillState.Connecting to "Testing…"
    is ConnTest.Ok -> PillState.Healthy to "Connected · ${c.vehicles.size} vehicle" +
        if (c.vehicles.size == 1) "" else "s"
    ConnTest.BadUrl -> PillState.Offline to "Check the URL"
    ConnTest.BadToken -> PillState.Offline to "Check the Query token"
    is ConnTest.Unreachable -> PillState.Offline to "Can't reach the server"
    is ConnTest.ServerError -> PillState.Degraded to "Server error ${c.code}"
}

private fun vehicleLabel(v: VehicleDto): String {
    val detail = listOfNotNull(v.year?.toString(), v.make, v.model)
        .joinToString(" ")
        .trim()
    return if (detail.isBlank()) v.name else "${v.name}  ·  $detail"
}
