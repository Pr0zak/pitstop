# Decision log

ADR-style. Append-only. Each decision has Context → Decision → Consequence.

---

## ADR-001 — TimescaleDB for everything

**Context.** Need a time-series store + relational tables (vehicles, profiles, fillups). Existing infra has a Prometheus CT but no Influx or Grafana actively running.

**Decision.** TimescaleDB 2.17 (Postgres 16 + hypertable extension). One DB for raw readings, trips, fillups, expenses, settings.

**Consequence.** No second TSDB to run. Same DB powers analytics, time-series rollups, and relational data. Postgres tooling (psql, pg_dump, alembic) just works.

---

## ADR-002 — No Tailscale on the pitstop CT

**Context.** Phone bridge must reach the broker from cellular. Easy answer: put Tailscale on the CT.

**Decision.** Skip it. Existing **CT 444 (`tailscale-subnet-router`)** advertises the home subnet to the tailnet. Phone (with TS enabled) reaches the pitstop CT's LAN IP from anywhere.

**Consequence.** One fewer daemon to maintain. No authkey to mint. No LAN-vs-TS-IP confusion. Phone user must enable "Use Tailscale subnets" in the TS Android app — one-time setting.

---

## ADR-003 — `fuelio_guid` is the sync key, not VIN

**Context.** Originally planned VIN as primary identifier for vehicles + fillups.

**Decision.** Real Fuelio export shows VIN is **empty** for all 3 vehicles. Use `id UUID` as PK with `fuelio_guid TEXT UNIQUE` for sync; VIN stored optionally.

**Consequence.** Importer is idempotent regardless of VIN. WiCAN-read VIN (if/when it appears on the bus) augments rather than identifies.

---

## ADR-004 — Mosquitto in the same Compose stack

**Context.** Could run a separate broker CT.

**Decision.** Co-locate. Single CT, single IP, single secret store.

**Consequence.** One-CT deploy script. Tradeoff: if pitstop crashes, broker also goes down — no buffering at the broker layer. Acceptable; WiCAN buffers on-device.

---

## ADR-005 — PID profile JSON 1:1 with WiCAN AutoPID format

**Context.** Backend needs a way to know which PIDs the device polls and how to parse them.

**Decision.** Adopt WiCAN's AutoPID profile JSON schema verbatim. Same file uploaded to the device is seeded into `pid_profiles` (JSONB column).

**Consequence.** Community-shared Honda profiles drop in. No translation layer. If WiCAN extends the schema, we accept the new fields without code change as long as the existing ones stay stable.

---

## ADR-006 — HA plumbing built, disabled by default

**Context.** User runs Home Assistant; might want sensors auto-published. But not at launch.

**Decision.** Build the full HA mirror worker + Settings UI (URL, token, prefix, per-PID toggle, test button), but the worker is gated on `settings.ha_enabled`. Default off.

**Consequence.** Zero HA traffic until flipped. Code path is exercised at build time, so flipping the toggle is cheap. ~30 LOC of additional logic.

---

## ADR-007 — Token auth like myvitals

**Context.** Need auth on the API. Could go full OAuth or per-user.

**Decision.** Two shared tokens in `.env`: `INGEST_TOKEN` (writes) and `QUERY_TOKEN` (reads). Same as myvitals.

**Consequence.** Trivial to deploy, trivial to rotate. Not multi-user. If we ever add multi-user, this becomes service-account credentials and we layer per-user auth on top.

---

## ADR-008 — Profile JSONs seed DB; DB is source of truth after seed

**Context.** Should the file or the DB be authoritative?

**Decision.** Files in `pid_profiles/*.json` are loaded into the DB on first start. After that, the DB row is authoritative — UI edits write to the DB. Re-running seed is a no-op (upsert by name; only inserts if missing).

**Consequence.** UI can edit profiles without git pushes. Repo files are the safety net (delete the row, restart, get the original back).

---

## ADR-009 — Multi-vehicle from day one

**Context.** Originally framed as "Honda Pilot data."

**Decision.** Real Fuelio data has 3 vehicles. Schema, UI, and APIs all handle N from the start. Vehicle picker in the header.

**Consequence.** No painful retrofit. Slightly more JSX/SQL work up front, ~zero ongoing cost.

---

## ADR-010 — Phone bridge: native Kotlin

**Context.** Cross-platform Flutter would let an iOS port happen later.

**Decision.** Native Kotlin foreground service. iOS port deferred until/unless requested.

**Consequence.** BLE quirks and battery management work as expected. Existing zonik-app is also native, so the build chain is familiar.

---

## ADR-011 — Mosquitto LAN-only at launch

**Context.** Could expose TLS-MQTT (8883) for off-network publish.

**Decision.** Bind to LAN IP only on 1883. Phone bridge from cellular reaches it via the subnet router; that path is encrypted by Tailscale (WireGuard) end-to-end.

**Consequence.** No cert management, no public exposure. If a non-TS publisher ever needs to push (rare), revisit then.

---

## ADR-012 — Use claude.ai/design for UI mockups

**Context.** UI design is the highest-effort phase.

**Decision.** Use [claude.ai/design](https://claude.ai/design) for visual mockup help, layout planning, and component variants before implementing in Vue.

**Consequence.** Faster iteration on layouts. Not a substitute for actually wiring up the components — just shortens the "what should this look like" loop.

---

## ADR-013 — Web/phone parity; server is source of truth

**Context.** Two clients ship for pitstop: the Vue web dashboard (laptop / desktop UX, browsing + analytics) and the Android app (driveway + pump + on-the-road UX). Without a rule, they'll drift: a fuel-quick-add on the phone with no matching read on the web, or a settings field that only one client respects, etc. Worse, the phone is tempted to keep authoritative state of its own ("just in case the broker's down") which guarantees future merge conflicts when the phone reconnects.

**Decision.**

1. **Feature parity** for shared workflows. Anything that produces or queries persisted data must be reachable from both clients. Acceptable asymmetries: write-only flows that are mobile-native by design (fuel quick-add with auto-GPS, photo capture) need only a *read* counterpart on web; deeply technical editors (PID profile JSON, alembic-style admin) can stay web-only. Where there's ambiguity, ship parity.
2. **The Android app is collect+ship+cache, not own.** It may cache server records locally for fast reads (last fillups, vehicle config, recent trips) and may buffer writes when offline. It **never persists authoritative state**. When a write is buffered offline, it lands on the server at next reconnect and the cache is reconciled from the server's response.
3. **Server is the single source of truth.** Every entity (vehicles, trips, readings, fillups, expenses, settings, logs, profiles) lives in TimescaleDB / Postgres. Both clients are read-through against the same REST API; live data is read-through against the same WebSocket.
4. **One contract, two consumers.** API endpoint and payload shape are designed up front. The web and Android implementations consume the same wire schema. New features add a backend endpoint first.

**Consequence.**

- More planning before adding mobile-only or web-only features — but no merge surprises.
- Phone offline behaviour is deliberate: outbox pattern for writes, cache-as-fallback for reads.
- Some duplication of UI work across trees, mitigated by sharing the backend contract.
- When the user logs a fillup at a pump, walks home, opens the laptop, the row is already there. When they edit a vehicle on the laptop, the phone sees it on the next refresh.

This rule was set by the user on 2026-05-08 after Phase B; backfilling parity for views the phone doesn't yet have (trips list, analytics, fuel history, maintenance reminders) is on the Phase D / Android-2 roadmap.

---

## ADR-014 — Tiered retention via TimescaleDB continuous aggregates

**Context.** `pid_readings` is a hypertable. Live OBD + IMU + GPS streams land at 1-5 Hz per metric per vehicle. After a few weeks of regular driving the table is the dominant disk consumer (122 MB total DB size at ~98 MB readings after the user's first fortnight). The user added a manual "Purge older than N days" button (Task #54) and a backend cron (Task #67) that drops chunks past a configured age. That works for *bounded* storage but throws away every long-term trend. We want both: cheap recent data at 1 s resolution AND coarse older data for year-over-year analytics.

**Decision.** Three-tier retention via Timescale continuous aggregates.

```
                  retention      cadence   approx weight
─────────────────  ───────────   ───────   ─────────────
 pid_readings       30 days       1 s        full firehose, ~30 GB/yr
 pid_readings_1m    90 days       1 min      ~120 MB/yr
 pid_readings_5m    1 year        5 min      ~24 MB/yr
 pid_readings_1h    indefinite    1 hour     ~2 MB/yr (forever)
```

Each aggregate is a Timescale CONTINUOUS AGGREGATE with refresh + retention policies. Aggregates carry `avg / min / max / n` so the UI can keep showing both "what was the average RPM at 09:00 last Tuesday" and "what was the peak RPM during that hour."

The analytics layer adds a thin `pid_readings_view` that picks the right tier per query window — coarsest sufficient grain wins. Frontend `aggregateReadings()` swaps to the new view; existing `bucket` query parameter maps cleanly onto the tiered tables.

**Consequence.**
- Long-term storage stops growing without bound. Year-over-year analytics still work.
- Charts past 30 d become min/avg/max bands instead of raw lines — defensible tradeoff for "MPG over the past year."
- Acute events (a single 7000 RPM spike at 14:23 last April) are lost past 30 d in sub-hour resolution. The 1-hour tier preserves max_v but not when within the hour it occurred.
- Continuous aggregates re-materialize incrementally; refresh policy triggers in the background.
- Migration is a one-shot create + backfill. Roll-forward is non-destructive — raw `pid_readings` retention stays at 30 d so a downgrade reverts cleanly.

**Implementation order (separate task when needed):**
1. Three CONTINUOUS AGGREGATEs + refresh policies (alembic migration).
2. Retention policies dropping chunks past per-tier limit.
3. Tier-picker helper in `api/readings.py`: given (from, to, bucket), return SQL for the coarsest tier whose grain ≤ bucket.
4. Surface a "tier" stat next to "rows" + "size" on Settings → Storage.

This ADR lays the policy. As of v0.1.64 the manual purge + auto-purge cron (Tasks #54 + #67) are sufficient for a single-vehicle-active fleet — implementation lands when the storage curve forces the issue.

---

## ADR-015 — Phone manual-sync mode

**Context.** Phone bridge streams every OBD frame to MQTT in real-time during drives so the web dashboard + Home Assistant see live numbers. Two ongoing pain points:

1. Cellular data + battery cost. A typical drive ships hundreds of MQTT publishes per minute over cellular plus the BLE↔MQTT bridging keeps the radio + CPU active continuously.
2. The phone also seals + uploads the whole drive as a single HTTP batch at engine-off. So the same data is shipped twice (live stream + batch), and the batch alone is enough for trip-detail reconstruction.

**Decision.** New Settings → Connectivity → "Manual-sync mode" toggle (default OFF, preserves existing behavior). When ON:

- Phone bridge suppresses all outgoing MQTT publishes — metric stream, `bridge/<slug>/location`, `bridge/<slug>/engine_state`.
- DriveSealer's post-seal auto-kick no-ops (no automatic HTTP upload after engine-off).
- Periodic DriveUploadWorker no-ops (no background drain).
- Local capture is unchanged: DriveBuffer + Live screen tiles + sealed-drive payload all flow normally.
- "Sync now" button in History always works (it's the explicit manual path).
- Bridge-state pill shows "Local-only" when the toggle is on and the bridge is otherwise connected.

A persistent "Drives waiting to sync" notification fires when the local queue reaches 5 unacked drives. Ongoing/sticky, with a "Sync now" action button. Auto-cancels when queue drops below threshold.

**Consequence.**
- Manual-sync users save ~MB-per-drive on cellular + meaningful battery.
- Their drives don't show up on the live web dashboard mid-drive — that's the conscious tradeoff.
- WiCAN direct-publish from the driveway is unaffected (it's its own MQTT client over Wi-Fi).
- The 5-drive threshold is hardcoded in `SyncReminderManager` for now; bump if forgetting-to-sync becomes a recurring problem.

Shipped in v0.1.120.

---

## ADR-016 — Drive payload stored to disk, not the SQLite row

**Context.** `PendingDrive` originally inlined the full sealed-drive JSON into `payloadJson TEXT NOT NULL`. Worked for small drives. At ~40 k frames / 5.7 MB it broke — Android's default SQLite CursorWindow caps row reads at ~2 MB, so `dao.oldestUnacked(): PendingDrive?` threw `IllegalStateException: Row too big to fit into CursorWindow` and the entire upload pass aborted. User had two genuinely unsynced drives sit stuck for hours because the oversized head row blocked smaller queued drives behind it.

**Decision.** Payload moves to `${context.filesDir}/pending-drives/${uuid}.json`. The SQLite row keeps only metadata + `payloadFilePath: String?`. The upload pass queries a `PendingDriveMeta` projection that omits the legacy `payloadJson` column, then reads the file off disk for the HTTP body.

Migration v1→v2 adds the `payloadFilePath` column. Legacy rows (path null, legacy inline JSON unreadable) get dropped on next sync — their data is already on the server via the streaming + deriver paths, so dropping them is safe.

**Consequence.**
- No more CursorWindow ceiling. Multi-hour drives upload cleanly.
- One additional file write per drive seal — trivial compared to the MQTT publish cadence we already do.
- Cleanup hook: file is deleted on successful upload ack.
- `payloadJson` column stays NOT NULL (SQLite ALTER can't relax that without rebuilding the table); new rows write `""`.

Shipped in v0.1.111.

---

## ADR-017 — BLE link state ≠ engine state

**Context.** The phone bridge tracks two related but distinct facts:
1. **BLE phase** — Idle / Scanning / Connecting / Connected / Disconnected / Error. Whether we can talk to the WiCAN device at all.
2. **Engine state** — On / Off / Unknown. Whether the engine is running, per OBD responses + WiCAN LWT.

These were conflated. The disconnect path cleared `engineState = Unknown` on every BLE drop, and the OBD-quiet watchdog didn't gate on BLE phase — so a 2-minute BLE flake mid-drive would: (a) clear engine state to Unknown, (b) prevent the watchdog from firing (it skipped when `state != On`), then (c) when BLE reconnected, the first OBD frame triggered `onEngineOnSignal()` whose guard said `state != On` → emit a fresh "engine on" event → DriveRecorder sealed the in-progress buffer as incomplete and opened a new one. One real drive turned into two stitched-together trips.

**Decision.**

1. **BLE disconnect does NOT touch engineState.** Phase becomes `Disconnected`; engine state is left to OBD-side signals (real frames + WiCAN STOPPED/LWT) to decide.
2. **OBD-quiet watchdog requires `phase == Connected`.** A frame-age over the 60 s threshold during the reconnect loop means we can't talk to the WiCAN, NOT that the engine went off.
3. **BLE-lost watchdog (15 min) seals stalled drives.** If the link has been NOT-Connected for 15 minutes while engineState is still On, declare engine-off with `kind="ble_lost"`. Covers the case where the car parks somewhere the WiCAN's BLE never comes back.

**Consequence.**
- Short BLE flakes (< 15 min) no longer split drives.
- Long BLE losses still seal eventually (15 min, vs the previous "indefinitely until next engine_on").
- One real-world hit retroactively merged via manual SQL (2026-05-12 trips 2748f573 + c996d9d3 → single 46-min row).
- Trips marked `incomplete=true` from this bug class should be vanishingly rare going forward.

Shipped in v0.1.120 (split fix) + v0.1.121 (BLE-lost watchdog).

---

## ADR-018 — WiCAN PID 0x68 firmware bug: poll IAT as a custom Mode 01 PID

> **PARTIALLY SUPERSEDED 2026-08-04 by ADR-022.** Point 3 below — "Mode 22 documented as
> gateway-blocked on 2019 Pilot Elite" — is **wrong**. Mode 22 is reachable. The earlier
> probes used the Honda-transaxle DID (`2201`) against powertrain headers, but this Elite
> has the **ZF 9HP**, whose DIDs live in the `30xx` block on module **`0x1E`**. That module
> returns `7F 22 31` (*requestOutOfRange*) for an unknown DID — proof the service is
> supported and only the DID was absent, not that a gateway is filtering. ATF temperature
> (`22 3083`, `payload[17] − 40`) and gear position (`22 3086`, `payload[23]`) are both
> verified live; gear matched a blind D→R→N→D shift sequence 4/4.
>
> The 136.4 °F false positive described below is now fully explained rather than merely
> distrusted: `payload[0]` is the `0x62` positive-response echo, and `0x62 = 98 →
> 98 × 9/5 − 40 = 136.4`. It is constant on every successful Mode 22 response.
>
> Points 1 and 2 (IAT via custom PID `0168`, and keeping `68-IntakeAirTempSens1`
> unaliased) **remain correct and necessary** — the firmware's `0x68` decoder still has
> `bit_start = 39`, reading the sensor-support bitmap instead of the temperature.
> See `docs/research/honda-pilot-pids.md` for the measured detail.


**Context.** Honda V6 PCMs (incl. 2019 Pilot) expose intake air temp only via SAE J1979 PID `0x68` (dual-sensor variant), not the simple-format `0x0F`. Trying to use `0x0F` as a custom PID returns NO DATA from this PCM. WiCAN-PRO firmware v4.49 Beta-06 has a confirmed decoder bug for std PID `0x68` on its MQTT publish path: it emits byte 0 of the response (the supported-sensors bitmap = `0x01`) instead of byte 1 (the actual temp). Applied to the `A-40` formula that yields a constant `-39 °C` regardless of real intake air temp. The UI Test button on the same device reads byte 1 correctly (~69 °C plausible), so the bug is in the publish path, not the bus query.

Bisect confirmed via custom Mode 01 PID `0168` with byte probes B0..B8 over MQTT:

```
B0 = 0x10  (ISO-TP First Frame indicator)
B1 = 0x09  (length)
B2 = 0x41  (Mode 01 positive response echo, 0x01 + 0x40)
B3 = 0x68  (PID echo)
B4 = 0x01  (sensors-supported bitmap)
B5 = sensor 1 temp byte (108 raw observed = 68 °C)
B6 = sensor 2 (absent on V6, 0)
B7-B8 = padding / next-frame leakage
```

A second long-running rabbit-hole: the previous "verified" ATF temp config from memory (`223083` to TCM `7E1`, expression `(B6*9/5)-40`) turned out to be a false positive — `B6 = 0x62 = 98 → 136.4 °F` was the Mode 22 positive-response echo byte, present at the same position in EVERY successful Mode 22 response regardless of actual data. Bisect confirmed all bytes of that response are static; the TCM doesn't return temperature there.

**Decision.**

1. **IAT comes from a custom Mode 01 PID, not a std PID.** Profile `honda-pilot-2019.json` declares `intake_air_temp` as a custom PID with `pid: "0168"`, `init: "ATSH7DF\rATCRA"`, `expression: "B5-40"`, `unit: "celsius"`. Naming it `intake_air_temp` directly (canonical snake_case) bypasses the alias map — values land in `pid_readings` as canonical.
2. **`68-IntakeAirTempSens1 → intake_air_temp` alias removed** from `backend/src/pitstop/workers/wican_aliases.py`. Defense against accidental re-enable (e.g. WiCAN's Standard PIDs scan auto-discovers `68/1` again on a factory-reset device) — without the alias, even if `68/1` ends up polled, its broken `-39` value lands under the hex-prefixed name and never poisons the canonical IAT.
3. **Mode 22 documented as gateway-blocked on 2019 Pilot Elite.** ATF temp / current gear / TPMS / i-VTM4 AWD torque split — all return NO DATA via any DID we tried (`222201`, `223083`, `22F186`, `22F190`, `22D101..D107`, on every reachable header `7E0/7E1/7E2/7E5/7E6/7DF`). Honda's on-board gateway only forwards Mode 01 broadcast queries on this trim. Removed UI surfaces (LiveView/AnalyticsView/TripDetailView, CarTiles/LiveScreen/TripDetailScreen on Android) that referenced these unreachable metrics so dashboards stop showing perpetually-empty tiles.

**Consequence.**

- IAT flows correctly as `intake_air_temp` with non-constant values that track engine heat-soak (verified 68 °C warm idle, will rise/fall with airflow during driving).
- One less alias-map entry on the backend; alias map docs explain the firmware bug + workaround so a future Claude doesn't re-add the broken mapping.
- Loss of access to ATF / gear / TPMS / AWD-torque on this specific car is permanent until either: Honda releases a firmware update that opens Mode 22, the user buys an aftermarket scanner with manufacturer access, or a different CAN bus tap is built that bypasses the gateway. None of these are in scope.
- The "verified working" memory entry was corrected; future sessions won't repeat the false-positive bisect on `223083`.
- Forum reports of `222201` working on some Pilot trims are not generalizable — the public `honda-pilot-2019.json` profile keeps the canonical config in `stub_pids` as a starting point for other owners while making explicit it didn't work on the test vehicle.

Shipped in v0.1.123.

---

## ADR-019 — Capture path: hybrid (WiCAN-MQTT + phone-BLE), backend dedupes

**Context.** Three candidate architectures for getting OBD frames off the WiCAN-Pro and into the backend:

- **Path A — Phone BLE bridge.** WiCAN in ELM327-style mode, phone polls OBD over BLE, ships via MQTT + caches everything to `PendingDrive` payload for offline-safe resilience.
- **Path B — WiCAN cellular tunnel.** WiCAN runs `protocol: auto_pid` + WiFi station to the car's WiFi hotspot → WireGuard tunnel to a home VPN server → mosquitto. No phone needed.
- **Path C — Hybrid.** Both paths active simultaneously. Backend dedupes on the existing `pid_readings` PK `(vehicle_id, metric, time)`.

A prior reading of the situation (TODO.md 2026-05-24 morning) concluded Path C was impossible because BLE and WiFi-station mode were mutually exclusive on WiCAN-Pro, citing:
- meatpiHQ/wican-fw Discussion #615 (user wired a physical toggle switch to flip modes per drive)
- meatpi docs: "When the BLE is connected, the device configuration access point will be disabled."
- Crowd Supply marketing mentioning *auto-switching* (sequential), not concurrent operation.

Based on that, we restructured TODO.md declaring Path A primary, Path B backup, and cancelled VPN-3 (phase-out of BLE).

**Then we empirically tested Beta-06 firmware (`git_version: v4.49p_beta-06`) in the driveway:**

1. WiCAN booted with `wifi_mode: Station`, `sta_status: Connected`, `ble_status: enable`.
2. 10-minute mosquitto capture showed WiCAN publishing `wican/pilot19/pid` at ~1 Hz over WiFi.
3. *Simultaneously*, the phone's pitstop app connected via BLE — logs at 15:23:45 captured the full handshake: `ble connect → ble services discovered → ble service matched → ble connected → ble link ready; entering poll loop → engine on → DriveRecorder: drive opened`.
4. WiCAN's MQTT publishing did **not** stop while BLE was connected. Both radios concurrent.

Discussion #615 was on older firmware. Beta-06 implements actual radio coexistence (likely via ESP-IDF's BLE/WiFi time-slicing). The exclusivity belief that drove the TODO restructure is wrong **for our installed firmware**.

**Decision.**

1. **Hybrid is the architecture.** Run both paths simultaneously on Beta-06 (or newer).
2. **Backend dedupes naturally** via `pid_readings`'s composite PK `(vehicle_id, metric, time)`. Same OBD frame arriving from both sources within the same second triggers an `INSERT … ON CONFLICT DO NOTHING`; whichever arrives first wins, the other is silently swallowed. No new code needed — this is already the schema's behavior.
3. **Path A (phone bridge) keeps its role as the offline-safe primary.** Captures everything to `PendingDrive` regardless of connectivity (ADR-016), uploads at trip end via `POST /drives`. Adds the GPS track (WiCAN has no GPS chip). Pre-existing pain (BLE GATT flap, BLE-2) is genuine but bounded.
4. **Path B (WiCAN tunnel) covers Path A's blind spots.** Passenger drives (no phone), dead phone, app crashed, BLE flap mid-drive. Lossier (WiCAN does fire-and-forget QoS-0 with no local buffer — cellular drops = OBD gaps), but better than nothing for those cases.
5. **Coexistence is firmware-version-gated.** Pin firmware to `v4.49p_beta-06` or newer. Pre-Beta-06 builds may force one-at-a-time; the documented behavior on older firmware (Discussion #615) was real, not marketing.

**Consequence.**

- Both data sources can run live with no application-level dedup logic — the schema does it.
- Hybrid resilience: at least one path is publishing in every plausible failure mode short of "WiCAN itself dead."
- TODO.md needs another restructure (the morning's BLE-only re-pivot was wrong).
- VPN-3 (BLE-OBD phase-out) stays cancelled — BLE is part of the architecture permanently, not a transitional fallback.
- VPN-VERIFY-1 (real cellular drive with phone-BT-off) is still valid as a Path B isolation test, just no longer the architectural make-or-break.
- WiCAN firmware upgrades must be evaluated: a regression that removes BLE+WiFi coexistence breaks the hybrid path. Worth a smoke test on every firmware bump.
- The `/health/ingest` endpoint advances `last_message_at` from retained-message redelivery to a freshly-reconnecting backend subscriber, even when no new data is actually flowing. This masked the WiCAN's mid-test silence and looked like ingestion health when there was none. Worth filing as a separate bug; the right signal is "last NEW row inserted into pid_readings," not "last MQTT receipt."
- The `Discussion #615 → Path A primary` decision documented earlier today is overturned by this ADR. The earlier TODO restructure was correct procedure given the information at the time; the empirical test is what produced the better answer.

Test ran 2026-05-24 driveway. ADR-019 written same day, no code change required.


---

## ADR-020 — Fuel consumed comes from the ECU's own fuel rate (PID 0x9D), not MAF integration

**Context.** `trips.fuel_used_l` was 0 or NULL on every trip after 2026-07-25, so the fuel-level estimator ran entirely on a flat EPA decrement (22 mpg) and could not tell a hard 10 miles from an easy 10.

Root cause: `maf_air_flow` has **never** come from the phone. All 22,593 historical rows were published by the WiCAN, which only reaches MQTT over home WiFi — i.e. the driveway, not drives. The phone polled PID `0x10` for MAF, but live probing showed **this ECU does not advertise `0x10` at all**; the real MAF source is `0x66`. So on every cellular drive there was no airflow stream, nothing to integrate, and the fuel-level-delta fallback couldn't cover: a mid-trip fillup makes the delta negative, and post-fill slosh trips the 0.40 L/km sanity cap.

Probing also found PID `0x9D` (engine fuel rate) *is* supported and returns live moving data — the firmware's decoder was zeroing it (see `docs/research/honda-pilot-pids.md`).

**Decision.**

1. **Prefer `engine_fuel_rate` (PID 0x9D) over any airflow source.** It is the ECU's own fuel calculation, so power enrichment and deceleration fuel cut-off are already folded in. Integrating MAF assumes stoichiometric 14.7:1 forever — under-reporting at WOT and over-reporting on every lift-off.
2. **Sources are integrated separately, first credible result wins — never summed.** Summing would double-count on a PCM that answers both a fuel rate and a MAF.
3. **Species-typed units.** Sources declare `FlowSpecies.FUEL` or `.AIR`; the air:fuel ratio and gasoline density are applied in exactly one pure function and appear in no SQL. A fuel flow converts by density alone; an air flow divides by stoich first. Confusing the two is a 14.7x error, so it is made structurally impossible rather than documented.
4. **A source must cover ≥50% of the trip's OBD-active seconds.** These integrals silently under-count (gaps >60 s are dropped), so a sliver of coverage yields a small positive number that passes a magnitude-only gate. Real case from the database: 120 s of driveway WiCAN coverage on a 1397 s / 12.65 km trip integrated to 0.056 L ≈ **530 MPG** — and because it cleared the floor it also suppressed the tank-delta fallback that would have produced a sane figure. The 0.40 L/km cap is an upper bound and cannot catch it.

   The denominator is **activity, not wall clock**. A trip's `ended_at` is the engine-off event, which the phone's BLE-lost watchdog stamps ~3 minutes after the last OBD frame (60 s for the OBD-quiet watchdog), and a mid-drive BLE flap silences every phone metric at once. Dividing by `ended_at − started_at` would charge that silence to the fuel source and reject a stream that covered every second the car was reporting — turning a correct figure into a blank, which is the very bug this ADR exists to fix. `obd_active_seconds` sums the same `dt < 60` segments over *all* metrics, so numerator and denominator share one convention and the gate stays aimed at its actual target: one source covering a fraction of the period the others covered.
5. **The WiCAN's own `9D-EngineFuelRate` name stays unaliased**, quarantined under its hex name exactly like `68-IntakeAirTempSens1`. That name is the *broken* decoder (11,586 rows, min = max = 0). Aliasing it would let a single `wican-config` re-enable interleave 1 Hz of zeros with the working custom-PID stream under the same canonical name, roughly halving the integral — still clearing the credibility floor, so it would win silently and inflate MPG about 2x.

**Consequence.**

- Fuel per trip reflects how the car was actually driven, not a constant.
- The estimator degrades gracefully: fuel rate → MAF → tank-delta → EPA.
- The 50% coverage gate means a driveway-only capture now yields *no* fuel figure rather than a wrong one — deliberately preferring a gap to a plausible lie.

---

## ADR-021 — Per-vehicle odometer offset, user-configurable

**Context.** The PCM's distance counter and the instrument cluster are separate modules and do not agree — on this Pilot the OBD odometer reads ~51 km (32 mi) above the dash. Fillup odometers are recorded from the **dash**; the app prefills them from the **PID**. Mixing the two injects a one-time 32-mile jump into the odo chain, and recomputed MPG is `Δodo ÷ volume`, so the tank straddling the switch is silently corrupted.

Separately, the web Live view rendered the raw `odometer` metric with no conversion and no unit label. The metric is kilometres, so an imperial user read `126959` as miles when it meant 78,889 mi.

**Decision.**

1. New nullable `vehicles.odometer_offset_km`, editable in the UI. **NULL means "not calibrated" and is distinct from a measured 0.0.**
2. The column stores **(PCM − dash)**; clients **subtract** it to land on the dash-equivalent number the user actually reads.
3. **Presentation and prefill only — raw `pid_readings` are never mutated.** The stored reading stays what the PCM said.
4. Applied consistently at every surface that shows an odometer to a human: web Live tile, web fillup modal, phone fillup prefill. A corrected number on one surface and a raw one on another is worse than neither.
5. On the phone the offset is applied **before** the existing plausibility guard, because that guard compares against the last fillup odometer, which is already dash-sourced.

**Consequence.**

- The odo chain stays internally consistent, so MPG is not corrupted by source-mixing.
- Distances are labelled everywhere; the km-shown-as-miles class of bug is closed.
- Vehicles with no measured offset behave exactly as before.

---

## ADR-022 — Extended (Mode 22) PIDs are polled by the phone, not the dongle

> **FAILED ON-VEHICLE 2026-08-05. The premise below is wrong; the feature is disabled and
> should stay disabled.** Enabling the toggle on the real car broke capture within four
> seconds, for TWO independent reasons.
>
> **1. There is no separate transport to isolate into.** Point 1 assumed the phone's BLE
> session is independent of the dongle's `auto_pid` polling. It is not — they share one
> ELM/CAN session through the dongle, so `ATSH18DA1EF1` sent by the PHONE corrupted the
> DONGLE's own publishing. Its MQTT payload collapsed from **62 keys to 16**, losing
> `engine_fuel_rate`, `engine_exhaust_flow`, `maf_air_flow`, `intake_air_temp`,
> `fuel_level`, `odometer`, catalyst temps, O2 sensors and every fuel trim. Recovery
> required re-applying the config via `/store_config` + `/store_auto_data`.
>
> **2. The BLE bridge does not reassemble multi-frame ISO-TP.** This was point 5's
> explicitly-unverified assumption, and it is false. Observed:
> ```
> obd response for unpolled pid           {echo: "2230", data_bytes: 4}
> obd extended value parser returned null {pid: gear_position, payload_bytes: 6}
> ```
> `gear_position` needs payload byte **23**; six bytes arrived. `2230` is a truncated
> Mode 22 echo. The HTTP `/autopid/test_pid` path reassembles continuation frames; the
> BLE publish path does not. So even with the header problem solved, nothing would decode.
>
> **The data itself is real** — ATF 61 °C and a gear decode validated 4/4 against a blind
> D→R→N→D shift (see `docs/research/honda-pilot-pids.md`). It is the *transports* that
> can't carry it: `auto_pid` breaks on sticky headers, BLE truncates multi-frame. Making
> this work needs dongle firmware supporting a per-PID header WITH ISO-TP reassembly on
> the publish path. That is not in our control.
>
> **Status:** setting defaults off and is labelled non-functional in the UI. The code
> (`obd/IsoTp.kt`, `obd/ZfTcmPids.kt`, the header-restore machinery) is left dormant
> rather than deleted — it is correct as written and cheap to re-test if firmware changes.
> **Do not re-enable without first re-verifying multi-frame reassembly over BLE.**
>
> The one thing that worked as designed: the silent-drop logging added the same day
> caught the whole failure in three log lines. Without it this would have presented as
> metrics quietly vanishing — exactly the MAF mystery that took a day to find.

**Context.** ATF temperature (`22 3083`) and gear position (`22 3086`) are reachable on the ZF 9HP TCM at module `0x1E` — both verified live, gear against a blind 4/4 shift sequence. Reaching them requires setting a non-default ELM transmit header (`ATSH18DA1EF1`).

Adding such a PID to the dongle's `auto_pid` table **collapsed its published payload from 62 keys to 19** and was rolled back. ELM headers are sticky and `Init` runs *before* a request, never after, so a header set for ATF persists into whatever standard PID polls next — those are then answered by the wrong module. `auto_pid` offers no "after" hook, and `_hdr_reset` is merely another entry competing in the same round-robin. Lengthening the period reduces the collapse frequency without eliminating it.

**Decision.**

1. **Extended PIDs are polled over the phone's BLE session**, where the poller controls exact command ordering: set header → request → restore. This is also the transport that is live *during drives*, which is when ATF and gear mean anything.
2. **The header restore must be structurally unavoidable** — on the failure path as well as the success path. A session left on `18DA1EF1` silently corrupts every subsequent standard reading, which is exactly the 62→19 collapse observed on the dongle.
3. **Opt-in, default off**, behind its own settings key. Unproven behaviour must not be able to disturb a working capture path.
4. **The restore is two commands on two lines** — `ATSH7DF\r` then `ATCRA\r`. An ELM327 parses exactly one command per CR-terminated line, so the concatenated `ATSH7DFATCRA` is read as `AT SH` with the argument `7DFATCRA` (not 3/6/8 hex digits — `T` and `R` aren't hex), answered with `?`, and the header is left exactly where it was. The dongle's own working custom-PID config uses the CR-separated form (`"init": "ATSH7DF\rATCRA"`, ADR-018 §1), which is the same evidence. A restore that is a silent no-op is worse than no restore at all, because point 2's guarantee then reads as satisfied.
5. Left explicitly unverified: whether the dongle's BLE bridge accepts `AT` commands mid-session. It accepts ELM-style PID requests, but header commands specifically have not been confirmed on-vehicle.

**Consequence.**

- ATF and gear become reachable without risking the 57-PID standard stream.
- The blast radius of the unproven part is a feature that does nothing until enabled.
- VCM cylinder-deactivation state (`22 2615`, byte 53) remains unreachable regardless — the dongle truncates ISO-TP reassembly at ~34 payload bytes.

## ADR-023 — Auto-upload on WiFi overrides manual-sync, on two independent paths

**Context.** Manual-sync mode (ADR-015) suppresses every phone-side MQTT publish and the
post-seal upload kick, so a drive is captured locally and stays queued until the user taps
"Sync now". Its stated purpose is to avoid streaming telemetry over cellular; the step it
leaves to the user is "upload it once you're back on WiFi". That step is easy to forget —
the sync-reminder notification exists precisely because queues were growing to five drives
unnoticed.

The obvious automation, "upload when I'm on my home WiFi", has an awkward shape on Android.
A `ConnectivityManager.NetworkCallback` sees the network arrive instantly but only while
the process is alive, and the walk from the parked car to the house is exactly when Android
reclaims the app. WorkManager survives process death and reboot but its network constraints
express only `CONNECTED` / `UNMETERED` / `METERED` — there is no "this SSID" constraint.

**Decision.**

1. **The gate is one class, evaluated by every caller.** `WifiUploadGate` answers "may an
   automatic drain proceed right now" and is consulted by the live trigger, the WorkManager
   worker, and `DriveSealer`. Three call sites with three copies of the rule would drift,
   and the drift would be invisible: the failure mode is a drive that silently never
   uploads.
2. **It deliberately overrides `manualSyncOnly` for the upload queue, and only for that.**
   Manual-sync exists to keep drive payloads off cellular; a network the user nominated is
   not cellular. Live MQTT publishing stays suppressed in manual mode regardless — the
   override covers the HTTP drive queue alone.
3. **Two paths, because neither is sufficient alone.** `WifiUploadTrigger` (a WiFi
   `NetworkCallback`) handles the app-alive case and fires within seconds.
   `enqueueWifiDriveUpload` — a unique one-shot with an `UNMETERED` constraint, armed by
   `DriveSealer` whenever it parks a drive it could not upload — handles process death; the
   worker re-runs the full gate on wake and exits quietly if the network isn't a nominated
   one. `DriveUploader`'s drain mutex makes a double-fire cost one skipped pass, not a
   double upload.
4. **An empty SSID allowlist means "any unmetered WiFi"; a named network is honoured even
   when metered.** The unmetered requirement is what makes the empty default safe, since a
   metered hotspot is the user's cellular plan under another name. Naming a network is an
   explicit choice and is taken at face value.
5. **`VALIDATED`, not merely `INTERNET`.** A captive portal or a router with no upstream
   would otherwise start a drain that can only fail, burning the per-drive retry budget on
   every queued drive.
6. **SSID matching is case-insensitive, and "can't read the name" is distinct from "no
   match".** Users type an SSID from memory; a case slip that silently never uploads is a
   worse failure than matching a network differing only in case. Reading an SSID needs
   `ACCESS_FINE_LOCATION` (already held for GPS capture) — without it the gate returns
   `NoLocationPermission` and Settings says so in red, rather than reporting a mismatch the
   user cannot act on.

**Consequence.**

- The headline case — manual-sync on, arrive home, drives upload themselves — is covered
  whether or not the app survived the trip.
- With manual-sync off, the one-shot is armed too, which upgrades "sealed with no coverage"
  from "waits up to 4 h for the periodic backstop" to "goes on the next unmetered network".
- Settings' capture summary now reports the WiFi policy ahead of the manual-sync wording,
  because with both on, "uploads on demand" would be untrue.
- One extra `NetworkCallback` when the feature is on, alongside the `InCarDetector`'s. Both
  read SSIDs through the shared `WifiSsidReader`, so the network that auto-starts the
  bridge and the network that auto-uploads can't disagree about what it's called.

---

## ADR-024 — Basemap tiles come from OpenFreeMap, not CARTO

**Context.** Every map in pitstop — the web trip-detail route, the web fuel-station map
(both driven by `MapLibreMap.vue`), the web heatmap, the home location picker, and the
phone's route and heatmap views — drew its basemap from CARTO.
The web used CARTO's raster endpoints (`basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png`)
for the dark style and OpenStreetMap's own raster tiles for the light one; the phone used
CARTO's Dark Matter vector GL style.

On 2026-09-01 CARTO began stamping an "API KEY REQUIRED" watermark diagonally across every
unauthenticated raster tile it serves, and announced that the raster basemaps are being
retired in favour of keyed vector ones. Both web dark basemaps broke at once. The phone's
vector style was still serving clean tiles, but it is fed by the same free tier CARTO is
withdrawing, so it is a break waiting to happen rather than a safe place to stay.

**Decision.** Every map moves to OpenFreeMap: `tiles.openfreemap.org/styles/dark` and
`.../styles/positron`. The URLs live in exactly two places — `frontend/src/lib/mapStyles.ts`
and `android/.../ui/history/MapStyles.kt` — which also collapses three duplicated inline
style objects in the frontend into one import.

**Why not the alternatives.**

1. **A free CARTO API key** (5M tiles/month) was rejected because `Pr0zak/pitstop` is a
   public repository. The key could not be committed, so it would need an `.env` entry, a
   backend config endpoint to serve it to the SPA, and a matching path on the phone —
   real plumbing, and a credential to rotate, in exchange for staying on a provider that
   has just demonstrated it will change the terms.
2. **Staying on OpenStreetMap's raster tiles for both styles** was rejected because
   `tile.openstreetmap.org` has no dark variant, and its tile usage policy is written for
   low-volume use rather than as an application backend.
3. **Self-hosted Protomaps** — a regional `.pmtiles` extract served from the stack's own
   Caddy container — is the strongest option on paper: no external dependency at all, which
   suits a self-hosted project. It was deferred, not rejected. It costs a few hundred MB to
   a couple of GB of the CT's 30 GB rootfs plus a periodic refresh to stay current, and
   that is a poor trade against a same-day fix for a broken basemap. It remains the
   documented migration path if OpenFreeMap becomes unavailable.

**Consequence.**

- The web gains vector basemaps, which render sharper on high-DPI displays than the raster
  tiles they replace and can be restyled at runtime. The phone was already on a vector style
  (CARTO's Dark Matter), so for it this is a provider swap and nothing more.
- Attribution now travels in OpenFreeMap's TileJSON, so MapLibre renders the
  OpenFreeMap / OpenMapTiles / OpenStreetMap credits without an explicit control. Do not
  add an `AttributionControl` for it; doing so double-prints the credits.
- Route and trace layers are added with no `beforeId`, so they now draw above the basemap's
  vector labels. With raster tiles the labels were baked into the image and always sat
  under the route, so this is unchanged in effect.
- **A failed style fetch now blanks the whole web map, not just its backdrop.** The web's
  styles were inline objects, so MapLibre's `load` event fired unconditionally and the route,
  markers and trace drew over a grey square even with no tiles. A style URL is fetched, and
  `load` never fires if that fetch fails — so an OpenFreeMap outage takes the overlays down
  with the basemap. The phone always used a style URL and so is unchanged. Accepted for now:
  the fix is a blank-style fallback on the error path, and it is not written.
- The accepted risk is that OpenFreeMap is a free public instance with no SLA. Its
  disappearance is a rendering outage, not a data-loss event: trips, GPS fixes and analytics
  are unaffected, and the fix is the Protomaps path above.
