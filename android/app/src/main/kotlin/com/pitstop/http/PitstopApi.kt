package com.pitstop.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
    // ── Write path ──────────────────────────────────────────────────
    @POST("api/fillups")
    suspend fun postFillup(@Body body: FillupRequest): FillupResponse

    @POST("api/logs")
    suspend fun postLogs(@Body body: LogBatchRequest): LogBatchResponse

    // ── Read path ───────────────────────────────────────────────────
    //
    // GET endpoints are mounted at the root — the /api prefix is reserved
    // for the phone-shaped write aliases (api_phone.py) where path-mismatch
    // would have to be papered over with vehicle_slug → vehicle_id resolution.
    // GETs are simple enough that the raw backend shape works as-is.
    @GET("vehicles")
    suspend fun getVehicles(): List<VehicleDto>

    /**
     * Returns a bare list (not wrapped). Backend returns
     * `[{...}, {...}]` ordered by fillup_date DESC.
     */
    @GET("fillups")
    suspend fun getFillups(
        @Query("vehicle_id") vehicleId: String,
        @Query("limit") limit: Int = 30,
    ): List<FillupDto>

    @GET("analytics/mpg")
    suspend fun getMpgTrend(
        @Query("vehicle_id") vehicleId: String,
        @Query("window") window: String = "year",
    ): MpgTrendResponse
}
