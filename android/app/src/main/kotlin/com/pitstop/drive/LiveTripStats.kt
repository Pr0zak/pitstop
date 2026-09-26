package com.pitstop.drive

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Running totals for the drive in progress, for the head unit's Trip tab.
 *
 * The server derives the authoritative trip after the fact
 * (backend `workers/trip_stats.py`); this is a live ESTIMATE of the same
 * numbers from the samples the phone is already recording, so the car can
 * show them mid-drive. The rules deliberately mirror the server's so the
 * figure on the dash and the figure in the trip list agree closely:
 *
 *  - distance: the LARGER of the GPS haversine sum and the vehicle_speed
 *    integral; segments with a gap of 60 s or more are skipped, and GPS
 *    steps implying > 250 km/h are dropped as fix jumps;
 *  - fuel: the first of engine_fuel_rate (fuel g/s) / maf_air_flow /
 *    maf_sensor_a (air g/s, ÷ 14.7) that clears 0.05 L and covered at least
 *    half of the OBD-active seconds, scaled up for the seconds it missed;
 *  - idle: seconds where vehicle_speed is under 1 km/h, the server's
 *    `value_num < 1` on the same kph column.
 *
 * Fed from [DriveBuffer.addPid] / [DriveBuffer.addGps], which are called
 * from the BLE and GPS threads, hence the lock. Every call is O(1).
 */
class LiveTripStats(private val startedAtMs: Long) {

    data class Snapshot(
        val durationS: Long,
        val distanceKm: Double,
        /** Null until a fuel source is credible — see the class doc. */
        val fuelL: Double?,
        val idleS: Long,
    )

    private class Integral {
        var lastT = -1L
        var lastV = 0.0
        var sum = 0.0
        var coveredS = 0.0

        /** Left-Riemann step, same as the server's `value * dt` over LEAD(). */
        fun add(t: Long, v: Double) {
            if (lastT >= 0) {
                val dt = (t - lastT) / 1000.0
                if (dt > 0 && dt < MAX_GAP_S) {
                    sum += lastV * dt
                    coveredS += dt
                }
            }
            lastT = t
            lastV = v
        }
    }

    private val lock = Any()
    private val speedKm = Integral() // kph · s → km after /3600
    private val fuelSources = FUEL_SOURCES.associateWith { Integral() }
    private var lastObdT = -1L
    private var obdActiveS = 0.0
    private var idleS = 0.0
    private var lastSpeedT = -1L
    private var lastSpeedKph = Double.NaN
    private var gpsKm = 0.0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastGpsT = -1L

    fun onPid(t: Long, metric: String, value: Double) {
        if (value.isNaN() || value.isInfinite()) return
        synchronized(lock) {
            if (lastObdT >= 0) {
                val dt = (t - lastObdT) / 1000.0
                if (dt > 0 && dt < MAX_GAP_S) obdActiveS += dt
            }
            lastObdT = max(lastObdT, t)
            when (metric) {
                "vehicle_speed" -> {
                    if (lastSpeedT >= 0 && lastSpeedKph < IDLE_KPH) {
                        val dt = (t - lastSpeedT) / 1000.0
                        if (dt > 0 && dt < MAX_GAP_S) idleS += dt
                    }
                    lastSpeedT = t
                    lastSpeedKph = value
                    speedKm.add(t, value)
                }
                in FUEL_SOURCES -> fuelSources.getValue(metric).add(t, value)
            }
        }
    }

    fun onGps(t: Long, lat: Double, lon: Double) {
        synchronized(lock) {
            if (lastGpsT >= 0) {
                val dt = (t - lastGpsT) / 1000.0
                val km = haversineKm(lastLat, lastLon, lat, lon)
                if (dt > 0 && dt < MAX_GAP_S && km / dt * 3600.0 < MAX_KPH) gpsKm += km
            }
            lastGpsT = t
            lastLat = lat
            lastLon = lon
        }
    }

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        Snapshot(
            durationS = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(0L),
            distanceKm = max(gpsKm, speedKm.sum / 3600.0),
            fuelL = resolveFuelL(),
            idleS = idleS.toLong(),
        )
    }

    private fun resolveFuelL(): Double? {
        for (metric in FUEL_SOURCES) {
            val src = fuelSources.getValue(metric)
            val fuelGrams = if (metric == "engine_fuel_rate") src.sum else src.sum / STOICH_AIR_PER_FUEL
            var liters = fuelGrams / GASOLINE_G_PER_L
            if (liters < MIN_CREDIBLE_FUEL_L) continue
            if (obdActiveS > 0 && src.coveredS < MIN_COVERAGE_FRACTION * obdActiveS) continue
            if (obdActiveS > 0 && src.coveredS > 0) liters *= max(1.0, obdActiveS / src.coveredS)
            return liters
        }
        return null
    }

    companion object {
        private const val MAX_GAP_S = 60.0
        private const val MAX_KPH = 250.0
        private const val IDLE_KPH = 1.0
        private const val GASOLINE_G_PER_L = 749.9
        private const val STOICH_AIR_PER_FUEL = 14.7
        private const val MIN_CREDIBLE_FUEL_L = 0.05
        private const val MIN_COVERAGE_FRACTION = 0.5

        /** Server order (trip_stats.FUEL_SOURCES): the ECU's own figure first. */
        private val FUEL_SOURCES = listOf("engine_fuel_rate", "maf_air_flow", "maf_sensor_a")

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }
    }
}
