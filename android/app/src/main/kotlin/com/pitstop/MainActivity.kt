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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ui.config.ConfigScreen
import com.pitstop.ui.onboarding.OnboardingGateViewModel
import com.pitstop.ui.onboarding.SetupWizardScreen
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.history.HistoryScreen
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.status.StatusScreen
import com.pitstop.ui.theme.PitstopTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Cleared by the launcher when a deep-link intent fires and the
     *  pager scrolls there; subsequent recomposes don't re-navigate. */
    private val pendingTabIndex = MutableStateFlow<Int?>(null)

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
        // notification body tap — land on History (tab 2) so the user
        // can see the queued drives + tap "Sync now".
        // A pitstop://setup?… VIEW intent lands on Settings (tab 4) and hands
        // the link to ConfigScreen to import.
        val setupLink = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data?.takeIf { it.scheme == "pitstop" }?.toString()
        if (setupLink != null) pendingSetupLink.value = setupLink
        val initialTab = when {
            setupLink != null -> 4
            intent?.action == ACTION_ADD_FILLUP -> 3
            intent?.action == ACTION_SYNC_DRIVES -> 2
            else -> 0
        }
        setContent {
            PitstopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PitstopRoot(
                        initialTab = initialTab,
                        pendingTabFlow = pendingTabIndex,
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
        when (intent.action) {
            ACTION_ADD_FILLUP -> pendingTabIndex.value = 3
            ACTION_SYNC_DRIVES -> pendingTabIndex.value = 2
            Intent.ACTION_VIEW -> {
                val link = intent.data?.takeIf { it.scheme == "pitstop" }?.toString()
                if (link != null) {
                    pendingSetupLink.value = link
                    pendingTabIndex.value = 4
                }
            }
        }
    }

    companion object {
        const val ACTION_ADD_FILLUP = "com.pitstop.action.ADD_FILLUP"
        const val ACTION_SYNC_DRIVES = "com.pitstop.action.SYNC_DRIVES"
    }
}

private data class TabDestination(
    val label: String,
    val iconActive: ImageVector,
    val iconInactive: ImageVector,
)

private val tabDestinations = listOf(
    TabDestination("Home",     Icons.Filled.Home,            Icons.Outlined.Home),
    TabDestination("Live",     Icons.Filled.Speed,           Icons.Outlined.Speed),
    TabDestination("History",  Icons.Filled.History,         Icons.Outlined.History),
    TabDestination("Fuel",     Icons.Filled.LocalGasStation, Icons.Outlined.LocalGasStation),
    TabDestination("Settings", Icons.Filled.Settings,        Icons.Outlined.Settings),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PitstopRoot(
    initialTab: Int = 0,
    pendingTabFlow: MutableStateFlow<Int?>? = null,
    pendingSetupLinkFlow: MutableStateFlow<String?>? = null,
    gateViewModel: OnboardingGateViewModel = hiltViewModel(),
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

    val pagerState = rememberPagerState(initialPage = initialTab) { tabDestinations.size }
    val scope = rememberCoroutineScope()

    // Route an onNewIntent deep link (shortcut tap on already-running
    // app) to the right pager page. Clears the pending value so a
    // recompose doesn't repeat the scroll.
    val pendingTab = pendingTabFlow?.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTab?.value) {
        val target = pendingTab?.value
        if (target != null) {
            pagerState.animateScrollToPage(target)
            pendingTabFlow?.value = null
        }
    }

    val context = LocalContext.current

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
        launcher.launch(perms)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                tabDestinations.forEachIndexed { i, dest ->
                    val selected = pagerState.currentPage == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(i) }
                        },
                        icon = {
                            Icon(
                                if (selected) dest.iconActive else dest.iconInactive,
                                contentDescription = dest.label,
                            )
                        },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
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
            // here only contributes the bottomBar. Material 3 reconciles the
            // nested insets so each page's content sits between top + bottom
            // bars correctly.
            when (page) {
                0 -> StatusScreen(
                    onOpenHistory = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                    onOpenSettings = {
                        scope.launch { pagerState.animateScrollToPage(4) }
                    },
                )
                1 -> LiveScreen()
                2 -> HistoryScreen()
                3 -> FuelAddScreen()
                4 -> ConfigScreen(pendingSetupLinkFlow = pendingSetupLinkFlow)
            }
        }
    }
}
