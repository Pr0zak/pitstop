package com.pitstop

import android.Manifest
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
import com.pitstop.ui.config.ConfigScreen
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.history.HistoryScreen
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.status.StatusScreen
import com.pitstop.ui.theme.PitstopTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitstopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PitstopRoot()
                }
            }
        }
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
private fun PitstopRoot() {
    val pagerState = rememberPagerState(initialPage = 0) { tabDestinations.size }
    val scope = rememberCoroutineScope()

    // Up-front permission request (notifications + bluetooth + location).
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* No-op: surfaces are responsible for re-checking before use. */ }

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
                0 -> StatusScreen()
                1 -> LiveScreen()
                2 -> HistoryScreen()
                3 -> FuelAddScreen()
                4 -> ConfigScreen()
            }
        }
    }
}
