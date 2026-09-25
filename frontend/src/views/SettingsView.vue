<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, watch, nextTick } from "vue";
import { useRoute, RouterLink } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useSettingsStore } from "@/stores/settings";
import { useUnitsStore, type UnitSystem } from "@/stores/units";
import { Save, Plug, RefreshCw, MapPin, Link as LinkIcon, HardDrive, Trash2, Undo2, Bug, Palette } from "lucide-vue-next";
import HondaLinkTest from "@/components/HondaLinkTest.vue";
import { useToastStore, errMessage } from "@/stores/toast";
import type { Settings } from "@/api/types";
import HomeLocationPicker from "@/components/HomeLocationPicker.vue";
import UpdateModal from "@/components/UpdateModal.vue";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import { parseLatLon, roundCoords } from "@/utils/parseLatLon";
import { apiQuery } from "@/api";
import * as api from "@/api/endpoints";
import type { StorageStats } from "@/api/endpoints";

const route = useRoute();
const auth = useAuthStore();
const settings = useSettingsStore();
const toast = useToastStore();

// Left anchor nav. Order is the page order.
const SECTIONS = [
  { id: "about", label: "About" },
  { id: "access", label: "Access" },
  { id: "units", label: "Units" },
  { id: "home", label: "Home" },
  { id: "integrations", label: "Integrations" },
  { id: "devices", label: "Devices" },
  { id: "storage", label: "Storage" },
  { id: "developer", label: "Developer" },
] as const;
const activeSection = ref<string>("about");
let sectionObserver: IntersectionObserver | null = null;
onMounted(async () => {
  await nextTick();
  if (typeof IntersectionObserver !== "undefined") {
    sectionObserver = new IntersectionObserver(
      (entries) => {
        const vis = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (vis[0]) activeSection.value = vis[0].target.id;
      },
      { rootMargin: "-80px 0px -60% 0px" },
    );
    for (const sct of SECTIONS) {
      const el = document.getElementById(sct.id);
      if (el) sectionObserver.observe(el);
    }
  }
  const hash = route.hash?.slice(1);
  if (hash) document.getElementById(hash)?.scrollIntoView();
});
onBeforeUnmount(() => sectionObserver?.disconnect());

const localQueryToken = ref("");
const localIngestToken = ref("");
const tokensSaved = ref(false);

const serverVersion = ref<string | null>(null);
const serverSha = ref<string | null>(null);
const updateModalOpen = ref(false);
async function loadServerVersion() {
  try {
    const v = await api.getVersion();
    serverVersion.value = v.version;
    serverSha.value = v.git_sha;
  } catch {
    /* ignore */
  }
}

const haEnabled = ref(false);
const haUrl = ref("");
const haToken = ref(""); // empty means "don't change"
const haDiscoveryPrefix = ref("homeassistant");
const haTestStatus = ref<"idle" | "ok" | "fail" | "running">("idle");
const haTestMsg = ref<string | null>(null);

const homeLat = ref<number | null>(null);
const homeLon = ref<number | null>(null);
const diskAlertPct = ref<number | null>(null);
const retentionReadingsDays = ref<number | null>(null);
const retentionLogsDays = ref<number | null>(null);
const retentionLogsDebugDays = ref<number | null>(null);

const units = useUnitsStore();
function setUnits(u: UnitSystem) {
  units.setPreference(u);
}

const saveStatus = ref<"idle" | "saving" | "saved" | "error">("idle");
const saveError = ref<string | null>(null);

const reasonHint = computed(() => {
  const r = route.query.reason;
  if (r === "auth_query") return "Your QUERY token was rejected (401). Please re-enter.";
  if (r === "auth_ingest") return "Your INGEST token was rejected (401). Please re-enter.";
  return null;
});

/** Server-backed form fields, and the snapshot they were loaded from —
 *  the save bar appears only when the two differ. */
function formState() {
  return {
    haEnabled: haEnabled.value,
    haUrl: haUrl.value,
    haDiscoveryPrefix: haDiscoveryPrefix.value,
    haToken: haToken.value,
    homeLat: homeLat.value,
    homeLon: homeLon.value,
    diskAlertPct: diskAlertPct.value,
    retentionReadingsDays: retentionReadingsDays.value,
    retentionLogsDays: retentionLogsDays.value,
    retentionLogsDebugDays: retentionLogsDebugDays.value,
  };
}
const snapshot = ref<string>(JSON.stringify(formState()));
// v-model.number turns a cleared field into "" — treat that as null.
const norm = (v: unknown) => (v === "" || v === undefined ? null : v);
function normState(st: ReturnType<typeof formState>) {
  return Object.fromEntries(Object.entries(st).map(([k, v]) => [k, norm(v)]));
}
const dirty = computed(() => JSON.stringify(normState(formState())) !== JSON.stringify(normState(JSON.parse(snapshot.value))));

function applyFromServer(s: Settings) {
  haEnabled.value = s.ha?.enabled ?? false;
  haUrl.value = s.ha?.url ?? "";
  haDiscoveryPrefix.value = s.ha?.discovery_prefix ?? "homeassistant";
  haToken.value = "";
  homeLat.value = s.home?.lat ?? null;
  homeLon.value = s.home?.lon ?? null;
  diskAlertPct.value = s.disk_alert_pct ?? null;
  retentionReadingsDays.value = s.retention_readings_days ?? null;
  retentionLogsDays.value = s.retention_logs_days ?? null;
  retentionLogsDebugDays.value = s.retention_logs_debug_days ?? null;
  snapshot.value = JSON.stringify(formState());
}
function discardChanges() {
  if (settings.settings) applyFromServer(settings.settings);
}

onMounted(async () => {
  void loadServerVersion();
  localQueryToken.value = auth.queryToken;
  localIngestToken.value = auth.ingestToken;
  if (auth.hasQueryToken) {
    await settings.fetchSettings();
    await Promise.all([loadStorage(), loadDevices()]);
  }
});

// Reload the form whenever the server copy changes — but never clobber
// unsaved edits (the pre-v0.1.83 form-init race class).
watch(
  () => settings.settings,
  (s) => {
    if (s && !dirty.value) applyFromServer(s);
  },
  { immediate: true },
);

function saveTokens() {
  auth.setQueryToken(localQueryToken.value);
  auth.setIngestToken(localIngestToken.value);
  tokensSaved.value = true;
  setTimeout(() => (tokensSaved.value = false), 2_000);
  toast.success("Tokens saved in this browser");
  void settings.fetchSettings();
}

async function saveAll() {
  saveStatus.value = "saving";
  saveError.value = null;
  try {
    const payload: {
      ha: {
        enabled: boolean;
        url: string | null;
        discovery_prefix: string;
        token?: string | null;
      };
      home: { lat: number | null; lon: number | null };
      disk_alert_pct: number | null;
      retention_readings_days: number | null;
      retention_logs_days: number | null;
      retention_logs_debug_days: number | null;
    } = {
      ha: {
        enabled: haEnabled.value,
        url: haUrl.value || null,
        discovery_prefix: haDiscoveryPrefix.value,
      },
      home: { lat: homeLat.value, lon: homeLon.value },
      disk_alert_pct: diskAlertPct.value,
      retention_readings_days: retentionReadingsDays.value,
      retention_logs_days: retentionLogsDays.value,
      retention_logs_debug_days: retentionLogsDebugDays.value,
    };
    if (haToken.value) {
      payload.ha.token = haToken.value;
    }
    // patchSettings expects partial Settings; the wire shape uses ha.token (separate from token_set).
    // We cast at the call site since the type definition models the read shape.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await settings.patchSettings(payload as any);
    haToken.value = ""; // clear after save
    if (settings.settings) applyFromServer(settings.settings);
    else snapshot.value = JSON.stringify(formState());
    saveStatus.value = "saved";
    toast.success("Settings saved");
    setTimeout(() => (saveStatus.value = "idle"), 2_000);
  } catch (e: unknown) {
    saveStatus.value = "error";
    saveError.value = errMessage(e, "save failed");
  }
}

async function testHa() {
  haTestStatus.value = "running";
  haTestMsg.value = null;
  try {
    const r = await settings.testHa();
    if (r.ok) {
      haTestStatus.value = "ok";
      haTestMsg.value = `OK${r.status_code ? " (" + r.status_code + ")" : ""}`;
    } else {
      haTestStatus.value = "fail";
      haTestMsg.value = `Failed${r.status_code ? " (" + r.status_code + ")" : ""}`;
    }
  } catch (e: unknown) {
    haTestStatus.value = "fail";
    haTestMsg.value = e instanceof Error ? e.message : "test failed";
  }
}

const showPicker = ref(false);
const shareLink = ref("");
const shareLinkStatus = ref<"idle" | "ok" | "fail">("idle");
const shareLinkMsg = ref<string | null>(null);

async function applyShareLink() {
  let working = shareLink.value.trim();
  let parsed = parseLatLon(working);

  // Short Google / Apple / OSM links → backend follows the redirect for us.
  if (
    !parsed &&
    /^https:\/\/(maps\.app\.goo\.gl|goo\.gl|g\.co|g\.page|apple\.co|osm\.org)\//i.test(
      working,
    )
  ) {
    shareLinkStatus.value = "idle";
    shareLinkMsg.value = "Resolving short link…";
    try {
      const r = await apiQuery.get<{ resolved: string; hops: number }>(
        "/utils/resolve-url",
        { params: { url: working } },
      );
      working = r.data.resolved;
      parsed = parseLatLon(working);
    } catch (e: unknown) {
      shareLinkStatus.value = "fail";
      shareLinkMsg.value =
        e instanceof Error ? `Resolver failed: ${e.message}` : "Resolver failed";
      return;
    }
  }

  if (!parsed) {
    shareLinkStatus.value = "fail";
    shareLinkMsg.value =
      "Could not extract lat/lon. Paste a Google Maps long URL (the kind with @LAT,LON in it), a short `maps.app.goo.gl/...` link, or a `LAT, LON` pair.";
    return;
  }
  const r = roundCoords(parsed.lat, parsed.lon);
  homeLat.value = r.lat;
  homeLon.value = r.lon;
  shareLinkStatus.value = "ok";
  shareLinkMsg.value = `Set to ${r.lat.toFixed(5)}, ${r.lon.toFixed(5)}`;
  shareLink.value = "";
  setTimeout(() => {
    shareLinkStatus.value = "idle";
    shareLinkMsg.value = null;
  }, 4_000);
}

function onPicked(lat: number, lon: number) {
  homeLat.value = lat;
  homeLon.value = lon;
  showPicker.value = false;
}

// ── Devices: WiCAN → vehicle mapping ────────────────────────────────
//
// Backed by /admin/devices. Mapped devices have a row in
// device_vehicle_map that the ingest worker consults when the topic's
// slug doesn't match a vehicle directly. Unmapped devices are MAC-style
// topic ids the worker has been dropping (parsed from backend log
// warnings); user picks a vehicle from the dropdown to assign.

const devices = ref<api.DeviceMapping[]>([]);
const devicesLoading = ref(false);
const devicesError = ref<string | null>(null);
const allVehicles = ref<{ id: string; slug: string; name: string }[]>([]);

async function loadDevices() {
  if (!auth.hasQueryToken) return;
  devicesLoading.value = true;
  devicesError.value = null;
  try {
    const [list, vs] = await Promise.all([
      api.listDevices(),
      api.listVehicles(),
    ]);
    devices.value = list;
    allVehicles.value = vs.map((v) => ({ id: v.id, slug: v.slug, name: v.name }));
  } catch (e: unknown) {
    devicesError.value = e instanceof Error ? e.message : "load failed";
  } finally {
    devicesLoading.value = false;
  }
}

async function assignDevice(deviceId: string, vehicleId: string) {
  if (!vehicleId) return;
  try {
    await api.mapDevice(deviceId, vehicleId);
    toast.success(`Mapped ${deviceId}`);
    await loadDevices();
  } catch (e: unknown) {
    devicesError.value = e instanceof Error ? e.message : "assign failed";
  }
}

// Unmap device — in-app confirm instead of native window.confirm.
const unmapTarget = ref<string | null>(null);
function requestUnassign(deviceId: string) {
  unmapTarget.value = deviceId;
}
async function confirmUnassign() {
  const deviceId = unmapTarget.value;
  if (!deviceId) return;
  unmapTarget.value = null;
  try {
    await api.unmapDevice(deviceId);
    await loadDevices();
  } catch (e: unknown) {
    devicesError.value = e instanceof Error ? e.message : "unmap failed";
  }
}

// ── Storage / data retention ────────────────────────────────────────
//
// Backed by /admin/storage (read) + /admin/purge/readings (mutating).
// Purge is two-step: first call returns the projected delete count, the
// user confirms, second call commits. Keeps a misclick from dropping a
// year of readings.

const storageStats = ref<StorageStats | null>(null);
const storageLoading = ref(false);
const storageError = ref<string | null>(null);
const purgeAgeDays = ref<number>(90);
const purgePreview = ref<{ rows: number; cutoff: string } | null>(null);
const purgeBusy = ref(false);
const purgeMessage = ref<string | null>(null);

async function loadStorage() {
  if (!auth.hasQueryToken) return;
  storageLoading.value = true;
  storageError.value = null;
  try {
    storageStats.value = await api.getStorageStats();
  } catch (e: unknown) {
    storageError.value = e instanceof Error ? e.message : "load failed";
  } finally {
    storageLoading.value = false;
  }
}

function humanBytes(b: number | null | undefined): string {
  if (b == null || b === 0) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  let v = Math.abs(b);
  let i = 0;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${u[i]}`;
}

async function previewPurge() {
  if (purgeBusy.value) return;
  purgeBusy.value = true;
  purgeMessage.value = null;
  try {
    const r = await api.purgeReadings(purgeAgeDays.value, false);
    purgePreview.value = {
      rows: r.rows_to_delete ?? 0,
      cutoff: r.cutoff_iso,
    };
  } catch (e: unknown) {
    purgeMessage.value = e instanceof Error ? e.message : "preview failed";
  } finally {
    purgeBusy.value = false;
  }
}

// Commit purge — in-app confirm gate instead of native window.confirm.
const confirmPurgeOpen = ref(false);
function requestCommitPurge() {
  if (purgeBusy.value || !purgePreview.value) return;
  confirmPurgeOpen.value = true;
}
async function commitPurge() {
  confirmPurgeOpen.value = false;
  if (purgeBusy.value || !purgePreview.value) return;
  purgeBusy.value = true;
  purgeMessage.value = null;
  try {
    const r = await api.purgeReadings(purgeAgeDays.value, true);
    purgeMessage.value = `Deleted ${(r.rows_deleted ?? 0).toLocaleString()} readings.`;
    purgePreview.value = null;
    await loadStorage();
  } catch (e: unknown) {
    purgeMessage.value = e instanceof Error ? e.message : "purge failed";
  } finally {
    purgeBusy.value = false;
  }
}

function geolocate() {
  if (!("geolocation" in navigator)) {
    toast.error("Geolocation isn't available in this browser");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      // round to 5 decimal places (~1.1m precision) to avoid storing extra noise
      homeLat.value = Math.round(pos.coords.latitude * 1e5) / 1e5;
      homeLon.value = Math.round(pos.coords.longitude * 1e5) / 1e5;
    },
    (err) => toast.error(`Geolocation failed: ${err.message}`),
    { enableHighAccuracy: false, timeout: 10_000 },
  );
}
</script>

<template>
  <div class="settings-page">
    <nav class="anchor-nav" aria-label="Settings sections">
      <a
        v-for="sct in SECTIONS"
        :key="sct.id"
        :href="`#${sct.id}`"
        :class="{ active: activeSection === sct.id }"
        :aria-current="activeSection === sct.id ? 'location' : undefined"
      >{{ sct.label }}</a>
    </nav>

    <div class="settings">
      <h1>Settings</h1>

      <div v-if="reasonHint" class="banner warn" role="alert">{{ reasonHint }}</div>

      <section id="about" class="card">
        <h3>About <span class="saves">read-only</span></h3>
        <div class="about-row">
          <div>
            <div class="muted small">Running version</div>
            <div class="version-line">
              <span class="version-number">{{ serverVersion ?? '—' }}</span>
              <span v-if="serverSha && serverSha !== 'unknown'" class="muted small">
                · {{ serverSha.slice(0, 7) }}
              </span>
            </div>
          </div>
          <button type="button" class="check-updates" @click="updateModalOpen = true">
            <RefreshCw :size="14" aria-hidden="true" /> Check for updates
          </button>
        </div>
        <p class="muted small">
          Compares the deployed backend to the latest GitHub release. The Upgrade button (in the
          modal) pulls the new images and recreates backend + frontend containers — no SSH needed.
        </p>
      </section>

      <UpdateModal :open="updateModalOpen" @close="updateModalOpen = false" />

      <section id="access" class="card">
        <h3>Access tokens <span class="saves">this browser · Save tokens</span></h3>
        <p class="muted">
          The query token is used for read endpoints and the live websocket; the ingest token for
          writes. Kept in this browser's localStorage only.
        </p>
        <div class="grid two">
          <label>
            QUERY token
            <input type="password" v-model="localQueryToken" autocomplete="off" placeholder="paste QUERY_TOKEN" />
          </label>
          <label>
            INGEST token
            <input type="password" v-model="localIngestToken" autocomplete="off" placeholder="paste INGEST_TOKEN" />
          </label>
        </div>
        <div class="actions">
          <button class="primary" type="button" @click="saveTokens">
            <Save :size="14" aria-hidden="true" /> Save tokens
          </button>
          <span v-if="tokensSaved" class="muted" role="status">Saved.</span>
        </div>
      </section>

      <section id="units" class="card">
        <h3>Display units <span class="saves">this browser · applies instantly</span></h3>
        <p class="muted">
          How values are rendered. <strong>Auto</strong> follows the selected vehicle's Fuelio unit
          codes. Storage is unchanged — this is render-only.
        </p>
        <div class="seg" role="radiogroup" aria-label="Display units">
          <button type="button" role="radio" :aria-checked="units.preference === 'auto'" :class="{ active: units.preference === 'auto' }" @click="setUnits('auto')">
            Auto <small class="muted">(currently {{ units.resolved }})</small>
          </button>
          <button type="button" role="radio" :aria-checked="units.preference === 'metric'" :class="{ active: units.preference === 'metric' }" @click="setUnits('metric')">
            Metric <small class="muted">km / L / °C</small>
          </button>
          <button type="button" role="radio" :aria-checked="units.preference === 'imperial'" :class="{ active: units.preference === 'imperial' }" @click="setUnits('imperial')">
            Imperial <small class="muted">mi / gal / °F</small>
          </button>
        </div>
      </section>

      <section id="home" class="card">
        <h3>Home location <span class="saves">server · save bar</span></h3>
        <p class="muted">
          Used for "trips that started/ended at home" and to centre the stations map.
        </p>
        <div class="grid two">
          <label>
            Latitude
            <input id="home-lat" type="number" step="0.00001" v-model.number="homeLat" />
          </label>
          <label>
            Longitude
            <input id="home-lon" type="number" step="0.00001" v-model.number="homeLon" />
          </label>
        </div>
        <div class="actions">
          <button type="button" @click="showPicker = true">
            <MapPin :size="14" aria-hidden="true" /> Pick on map
          </button>
          <button type="button" @click="geolocate">Use current location</button>
        </div>
        <div class="share-row">
          <label>
            <span class="row-label"><LinkIcon :size="12" aria-hidden="true" /> Paste shared link or coords</span>
            <div class="share-input-row">
              <input
                type="text"
                v-model="shareLink"
                placeholder="https://www.google.com/maps/…/@40.71,-74.00,15z … or 40.71, -74.00"
                @keydown.enter.prevent="applyShareLink"
              />
              <button type="button" @click="applyShareLink" :disabled="!shareLink.trim()">Apply</button>
            </div>
            <small v-if="shareLinkStatus === 'ok'" class="badge success">{{ shareLinkMsg }}</small>
            <small v-else-if="shareLinkStatus === 'fail'" class="muted warn-text">{{ shareLinkMsg }}</small>
            <small v-else-if="shareLinkMsg" class="muted">{{ shareLinkMsg }}</small>
            <small v-else class="muted">
              Google Maps long &amp; short URLs (incl. <code>maps.app.goo.gl/…</code>), Apple Maps,
              OpenStreetMap, or a plain <code>lat, lon</code> pair.
            </small>
          </label>
        </div>
      </section>

      <HomeLocationPicker
        v-if="showPicker"
        :initial-lat="homeLat"
        :initial-lon="homeLon"
        @pick="onPicked"
        @cancel="showPicker = false"
      />

      <section id="integrations" class="card">
        <h3>Integrations</h3>
        <div class="sub">
          <h4>Home Assistant mirror <span class="saves">server · save bar</span></h4>
          <p class="muted">
            Built but disabled by default. When enabled, the backend re-publishes readings as MQTT
            discovery sensors so HA picks them up automatically.
          </p>
          <label class="cb">
            <input type="checkbox" v-model="haEnabled" />
            Enable HA mirror
          </label>
          <div class="grid two" :class="{ dim: !haEnabled }">
            <label>
              HA URL
              <input type="url" v-model="haUrl" placeholder="http://homeassistant.local:8123" />
            </label>
            <label>
              Discovery prefix
              <input v-model="haDiscoveryPrefix" placeholder="homeassistant" />
            </label>
            <label class="full">
              Long-lived token
              <input type="password" v-model="haToken" placeholder="leave blank to keep current" autocomplete="off" />
              <small v-if="settings.settings?.ha?.token_set" class="muted">(token currently set on server)</small>
            </label>
          </div>
          <div class="actions">
            <button type="button" @click="testHa" :disabled="!haEnabled || !haUrl">
              <Plug :size="14" aria-hidden="true" /> Test connection
            </button>
            <span v-if="haTestStatus === 'ok'" class="badge success">{{ haTestMsg }}</span>
            <span v-else-if="haTestStatus === 'fail'" class="badge danger">{{ haTestMsg }}</span>
            <span v-else-if="haTestStatus === 'running'" class="muted"><RefreshCw :size="12" aria-hidden="true" /> testing…</span>
          </div>
        </div>
        <div class="sub">
          <h4>HondaLink connection test <span class="saves">nothing is saved</span></h4>
          <HondaLinkTest />
        </div>
      </section>

      <section id="devices" class="card">
        <h3>
          Devices <span class="saves">applies instantly</span>
          <button
            type="button"
            class="ghost refresh"
            @click="loadDevices"
            :disabled="devicesLoading"
          >
            <RefreshCw :size="12" aria-hidden="true" /> {{ devicesLoading ? "…" : "Refresh" }}
          </button>
        </h3>
        <p class="muted">
          Tie a WiCAN OBD device (or a phone bridge) to a vehicle so its published metrics route
          correctly. Devices <strong>without</strong> a vehicle have been dropping messages — pick
          one to start ingesting.
        </p>

      <div v-if="devicesError" class="banner warn">{{ devicesError }}</div>

      <div v-if="devices.length > 0" class="table-scroll">
      <table class="data" style="margin-top: 0.5rem">
        <thead>
          <tr>
            <th>Device ID</th>
            <th>Kind</th>
            <th>Vehicle</th>
            <th>Last seen</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in devices" :key="d.device_id">
            <td><code>{{ d.device_id }}</code></td>
            <td>
              <span class="badge">{{ d.kind ?? "unknown" }}</span>
              <span
                v-if="!d.mapped"
                class="badge warn"
                style="margin-left: 0.3rem"
              >
                {{ d.warn_count ?? "?" }} drops/24h
              </span>
            </td>
            <td>
              <select
                :aria-label="`Vehicle for ${d.device_id}`"
                :value="d.vehicle_id ?? ''"
                @change="(e) => assignDevice(d.device_id, (e.target as HTMLSelectElement).value)"
              >
                <option value="" disabled>— pick vehicle —</option>
                <option
                  v-for="v in allVehicles"
                  :key="v.id"
                  :value="v.id"
                >
                  {{ v.name }}
                </option>
              </select>
            </td>
            <td>
              <code v-if="d.last_seen_at">
                {{ d.last_seen_at.slice(0, 19).replace("T", " ") }}
              </code>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <button
                v-if="d.mapped"
                class="ghost"
                type="button"
                @click="requestUnassign(d.device_id)"
                title="Unmap"
                :aria-label="`Unmap ${d.device_id}`"
              >
                <Trash2 :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <p v-else class="muted small">
        No devices seen yet. Once your WiCAN starts publishing it'll
        appear here.
      </p>
      </section>

      <section id="storage" class="card">
        <h3>
          <HardDrive :size="14" aria-hidden="true" /> Storage
          <span class="saves">retention: save bar · purge: immediate</span>
          <button type="button" class="ghost refresh" @click="loadStorage" :disabled="storageLoading">
            <RefreshCw :size="12" aria-hidden="true" /> {{ storageLoading ? "…" : "Refresh" }}
          </button>
        </h3>
        <p class="muted">
          Database size and the oldest data. Purge drops OBD readings beyond a chosen age after a
          preview.
        </p>
        <label class="inline-num">
          Disk alert threshold (%)
          <input type="number" min="0" max="100" v-model.number="diskAlertPct" placeholder="80" />
        </label>

      <div v-if="storageError" class="banner warn">{{ storageError }}</div>

      <div v-if="storageStats" class="table-scroll">
      <table class="data" style="margin-top: 0.6rem">
        <thead>
          <tr>
            <th>Table</th>
            <th class="num">Rows</th>
            <th class="num">Size</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in storageStats.tables" :key="t.table">
            <td>
              <code>{{ t.table }}</code>
              <span v-if="t.is_hypertable" class="badge" style="margin-left: 0.4rem">
                hypertable
              </span>
            </td>
            <td class="num">{{ t.rows != null ? t.rows.toLocaleString() : "—" }}</td>
            <td class="num">{{ humanBytes(t.size_bytes) }}</td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <td><strong>Total DB size</strong></td>
            <td></td>
            <td class="num"><strong>{{ humanBytes(storageStats.total_size_bytes) }}</strong></td>
          </tr>
        </tfoot>
      </table>
      </div>

      <p
        v-if="storageStats?.oldest_reading_at"
        class="muted small"
        style="margin-top: 0.4rem"
      >
        Oldest OBD reading: <code>{{ storageStats.oldest_reading_at.slice(0, 19).replace("T", " ") }}</code>
      </p>

      <div class="purge">
        <h4>Auto-purge schedule</h4>
        <p class="muted small">
          Backend cron runs hourly. Leave a field blank (or zero) to disable
          auto-purge for that stream — the manual buttons below still work.
        </p>
        <div class="purge-row">
          <label>
            Drop OBD readings older than
            <input
              type="number"
              min="0"
              max="3650"
              v-model.number="retentionReadingsDays"
              placeholder="off"
            />
            days
          </label>
        </div>
        <div class="purge-row" style="margin-top: 0.4rem">
          <label>
            Drop debug logs older than
            <input
              type="number"
              min="0"
              max="3650"
              v-model.number="retentionLogsDebugDays"
              placeholder="off"
            />
            days
          </label>
          <span class="muted small">Debug dominates volume — keep this short.</span>
        </div>
        <div class="purge-row" style="margin-top: 0.4rem">
          <label>
            Drop other logs (warn/info/error) older than
            <input
              type="number"
              min="0"
              max="3650"
              v-model.number="retentionLogsDays"
              placeholder="off"
            />
            days
          </label>
          <span class="muted small">Saved with the save bar</span>
        </div>

        <h4 style="margin-top: 1rem">Manual purge</h4>
        <div class="purge-row">
          <label>
            Older than
            <input
              type="number"
              min="1"
              max="3650"
              v-model.number="purgeAgeDays"
            />
            days
          </label>
          <button type="button" @click="previewPurge" :disabled="purgeBusy">
            Preview
          </button>
          <button
            type="button"
            class="danger"
            @click="requestCommitPurge"
            :disabled="purgeBusy || !purgePreview"
          >
            <Trash2 :size="14" aria-hidden="true" /> Commit purge
          </button>
        </div>
        <p v-if="purgePreview" class="muted small">
          Would delete <strong>{{ purgePreview.rows.toLocaleString() }}</strong> reading(s)
          before <code>{{ purgePreview.cutoff.slice(0, 19).replace("T", " ") }}</code>.
        </p>
        <p v-if="purgeMessage" class="muted small">{{ purgeMessage }}</p>
      </div>
      </section>

      <section id="developer" class="card">
        <h3>Developer</h3>
        <ul class="dev-links">
          <li>
            <RouterLink to="/debug"><Bug :size="14" aria-hidden="true" /> Debug &amp; logs</RouterLink>
            <span class="muted small">Live log tail across phone, web, backend and WiCAN.</span>
          </li>
          <li>
            <RouterLink to="/logos"><Palette :size="14" aria-hidden="true" /> Logo concepts</RouterLink>
            <span class="muted small">Pick the sidebar mark (saved in this browser).</span>
          </li>
          <li>
            <RouterLink to="/hondalink-test"><Plug :size="14" aria-hidden="true" /> HondaLink test (standalone page)</RouterLink>
          </li>
        </ul>
      </section>

    <ConfirmDialog
      :open="unmapTarget != null"
      :title="unmapTarget ? `Unmap ${unmapTarget}?` : 'Unmap device?'"
      message="Future readings from this device will be dropped until you remap it."
      confirm-label="Unmap"
      tone="danger"
      @confirm="confirmUnassign"
      @cancel="unmapTarget = null"
    />
    <ConfirmDialog
      :open="confirmPurgeOpen"
      title="Delete old readings?"
      :message="
        purgePreview
          ? `Delete ${purgePreview.rows.toLocaleString()} reading(s) older than ${purgeAgeDays} days? This cannot be undone.`
          : null
      "
      confirm-label="Delete"
      tone="danger"
      :busy="purgeBusy"
      @confirm="commitPurge"
      @cancel="confirmPurgeOpen = false"
    />
    </div>

    <!-- Sticky save bar: only while server-backed fields differ from what
         was loaded. Covers HA, home, disk alert and all retention fields. -->
    <Transition name="savebar">
      <div v-if="dirty || saveStatus === 'error'" class="save-bar" role="region" aria-label="Unsaved changes">
        <span class="msg">
          <template v-if="saveStatus === 'error'">Save failed: {{ saveError }}</template>
          <template v-else>Unsaved changes</template>
        </span>
        <button type="button" class="ghost" :disabled="saveStatus === 'saving'" @click="discardChanges">
          <Undo2 :size="14" aria-hidden="true" /> Discard
        </button>
        <button class="primary" type="button" @click="saveAll" :disabled="saveStatus === 'saving'">
          <Save :size="14" aria-hidden="true" />
          {{ saveStatus === "saving" ? "Saving…" : "Save changes" }}
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.settings-page {
  display: grid;
  grid-template-columns: 160px minmax(0, 820px);
  gap: 1.5rem;
  align-items: start;
  padding-bottom: 4.5rem;
}
.anchor-nav {
  position: sticky;
  top: calc(var(--topbar-h) + 1rem);
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-top: 3rem;
}
.anchor-nav a {
  color: var(--c-ink2);
  padding: 0.35rem 0.6rem;
  border-radius: var(--r-sm);
  font-size: 0.88rem;
  border-left: 2px solid transparent;
}
.anchor-nav a:hover {
  text-decoration: none;
  color: var(--c-ink0);
  background: var(--c-bg2);
}
.anchor-nav a.active {
  color: var(--c-ink0);
  border-left-color: var(--c-accent);
}
.settings {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  min-width: 0;
}
.settings section {
  scroll-margin-top: calc(var(--topbar-h) + 0.75rem);
}
.card h3 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.saves {
  text-transform: none;
  letter-spacing: 0;
  font-weight: 400;
  font-size: 0.75rem;
  color: var(--c-ink3);
  border: 1px solid var(--c-line1);
  border-radius: 999px;
  padding: 0 0.5rem;
}
.refresh {
  margin-left: auto;
  font-size: 0.78rem;
  text-transform: none;
  letter-spacing: 0;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}
.sub + .sub {
  margin-top: 1.2rem;
  padding-top: 1rem;
  border-top: 1px solid var(--c-line0);
}
.sub h4 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  font-size: 0.95rem;
  margin-bottom: 0.4rem;
}
.inline-num {
  flex-direction: row;
  align-items: center;
  gap: 0.6rem;
  margin-top: 0.4rem;
}
.inline-num input {
  width: 90px;
}
.dev-links {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.dev-links li {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}
.dev-links a {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
}
.table-scroll {
  overflow-x: auto;
}
.save-bar {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 1rem;
  z-index: 60;
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.55rem 0.6rem 0.55rem 1rem;
  background: var(--c-bg3);
  border: 1px solid var(--c-line2);
  border-radius: var(--r-lg);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
  max-width: calc(100vw - 2rem);
}
.save-bar .msg {
  font-size: 0.9rem;
  color: var(--c-ink1);
  margin-right: 0.5rem;
}
.save-bar button {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}
.savebar-enter-active,
.savebar-leave-active {
  transition: opacity 150ms, transform 150ms;
}
.savebar-enter-from,
.savebar-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}
@media (max-width: 900px) {
  .settings-page {
    grid-template-columns: minmax(0, 1fr);
    gap: 0.6rem;
  }
  .anchor-nav {
    position: sticky;
    top: var(--topbar-h);
    z-index: 5;
    flex-direction: row;
    overflow-x: auto;
    padding: 0.4rem 0;
    background: var(--c-bg);
    order: 0;
  }
  .anchor-nav a {
    border-left: 0;
    border-bottom: 2px solid transparent;
    white-space: nowrap;
  }
  .anchor-nav a.active {
    border-bottom-color: var(--c-accent);
  }
}
@media (max-width: 700px) {
  .save-bar {
    bottom: calc(var(--tabbar-h) + 0.75rem + env(safe-area-inset-bottom));
  }
  .grid.two {
    grid-template-columns: 1fr;
  }
}
.banner {
  border-radius: var(--r-md);
  padding: 0.6rem 0.9rem;
}
.banner.warn {
  background: var(--c-warn-soft);
  border: 1px solid rgba(255, 176, 32, 0.3);
  color: var(--c-warn);
}
.about-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin: 0.3rem 0 0.6rem 0;
}
.about-row .check-updates {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  white-space: nowrap;
}
.version-line {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 1.1rem;
  font-weight: 600;
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
}
.version-number {
  color: var(--c-ink0);
}
.small {
  font-size: 0.78rem;
}
.grid {
  display: grid;
  gap: 0.7rem;
  margin-top: 0.4rem;
}
.grid.two {
  grid-template-columns: 1fr 1fr;
}
.grid label.full {
  grid-column: 1 / -1;
}
.grid.dim {
  opacity: 0.55;
}
label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: var(--c-muted);
}
label.cb {
  flex-direction: row;
  align-items: center;
  gap: 0.4rem;
  margin-top: 0.4rem;
}
.actions {
  margin-top: 0.7rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.actions button {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}

small {
  font-size: 0.75rem;
}
.purge {
  margin-top: 0.9rem;
  padding-top: 0.8rem;
  border-top: 1px solid var(--c-line0);
}
.purge h4 {
  margin: 0 0 0.5rem 0;
  font-size: 0.9rem;
  font-weight: 600;
}
.purge-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
.purge-row input[type="number"] {
  width: 80px;
  text-align: right;
}
.seg {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-top: 0.7rem;
}
.seg button {
  display: inline-flex;
  align-items: baseline;
  gap: 0.4rem;
  padding: 0.5rem 0.8rem;
}
.seg button.active {
  border-color: var(--c-accent);
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.share-row {
  margin-top: 0.7rem;
}
.share-row .row-label {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}
.share-input-row {
  display: flex;
  gap: 0.4rem;
}
.share-input-row input {
  flex: 1;
}
.warn-text {
  color: var(--c-warn);
}
.grid.two > label {
  min-width: 0;
}
.grid.two input {
  width: 100%;
  min-width: 0;
}
@media (max-width: 700px) {
  .grid.two {
    grid-template-columns: minmax(0, 1fr);
  }
}
.purge-row label {
  flex-direction: row;
  align-items: center;
  gap: 0.4rem;
}
</style>
