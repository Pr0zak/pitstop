package com.pitstop.ui.live

import androidx.lifecycle.ViewModel
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.MetricSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    stateBus: BridgeStateBus,
) : ViewModel() {

    val latestByMetric: StateFlow<Map<String, MetricSample>> = stateBus.latestByMetric
}
