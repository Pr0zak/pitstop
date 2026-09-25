package com.pitstop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ui.RootViewModel
import com.pitstop.ui.config.ConfigScreen
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.history.HistoryScreen
import com.pitstop.ui.history.HistorySubTab
import com.pitstop.ui.history.HistoryViewModel
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.onboarding.OnboardingGateViewModel
import com.pitstop.ui.onboarding.SetupWizardScreen
import com.pitstop.ui.status.StatusScreen
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.PitstopTheme
import com.pitstop.util.requireActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The five primary destinations, in bottom-bar order. The pager index IS
 * the ordinal — nothing else in the app hard-codes a page number, so
 * reordering the bar is an edit here and nowhere else.
 */
enum class Tab(
    val label: String,
    val iconActive: ImageVector,
    val iconInactive: ImageVector,
) {
    Home("Home", Icons.Filled.Home, Icons.Outlined.Home),
    Live("Live", Icons.Filled.Speed, Icons.Outlined.Speed),
    History("History", Icons.Filled.History, Icons.Outlined.History),
    Fuel("Fuel", Icons.Filled.LocalGasStation, Icons.Outlined.LocalGasStation),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Cleared by the launcher when a deep-link intent fires and the
     *  pager scrolls there; subsequent recomposes don't re-navigate. */
    private val pendingTab = MutableStateFlow<Tab?>(null)

    /** A pitstop://setup?… link tapped from a QR page / message. Consumed once
     *  by ConfigScreen (which imports it) then cleared. */
    private val pendingSetupLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Belt-and-suspenders fuel-widget refresh. Opening the app is
        // the user's strongest "fix this thing" signal; if the widget
        // got stuck on a failed onUpdate after an APK install (Hilt
        // not ready, no network, etc.) and is now waiting for the next
        // 30-min OS tick, we unstick it here.
        com.pitstop.widget.FuelWidgetProvider.refreshWidgets(this)
        // The Add-Fillup launcher shortcut (#121) — long-press the app
        // icon or use a home-screen shortcut — fires this action so we
        // land directly on the Fuel tab. Other launch paths (icon tap,
        // recents) get the default Home tab.
        //
        // ACTION_SYNC_DRIVES is fired by the SyncReminderManager
        // notification body tap — land on History → Trips so the user
        // can see the queued drives + tap "Sync".
        // A pitstop://setup?… VIEW intent lands on Settings and hands
        // the link to ConfigScreen to import.
        val setupLink = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data?.takeIf { it.scheme == "pitstop" }?.toString()
        if (setupLink != null) pendingSetupLink.value = setupLink
        val initialTab = tabForIntent(intent) ?: Tab.Home
        if (intent?.action == ACTION_SYNC_DRIVES) openHistory(HistorySubTab.Trips)
        setContent {
            PitstopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PitstopRoot(
                        initialTab = initialTab,
                        pendingTabFlow = pendingTab,
                        pendingSetupLinkFlow = pendingSetupLink,
                    )
                }
            }
        }
    }

    /** Single-task launchMode means a shortcut tap on an already-
     *  running app reuses this instance — we get the new intent
     *  here. Surface it to the composable so the pager scrolls. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            val link = intent.data?.takeIf { it.scheme == "pitstop" }?.toString() ?: return
            pendingSetupLink.value = link
        }
        if (intent.action == ACTION_SYNC_DRIVES) openHistory(HistorySubTab.Trips)
        tabForIntent(intent)?.let { pendingTab.value = it }
    }

    private fun tabForIntent(intent: Intent?): Tab? = when {
        intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "pitstop" -> Tab.Settings
        intent?.action == ACTION_ADD_FILLUP -> Tab.Fuel
        intent?.action == ACTION_SYNC_DRIVES -> Tab.History
        else -> null
    }

    /** Pre-select History's sub-tab before the pager lands there. */
    private fun openHistory(sub: HistorySubTab) {
        ViewModelProvider(this)[HistoryViewModel::class.java].selectSubTab(sub)
    }

    companion object {
        const val ACTION_ADD_FILLUP = "com.pitstop.action.ADD_FILLUP"
        const val ACTION_SYNC_DRIVES = "com.pitstop.action.SYNC_DRIVES"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PitstopRoot(
    initialTab: Tab = Tab.Home,
    pendingTabFlow: MutableStateFlow<Tab?>? = null,
    pendingSetupLinkFlow: MutableStateFlow<String?>? = null,
    gateViewModel: OnboardingGateViewModel = hiltViewModel(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val unitSystem by rootViewModel.unitSystem.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalUnitSystem provides unitSystem) {
        PitstopRootBody(initialTab, pendingTabFlow, pendingSetupLinkFlow, gateViewModel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PitstopRootBody(
    initialTab: Tab,
    pendingTabFlow: MutableStateFlow<Tab?>?,
    pendingSetupLinkFlow: MutableStateFlow<String?>?,
    gateViewModel: OnboardingGateViewModel,
) {
    // First-run gate (#12): while un-onboarded + unconfigured, the setup wizard
    // replaces the whole pager. `null` = still loading settings — render nothing
    // rather than flash the pager before swapping to the wizard.
    val showWizard by gateViewModel.showWizard.collectAsStateWithLifecycle()
    when (showWizard) {
        // Settings still loading — render nothing (a blank frame) and return, so
        // the pager isn't briefly drawn under a fresh install before the wizard
        // swaps in. Returning here (not just `Unit`) is what prevents that flash.
        null -> return
        true -> {
            SetupWizardScreen(
                onDone = { gateViewModel.markComplete() },
                pendingSetupLinkFlow = pendingSetupLinkFlow,
            )
            return
        }
        false -> Unit // fall through to the pager
    }

    val pagerState = rememberPagerState(initialPage = initialTab.ordinal) { Tab.entries.size }
    val scope = rememberCoroutineScope()
    val goTo: (Tab) -> Unit = { tab -> scope.launch { pagerState.scrollToPage(tab.ordinal) } }

    // Route an onNewIntent deep link (shortcut tap on already-running
    // app) to the right pager page. Clears the pending value so a
    // recompose doesn't repeat the scroll.
    val pendingTab = pendingTabFlow?.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTab?.value) {
        val target = pendingTab?.value
        if (target != null) {
            pagerState.scrollToPage(target.ordinal)
            pendingTabFlow?.value = null
        }
    }

    val context = LocalContext.current
    // History's ViewModel is Activity-scoped (see HistoryScreen); Home
    // reaches it the same way to hand over a sub-tab + detail route before
    // switching tabs. Resolved lazily in the callbacks, so an app that never
    // taps a DTC or a trip on Home never builds it early.
    val historyVm: () -> HistoryViewModel = {
        ViewModelProvider(context.requireActivity())[HistoryViewModel::class.java]
    }

    // Up-front permission request (notifications + bluetooth + foreground
    // location). ACCESS_BACKGROUND_LOCATION is deliberately NOT in this batch —
    // Android 11+ rejects a request that bundles background with foreground
    // location, and the OS only shows the "Allow all the time" option once
    // fine/coarse is already granted. It's requested in the second step below.
    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // Step 2 of the Android 11+ two-step location flow: "Allow all the time".
    // On Android 11+ this MUST be its own request, launched only after fine
    // location is granted; the system takes the user to a settings-style
    // screen for it. Required so the bridge's FOREGROUND_SERVICE_TYPE_LOCATION
    // survives a CDM background start (screen-off in-car GPS). No-op below
    // Android 10, where background location is implied by fine/coarse.
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* No-op: startGpsUpdates / startForeground re-check at use time. */ }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Only prompt for "Allow all the time" once foreground location is in
        // hand (either just granted here or granted on a prior launch). Below
        // Android 10 the background permission doesn't exist as a separate grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            val backgroundGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (fineGranted && !backgroundGranted) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    LaunchedEffect(Unit) {
        // Already-granted permissions return instantly without a dialog, so
        // after the setup wizard's Permissions step this is a silent no-op —
        // but it still drives the background-location follow-up above.
        launcher.launch(perms)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                for (tab in Tab.entries) {
                    val selected = pagerState.currentPage == tab.ordinal
                    NavigationBarItem(
                        selected = selected,
                        onClick = { goTo(tab) },
                        icon = {
                            // The label below already names the item; a
                            // matching contentDescription made TalkBack read
                            // "Home, Home".
                            Icon(
                                if (selected) tab.iconActive else tab.iconInactive,
                                contentDescription = null,
                            )
                        },
                        label = { Text(tab.label) },
                        // M3 baseline: tonal indicator, not a filled primary
                        // blob — the coral accent is for the one primary
                        // action on a screen, not for "you are here".
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            // The bottom bar switches tabs; swiping does not. Every tab but
            // Home hosts something that wants horizontal drags for itself —
            // maps, the trip timeline scrub, chip rails — and a pager
            // underneath stole them half-way through the gesture.
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Compose by default lazily renders only the visible page; we keep
            // beyondViewportPageCount = 0 so off-screen tabs don't pay the
            // recompose cost while idle. Live + Fuel both attach to view-models
            // that emit even when not visible (BridgeStateBus, etc.) so the
            // numbers don't go stale on tab switch.
        ) { page ->
            // Each screen owns its own Scaffold + TopAppBar; the outer Scaffold
            // here only contributes the bottomBar. Every tab wears the brand
            // PitstopTopAppBar; pushed detail screens wear DetailTopAppBar.
            when (Tab.entries[page]) {
                Tab.Home -> StatusScreen(
                    onOpenHistory = {
                        historyVm().selectSubTab(HistorySubTab.Trips)
                        goTo(Tab.History)
                    },
                    onOpenSettings = { goTo(Tab.Settings) },
                    onOpenDtc = { code, vehicleId ->
                        historyVm().openDtc(code, vehicleId)
                        goTo(Tab.History)
                    },
                    onOpenTrip = { id ->
                        historyVm().openTrip(id)
                        goTo(Tab.History)
                    },
                )
                Tab.Live -> LiveScreen(onOpenHome = { goTo(Tab.Home) })
                Tab.History -> HistoryScreen()
                Tab.Fuel -> FuelAddScreen()
                Tab.Settings -> ConfigScreen(pendingSetupLinkFlow = pendingSetupLinkFlow)
            }
        }
    }
}
