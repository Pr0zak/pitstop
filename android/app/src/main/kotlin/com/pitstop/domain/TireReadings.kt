package com.pitstop.domain

/**
 * The ONE merge behind "the last tire readings always show" (Home Tires
 * card + the tire widget). Sources, newest value PER METRIC wins — so a
 * corner missing from the newest set keeps its older value instead of a
 * dash:
 *  1. the persisted per-vehicle snapshot (survives offline and reboots),
 *  2. the server's /readings/latest for the SELECTED vehicle (30-day
 *     lookback server-side — older corners live on in the snapshot),
 *  3. live samples (phone Mode 22 poll / WiCAN MQTT).
 * Values are (value, epoch ms). Only `tire_*` metrics are kept.
 */
object TireReadings {

    fun merge(vararg sources: Map<String, Pair<Double, Long>>): Map<String, Pair<Double, Long>> {
        val out = HashMap<String, Pair<Double, Long>>()
        for (src in sources) for ((k, v) in src) {
            if (!k.startsWith("tire_")) continue
            // Sentinels never displace a real reading: 0 kPa = no answer.
            if (k.startsWith("tire_pressure_") && v.first <= 0) continue
            val cur = out[k]
            if (cur == null || v.second >= cur.second) out[k] = v
        }
        return out
    }

    /**
     * The vehicle the user is looking at: the in-app selection, else the
     * bridge's configured slug. NEVER "the first vehicle" — /api/vehicles
     * lists the Truck before the Pilot.
     */
    fun <V> pickVehicle(vehicles: List<V>, slugOf: (V) -> String, viewSlug: String?, configuredSlug: String): V? {
        val slug = viewSlug?.trim()?.takeIf { it.isNotEmpty() } ?: configuredSlug.trim()
        if (slug.isEmpty()) return null
        return vehicles.firstOrNull { slugOf(it) == slug }
    }
}
