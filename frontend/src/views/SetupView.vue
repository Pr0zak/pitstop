<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
} from "lucide-vue-next";
import { listVehicles, listProfiles } from "@/api/endpoints";
import { apiQuery } from "@/api";
import { useSettingsStore } from "@/stores/settings";
import type { Vehicle, Profile } from "@/api/types";

const settings = useSettingsStore();

const vehicles = ref<Vehicle[]>([]);
const profiles = ref<Profile[]>([]);
const fillupCount = ref<number | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

// localStorage flags for the optional manual steps.
const piholeDone = ref(loadFlag("setup_pihole_done"));
const haDone = ref(loadFlag("setup_ha_done"));

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

async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [v, p, fillups] = await Promise.allSettled([
      listVehicles(),
      listProfiles(),
      apiQuery.get("/fillups", { params: { limit: 1 } }),
    ]);
    if (v.status === "fulfilled") vehicles.value = v.value;
    if (p.status === "fulfilled") profiles.value = p.value;
    if (fillups.status === "fulfilled") {
      const total = fillups.value.headers["x-total-count"];
      fillupCount.value =
        typeof total === "string"
          ? parseInt(total, 10)
          : Array.isArray(fillups.value.data)
            ? fillups.value.data.length
            : 0;
    }
    await settings.fetchSettings();
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "fetch failed";
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const brokerHost = computed(() => window.location.hostname);
const apiBase = computed(
  () => `${window.location.protocol}//${window.location.host}`,
);

const profileHonda = computed(() =>
  profiles.value.find((p) => p.name === "honda-pilot-2019"),
);

// Step 1: any reading from any vehicle in the last 24h?
const step1Done = computed(() => {
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  return vehicles.value.some(
    (v) => v.last_seen_at && new Date(v.last_seen_at).getTime() >= cutoff,
  );
});

// Step 2: any vehicle has a profile bound AND has any reading.
const step2Done = computed(() =>
  vehicles.value.some(
    (v) =>
      v.pid_profile_id &&
      v.latest &&
      Object.keys(v.latest).length > 0,
  ),
);

// Step 3: any vehicle has 100+ readings (proxy via reading count is heavier; for now use last_seen_at recency).
const step3Done = computed(() => {
  const cutoff = Date.now() - 60 * 60 * 1000; // last hour
  return vehicles.value.some(
    (v) => v.last_seen_at && new Date(v.last_seen_at).getTime() >= cutoff,
  );
});

const step4Done = computed(() => (fillupCount.value ?? 0) > 0);

const step6Done = computed(
  () =>
    settings.settings?.ha?.enabled === true ||
    settings.settings?.ha?.url != null,
);

function copy(text: string) {
  void navigator.clipboard?.writeText(text);
}

async function downloadProfile() {
  if (!profileHonda.value) return;
  try {
    const r = await apiQuery.get<Profile & { profile?: unknown; body?: unknown }>(
      `/profiles/${profileHonda.value.id}`,
    );
    // Backend exposes the JSONB blob as `profile` (raw asyncpg row);
    // local type uses `body`. Accept either.
    const blob = new Blob(
      [JSON.stringify(r.data.profile ?? r.data.body, null, 2)],
      { type: "application/json" },
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "honda-pilot-2019.json";
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
        <h1><HelpCircle :size="22" /> Setup</h1>
        <p class="muted">
          Six steps from a fresh deploy to the full picture. Status badges
          flip green automatically as data lands; optional steps are remembered
          per browser.
        </p>
      </div>
      <button class="ghost" type="button" @click="load" :disabled="loading">
        Refresh
      </button>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>

    <!-- Step 1: WiCAN MQTT -->
    <section class="card step" :class="{ done: step1Done }">
      <div class="step-head">
        <div class="num">1</div>
        <Wifi :size="16" />
        <h3>Connect the WiCAN device to MQTT</h3>
        <span class="status">
          <Check v-if="step1Done" :size="14" /><Circle v-else :size="14" />
          {{ step1Done ? "Receiving telemetry" : "No vehicle has reported in 24 h" }}
        </span>
      </div>
      <p class="muted">
        Open the WiCAN web UI (typically <code>http://wican.local/</code> on
        your home Wi-Fi) → Settings → MQTT, and paste:
      </p>
      <div class="kv">
        <div class="row">
          <span class="lbl">Broker</span>
          <code>{{ brokerHost }}:1883</code>
          <button class="ghost" @click="copy(`${brokerHost}:1883`)">
            <Copy :size="12" />
          </button>
        </div>
        <div class="row">
          <span class="lbl">Username</span>
          <code>pitstop</code>
          <button class="ghost" @click="copy('pitstop')">
            <Copy :size="12" />
          </button>
        </div>
        <div class="row">
          <span class="lbl">Password</span>
          <code class="muted">paste from <code>~/.pitstop-deploy-secrets.txt</code></code>
        </div>
        <div class="row">
          <span class="lbl">Topic prefix</span>
          <code>wican/pilot19/</code>
          <button class="ghost" @click="copy('wican/pilot19/')">
            <Copy :size="12" />
          </button>
        </div>
      </div>
      <p class="muted small hint">
        <code>pilot19</code> is the suggested vehicle slug. Change it if you
        already created a different slug under <RouterLink to="/vehicles">Vehicles</RouterLink>.
      </p>
    </section>

    <!-- Step 2: AutoPID -->
    <section class="card step" :class="{ done: step2Done }">
      <div class="step-head">
        <div class="num">2</div>
        <FileJson :size="16" />
        <h3>Upload the AutoPID profile</h3>
        <span class="status">
          <Check v-if="step2Done" :size="14" /><Circle v-else :size="14" />
          {{ step2Done ? "Profile attached + parsing" : "No parsed metrics yet" }}
        </span>
      </div>
      <p class="muted">
        Download <code>honda-pilot-2019.json</code> and upload it in the
        WiCAN UI → Settings → AutoPID. Reboot the device when prompted.
      </p>
      <div class="actions">
        <button
          class="primary"
          type="button"
          :disabled="!profileHonda"
          @click="downloadProfile"
        >
          <Download :size="14" /> Download honda-pilot-2019.json
        </button>
        <RouterLink class="link" to="/profiles">
          Browse all profiles <ExternalLink :size="12" />
        </RouterLink>
      </div>
    </section>

    <!-- Step 3: drive -->
    <section class="card step" :class="{ done: step3Done }">
      <div class="step-head">
        <div class="num">3</div>
        <Car :size="16" />
        <h3>Take the first drive</h3>
        <span class="status">
          <Check v-if="step3Done" :size="14" /><Circle v-else :size="14" />
          {{ step3Done ? "Recent telemetry within the last hour" : "No driveway / drive data yet" }}
        </span>
      </div>
      <p class="muted">
        With the WiCAN configured, drive (or idle in the driveway). Within
        a minute the <RouterLink to="/live">Live</RouterLink> view should
        animate. Trip detector closes the trip 60 s after silence.
      </p>
      <div class="actions">
        <RouterLink class="link" to="/live">Open Live view <ExternalLink :size="12" /></RouterLink>
        <RouterLink class="link" to="/debug?source=backend&level=info">
          Tail backend logs <ExternalLink :size="12" />
        </RouterLink>
      </div>
    </section>

    <!-- Step 4: Fuelio -->
    <section class="card step" :class="{ done: step4Done }">
      <div class="step-head">
        <div class="num">4</div>
        <FuelIcon :size="16" />
        <h3>Import historical Fuelio data</h3>
        <span class="status">
          <Check v-if="step4Done" :size="14" /><Circle v-else :size="14" />
          {{
            step4Done
              ? `${fillupCount} fillups imported`
              : "No fillups in the database yet"
          }}
        </span>
      </div>
      <p class="muted">
        Drag your Fuelio export <code>.zip</code>(s) into the import page.
        Dry-run shows a preview before commit; re-importing the same file
        is a no-op (last-write-wins on <code>fuelio_guid</code>).
      </p>
      <div class="actions">
        <RouterLink class="primary link button" to="/fuel/import">
          Open Fuelio import →
        </RouterLink>
      </div>
    </section>

    <!-- Step 5: Pi-hole -->
    <section class="card step optional" :class="{ done: piholeDone }">
      <div class="step-head">
        <div class="num">5</div>
        <Network :size="16" />
        <h3>Pin the CT's IP in Pi-hole <span class="muted small">(optional)</span></h3>
        <span class="status">
          <Check v-if="piholeDone" :size="14" /><Circle v-else :size="14" />
          {{ piholeDone ? "Marked done" : "Manual confirmation" }}
        </span>
      </div>
      <p class="muted">
        DHCP usually re-assigns the same IP, but a static lease keeps things
        deterministic. Either set <code>PITSTOP_PIHOLE_PASSWORD</code> and re-run
        <code>deploy/pihole-reserve.sh {{ brokerHost }}</code>, or add the
        reservation manually in your DNS/DHCP UI.
      </p>
      <label class="cb">
        <input
          type="checkbox"
          :checked="piholeDone"
          @change="(e) => { piholeDone = (e.target as HTMLInputElement).checked; saveFlag('setup_pihole_done', piholeDone); }"
        />
        I've reserved <code>{{ brokerHost }}</code> for the pitstop CT
      </label>
    </section>

    <!-- Step 6: HA mirror -->
    <section class="card step optional" :class="{ done: step6Done }">
      <div class="step-head">
        <div class="num">6</div>
        <Home :size="16" />
        <h3>Home Assistant mirror <span class="muted small">(optional)</span></h3>
        <span class="status">
          <Check v-if="step6Done" :size="14" /><Circle v-else :size="14" />
          {{ step6Done ? "Configured" : "Not configured" }}
        </span>
      </div>
      <p class="muted">
        Plumbing is built but disabled by default
        (<RouterLink to="/settings">Settings → Home Assistant</RouterLink>).
        Flip the toggle, paste the long-lived token, click "Test connection",
        and pitstop will republish readings as MQTT discovery sensors.
      </p>
      <div class="actions">
        <RouterLink class="link" to="/settings">Open Settings <ExternalLink :size="12" /></RouterLink>
      </div>
    </section>

    <footer class="foot muted">
      Need to find the secrets? They live at
      <code>~/.pitstop-deploy-secrets.txt</code> (mode 0600). API base for the
      WiCAN device:
      <code>{{ brokerHost }}:1883</code> (LAN-only). Web base: <code>{{ apiBase }}</code>.
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
.banner.danger {
  background: rgba(248, 81, 73, 0.12);
  border: 1px solid rgba(248, 81, 73, 0.3);
  color: var(--c-danger);
  border-radius: var(--r-md);
  padding: 0.6rem 0.9rem;
}
.step {
  position: relative;
}
.step.done {
  border-color: rgba(63, 185, 80, 0.35);
}
.step.optional {
  border-style: dashed;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 0.5rem;
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
  background: rgba(63, 185, 80, 0.18);
  border-color: rgba(63, 185, 80, 0.45);
  color: var(--c-success);
}
.status {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.78rem;
  color: var(--c-muted);
  margin-left: auto;
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
