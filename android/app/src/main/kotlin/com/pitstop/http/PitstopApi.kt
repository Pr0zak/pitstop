package com.pitstop.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ============================================================================
// Write path — INGEST_TOKEN auth (PitstopAuthInterceptor switches by method).
// ============================================================================

@Serializable
data class FillupRequest(
    @SerialName("vehicle_slug") val vehicleSlug: String,
    @SerialName("ts") val timestampIso: String,
    @SerialName("gallons") val gallons: Double,
    @SerialName("total_price") val totalPrice: Double,
    @SerialName("odometer_mi") val odometerMi: Double? = null,
    @SerialName("partial") val partial: Boolean = false,
    @SerialName("lat") val lat: Double? = null,
    @SerialName("lon") val lon: Double? = null,
    @SerialName("station_name") val stationName: String? = null,
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class FillupResponse(
    @SerialName("id") val id: String,
)

@Serializable
data class LogEntryDto(
    @SerialName("ts") val ts: String? = null,
    @SerialName("source") val source: String = "phone",
    @SerialName("level") val level: String,
    @SerialName("message") val message: String,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("context") val context: JsonElement? = null,
)

@Serializable
data class LogBatchRequest(
    @SerialName("entries") val entries: List<LogEntryDto>,
)

@Serializable
data class LogBatchResponse(
    @SerialName("accepted") val accepted: Int,
)

// ============================================================================
// Read path — QUERY_TOKEN auth. Mirrors the web frontend's API client so the
// phone Home tab can render the same hero metrics + sparkline the web Overview
// shows. Only the fields actually consumed by the phone today are declared;
// adding more is a one-line append (kotlinx-serialization with
// ignoreUnknownKeys = true tolerates the rest of the payload).
// ============================================================================

@Serializable
data class VehicleDto(
    @SerialName("id") val id: String,
    @SerialName("slug") val slug: String,
    @SerialName("name") val name: String,
    @SerialName("year") val year: Int? = null,
    @SerialName("make") val make: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("tank1_capacity") val tank1Capacity: Double? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("last_metric") val lastMetric: String? = null,
)

@Serializable
data class FillupDto(
    @SerialName("id") val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("fillup_date") val fillupDate: String,
    @SerialName("odo") val odo: Double,
    @SerialName("fuel_volume") val fuelVolume: Double? = null,
    @SerialName("is_full") val isFull: Boolean = true,
    @SerialName("is_missed") val isMissed: Boolean = false,
    @SerialName("price_total") val priceTotal: Double? = null,
    @SerialName("price_per_unit") val pricePerUnit: Double? = null,
    @SerialName("mpg") val mpg: Double? = null,
    @SerialName("mpg_reported") val mpgReported: Double? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("lat") val lat: Double? = null,
    @SerialName("lon") val lon: Double? = null,
    @SerialName("station_id") val stationId: String? = null,
    @SerialName("fuel_type") val fuelType: Int? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("weather_temp_c") val weatherTempC: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
)


@Serializable
data class MpgPointDto(
    @SerialName("period") val period: String,
    @SerialName("mpg") val mpg: Double? = null,
    @SerialName("miles") val miles: Double? = null,
    @SerialName("volume") val volume: Double? = null,
)

@Serializable
data class MpgTrendResponse(
    @SerialName("points") val points: List<MpgPointDto>,
)

interface PitstopApi {
    // Path conventions, given the deployed Caddy in front of the backend:
    //   /api/*   — Caddy strips /api/, forwards to backend root mount
    //              points (vehicles, fillups, analytics, ...). Same shape
    //              as the web frontend's API client.
    //   /phone/* — Caddy forwards verbatim. Backend's api_phone.py owns
    //              that prefix for the slug-based write aliases the phone
    //              needs (POST /phone/fillups with vehicle_slug instead
    //              of UUID).
    //   /ws/*    — backend's WebSocket endpoint.
    // The auth interceptor switches token by HTTP method (GET → query
    // token, POST → ingest token).

    // ── Write path: phone-shaped, vehicle_slug-based ───────────────
    @POST("phone/fillups")
    suspend fun postFillup(@Body body: FillupRequest): FillupResponse

    @POST("api/logs")
    suspend fun postLogs(@Body body: LogBatchRequest): LogBatchResponse

    /**
     * Atomic per-drive batch upload (Task #117). The phone seals a
     * drive on the engine-off + presence-gone gate and POSTs the
     * whole thing — PIDs, GPS, engine events, IMU — in one request.
     * The server keys idempotency on `client_drive_uuid`.
     */
    @POST("api/ingest/drive")
    suspend fun postDrive(
        @Body body: com.pitstop.drive.DriveUploadDto,
    ): com.pitstop.drive.DriveUploadResponseDto

    // ── Read path: shared with the web frontend, /api/ stripped by Caddy ─
    @GET("api/vehicles")
    suspend fun getVehicles(): List<VehicleDto>

    /**
     * Returns a bare list (not wrapped). Backend returns
     * `[{...}, {...}]` ordered by fillup_date DESC.
     */
    @GET("api/fillups")
    suspend fun getFillups(
        @Query("vehicle_id") vehicleId: String,
        @Query("limit") limit: Int = 30,
    ): List<FillupDto>

    @GET("api/analytics/mpg")
    suspend fun getMpgTrend(
        @Query("vehicle_id") vehicleId: String,
        @Query("window") window: String = "year",
    ): MpgTrendResponse

    @GET("api/trips")
    suspend fun getTrips(
        @Query("vehicle_id") vehicleId: String,
        @Query("limit") limit: Int = 5,
    ): List<TripDto>

    @GET("api/dtcs")
    suspend fun getDtcs(
        @Query("vehicle_id") vehicleId: String,
        @Query("active_only") activeOnly: Boolean = true,
    ): List<DtcDto>

    // ── Detail endpoints (Task #122) — the History tab drills into ─────
    // these via TripDetailScreen / FillupDetailScreen / DTCDetailScreen.
    // All three return the same payloads the web frontend already
    // consumes; the phone declares only the fields it renders today and
    // relies on Json { ignoreUnknownKeys = true } for the rest.

    @GET("api/trips/{id}")
    suspend fun getTripDetail(@Path("id") id: String): TripDetailDto

    @GET("api/trips/{id}/route")
    suspend fun getTripRoute(@Path("id") id: String): TripRouteDto

    @GET("api/fillups/{id}")
    suspend fun getFillupDetail(@Path("id") id: String): FillupDto

    /**
     * DTC timeline groups all events by code over `days` for the given
     * vehicle. DTCDetailScreen filters client-side to the requested
     * code — there's no per-code endpoint and the response is small
     * (one row per distinct code, typically <10 for a real vehicle).
     */
    @GET("api/dtcs/timeline")
    suspend fun getDtcTimeline(
        @Query("vehicle_id") vehicleId: String,
        @Query("days") days: Int = 365,
    ): DtcTimelineResponse
}

@kotlinx.serialization.Serializable
data class DtcDto(
    val id: String,
    @kotlinx.serialization.SerialName("vehicle_id") val vehicleId: String,
    val code: String,
    @kotlinx.serialization.SerialName("seen_at") val seenAt: String,
    @kotlinx.serialization.SerialName("cleared_at") val clearedAt: String? = null,
    val description: String? = null,
)

@kotlinx.serialization.Serializable
data class TripDto(
    val id: String,
    @kotlinx.serialization.SerialName("vehicle_id") val vehicleId: String,
    @kotlinx.serialization.SerialName("started_at") val startedAt: String,
    @kotlinx.serialization.SerialName("ended_at") val endedAt: String? = null,
    @kotlinx.serialization.SerialName("duration_s") val durationS: Int? = null,
    @kotlinx.serialization.SerialName("distance_km") val distanceKm: Double? = null,
    @kotlinx.serialization.SerialName("max_speed_kph") val maxSpeedKph: Double? = null,
    @kotlinx.serialization.SerialName("max_rpm") val maxRpm: Double? = null,
    @kotlinx.serialization.SerialName("fuel_used_l") val fuelUsedL: Double? = null,
    @kotlinx.serialization.SerialName("dtc_count") val dtcCount: Int = 0,
    val category: String? = null,
)

/**
 * Full trip payload returned by `/api/trips/{id}`. Mirrors the
 * `TripDetail` Pydantic schema the web frontend consumes plus the
 * derived bundles (`samples`, `dtcs`, `imu_events`, `odo_*`,
 * weather, ...). Unknown keys are ignored at the Json layer.
 */
@kotlinx.serialization.Serializable
data class TripDetailDto(
    val id: String,
    @kotlinx.serialization.SerialName("vehicle_id") val vehicleId: String,
    @kotlinx.serialization.SerialName("started_at") val startedAt: String,
    @kotlinx.serialization.SerialName("ended_at") val endedAt: String? = null,
    @kotlinx.serialization.SerialName("duration_s") val durationS: Int? = null,
    @kotlinx.serialization.SerialName("distance_km") val distanceKm: Double? = null,
    @kotlinx.serialization.SerialName("max_rpm") val maxRpm: Double? = null,
    @kotlinx.serialization.SerialName("max_speed_kph") val maxSpeedKph: Double? = null,
    @kotlinx.serialization.SerialName("avg_speed_kph") val avgSpeedKph: Double? = null,
    @kotlinx.serialization.SerialName("avg_coolant_c") val avgCoolantC: Double? = null,
    @kotlinx.serialization.SerialName("fuel_used_l") val fuelUsedL: Double? = null,
    @kotlinx.serialization.SerialName("dtc_count") val dtcCount: Int = 0,
    @kotlinx.serialization.SerialName("idle_s") val idleS: Int? = null,
    val category: String? = null,
    val notes: String? = null,
    @kotlinx.serialization.SerialName("weather_temp_c") val weatherTempC: Double? = null,
    @kotlinx.serialization.SerialName("weather_humidity_pct") val weatherHumidityPct: Double? = null,
    @kotlinx.serialization.SerialName("weather_precip_mm") val weatherPrecipMm: Double? = null,
    @kotlinx.serialization.SerialName("weather_wind_kph") val weatherWindKph: Double? = null,
    @kotlinx.serialization.SerialName("weather_code") val weatherCode: Int? = null,
    val source: String? = null,
    val incomplete: Boolean? = null,
    @kotlinx.serialization.SerialName("odo_start_km") val odoStartKm: Double? = null,
    @kotlinx.serialization.SerialName("odo_end_km") val odoEndKm: Double? = null,
    val samples: List<TripSampleDto> = emptyList(),
    val dtcs: List<TripDtcDto> = emptyList(),
    @kotlinx.serialization.SerialName("imu_events") val imuEvents: List<ImuEventDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class TripSampleDto(
    val time: String,
    val metric: String,
    @kotlinx.serialization.SerialName("value_num") val valueNum: Double? = null,
)

@kotlinx.serialization.Serializable
data class TripDtcDto(
    val id: String,
    val code: String,
    @kotlinx.serialization.SerialName("seen_at") val seenAt: String,
    val description: String? = null,
)

@kotlinx.serialization.Serializable
data class ImuEventDto(
    val t: String,
    val magnitude: Double,
    val lat: Double? = null,
    val lon: Double? = null,
)

@kotlinx.serialization.Serializable
data class TripRouteDto(
    @kotlinx.serialization.SerialName("trip_id") val tripId: String,
    val points: List<RoutePointDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class RoutePointDto(
    val t: String,
    val lat: Double,
    val lon: Double,
    @kotlinx.serialization.SerialName("alt_m") val altM: Double? = null,
    @kotlinx.serialization.SerialName("speed_mps") val speedMps: Double? = null,
    @kotlinx.serialization.SerialName("heading_deg") val headingDeg: Double? = null,
    @kotlinx.serialization.SerialName("accuracy_m") val accuracyM: Double? = null,
)

@kotlinx.serialization.Serializable
data class DtcTimelineResponse(
    val codes: List<DtcTimelineCode> = emptyList(),
    @kotlinx.serialization.SerialName("window_days") val windowDays: Int = 365,
)

@kotlinx.serialization.Serializable
data class DtcTimelineCode(
    val code: String,
    val description: String? = null,
    val count: Int = 0,
    @kotlinx.serialization.SerialName("first_seen") val firstSeen: String? = null,
    @kotlinx.serialization.SerialName("last_seen") val lastSeen: String? = null,
    val active: Boolean = false,
    val events: List<DtcTimelineEvent> = emptyList(),
)

@kotlinx.serialization.Serializable
data class DtcTimelineEvent(
    val id: String,
    @kotlinx.serialization.SerialName("seen_at") val seenAt: String,
)
