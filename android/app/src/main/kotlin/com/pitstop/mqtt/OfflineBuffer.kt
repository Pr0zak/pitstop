package com.pitstop.mqtt

import android.content.Context
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-backed FIFO for telemetry that couldn't be published live (no
 * cellular, broker unreachable, etc.). Each entry is one line:
 *   <epochMillis>\t<topic>\t<payload>\n
 *
 * Lifetime model: the bridge appends here whenever [MqttPublisher.publish]
 * returns false (no live connection). Once the broker reconnects, the
 * service drains the file in batches via [drain]. Any line that fails to
 * publish is preserved for the next drain pass.
 *
 * Capacity: bounded by [MAX_BYTES] (50 MB ≈ several drives' worth even
 * with full IMU + GPS + OBD telemetry). When an enqueue would exceed
 * the cap we discard the oldest ~25% of the file in one rewrite pass.
 * Stale data is the less interesting half of the trade-off vs running
 * the device out of storage.
 *
 * Thread-safety: a single [Mutex] gates all file operations. The bridge
 * may call enqueue from sensor callbacks at hundreds of Hz, so all I/O
 * is small (one append per call) and the mutex hold time stays short.
 */
@Singleton
class OfflineBuffer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logBuffer: LogBuffer,
) {

    private val mutex = Mutex()

    private val file: File by lazy {
        File(context.filesDir, FILE_NAME).also {
            // Hard-failing here would crash the bridge service; the buffer is
            // a nice-to-have, so we just log and keep going.
            runCatching { it.parentFile?.mkdirs() }
        }
    }

    /** Bytes currently sitting on disk. Used by the bridge UI for the buffer pill. */
    suspend fun byteCount(): Long = mutex.withLock { if (file.exists()) file.length() else 0L }

    suspend fun enqueue(topic: String, payload: String) {
        val line = "${System.currentTimeMillis()}\t${topic}\t${payload}\n"
        mutex.withLock {
            try {
                if (file.exists() && file.length() + line.length > MAX_BYTES) {
                    trimOldestLocked()
                }
                file.appendText(line)
            } catch (e: Exception) {
                logBuffer.warn(
                    "offline buffer enqueue failed",
                    mapOf("err" to (e.message ?: e::class.java.simpleName)),
                )
            }
        }
    }

    /**
     * Walk the file once and hand each entry to [publish]. Returns the
     * count of entries successfully drained. The first failed publish stops
     * the pass — we preserve the rest for the next drain so we don't re-send
     * the same backlog twice or interleave it with the live stream.
     *
     * The publish callback is intentionally synchronous-shaped (returns
     * Boolean). MqttPublisher's internal future completes async, but for
     * QoS 0 the meaningful failure mode is "not connected", which we can
     * answer immediately.
     */
    suspend fun drain(publish: (topic: String, payload: String) -> Boolean): DrainResult {
        return mutex.withLock {
            if (!file.exists() || file.length() == 0L) return@withLock DrainResult(0, 0L)

            val staging = File(context.filesDir, FILE_NAME_DRAINING)
            if (staging.exists()) staging.delete()
            if (!file.renameTo(staging)) {
                logBuffer.warn("offline buffer drain: rename failed, skipping pass")
                return@withLock DrainResult(0, file.length())
            }

            var drained = 0
            var firstFailure = false
            val pending = mutableListOf<String>()

            staging.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    if (firstFailure) {
                        pending.add(raw)
                        continue
                    }
                    val parts = raw.split('\t', limit = 3)
                    if (parts.size != 3) continue   // drop malformed
                    val ok = runCatching { publish(parts[1], parts[2]) }.getOrDefault(false)
                    if (ok) {
                        drained++
                    } else {
                        firstFailure = true
                        pending.add(raw)
                    }
                }
            }

            staging.delete()

            if (pending.isNotEmpty()) {
                file.bufferedWriter().use { w ->
                    for (line in pending) {
                        w.write(line)
                        w.newLine()
                    }
                }
            }

            DrainResult(drained, if (file.exists()) file.length() else 0L)
        }
    }

    /**
     * Drop the oldest ~25% of the file. Caller must hold [mutex].
     */
    private fun trimOldestLocked() {
        val len = file.length()
        if (len == 0L) return
        val skipBytes = (len / 4).coerceAtLeast(1L)
        val tmp = File(context.filesDir, FILE_NAME_TRIMMING)
        if (tmp.exists()) tmp.delete()
        try {
            file.inputStream().use { input ->
                input.skip(skipBytes)
                // Drop a partial-line prefix so we don't ship a half-entry to
                // the broker on the next drain.
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                }
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            file.delete()
            tmp.renameTo(file)
            logBuffer.info(
                "offline buffer trimmed",
                mapOf("dropped_bytes" to skipBytes, "remaining_bytes" to file.length()),
            )
        } catch (e: Exception) {
            tmp.delete()
            logBuffer.warn(
                "offline buffer trim failed",
                mapOf("err" to (e.message ?: e::class.java.simpleName)),
            )
        }
    }

    data class DrainResult(val drained: Int, val remainingBytes: Long)

    companion object {
        const val FILE_NAME = "offline_telemetry.log"
        const val FILE_NAME_DRAINING = "offline_telemetry.log.draining"
        const val FILE_NAME_TRIMMING = "offline_telemetry.log.trimming"
        const val MAX_BYTES: Long = 50L * 1024L * 1024L
    }
}
