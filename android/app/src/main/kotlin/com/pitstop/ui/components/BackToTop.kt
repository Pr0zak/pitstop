package com.pitstop.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * "↑ Top": the one back-to-top control for every long list. It sits at
 * the bottom-end in the Scaffold's FAB slot — on Fuel stacked above
 * "Log fillup" — so it never covers that button, and it only shows once
 * the list is about two screens down.
 */
@Composable
fun BackToTopButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clearAndSetSemantics {
                    contentDescription = "Back to top"
                    role = Role.Button
                },
        ) {
            Text(
                "↑ Top",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Lists: past ~8 items or ~two viewports down. */
@Composable
fun rememberBackToTopVisible(state: LazyListState): Boolean {
    val v by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val visible = info.visibleItemsInfo
            val avg = if (visible.isEmpty()) 0 else visible.sumOf { it.size } / visible.size
            val scrolledPx = state.firstVisibleItemIndex.toLong() * avg + state.firstVisibleItemScrollOffset
            state.firstVisibleItemIndex >= 8 || (viewport > 0 && scrolledPx > 2L * viewport)
        }
    }
    return v
}

@Composable
fun rememberBackToTopVisible(state: ScrollState): Boolean {
    val v by remember(state) {
        // A plain scroll (Home) is rarely more than ~3 screens tall, so
        // its threshold is 1.5 screens rather than the lists' two.
        derivedStateOf { state.viewportSize > 0 && state.value > state.viewportSize * 3 / 2 }
    }
    return v
}

/** Jump most of the way instantly, animate the last stretch — never a seconds-long glide. */
suspend fun LazyListState.scrollToTop(reduceMotion: Boolean) {
    if (reduceMotion) {
        scrollToItem(0)
        return
    }
    if (firstVisibleItemIndex > 6) scrollToItem(6)
    animateScrollToItem(0)
}

suspend fun ScrollState.scrollToTop(reduceMotion: Boolean) {
    if (reduceMotion) {
        scrollTo(0)
        return
    }
    if (value > viewportSize) scrollTo(viewportSize)
    animateScrollTo(0)
}

/** The system "Remove animations" setting (animator duration scale 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
