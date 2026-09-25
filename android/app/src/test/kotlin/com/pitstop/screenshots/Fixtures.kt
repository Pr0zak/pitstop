package com.pitstop.screenshots

import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.DtcDto
import com.pitstop.http.MonthlySpendPointDto
import com.pitstop.http.MpgPointDto
import com.pitstop.http.TripDto
import com.pitstop.service.BridgePhase
import com.pitstop.service.BridgeStatus
import com.pitstop.service.EngineState
import com.pitstop.service.MetricSample
import com.pitstop.ui.components.HeroCardData
import com.pitstop.ui.status.StatusUiState

/** Synthetic, made-up data for screenshots. Nothing here is real driving data. */
object Fixtures {
    val NOW = System.currentTimeMillis()

    private val months = listOf(
        "2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03",
        "2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09",
    )
    private val mpg = listOf(19.8, 18.9, 18.1, 17.6, 18.0, 19.2, 20.1, 20.8, 21.3, 20.9, 20.4, 21.0)

    val connected = BridgeStatus(
        phase = BridgePhase.Connected,
        deviceName = "WiCAN-demo",
        brokerConnected = true,
        publishedLastMinute = 118,
        lastFrameAtMs = NOW - 2_000,
        lastObdFrameAtMs = NOW - 2_000,
        metricsActive = 14,
        engineState = EngineState.On,
        inCar = true,
    )

    val home = StatusUiState(
        status = connected,
        hero = HeroCardData(
            avgConsumptionMpg = 20.4,
            latestPpg = 3.29,
            ppgDeltaPct = -2.1,
            monthCost = 142.37,
            monthCount = 3,
            fuelLevelPct = 62.0,
            fuelGallons = 12.2,
            fuelLevelAge = "4 min ago",
            tank1CapacityGal = 19.5,
            mpgSeries = mpg,
            fuelLevelIsEstimate = true,
        ),
        mpgMonthly = months.zip(mpg) { m, v -> MpgPointDto(period = m, mpg = v, miles = 900.0) },
        mpgYearly = listOf(
            MpgPointDto("2023", 18.7), MpgPointDto("2024", 19.1),
            MpgPointDto("2025", 19.6), MpgPointDto("2026", 20.2),
        ),
        costPerMile = months.mapIndexed { i, m ->
            CostPerMilePointDto(period = m, costPerMi = 0.15 + (i % 4) * 0.01, miles = 900.0, totalCost = 150.0)
        },
        monthlySpend = months.mapIndexed { i, m ->
            MonthlySpendPointDto(month = m, fuel = 120.0 + (i * 37 % 60), service = if (i == 5) 89.0 else 0.0, total = 0.0)
        },
        recentTrips = listOf(
            trip("t1", "2026-09-24T08:14:00Z", 1320, 19.9, 109.0),
            trip("t2", "2026-09-23T17:42:00Z", 1860, 27.4, 113.0),
            trip("t3", "2026-09-23T07:58:00Z", 1270, 19.6, 105.0),
        ),
        activeDtcs = listOf(
            DtcDto(id = "d1", vehicleId = "v1", code = "P0420", seenAt = "2026-09-22T18:03:00Z",
                description = "Catalyst system efficiency below threshold (bank 1)"),
        ),
        hasServer = true,
        hasVehicle = true,
    )

    private fun trip(id: String, start: String, durS: Int, km: Double, maxKph: Double) = TripDto(
        id = id, vehicleId = "v1", startedAt = start, durationS = durS,
        distanceKm = km, maxSpeedKph = maxKph, maxRpm = 3100.0, fuelUsedL = km / 8.5,
    )

    private fun m(name: String, v: Double, ageMs: Long = 2_000) = name to MetricSample(name, v, NOW - ageMs)

    /** Metrics a single-bank V6 actually reports; bank-2, EGR and evap never arrive. */
    val liveMetrics = mapOf(
        m("engine_rpm", 1850.0), m("vehicle_speed", 72.0),
        m("coolant_temp", 91.0), m("intake_air_temp", 24.0), m("engine_load", 38.0),
        m("throttle_position", 17.0), m("maf_air_flow", 14.2), m("manifold_pressure", 52.0),
        m("fuel_level", 62.0), m("stft_b1", 1.6), m("ltft_b1", -3.1),
        m("control_module_voltage", 14.2), m("run_time_since_start", 1260.0),
        m("gps_speed", 20.0), m("gps_alt", 212.0), m("gps_lat", 40.00), m("gps_lon", -83.00),
    )

    /** Same values, but the BLE link dropped 95 s ago: the screen still looks live. */
    val staleStatus = connected.copy(
        phase = BridgePhase.Disconnected,
        lastObdFrameAtMs = NOW - 95_000,
        engineState = EngineState.Unknown,
    )
}
