package com.pitstop.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Live nearby fuel prices (ADR-026): grades, the server's `status` values,
 * and the text rules the Fuel tab's "Nearby prices" card renders. Pure, so
 * the web card's wording can be checked against it in unit tests.
 */

/** The four grades `GET /fuel-prices/nearby?grade=` accepts. */
enum class FuelGrade(val apiValue: String, val label: String) {
    Regular("REGULAR_UNLEADED", "Regular"),
    Mid("MIDGRADE", "Mid"),
    Premium("PREMIUM", "Premium"),
    Diesel("DIESEL", "Diesel"),
    ;

    companion object {
        fun fromApi(v: String?): FuelGrade = entries.firstOrNull { it.apiValue == v } ?: Regular
    }
}

/** Every `/fuel-prices/nearby` answer is a 200 carrying one of these. */
enum class NearbyStatus(val wire: String) {
    Ok("ok"),
    NoKey("no_key"),
    NoLocation("no_location"),
    QuotaReached("quota_reached"),
    UpstreamError("upstream_error"),
    ;

    companion object {
        /** Null for a value this build doesn't know (a newer server). */
        fun fromWire(v: String?): NearbyStatus? = entries.firstOrNull { it.wire == v }
    }
}

object FuelPrices {

    /**
     * "$3.49" / "$3.459": two decimals, or three when the price carries a
     * third (US pumps post 9/10ths). Currency comes from the station's own
     * code; the symbol placement from the device locale. "—" for no price —
     * never "$0.00", which would read as free fuel.
     */
    fun price(v: Double?, currency: String? = null, locale: Locale = Locale.getDefault()): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "—"
        // A language-only locale ("en") has no currency; same fallback as UnitFormat.money.
        val loc = if (locale.country.isNullOrBlank()) Locale.US else locale
        val nf = NumberFormat.getCurrencyInstance(loc)
        currency?.let { code -> runCatching { Currency.getInstance(code) }.getOrNull()?.let { nf.currency = it } }
        val digits = priceDigits(v)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(v)
    }

    /** 2, or 3 when the value has a meaningful third decimal. */
    fun priceDigits(v: Double): Int {
        val cents = v * 100.0
        return if (abs(cents - cents.roundToLong()) < 1e-6) 2 else 3
    }

    /**
     * The message a non-`ok` status (or an empty `ok`) shows in place of the
     * list; null when the list itself is the answer. [radiusLabel] is the
     * search radius already formatted in the user's distance unit.
     */
    fun statusMessage(
        status: NearbyStatus?,
        detail: String?,
        stationCount: Int,
        radiusLabel: String,
        hasLocationPermission: Boolean,
    ): String? = when (status) {
        NearbyStatus.Ok -> if (stationCount == 0) "No stations within $radiusLabel" else null
        NearbyStatus.NoKey -> "Add a Google Places API key in Settings to see live prices"
        NearbyStatus.NoLocation ->
            if (hasLocationPermission) {
                "No location to search from yet — the phone has no fix, the car hasn't reported GPS, and no home is set"
            } else {
                "No location to search from — allow location for pitstop, or set a home location in the web Settings"
            }
        NearbyStatus.QuotaReached -> "Monthly lookup limit reached — resets on the 1st"
        NearbyStatus.UpstreamError -> detail?.takeIf { it.isNotBlank() } ?: "Google couldn't answer the price lookup"
        null -> "This server returned a status the app doesn't know — update pitstop"
    }

    /**
     * Where the search was centred, when it wasn't the phone itself:
     * "Near where the car was parked · 3 h ago" / "Near home". Null for the
     * phone's own fix (or no origin), which needs no explanation.
     */
    fun originLine(source: String?, asOfAgo: String?): String? = when (source) {
        "vehicle" -> listOfNotNull("Near where the car was parked", asOfAgo).joinToString(" · ")
        "home" -> "Near home"
        else -> null
    }

    /**
     * "Prices from Google · 12/900 lookups this month · updated just now".
     * [fetchedAgo] is the age of `fetched_at` (null when the server sent
     * none). A fresh lookup (`cached=false`) reads "updated just now" — but
     * only while [fetchedAgeMs] really is recent, so a response replayed off
     * the offline HTTP cache days later can't claim to be new.
     */
    fun footer(
        monthCalls: Int?,
        monthlyCap: Int?,
        cached: Boolean,
        fetchedAgo: String?,
        fetchedAgeMs: Long?,
    ): String {
        val parts = mutableListOf("Prices from Google")
        if (monthCalls != null && monthlyCap != null && monthlyCap > 0) {
            parts += "$monthCalls/$monthlyCap lookups this month"
        }
        when {
            fetchedAgo == null -> Unit
            !cached && fetchedAgeMs != null && fetchedAgeMs < FRESH_MS -> parts += "updated just now"
            else -> parts += "updated $fetchedAgo"
        }
        return parts.joinToString(" · ")
    }

    /** "You paid $3.29 · Sep 12"; null when the user has no fillup there. */
    fun youPaid(price: Double?, dateLabel: String?, locale: Locale = Locale.getDefault()): String? {
        if (price == null) return null
        return listOfNotNull("You paid ${price(price, null, locale)}", dateLabel?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
    }

    /** How long a `cached=false` answer may still be called "just now". */
    const val FRESH_MS = 10 * 60_000L

    /** Rows shown before "Show all" once the card is open. */
    const val COLLAPSED_ROWS = 3

    /**
     * The one line a closed card shows: "$4.20 at Conoco · 0.4 mi" for the
     * cheapest priced station (the server sorts priced cheapest-first, so
     * that is the first row with a price), otherwise a short status.
     * [distance] formats metres in the user's unit.
     */
    fun summary(
        status: NearbyStatus?,
        stations: List<SummaryStation>,
        gradeLabel: String,
        loading: Boolean,
        error: String?,
        hasResult: Boolean,
        distance: (Int) -> String,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (!hasResult) {
            return when {
                loading -> "Looking up prices…"
                error != null -> error
                else -> "Not loaded yet"
            }
        }
        return when (status) {
            NearbyStatus.Ok -> {
                val best = stations.firstOrNull { it.price != null }
                when {
                    best != null -> listOfNotNull(
                        "${price(best.price, best.currency, locale)} at ${best.name ?: "a station"}",
                        distance(best.distanceM),
                    ).joinToString(" · ")
                    stations.isNotEmpty() -> "No $gradeLabel prices reported nearby"
                    else -> "No stations nearby"
                }
            }
            NearbyStatus.NoKey -> "Add a Places API key to see prices"
            NearbyStatus.NoLocation -> "No location to search from"
            NearbyStatus.QuotaReached -> "Monthly lookup limit reached"
            NearbyStatus.UpstreamError -> "Google couldn't answer"
            null -> "Update pitstop to see prices"
        }
    }

    /** What [summary] needs from a station row. */
    data class SummaryStation(
        val name: String?,
        val price: Double?,
        val currency: String?,
        val distanceM: Int,
    )
}

/**
 * `PATCH /settings` bodies for the Places API key. The one rule: a blank
 * key is never sent — [set] returns null for it, so a form that saves
 * before anything was typed can't wipe the stored key (the server also
 * ignores ""). Clearing is its own explicit [clear], an explicit JSON null.
 */
object PlacesKeyPatch {
    fun set(key: String): JsonObject? {
        val k = key.trim()
        if (k.isEmpty()) return null
        return body(JsonPrimitive(k))
    }

    fun clear(): JsonObject = body(JsonNull)

    private fun body(value: JsonElement) =
        JsonObject(
            mapOf("places" to JsonObject(mapOf("api_key" to value))),
        )
}
