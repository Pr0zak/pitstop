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

## Troubleshooting

- **No publishes:** check WiCAN's MQTT status indicator on its homepage. If it says "disconnected," verify the broker IP is reachable from the WiCAN's network (it must be on home WiFi or the same subnet).
- **Publishes but nothing in pitstop:** confirm the `<vehicle_id>` slug in the topic prefix matches a row in `vehicles.slug`. Backend logs warn-then-drop messages for unknown slugs.
- **Wrong values:** the AutoPID profile's expression may be off for your trim. Honda's Mode 22 PIDs vary slightly between trims; refer to `docs/research/honda-pilot-pids.md` and Piloteers forum threads.

## Off-network publishing (cellular)

The WiCAN device only publishes over its local Wi-Fi link. For off-network telemetry while driving, use the **pitstop Android app** (Phase B `android/`):

- Install the debug APK from `android/app/build/outputs/apk/debug/app-debug.apk`.
- In the app's Config screen, point the broker URL at the CT IP (over Tailscale subnets — make sure "Use Tailscale subnets" is enabled in the Tailscale Android app), enter the same MQTT credentials, and the vehicle slug.
- The phone bridges the WiCAN's BLE feed to MQTT under topic `bridge/<slug>/<metric>`.
- The backend ingest worker subscribes to both `wican/+/+` and `bridge/+/+`, so driveway and on-road data converge.
