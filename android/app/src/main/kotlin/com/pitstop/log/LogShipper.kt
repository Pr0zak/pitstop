package com.pitstop.log

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings as AndroidSettings
import com.pitstop.data.SettingsRepository
import com.pitstop.http.LogBatchRequest
import com.pitstop.http.LogEntryDto
import com.pitstop.http.PitstopApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains [LogBuffer] into the backend's `/api/logs` endpoint.
 *
 * Lifecycle: [start] is called from [com.pitstop.service.PitstopBridgeService.onCreate];
 * [stop] from `onDestroy`. The shipper outlives a single bridge "session" (start/stop
 * button) — we want logs to survive even after the bridge shuts down — so it's a
 * Singleton with its own scope, not tied to the service's coroutine scope.
 *
 * Flush triggers (whichever happens first):
 *  - 60 s tick (or 120 s when [batterySaving] is true)
 *  - buffer length >= 50
 *  - explicit [flushNow]
 *
 * Failure handling: on POST failure the entries are returned to the head of the buffer
 * (so order is preserved) and an exponential backoff (2s -> 4s -> 8s ... cap 60s) gates
 * the next attempt.
 *
 * Privacy notes:
 *  - We DO NOT include any auth token in log context. The POST itself uses the same
 *    bearer token via [com.pitstop.http.PitstopAuthInterceptor].
 *  - `device_id` is `Settings.Secure.ANDROID_ID` (or a UUID fallback). On the user's own
 *    infra this is fine; documented so it isn't surprising.
 */
@Singleton
class LogShipper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buffer: LogBuffer,
    private val api: PitstopApi,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()

    @Volatile private var deviceIdCache: String? = null
    @Volatile private var loopJob: Job? = null

    /** Halve the flush rate when the BLE/MQTT layer is in a long-disconnect backoff. */
    @Volatile var batterySaving: Boolean = false

    private val _lastFlushAtMs = MutableStateFlow<Long?>(null)
    val lastFlushAtMs: StateFlow<Long?> = _lastFlushAtMs.asStateFlow()

    private val _lastFlushResult = MutableStateFlow<FlushResult?>(null)
    val lastFlushResult: StateFlow<FlushResult?> = _lastFlushResult.asStateFlow()

    /**
     * Capacity-1 conflated channel: an eager-flush trigger from any source coalesces with
     * pending triggers, and the loop wakes immediately. Used instead of a busy-wait poll.
     */
    private val triggerChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Idempotent: subsequent start() calls are no-ops while the loop is running.
     */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            // Kick off device-id resolution once.
            launch { resolveDeviceId() }

            // Watch the buffer to fire eager flushes when it crosses the 50-entry threshold.
            launch {
                buffer.bufferedCount.collect { count ->
                    if (count >= EAGER_FLUSH_THRESHOLD) bumpTrigger()
                }
            }

            runFlushLoop()
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Force a flush. Returns the count actually flushed (-1 on failure, 0 on empty buffer).
     * Suspends until the flush attempt completes.
     */
    suspend fun flushNow(): Int {
        if (buffer.snapshotSize() == 0) {
            _lastFlushResult.value = FlushResult.Empty
            _lastFlushAtMs.value = System.currentTimeMillis()
            return 0
        }
        return tryFlushOnce()
    }

    private suspend fun runFlushLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (scope.isActive) {
            val flushPeriod = if (batterySaving) FLUSH_PERIOD_BATTERY_MS else FLUSH_PERIOD_MS
            // Wait for the period OR a trigger (eager flush / explicit flushNow). The
            // channel is conflated, so a stale trigger queued during an earlier flush is
            // consumed cleanly here.
            withTimeoutOrNull(flushPeriod) { triggerChannel.receive() }

            if (buffer.snapshotSize() == 0) {
                // nothing to flush; reset backoff
                backoffMs = MIN_BACKOFF_MS
                continue
            }

            val accepted = try {
                tryFlushOnce()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                -1
            }
            backoffMs = if (accepted >= 0) {
                MIN_BACKOFF_MS
            } else {
                // exponential backoff up to MAX_BACKOFF_MS; sleep once before retrying
                delay(backoffMs)
                (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun tryFlushOnce(): Int = flushMutex.withLock {
        val drained = buffer.drain(MAX_BATCH_SIZE)
        if (drained.isEmpty()) {
            _lastFlushResult.value = FlushResult.Empty
            _lastFlushAtMs.value = System.currentTimeMillis()
            return@withLock 0
        }

        val deviceId = deviceIdCache ?: resolveDeviceId()
        val dtos = drained.map { it.toDto(deviceId) }
        try {
            val resp = api.postLogs(LogBatchRequest(entries = dtos))
            _lastFlushResult.value = FlushResult.Ok(count = resp.accepted)
            _lastFlushAtMs.value = System.currentTimeMillis()
            resp.accepted
        } catch (ce: CancellationException) {
            // On cancellation, return the entries — we'll retry next start.
            buffer.returnToHead(drained)
            throw ce
        } catch (t: Throwable) {
            buffer.returnToHead(drained)
            _lastFlushResult.value = FlushResult.Error(t.message ?: t::class.java.simpleName)
            _lastFlushAtMs.value = System.currentTimeMillis()
            -1
        }
    }

    private fun bumpTrigger() {
        triggerChannel.trySend(Unit)
    }

    @SuppressLint("HardwareIds")
    private suspend fun resolveDeviceId(): String {
        deviceIdCache?.let { return it }
        val persisted = settingsRepository.deviceIdOrNull()
        if (!persisted.isNullOrBlank()) {
            deviceIdCache = persisted
            return persisted
        }
        // Settings.Secure.ANDROID_ID is documented as "stable across reinstall on most
        // devices, but not after factory reset". Good enough for log correlation on the
        // user's own infra. If empty (rare), fall back to a random UUID.
        val androidId = try {
            AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val resolved = if (!androidId.isNullOrBlank()) {
            "android-$androidId"
        } else {
            "android-${UUID.randomUUID()}"
        }
        settingsRepository.setDeviceId(resolved)
        deviceIdCache = resolved
        return resolved
    }

    sealed interface FlushResult {
        object Empty : FlushResult
        data class Ok(val count: Int) : FlushResult
        data class Error(val message: String) : FlushResult
    }

    companion object {
        const val MAX_BATCH_SIZE = 500
        const val EAGER_FLUSH_THRESHOLD = 50
        const val FLUSH_PERIOD_MS = 60_000L
        const val FLUSH_PERIOD_BATTERY_MS = 120_000L
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}

private fun LogBuffer.LogEntry.toDto(deviceId: String): LogEntryDto =
    LogEntryDto(
        ts = DateTimeFormatter.ISO_INSTANT.format(clientTs),
        source = "phone",
        level = level.wire,
        message = message,
        deviceId = deviceId,
        context = context,
    )
