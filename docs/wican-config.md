# WiCAN device configuration

After the pitstop CT is up, the WiCAN device needs two manual settings tweaks:

## 1. MQTT settings

Open the WiCAN web UI (typically `http://wican.local/` or its DHCP IP). Navigate to **Settings → MQTT**:

| Field | Value |
|---|---|
| Server | `<CT_IP>` (from `~/.pitstop-deploy-secrets.txt` after deploy) |
| Port | `1883` |
| Username | `pitstop` (or `MQTT_USER` from secrets file) |
| Password | from `MQTT_PASSWORD` in the secrets file |
| Client ID | anything; `wican-pilot19` is conventional |
| Topic prefix | `wican/pilot19/` (lowercase, slug-style; backend resolves slug → vehicle UUID) |

Save and reboot the device.

## 2. AutoPID profile

In **Settings → AutoPID**:

1. Click **Upload profile**.
2. Select `pid_profiles/honda-pilot-2019.json` from this repo (or your local copy).
3. Save and reboot.

The device will start polling the listed PIDs and publishing to `wican/pilot19/<metric>` topics. The pitstop backend's MQTT subscriber resolves the slug, parses the values via the same profile JSONB stored in the DB, and writes to `pid_readings`.

## Verification

- After ~10 s of driveway idle, check `pitstop-status` (or `curl /health/ingest`) — `lag_s` should be small.
- Open the pitstop **Live** view in a browser; you should see RPM, speed, voltage, temps animate.
- `mosquitto_sub -h <CT_IP> -u pitstop -P <pw> -t 'wican/+/+'` from a laptop on the LAN will dump live publishes for sanity.

## WiFi quirk on this network

If the WiCAN fails to associate (stuck "connecting", repeated retry), set **Settings → WiFi → Security** to **WPA2** (not "WPA2/WPA3" or "WPA3"). The ESP32's SAE handshake mis-negotiates with some consumer routers (this user's included) even when the AP advertises WPA2/WPA3 mixed mode. Plain WPA2 PSK skips SAE entirely and associates cleanly. Reference: WiCAN-fw `main/wifi_network.c::case WIFI_WPA3_PSK` maps to `WIFI_AUTH_WPA2_WPA3_PSK` (mixed-mode threshold).

## Mode 22 availability varies — check before relying on custom PIDs

Honda Mode 22 (Read Data By Identifier) PIDs — used for ATF temp, current gear, TPMS pressures, AWD torque split, etc. — are routed through the vehicle's on-board gateway. The gateway can block Mode 22 entirely depending on year, trim, and firmware. Some Pilots forward Mode 22 to the addressed ECU; others (verified: 2019 Pilot Elite as of 2026-05-12) return NO DATA for every Mode 22 query.

**Standard Mode 01 PIDs are always available** — they're routed directly to the PCM via broadcast and account for 60+ live metrics including RPM, speed, all temps (coolant, IAT, oil, baro, cat), throttle position, MAF, fuel rate, fuel trims, oxygen sensor data, distance counters, odometer.

Bisect procedure when adding a Mode 22 PID for a new trim:
1. Configure 4–8 custom PIDs with the same DID but different `Bn` expressions (`B5`, `B6`, `B7`, …) — the byte where the data lives varies with WiCAN's framing.
2. Drive 5 min, then check the published MQTT JSON.
3. **Trust the AutoPID-delivered values over the WiCAN UI's "Test" button** — the Test button uses the ELM327 emulation path which is broken in WiCAN-PRO v4.49 Beta-06 (returns NO DATA even for universal queries like `0100`).
4. Whichever byte returns a value that **varies plausibly with engine state** is the data byte. Constant bytes — even ones that decode to plausible temperatures (e.g. `0x62 = 98 → 136.4°F via `AA*9/5-40`) — are usually the mode-echo or DID-echo positions, NOT real data. A "stuck verified value" is a red flag.

## Honda V6 uses extended PIDs, not the SAE single-sensor variants

The Pilot V6 PCM doesn't support the simple-format Mode 01 PIDs `0F-IntakeAirTemp`, `10-MAFAirFlowRate`, or `05-EngineCoolantTemp` directly (they return NO DATA). Honda uses the bank-specific extended PIDs instead:

- `66-MAFSensorA` instead of `10-MAFAirFlowRate`
- `67-EngineCoolantTemp1` instead of `05-EngineCoolantTemp`
- `68-IntakeAirTempSens1` instead of `0F-IntakeAirTemp`

The backend's `wican_aliases.py` maps these to canonical names (`maf_air_flow`, `coolant_temp`, `intake_air_temp`). The `Sensor2`/`B` variants are usually sentinel "no sensor" responses on this engine — skip them.

Disable banks 3/4 oxy trims (`55-58 *Bank3/Bank4`) on the V6 — only banks 1/2 exist, so banks 3/4 always NO DATA and just slow the poll cycle.

PIDs that the 2019 Pilot Elite PCM **does not expose**: `0F`, `10`, `46-AmbientAirTemp`, `5C-EngineOilTemp`, `5A-RelAccelPedalPos`, `4D-TimeRunMILOn`, `61-DriversDemandPercentTorque`. Trying to add them as custom Mode 01 PIDs returns NO DATA — not a WiCAN bug, the PCM just doesn't support them.

## Troubleshooting

- **No publishes:** check WiCAN's MQTT status indicator on its homepage. If it says "disconnected," verify the broker IP is reachable from the WiCAN's network (it must be on home WiFi or the same subnet).
- **Publishes but nothing in pitstop:** confirm the `<vehicle_id>` slug in the topic prefix matches a row in `vehicles.slug`. Backend logs warn-then-drop messages for unknown slugs.
- **Wrong values:** the AutoPID profile's expression may be off for your trim. Honda's Mode 22 PIDs vary slightly between trims; refer to `docs/research/honda-pilot-pids.md` and Piloteers forum threads.
- **Custom Mode 22 PID always NO DATA:** Mode 22 may be gateway-blocked on your vehicle. Try the forum-canonical config first; if even that fails, see "Mode 22 availability varies" above.

## Off-network publishing (cellular)

The WiCAN device only publishes over its local Wi-Fi link. For off-network telemetry while driving, use the **pitstop Android app** (Phase B `android/`):

- Install the debug APK from `android/app/build/outputs/apk/debug/app-debug.apk`.
- In the app's Config screen, point the broker URL at the CT IP (over Tailscale subnets — make sure "Use Tailscale subnets" is enabled in the Tailscale Android app), enter the same MQTT credentials, and the vehicle slug.
- The phone bridges the WiCAN's BLE feed to MQTT under topic `bridge/<slug>/<metric>`.
- The backend ingest worker subscribes to both `wican/+/+` and `bridge/+/+`, so driveway and on-road data converge.
