package com.pitstop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Photos-style fast scroller for a long [LazyListState] list. Lay it over
 * the list with `Modifier.matchParentSize()`.
 *
 * A thin track on the right edge shows while the list scrolls and fades
 * ~1.5 s after it stops. Its thumb (48 dp touch target) drags the list in
 * proportion to item index; while dragging, a bubble names the date
 * bucket under the thumb — [labelForKey] maps a visible item's key to it
 * ("Sep 2026"); null keys are skipped, and a null [labelForKey] (a list
 * sorted by something other than date) means no bubble, just the thumb.
 *
 * Only the thumb takes touches, and only vertical drags, so the list's
 * own taps and any horizontal swipe go through untouched. Reduced motion
 * drops the fade. [topInset] / [bottomInset] keep the track clear of
 * headers and the FAB column.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    labelForKey: ((Any) -> String?)? = null,
    topInset: Dp = 8.dp,
    bottomInset: Dp = 8.dp,
) {
    val reduceMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val label by rememberUpdatedState(labelForKey)
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }

    // Long enough to be worth it: more than ~3 screens of items.
    val scrollable by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount > info.visibleItemsInfo.size * 3
        }
    }
    val progress by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val span = (info.totalItemsCount - visible.size).coerceAtLeast(1)
            val first = visible.firstOrNull()
            val within = if (first != null && first.size > 0) state.firstVisibleItemScrollOffset.toFloat() / first.size else 0f
            ((state.firstVisibleItemIndex + within) / span).coerceIn(0f, 1f)
        }
    }
    val bubble by remember(state) {
        derivedStateOf {
            val f = label ?: return@derivedStateOf null
            // Skip items that have scrolled (mostly) under a pinned sticky
            // header: they are first in visibleItemsInfo but not what the
            // eye sees at the top.
            val top = state.layoutInfo.viewportStartOffset
            state.layoutInfo.visibleItemsInfo
                .filter { it.offset + it.size / 2 >= top }
                .firstNotNullOfOrNull { f(it.key) }
        }
    }

    val active = state.isScrollInProgress || dragging
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1_500)
            shown = false
        }
    }
    if (!scrollable) return
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 250),
        label = "fastScroller",
    )
    if (alpha == 0f) return

    BoxWithConstraints(
        modifier
            .padding(top = topInset, bottom = bottomInset)
            .alpha(alpha),
    ) {
        val thumbH = 48.dp
        val density = LocalDensity.current
        val travelNow = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val travelPx by rememberUpdatedState(travelNow)
        val frac = if (dragging) dragFrac else progress
        val thumbY = with(density) { (frac * travelPx).toDp() }
        // Track.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
        )
        // Thumb: 48 dp target around a slim handle.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbY)
                .size(48.dp)
                .semantics { contentDescription = "Fast scroll" }
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFrac = progress
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        dragFrac = (dragFrac + dy / travelPx).coerceIn(0f, 1f)
                        val info = state.layoutInfo
                        val span = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
                        val target = (dragFrac * span).roundToInt()
                        scope.launch { state.scrollToItem(target) }
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
                    .width(7.dp)
                    .fillMaxHeight(0.85f)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
        }
        val text = bubble
        if (dragging && text != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-56).dp, y = thumbY + 6.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
