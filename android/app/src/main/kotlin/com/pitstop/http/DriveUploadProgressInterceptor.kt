package com.pitstop.http

import com.pitstop.drive.UploadPhase
import com.pitstop.drive.UploadProgressBus
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports how much of a drive payload has actually gone out on the
 * wire, so the UI can show "1.4 MB of 3.2 MB sent" instead of an
 * unchanging "Syncing…" for the better part of a minute.
 *
 * Registered as a **network** interceptor: it must wrap the body that
 * OkHttp writes to the socket for this attempt, and a retry gets its
 * own pass through here (and so restarts the byte count) rather than
 * silently continuing a stale total.
 *
 * Only `POST /api/ingest/drive` is instrumented. Every other request —
 * fillups, logs, all the GETs — passes through untouched.
 */
@Singleton
class DriveUploadProgressInterceptor @Inject constructor(
    private val bus: UploadProgressBus,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isDriveUpload(request)) return chain.proceed(request)
        val body = request.body ?: return chain.proceed(request)

        val counting = CountingRequestBody(body) { sent, total ->
            bus.updateRunning { running ->
                val known = if (total > 0L) total else running.payloadBytes
                // Body fully written but the response hasn't landed: the
                // server is now doing the work. Flip the phase so the UI
                // can say so (and start ageing it) instead of showing a
                // progress bar frozen at 100 %.
                val phase = if (known > 0L && sent >= known) {
                    UploadPhase.AwaitingServer
                } else {
                    UploadPhase.Sending
                }
                running.copy(
                    bytesSent = sent,
                    payloadBytes = known,
                    phase = phase,
                    phaseSinceMs = if (phase != running.phase) {
                        System.currentTimeMillis()
                    } else {
                        running.phaseSinceMs
                    },
                )
            }
        }
        return chain.proceed(
            request.newBuilder().method(request.method, counting).build(),
        )
    }

    private fun isDriveUpload(request: Request): Boolean =
        request.method.equals("POST", ignoreCase = true) &&
            request.url.encodedPath.endsWith(DRIVE_INGEST_PATH)

    private companion object {
        const val DRIVE_INGEST_PATH = "/ingest/drive"
    }
}

/**
 * [RequestBody] decorator that counts bytes as they are written.
 *
 * Progress is reported at most once per [REPORT_EVERY_BYTES] (plus a
 * final callback on completion). Okio hands us 8 KiB segments, so an
 * uninstrumented 5 MB payload would otherwise push ~600 StateFlow
 * emissions through to Compose for no visible gain.
 */
private class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = runCatching { contentLength() }.getOrDefault(-1L)
        var written = 0L
        var reported = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (written - reported >= REPORT_EVERY_BYTES) {
                    reported = written
                    onProgress(written, total)
                }
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
        onProgress(written, total)
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 64L * 1024L
    }
}
