<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  HelpCircle,
  Wifi,
  FileJson,
  Car,
  FuelIcon,
  Network,
  Home,
  Check,
  Circle,
  Copy,
  ExternalLink,
  Download,
  ChevronDown,
} from "lucide-vue-next";
import { listVehicles, listProfiles } from "@/api/endpoints";
import { apiQuery } from "@/api";
import { useSettingsStore } from "@/stores/settings";
import { useVehiclesStore } from "@/stores/vehicles";
import { useToastStore } from "@/stores/toast";
import type { Vehicle, Profile } from "@/api/types";

const settings = useSettingsStore();
const vehiclesStore = useVehiclesStore();
const toast = useToastStore();

const vehicles = ref<Vehicle[]>([]);
const profiles = ref<Profile[]>([]);
const fillupCount = ref<number | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

// Which vehicle the instructions are for. Defaults to the global
// selection; changing it here doesn't touch the global picker.
const setupVehicleId = ref<string | null>(vehiclesStore.selectedVehicleId);
const vehicle = computed<Vehicle | null>(
  () => vehicles.value.find((v) => v.id === setupVehicleId.value) ?? vehicles.value[0] ?? null,
);

// localStorage flags for the optional manual steps.
const dhcpDone = ref(loadFlag("setup_pihole_done"));

function loadFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}
function saveFlag(key: string, v: boolean) {
  try {
    localStorage.setItem(key, v ? "1" : "0");
  } catch {
    /* ignore */
  }
}

async function loadFillupCount() {
  fillupCount.value = null;
  const vid = vehicle.value?.id;
  try {
    const r = await apiQuery.get("/fillups", { params: { limit: 1, ...(vid ? { vehicle_id: vid } : {}) } });
    const total = r.headers["x-total-count"];
    fillupCount.value =
      typeof total === "string" ? parseInt(total, 10) : Array.isArray(r.data) ? r.data.length : 0;
  } catch {
    /* leave unknown */
  }
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [v, p] = await Promise.allSettled([listVehicles(), listProfiles()]);
    if (v.status === "fulfilled") vehicles.value = v.value;
    if (p.status === "fulfilled") profiles.value = p.value;
    if (!setupVehicleId.value && vehicles.value[0]) setupVehicleId.value = vehicles.value[0].id;
    await Promise.all([loadFillupCount(), settings.fetchSettings()]);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "fetch failed";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(setupVehicleId, () => void loadFillupCount());

const brokerHost = computed(() => window.location.hostname);
const apiBase = computed(() => `${window.location.protocol}//${window.location.host}`);

/** Topic prefix and AutoPID profile come from the chosen vehicle — no
 *  hard-coded slug or profile file. */
const slug = computed(() => vehicle.value?.slug || "<vehicle-slug>");
const topicPrefix = computed(() => `wican/${slug.value}/`);
const boundProfile = computed<Profile | null>(() => {
  const v = vehicle.value;
  if (!v) return null;
  const id = v.pid_profile_id ?? v.pid_profile?.id ?? null;
  return profiles.value.find((p) => p.id === id) ?? (v.pid_profile ? (v.pid_profile as Profile) : null);
});
const pickedProfileId = ref<string>("");
const downloadProfileTarget = computed<Profile | null>(
  () => boundProfile.value ?? profiles.value.find((p) => p.id === pickedProfileId.value) ?? null,
);

const recent = (iso: string | null | undefined, ms: number) =>
  !!iso && Date.now() - new Date(iso).getTime() <= ms;

// Step 1: the vehicle has reported in the last 24 h.
const step1Done = computed(() => recent(vehicle.value?.last_seen_at, 24 * 3600_000));
// Step 2: a profile is bound and at least one metric has been parsed.
const step2Done = computed(
  () => !!boundProfile.value && !!vehicle.value?.latest && Object.keys(vehicle.value.latest).length > 0,
);
// Step 3: telemetry within the last hour.
const step3Done = computed(() => recent(vehicle.value?.last_seen_at, 3600_000));
const step4Done = computed(() => (fillupCount.value ?? 0) > 0);
const step6Done = computed(
  () => settings.settings?.ha?.enabled === true || settings.settings?.ha?.url != null,
);
const doneFlags = computed(() => [
  step1Done.value,
  step2Done.value,
  step3Done.value,
  step4Done.value,
  dhcpDone.value,
  step6Done.value,
]);
const doneCount = computed(() => doneFlags.value.filter(Boolean).length);

// Completed steps collapse; the header toggles them open again.
const expanded = ref<Record<number, boolean>>({});
function isOpen(n: number): boolean {
  return expanded.value[n] ?? !doneFlags.value[n - 1];
}
function toggle(n: number) {
  expanded.value = { ...expanded.value, [n]: !isOpen(n) };
}

function copy(text: string) {
  void navigator.clipboard?.writeText(text).then(
    () => toast.success("Copied"),
    () => toast.error("Clipboard unavailable"),
  );
}

async function downloadProfile() {
  const prof = downloadProfileTarget.value;
  if (!prof) return;
  try {
    const r = await apiQuery.get<Profile & { profile?: unknown; body?: unknown }>(`/profiles/${prof.id}`);
    // Backend exposes the JSONB blob as `profile`; local type uses `body`.
    const blob = new Blob([JSON.stringify(r.data.profile ?? r.data.body, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${prof.name}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "download failed";
  }
}
</script>

<template>
  <div class="setup">
    <header class="head">
      <div>
        <h1><HelpCircle :size="22" aria-hidden="true" /> Setup</h1>
        <p class="muted">
          Six steps from a fresh deploy to the full picture. Status flips automatically as data
          lands; completed steps fold away.
        </p>
      </div>
      <div class="head-side">
        <span class="progress num" role="status">{{ doneCount }} / 6 done</span>
        <button class="ghost" type="button" @click="load" :disabled="loading">Refresh</button>
      </div>
    </header>
    <div class="progress-bar" aria-hidden="true">
      <span :style="{ width: (doneCount / 6) * 100 + '%' }"></span>
    </div>

    <label v-if="vehicles.length > 1" class="vehicle-sel">
      <span class="muted small">Setting up</span>
      <select v-model="setupVehicleId">
        <option v-for="v in vehicles" :key="v.id" :value="v.id">{{ v.name }} ({{ v.slug }})</option>
      </select>
    </label>
    <div v-else-if="vehicles.length === 0 && !loading" class="banner">
      No vehicles yet — <RouterLink to="/vehicles">add one</RouterLink> first; its slug becomes the MQTT topic.
    </div>

    <div v-if="error" class="banner danger" role="alert">{{ error }}</div>

    <!-- Step 1: WiCAN MQTT -->
    <section class="card step" :class="{ done: step1Done }">
      <button type="button" class="step-head" :aria-expanded="isOpen(1)" @click="toggle(1)">
        <span class="num">1</span>
        <Wifi :size="16" aria-hidden="true" />
        <h3>Connect the WiCAN device to MQTT</h3>
        <span class="status">
          <Check v-if="step1Done" :size="14" /><Circle v-else :size="14" />
          {{ step1Done ? "Receiving telemetry" : "Not reported in 24 h" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(1) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(1)" class="step-body">
        <p class="muted">
          Open the WiCAN web UI (typically <code>http://wican.local/</code> on your home Wi-Fi) →
          Settings → MQTT, and paste:
        </p>
        <div class="kv">
          <div class="row">
            <span class="lbl">Broker</span>
            <code>{{ brokerHost }}:1883</code>
            <button class="ghost" type="button" aria-label="Copy broker address" @click="copy(`${brokerHost}:1883`)">
              <Copy :size="12" />
            </button>
          </div>
          <div class="row">
            <span class="lbl">Username</span>
            <code>pitstop</code>
            <button class="ghost" type="button" aria-label="Copy username" @click="copy('pitstop')">
              <Copy :size="12" />
            </button>
          </div>
          <div class="row">
            <span class="lbl">Password</span>
            <code class="muted">from your deploy secrets file</code>
          </div>
          <div class="row">
            <span class="lbl">Topic prefix</span>
            <code>{{ topicPrefix }}</code>
            <button class="ghost" type="button" aria-label="Copy topic prefix" @click="copy(topicPrefix)">
              <Copy :size="12" />
            </button>
          </div>
        </div>
        <p class="muted small hint">
          The prefix is <strong>{{ vehicle?.name ?? "the vehicle" }}</strong>'s slug. Change it under
          <RouterLink to="/vehicles">Vehicles</RouterLink> if needed.
        </p>
      </div>
    </section>

    <!-- Step 2: AutoPID -->
    <section class="card step" :class="{ done: step2Done }">
      <button type="button" class="step-head" :aria-expanded="isOpen(2)" @click="toggle(2)">
        <span class="num">2</span>
        <FileJson :size="16" aria-hidden="true" />
        <h3>Upload the AutoPID profile</h3>
        <span class="status">
          <Check v-if="step2Done" :size="14" /><Circle v-else :size="14" />
          {{ step2Done ? "Profile attached + parsing" : boundProfile ? "No parsed metrics yet" : "No profile bound" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(2) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(2)" class="step-body">
        <p v-if="boundProfile" class="muted">
          {{ vehicle?.name }} uses <code>{{ boundProfile.name }}</code>. Download it and upload it in
          the WiCAN UI → Settings → AutoPID, then reboot the device when prompted.
        </p>
        <template v-else>
          <p class="muted">
            {{ vehicle?.name ?? "This vehicle" }} has no PID profile bound. Pick one to download, and
            bind it on the <RouterLink to="/profiles">Profiles</RouterLink> page.
          </p>
          <select v-model="pickedProfileId" aria-label="Profile to download" class="profile-sel">
            <option value="">— choose a profile —</option>
            <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </template>
        <div class="actions">
          <button class="primary" type="button" :disabled="!downloadProfileTarget" @click="downloadProfile">
            <Download :size="14" aria-hidden="true" />
            Download {{ downloadProfileTarget ? `${downloadProfileTarget.name}.json` : "profile" }}
          </button>
          <RouterLink class="link" to="/profiles">Browse all profiles <ExternalLink :size="12" /></RouterLink>
        </div>
      </div>
    </section>

    <!-- Step 3: drive -->
    <section class="card step" :class="{ done: step3Done }">
      <button type="button" class="step-head" :aria-expanded="isOpen(3)" @click="toggle(3)">
        <span class="num">3</span>
        <Car :size="16" aria-hidden="true" />
        <h3>Take the first drive</h3>
        <span class="status">
          <Check v-if="step3Done" :size="14" /><Circle v-else :size="14" />
          {{ step3Done ? "Telemetry within the last hour" : "No recent drive data" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(3) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(3)" class="step-body">
        <p class="muted">
          With the WiCAN configured, drive (or idle in the driveway). Within a minute the
          <RouterLink to="/live">Live</RouterLink> view should animate. Trips are derived a few
          minutes after the drive ends.
        </p>
        <div class="actions">
          <RouterLink class="link" to="/live">Open Live view <ExternalLink :size="12" /></RouterLink>
          <RouterLink class="link" to="/debug?source=backend&level=info">
            Tail backend logs <ExternalLink :size="12" />
          </RouterLink>
        </div>
      </div>
    </section>

    <!-- Step 4: Fuelio -->
    <section class="card step" :class="{ done: step4Done }">
      <button type="button" class="step-head" :aria-expanded="isOpen(4)" @click="toggle(4)">
        <span class="num">4</span>
        <FuelIcon :size="16" aria-hidden="true" />
        <h3>Import historical Fuelio data</h3>
        <span class="status">
          <Check v-if="step4Done" :size="14" /><Circle v-else :size="14" />
          {{ step4Done ? `${fillupCount} fillups` : "No fillups yet" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(4) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(4)" class="step-body">
        <p class="muted">
          Drag your Fuelio export <code>.zip</code>(s) into the import page. Dry-run shows a
          preview before commit; re-importing the same file is a no-op.
        </p>
        <div class="actions">
          <RouterLink class="primary link button" to="/fuel/import">Open Fuelio import →</RouterLink>
        </div>
      </div>
    </section>

    <!-- Step 5: DHCP reservation -->
    <section class="card step optional" :class="{ done: dhcpDone }">
      <button type="button" class="step-head" :aria-expanded="isOpen(5)" @click="toggle(5)">
        <span class="num">5</span>
        <Network :size="16" aria-hidden="true" />
        <h3>Reserve the server's IP <span class="muted small">(optional)</span></h3>
        <span class="status">
          <Check v-if="dhcpDone" :size="14" /><Circle v-else :size="14" />
          {{ dhcpDone ? "Marked done" : "Manual" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(5) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(5)" class="step-body">
        <p class="muted">
          DHCP usually re-assigns the same address, but a static lease keeps the WiCAN's broker
          setting valid. Add a reservation for <code>{{ brokerHost }}</code> in your router or
          DNS/DHCP server.
        </p>
        <label class="cb">
          <input
            type="checkbox"
            :checked="dhcpDone"
            @change="(e) => { dhcpDone = (e.target as HTMLInputElement).checked; saveFlag('setup_pihole_done', dhcpDone); }"
          />
          I've reserved <code>{{ brokerHost }}</code> for the pitstop server
        </label>
      </div>
    </section>

    <!-- Step 6: HA mirror -->
    <section class="card step optional" :class="{ done: step6Done }">
      <button type="button" class="step-head" :aria-expanded="isOpen(6)" @click="toggle(6)">
        <span class="num">6</span>
        <Home :size="16" aria-hidden="true" />
        <h3>Home Assistant mirror <span class="muted small">(optional)</span></h3>
        <span class="status">
          <Check v-if="step6Done" :size="14" /><Circle v-else :size="14" />
          {{ step6Done ? "Configured" : "Not configured" }}
        </span>
        <ChevronDown :size="14" class="chev" :class="{ open: isOpen(6) }" aria-hidden="true" />
      </button>
      <div v-if="isOpen(6)" class="step-body">
        <p class="muted">
          Plumbing is built but disabled by default
          (<RouterLink to="/settings#integrations">Settings → Integrations</RouterLink>). Flip the
          toggle, paste the long-lived token, test the connection, and pitstop republishes readings
          as MQTT discovery sensors.
        </p>
        <div class="actions">
          <RouterLink class="link" to="/settings#integrations">Open Settings <ExternalLink :size="12" /></RouterLink>
        </div>
      </div>
    </section>

    <footer class="foot muted">
      WiCAN broker: <code>{{ brokerHost }}:1883</code> (LAN-only). Web base: <code>{{ apiBase }}</code>.
    </footer>
  </div>
</template>

<style scoped>
.setup {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-width: 920px;
}
.head {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
}
.head > div {
  flex: 1;
}
.head h1 {
  margin: 0 0 0.3rem 0;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}
.head-side {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.progress {
  font-size: 0.9rem;
  color: var(--c-ink1);
}
.progress-bar {
  height: 3px;
  border-radius: 2px;
  background: var(--c-bg3);
  overflow: hidden;
  margin-top: -0.4rem;
}
.progress-bar span {
  display: block;
  height: 100%;
  background: var(--c-success);
  transition: width 200ms;
}
.vehicle-sel {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.banner {
  background: var(--c-bg2);
  border: 1px solid var(--c-line1);
  border-radius: var(--r-md);
  padding: 0.6rem 0.9rem;
}
.banner.danger {
  background: var(--c-danger-soft);
  border: 1px solid rgba(255, 58, 46, 0.3);
  color: var(--c-danger);
}
.step-body {
  margin-top: 0.5rem;
}
.chev {
  color: var(--c-ink3);
  transform: rotate(-90deg);
  transition: transform 120ms;
}
.chev.open {
  transform: none;
}
.profile-sel {
  margin-bottom: 0.3rem;
}
.step {
  position: relative;
}
.step.done {
  border-color: rgba(74, 222, 128, 0.3);
}
.step.optional {
  border-style: dashed;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  width: 100%;
  background: none;
  border: 0;
  padding: 0;
  text-align: left;
  color: inherit;
  font-weight: inherit;
}
.step-head:hover:not(:disabled) {
  background: none;
}
.step-head h3 {
  margin: 0;
  flex: 1;
  text-transform: none;
  letter-spacing: 0;
  color: var(--c-text);
  font-size: 0.95rem;
  font-weight: 600;
}
.num {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 0.8rem;
  color: var(--c-muted);
  flex-shrink: 0;
}
.step.done .num {
  background: var(--c-success-soft);
  border-color: rgba(74, 222, 128, 0.45);
  color: var(--c-success);
}
.status {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.78rem;
  color: var(--c-muted);
  margin-left: auto;
  white-space: nowrap;
}
@media (max-width: 700px) {
  .head {
    flex-direction: column;
  }
  .step-head {
    flex-wrap: wrap;
  }
  .step-head h3 {
    flex-basis: calc(100% - 80px);
  }
}
.step.done .status {
  color: var(--c-success);
}
.kv {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  margin: 0.6rem 0;
}
.kv .row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
}
.kv .lbl {
  width: 90px;
  color: var(--c-muted);
  text-transform: uppercase;
  font-size: 0.72rem;
  letter-spacing: 0.05em;
}
.kv code {
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  padding: 0.15rem 0.45rem;
  border-radius: var(--r-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  align-items: center;
  margin-top: 0.7rem;
}
.actions .link {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.85rem;
}
.actions .button {
  padding: 0.4rem 0.8rem;
  border-radius: var(--r-sm);
  background: var(--c-accent);
  color: white;
  text-decoration: none;
}
.cb {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  margin-top: 0.6rem;
  font-size: 0.85rem;
  color: var(--c-text);
}
.hint {
  margin-top: 0.4rem;
}
.foot {
  font-size: 0.8rem;
  margin-top: 0.5rem;
}
.small {
  font-size: 0.75rem;
}
</style>
