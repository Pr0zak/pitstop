package com.pitstop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One page of the Home "Trends" carousel: its title and its body. */
class TrendPage(val title: String, val content: @Composable () -> Unit)

/**
 * Home's long-range charts (MPG by month, lifetime MPG, cost per mile,
 * monthly spend) as ONE swipeable card, so Recent trips sits a screen
 * higher. Header: "Trends", the current page's title, and page dots.
 *
 * Stateless on purpose: every page renders data the Home ViewModel has
 * already loaded. Nothing here may launch or own cancellable work — the
 * pager composes and disposes pages as they scroll (the same trap as the
 * app's tab pager, whose ViewModels die on swipe).
 *
 * Under inspection (previews, Paparazzi) the first page renders without
 * a pager: pager is a lazy layout and layoutlib cannot run its prefetch
 * scheduler (see [rememberPitstopListState]).
 */
@Composable
fun TrendsCarousel(pages: List<TrendPage>, modifier: Modifier = Modifier) {
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState { pages.size }
    val inspection = LocalInspectionMode.current
    val current = if (inspection) 0 else pagerState.currentPage
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Trends",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "  ·  ${pages[current].title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                PageDots(count = pages.size, current = current)
            }
            if (inspection) {
                pages.first().content()
            } else {
                HorizontalPager(
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    pages[page].content()
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count < 2) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Page ${current + 1} of $count" },
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 7.dp else 6.dp)
                    .background(
                        if (i == current) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * Frame for a trend body: its own card on its own, bare when it is a
 * [TrendsCarousel] page (the carousel is the card).
 */
@Composable
internal fun TrendFrame(framed: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (framed) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) { content() }
    } else {
        Box(modifier.fillMaxWidth()) { content() }
    }
}
