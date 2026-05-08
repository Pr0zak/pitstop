package com.pitstop.ui.fuel

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val SERIALIZER = ListSerializer(HistoricFillup.serializer())

@Serializable
data class HistoricFillup(
    val tsMs: Long,
    val stationName: String?,
    val lat: Double?,
    val lon: Double?,
)

private val Context.fuelHistoryStore by preferencesDataStore(name = "pitstop_fuel_history")
private val FUEL_HISTORY_KEY: Preferences.Key<String> = stringPreferencesKey("history_json")

/**
 * Local cache of recent fuel-up submissions. Used to:
 *   - autocomplete station names
 *   - suggest "log fillup at this station" if the device is near a prior fillup (~150 m)
 *
 * The cache caps at 100 entries.
 */
@Singleton
class FuelHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun all(): List<HistoricFillup> {
        val raw = context.fuelHistoryStore.data
            .map { it[FUEL_HISTORY_KEY].orEmpty() }
            .first()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(SERIALIZER, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun add(entry: HistoricFillup) {
        val updated = (listOf(entry) + all()).take(100)
        context.fuelHistoryStore.edit {
            it[FUEL_HISTORY_KEY] = json.encodeToString(SERIALIZER, updated)
        }
    }

    /** Returns the most-recent prior fillup within [radiusMeters] of (lat, lon), or null. */
    suspend fun nearestRecent(lat: Double, lon: Double, radiusMeters: Double = 150.0): HistoricFillup? {
        return all()
            .filter { it.lat != null && it.lon != null }
            .firstOrNull { haversine(lat, lon, it.lat!!, it.lon!!) <= radiusMeters }
    }

    fun stationSuggestions(history: List<HistoricFillup>, query: String): List<String> {
        val q = query.trim().lowercase()
        return history
            .mapNotNull { it.stationName }
            .distinct()
            .filter { q.isBlank() || it.lowercase().contains(q) }
            .take(8)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }
}
