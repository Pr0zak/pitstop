package com.pitstop.screenshots

import com.pitstop.car.CarTileCatalog
import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.DtcDto
import com.pitstop.http.DtcTimelineCode
import com.pitstop.http.DtcTimelineEvent
import com.pitstop.http.FillupDto
import com.pitstop.http.MonthlySpendPointDto
import com.pitstop.http.MpgPointDto
import com.pitstop.http.RoutePointDto
import com.pitstop.http.TripBaselineDto
import com.pitstop.http.TripDetailDto
import com.pitstop.http.TripDto
import com.pitstop.http.TripDtcDto
import com.pitstop.http.TripSampleDto
import com.pitstop.http.VehicleDto
import com.pitstop.ui.config.AutoStartStatus
import com.pitstop.ui.config.AutoStartVerdict
import com.pitstop.ui.config.ConfigFormState
import com.pitstop.ui.config.ConnTest
import com.pitstop.ui.config.SettingsRow
import com.pitstop.ui.config.SignalState
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.fuel.FuelFormState
import com.pitstop.ui.fuel.VehicleOption
import com.pitstop.ui.history.HistoryListState
import com.pitstop.ui.history.HistoryUiState
import com.pitstop.ui.history.RefreshInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
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
            MpgPointDto("2023", 18.7, fillupCount = 27), MpgPointDto("2024", 19.1, fillupCount = 31),
            MpgPointDto("2025", 19.6, fillupCount = 29), MpgPointDto("2026", 20.2, fillupCount = 21),
        ),
        costPerMile = months.mapIndexed { i, m ->
            CostPerMilePointDto(period = m, costPerMi = 0.15 + (i % 4) * 0.01, miles = 900.0, totalCost = 150.0)
        },
        monthlySpend = months.mapIndexed { i, m ->
            MonthlySpendPointDto(month = m, fuel = 120.0 + (i * 37 % 60), service = if (i == 5) 89.0 else 0.0, total = 0.0)
        },
        recentTrips = listOf(
            trip("t2", iso(0, 22, 42), 1860, 27.4, 113.0).copy(fuelUsedL = 2.6),
            trip("t3", iso(1, 12, 58), 1270, 19.6, 105.0),
            trip("t4", iso(3, 17, 5), 5400, 96.3, 121.0).copy(fuelUsedL = null),
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
        m("fuel_level", 62.0), m("stft_b1", 1.6), m("ltft_b1", -3.1), m("engine_fuel_rate", 1.4),
        m("control_module_voltage", 14.2), m("run_time_since_start", 1260.0),
        m("gps_speed", 20.0), m("gps_alt", 212.0), m("gps_lat", 40.00), m("gps_lon", -83.00),
    )

    /** Same values, but the BLE link dropped 95 s ago: the screen still looks live. */
    val staleStatus = connected.copy(
        phase = BridgePhase.Disconnected,
        lastObdFrameAtMs = NOW - 95_000,
        engineState = EngineState.Unknown,
    )

    // ── History ────────────────────────────────────────────────────
    /** ISO timestamp [daysAgo] days back at [hour]:[min] UTC. */
    private fun iso(daysAgo: Long, hour: Int, min: Int = 0): String =
        LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).atTime(hour, min).atOffset(ZoneOffset.UTC).toString()

    val trips = listOf(
        trip("t1", iso(0, 13, 14), 1320, 19.9, 109.0),
        // Three driveway shuffles between two real drives → one folded row.
        trip("h1", iso(0, 20, 5), 95, 0.31, 18.0).copy(fuelUsedL = 0.04),
        trip("h2", iso(0, 20, 12), 70, 0.42, 21.0).copy(fuelUsedL = null),
        trip("h3", iso(0, 20, 21), 60, 0.18, 14.0).copy(fuelUsedL = null),
        trip("t2", iso(0, 22, 42), 1860, 27.4, 113.0).copy(category = "Commute", source = "phone_batch", fuelUsedL = 2.6),
        trip("t3", iso(1, 12, 58), 1270, 19.6, 105.0).copy(source = "phone_batch"),
        trip("t4", iso(2, 17, 5), 5400, 96.3, 121.0).copy(isTowing = true, category = "Boat", fuelUsedL = 15.8),
        // GPS-only capture has no fuel figure: the row must read "— mpg", not 0.
        trip("t5", iso(3, 14, 20), 640, 4.1, 58.0).copy(gpsOnly = true, source = "phone_batch", fuelUsedL = null),
        trip("t6", iso(5, 11, 45), 2100, 31.8, 118.0).copy(dtcCount = 1, source = "manual_merge"),
        trip("t7", iso(9, 16, 30), 900, 11.2, 84.0),
        trip("t8", iso(12, 13, 10), 1500, 22.7, 104.0),
    )

    val fillups = listOf(
        fillup("f1", iso(1, 18, 20), 76_612.0, 14.21, 3.289, 21.4, "Columbus"),
        fillup("f2", iso(9, 12, 5), 76_304.0, 15.02, 3.349, 20.1, "Dublin"),
        fillup("f3", iso(18, 9, 40), 76_001.0, 8.10, 3.199, null, "Columbus").copy(isFull = false),
        fillup("f4", iso(27, 17, 55), 75_812.0, 16.33, 3.259, 19.3, "Worthington"),
        fillup("f5", iso(41, 8, 15), 75_498.0, 15.87, 3.419, 20.6, "Columbus"),
    )

    private fun fillup(
        id: String, date: String, odo: Double, gal: Double, ppg: Double, mpg: Double?, city: String,
    ) = FillupDto(
        id = id, vehicleId = "v1", fillupDate = date, odo = odo, fuelVolume = gal,
        priceTotal = gal * ppg, pricePerUnit = ppg, mpg = mpg, city = city,
        lat = 40.0, lon = -83.0, weatherTempC = 18.0, weatherCode = 1,
    )

    val dtcs = listOf(
        DtcDto(id = "d1", vehicleId = "v1", code = "P0420", seenAt = iso(2, 18, 3),
            description = "Catalyst system efficiency below threshold (bank 1)"),
        DtcDto(id = "d2", vehicleId = "v1", code = "P0171", seenAt = iso(40, 7, 51), clearedAt = iso(38, 9, 0),
            description = "System too lean (bank 1)"),
    )

    val historyUi = HistoryUiState(
        trips = HistoryListState(data = trips),
        fillups = HistoryListState(data = fillups),
        dtcs = HistoryListState(data = dtcs),
        costPerMile = home.costPerMile.orEmpty(),
        monthlySpend = home.monthlySpend.orEmpty(),
        lastRefresh = RefreshInfo(atMs = NOW - 90_000, newTrips = 2),
    )

    // ── Trip detail ────────────────────────────────────────────────
    val tripDetail: TripDetailDto = run {
        val samples = mutableListOf<TripSampleDto>()
        val n = 200
        val start = Instant.parse("2026-09-23T17:42:00Z")
        for (i in 0 until n) {
            val f = i / n.toDouble()
            val t = start.plusSeconds(i * 9L).toString()
            fun add(metric: String, v: Double) = samples.add(TripSampleDto(t, metric, v))
            add("vehicle_speed", 95.0 * sin(PI * f) + 6 * sin(i / 4.0))
            add("engine_rpm", 1700.0 + 900 * sin(PI * f))
            add("coolant_temp", 62.0 + 28 * f)
            add("engine_fuel_rate", 0.5 + 1.1 * sin(PI * f))
        }
        TripDetailDto(
            id = "t2", vehicleId = "v1", startedAt = "2026-09-23T17:42:00Z",
            endedAt = "2026-09-23T18:12:00Z", durationS = 1800, distanceKm = 27.4,
            maxRpm = 2900.0, maxSpeedKph = 113.0, avgSpeedKph = 54.8, avgCoolantC = 86.0,
            fuelUsedL = 2.6, idleS = 212, category = "Commute",
            notes = "Traffic on the ring road; AC on full.",
            odoStartKm = 123_310.0, odoEndKm = 123_337.4,
            fuelLevelStartPct = 64.0, fuelLevelEndPct = 60.0,
            weatherTempC = 21.0, weatherCode = 2, dtcCount = 1,
            samples = samples,
            dtcs = listOf(TripDtcDto("e1", "P0420", "2026-09-23T17:58:00Z", "Catalyst system efficiency below threshold (bank 1)")),
        )
    }

    val baseline = TripBaselineDto(
        bucketLabel = "10–30 mi", sampleSize = 42, sufficient = true,
        avgDistanceKm = 25.1, avgDurationS = 1620.0, avgSpeedKph = 49.9,
        avgMaxSpeedKph = 108.0, avgMpg = 21.2,
    )

    // ── DTC detail ─────────────────────────────────────────────────
    val dtcTimeline = DtcTimelineCode(
        code = "P0420",
        description = "Catalyst system efficiency below threshold (bank 1)",
        count = 6, firstSeen = iso(70, 9, 0), lastSeen = iso(2, 18, 3), active = true,
        events = listOf(
            DtcTimelineEvent("e1", iso(2, 18, 3), tripId = "t4", tripDistanceKm = 96.3),
            DtcTimelineEvent("e2", iso(5, 11, 58), tripId = "t6", tripDistanceKm = 31.8),
            DtcTimelineEvent("e3", iso(5, 12, 20), tripId = "t6", tripDistanceKm = 31.8),
            DtcTimelineEvent("e4", iso(21, 7, 40)),
            DtcTimelineEvent("e5", iso(44, 17, 2), tripId = "t9", tripDistanceKm = 12.0),
            DtcTimelineEvent("e6", iso(70, 9, 0)),
        ),
    )

    // ── Fuel form ──────────────────────────────────────────────────
    private val vehicle = VehicleOption(id = "v1", slug = "demo", name = "Demo SUV", active = true)
    val fuelForm = FuelFormState(
        vehicles = listOf(vehicle), selectedVehicleSlug = "demo",
        odometer = "76924", lastOdometer = 76_612.0,
        volume = "13.84", pricePerVolume = "3.279", totalPrice = "45.38",
        gps = com.pitstop.ui.fuel.GpsFix(lat = 40.0, lon = -83.0, accuracyMeters = 8f),
        nearestPriorStation = "Main St Fuel", stationSuggestions = listOf("Main St Fuel", "Corner Gas", "Hilltop Station"),
    )
    val fuelFormErrors = fuelForm.copy(odometer = "76100", volume = "", totalPrice = "", showAllErrors = true)

    // ── Settings ───────────────────────────────────────────────────
    val autoStart = AutoStartStatus(
        verdict = AutoStartVerdict.NeedsPairing, inCarNow = false,
        wifi = SignalState.Idle, projection = SignalState.Idle, motion = SignalState.Disabled,
        companionSupported = true,
    )
    val settingsForm = ConfigFormState(
        apiBaseUrl = "http://192.0.2.10:8080", vehicleSlug = "demo",
        aaTilesHome = CarTileCatalog.DEFAULT_HOME,
    )

    val wizardForm = ConfigFormState(apiBaseUrl = "http://192.0.2.10:8080", ingestToken = "ingest-demo-token", queryToken = "query-demo-token")
    val wizardVehicles = ConnTest.Ok(
        listOf(
            VehicleDto(id = "v1", slug = "demo", name = "Demo SUV", year = 2019, make = "Acme", model = "Tourer"),
            VehicleDto(id = "v2", slug = "truck", name = "Work truck", year = 2014, make = "Acme", model = "Hauler"),
        ),
    )
}
