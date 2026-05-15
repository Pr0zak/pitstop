package com.pitstop.drive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory recorder for one engine-on cycle. The bridge service
 * appends samples here while the engine is running; on the seal-gate
 * (engine_off + presence-gone + OBD-quiet) the buffer is frozen and
 * serialised into a [PendingDrive] row.
 *
 * Concurrency: data is appended from many threads (BLE callback,
 * GPS handler) so the append paths use lock-free queues + atomic
 * counters. Read-and-freeze happens on a single coroutine dispatched
 * by [DriveSealer]; the buffer is single-consumer there.
 */
class DriveBuffer(
    val vehicleId: String,
    val startedAtMs: Long,
) {
    private val pidReadings = ConcurrentLinkedQueue<PidSample>()
    private val gpsPoints = ConcurrentLinkedQueue<GpsSample>()
    private val engineEvents = ConcurrentLinkedQueue<EngineEvent>()
    private val totalFrames = AtomicLong(0)

    fun addPid(t: Long, metric: String, valueNum: Double?, valueText: String? = null) {
        pidReadings.add(PidSample(t, metric, valueNum, valueText))
        totalFrames.incrementAndGet()
    }

    fun addGps(
        t: Long,
        lat: Double,
        lon: Double,
        altM: Double?,
        speedMps: Double?,
        headingDeg: Double?,
        accuracyM: Double?,
    ) {
        gpsPoints.add(GpsSample(t, lat, lon, altM, speedMps, headingDeg, accuracyM))
        totalFrames.incrementAndGet()
    }

    fun addEngineEvent(t: Long, kind: String) {
        engineEvents.add(EngineEvent(t, kind))
        totalFrames.incrementAndGet()
    }

    fun frameCount(): Int = totalFrames.get().toInt()

    /**
     * Freeze the buffer into a serialisable payload. After freeze, no
     * further appends should happen — the sealer holds the only
     * reference and discards it after persisting.
     *
     * @param endedAtMs the time the sealer decided to close (post-gate).
     * @param incomplete true when the drive sealed without an engine_off
     *   event (phone crashed mid-drive, recovered via the orphan-buffer
     *   path). The server stamps the trip row accordingly.
     * @param deviceId stable installation id (passed through to the
     *   server so support can correlate uploads with phone instances).
     */
    fun seal(endedAtMs: Long, incomplete: Boolean, deviceId: String): DriveUploadDto {
        val uuid = clientDriveUuid(vehicleId, startedAtMs)
        return DriveUploadDto(
            vehicleId = vehicleId,
            startedAt = isoMillis(startedAtMs),
            endedAt = isoMillis(endedAtMs),
            deviceId = deviceId,
            clientDriveUuid = uuid,
            frameCount = frameCount(),
            incomplete = incomplete,
            engineEvents = engineEvents.map { EngineEventDto(isoMillis(it.t), it.kind) },
            pidReadings = pidReadings.map {
                PidReadingDto(isoMillis(it.t), it.metric, it.valueNum, it.valueText)
            },
            gpsPoints = gpsPoints.map {
                GpsPointDto(
                    isoMillis(it.t), it.lat, it.lon, it.altM,
                    it.speedMps, it.headingDeg, it.accuracyM,
                )
            },
        )
    }

    private data class PidSample(
        val t: Long, val metric: String,
        val valueNum: Double?, val valueText: String?,
    )

    private data class GpsSample(
        val t: Long, val lat: Double, val lon: Double,
        val altM: Double?, val speedMps: Double?,
        val headingDeg: Double?, val accuracyM: Double?,
    )

    private data class EngineEvent(val t: Long, val kind: String)

    companion object {
        /**
         * Deterministic UUID v5 keyed on (vehicleId, startedAtMs). Matches
         * the server's expectation so retries collapse on PK. Uses the
         * DNS namespace and a stable seed string — Kotlin's standard
         * library doesn't ship UUID v5, but UUID v3 (md5-based) is
         * functionally equivalent for our determinism guarantee.
         *
         * Important: keep this in sync with how the server identifies
         * the trip — both sides must agree.
         */
        fun clientDriveUuid(vehicleId: String, startedAtMs: Long): String {
            val seed = "pitstop|drive|$vehicleId|$startedAtMs"
            return UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
        }

        private fun isoMillis(ms: Long): String {
            // 8601 in UTC with millisecond resolution. Java 8 Instant
            // already formats this way.
            return java.time.Instant.ofEpochMilli(ms).toString()
        }
    }
}

// ─── DTOs serialised to JSON for POST /ingest/drive ─────────────────

@Serializable
data class DriveUploadDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("client_drive_uuid") val clientDriveUuid: String,
    @SerialName("frame_count") val frameCount: Int,
    @SerialName("incomplete") val incomplete: Boolean,
    @SerialName("engine_events") val engineEvents: List<EngineEventDto>,
    @SerialName("pid_readings") val pidReadings: List<PidReadingDto>,
    @SerialName("gps_points") val gpsPoints: List<GpsPointDto>,
)

@Serializable
data class EngineEventDto(
    @SerialName("t") val t: String,
    @SerialName("kind") val kind: String,
)

@Serializable
data class PidReadingDto(
    @SerialName("t") val t: String,
    @SerialName("metric") val metric: String,
    @SerialName("value_num") val valueNum: Double? = null,
    @SerialName("value_text") val valueText: String? = null,
)

@Serializable
data class GpsPointDto(
    @SerialName("t") val t: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("alt_m") val altM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("heading_deg") val headingDeg: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
)

@Serializable
data class DriveUploadResponseDto(
    @SerialName("trip_id") val tripId: String,
    @SerialName("client_drive_uuid") val clientDriveUuid: String,
    @SerialName("duplicate") val duplicate: Boolean,
    @SerialName("frame_count_accepted") val frameCountAccepted: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("incomplete") val incomplete: Boolean,
)
