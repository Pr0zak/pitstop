package com.pitstop.util

/**
 * Unit-aware formatters for telemetry values. The bridge service stores
 * canonical metric units on the wire (°C, km/h, kPa, etc.) — these
 * helpers convert at render time based on the user's "imperial" /
 * "metric" preference in SettingsRepository.
 *
 * Each formatter takes the canonical-unit value and returns "value unit"
 * pre-formatted; null returns "—". Pass system="imperial" or "metric".
 */
object UnitFormat {

    fun temp(c: Double?, system: String, digits: Int = 0): String {
        if (c == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f °F".format(c * 9.0 / 5.0 + 32.0)
        } else {
            "%.${digits}f °C".format(c)
        }
    }

    fun speed(kph: Double?, system: String, digits: Int = 0): String {
        if (kph == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f mph".format(kph * 0.621371)
        } else {
            "%.${digits}f km/h".format(kph)
        }
    }

    /** OBD MAF returns g/s; convert to lb/min for imperial (× 0.132277). */
    fun mafGramsPerSec(gps: Double?, system: String, digits: Int = 1): String {
        if (gps == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f lb/min".format(gps * 0.132277)
        } else {
            "%.${digits}f g/s".format(gps)
        }
    }

    /** MAP kPa → psi for imperial (× 0.145038). */
    fun pressureKpa(kpa: Double?, system: String, digits: Int = 0): String {
        if (kpa == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f psi".format(kpa * 0.145038)
        } else {
            "%.${digits}f kPa".format(kpa)
        }
    }

    /** Distance km → mi. Used for the GPS altitude tile (m → ft). */
    fun altitudeM(m: Double?, system: String, digits: Int = 0): String {
        if (m == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f ft".format(m * 3.28084)
        } else {
            "%.${digits}f m".format(m)
        }
    }

    /** GPS speed m/s → mph for imperial, km/h for metric. */
    fun gpsSpeedMs(ms: Double?, system: String, digits: Int = 0): String {
        if (ms == null) return "—"
        return if (system == "imperial") {
            "%.${digits}f mph".format(ms * 2.23694)
        } else {
            "%.${digits}f km/h".format(ms * 3.6)
        }
    }
}
