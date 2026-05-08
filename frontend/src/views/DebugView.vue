<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Bug, Copy, RotateCcw, Pause, Play } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { LogEntry, LogLevel, LogSource } from "@/api/types";
import { fmtRelative } from "@/composables/useFormat";

const ALL_SOURCES: LogSource[] = ["phone", "web", "backend", "wican"];
const ALL_LEVELS: LogLevel[] = ["debug", "info", "warn", "error"];

type TimeWindow = "5m" | "1h" | "24h" | "7d" | "all";
const TIME_WINDOWS: { value: TimeWindow; label: string }[] = [
  { value: "5m", label: "Last 5 min" },
  { value: "1h", label: "1 h" },
  { value: "24h", label: "24 h" },
  { value: "7d", label: "7 days" },
  { value: "all", label: "All" },
];

// Default selections: all sources, all levels except debug, 1h window, auto-refresh on.
const selectedSources = ref<LogSource[]>([...ALL_SOURCES]);
const selectedLevels = ref<LogLevel[]>(["info", "warn", "error"]);
const timeWindow = ref<TimeWindow>("1h");
const search = ref("");
const autoRefresh = ref(true);

const entries = ref<LogEntry[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const expanded = ref<Set<string | number>>(new Set());

const allSourcesSelected = computed(
  () => selectedSources.value.length === ALL_SOURCES.length,
);

function toggleAllSources() {
  if (allSourcesSelected.value) {
    selectedSources.value = [];
  } else {
    selectedSources.value = [...ALL_SOURCES];
  }
}

function toggleSource(s: LogSource) {
  if (selectedSources.value.includes(s)) {
    selectedSources.value = selectedSources.value.filter((v) => v !== s);
  } else {
    selectedSources.value = [...selectedSources.value, s];
  }
}

function toggleLevel(l: LogLevel) {
  if (selectedLevels.value.includes(l)) {
    selectedLevels.value = selectedLevels.value.filter((v) => v !== l);
  } else {
    selectedLevels.value = [...selectedLevels.value, l];
  }
}

function windowToFromIso(w: TimeWindow): string | undefined {
  if (w === "all") return undefined;
  const now = Date.now();
  const offsets: Record<Exclude<TimeWindow, "all">, number> = {
    "5m": 5 * 60 * 1000,
    "1h": 60 * 60 * 1000,
    "24h": 24 * 60 * 60 * 1000,
    "7d": 7 * 24 * 60 * 60 * 1000,
  };
  return new Date(now - offsets[w]).toISOString();
}

async function fetchLogs(): Promise<void> {
  // Don't surface a spinner on auto-refresh polls; only on the first load
  // (entries empty) and on manual reset.
  const showSpinner = entries.value.length === 0;
  if (showSpinner) loading.value = true;
  error.value = null;

  const params: Record<string, string | number> = { limit: 1000 };
  if (selectedSources.value.length > 0) {
    params.source = selectedSources.value.join(",");
  }
  if (selectedLevels.value.length > 0) {
    params.level = selectedLevels.value.join(",");
  }
  const from = windowToFromIso(timeWindow.value);
  if (from) params.from = from;
  const q = search.value.trim();
  if (q) params.q = q;

  try {
    const data = await api.listRecentLogs(params);
    entries.value = data;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "request failed";
  } finally {
    loading.value = false;
  }
}

// Auto-refresh — pause when the tab isn't visible.
let pollHandle: number | null = null;
function startPolling() {
  stopPolling();
  if (!autoRefresh.value) return;
  pollHandle = window.setInterval(() => {
    if (document.visibilityState === "visible") void fetchLogs();
  }, 5_000);
}
function stopPolling() {
  if (pollHandle !== null) {
    clearInterval(pollHandle);
    pollHandle = null;
  }
}

onMounted(() => {
  void fetchLogs();
  startPolling();
});
onBeforeUnmount(() => {
  stopPolling();
});

watch(autoRefresh, () => startPolling());
watch(
  [selectedSources, selectedLevels, timeWindow, search],
  () => {
    void fetchLogs();
  },
  { deep: true },
);

function resetFilters() {
  selectedSources.value = [...ALL_SOURCES];
  selectedLevels.value = ["info", "warn", "error"];
  timeWindow.value = "1h";
  search.value = "";
  void fetchLogs();
}

function levelClass(l: LogLevel): string {
  switch (l) {
    case "error":
      return "danger";
    case "warn":
      return "warn";
    case "info":
      return "success";
    default:
      return "";
  }
}

function sourceLabel(s: LogSource): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function toggleExpand(id: string | number) {
  const next = new Set(expanded.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  expanded.value = next;
}

function fmtContext(ctx: unknown): string {
  if (ctx == null) return "";
  try {
    return JSON.stringify(ctx, null, 2);
  } catch {
    return String(ctx);
  }
}

const copyState = ref<"idle" | "ok" | "err">("idle");
async function copyAsNdjson() {
  try {
    const ndjson = entries.value.map((e) => JSON.stringify(e)).join("\n");
    await navigator.clipboard.writeText(ndjson);
    copyState.value = "ok";
  } catch {
    copyState.value = "err";
  }
  setTimeout(() => {
    copyState.value = "idle";
  }, 1500);
}

const copyLabel = computed(() => {
  if (copyState.value === "ok") return "Copied";
  if (copyState.value === "err") return "Copy failed";
  return "Copy NDJSON";
});

const empty = computed(() => !loading.value && entries.value.length === 0);
</script>

<template>
  <div class="debug">
    <header class="head">
      <div class="title">
        <h1><Bug :size="20" /> Debug</h1>
        <span class="muted small">
          {{ entries.length }} {{ entries.length === 1 ? "entry" : "entries" }}
          <span v-if="error"> · <span class="err">error: {{ error }}</span></span>
        </span>
      </div>
      <div class="head-actions">
        <button type="button" class="ghost" @click="copyAsNdjson" :disabled="entries.length === 0">
          <Copy :size="14" /> {{ copyLabel }}
        </button>
        <button
          type="button"
          class="ghost"
          @click="autoRefresh = !autoRefresh"
          :title="autoRefresh ? 'Pause auto-refresh' : 'Resume auto-refresh'"
        >
          <component :is="autoRefresh ? Pause : Play" :size="14" />
          {{ autoRefresh ? "Pause" : "Resume" }}
        </button>
      </div>
    </header>

    <section class="card filters">
      <div class="filter-row">
        <span class="lbl">Source</span>
        <div class="chips">
          <button
            type="button"
            class="chip"
            :class="{ active: allSourcesSelected }"
            @click="toggleAllSources"
          >All</button>
          <button
            v-for="s in ALL_SOURCES"
            :key="s"
            type="button"
            class="chip"
            :class="{ active: selectedSources.includes(s) }"
            @click="toggleSource(s)"
          >{{ sourceLabel(s) }}</button>
        </div>
      </div>
      <div class="filter-row">
        <span class="lbl">Level</span>
        <div class="chips">
          <button
            v-for="l in ALL_LEVELS"
            :key="l"
            type="button"
            class="chip"
            :class="[{ active: selectedLevels.includes(l) }, levelClass(l)]"
            @click="toggleLevel(l)"
          >{{ l }}</button>
        </div>
      </div>
      <div class="filter-row">
        <span class="lbl">Time</span>
        <select v-model="timeWindow" class="time-select">
          <option v-for="w in TIME_WINDOWS" :key="w.value" :value="w.value">
            {{ w.label }}
          </option>
        </select>
        <input
          type="search"
          v-model="search"
          placeholder="Search messages…"
          class="search"
        />
      </div>
    </section>

    <div v-if="loading && entries.length === 0" class="card">
      <p class="muted">Loading logs…</p>
    </div>
    <div v-else-if="empty" class="card empty">
      <Bug :size="20" />
      <h3>No logs match your filters</h3>
      <p class="muted">
        Either the backend's <code>/api/logs/recent</code> endpoint isn't deployed
        yet, or your filters are too narrow.
      </p>
      <button type="button" @click="resetFilters">
        <RotateCcw :size="14" /> Reset filters
      </button>
    </div>
    <div v-else class="card no-pad">
      <table class="data logs">
        <thead>
          <tr>
            <th class="ts">When</th>
            <th class="src">Source</th>
            <th class="lvl">Level</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="e in entries" :key="e.id">
            <tr class="clickable row" @click="toggleExpand(e.id)">
              <td class="ts" :title="e.ts">{{ fmtRelative(e.ts) }}</td>
              <td class="src">
                <span class="badge">{{ sourceLabel(e.source) }}</span>
              </td>
              <td class="lvl">
                <span class="badge" :class="levelClass(e.level)">{{ e.level }}</span>
              </td>
              <td class="msg">
                <span class="msg-text">{{ e.message }}</span>
                <span v-if="e.device_id" class="muted small"> · {{ e.device_id }}</span>
              </td>
            </tr>
            <tr v-if="expanded.has(e.id)" class="ctx-row">
              <td colspan="4">
                <pre class="ctx">{{ fmtContext({
                  ts: e.ts,
                  client_ts: e.client_ts,
                  vehicle_id: e.vehicle_id,
                  device_id: e.device_id,
                  context: e.context,
                }) }}</pre>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.debug {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}
.title {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
}
.title h1 {
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}
.head-actions {
  display: flex;
  gap: 0.5rem;
}
.head-actions button {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}
.err {
  color: var(--c-danger);
}
.small {
  font-size: 0.78rem;
}

.filters {
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
}
.filter-row {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  flex-wrap: wrap;
}
.lbl {
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--c-muted);
  min-width: 56px;
}
.chips {
  display: flex;
  gap: 0.35rem;
  flex-wrap: wrap;
}
.chip {
  padding: 0.3rem 0.7rem;
  border-radius: 999px;
  font-size: 0.78rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  color: var(--c-muted);
  cursor: pointer;
  transition:
    background 0.12s,
    border-color 0.12s,
    color 0.12s;
}
.chip:hover {
  background: var(--c-surface-3);
  color: var(--c-text);
}
.chip.active {
  background: var(--c-accent-soft);
  border-color: rgba(47, 129, 247, 0.4);
  color: var(--c-accent);
}
.chip.active.warn {
  background: rgba(210, 153, 34, 0.14);
  border-color: rgba(210, 153, 34, 0.45);
  color: var(--c-warn);
}
.chip.active.danger {
  background: rgba(248, 81, 73, 0.14);
  border-color: rgba(248, 81, 73, 0.45);
  color: var(--c-danger);
}
.chip.active.success {
  background: rgba(63, 185, 80, 0.14);
  border-color: rgba(63, 185, 80, 0.45);
  color: var(--c-success);
}
.time-select {
  min-width: 140px;
}
.search {
  flex: 1;
  min-width: 180px;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  text-align: center;
  padding: 2.5rem 1rem;
}
.empty h3 {
  margin: 0;
}
.empty p {
  max-width: 36rem;
}
.empty button {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.no-pad {
  padding: 0;
  overflow: hidden;
}
table.logs {
  table-layout: fixed;
}
table.logs th.ts,
table.logs td.ts {
  width: 130px;
  white-space: nowrap;
}
table.logs th.src,
table.logs td.src {
  width: 110px;
}
table.logs th.lvl,
table.logs td.lvl {
  width: 90px;
}
table.logs td.msg {
  word-break: break-word;
}
.msg-text {
  font-family:
    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.85rem;
}
.ctx-row td {
  background: var(--c-surface-2);
  padding: 0;
}
.ctx {
  margin: 0;
  padding: 0.7rem 1rem;
  background: transparent;
  border: 0;
  font-size: 0.78rem;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
