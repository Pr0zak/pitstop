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

    /**
     * Default poll set. ATF temp (Honda Mode 22 0x2201) is intentionally OUT — that
     * is the WiCAN device's job (driveway publish over wifi). The phone bridge focuses
     * on the moving-vehicle path: live engine state + GPS.
     */
    val DEFAULT = listOf(
        EngineRpm,
        VehicleSpeed,
        ThrottlePosition,
        CoolantTemp,
        ControlModuleVoltage,
        IntakeAirTemp,
        FuelLevel,
    )
}
