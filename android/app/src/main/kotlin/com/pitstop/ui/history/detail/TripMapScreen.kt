package com.pitstop.ui.history.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pitstop.http.PitstopApi
import com.pitstop.http.RoutePointDto
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.LoadErrorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Route points for the full-screen map; null while loading. */
data class TripMapUi(val loading: Boolean = true, val points: List<RoutePointDto>? = null)

/**
 * Loads just the GPS route — the full-screen map doesn't need the trip's
 * samples, so it doesn't pay for them. OkHttp's cache usually answers this
 * from the request the detail screen already made.
 */
@HiltViewModel
class TripMapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: PitstopApi,
) : ViewModel() {
    private val tripId: String = savedStateHandle.get<String>("id")
        ?: error("trip id missing from navigation args")
    private val _ui = MutableStateFlow(TripMapUi())
    val ui: StateFlow<TripMapUi> = _ui.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _ui.value = TripMapUi(loading = true)
            val pts = runCatching { api.getTripRoute(tripId).points }.getOrNull()
            _ui.value = TripMapUi(loading = false, points = pts)
        }
    }
}

/**
 * trip/{id}/map — the route full-screen with pan / zoom enabled. The
 * embedded preview on trip detail is deliberately non-interactive; this is
 * where the gestures live.
 */
@Composable
fun TripMapScreen(
    onBack: () -> Unit,
    viewModel: TripMapViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { DetailTopAppBar(title = "Route", onBack = onBack) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val points = ui.points
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                points.isNullOrEmpty() -> LoadErrorState(what = "the route", onRetry = viewModel::load)
                else -> {
                    MapLibreRouteView(points = points, interactive = true, modifier = Modifier.fillMaxSize())
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { SpeedLegendRow() }
                    }
                }
            }
        }
    }
}
