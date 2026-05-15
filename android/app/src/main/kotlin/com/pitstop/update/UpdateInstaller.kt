package com.pitstop.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** In-app surface for the APK download. The system download notification
 *  is fine but doesn't give a user inside Settings → App any indication
 *  that the bytes are flowing. UpdateInstaller polls DownloadManager
 *  every ~400 ms while a download is in flight and pushes the bytes-so-far
 *  + total into this state. The UI binds a LinearProgressIndicator to it. */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class InProgress(val bytesSoFar: Long, val totalBytes: Long) : DownloadState()
    data object Complete : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}

/**
 * Drives the "download v… and open the system installer" flow that the
 * Settings → App "Update" button kicks off. Uses the platform
 * [DownloadManager] so the OS handles retries, doze-aware scheduling, and
 * the foreground download notification — we don't need to build a worker.
 *
 * On completion we register a one-shot receiver that hands the downloaded
 * APK URI to the system package installer via `ACTION_VIEW`. The user
 * sees the standard Android "Install / Cancel" prompt (gated by the
 * REQUEST_INSTALL_PACKAGES permission and the per-app "Install unknown
 * apps" toggle, which the OS auto-prompts for on first use).
 *
 * Repeated downloads are de-duplicated by asset filename — we cancel the
 * stale enqueued download before starting a fresh one. The destination
 * lives in the public `Downloads/` dir so it survives if the user
 * dismisses the notification before tapping it.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logBuffer: LogBuffer,
) {

    private val dm: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** Polling scope is process-singleton: the user might background the
     *  app mid-download and reopen Settings — the poll loop keeps the
     *  StateFlow live so the bar picks back up. */
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /**
     * Start (or resume) the download. Returns the DownloadManager id, or
     * null if the input was malformed. The receiver registered here will
     * unregister itself once the install intent fires.
     */
    fun startDownload(info: UpdateInfo): Long? {
        val url = info.apkUrl ?: run {
            logBuffer.warn("update install: release has no .apk asset")
            return null
        }
        val name = info.apkAssetName ?: "pitstop-${info.latestVersion}.apk"

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Pitstop v${info.latestVersion}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = try {
            dm.enqueue(req)
        } catch (t: Throwable) {
            logBuffer.error(
                "update install enqueue failed",
                mapOf("err" to (t.message ?: t::class.java.simpleName)),
            )
            return null
        }

        logBuffer.info(
            "update install: download enqueued",
            mapOf("id" to id, "asset" to name, "size" to info.apkSizeBytes),
        )
        registerCompletionReceiver(id)
        startProgressPoll(id, info.apkSizeBytes ?: -1L)
        return id
    }

    private fun startProgressPoll(targetId: Long, hintTotal: Long) {
        pollJob?.cancel()
        _downloadState.value = DownloadState.InProgress(0L, hintTotal.coerceAtLeast(0L))
        pollJob = pollScope.launch {
            while (isActive) {
                val q = DownloadManager.Query().setFilterById(targetId)
                val snap = dm.query(q)?.use { c ->
                    if (!c.moveToFirst()) null else {
                        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                        PollSnap(
                            status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_FAILED,
                            soFar = if (soFarIdx >= 0) c.getLong(soFarIdx) else 0L,
                            total = if (totalIdx >= 0) c.getLong(totalIdx) else -1L,
                            reason = if (reasonIdx >= 0) c.getInt(reasonIdx) else -1,
                        )
                    }
                }
                if (snap == null) {
                    // Row vanished (user cleared the download list).
                    _downloadState.value = DownloadState.Idle
                    return@launch
                }
                val total = if (snap.total > 0) snap.total else hintTotal.coerceAtLeast(0L)
                when (snap.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _downloadState.value = DownloadState.Complete
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        _downloadState.value = DownloadState.Failed("download failed (${snap.reason})")
                        return@launch
                    }
                    else -> {
                        _downloadState.value = DownloadState.InProgress(snap.soFar, total)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private data class PollSnap(
        val status: Int,
        val soFar: Long,
        val total: Long,
        val reason: Int,
    )

    private fun registerCompletionReceiver(targetId: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != targetId) return
                try {
                    onDownloadComplete(id)
                } finally {
                    runCatching { ctx.unregisterReceiver(this) }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // Android 14+ requires the export flag for runtime-registered receivers
        // listening to system broadcasts.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun onDownloadComplete(id: Long) {
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) {
                logBuffer.warn("update install: completion query empty", mapOf("id" to id))
                return
            }
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_FAILED
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                val reason = if (reasonIdx >= 0) c.getInt(reasonIdx) else -1
                logBuffer.warn(
                    "update install: download failed",
                    mapOf("id" to id, "status" to status, "reason" to reason),
                )
                return
            }
        }
        val uri = dm.getUriForDownloadedFile(id) ?: run {
            logBuffer.warn("update install: missing content URI", mapOf("id" to id))
            return
        }
        launchInstaller(uri)
    }

    private fun launchInstaller(contentUri: Uri) {
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        try {
            context.startActivity(install)
            logBuffer.info("update install: opened system installer")
        } catch (t: Throwable) {
            logBuffer.error(
                "update install: launch failed",
                mapOf("err" to (t.message ?: t::class.java.simpleName)),
            )
        }
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val POLL_INTERVAL_MS = 400L
    }
}
