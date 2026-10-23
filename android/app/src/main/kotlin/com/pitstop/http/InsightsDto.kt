package com.pitstop.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GET /analytics/insights — plain-language findings, one card shape for
// every insight. The backend module docstring (backend/src/pitstop/insights.py)
// is the contract. Everything here is server-computed and already in US
// display units; the phone only renders it (ADR-013).
//
// kotlinx-serialization is strict, so every optional field is nullable
// with a default, and every number is a Double: Python sends `12` and
// `12.0` interchangeably, and an Int field would reject the latter.

@Serializable
data class InsightsResponseDto(
    @SerialName("groups") val groups: List<InsightGroupDto> = emptyList(),
    /** Card ids that need the driver's attention, worst first. */
    @SerialName("attention") val attention: List<String> = emptyList(),
    @SerialName("insights") val insights: List<InsightCardDto> = emptyList(),
    @SerialName("generated_at") val generatedAt: String? = null,
    /** Trips the server hasn't summarised yet; cards may still fill in. */
    @SerialName("pending_trips") val pendingTrips: Double? = null,
)

@Serializable
data class InsightGroupDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String? = null,
)

@Serializable
data class InsightCardDto(
    @SerialName("id") val id: String,
    @SerialName("group") val group: String = "",
    @SerialName("title") val title: String = "",
    /** good | watch | bad | info | learning. Anything else renders as info. */
    @SerialName("status") val status: String = "info",
    @SerialName("headline") val headline: String = "",
    @SerialName("detail") val detail: String? = null,
    /** The big number; may arrive as an int or a float. */
    @SerialName("value") val value: Double? = null,
    @SerialName("value_unit") val valueUnit: String? = null,
    @SerialName("chart") val chart: InsightChartDto? = null,
    /** How the number was worked out. */
    @SerialName("how") val how: String? = null,
    @SerialName("tip") val tip: String? = null,
    @SerialName("sample") val sample: String? = null,
    /** The data window this card looked at, e.g. "Last 30 days". */
    @SerialName("window") val window: String? = null,
    /** Only on "learning" cards: how much data it has vs needs. */
    @SerialName("progress") val progress: InsightProgressDto? = null,
)

@Serializable
data class InsightProgressDto(
    @SerialName("have") val have: Double = 0.0,
    @SerialName("need") val need: Double = 0.0,
    @SerialName("unit") val unit: String? = null,
)

/**
 * One DTO for all three chart kinds; the fields a kind doesn't use stay null.
 *
 *  - `bars`:   [yUnit], [bars], optional [band] / [ref]
 *  - `lines`:  [xType] ("time", x = epoch ms), [yUnit], [series], optional [band] / [ref]
 *  - `points`: [xUnit], [yUnit], [points] (a scatter)
 */
@Serializable
data class InsightChartDto(
    @SerialName("kind") val kind: String = "",
    @SerialName("y_unit") val yUnit: String? = null,
    @SerialName("x_unit") val xUnit: String? = null,
    @SerialName("x_type") val xType: String? = null,
    @SerialName("bars") val bars: List<InsightBarDto>? = null,
    @SerialName("series") val series: List<InsightSeriesDto>? = null,
    /** `[[x, y], ...]` for `points`. Entries with a null or missing coordinate are skipped. */
    @SerialName("points") val points: List<List<Double?>>? = null,
    @SerialName("band") val band: InsightBandDto? = null,
    @SerialName("ref") val ref: InsightRefDto? = null,
)

@Serializable
data class InsightBarDto(
    @SerialName("label") val label: String = "",
    /** Null = no data for this bucket (drawn as an empty slot). */
    @SerialName("value") val value: Double? = null,
    /** Sample count behind the bar; 0 when the card doesn't count. */
    @SerialName("n") val n: Double? = null,
    @SerialName("highlight") val highlight: Boolean = false,
)

@Serializable
data class InsightSeriesDto(
    @SerialName("name") val name: String = "",
    /** `[[epoch_ms, y], ...]`. */
    @SerialName("points") val points: List<List<Double?>> = emptyList(),
)

/** A shaded "healthy" range. */
@Serializable
data class InsightBandDto(
    @SerialName("lo") val lo: Double? = null,
    @SerialName("hi") val hi: Double? = null,
    @SerialName("label") val label: String? = null,
)

/** A dashed reference line. */
@Serializable
data class InsightRefDto(
    @SerialName("value") val value: Double? = null,
    @SerialName("label") val label: String? = null,
)
