# Deployment plan — pitstop on pve5 CT 231

## Goal

Provision, deploy, and **automatically verify** pitstop on a new Proxmox LXC. Zero user interaction during the run; user only confirms at start and configures the WiCAN device's MQTT settings at the end.

## Pre-requisites (must be true before deployment runs)

- Phase A complete (Tasks 1, 2, 3, 4, 12, 14, 15) — backend has MQTT subscriber, schema, importer, trip detector
- Task #5 complete (REST API including `/health`, `/version`, `/health/disk`)
- Backend Docker image builds locally
- `docker-compose.yml` exists with healthchecks for all four services
- `deploy/ct-bootstrap.sh` and `deploy/upgrade.sh` exist
- Smoke + E2E test scripts exist in `deploy/tests/`
- `.gitignore` excludes `.env` and `data/` (already in place)

If any of the above is missing, deployment refuses to start and reports which.

## CT specification

| Resource | Value | Why |
|---|---|---|
| **CT ID** | `231` | next available |
| **Hostname** | `pitstop` | matches app |
| **OS template** | `debian-12-standard` | matches myvitals |
| **CPU** | 2 cores | sufficient for FastAPI + Postgres + Mosquitto |
| **RAM** | 6 GB | TimescaleDB likes RAM; 6 leaves room for backend + frontend + broker |
| **Swap** | 2 GB | safety net |
| **Disk** | **30 GB** on `local-lvm-2t` | tight but realistic for ~2–3 years; monitored, resizable non-destructively |
| **Network** | `vmbr0`, DHCP, MTU 1500 | matches myvitals; pin a stable lease in pihole post-provision |
| **Privileged?** | unprivileged | matches myvitals |
| **Features** | `keyctl=1,nesting=1` | Docker-in-LXC requires both |
| **Onboot** | yes (`onboot=1`) | start at host boot |
| **Tailscale** | none | existing CT 444 subnet router covers off-net access |

### Disk math (why 30 GB)

- **Postgres / TimescaleDB:** 1Hz × ~15 metrics × 30 min/day driving × 365 days = ~10M rows/year per active vehicle. With indexes and overhead: ~1–2 GB/year/vehicle. **Active Pilot only ≈ 1.5 GB/year**; truck + jet ski are essentially static historical data.
- **Photos** (if user later mounts/copies): ~500 MB realistic.
- **Docker images:** backend ~500 MB + frontend ~100 MB + timescaledb ~700 MB + mosquitto ~30 MB ≈ **1.5 GB**.
- **WAL / logs / tmp / headroom:** ~3 GB working set.
- **Initial fill (after Fuelio import):** ~2 GB total.
- **Year-1 projection:** ~5 GB. **Year-3 projection:** ~10 GB. 30 GB = ~2–3× headroom on the realistic curve.

`local-lvm-2t` is LVM-thin, so allocated-but-unused space stays in the pool. Resize is one command, non-destructive.

### Disk monitoring (defense in depth, 30 GB needs it)

Three layers, since 30 GB is on the snug side:

1. **`/health/disk` endpoint** — backend reports rootfs % used and absolute free GB. `pitstop-status` skill surfaces it on every check.
2. **Daily cron in CT** — writes a disk-usage line to `/var/log/pitstop-disk.log` and POSTs a one-liner to `PITSTOP_DISK_ALERT_WEBHOOK` if usage > **70%** (lowered from 80% given the smaller disk — buys earlier warning).
3. **Easy resize** when warning fires:
   ```
   ssh root@pve5 "pct resize 231 rootfs +20G"
   ssh root@pve5 "pct exec 231 -- resize2fs /dev/<rootfs>"
   ```
   No downtime, no container restart needed for ext4. Run once when monitoring shouts.

## Sequence (orchestrated by a single deploy script)

### Phase 1 — Pre-flight (host-side, no CT changes yet)

```
1.  SSH to pve5 reachable
2.  CT 231 does not yet exist (pct status returns "does not exist")
3.  local-lvm-2t free space >= 50 GB (headroom over 30 GB)
4.  vmbr0 is up
5.  pihole reachable (for DHCP reservation step)
6.  /home/spider/pitstop has docker-compose.yml + deploy/ + .env.example
7.  Required tools available locally: ssh, rsync, openssl, curl, jq, mosquitto_pub
8.  No uncommitted changes in /home/spider/pitstop OR user has acknowledged
```

If any check fails → abort with explicit reason. **No user prompt** during the run; the script exits non-zero and reports.

### Phase 2 — Provision

```
ssh root@pve5 pct create 231 \
  /var/lib/vz/template/cache/debian-12-standard_*.tar.zst \
  --hostname pitstop \
  --cores 2 --memory 6144 --swap 2048 \
  --rootfs local-lvm-2t:30 \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp \
  --features keyctl=1,nesting=1 \
  --unprivileged 1 \
  --onboot 1 \
  --start 1
```

Wait up to 60s for systemd to be ready (`systemctl is-system-running` returns `running` or `degraded`). Capture the DHCP-assigned IP:

```
ssh root@pve5 "pct exec 231 -- ip -4 -o addr show eth0 | awk '{print \$4}' | cut -d/ -f1"
```

### Phase 3 — Bootstrap (`deploy/ct-bootstrap.sh` runs inside CT 231)

```
1.  apt update && apt install -y curl ca-certificates gnupg lsb-release rsync git
2.  Install Docker CE + compose plugin (docker.io repo)
3.  runc 1.1.x swap (Debian's runc, mandatory for unprivileged LXC + Docker):
      apt install -y runc
      cp /usr/sbin/runc /usr/bin/runc
      systemctl restart docker
4.  Create /opt/pitstop, set ownership
5.  Create data dirs: /opt/pitstop/data/{db,mosquitto,photos,backups}
6.  Set TZ to America/Chicago (or value from --tz arg)
7.  Disable journald disk binge: SystemMaxUse=500M
```

### Phase 4 — Push code + secrets

```
1.  Locally: render .env from .env.example, generating fresh secrets:
      INGEST_TOKEN     = openssl rand -hex 32
      QUERY_TOKEN      = openssl rand -hex 32
      POSTGRES_PASSWORD= openssl rand -hex 24
      MQTT_USER        = pitstop
      MQTT_PASSWORD    = openssl rand -hex 24
2.  Save secrets locally to ~/.pitstop-deploy-secrets.txt (gitignored), readable only by user
3.  rsync /home/spider/pitstop/ → CT 231:/opt/pitstop/ (excluding .git, node_modules, .venv, data, .env)
4.  scp local .env → CT 231:/opt/pitstop/.env (mode 0600)
5.  Generate Mosquitto password file from MQTT credentials
6.  Bind Mosquitto port to LAN IP only via compose ports: "<CT_IP>:1883:1883"
```

### Phase 5 — Bring up the stack

```
ssh root@pve5 "pct exec 231 -- bash -c 'cd /opt/pitstop && docker compose pull && docker compose up -d'"
```

Wait until all four services report `healthy` (poll `docker compose ps --format json` every 5 s, max 180 s).

### Phase 6 — Pihole DHCP reservation

```
1.  Capture CT 231's eth0 MAC
2.  POST to pihole admin API: reserve <CT_IP> for that MAC
3.  Verify the lease sticks
```

If the user's pihole has no admin API enabled, log a warning and skip — DHCP usually re-assigns the same IP anyway.

### Phase 7 — Automated verification (the test plan)

This phase is the "verify without user interaction" core. Three tiers, each must pass before the next runs.

#### Tier A — Liveness

| Check | Pass criteria |
|---|---|
| `curl http://<CT_IP>:8000/health` | HTTP 200, JSON `{"status":"ok"}` |
| `curl http://<CT_IP>:8000/version` | HTTP 200, returns `version` + `git_sha` |
| `curl http://<CT_IP>:8080/` | HTTP 200, HTML body contains `pitstop` |
| `curl http://<CT_IP>:8000/health/disk` | usage % < 10% on a fresh box |
| `mosquitto_sub -h <CT_IP> -t '$SYS/broker/version' -W 5 -C 1 -u pitstop -P <pw>` | broker responds with version |
| `docker compose ps --format json` (on CT) | all four services `healthy`, restart count 0 |

#### Tier B — Schema + seed

| Check | Pass criteria |
|---|---|
| `psql -c '\dt'` | tables exist: `vehicles`, `pid_profiles`, `pid_readings`, `trips`, `dtc_events`, `fillups`, `expenses`, `expense_categories`, `pictures`, `settings`, `lookup_*` |
| `psql -c "SELECT count(*) FROM pid_profiles"` | ≥ 1 (Honda Pilot 2019 seeded) |
| `psql -c "SELECT count(*) FROM expense_categories"` | = 9 (Fuelio defaults) |
| `psql -c "SELECT count(*) FROM lookup_units"` | ≥ 6 |
| Hypertable check | `pid_readings` registered as hypertable |

#### Tier C — End-to-end fixture (no user interaction)

Two synthetic flows that exercise the full pipeline:

**E2E-1: Synthetic OBD trip**
```
1.  POST /vehicles {name: "test-pilot", fuelio_guid: "test-guid", ...} → 201 with id
2.  Publish 60 messages to topic wican/test-pilot/engine_rpm via mosquitto_pub at 1Hz
    (alongside vehicle_speed, coolant_temp, control_module_voltage)
3.  Wait 90s for trip detector to close trip
4.  GET /trips?vehicle_id=<id> → 1 trip with duration ≈ 60s, max RPM as published
5.  GET /readings?vehicle_id=<id>&metric=engine_rpm&limit=200 → 60 rows
6.  DELETE /vehicles/<id> (cascades to trips + readings)
```

**E2E-2: Synthetic Fuelio import**
```
1.  Generate a tiny synthetic Fuelio CSV: 1 vehicle + 3 fillups + 1 service entry, all with
    deterministic fuelio_guids (fixture-* prefix)
2.  POST /import/fuelio (multipart, dry-run=true) → preview shows {vehicles:1, fillups:3, expenses:1}
3.  POST /import/fuelio (multipart, dry-run=false) → import succeeds
4.  GET /fuel/fillups?vin_or_guid=fixture-* → 3 rows
5.  POST /import/fuelio same file again → preview shows {fillups_skipped:3} (idempotent)
6.  DELETE the fixture vehicle (cascades)
```

#### Tier D — HA mirror is OFF

| Check | Pass criteria |
|---|---|
| `GET /settings` | returns `ha.enabled: false` |
| `mosquitto_sub -h <CT_IP> -t 'homeassistant/#' -W 5 -u pitstop -P <pw>` | timeout, no messages |

If any tier fails, the deploy script:
1. Captures relevant logs (`docker compose logs --tail 200`)
2. Saves them to `~/.pitstop-deploy-logs/<timestamp>/`
3. Exits non-zero with a one-line summary
4. **Does NOT roll back automatically** (preserve state for diagnosis)

### Phase 8 — Post-deploy report

Printed to stdout, also written to `~/.pitstop-deploy-secrets.txt`:

```
✅ pitstop deployed to CT 231 on pve5
   IP:        <CT_IP>
   API:       http://<CT_IP>:8000
   Frontend:  http://<CT_IP>:8080
   Mosquitto: <CT_IP>:1883 (user "pitstop", pw in ~/.pitstop-deploy-secrets.txt)
   Tokens:    INGEST + QUERY in ~/.pitstop-deploy-secrets.txt
   DB:        timescaledb on internal port 5432

Manual steps:
   1. Open WiCAN web UI → MQTT Settings:
      - Broker:   <CT_IP>:1883
      - Username: pitstop
      - Password: (paste from ~/.pitstop-deploy-secrets.txt)
      - Topic prefix: wican/pilot19/
   2. Open WiCAN web UI → AutoPID → Upload pid_profiles/honda-pilot-2019.json
   3. Drive once. Run pitstop-status to confirm data flowed.
```

## Rollback strategy

The CT is a clean fresh provision. Rollback = destroy and start over:
```
ssh root@pve5 "pct stop 231 && pct destroy 231 --purge"
```

No data loss because nothing real is in there yet on a failed deploy. Subsequent re-deploys re-mint secrets.

If we need to roll back **after** real data is present (e.g. a bad upgrade), the path is:
- PBS9000 covers nightly CT-level snapshot — restore from PBS
- Or `pct snapshot 231 pre-upgrade` before risky changes; `pct rollback 231 pre-upgrade` if needed

## Manual steps that remain user-driven

These cannot be automated:

1. **WiCAN MQTT settings** — paste credentials into the WiCAN device's web UI.
2. **WiCAN AutoPID profile upload** — upload `honda-pilot-2019.json` via the device UI.
3. **First drive** to generate live data.
4. **Tailscale subnet acceptance** on the user's phone (one-time toggle in TS Android app's "Use Tailscale subnets" section).

The deploy script's final report tells the user exactly what to do.

## Open questions (blocking and non-blocking)

### Blocking — must answer before running deploy

1. **GitHub repo: public or private?** Default I'll assume: **public** (matches `Pr0zak/myvitals` pattern). The `.gitignore` + privacy audit guard against leaking personal data. Confirm.
2. **GHCR image visibility.** Public package (anyone can pull) or private (CT needs PAT). Default: **public** since the source repo is public.
3. **Scaffold is not yet done.** Deployment runs **after** Tasks #1–#5, #12, #14, #15. OK to defer until Phase A complete?

### Non-blocking — defaults will be applied

| Question | Default |
|---|---|
| CT ID | 231 |
| Hostname | `pitstop` |
| Disk size | 30 GB on `local-lvm-2t` (resizable) |
| RAM / cores / swap | 6 GB / 2 / 2 GB |
| TZ | `America/Chicago` |
| Static IP via | pihole DHCP reservation |
| Mosquitto auth | auto-generated, written to `~/.pitstop-deploy-secrets.txt` |
| Backup | rely on PBS9000 CT snapshots; no app-level dumps at launch |
| Disk-alert webhook | none unless user provides one |

If any default is wrong, override via env vars to the deploy script (e.g. `PITSTOP_CT_ID=232 PITSTOP_DISK_GB=200 ./deploy/run.sh`).

## Subagent usage

| Subagent | Used for |
|---|---|
| **Explore** | Recon-style reads against pve5 (storage status, next CT ID, network, existing CTs). One-shot before deploy. |
| **general-purpose** | Authoring `ct-bootstrap.sh` (long script, validates against myvitals's version). Run in background; main thread continues planning. |
| **general-purpose** | Authoring smoke + E2E test scripts. Independent of bootstrap, runs in parallel. |
| **Plan** | Sanity-checking the deployment sequence end-to-end before execution. |

Two parallel general-purpose agents (bootstrap-author + tests-author) is the natural break. Recon is a single Explore call. Plan is a single check after both finish.

## Test fixtures kept in repo

Under `deploy/tests/fixtures/`:

- `fixture-fuelio.csv` — synthetic minimal Fuelio export (1 vehicle, 3 fillups, 1 service). Hand-crafted, no real data.
- `fixture-mqtt-publish.sh` — mosquitto_pub script that publishes 60s of synthetic OBD-II values for a "test-pilot" vehicle.

These are committed to the public repo (no real data in them).

## Verification scripts kept in repo

Under `deploy/tests/`:

- `tier-a-liveness.sh` — health/version/frontend/mqtt liveness
- `tier-b-schema.sh` — psql checks for tables, seed counts, hypertable
- `tier-c-e2e.sh` — runs E2E-1 and E2E-2
- `tier-d-ha-off.sh` — confirms HA mirror is dormant
- `run-all.sh` — runs A→B→C→D, exits non-zero on first failure

Each script is independently runnable for ad-hoc post-incident verification.
