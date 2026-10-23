<script setup lang="ts">
// Insights — plain-language findings from GET /analytics/insights. The
// server writes every sentence and every number (ADR-013); this page only
// orders and lays them out: what needs a look first, then one section per
// group in the server's order.
import { computed, nextTick, ref } from "vue";
import { CheckCircle2, RotateCw } from "lucide-vue-next";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";
import InsightCardView from "@/components/insights/InsightCard.vue";
import { useAsync } from "@/composables/useAsync";
import { useQueryParam } from "@/composables/useQueryParam";
import { fmtWhen } from "@/composables/useFormat";
import { useVehiclesStore } from "@/stores/vehicles";
import * as api from "@/api/endpoints";
import type { InsightCard, InsightsResponse } from "@/api/types";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);

type DaysKey = "90" | "180" | "365" | "3650";
const DAYS_OPTIONS = [
  { value: "90" as const, label: "3 mo", title: "Last 3 months" },
  { value: "180" as const, label: "6 mo", title: "Last 6 months" },
  { value: "365" as const, label: "1 yr", title: "Last 12 months" },
  { value: "3650" as const, label: "All", title: "All history" },
];
const days = useQueryParam<DaysKey>("days", "365", ["90", "180", "365", "3650"]);
const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";

// Refresh = the same request with refresh=true (skips the server cache).
// The flag is consumed by the next fetch only.
let forceNext = false;
const q = useAsync<InsightsResponse | null>(() => {
  const refresh = forceNext;
  forceNext = false;
  if (!vehicleId.value) return Promise.resolve(null);
  return api.getInsights({ vehicle_id: vehicleId.value, days: Number(days.value), tz, refresh });
}, [vehicleId, days]);
const refreshing = ref(false);
async function refresh() {
  forceNext = true;
  refreshing.value = true;
  try {
    await q.reload();
  } finally {
    refreshing.value = false;
  }
}

const data = computed(() => q.data.value);
const updated = computed(() =>
  data.value ? `Updated ${fmtWhen(data.value.generated_at, { withTime: true })}` : "",
);

const byId = computed(() => new Map((data.value?.insights ?? []).map((c) => [c.id, c])));
const attention = computed<InsightCard[]>(() =>
  (data.value?.attention ?? [])
    .map((id) => byId.value.get(id))
    .filter((c): c is InsightCard => c != null),
);

// ── group filter ──
const group = ref<string>("all");
const groupOptions = computed(() => [
  { value: "all", label: "All" },
  ...(data.value?.groups ?? [])
    .filter((g) => data.value!.insights.some((c) => c.group === g.id))
    .map((g) => ({ value: g.id, label: g.title })),
]);
const activeGroup = computed(() =>
  groupOptions.value.some((o) => o.value === group.value) ? group.value : "all",
);
const sections = computed(() => {
  const d = data.value;
  if (!d) return [];
  return d.groups
    .filter((g) => activeGroup.value === "all" || g.id === activeGroup.value)
    // Cards that are still collecting data go last in their group so the
    // findings come first; otherwise the server's order is kept.
    .map((g) => {
      const inGroup = d.insights.filter((c) => c.group === g.id);
      return {
        ...g,
        cards: [
          ...inGroup.filter((c) => c.status !== "learning"),
          ...inGroup.filter((c) => c.status === "learning"),
        ],
      };
    })
    .filter((g) => g.cards.length > 0);
});

// ── jump from the attention strip to a card ──
const flashId = ref<string | null>(null);
let flashTimer: ReturnType<typeof setTimeout> | undefined;
async function jumpTo(id: string) {
  const card = byId.value.get(id);
  if (card && activeGroup.value !== "all" && activeGroup.value !== card.group) {
    group.value = "all";
    await nextTick();
  }
  const el = document.getElementById(`insight-${id}`);
  if (!el) return;
  const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  el.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
  el.focus({ preventScroll: true });
  flashId.value = id;
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => (flashId.value = null), 1600);
}
</script>

<template>
  <div class="insights">
    <header class="head">
      <div class="titles">
        <h1>Insights</h1>
        <p class="explainer">
          What your trips, fillups and sensors say, in plain words. Anything that needs a
          look is listed first.
        </p>
      </div>
      <div class="controls">
        <WindowChips v-model="days" :options="DAYS_OPTIONS" label="Time window" />
        <button
          type="button"
          class="ghost refresh"
          :disabled="!vehicleId || q.loading.value"
          title="Recalculate now instead of using the last saved result"
          @click="refresh"
        >
          <RotateCw :size="14" :class="{ spin: refreshing }" aria-hidden="true" />
          Refresh
        </button>
        <span v-if="updated" class="updated">{{ updated }}</span>
      </div>
    </header>

    <StateCard v-if="!vehicleId" state="empty" title="Select a vehicle." />
    <StateCard v-else-if="q.loading.value && !data" state="loading" title="Working out your insights…" />
    <StateCard
      v-else-if="q.error.value && !data"
      state="error"
      :message="q.error.value"
      @retry="q.reload()"
    />
    <StateCard
      v-else-if="data && data.insights.length === 0"
      state="empty"
      title="No insights yet."
      message="Drive a few trips and log a fillup or two, then check back."
    />

    <template v-else-if="data">
      <StateCard
        v-if="q.error.value"
        state="error"
        title="Couldn't refresh — showing the last result."
        :message="q.error.value"
        @retry="q.reload()"
      />

      <div class="body" :class="{ stale: q.loading.value }" :aria-busy="q.loading.value">
        <section v-if="attention.length" class="card attention" aria-labelledby="att-h">
          <h2 id="att-h" class="att-h">
            Needs attention <span class="count">{{ attention.length }}</span>
          </h2>
          <ul>
            <li v-for="c in attention" :key="c.id">
              <button type="button" class="att-row" @click="jumpTo(c.id)">
                <i class="dot" :class="c.status" aria-hidden="true" />
                <span class="att-title">{{ c.title }}</span>
                <span class="att-line">{{ c.headline }}</span>
              </button>
            </li>
          </ul>
        </section>
        <div v-else class="calm" role="status">
          <CheckCircle2 :size="16" aria-hidden="true" />
          Nothing needs attention right now.
        </div>

        <WindowChips
          v-if="groupOptions.length > 2"
          v-model="group"
          :options="groupOptions"
          label="Show group"
          class="group-chips"
        />

        <section
          v-for="s in sections"
          :key="s.id"
          class="group"
          :aria-labelledby="`grp-${s.id}`"
        >
          <header class="group-head">
            <h2 :id="`grp-${s.id}`">{{ s.title }}</h2>
            <span class="sub">{{ s.subtitle }}</span>
          </header>
          <div class="cards">
            <InsightCardView
              v-for="c in s.cards"
              :key="c.id"
              :card="c"
              :flash="flashId === c.id"
            />
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.insights {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  container-type: inline-size;
}
.head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 0.75rem 1.5rem;
}
.titles h1 {
  margin: 0 0 0.2rem;
}
.explainer {
  margin: 0;
  max-width: 60ch;
  color: var(--c-ink2);
}
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 0.75rem;
}
.refresh {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.7rem;
  font-size: 0.82rem;
  border-color: var(--c-line1);
}
.updated {
  font-size: 12px;
  color: var(--c-ink3);
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.body {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  transition: opacity 150ms;
}
.body.stale {
  opacity: 0.6;
}

/* ── needs attention ── */
.attention {
  padding: 0.8rem 0.6rem 0.5rem;
}
.att-h {
  margin: 0 0.5rem 0.4rem;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--c-ink3);
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.count {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-size: 11px;
  padding: 0 0.4rem;
  border-radius: 999px;
  background: var(--c-bg4);
  color: var(--c-ink1);
  letter-spacing: 0;
}
.attention ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.att-row {
  display: grid;
  grid-template-columns: 10px minmax(8rem, max-content) 1fr;
  align-items: baseline;
  gap: 0.6rem;
  width: 100%;
  padding: 0.5rem 0.5rem;
  background: transparent;
  border: 0;
  border-radius: var(--r-sm);
  text-align: left;
  font-weight: 400;
}
.att-row:hover:not(:disabled) {
  background: var(--c-bg3);
}
.attention li + li .att-row {
  border-top: 1px solid var(--c-line0);
  border-radius: 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  align-self: center;
  background: var(--c-ink3);
}
.dot.bad {
  background: var(--c-danger);
}
.dot.watch {
  background: var(--c-warn);
}
.dot.good {
  background: var(--c-success);
}
.dot.info {
  background: var(--c-info);
}
.att-title {
  font-weight: 600;
  color: var(--c-ink0);
}
.att-line {
  min-width: 0;
  color: var(--c-ink2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.calm {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.9rem;
  border-radius: var(--r-md);
  border: 1px solid var(--c-line0);
  background: var(--c-bg2);
  color: var(--c-ink2);
}
.calm svg {
  color: var(--c-success);
}

/* ── groups ── */
.group {
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
}
.group-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.2rem 0.75rem;
  padding-bottom: 0.4rem;
  border-bottom: 1px solid var(--c-line0);
}
.group-head h2 {
  margin: 0;
  font-size: 1.15rem;
}
.sub {
  color: var(--c-ink3);
  font-size: 0.9rem;
}
.cards {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
}
@container (min-width: 760px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .head {
    align-items: flex-start;
  }
  .att-row {
    grid-template-columns: 10px 1fr;
    row-gap: 0.1rem;
  }
  .att-line {
    grid-column: 2;
    white-space: normal;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
}
</style>
