package com.pitstop

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pitstop.ui.config.ConfigScreen
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.status.StatusScreen
import com.pitstop.ui.theme.PitstopTheme
import dagger.hilt.android.AndroidEntryPoint

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

/**
 * Routes split into two tiers — primary destinations carry the bottom
 * NavigationBar; push routes hide it so a flow like "Add fillup" or
 * "Configure broker" gets the user's full attention without the
 * persistent nav chrome stealing pixels.
 */
private object Routes {
    // Primary (bottom-bar) destinations
    const val HOME = "home"
    const val LIVE = "live"
    const val FUEL = "fuel"
    // Push routes (modal-feel, no bottom bar)
    const val CONFIG = "config"
    const val FUEL_ADD = "fuel/add"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val iconActive: ImageVector,
    val iconInactive: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomDestination(Routes.LIVE, "Live", Icons.Filled.Speed, Icons.Outlined.Speed),
    BottomDestination(Routes.FUEL, "Fuel", Icons.Filled.LocalGasStation, Icons.Outlined.LocalGasStation),
)

@Composable
private fun PitstopRoot() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

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

    val isPrimaryRoute = currentRoute in bottomDestinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (isPrimaryRoute) {
                PitstopBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { dest ->
                        // Standard Material 3 bottom-nav pattern: pop back to the
                        // graph start so we don't accumulate destinations on the
                        // back stack as the user taps between primary tabs, but
                        // keep state on tabs they've visited.
                        nav.navigate(dest.route) {
                            popUpTo(nav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Inner NavHost; the screens consume `padding` (or pass it through their
        // own Scaffolds, which Compose merges with the outer insets correctly).
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(if (isPrimaryRoute) padding else PaddingValues(0.dp)),
        ) {
            composable(Routes.HOME) {
                StatusScreen(
                    onOpenConfig = { nav.navigate(Routes.CONFIG) },
                    onOpenLive = { nav.navigate(Routes.LIVE) },
                    onOpenFuel = { nav.navigate(Routes.FUEL) },
                )
            }
            composable(Routes.LIVE) {
                LiveScreen(
                    onBack = { nav.popBackStack() },
                    onOpenConfig = { nav.navigate(Routes.CONFIG) },
                )
            }
            composable(Routes.FUEL) {
                FuelAddScreen(
                    onBack = { nav.popBackStack() },
                    onOpenConfig = { nav.navigate(Routes.CONFIG) },
                )
            }
            composable(Routes.CONFIG) {
                ConfigScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun PitstopBottomBar(
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        for (dest in bottomDestinations) {
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
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
}

