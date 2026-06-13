package com.pitstop.obd

/**
 * Baseline OBD-II Mode 01 PIDs that the bridge polls. The metric names mirror
 * what the WiCAN AutoPID profile publishes for the Honda Pilot 2019 — see
 * `pid_profiles/honda-pilot-2019.json` in the backend.
 *
 * The "request" string is the ASCII OBD-II ELM-style command (Mode 01 + PID).
 * WiCAN's BLE bridge accepts both raw OBD frames and ELM-style ASCII; ELM is
 * cheaper to debug. If the device firmware needs raw CAN frames the formatter
 * here is the only place to swap.
 */
data class Pid(
    val name: String,
    val mode: Int,
    val pid: Int,
    val periodMs: Long,
    val parser: (ByteArray) -> Double?,
) {
    /** ELM327 ASCII command string for this PID, e.g. "010C\r" for engine RPM. */
    fun command(): String = "%02X%02X\r".format(mode, pid)
}

object Pids {

    /** A=byte0, B=byte1 etc. of the response data bytes (after the mode/PID echo). */
    private fun byte(b: ByteArray, i: Int): Int? = b.getOrNull(i)?.toInt()?.and(0xFF)

    val EngineRpm = Pid(
        name = "engine_rpm",
        mode = 0x01,
        pid = 0x0C,
        periodMs = 1_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 4.0
        },
    )

    val VehicleSpeed = Pid(
        name = "vehicle_speed",
        mode = 0x01,
        pid = 0x0D,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.toDouble() },
    )

    val CoolantTemp = Pid(
        name = "coolant_temp",
        mode = 0x01,
        pid = 0x05,
        periodMs = 5_000,
        parser = { bytes -> byte(bytes, 0)?.let { it - 40.0 } },
    )

    val ControlModuleVoltage = Pid(
        name = "control_module_voltage",
        mode = 0x01,
        pid = 0x42,
        periodMs = 5_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 1000.0
        },
    )

    val FuelLevel = Pid(
        name = "fuel_level",
        mode = 0x01,
        pid = 0x2F,
        periodMs = 30_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val ThrottlePosition = Pid(
        name = "throttle_position",
        mode = 0x01,
        pid = 0x11,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val IntakeAirTemp = Pid(
        name = "intake_air_temp",
        mode = 0x01,
        pid = 0x0F,
        periodMs = 5_000,
        parser = { bytes -> byte(bytes, 0)?.let { it - 40.0 } },
    )

    val EngineLoad = Pid(
        name = "engine_load",
        mode = 0x01,
        pid = 0x04,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.let { it * 100.0 / 255.0 } },
    )

    val ManifoldPressure = Pid(
        name = "manifold_pressure",
        mode = 0x01,
        pid = 0x0B,
        periodMs = 1_000,
        parser = { bytes -> byte(bytes, 0)?.toDouble() },  // kPa absolute
    )

    val MafAirFlow = Pid(
        name = "maf_air_flow",
        mode = 0x01,
        pid = 0x10,
        periodMs = 1_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b) / 100.0  // g/s
        },
    )

    val Odometer = Pid(
        name = "odometer",
        mode = 0x01,
        pid = 0xA6,
        // Odometer changes < 0.05 km/s even at WOT highway speeds.
        // Polling once every 30 s captures trip start + end accurately
        // without crowding the round-robin slots that fuel_level,
        // RPM, speed need at higher cadence.
        periodMs = 30_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            val c = byte(bytes, 2) ?: return@Pid null
            val d = byte(bytes, 3) ?: return@Pid null
            // SAE J1979 PID 0xA6: 4-byte unsigned, value in 0.1 km
            // units. Cast to Long up front so the << 24 doesn't
            // overflow Int when A is large.
            val raw = (a.toLong() shl 24) or
                (b.toLong() shl 16) or
                (c.toLong() shl 8) or
                d.toLong()
            raw / 10.0  // km
        },
    )

    val RunTimeSinceStart = Pid(
        name = "run_time_since_start",
        mode = 0x01,
        pid = 0x1F,
        periodMs = 5_000,
        parser = { bytes ->
            val a = byte(bytes, 0) ?: return@Pid null
            val b = byte(bytes, 1) ?: return@Pid null
            ((a * 256) + b).toDouble()  // seconds
        },
    )

    private fun fuelTrim(b: ByteArray): Double? =
        byte(b, 0)?.let { (it - 128) * 100.0 / 128.0 }

    val StftB1 = Pid(name = "stft_b1", mode = 0x01, pid = 0x06, periodMs = 2_000, parser = ::fuelTrim)
    val LtftB1 = Pid(name = "ltft_b1", mode = 0x01, pid = 0x07, periodMs = 5_000, parser = ::fuelTrim)
    val StftB2 = Pid(name = "stft_b2", mode = 0x01, pid = 0x08, periodMs = 2_000, parser = ::fuelTrim)
    val LtftB2 = Pid(name = "ltft_b2", mode = 0x01, pid = 0x09, periodMs = 5_000, parser = ::fuelTrim)

    /**
     * Default poll set. ATF temp (Honda Mode 22 0x2201) is intentionally OUT — that
     * is the WiCAN device's job (driveway publish over wifi). The phone bridge focuses
     * on the moving-vehicle path: live engine state + GPS + the standard Mode 01 PIDs
     * the Live view expects to see populated.
     */
    val DEFAULT = listOf(
        EngineRpm,
        VehicleSpeed,
        ThrottlePosition,
        CoolantTemp,
        ControlModuleVoltage,
        FuelLevel,
        EngineLoad,
        ManifoldPressure,
        MafAirFlow,
        Odometer,
        StftB1, LtftB1, StftB2, LtftB2,
        // MAF (0x10) added at ~1 Hz to fix the backend's
        // FUEL-DECREMENT-NULL: fuel-consumed integration needs a MAF
        // (or equivalent) airflow stream during phone-BLE drives. The
        // WiCAN AutoPID path publishes it over WiFi in the driveway,
        // but cellular drives (phone-only) had no airflow source. If
        // this PCM answers 0x10 with NO DATA the response is logged
        // and dropped harmlessly (engine-state no longer nudged per
        // OBD-1); validation needs a real drive. Std PIDs 0x0F
        // (intake_air_temp) + 0x1F (run_time_since_start) stay OUT —
        // the WiCAN AutoPID handles the Honda-extended 0x66/67/68
        // equivalents over WiFi MQTT; WiCanSubscriber fans those into
        // the BridgeStateBus.
    )
}
