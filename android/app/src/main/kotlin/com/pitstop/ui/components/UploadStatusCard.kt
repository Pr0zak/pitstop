package com.pitstop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.drive.UploadOutcome
import com.pitstop.drive.UploadPhase
import com.pitstop.drive.UploadProgress
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One card that answers "what is the phone uploading, how far along is
 * it, and did it finish?" — the questions the old one-line "Syncing…"
 * chip could not.
 *
 * Rendered on both Home and History off the same
 * [com.pitstop.drive.UploadProgressBus] state, so the two surfaces
 * cannot disagree, and a pass started by the post-drive auto-kick shows
 * up without the user having tapped anything.
 *
 * Returns without composing anything when there is genuinely nothing to
 * say: no queue, no pass running, and no recent result.
 */
@Composable
fun UploadStatusCard(
    progress: UploadProgress,
    pendingCount: Int,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = progress as? UploadProgress.Running
    val finished = progress as? UploadProgress.Finished

    // Tick once a second while a pass is live so the elapsed / stalled
    // readouts advance, and for as long as a finished summary is still
    // inside its display window so it retires on its own. Nothing
    // running and nothing recent → no timer at all.
    val nowMs by nowTicker(
        active = running != null || finished != null,
        untilMs = if (running != null) null else finished?.let { it.finishedAtMs + FINISHED_VISIBLE_MS },
    )

    val showFinished = finished != null &&
        (nowMs - finished.finishedAtMs) < FINISHED_VISIBLE_MS
    if (running == null && !showFinished && pendingCount == 0) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                running != null -> RunningBody(running, nowMs, onCancel)
                showFinished && finished != null -> FinishedBody(finished, pendingCount, onSync)
                else -> QueuedBody(pendingCount, onSync)
            }
        }
    }
}

@Composable
private fun RunningBody(
    running: UploadProgress.Running,
    nowMs: Long,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "Uploading drive ${running.driveIndex} of ${running.driveTotal}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }

    // Which drive — so "uploading" means something concrete rather than
    // an anonymous blob of bytes.
    Text(
        text = buildString {
            append(clockRange(running.driveStartedAtMs, running.driveEndedAtMs))
            append(" · ")
            append(formatCount(running.frameCount))
            append(" samples")
            if (running.priorAttempts > 0) {
                append(" · retry ${running.priorAttempts + 1}")
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val fraction = running.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    val phaseSeconds = ((nowMs - running.phaseSinceMs) / 1000L).coerceAtLeast(0L)
    Text(
        text = when (running.phase) {
            UploadPhase.Reading -> "Reading the saved drive…"
            UploadPhase.Sending ->
                if (running.payloadBytes > 0L) {
                    "Sent ${formatBytes(running.bytesSent)} of " +
                        formatBytes(running.payloadBytes)
                } else {
                    "Sending…"
                }
            // The upload is done; the backend is writing the rows. This
            // is the state that used to look like a hang, so it gets an
            // explicit label and a running clock.
            UploadPhase.AwaitingServer ->
                "Sent ${formatBytes(running.payloadBytes)} · waiting for the server " +
                    "(${phaseSeconds}s)"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (running.uploadedThisPass > 0) {
        Text(
            text = "${running.uploadedThisPass} done so far this sync",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FinishedBody(
    finished: UploadProgress.Finished,
    pendingCount: Int,
    onSync: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (finished.outcome) {
                    UploadOutcome.Completed ->
                        "Uploaded ${plural(finished.uploaded, "drive")}"
                    UploadOutcome.NothingQueued -> "Everything is already uploaded"
                    UploadOutcome.NetworkStopped ->
                        "Couldn't reach the server" +
                            if (finished.uploaded > 0) {
                                " after ${plural(finished.uploaded, "drive")}"
                            } else {
                                ""
                            }
                    UploadOutcome.Cancelled ->
                        "Sync cancelled" +
                            if (finished.uploaded > 0) {
                                " after ${plural(finished.uploaded, "drive")}"
                            } else {
                                ""
                            }
                    UploadOutcome.Stalled -> "Sync stopped — a drive keeps failing"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val detail = buildString {
                append(clockOf(finished.finishedAtMs))
                if (finished.remaining > 0) {
                    append(" · ${finished.remaining} still queued")
                }
                if (finished.rejected > 0) {
                    append(" · ${plural(finished.rejected, "drive")} rejected")
                }
                finished.detail?.takeIf {
                    finished.outcome == UploadOutcome.NetworkStopped ||
                        finished.outcome == UploadOutcome.Stalled
                }?.let { append(" · $it") }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pendingCount > 0) {
            SyncChip(label = "Retry", onClick = onSync)
        }
    }
}

@Composable
private fun QueuedBody(pendingCount: Int, onSync: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${plural(pendingCount, "drive")} waiting to upload",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        SyncChip(label = "Sync now", onClick = onSync)
    }
}

@Composable
private fun SyncChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

/**
 * Emits `System.currentTimeMillis()` once a second while [active],
 * stopping at [untilMs] when one is given (null = until [active] goes
 * false). One extra emission lands past the deadline so whatever the
 * clock was gating actually re-evaluates.
 */
@Composable
private fun nowTicker(active: Boolean, untilMs: Long?): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), active, untilMs) {
        value = System.currentTimeMillis()
        if (!active) return@produceState
        while (untilMs == null || value < untilMs) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }

// ── Formatting helpers ──────────────────────────────────────────────

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockOf(epochMs: Long): String =
    HHMM.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun clockRange(startMs: Long, endMs: Long): String =
    "${clockOf(startMs)}–${clockOf(endMs)}"

private fun plural(n: Int, noun: String): String =
    "$n $noun${if (n == 1) "" else "s"}"

/** Thousands separators without pulling in a locale-aware formatter for
 *  a single label. */
internal fun formatCount(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/** Binary units, one decimal above a megabyte — payloads here run from
 *  tens of kilobytes to a handful of megabytes. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

/** How long a finished summary stays on screen before the card falls
 *  back to the queued/empty state. */
private const val FINISHED_VISIBLE_MS = 60_000L
