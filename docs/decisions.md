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
