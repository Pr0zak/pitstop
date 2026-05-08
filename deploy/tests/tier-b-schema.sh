#!/usr/bin/env bash
# Tier B — schema + seed. psql checks against the live DB.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

bold "[Tier B] schema + seed checks"

# 1. Tables present
tables_csv="$(ct_psql "SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables WHERE table_schema='public'")"
expected=(vehicles pid_profiles pid_readings trips dtc_events vehicle_state settings lookup_units expense_categories fillups expenses pictures trip_categories)
for t in "${expected[@]}"; do
  if [[ ",${tables_csv}," == *",${t},"* ]]; then
    ok "table $t exists"
  else
    fail "table $t missing (got: ${tables_csv})"
  fi
done

# 2. Hypertable
ht="$(ct_psql "SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name='pid_readings'")"
[[ "$ht" == "1" ]] || fail "pid_readings is not a hypertable (got ${ht})"
ok "pid_readings is a hypertable"

# 3. expense_categories = 9
n="$(ct_psql "SELECT count(*) FROM expense_categories")"
[[ "$n" == "9" ]] || fail "expense_categories count=$n (expected 9)"
ok "expense_categories = 9"

# 4. lookup_units >= 6
n="$(ct_psql "SELECT count(*) FROM lookup_units")"
[[ "$n" -ge 6 ]] || fail "lookup_units count=$n (expected ≥ 6)"
ok "lookup_units = $n (≥ 6)"

# 5. settings singleton
n="$(ct_psql "SELECT count(*) FROM settings WHERE id=1")"
[[ "$n" == "1" ]] || fail "settings singleton missing (got count=$n)"
ok "settings singleton present"

# 6. honda-pilot-2019 profile seeded
n="$(ct_psql "SELECT count(*) FROM pid_profiles WHERE name='honda-pilot-2019'")"
[[ "$n" == "1" ]] || fail "honda-pilot-2019 profile not seeded (got count=$n)"
ok "honda-pilot-2019 profile seeded"

# 7. trip_categories has Private + Work
n="$(ct_psql "SELECT count(*) FROM trip_categories")"
[[ "$n" -ge 2 ]] || fail "trip_categories count=$n (expected ≥ 2)"
ok "trip_categories = $n (≥ 2)"

bold "[Tier B] PASS"
