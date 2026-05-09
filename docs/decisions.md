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
