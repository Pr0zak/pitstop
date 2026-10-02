package com.pitstop.ui.status

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * The Home tab's own back stack: the dashboard, and the detail screens
 * Home opens itself (today: MPG over time). Everything else Home links to
 * lives on another tab and is handed over by MainActivity's callbacks.
 *
 * [StatusViewModel] is resolved HERE, outside the NavHost, so it keeps the
 * owner it always had (the activity, via the pager page) instead of being
 * re-scoped to a back-stack entry.
 */
@Composable
fun HomeSection(
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDtc: (code: String, vehicleId: String) -> Unit = { _, _ -> },
    onOpenTrip: (id: String) -> Unit = {},
    onOpenService: () -> Unit = {},
    onOpenFuel: () -> Unit = {},
    onOpenFillup: (String) -> Unit = {},
) {
    val viewModel: StatusViewModel = hiltViewModel()
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ROUTE_HOME, modifier = Modifier.fillMaxSize()) {
        composable(ROUTE_HOME) {
            StatusScreen(
                viewModel = viewModel,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
                onOpenDtc = onOpenDtc,
                onOpenTrip = onOpenTrip,
                onOpenService = onOpenService,
                onOpenMpgHistory = { nav.navigate(ROUTE_MPG) },
                onOpenFuel = onOpenFuel,
                onOpenFillup = onOpenFillup,
            )
        }
        composable(ROUTE_MPG) {
            val ui by viewModel.uiState.collectAsStateWithLifecycle()
            MpgHistoryScreen(points = ui.mpgMonthly, onBack = { nav.popBackStack() })
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_MPG = "mpg-history"
