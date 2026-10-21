<script setup lang="ts">
// PID Lab: one-shot Mode 22 reads through the WiCAN's firmware-6 test endpoint,
// to verify candidate signals against the dash before they are wired into
// capture. The backend refuses anything but service 0x22. Results live in
// this page's session log only; "Download log" saves them as fixtures.
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { Check, Download, FlaskConical, Play, RefreshCw, Repeat, X } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { PidLabDevice, PidLabProbe, PidLabResult } from "@/api/endpoints";

interface LogEntry {
  at: string;
  probe: string;
  title: string;
  result: PidLabResult;
  verdict: "match" | "mismatch" | null;
  note: string;
}

const URL_KEY = "pitstop.pidlab.wicanUrl";

const probes = ref<PidLabProbe[]>([]);
const baseUrl = ref("");
const device = ref<PidLabDevice | null>(null);
const deviceBusy = ref(false);
const loadError = ref<string | null>(null);
const busy = ref<string | null>(null);
const latest = ref<Record<string, PidLabResult>>({});
const log = ref<LogEntry[]>([]);
const watching = ref<string | null>(null);
let watchTimer: ReturnType<typeof setInterval> | null = null;

const customModule = ref("10");
const customData = ref("22 ");

const groups = computed(() => {
  const out = new Map<string, PidLabProbe[]>();
  for (const p of probes.value) {
    out.set(p.module, [...(out.get(p.module) ?? []), p]);
  }
  return [...out.entries()];
});

const fw = computed(() => {
  const info = device.value?.info as { fw_version?: string } | null | undefined;
  return info?.fw_version ?? null;
});
const isV6 = computed(() => !!fw.value && /^6\./.test(fw.value));

function readStoredUrl(): string {
  try {
    return localStorage.getItem(URL_KEY) ?? "";
  } catch {
    return "";
  }
}
function storeUrl() {
  try {
    localStorage.setItem(URL_KEY, baseUrl.value.trim());
  } catch {
    /* private window: the field still works for this session */
  }
}

function errText(e: unknown): string {
  const r = (e as { response?: { data?: { detail?: string } } }).response;
  return r?.data?.detail ?? (e as Error).message ?? String(e);
}

async function checkDevice() {
  deviceBusy.value = true;
  storeUrl();
  try {
    device.value = await api.getPidLabDevice(baseUrl.value.trim());
    loadError.value = null;
  } catch (e) {
    device.value = null;
    loadError.value = errText(e);
  } finally {
    deviceBusy.value = false;
  }
}

async function run(probe: PidLabProbe | null) {
  const key = probe?.id ?? "custom";
  busy.value = key;
  storeUrl();
  let result: PidLabResult;
  try {
    result = await api.runPidLab(
      probe
        ? { probe: probe.id, base_url: baseUrl.value.trim() || undefined }
        : {
            module_id: customModule.value.trim(),
            data: customData.value.trim(),
            base_url: baseUrl.value.trim() || undefined,
          },
    );
  } catch (e) {
    result = {
      ok: false,
      request: { tx_id: "", rx_id: "", data: probe ? `22 ${probe.did}` : customData.value },
      error: errText(e),
    };
  } finally {
    busy.value = null;
  }
  latest.value = { ...latest.value, [key]: result };
  log.value.unshift({
    at: new Date().toISOString(),
    probe: key,
    title: probe?.title ?? `custom ${customModule.value} ${customData.value}`,
    result,
    verdict: null,
    note: "",
  });
}

function toggleWatch(probe: PidLabProbe) {
  stopWatch();
  if (watching.value === probe.id) {
    watching.value = null;
    return;
  }
  watching.value = probe.id;
  run(probe);
  watchTimer = setInterval(() => {
    if (!busy.value) run(probe);
  }, 3000);
}
function stopWatch() {
  if (watchTimer) clearInterval(watchTimer);
  watchTimer = null;
}

function setVerdict(entry: LogEntry, v: "match" | "mismatch") {
  entry.verdict = entry.verdict === v ? null : v;
}

function fmtValue(s: { value: number | null; unit: string; label: string | null }): string {
  if (s.value == null) return "—";
  const v = s.label ? `${s.label} (${s.value})` : String(s.value);
  return s.unit ? `${v} ${s.unit}` : v;
}

function downloadLog() {
  const blob = new Blob(
    [JSON.stringify({ firmware: fw.value, exported_at: new Date().toISOString(), entries: log.value }, null, 2)],
    { type: "application/json" },
  );
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `pidlab-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
}

onMounted(async () => {
  try {
    const c = await api.getPidLabCatalog();
    probes.value = c.probes;
    baseUrl.value = readStoredUrl() || c.wican_url;
  } catch (e) {
    loadError.value = errText(e);
  }
});
onBeforeUnmount(stopWatch);
</script>

<template>
  <div class="pidlab">
    <p class="crumb muted small">
      <RouterLink to="/settings#developer">Settings › Developer</RouterLink>
    </p>
    <h1><FlaskConical :size="20" aria-hidden="true" /> PID Lab</h1>
    <p class="muted intro">
      One-shot Mode 22 reads through the WiCAN, to check a candidate signal against the dash
      before it goes into capture. Read-only: only service 22 is ever sent. Needs WiCAN firmware 6,
      which returns the whole response. Engine running (the WiCAN sleeps otherwise). Each read
      briefly pauses the WiCAN's own polling.
    </p>

    <section class="card">
      <h3>Dongle</h3>
      <div class="row">
        <label class="grow">
          WiCAN address
          <input v-model="baseUrl" placeholder="http://wican.lan" autocomplete="off" @keyup.enter="checkDevice" />
        </label>
        <button type="button" :disabled="deviceBusy" @click="checkDevice">
          <RefreshCw :size="14" :class="{ spin: deviceBusy }" aria-hidden="true" /> Check
        </button>
      </div>
      <p v-if="loadError" class="badge danger">{{ loadError }}</p>
      <dl v-if="device" class="facts">
        <dt>Firmware</dt>
        <dd>
          {{ fw ?? "—" }}
          <span v-if="fw && !isV6" class="badge warn">not 6.x: probes will fail</span>
          <span v-if="device.errors?.info" class="badge danger">{{ device.errors.info }}</span>
        </dd>
        <dt>AutoPID live params</dt>
        <dd>
          {{ device.autopid_live_params }}
          <span class="muted small">re-check after probing: this number must not drop</span>
        </dd>
      </dl>
    </section>

    <section v-for="[module, list] in groups" :key="module" class="card">
      <h3>{{ module }} <span class="muted small mono">{{ list[0].tx_id }}</span></h3>
      <div v-for="p in list" :key="p.id" class="probe">
        <div class="probe-head">
          <div>
            <strong>{{ p.title }}</strong>
            <span class="mono muted small">22 {{ p.did }}</span>
            <span v-if="p.verified" class="badge success">verified on this car</span>
          </div>
          <div class="btns">
            <button type="button" class="primary" :disabled="busy !== null" @click="run(p)">
              <Play :size="14" aria-hidden="true" /> Read
            </button>
            <button
              type="button"
              :class="{ active: watching === p.id }"
              :aria-pressed="watching === p.id"
              title="Read every 3 s"
              @click="toggleWatch(p)"
            >
              <Repeat :size="14" aria-hidden="true" /> {{ watching === p.id ? "Stop" : "Watch" }}
            </button>
          </div>
        </div>
        <p class="muted small check">{{ p.check }}</p>
        <div v-if="latest[p.id]" class="result" aria-live="polite">
          <template v-if="latest[p.id].ok">
            <table class="signals">
              <tbody>
                <tr v-for="s in latest[p.id].signals" :key="s.name">
                  <td class="mono">{{ s.name }}</td>
                  <td class="val">{{ fmtValue(s) }}</td>
                  <td class="muted small">payload[{{ s.payload_index }}]</td>
                </tr>
              </tbody>
            </table>
          </template>
          <p v-else class="badge danger">{{ latest[p.id].error }}</p>
          <p v-if="latest[p.id].response" class="mono raw">
            {{ latest[p.id].response }}
            <span class="muted">({{ latest[p.id].length }} bytes, {{ latest[p.id].elapsed_ms }} ms)</span>
          </p>
        </div>
      </div>
    </section>

    <section class="card">
      <h3>Custom read</h3>
      <p class="muted small">Module byte of <span class="mono">18DA<em>mm</em>F1</span> and a request starting with 22.</p>
      <div class="row">
        <label>
          Module
          <input v-model="customModule" class="mono narrow" maxlength="2" />
        </label>
        <label class="grow">
          Request
          <input v-model="customData" class="mono" placeholder="22 70 2A" @keyup.enter="run(null)" />
        </label>
        <button type="button" class="primary" :disabled="busy !== null" @click="run(null)">
          <Play :size="14" aria-hidden="true" /> Read
        </button>
      </div>
      <div v-if="latest.custom" class="result">
        <p v-if="!latest.custom.ok" class="badge danger">{{ latest.custom.error }}</p>
        <p v-if="latest.custom.response" class="mono raw">{{ latest.custom.response }}</p>
      </div>
    </section>

    <section class="card">
      <div class="probe-head">
        <h3>Session log <span class="muted small">{{ log.length }} reads</span></h3>
        <button type="button" :disabled="!log.length" @click="downloadLog">
          <Download :size="14" aria-hidden="true" /> Download log
        </button>
      </div>
      <p class="muted small">
        Mark each read against what the dash shows. The download keeps raw responses, so a matching
        read can become a test fixture. It can hold the odometer; keep it out of the public repo.
      </p>
      <ul class="log">
        <li v-for="(e, i) in log" :key="i">
          <span class="muted small mono">{{ e.at.slice(11, 19) }}</span>
          <span class="title">{{ e.title }}</span>
          <span v-if="e.result.ok" class="small">
            {{ (e.result.signals ?? []).map((s) => `${s.name}=${fmtValue(s)}`).join(", ") || e.result.response }}
          </span>
          <span v-else class="badge danger">{{ e.result.error }}</span>
          <span class="verdict">
            <button
              type="button"
              class="ghost"
              :class="{ on: e.verdict === 'match' }"
              title="Matches the dash"
              @click="setVerdict(e, 'match')"
            >
              <Check :size="14" aria-hidden="true" />
            </button>
            <button
              type="button"
              class="ghost"
              :class="{ off: e.verdict === 'mismatch' }"
              title="Does not match"
              @click="setVerdict(e, 'mismatch')"
            >
              <X :size="14" aria-hidden="true" />
            </button>
            <input v-model="e.note" class="note" placeholder="dash shows…" />
          </span>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.pidlab {
  max-width: 900px;
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
}
.crumb,
h1,
h3 {
  margin: 0;
}
h1 {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.intro {
  margin: 0;
  max-width: 70ch;
}
.row {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
  flex-wrap: wrap;
}
.row label {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
.grow {
  flex: 1 1 14rem;
}
.narrow {
  width: 4rem;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.3rem 1rem;
  margin: 0.6rem 0 0;
}
.facts dt {
  color: var(--c-text-2, inherit);
  opacity: 0.75;
}
.facts dd {
  margin: 0;
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  align-items: center;
}
.probe {
  border-top: 1px solid var(--c-line);
  padding: 0.6rem 0;
}
.probe:first-of-type {
  border-top: none;
}
.probe-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.probe-head > div:first-child {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.btns {
  display: flex;
  gap: 0.4rem;
}
button.active {
  border-color: var(--c-accent);
  color: var(--c-accent);
}
.check {
  margin: 0.3rem 0 0;
}
.result {
  margin-top: 0.4rem;
}
.signals {
  border-collapse: collapse;
}
.signals td {
  padding: 0.15rem 1rem 0.15rem 0;
}
.val {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.raw {
  font-size: 0.78rem;
  overflow-wrap: anywhere;
  margin: 0.3rem 0 0;
  opacity: 0.85;
}
.log {
  list-style: none;
  padding: 0;
  margin: 0.4rem 0 0;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.log li {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.log .title {
  font-weight: 600;
}
.verdict {
  margin-left: auto;
  display: flex;
  gap: 0.2rem;
  align-items: center;
}
.verdict .on {
  color: var(--c-success);
}
.verdict .off {
  color: var(--c-danger);
}
.note {
  width: 10rem;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
