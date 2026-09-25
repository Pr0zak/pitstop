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
import com.pitstop.ui.history.HistoryListContent
import com.pitstop.ui.history.HistorySubTab
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
        onRefresh = {}, onStart = {}, onStop = {}, onSync = {}, onCancelSync = {},
        onOpenHistory = {}, onOpenSettings = {},
    )

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

    // ── History ─────────────────────────────────────────────────────
    @Test fun historyTrips() = shot("history_trips") { history(HistorySubTab.Trips) }

    @Test fun historyTripsSelecting() = shot("history_trips_selecting") {
        history(HistorySubTab.Trips, TripSelection(mode = true, ids = setOf("t2", "t3", "t5")))
    }

    @Test fun historyFillups() = shot("history_fillups") { history(HistorySubTab.Fillups) }

    @Test fun historyDtcs() = shot("history_dtcs") { history(HistorySubTab.Dtcs) }

    @Composable
    private fun history(tab: HistorySubTab, selection: TripSelection = TripSelection()) = HistoryListContent(
        subTab = tab, onSubTab = {}, ui = Fixtures.historyUi, pendingCount = 2,
        uploadProgress = UploadProgress.Idle, onSync = {}, onCancelSync = {}, onRefresh = {},
        selection = selection, mergeState = MergeState.Idle, hiddenTripIds = emptySet(),
        tripSort = TripSortOrder.RecentFirst, tripFilter = TripSourceFilter.All, towingOnly = false,
        onTripSort = {}, onTripFilter = {}, onTowingOnly = {}, onToggleSelect = {}, onLongPress = {},
        onCancelSelection = {}, onMerge = {}, onDelete = {},
        fillupSort = FillupSortOrder.RecentFirst, fillupFilter = FillupFilter.All,
        onFillupSort = {}, onFillupFilter = {}, onOpenTrip = {}, onOpenFillup = {}, onOpenDtc = { _, _ -> },
    )

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
