package com.pitstop.domain

/**
 * Holds back a corner's TPMS readings after a park until its tire sensor has
 * really reported.
 *
 * The Pilot's TPMS module answers DID 226001 with the last set it heard. The
 * sensors stay quiet while the car is parked and transmit again a minute or
 * so into a drive, so the first answers after a park are a stored set of
 * unknown age. On 2026-11-16 it reported FR 218 kPa (32 psi) for the first
 * six minutes; the stored set dated from before the previous day's drive.
 * When FR's sensor woke it read 152 kPa (22 psi): the tire had lost air
 * overnight. Recorded with fresh timestamps, the stored set passed for a
 * cold reading and hid the leak on Home and the widgets.
 *
 * Rule, per corner: after [rearmMs] without a pressure reading, the next
 * pressure is latched and nothing for that corner is admitted until the
 * pressure differs from the latch (the sensor has transmitted). Over 25 days
 * of drives the first change came 30 s to 12 min after the first reading,
 * usually by 1 to 3 kPa. A drive too short for the sensors to wake records
 * no tire readings. Temps follow their corner's pressure. Every other metric
 * is admitted.
 *
 * Mirrored on the server for the WiCAN's own AutoPID frames
 * (backend tires.TpmsWakeGate); keep the two in step.
 */
class TpmsWakeGate(private val rearmMs: Long = REARM_MS) {

    private class Corner(var latch: Double, var awake: Boolean, var lastMs: Long)

    private val corners = HashMap<String, Corner>()

    /** True when [metric] = [value] at [tMs] should be recorded. */
    fun admit(metric: String, value: Double, tMs: Long): Boolean {
        if (metric.startsWith(TEMP)) {
            val c = corners[metric.removePrefix(TEMP)] ?: return false
            return c.awake && tMs - c.lastMs < rearmMs
        }
        if (!metric.startsWith(PRESSURE)) return true
        // A 0 kPa "no answer" carries no age; leave the state alone.
        if (value <= 0) return false
        val key = metric.removePrefix(PRESSURE)
        val c = corners[key]
        if (c == null || tMs - c.lastMs >= rearmMs) {
            corners[key] = Corner(latch = value, awake = false, lastMs = tMs)
            return false
        }
        c.lastMs = tMs
        if (!c.awake && value != c.latch) c.awake = true
        return c.awake
    }

    companion object {
        /** A gap this long between pressures = parked; the sensors sleep. */
        const val REARM_MS = 10 * 60_000L
        private const val PRESSURE = "tire_pressure_"
        private const val TEMP = "tire_temp_"
    }
}
