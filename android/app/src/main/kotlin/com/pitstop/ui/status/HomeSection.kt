package com.pitstop.ui.status

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pitstop.ui.insights.HomeInsightsCard
import com.pitstop.ui.insights.InsightsScreen
import com.pitstop.ui.insights.InsightsViewModel
import com.pitstop.util.requireActivity

/**
 * The Home tab's own back stack: the dashboard, and the detail screens
 * Home opens itself (MPG over time, Insights). Everything else Home links to
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
    onWicanAction: () -> Unit = onOpenSettings,
) {
    val viewModel: StatusViewModel = hiltViewModel()
    // Activity-scoped, shared with Car's entry card and the Insights screen.
    val insightsVm: InsightsViewModel = hiltViewModel(LocalContext.current.requireActivity())
    val insights by insightsVm.ui.collectAsStateWithLifecycle()
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
                onWicanAction = onWicanAction,
                insightsCard = {
                    LaunchedEffect(Unit) { insightsVm.refreshIfStale() }
                    HomeInsightsCard(insights, onOpen = { nav.navigate(ROUTE_INSIGHTS) })
                },
            )
        }
        composable(ROUTE_MPG) {
            val ui by viewModel.uiState.collectAsStateWithLifecycle()
            MpgHistoryScreen(points = ui.mpgMonthly, onBack = { nav.popBackStack() })
        }
        composable(ROUTE_INSIGHTS) {
            InsightsScreen(onBack = { nav.popBackStack() })
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_MPG = "mpg-history"
private const val ROUTE_INSIGHTS = "insights"
