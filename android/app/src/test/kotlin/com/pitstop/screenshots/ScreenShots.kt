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

    private fun shot(name: String, units: String = "imperial", content: @Composable () -> Unit) =
        paparazzi.snapshot(name) {
            PitstopTheme {
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
            state = BridgeSheetState(status = Fixtures.staleStatus, needsPairing = true, configured = true),
            onStart = {}, onStop = {}, onOpenSettings = {},
        )
    }

    // ── Live ────────────────────────────────────────────────────────
    @Test fun live() = shot("live") {
        LiveContent(Fixtures.liveMetrics, Fixtures.connected, brokerConnected = true, unitSystem = "imperial", obdAgeS = 2, driveMode = false)
    }

    @Test fun liveStale() = shot("live_stale") {
        LiveContent(Fixtures.liveMetrics, Fixtures.staleStatus, brokerConnected = true, unitSystem = "imperial", obdAgeS = 95, driveMode = false)
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
        FuelHubContent(ui = Fixtures.historyUi, sort = FillupSortOrder.RecentFirst, filter = FillupFilter.All)
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

    // ── Details ─────────────────────────────────────────────────────
    @Test fun tripDetail() = shot("trip_detail") { tripDetail("imperial") }

    @Test fun tripDetailMetric() = shot("trip_detail_metric", units = "metric") { tripDetail("metric") }

    @Composable
    private fun tripDetail(units: String) = TripDetailContent(
        trip = Fixtures.tripDetail, route = emptyList(), baseline = Fixtures.baseline,
        unitSystem = units, storedSeries = StoredSeries(loaded = true, metrics = null),
        onPersistSeries = {}, onOpenDtc = { _, _ -> }, onOpenMap = {}, onEdit = {},
    )

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
            saveLabel = "All changes saved",
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
