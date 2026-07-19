package com.pitstop.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Confirmation gate for a `pitstop://setup?…` credential handoff. Because such a
 * link is reachable from any web page / QR / message / co-installed app and
 * carries a server URL + auth tokens, we NEVER apply it silently — this dialog
 * shows exactly which server it would point uploads at (so a malicious host is
 * visible) and what it will replace, and only [onConfirm] applies it.
 */
@Composable
fun ImportConfirmDialog(
    payload: SetupPayload,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Apply this setup link?") },
        text = {
            Column {
                val server = payload.apiBaseUrl
                if (!server.isNullOrBlank()) {
                    Text(
                        "This will point pitstop — including your drive, GPS and " +
                            "vehicle data — at:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        server,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                val fields = payload.importedFields()
                if (fields.isNotEmpty()) {
                    Text(
                        "It will set: ${fields.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Only continue if you created this link or trust where it came from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
