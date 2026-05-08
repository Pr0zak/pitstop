package com.pitstop.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Backend contract assumption (FLAG to backend agent for confirmation):
 *   POST /api/fillups
 *   Body: { vehicle_slug, ts, gallons, total_price, odometer_mi, partial,
 *           lat?, lon?, station_name? }
 *   Response: { id: string }
 *
 * Auth: Bearer <INGEST_TOKEN> via [PitstopAuthInterceptor].
 *
 * If the backend wires `vehicle_id` (UUID) instead of `vehicle_slug`, we resolve the
 * slug client-side later — but slug is the natural key to mirror the MQTT topic
 * convention (`bridge/<vehicle_slug>/<metric>`).
 */
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

interface PitstopApi {
    @POST("api/fillups")
    suspend fun postFillup(@Body body: FillupRequest): FillupResponse
}
