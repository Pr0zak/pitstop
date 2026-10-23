package com.pitstop.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pitstop.drive.UploadProgress
import com.pitstop.service.BridgePhase
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.pitstop.ui.components.CostPerMileCard
import com.pitstop.ui.components.MonthlySpendCard
import com.pitstop.ui.components.MpgLifetimeCard
import com.pitstop.ui.components.MpgYearChart
import com.pitstop.ui.components.TrendPage
import com.pitstop.ui.components.TrendsCarousel
import com.pitstop.ui.config.ConfigRootContent
import com.pitstop.ui.config.ConnTest
import com.pitstop.ui.config.settingsRows
import com.pitstop.ui.fuel.FuelAddContent
import com.pitstop.ui.history.FillupFilter
import com.pitstop.ui.history.FillupSortOrder
import com.pitstop.ui.history.TripsListContent
import com.pitstop.ui.history.HistorySubTab
import com.pitstop.ui.history.CarSection
import com.pitstop.ui.components.AppBarHost
import com.pitstop.ui.components.LocalAppBarHost
import com.pitstop.ui.fuel.FuelHubContent
import com.pitstop.ui.fuel.NearbyPricesCard
import com.pitstop.ui.config.PlacesKeySection
import com.pitstop.ui.config.PlacesKeyStatus
import com.pitstop.ui.config.PlacesKeyUi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import com.pitstop.ui.fuel.LogFillupSheetContent
import com.pitstop.ui.vehicle.CarContent
import com.pitstop.ui.vehicle.CodesList
import com.pitstop.ui.vehicle.ServiceContent
import com.pitstop.ui.status.BridgeSheetContent
import com.pitstop.ui.BridgeSheetState
import com.pitstop.ui.history.MergeState
import com.pitstop.ui.history.TripSelection
import com.pitstop.ui.history.TripSortOrder
import com.pitstop.ui.history.TripSourceFilter
import com.pitstop.ui.history.detail.DtcDetailContent
import com.pitstop.ui.history.detail.FillupDetailContent
import com.pitstop.ui.history.detail.StoredSeries
import com.pitstop.ui.history.detail.TripDetailContent
import com.pitstop.ui.live.LiveContent
import com.pitstop.ui.onboarding.SetupWizardContent
import com.pitstop.ui.onboarding.WizardStep
import com.pitstop.ui.status.StatusContent
import com.pitstop.ui.status.StatusUiState
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.PitstopTheme
import org.junit.Rule
import org.junit.Test

/** Renders screens from [Fixtures] at Pixel 6 size, tall enough to show a whole scroll. */
class ScreenShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 4200, softButtons = false),
        useDeviceResolution = true,
    )

    private fun shot(name: String, units: String = "imperial", dark: Boolean = true, content: @Composable () -> Unit) =
        paparazzi.snapshot(name) {
            PitstopTheme(useDarkTheme = dark) {
                // Inspection mode: lazy lists skip their prefetch scheduler,
                // which layoutlib can't run (see rememberPitstopListState).
                CompositionLocalProvider(
                    LocalUnitSystem provides units,
                    LocalInspectionMode provides true,
                    // The tab shell's top bar: vehicle switcher, logging chip, gear.
                    LocalAppBarHost provides AppBarHost(Fixtures.appBar),
                ) {
                    // Same root Surface MainActivity provides, so bodies that
                    // are not inside their own Scaffold get the app's colours.
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }

    // ── Home ────────────────────────────────────────────────────────
    @Test fun home() = shot("home") { home(Fixtures.home) }

    @Test fun homeMetric() = shot("home_metric", units = "metric") { home(Fixtures.home) }

    /** Home's Tires card: FR highlighted low (verified 2026-10-21 values). */
    @Test fun homeTires() = shot("home_tires") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
            com.pitstop.ui.components.TiresCard(
                tires = Fixtures.homeTires.tires,
                newestMs = Fixtures.homeTires.newestMs,
                nowMs = Fixtures.NOW,
                system = "imperial",
                placardFrontKpa = Fixtures.homeTires.placardFrontKpa,
                placardRearKpa = Fixtures.homeTires.placardRearKpa,
            )
        }
    }

    /** The tire home-screen widget bitmaps (2×2, 2×1, stale + placard, empty). */
    @Test fun tireWidget() = shot("tire_widget") {
        val now = Fixtures.NOW
        fun snap(ageMs: Long, placard: Boolean = false) = buildMap {
            put("tire_pressure_fl", com.pitstop.data.CachedReading(213.0, now - ageMs))
            put("tire_pressure_fr", com.pitstop.data.CachedReading(196.0, now - ageMs))
            put("tire_pressure_rl", com.pitstop.data.CachedReading(209.0, now - ageMs))
            put("tire_pressure_rr", com.pitstop.data.CachedReading(205.0, now - ageMs))
            put("tire_temp_fl", com.pitstop.data.CachedReading(24.0, now - ageMs))
            put("tire_temp_fr", com.pitstop.data.CachedReading(23.0, now - ageMs))
            if (placard) {
                put("_tire_placard_front_kpa", com.pitstop.data.CachedReading(241.3, now))
                put("_tire_placard_rear_kpa", com.pitstop.data.CachedReading(241.3, now))
            }
        }
        val fresh = com.pitstop.widget.TireWidgetProvider.modelOf(snap(5 * 60_000L, placard = true), now, "imperial")
        val stale = com.pitstop.widget.TireWidgetProvider.modelOf(snap(30 * 3_600_000L, placard = true), now, "imperial")
        val empty = com.pitstop.widget.TireWidgetProvider.modelOf(emptyMap(), now, "imperial")
        // Each size on the widget's own background, at roughly launcher scale.
        @androidx.compose.runtime.Composable
        fun cell(m: com.pitstop.domain.TireWidgetModel, size: com.pitstop.widget.TireWidgetRender.Size, wDp: Int, hDp: Int) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(wDp.dp, hDp.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFF2A2F37), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .padding(6.dp),
            ) {
                androidx.compose.foundation.Image(
                    bitmap = com.pitstop.widget.TireWidgetRender.render(m, size).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cell(fresh, com.pitstop.widget.TireWidgetRender.Size.Tiny, 80, 80)
                cell(stale, com.pitstop.widget.TireWidgetRender.Size.Tiny, 80, 80)
                cell(empty, com.pitstop.widget.TireWidgetRender.Size.Tiny, 80, 80)
            }
            cell(fresh, com.pitstop.widget.TireWidgetRender.Size.Wide, 176, 80)
            cell(stale, com.pitstop.widget.TireWidgetRender.Size.Wide, 176, 80)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cell(fresh, com.pitstop.widget.TireWidgetRender.Size.Large, 176, 176)
                cell(stale, com.pitstop.widget.TireWidgetRender.Size.Large, 176, 176)
            }
        }
    }

    /** "Fuel & tires" widget (design B): 1×1, 2×1 and 2×2 — fresh, stale, never read. */
    @Test fun comboWidget() = shot("combo_b_final") {
        val now = Fixtures.NOW
        fun tires(ageMs: Long) = mapOf(
            "tire_pressure_fl" to com.pitstop.data.CachedReading(213.0, now - ageMs),
            "tire_pressure_fr" to com.pitstop.data.CachedReading(196.0, now - ageMs),
            "tire_pressure_rl" to com.pitstop.data.CachedReading(209.0, now - ageMs),
            "tire_pressure_rr" to com.pitstop.data.CachedReading(205.0, now - ageMs),
            "_tire_placard_front_kpa" to com.pitstop.data.CachedReading(241.3, now),
            "_tire_placard_rear_kpa" to com.pitstop.data.CachedReading(241.3, now),
        )
        fun fuel(ageMs: Long) = com.pitstop.data.CachedFuel(44.7 / 73.8 * 100, 240.0, now - ageMs, "Cluster")
        val fresh = com.pitstop.widget.ComboWidgetProvider.modelOf(fuel(5 * 60_000L), tires(4 * 60_000L), now, "imperial")
        // Fuel fresh, tires a day old: the OLDER age is what shows.
        val stale = com.pitstop.widget.ComboWidgetProvider.modelOf(fuel(5 * 60_000L), tires(26 * 3_600_000L), now, "imperial")
        val never = com.pitstop.widget.ComboWidgetProvider.modelOf(null, emptyMap(), now, "imperial")
        @androidx.compose.runtime.Composable
        fun tile(m: com.pitstop.widget.ComboWidgetRender.Model, size: com.pitstop.widget.ComboWidgetRender.Size) {
            val (wDp, hDp) = when (size) {
                com.pitstop.widget.ComboWidgetRender.Size.Tiny -> 90 to 90
                com.pitstop.widget.ComboWidgetRender.Size.Wide -> 190 to 88
                com.pitstop.widget.ComboWidgetRender.Size.Large -> 180 to 180
            }
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(wDp.dp, hDp.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFF2A2F37), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .padding(6.dp),
            ) {
                androidx.compose.foundation.Image(
                    bitmap = com.pitstop.widget.ComboWidgetRender.render(m, size).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            androidx.compose.material3.Text("Wide (2×1) never read", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            tile(never, com.pitstop.widget.ComboWidgetRender.Size.Wide)
            for (size in com.pitstop.widget.ComboWidgetRender.Size.entries) {
                androidx.compose.material3.Text(
                    "${size.name} (${when (size) {
                        com.pitstop.widget.ComboWidgetRender.Size.Tiny -> "1×1"
                        com.pitstop.widget.ComboWidgetRender.Size.Wide -> "2×1"
                        com.pitstop.widget.ComboWidgetRender.Size.Large -> "2×2"
                    }})   fresh · stale (tires 1d) · never read",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tile(fresh, size)
                    tile(stale, size)
                    if (size == com.pitstop.widget.ComboWidgetRender.Size.Tiny) tile(never, size)
                }
            }
        }
    }

    /** Metric units: temps in °C, pressures still whole psi (dash parity). */
    @Test fun homeTiresMetric() = shot("home_tires_metric", units = "metric") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) {
            com.pitstop.ui.components.TiresCard(
                tires = Fixtures.homeTires.tires,
                newestMs = Fixtures.homeTires.newestMs,
                nowMs = Fixtures.NOW,
                system = "metric",
            )
        }
    }

    /** Nothing needs attention → no attention strip at all. */
    @Test fun homeQuiet() = shot("home_quiet") { home(Fixtures.homeQuiet) }

    @Test fun homeSetup() = shot("home_setup") {
        home(StatusUiState(hasServer = true, hasVehicle = false))
    }

    /** Every Trends-carousel page, stacked: the pager itself can't run
     *  under layoutlib, so the Home shot only shows page one. */
    @Test fun homeTrendPages() = shot("home_trend_pages") {
        val h = Fixtures.home
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            modifier = androidx.compose.ui.Modifier.padding(16.dp),
        ) {
            TrendsCarousel(listOf(TrendPage("MPG, last 12 months") { MpgYearChart(h.mpgMonthly!!, framed = false) }))
            TrendsCarousel(listOf(TrendPage("Lifetime MPG") { MpgLifetimeCard(h.mpgYearly!!, framed = false) }))
            TrendsCarousel(listOf(TrendPage("Cost per mile") { CostPerMileCard(h.costPerMile!!, framed = false) }))
            TrendsCarousel(listOf(TrendPage("Monthly fuel spend") { MonthlySpendCard(h.monthlySpend!!, framed = false) }))
            TrendsCarousel(
                listOf(
                    TrendPage("Fuel spend, 2026 vs 2025") {
                        com.pitstop.ui.components.SpendYoyChart(ChartFixtures.spendYoy, framed = false)
                    },
                ),
            )
        }
    }

    // ── Fuel charts (MPG over time, paid vs market, spend by year) ──
    @Test fun mpgHistory() = shot("mpg_history") { mpgHistory(com.pitstop.domain.FuelCharts.MpgSpan.ThreeYears, 30) }

    @Test fun mpgHistoryAll() = shot("mpg_history_all") { mpgHistory(com.pitstop.domain.FuelCharts.MpgSpan.All, -1) }

    @Test fun mpgHistoryMetric() = shot("mpg_history_metric", units = "metric") {
        mpgHistory(com.pitstop.domain.FuelCharts.MpgSpan.Year, 5)
    }

    @Composable
    private fun mpgHistory(span: com.pitstop.domain.FuelCharts.MpgSpan, selected: Int) =
        com.pitstop.ui.status.MpgHistoryContent(
            points = ChartFixtures.mpgMonths, span = span, onSpan = {},
            selected = selected, onSelect = {}, onBack = {},
        )

    @Test fun fuelCharts() = shot("fuel_charts") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.pitstop.ui.fuel.MarketPriceCard(ChartFixtures.marketCompare)
            com.pitstop.ui.fuel.MarketPriceCard(ChartFixtures.marketCompare, initialSelected = 10)
            TrendsCarousel(
                listOf(
                    TrendPage("Fuel spend, 2026 vs 2025") {
                        com.pitstop.ui.components.SpendYoyChart(ChartFixtures.spendYoy, framed = false)
                    },
                ),
            )
            com.pitstop.ui.components.SpendYoyChart(ChartFixtures.spendYoy, initialSelected = 12)
        }
    }

    @Test fun homeFuelCards() = shot("home_fuel_cards") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.pitstop.ui.fuel.MarketPriceCard(ChartFixtures.marketCompare, compact = true, onClick = {})
            com.pitstop.ui.fuel.TankMpgCard(
                com.pitstop.domain.FuelCharts.tankSeries(ChartFixtures.tankFills, zone = java.time.ZoneOffset.UTC)!!,
            )
        }
    }

    @Test fun fuelChartsMetric() = shot("fuel_charts_metric", units = "metric") {
        Column(Modifier.padding(12.dp)) {
            com.pitstop.ui.fuel.MarketPriceCard(ChartFixtures.marketCompare, initialSelected = 3)
        }
    }

    @Composable
    private fun home(ui: StatusUiState) = StatusContent(
        ui = ui, uploadProgress = UploadProgress.Idle, pendingDrives = 0,
        onRefresh = {}, onSync = {}, onCancelSync = {},
        onOpenHistory = {}, onOpenSettings = {},
    )

    @Test fun bridgeSheet() = shot("bridge_sheet") {
        BridgeSheetContent(
            state = BridgeSheetState(
                status = Fixtures.staleStatus,
                wican = com.pitstop.domain.WicanSetup.State.AutoStartOff,
                configured = true,
            ),
            onStart = {}, onStop = {}, onOpenSettings = {},
        )
    }

    // ── Live ────────────────────────────────────────────────────────
    @Test fun live() = shot("live") {
        LiveContent(Fixtures.liveMetrics, Fixtures.connected, brokerConnected = true, unitSystem = "imperial", obdAgeS = 2, driveMode = false)
    }

    /** Firmware-6 WiCAN asking for its passkey: the Live pairing banner. */
    @Test fun livePairing() = shot("live_pairing") {
        LiveContent(
            Fixtures.liveMetrics,
            Fixtures.connected.copy(blePairing = com.pitstop.service.BlePairing.InProgress),
            brokerConnected = true, unitSystem = "imperial", obdAgeS = 2, driveMode = false,
        )
    }

    /** Stale bond after a reflash: the loud "Connect WiCAN" sheet card. */
    @Test fun bridgeSheetRejected() = shot("bridge_sheet_rejected") {
        BridgeSheetContent(
            state = BridgeSheetState(
                status = Fixtures.staleStatus,
                wican = com.pitstop.domain.WicanSetup.State.BluetoothRejected,
                configured = true,
            ),
            onStart = {}, onStop = {}, onOpenSettings = {},
        )
    }

    /** The unified WiCAN card in each state the user can meet. */
    @Test fun wicanSetupStates() = shot("wican_setup_states") {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            for (ui in Fixtures.wicanStates) {
                com.pitstop.ui.components.WicanSetupCard(ui = ui, onAction = {})
            }
        }
    }

    @Test fun liveStale() = shot("live_stale") {
        LiveContent(Fixtures.liveMetrics, Fixtures.staleStatus, brokerConnected = true, unitSystem = "imperial", obdAgeS = 95, driveMode = false)
    }

    /** Bridge off, nothing live: last server values dimmed, battery card. */
    @Test fun liveParked() = shot("live_parked") {
        val old = Fixtures.NOW - 16 * 3_600_000L
        LiveContent(
            emptyMap(), Fixtures.connected.copy(phase = BridgePhase.Idle), brokerConnected = false,
            unitSystem = "imperial", obdAgeS = null, driveMode = false,
            parkedMetrics = Fixtures.liveMetrics.filterKeys { it != "engine_rpm" && it != "vehicle_speed" }
                .mapValues { (_, m) -> m.copy(tsMs = old) },
            battery = listOf(12.15, 12.4, 11.98, 12.4, 12.2, 12.4, 12.1, 12.3, 12.0, 12.27).mapIndexed { i, v ->
                com.pitstop.ui.live.BatteryDay(java.time.LocalDate.of(2026, 9, 19).plusDays(i.toLong()), v)
            },
            nowMs = Fixtures.NOW,
        )
    }

    @Test fun liveEmpty() = shot("live_empty") {
        LiveContent(emptyMap(), Fixtures.connected.copy(phase = BridgePhase.Idle), brokerConnected = false, unitSystem = "imperial", obdAgeS = null, driveMode = false)
    }

    // ── Trips ───────────────────────────────────────────────────────
    @Test fun trips() = shot("trips") { tripsList() }

    @Test fun tripsSelecting() = shot("trips_selecting") {
        tripsList(TripSelection(mode = true, ids = setOf("t2", "t3", "t5")))
    }

    /** A row swiped open: its quick-tag chips showing under it. */
    @Test fun tripsTagging() = shot("trips_tagging") { tripsList(tagging = "t2") }

    @Composable
    private fun tripsList(selection: TripSelection = TripSelection(), tagging: String? = null) = TripsListContent(
        subTab = HistorySubTab.Trips, onSubTab = {}, ui = Fixtures.historyUi, pendingCount = 2,
        uploadProgress = UploadProgress.Idle, onSync = {}, onCancelSync = {}, onRefresh = {},
        selection = selection, mergeState = MergeState.Idle, hiddenTripIds = emptySet(),
        tripSort = TripSortOrder.RecentFirst, tripFilter = TripSourceFilter.All, towingOnly = false,
        onTripSort = {}, onTripFilter = {}, onTowingOnly = {}, onToggleSelect = {}, onLongPress = {},
        onCancelSelection = {}, onMerge = {}, onDelete = {}, onOpenTrip = {},
        taggingTripId = tagging,
    )

    // ── Fuel ────────────────────────────────────────────────────────
    @Test fun fuelHub() = shot("fuel_hub") {
        FuelHubContent(
            ui = Fixtures.historyUi,
            sort = FillupSortOrder.RecentFirst,
            filter = FillupFilter.All,
            nearbyCard = { NearbyPricesCard(Fixtures.nearbyClosed, nowMs = Fixtures.NOW) },
        )
    }

    @Test fun nearbyPrices() = shot("nearby_prices") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NearbyPricesCard(Fixtures.nearbyClosed, nowMs = Fixtures.NOW)
            NearbyPricesCard(Fixtures.nearbyExpanded.copy(open = true), nowMs = Fixtures.NOW)
            NearbyPricesCard(Fixtures.nearbyVehicle.copy(open = true), nowMs = Fixtures.NOW)
        }
    }

    @Test fun nearbyPricesStates() = shot("nearby_prices_states") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (s in listOf(
                Fixtures.nearbyNoKey, Fixtures.nearbyNoLocation, Fixtures.nearbyQuota,
                Fixtures.nearbyUpstream, Fixtures.nearbyEmpty, Fixtures.nearbyLoading, Fixtures.nearbyFailed,
            )) {
                NearbyPricesCard(s.copy(open = true), nowMs = Fixtures.NOW)
            }
        }
    }

    @Test fun nearbyPricesMetric() = shot("nearby_prices_metric", units = "metric") {
        Column(Modifier.padding(12.dp)) { NearbyPricesCard(Fixtures.nearbyOk, nowMs = Fixtures.NOW) }
    }

    @Test fun settingsPlacesKey() = shot("settings_places_key") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlacesKeySection(PlacesKeyUi(status = PlacesKeyStatus.Loaded(keySet = false, monthCalls = 0, monthlyCap = 900)))
            PlacesKeySection(
                PlacesKeyUi(status = PlacesKeyStatus.Loaded(keySet = true, monthCalls = 37, monthlyCap = 900), draft = "demo-key"),
            )
            PlacesKeySection(PlacesKeyUi(status = PlacesKeyStatus.Unsupported))
        }
    }

    @Test fun logFillupSheet() = shot("log_fillup_sheet") {
        LogFillupSheetContent(form = Fixtures.quickForm, more = false)
    }

    @Test fun logFillupSheetMore() = shot("log_fillup_sheet_more") {
        LogFillupSheetContent(form = Fixtures.quickForm, more = true)
    }

    // ── Car ─────────────────────────────────────────────────────────
    @Test fun carCodes() = shot("car_codes") {
        CarContent(section = CarSection.Codes, onSection = {}, codes = {
            CodesList(
                state = com.pitstop.ui.history.HistoryListState(data = Fixtures.carDtcs),
                lastRefresh = Fixtures.historyUi.lastRefresh, onRefresh = {}, onOpen = { _, _ -> },
            )
        })
    }

    @Test fun carService() = shot("car_service") {
        CarContent(section = CarSection.Service, onSection = {}, service = { ServiceContent(Fixtures.serviceWithReminders) })
    }

    @Test fun carServicePresets() = shot("car_service_presets") {
        CarContent(section = CarSection.Service, onSection = {}, service = { ServiceContent(Fixtures.serviceEmpty) })
    }

    // ── Insights ────────────────────────────────────────────────────
    private fun insights(ui: com.pitstop.ui.insights.InsightsUi, group: String? = null, expanded: Set<String> = emptySet()): @Composable () -> Unit = {
        com.pitstop.ui.insights.InsightsContent(
            ui = ui, group = group, onGroup = {}, expandedHow = expanded, onToggleHow = {},
            onWindow = {}, onRefresh = {}, onBack = {},
        )
    }

    @Test fun insights() = shot("insights", content = insights(InsightsFixtures.ui))

    @Test fun insightsLight() = shot("insights_light", dark = false, content = insights(InsightsFixtures.ui))

    @Test fun insightsEngine() = shot("insights_engine", content = insights(InsightsFixtures.ui, group = "engine", expanded = setOf("battery")))

    @Test fun insightsAllClear() = shot("insights_all_clear", content = insights(InsightsFixtures.uiAllClear))

    @Test fun insightsLoading() = shot("insights_loading", content = insights(InsightsFixtures.uiLoading))

    @Test fun insightsError() = shot("insights_error", content = insights(InsightsFixtures.uiError))

    /** Every card in a plain column (the lazy list only lays out what fits). */
    @Test fun insightCards() = shot("insight_cards") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in listOf(InsightsFixtures.bestSpeed, InsightsFixtures.fillDay, InsightsFixtures.tireLeak, InsightsFixtures.priceVsMarket, InsightsFixtures.warmup, InsightsFixtures.misfires)) {
                com.pitstop.ui.insights.InsightCard(card = c, howExpanded = c.id == "tire_leak", onToggleHow = {})
            }
        }
    }

    @Test fun insightCardsLight() = shot("insight_cards_light", dark = false) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in listOf(InsightsFixtures.shortTrips, InsightsFixtures.trimSplit, InsightsFixtures.battery, InsightsFixtures.fillDay)) {
                com.pitstop.ui.insights.InsightCard(card = c, howExpanded = false, onToggleHow = {})
            }
        }
    }

    @Test fun carInsightsEntry() = shot("car_insights_entry") {
        CarContent(
            section = CarSection.Service, onSection = {},
            service = { ServiceContent(Fixtures.serviceWithReminders) },
            insightsEntry = { com.pitstop.ui.insights.InsightsEntryCard(InsightsFixtures.ui, onOpen = {}) },
        )
    }

    @Test fun insightsEntryStates() = shot("insights_entry_states") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            com.pitstop.ui.insights.InsightsEntryCard(InsightsFixtures.ui, onOpen = {})
            com.pitstop.ui.insights.InsightsEntryCard(InsightsFixtures.uiAllClear, onOpen = {})
            com.pitstop.ui.insights.InsightsEntryCard(InsightsFixtures.uiLoading, onOpen = {})
            com.pitstop.ui.insights.InsightsEntryCard(InsightsFixtures.uiError, onOpen = {})
        }
    }

    // ── Details ─────────────────────────────────────────────────────
    @Test fun tripDetail() = shot("trip_detail") { tripDetail("imperial") }

    @Test fun tripDetailMetric() = shot("trip_detail_metric", units = "metric") { tripDetail("metric") }

    @Composable
    private fun tripDetail(units: String) = TripDetailContent(
        trip = Fixtures.tripDetail, route = emptyList(), baseline = Fixtures.baseline,
        unitSystem = units, storedSeries = StoredSeries(loaded = true, metrics = null),
        onPersistSeries = {}, onOpenDtc = { _, _ -> }, onOpenMap = {}, onEdit = {},
    )

    /** Trip-detail Tires card: shared-axis psi chart + start→end summary, FR LOW. */
    @Test fun tripTires() = shot("trip_tires") {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            com.pitstop.ui.history.detail.TripTiresCard(
                samples = Fixtures.tripDetail.samples,
                tires = Fixtures.tripTires,
                system = "imperial",
            )
        }
    }

    @Test fun fillupDetail() = shot("fillup_detail") {
        FillupDetailContent(fillup = Fixtures.fillups[0], context = Fixtures.fillups)
    }

    @Test fun dtcDetail() = shot("dtc_detail") {
        DtcDetailContent(entry = Fixtures.dtcTimeline, onOpenTrip = {})
    }

    /** A code that isn't in the bundled table: the honest family fallback. */
    @Test fun dtcDetailUnknown() = shot("dtc_detail_unknown") {
        DtcDetailContent(
            entry = Fixtures.dtcTimeline.copy(code = "P1456", description = "EVAP control system leak (fuel tank)"),
            onOpenTrip = {},
        )
    }

    // ── Fuel ────────────────────────────────────────────────────────
    @Test fun fuelAdd() = shot("fuel_add") { FuelAddContent(form = Fixtures.fuelForm) }

    @Test fun fuelAddErrors() = shot("fuel_add_errors") { FuelAddContent(form = Fixtures.fuelFormErrors) }

    @Test fun fuelAddMetric() = shot("fuel_add_metric", units = "metric") {
        FuelAddContent(form = Fixtures.fuelForm.copy(volume = "52.39", pricePerVolume = "0.866", odometer = "123795", lastOdometer = 123_293.0))
    }

    // ── Settings + setup ────────────────────────────────────────────
    @Test fun settingsRoot() = shot("settings_root") {
        ConfigRootContent(
            rows = settingsRows(
                form = Fixtures.settingsForm,
                connTest = ConnTest.Ok(emptyList()),
                autoStartStatus = Fixtures.autoStart,
                latestIsNewer = false,
                installedByPlay = true,
            ),
            autoStartStatus = Fixtures.autoStart,
            pairing = false,
        )
    }

    @Test fun wizardConnect() = shot("wizard_connect") {
        SetupWizardContent(
            step = WizardStep.Connect, form = Fixtures.wizardForm, connTest = ConnTest.Idle,
            permissionsAsked = false, companionSupported = true, companionPaired = false, pairing = false,
        )
    }

    @Test fun wizardVehicle() = shot("wizard_vehicle") {
        SetupWizardContent(
            step = WizardStep.Vehicle, form = Fixtures.wizardForm.copy(vehicleSlug = "demo"),
            connTest = Fixtures.wizardVehicles,
            permissionsAsked = false, companionSupported = true, companionPaired = false, pairing = false,
        )
    }

    @Test fun wizardPermissions() = shot("wizard_permissions") {
        SetupWizardContent(
            step = WizardStep.Permissions, form = Fixtures.wizardForm, connTest = Fixtures.wizardVehicles,
            permissionsAsked = false, companionSupported = true, companionPaired = false, pairing = false,
        )
    }
}

/** Live's landscape "drive mode" needs a landscape device. */
class LandscapeShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 2400,
            screenHeight = 1080,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        useDeviceResolution = true,
    )

    @Test fun liveDriveMode() = paparazzi.snapshot("live_drive_mode") {
        PitstopTheme {
            Surface(color = MaterialTheme.colorScheme.background) {
            LiveContent(Fixtures.liveMetrics, Fixtures.connected, brokerConnected = true, unitSystem = "imperial", obdAgeS = 1, driveMode = true)
            }
        }
    }
}
