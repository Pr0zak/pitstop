package com.pitstop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * `rememberLazyListState()` for every LazyColumn / LazyRow in the app.
 *
 * Identical to the default at runtime. Under inspection (previews and the
 * Paparazzi screenshot tests) it swaps in a no-op prefetch strategy: the
 * default prefetch scheduler asks `Display.getRefreshRate()`, which the
 * layoutlib build the screenshots render with cannot answer (it throws
 * NoSuchMethodError), so every lazy list would fail to render at all.
 * Nothing scrolls in a screenshot, so there is nothing to prefetch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPitstopListState(): LazyListState =
    if (LocalInspectionMode.current) {
        rememberLazyListState(prefetchStrategy = InspectionPrefetch)
    } else {
        rememberLazyListState()
    }

@OptIn(ExperimentalFoundationApi::class)
private object InspectionPrefetch : LazyListPrefetchStrategy {
    override val prefetchScheduler: PrefetchScheduler = object : PrefetchScheduler {
        override fun schedulePrefetch(prefetchRequest: PrefetchRequest) = Unit
    }
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}
