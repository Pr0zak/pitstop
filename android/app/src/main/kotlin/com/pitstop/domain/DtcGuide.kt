package com.pitstop.domain

/**
 * How soon a trouble code wants attention. Labels are deliberately calm:
 * this is general information about a code, not a diagnosis of the car.
 */
enum class DtcSeverity(val label: String) {
    /** Risk of engine / catalyst damage or of being stranded. */
    STOP_NOW("Urgent"),

    /** Drivable, but book it in; may affect running, economy or emissions. */
    CHECK_SOON("Check soon"),

    /** Usually harmless to drive on; fix at the next convenient service. */
    MONITOR("Monitor"),
}

data class DtcGuideEntry(
    val code: String,
    /** Short plain-language title ("Catalyst efficiency"). */
    val title: String,
    val severity: DtcSeverity,
    /** One or two sentences answering "can I keep driving?". */
    val safeToDrive: String,
    val causes: List<String>,
    /** False for family fallbacks — the UI says "not in pitstop's table". */
    val known: Boolean = true,
)

/**
 * Bundled plain-language table for common OBD-II codes, plus an honest
 * family fallback for anything else. Pure Kotlin, no I/O — unit-tested.
 *
 * Content is general guidance drawn from the SAE J2012 definitions and the
 * usual causes mechanics list; every surface that shows it also shows
 * [DISCLAIMER].
 */
object DtcGuide {

    const val DISCLAIMER = "General guidance, not a diagnosis."

    private const val MISFIRE_SAFE =
        "Gentle, short driving is usually OK while the check-engine light is steady. " +
            "Avoid hard acceleration and towing. If the light is flashing, pull over when " +
            "safe — unburned fuel can overheat and ruin the catalytic converter."

    private val MISFIRE_CAUSES = listOf(
        "Worn spark plug",
        "Failing ignition coil",
        "Clogged or leaking fuel injector",
        "Vacuum leak or low compression",
    )

    private fun e(
        code: String,
        title: String,
        severity: DtcSeverity,
        safe: String,
        vararg causes: String,
    ) = code to DtcGuideEntry(code, title, severity, safe, causes.toList())

    private fun MutableMap<String, DtcGuideEntry>.add(p: Pair<String, DtcGuideEntry>) {
        put(p.first, p.second)
    }

    private val TABLE: Map<String, DtcGuideEntry> = buildMap {
        // ── Cam / crank timing ─────────────────────────────────────
        add(e("P0011", "Intake cam timing over-advanced (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable, but it can run rough and use more fuel. Check the oil level first.",
            "Low or dirty engine oil", "Sticking variable-valve-timing solenoid", "Stretched timing chain"))
        add(e("P0014", "Exhaust cam timing over-advanced (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable; check the oil level and get it looked at soon.",
            "Low or dirty engine oil", "Sticking exhaust cam-timing solenoid", "Stretched timing chain"))
        add(e("P0016", "Crank / cam position correlation (bank 1)", DtcSeverity.CHECK_SOON,
            "Drive gently and get it checked soon — a slipped or stretched timing chain can get worse quickly.",
            "Stretched timing chain or worn guides", "Faulty crank or cam position sensor", "Low oil pressure to the cam phaser"))
        // ── Fuel & air metering ────────────────────────────────────
        add(e("P0087", "Fuel rail pressure too low", DtcSeverity.CHECK_SOON,
            "May hesitate or stall under load. Avoid long trips until it's checked.",
            "Weak fuel pump", "Clogged fuel filter", "Faulty fuel pressure regulator or sensor"))
        add(e("P0101", "Mass airflow sensor range / performance", DtcSeverity.CHECK_SOON,
            "Usually drivable, though it may hesitate or run rough. Get it checked soon.",
            "Dirty MAF sensor", "Air leak between the MAF and the throttle", "Clogged air filter", "Wiring or connector fault"))
        add(e("P0102", "Mass airflow sensor signal low", DtcSeverity.CHECK_SOON,
            "Usually drivable, but power and economy may suffer.",
            "Unplugged or damaged MAF connector", "Dirty or failed MAF sensor", "Wiring fault"))
        add(e("P0103", "Mass airflow sensor signal high", DtcSeverity.CHECK_SOON,
            "Usually drivable, but it may run rich and rough.",
            "Failed MAF sensor", "Wiring shorted to voltage", "Air leak or damaged intake duct"))
        add(e("P0106", "Manifold pressure sensor range / performance", DtcSeverity.CHECK_SOON,
            "Usually drivable; may idle roughly or lose power.",
            "Cracked or disconnected vacuum hose to the sensor", "Failed MAP sensor", "Vacuum leak"))
        add(e("P0112", "Intake air temperature sensor signal low", DtcSeverity.MONITOR,
            "Yes. The engine uses a default value; economy may dip slightly.",
            "Shorted IAT sensor or wiring", "Failed sensor"))
        add(e("P0113", "Intake air temperature sensor signal high", DtcSeverity.MONITOR,
            "Yes. The engine uses a default value; economy may dip slightly.",
            "Unplugged or damaged IAT sensor connector", "Open circuit in the wiring", "Failed sensor"))
        add(e("P0117", "Coolant temperature sensor signal low", DtcSeverity.CHECK_SOON,
            "Usually drivable, but watch the temperature gauge — the cooling fan may not switch correctly.",
            "Failed coolant temperature sensor", "Shorted wiring"))
        add(e("P0118", "Coolant temperature sensor signal high", DtcSeverity.CHECK_SOON,
            "Usually drivable, but watch the temperature gauge; hard starting is common.",
            "Unplugged or corroded sensor connector", "Open circuit", "Failed sensor"))
        add(e("P0121", "Throttle position sensor range / performance", DtcSeverity.CHECK_SOON,
            "May hesitate or go into reduced-power mode. Get it checked soon.",
            "Worn throttle position sensor", "Dirty throttle body", "Wiring or connector fault"))
        add(e("P0125", "Engine slow to reach closed-loop temperature", DtcSeverity.MONITOR,
            "Yes. Economy and cabin heat may be poorer until it's fixed.",
            "Thermostat stuck open", "Faulty coolant temperature sensor", "Low coolant"))
        add(e("P0128", "Engine running below thermostat temperature", DtcSeverity.MONITOR,
            "Yes. Expect weaker cabin heat and a bit worse economy until the thermostat is replaced.",
            "Thermostat stuck open", "Faulty coolant temperature sensor", "Low coolant level"))
        // ── Oxygen sensors ─────────────────────────────────────────
        add(e("P0131", "Upstream O2 sensor low voltage (bank 1)", DtcSeverity.MONITOR,
            "Usually yes; economy and emissions may suffer.",
            "Failed O2 sensor", "Exhaust leak near the sensor", "Wiring fault"))
        add(e("P0133", "Upstream O2 sensor slow response (bank 1)", DtcSeverity.MONITOR,
            "Yes. The sensor is ageing; economy may drop slowly.",
            "Aged O2 sensor", "Exhaust leak", "Contamination from oil or coolant"))
        add(e("P0135", "Upstream O2 sensor heater circuit (bank 1)", DtcSeverity.MONITOR,
            "Yes. Emissions are a little higher just after a cold start.",
            "Failed O2 sensor heater", "Blown heater fuse", "Wiring or connector fault"))
        add(e("P0137", "Downstream O2 sensor low voltage (bank 1)", DtcSeverity.MONITOR,
            "Yes. It mainly affects catalyst monitoring.",
            "Failed rear O2 sensor", "Exhaust leak", "Wiring fault"))
        add(e("P0138", "Downstream O2 sensor high voltage (bank 1)", DtcSeverity.MONITOR,
            "Yes. It mainly affects catalyst monitoring.",
            "Failed rear O2 sensor", "Wiring shorted to voltage", "Engine running rich"))
        add(e("P0141", "Downstream O2 sensor heater circuit (bank 1)", DtcSeverity.MONITOR,
            "Yes. It only affects emissions monitoring.",
            "Failed O2 sensor heater", "Blown heater fuse", "Wiring or connector fault"))
        add(e("P0151", "Upstream O2 sensor low voltage (bank 2)", DtcSeverity.MONITOR,
            "Usually yes; economy and emissions may suffer.",
            "Failed O2 sensor", "Exhaust leak near the sensor", "Wiring fault"))
        add(e("P0155", "Upstream O2 sensor heater circuit (bank 2)", DtcSeverity.MONITOR,
            "Yes. Emissions are a little higher just after a cold start.",
            "Failed O2 sensor heater", "Blown heater fuse", "Wiring or connector fault"))
        add(e("P0161", "Downstream O2 sensor heater circuit (bank 2)", DtcSeverity.MONITOR,
            "Yes. It only affects emissions monitoring.",
            "Failed O2 sensor heater", "Blown heater fuse", "Wiring or connector fault"))
        // ── Fuel trim ──────────────────────────────────────────────
        add(e("P0171", "System too lean (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable short-term, but a long-term lean condition can cause misfires and damage. Get it checked soon.",
            "Vacuum leak (cracked hose, intake gasket, PCV)", "Dirty or failing MAF sensor", "Weak fuel pump or clogged filter", "Leaking exhaust before the O2 sensor"))
        add(e("P0172", "System too rich (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable short-term, but running rich can damage the catalytic converter.",
            "Leaking fuel injector", "High fuel pressure", "Dirty MAF sensor", "Stuck-open EVAP purge valve"))
        add(e("P0174", "System too lean (bank 2)", DtcSeverity.CHECK_SOON,
            "Usually drivable short-term; get it checked soon. With P0171 too, suspect something shared like a vacuum leak or the MAF.",
            "Vacuum leak", "Dirty or failing MAF sensor", "Low fuel pressure", "Intake gasket leak on bank 2"))
        add(e("P0175", "System too rich (bank 2)", DtcSeverity.CHECK_SOON,
            "Usually drivable short-term, but running rich can damage the catalytic converter.",
            "Leaking fuel injector", "High fuel pressure", "Dirty MAF sensor", "Stuck-open EVAP purge valve"))
        add(e("P0217", "Engine over-temperature", DtcSeverity.STOP_NOW,
            "No. Pull over when safe, switch off and let it cool — driving hot can warp the head or blow the head gasket.",
            "Low coolant or a leak", "Failed cooling fan", "Stuck thermostat or failed water pump"))
        add(e("P0218", "Transmission fluid over-temperature", DtcSeverity.STOP_NOW,
            "Pull over when safe and let it cool. Overheated fluid quickly damages the transmission.",
            "Towing or heavy load in hot weather", "Low or worn transmission fluid", "Blocked transmission cooler"))
        add(e("P0230", "Fuel pump primary circuit", DtcSeverity.CHECK_SOON,
            "It may stall or fail to start. Get it checked before a long trip.",
            "Failed fuel pump relay", "Blown fuse", "Wiring fault", "Failing fuel pump"))
        // ── Misfire ────────────────────────────────────────────────
        add(DtcGuideEntry("P0300", "Random / multiple misfire", DtcSeverity.STOP_NOW, MISFIRE_SAFE,
            listOf("Worn spark plugs", "Vacuum leak", "Low fuel pressure", "Failing ignition coils")).let { it.code to it })
        for (cyl in 1..12) {
            val code = "P03%02d".format(cyl)
            put(code, DtcGuideEntry(code, "Cylinder $cyl misfire", DtcSeverity.STOP_NOW, MISFIRE_SAFE, MISFIRE_CAUSES))
        }
        add(e("P0325", "Knock sensor circuit (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable; the engine may retard timing and lose some power. Use the recommended fuel grade.",
            "Failed knock sensor", "Wiring or connector fault"))
        add(e("P0335", "Crankshaft position sensor circuit", DtcSeverity.CHECK_SOON,
            "The engine may stall or refuse to restart without warning. Get it checked promptly and avoid long trips.",
            "Failed crankshaft position sensor", "Damaged wiring or connector", "Damaged reluctor ring"))
        add(e("P0340", "Camshaft position sensor circuit (bank 1)", DtcSeverity.CHECK_SOON,
            "It may start hard, run rough or stall. Get it checked promptly.",
            "Failed camshaft position sensor", "Wiring or connector fault", "Timing chain problem"))
        // ── Emissions ──────────────────────────────────────────────
        add(e("P0401", "EGR flow insufficient", DtcSeverity.MONITOR,
            "Yes. You may notice pinging under load; emissions are higher.",
            "Carbon-clogged EGR passages", "Stuck EGR valve", "Faulty EGR position or pressure sensor"))
        add(e("P0402", "EGR flow excessive", DtcSeverity.CHECK_SOON,
            "Usually drivable, but it may idle roughly or stall.",
            "EGR valve stuck open", "Faulty EGR control solenoid", "Faulty EGR sensor"))
        add(e("P0411", "Secondary air injection flow incorrect", DtcSeverity.MONITOR,
            "Yes. It only affects cold-start emissions.",
            "Failed air pump", "Stuck check valve", "Blown fuse or relay"))
        add(e("P0420", "Catalyst efficiency below threshold (bank 1)", DtcSeverity.CHECK_SOON,
            "Yes, it's drivable, but it will fail an emissions test. If other codes (misfire, rich or lean) are present, fix those first — they can ruin the converter.",
            "Ageing catalytic converter", "Faulty downstream O2 sensor", "Exhaust leak near the sensors", "Misfire or oil burning damaging the cat"))
        add(e("P0430", "Catalyst efficiency below threshold (bank 2)", DtcSeverity.CHECK_SOON,
            "Yes, it's drivable, but it will fail an emissions test. Fix any misfire, rich or lean codes first.",
            "Ageing catalytic converter", "Faulty downstream O2 sensor", "Exhaust leak near the sensors", "Misfire or oil burning damaging the cat"))
        add(e("P0440", "Evaporative emission system fault", DtcSeverity.MONITOR,
            "Yes. You may smell fuel; start with the gas cap.",
            "Loose or worn gas cap", "Cracked EVAP hose", "Faulty purge or vent valve"))
        add(e("P0441", "EVAP incorrect purge flow", DtcSeverity.MONITOR,
            "Yes. You may notice rough idle or hard starting after refuelling.",
            "Faulty purge valve", "Cracked or blocked EVAP hose", "Faulty vent valve"))
        add(e("P0442", "EVAP small leak detected", DtcSeverity.MONITOR,
            "Yes. Check the gas cap clicks shut; the light can take a few drives to clear.",
            "Loose or worn gas cap", "Cracked EVAP hose", "Leaking purge or vent valve"))
        add(e("P0446", "EVAP vent control circuit", DtcSeverity.MONITOR,
            "Yes. It may be slow to refuel (pump keeps clicking off).",
            "Faulty or blocked vent valve", "Clogged charcoal canister", "Wiring fault"))
        add(e("P0455", "EVAP large leak detected", DtcSeverity.MONITOR,
            "Yes. Check the gas cap first — a missing or loose cap is the most common cause.",
            "Gas cap missing or loose", "Disconnected or cracked EVAP hose", "Faulty purge or vent valve"))
        add(e("P0456", "EVAP very small leak detected", DtcSeverity.MONITOR,
            "Yes. Check the gas cap seal; small leaks are often a worn cap or hose.",
            "Worn gas cap seal", "Small crack in an EVAP hose", "Leaking purge or vent valve"))
        add(e("P0457", "EVAP leak — fuel cap loose or off", DtcSeverity.MONITOR,
            "Yes. Tighten or replace the gas cap; the light clears after a few drives.",
            "Gas cap loose, missing or damaged"))
        add(e("P0496", "EVAP high purge flow", DtcSeverity.CHECK_SOON,
            "Usually drivable, but it may stall or start hard right after refuelling.",
            "Purge valve stuck open", "Faulty purge valve wiring"))
        // ── Speed, idle, electrical ────────────────────────────────
        add(e("P0500", "Vehicle speed sensor", DtcSeverity.CHECK_SOON,
            "Drivable with care: the speedometer, cruise control, shifting or ABS may misbehave.",
            "Failed vehicle speed sensor", "Wiring or connector fault", "Faulty ABS wheel-speed signal"))
        add(e("P0505", "Idle air control system", DtcSeverity.CHECK_SOON,
            "Usually drivable, but it may idle roughly or stall at stops.",
            "Dirty throttle body or idle air control valve", "Vacuum leak", "Failed idle air control motor"))
        add(e("P0506", "Idle speed lower than expected", DtcSeverity.MONITOR,
            "Yes, though it may idle roughly.",
            "Dirty throttle body", "Clogged idle air passage", "Restricted air intake"))
        add(e("P0507", "Idle speed higher than expected", DtcSeverity.MONITOR,
            "Yes. Expect a high idle and slightly worse economy.",
            "Vacuum leak", "Dirty throttle body", "Faulty idle air control valve"))
        add(e("P0520", "Oil pressure sensor circuit", DtcSeverity.CHECK_SOON,
            "Check the oil level now. If it's correct and the engine sounds normal, it's usually the sensor — get it checked soon.",
            "Failed oil pressure sensor or switch", "Wiring fault", "Genuinely low oil pressure"))
        add(e("P0524", "Engine oil pressure too low", DtcSeverity.STOP_NOW,
            "No. Stop the engine as soon as it's safe and check the oil — low oil pressure can destroy an engine in minutes.",
            "Low oil level", "Failing oil pump", "Worn engine bearings", "Faulty sensor"))
        add(e("P0562", "System voltage low", DtcSeverity.CHECK_SOON,
            "Drivable, but the car may not restart. Get the battery and alternator tested.",
            "Weak battery", "Failing alternator", "Loose or corroded battery terminal"))
        add(e("P0563", "System voltage high", DtcSeverity.CHECK_SOON,
            "Get it checked soon — overcharging can damage the battery and electronics.",
            "Faulty voltage regulator / alternator", "Poor ground connection"))
        add(e("P0606", "Engine computer internal fault", DtcSeverity.CHECK_SOON,
            "It may run normally, but can stall or go into reduced-power mode. Get it checked.",
            "Failing engine control module", "Low or unstable supply voltage", "Software fault (may need an update)"))
        // ── Transmission ───────────────────────────────────────────
        add(e("P0700", "Transmission control system fault", DtcSeverity.CHECK_SOON,
            "It's a pointer: the transmission module has stored its own code. Drive gently and have the transmission codes read.",
            "Any transmission fault — read the transmission module's codes"))
        add(e("P0715", "Transmission input speed sensor", DtcSeverity.CHECK_SOON,
            "Shifting may be harsh or it may stay in one gear. Drive gently and get it checked.",
            "Failed input speed sensor", "Wiring fault", "Low transmission fluid"))
        add(e("P0730", "Incorrect gear ratio", DtcSeverity.CHECK_SOON,
            "Drive gently and get it checked soon — the transmission may be slipping.",
            "Low or worn transmission fluid", "Faulty shift solenoid", "Internal transmission wear"))
        add(e("P0740", "Torque converter clutch circuit", DtcSeverity.CHECK_SOON,
            "Usually drivable; economy drops and the transmission may run hot.",
            "Faulty torque converter clutch solenoid", "Wiring fault", "Low transmission fluid"))
        add(e("P0741", "Torque converter clutch stuck off", DtcSeverity.CHECK_SOON,
            "Usually drivable; economy drops and the transmission may run hot. Avoid towing.",
            "Worn torque converter clutch", "Faulty solenoid", "Low or worn transmission fluid"))
        add(e("P0750", "Shift solenoid A", DtcSeverity.CHECK_SOON,
            "It may shift harshly or stay in one gear. Drive gently and get it checked.",
            "Failed shift solenoid", "Wiring fault", "Low transmission fluid"))
        // ── P2xxx ──────────────────────────────────────────────────
        add(e("P2096", "Post-catalyst fuel trim too lean (bank 1)", DtcSeverity.MONITOR,
            "Usually yes; get it checked at the next service.",
            "Exhaust leak", "Faulty downstream O2 sensor", "Vacuum leak"))
        add(e("P2097", "Post-catalyst fuel trim too rich (bank 1)", DtcSeverity.MONITOR,
            "Usually yes; get it checked at the next service.",
            "Ageing catalytic converter", "Faulty downstream O2 sensor", "Leaking injector"))
        add(e("P2135", "Throttle / pedal position sensor correlation", DtcSeverity.CHECK_SOON,
            "It may go into reduced-power (limp) mode. Get it checked soon.",
            "Worn throttle body sensor", "Dirty throttle body", "Wiring or connector fault"))
        add(e("P2187", "System too lean at idle (bank 1)", DtcSeverity.CHECK_SOON,
            "Usually drivable; a lean idle often points to a vacuum leak. Get it checked soon.",
            "Vacuum leak or PCV hose", "Dirty MAF sensor", "Intake gasket leak"))
        add(e("P2270", "Downstream O2 sensor stuck lean (bank 1)", DtcSeverity.MONITOR,
            "Usually yes; get it checked at the next service.",
            "Exhaust leak near the sensor", "Failed rear O2 sensor", "Engine running lean"))
        // ── Network (U) ────────────────────────────────────────────
        add(e("U0100", "Lost communication with the engine computer", DtcSeverity.CHECK_SOON,
            "If the engine runs normally it's usually drivable, but it can stall or refuse to start. If it stalls, don't keep driving — get it checked.",
            "CAN bus wiring or connector fault", "Low battery voltage", "Failing engine computer", "A device on the OBD port disturbing the bus"))
        add(e("U0101", "Lost communication with the transmission computer", DtcSeverity.CHECK_SOON,
            "Shifting may be erratic or stuck in one gear. Drive gently and get it checked.",
            "CAN bus wiring or connector fault", "Low battery voltage", "Failing transmission computer"))
        add(e("U0121", "Lost communication with the ABS module", DtcSeverity.CHECK_SOON,
            "Normal braking works, but ABS and stability control may be off. Leave extra stopping distance.",
            "CAN bus wiring fault", "Blown ABS fuse", "Failing ABS module"))
        add(e("U0140", "Lost communication with the body control module", DtcSeverity.CHECK_SOON,
            "Usually drivable; lights, locks or other body functions may misbehave.",
            "CAN bus wiring fault", "Low battery voltage", "Failing body control module"))
        add(e("U0155", "Lost communication with the instrument cluster", DtcSeverity.CHECK_SOON,
            "Gauges and warning lights may not work — don't rely on the dash until it's fixed.",
            "CAN bus wiring fault", "Failing instrument cluster", "Low battery voltage"))
        // ── Chassis (C) ────────────────────────────────────────────
        for ((code, wheel) in listOf("C0035" to "left front", "C0040" to "right front", "C0045" to "left rear", "C0050" to "right rear")) {
            put(code, DtcGuideEntry(code, "Wheel speed sensor ($wheel)", DtcSeverity.CHECK_SOON,
                "Normal braking works, but ABS and traction control may be off. Leave extra stopping distance and get it checked soon.",
                listOf("Dirty or failed wheel speed sensor", "Damaged sensor wiring", "Damaged tone ring or wheel bearing")))
        }
    }

    /** Codes in the table (for tests and counting). */
    val size: Int get() = TABLE.size

    /** Normalised lookup; always returns something — a family fallback when unknown. */
    fun lookup(rawCode: String, description: String? = null): DtcGuideEntry {
        val code = rawCode.trim().uppercase()
        TABLE[code]?.let { return it }
        return fallback(code, description?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun fallback(code: String, description: String?): DtcGuideEntry {
        val family = code.firstOrNull()
        val second = code.getOrNull(1)
        val (title, severity, safe) = when {
            // Misfire range beyond the table is still a misfire.
            code.startsWith("P030") -> Triple("Misfire", DtcSeverity.STOP_NOW, MISFIRE_SAFE)
            family == 'P' && (second == '0' || second == '2' || second == '3') -> Triple(
                "Engine / transmission (generic) code",
                DtcSeverity.CHECK_SOON,
                "Usually drivable if the car feels normal. Get it read properly soon, and stop if the check-engine light flashes or the car runs badly.",
            )
            family == 'P' && second == '1' -> Triple(
                "Manufacturer-specific engine code",
                DtcSeverity.CHECK_SOON,
                "Its meaning depends on the make. If the car drives normally it's usually OK short-term — have it read with a make-specific tool.",
            )
            family == 'B' && code.startsWith("B00") -> Triple(
                "Airbag / restraint system code",
                DtcSeverity.CHECK_SOON,
                "The car drives normally, but airbags may not deploy in a crash. Get it checked soon.",
            )
            family == 'B' -> Triple(
                "Body system code",
                DtcSeverity.MONITOR,
                "Usually drivable — body codes cover things like lights, locks and climate. Fix when convenient.",
            )
            family == 'C' -> Triple(
                "Chassis code (brakes, ABS, steering or suspension)",
                DtcSeverity.CHECK_SOON,
                "Check the brakes and steering feel normal. ABS or stability control may be off — get it checked soon.",
            )
            family == 'U' -> Triple(
                "Network communication code",
                DtcSeverity.CHECK_SOON,
                "Modules aren't talking to each other. Usually drivable if nothing else is wrong, but get it checked soon.",
            )
            else -> Triple("Unrecognised code", DtcSeverity.MONITOR, "Have the code read with a scan tool to find out what it means.")
        }
        return DtcGuideEntry(
            code = code,
            title = description ?: title,
            severity = severity,
            safeToDrive = safe,
            causes = emptyList(),
            known = false,
        )
    }

    /** "This is a Body system code — not in pitstop's table." wording for unknowns. */
    fun familyLabel(code: String): String {
        val c = code.trim().uppercase()
        return when (c.firstOrNull()) {
            'P' -> if (c.getOrNull(1) == '1') "manufacturer-specific powertrain" else "generic powertrain"
            'B' -> "body"
            'C' -> "chassis"
            'U' -> "network"
            else -> "unknown"
        }
    }
}
