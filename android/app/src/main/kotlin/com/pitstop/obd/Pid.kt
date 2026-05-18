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
        StftB1, LtftB1, StftB2, LtftB2,
        // Honda V6 PCM does NOT answer the simple-format Mode 01
        // PIDs 0x0F (intake_air_temp), 0x10 (maf_air_flow),
        // 0x1F (run_time_since_start) — they always come back
        // "NO DATA". The WiCAN dongle's AutoPID handles the
        // Honda-extended equivalents (0x66/67/68 etc.) and ships
        // them via WiFi MQTT; WiCanSubscriber fans them into the
        // BridgeStateBus. Polling the std-PID forms over BLE just
        // wastes round-robin slots that could go to fuel_level +
        // friends, and each NO DATA used to wrongly nudge the
        // engine-state counter (OBD-1).
    )
}
