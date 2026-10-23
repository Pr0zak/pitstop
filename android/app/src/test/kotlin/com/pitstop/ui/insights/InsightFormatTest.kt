package com.pitstop.ui.insights

import com.pitstop.http.InsightCardDto
import com.pitstop.http.InsightGroupDto
import com.pitstop.http.InsightsResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightFormatTest {

    @Test fun `numbers drop trailing zeros and group thousands`() {
        assertEquals("4", InsightFormat.number(4.0))
        assertEquals("12.28", InsightFormat.number(12.28))
        assertEquals("1,345", InsightFormat.number(1345.0))
        assertEquals("−4.6", InsightFormat.number(-4.6))
        assertEquals("0", InsightFormat.number(-0.0))
    }

    @Test fun `leading unit symbols join the number`() {
        assertEquals(InsightFormat.ValueParts("10%", "swing"), InsightFormat.valueParts(10.0, "% swing"))
        assertEquals(InsightFormat.ValueParts("$65", null), InsightFormat.valueParts(65.0, "$"))
        assertEquals(InsightFormat.ValueParts("153°F", "peak"), InsightFormat.valueParts(153.0, "°F peak"))
        assertEquals(InsightFormat.ValueParts("−30¢", "/gal vs average"), InsightFormat.valueParts(-30.0, "¢/gal vs average"))
        assertEquals(InsightFormat.ValueParts("12.28", "V"), InsightFormat.valueParts(12.28, "V"))
        assertEquals(InsightFormat.ValueParts("3", null), InsightFormat.valueParts(3.0, null))
    }

    @Test fun `footnote joins window and sample`() {
        assertEquals("Last 30 days · 2,400 readings", InsightFormat.footnote("Last 30 days", "2,400 readings"))
        assertEquals("All data", InsightFormat.footnote("All data", null))
        assertEquals("30 fill-ups", InsightFormat.footnote(null, "30 fill-ups"))
        assertEquals(null, InsightFormat.footnote(null, " "))
    }

    @Test fun `progress reads as have of need`() {
        assertEquals("2 of 7 days", InsightFormat.progress(2.0, 7.0, "days"))
    }

    @Test fun `nice ticks cover the data`() {
        val t = InsightFormat.niceTicks(11.9, 12.8, 3)
        assertTrue(t.first() <= 11.9 && t.last() >= 12.8)
        assertTrue(t.size in 2..6)
        val flat = InsightFormat.niceTicks(5.0, 5.0, 3)
        assertTrue(flat.first() < 5.0 && flat.last() > 5.0)
        assertEquals(1, InsightFormat.labelStep(4, 7))
        assertEquals(3, InsightFormat.labelStep(16, 7))
        // Bar 11 highlighted among 16 at step 3: its close neighbours 9 and 12 drop out.
        assertEquals(setOf(0, 3, 6, 11, 15), InsightFormat.shownLabels(16, 3, listOf(11)))
        assertEquals((0 until 4).toSet(), InsightFormat.shownLabels(4, 1, listOf(2)))
    }

    @Test fun `rows put attention first, filter by group, learning last`() {
        fun card(id: String, group: String, status: String) = InsightCardDto(id = id, group = group, title = id, status = status, headline = "h")
        val data = InsightsResponseDto(
            groups = listOf(InsightGroupDto("fuel", "Fuel"), InsightGroupDto("engine", "Engine"), InsightGroupDto("cost", "Cost")),
            attention = listOf("b", "gone"),
            insights = listOf(card("l", "fuel", "learning"), card("a", "fuel", "good"), card("b", "engine", "bad"), card("x", "mystery", "info")),
        )
        val all = insightRows(data, null)
        assertEquals(
            listOf("intro", "attention", "groups", "h:fuel", "c:a", "c:l", "h:engine", "c:b", "h:other", "c:x"),
            all.map { it.key },
        )
        assertEquals(listOf("b"), (all[1] as InsightRow.Attention).cards.map { it.id })
        val engine = insightRows(data, "engine")
        assertEquals(listOf("intro", "attention", "groups", "h:engine", "c:b"), engine.map { it.key })
    }

    @Test fun `home shows only critical cards, worst first`() {
        val data = InsightsResponseDto(
            attention = listOf("leak", "battery", "trips"),
            insights = listOf(
                InsightCardDto(id = "trips", status = "watch"),
                InsightCardDto(id = "battery", status = "bad"),
                InsightCardDto(id = "warmup", status = "good"),
                InsightCardDto(id = "leak", status = "bad"),
                InsightCardDto(id = "misfires", status = "learning"),
            ),
        )
        assertEquals(listOf("leak", "battery"), criticalInsights(data).map { it.id })
    }

    @Test fun `amber-only or missing data has no critical cards`() {
        assertTrue(criticalInsights(null).isEmpty())
        val amber = InsightsResponseDto(
            attention = listOf("trips"),
            insights = listOf(InsightCardDto(id = "trips", status = "watch"), InsightCardDto(id = "x", status = "info")),
        )
        assertTrue(criticalInsights(amber).isEmpty())
    }

    @Test fun `a bad card missing from attention still counts, after ranked ones`() {
        val data = InsightsResponseDto(
            attention = listOf("b"),
            insights = listOf(InsightCardDto(id = "a", status = "bad"), InsightCardDto(id = "b", status = "bad")),
        )
        assertEquals(listOf("b", "a"), criticalInsights(data).map { it.id })
    }
}
