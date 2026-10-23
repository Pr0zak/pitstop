package com.pitstop.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GET /analytics/tires — TPMS history (backend pitstop/tires.py
// assemble_tire_analytics is the contract). Pressures are kPa, tire temps °C,
// outside temp °F, exactly as stored; the phone converts for display.
//
// Every number is a Double (Python sends `3` and `3.0` interchangeably) and
// every optional field is nullable with a default: a day with no reading is
// nulls, never zeros. Types checked against a live response 2026-10-23.

@Serializable
data class TireAnalyticsDto(
    @SerialName("has_data") val hasData: Boolean = false,
    /** Every UTC day in the window, ascending, "2026-10-21". */
    @SerialName("days") val days: List<String> = emptyList(),
    @SerialName("ambient") val ambient: List<TireAmbientDayDto> = emptyList(),
    @SerialName("corners") val corners: List<TireCornerHistoryDto> = emptyList(),
    /** Corners ≥ [lowDeltaPsi] below the highest current cold pressure. */
    @SerialName("low_corners") val lowCorners: List<String> = emptyList(),
    @SerialName("low_delta_psi") val lowDeltaPsi: Double? = null,
    @SerialName("slow_leak_psi_per_week") val slowLeakPsiPerWeek: Double? = null,
    @SerialName("slow_leak_min_samples") val slowLeakMinSamples: Double? = null,
    @SerialName("cold_park_hours") val coldParkHours: Double? = null,
    @SerialName("placard_front_kpa") val placardFrontKpa: Double? = null,
    @SerialName("placard_rear_kpa") val placardRearKpa: Double? = null,
    @SerialName("under_inflated_fraction") val underInflatedFraction: Double? = null,
    @SerialName("below_spec_psi") val belowSpecPsi: Double? = null,
    @SerialName("window_days") val windowDays: Double? = null,
)

@Serializable
data class TireAmbientDayDto(
    @SerialName("day") val day: String,
    @SerialName("ambient_mean_f") val ambientMeanF: Double? = null,
)

@Serializable
data class TireCornerHistoryDto(
    /** "fl" | "fr" | "rl" | "rr". */
    @SerialName("corner") val corner: String,
    @SerialName("daily") val daily: List<TireDayDto> = emptyList(),
    @SerialName("cold_readings") val coldReadings: List<TireColdReadingDto> = emptyList(),
    @SerialName("current_cold_kpa") val currentColdKpa: Double? = null,
    @SerialName("current_cold_at") val currentColdAt: String? = null,
    /** After air was added with no cold reading since: an estimate. */
    @SerialName("current_cold_estimated") val currentColdEstimated: Boolean = false,
    @SerialName("aired_up_at") val airedUpAt: String? = null,
    @SerialName("slope_psi_per_week") val slopePsiPerWeek: Double? = null,
    @SerialName("slope_samples") val slopeSamples: Double? = null,
    @SerialName("possible_slow_leak") val possibleSlowLeak: Boolean = false,
    @SerialName("warmup_median_psi") val warmupMedianPsi: Double? = null,
    @SerialName("placard_kpa") val placardKpa: Double? = null,
    @SerialName("below_placard_psi") val belowPlacardPsi: Double? = null,
    @SerialName("under_inflated") val underInflated: Boolean = false,
    @SerialName("below_spec") val belowSpec: Boolean = false,
)

@Serializable
data class TireDayDto(
    @SerialName("day") val day: String,
    @SerialName("pressure_min_kpa") val pressureMinKpa: Double? = null,
    @SerialName("pressure_mean_kpa") val pressureMeanKpa: Double? = null,
    @SerialName("pressure_max_kpa") val pressureMaxKpa: Double? = null,
    @SerialName("temp_mean_c") val tempMeanC: Double? = null,
    @SerialName("temp_max_c") val tempMaxC: Double? = null,
    @SerialName("cold_pressure_kpa") val coldPressureKpa: Double? = null,
    @SerialName("warmup_psi") val warmupPsi: Double? = null,
)

@Serializable
data class TireColdReadingDto(
    @SerialName("time") val time: String,
    @SerialName("pressure_kpa") val pressureKpa: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
)
