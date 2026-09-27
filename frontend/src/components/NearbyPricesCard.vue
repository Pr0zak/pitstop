<script setup lang="ts">
// Live nearby fuel prices (GET /fuel-prices/nearby, ADR-026).
//
// No lat/lon from the browser: the web UI is plain http on the LAN, where
// geolocation is refused, so the server searches around the car's last GPS
// point (then Settings → home). Every response is a 200 with a `status`
// rendered here directly. Uncached lookups cost money, so this fetches on
// mount / vehicle / grade change and on the Refresh button only — never on
// an interval. Rendered as a list, not map pins: Google's terms restrict
// Places content on a non-Google map.
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { ExternalLink, RefreshCw } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { FuelGrade } from "@/api/types";
import { useAsync } from "@/composables/useAsync";
import { useNow } from "@/composables/useNow";
import { distUnitLabel, fmtDateTime, fmtDistance } from "@/composables/useFormat";
import {
  DEFAULT_GRADE,
  GRADE_OPTIONS,
  agoText,
  emptyLine,
  fmtFuelPrice,
  isFuelGrade,
  myLastLine,
  originLine,
  otherPricesLine,
  usageLine,
} from "@/lib/nearbyPrices";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";

const props = defineProps<{
  vehicleId: string;
  vehicleName?: string | null;
}>();

const GRADE_KEY = "pitstop_nearby_grade";
function loadGrade(): FuelGrade {
  try {
    const v = localStorage.getItem(GRADE_KEY);
    if (isFuelGrade(v)) return v;
  } catch {
    /* ignore */
  }
  return DEFAULT_GRADE;
}
const grade = ref<FuelGrade>(loadGrade());
watch(grade, (g) => {
  try {
    localStorage.setItem(GRADE_KEY, g);
  } catch {
    /* ignore */
  }
});

// The server caches per ~1 km cell with every grade in it, so a grade
// switch inside 30 minutes doesn't spend another lookup.
const q = useAsync(
  () => api.nearbyFuelPrices(props.vehicleId, grade.value),
  [() => props.vehicleId, grade],
);

// Ticks the "3 h ago" labels; ages matter more than clock times here.
const nowMs = useNow(60_000);

// Whole km ("5 km"), one decimal in miles ("3.1 mi") — as Android words it.
function radiusLabel(radiusM: number): string {
  return fmtDistance(radiusM / 1000, "km", distUnitLabel() === "mi" ? 1 : 0);
}

const res = computed(() => q.data.value);
const origin = computed(() =>
  res.value?.status === "ok"
    ? originLine(res.value.origin, props.vehicleName, { now: new Date(nowMs.value) })
    : null,
);
const rows = computed(() =>
  (res.value?.stations ?? []).map((s) => ({
    ...s,
    priceText: fmtFuelPrice(s.price, s.currency),
    priceAge: agoText(s.price_updated_at, nowMs.value),
    others: otherPricesLine(s.other_prices),
    mine: myLastLine(s.my_last_price, s.my_last_date),
  })),
);
</script>

<template>
  <section class="card nearby" aria-labelledby="nearby-title">
    <header class="np-head">
      <h3 id="nearby-title">Nearby prices</h3>
      <WindowChips v-model="grade" :options="GRADE_OPTIONS" label="Fuel grade" />
      <button
        type="button"
        class="ghost np-refresh"
        :disabled="q.loading.value"
        aria-label="Refresh nearby prices"
        @click="q.reload()"
      >
        <RefreshCw :size="12" aria-hidden="true" :class="{ spin: q.loading.value }" />
        Refresh
      </button>
    </header>
    <p v-if="origin" class="muted small np-origin">{{ origin }}</p>

    <StateCard
      v-if="q.loading.value && !res"
      bare
      state="loading"
      title="Looking up nearby prices…"
    />
    <StateCard
      v-else-if="q.error.value && !res"
      bare
      state="error"
      :message="q.error.value"
      @retry="q.reload()"
    />
    <template v-else-if="res">
      <p v-if="q.error.value" class="small warn-text" role="alert">
        Refresh failed: {{ q.error.value }}
      </p>

      <StateCard v-if="res.status === 'no_key'" bare state="empty" title="Add a Google Places API key in Settings">
        <RouterLink :to="{ path: '/settings', hash: '#places' }">Open Settings</RouterLink>
      </StateCard>
      <StateCard
        v-else-if="res.status === 'no_location'"
        bare
        state="empty"
        title="No location to search around"
        message="The car has no GPS fix yet and no home location is set."
      >
        <RouterLink :to="{ path: '/settings', hash: '#home' }">Set a home location</RouterLink>
      </StateCard>
      <StateCard
        v-else-if="res.status === 'quota_reached'"
        bare
        state="empty"
        title="Monthly lookup limit reached — resets on the 1st"
        :message="`${res.usage.month_calls}/${res.usage.monthly_cap} lookups used this month.`"
      />
      <StateCard
        v-else-if="res.status === 'upstream_error'"
        bare
        state="error"
        title="Google Places lookup failed"
        :message="res.detail ?? 'No detail from Google.'"
        @retry="q.reload()"
      />
      <template v-else>
        <StateCard v-if="rows.length === 0" bare state="empty" :title="emptyLine(radiusLabel(res.radius_m))" />
        <ul v-else class="np-list" :class="{ stale: q.loading.value }">
          <li v-for="s in rows" :key="s.place_id" class="np-row">
            <div class="np-main">
              <a class="np-name" :href="s.maps_url" target="_blank" rel="noopener noreferrer">
                {{ s.name ?? "Unnamed station" }}
                <ExternalLink :size="11" aria-hidden="true" />
                <span class="sr-only">(opens Google Maps)</span>
              </a>
              <div class="np-sub">
                <span>{{ fmtDistance(s.distance_m / 1000, "km", 1) }}</span>
                <span v-if="s.address" class="np-addr">{{ s.address }}</span>
              </div>
              <div v-if="s.others" class="np-sub np-others" title="Other grades at this station">
                {{ s.others }}
              </div>
            </div>
            <div class="np-price">
              <div
                class="np-amt"
                :class="{ none: s.price == null }"
                :title="s.price == null ? 'No price reported for this grade' : undefined"
              >
                {{ s.priceText }}
              </div>
              <div v-if="s.priceAge" class="np-sub" :title="`Price updated ${fmtDateTime(s.price_updated_at)}`">
                {{ s.priceAge }}
              </div>
              <div
                v-if="s.mine"
                class="np-sub np-mine"
                :title="`${s.my_fillup_count} fillup${s.my_fillup_count === 1 ? '' : 's'} logged here`"
              >
                {{ s.mine }}
              </div>
            </div>
          </li>
        </ul>
        <p class="muted small np-foot">
          {{ usageLine(res.usage) }}<template v-if="res.fetched_at">
            · Fetched {{ agoText(res.fetched_at, nowMs) }}</template>
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.np-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem 0.75rem;
}
.np-head h3 {
  margin: 0;
}
.np-refresh {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.78rem;
  padding: 0.3rem 0.6rem;
}
.np-origin {
  margin: 0.45rem 0 0;
}
.small {
  font-size: 0.78rem;
}
.warn-text {
  color: var(--c-warn);
  margin: 0.5rem 0 0;
}
.np-list {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
  transition: opacity 120ms;
}
.np-list.stale {
  opacity: 0.55;
}
.np-row {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  padding: 0.6rem 0;
  border-bottom: 1px solid var(--c-line0);
}
.np-row:last-child {
  border-bottom: none;
}
.np-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.np-name {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--c-ink0);
  font-weight: 500;
  align-self: flex-start;
  max-width: 100%;
}
.np-name:hover {
  text-decoration: underline;
}
.np-name :deep(svg) {
  flex: none;
  color: var(--c-ink3);
}
.np-sub {
  font-size: 0.8rem;
  color: var(--c-ink2);
}
.np-main .np-sub {
  display: flex;
  min-width: 0;
}
.np-main .np-sub > span:not(:last-child)::after {
  content: "·";
  margin: 0 0.4rem;
  color: var(--c-ink4);
}
.np-addr {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.np-others {
  color: var(--c-ink3);
}
.np-price {
  flex: none;
  text-align: right;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.np-amt {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--c-ink0);
}
.np-amt.none {
  color: var(--c-ink3);
  font-weight: 400;
}
.np-mine {
  color: var(--c-ink1);
}
.np-foot {
  margin: 0.6rem 0 0;
}
.spin {
  animation: np-spin 0.9s linear infinite;
}
@keyframes np-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
