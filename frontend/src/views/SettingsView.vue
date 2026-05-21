<script setup lang="ts">
import { ref, onMounted, computed, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useSettingsStore } from "@/stores/settings";
import { useUnitsStore, type UnitSystem } from "@/stores/units";
import { Save, Plug, RefreshCw, MapPin, Link as LinkIcon, HardDrive, Trash2 } from "lucide-vue-next";
import HomeLocationPicker from "@/components/HomeLocationPicker.vue";
import UpdateModal from "@/components/UpdateModal.vue";
import { parseLatLon, roundCoords } from "@/utils/parseLatLon";
import { apiQuery } from "@/api";
import * as api from "@/api/endpoints";
import type { StorageStats } from "@/api/endpoints";

const route = useRoute();
const auth = useAuthStore();
const settings = useSettingsStore();

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

onMounted(async () => {
  void loadServerVersion();
  localQueryToken.value = auth.queryToken;
  localIngestToken.value = auth.ingestToken;
  if (auth.hasQueryToken) {
    await settings.fetchSettings();
    if (settings.settings) {
      const s = settings.settings;
      haEnabled.value = s.ha?.enabled ?? false;
      haUrl.value = s.ha?.url ?? "";
      haDiscoveryPrefix.value = s.ha?.discovery_prefix ?? "homeassistant";
      homeLat.value = s.home?.lat ?? null;
      homeLon.value = s.home?.lon ?? null;
      diskAlertPct.value = s.disk_alert_pct ?? null;
      retentionReadingsDays.value = s.retention_readings_days ?? null;
      retentionLogsDays.value = s.retention_logs_days ?? null;
      retentionLogsDebugDays.value = s.retention_logs_debug_days ?? null;
    }
    await loadStorage();
    await loadDevices();
  }
});

watch(
  () => settings.settings,
  (s) => {
    if (!s) return;
    haEnabled.value = s.ha?.enabled ?? false;
    haUrl.value = s.ha?.url ?? "";
    haDiscoveryPrefix.value = s.ha?.discovery_prefix ?? "homeassistant";
    homeLat.value = s.home?.lat ?? null;
    homeLon.value = s.home?.lon ?? null;
    diskAlertPct.value = s.disk_alert_pct ?? null;
  },
);

function saveTokens() {
  auth.setQueryToken(localQueryToken.value);
  auth.setIngestToken(localIngestToken.value);
  tokensSaved.value = true;
  setTimeout(() => (tokensSaved.value = false), 2_000);
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
    saveStatus.value = "saved";
    setTimeout(() => (saveStatus.value = "idle"), 2_000);
  } catch (e: unknown) {
    saveStatus.value = "error";
    saveError.value = e instanceof Error ? e.message : "save failed";
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
    await loadDevices();
  } catch (e: unknown) {
    devicesError.value = e instanceof Error ? e.message : "assign failed";
  }
}

async function unassignDevice(deviceId: string) {
  if (!window.confirm(`Unmap ${deviceId}? Future readings will be dropped until you remap it.`)) {
    return;
  }
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

async function commitPurge() {
  if (purgeBusy.value || !purgePreview.value) return;
  if (
    !window.confirm(
      `Delete ${purgePreview.value.rows.toLocaleString()} reading(s) older than ${purgeAgeDays.value} days? This cannot be undone.`,
    )
  ) {
    return;
  }
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
    alert("Geolocation not available in this browser");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      // round to 5 decimal places (~1.1m precision) to avoid storing extra noise
      homeLat.value = Math.round(pos.coords.latitude * 1e5) / 1e5;
      homeLon.value = Math.round(pos.coords.longitude * 1e5) / 1e5;
    },
    (err) => alert(`Geolocation failed: ${err.message}`),
    { enableHighAccuracy: false, timeout: 10_000 },
  );
}
</script>

<template>
  <div class="settings">
    <h1>Settings</h1>

    <div v-if="reasonHint" class="banner warn">{{ reasonHint }}</div>

    <section class="card">
      <h3>About</h3>
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
          <RefreshCw :size="14" /> Check for updates
        </button>
      </div>
      <p class="muted small">
        Compares the deployed backend to the latest GitHub release. The
        Upgrade button (in the modal) pulls the new images and recreates
        backend + frontend containers — no SSH needed.
      </p>
    </section>

    <UpdateModal :open="updateModalOpen" @close="updateModalOpen = false" />

    <section class="card">
      <h3>API tokens</h3>
      <p class="muted">
        Stored in your browser's localStorage. The query token is used for read endpoints
        and the live websocket. The ingest token is used for writes.
      </p>
      <div class="grid two">
        <label>
          QUERY token
          <input
            type="password"
            v-model="localQueryToken"
            autocomplete="off"
            placeholder="paste QUERY_TOKEN"
          />
        </label>
        <label>
          INGEST token
          <input
            type="password"
            v-model="localIngestToken"
            autocomplete="off"
            placeholder="paste INGEST_TOKEN"
          />
        </label>
      </div>
      <div class="actions">
        <button class="primary" type="button" @click="saveTokens">
          <Save :size="14" /> Save tokens
        </button>
        <span v-if="tokensSaved" class="muted">Saved.</span>
      </div>
    </section>

    <section class="card">
      <h3>Home location</h3>
      <p class="muted">
        Used for "trips that started/ended at home" and disk alerts when the CT is offline.
        Stored on the server.
      </p>
      <div class="grid two">
        <label>
          Latitude
          <input type="number" step="0.00001" v-model.number="homeLat" />
        </label>
        <label>
          Longitude
          <input type="number" step="0.00001" v-model.number="homeLon" />
        </label>
      </div>
      <div class="actions">
        <button type="button" @click="showPicker = true">
          <MapPin :size="14" /> Pick on map
        </button>
        <button type="button" @click="geolocate">Use current location</button>
      </div>
      <div class="share-row">
        <label>
          <span class="row-label"><LinkIcon :size="12" /> Paste shared link or coords</span>
          <div class="share-input-row">
            <input
              type="text"
              v-model="shareLink"
              placeholder="https://www.google.com/maps/place/.../@39.0,-94.6,15z … or 39.0, -94.6"
              @keydown.enter.prevent="applyShareLink"
            />
            <button type="button" @click="applyShareLink" :disabled="!shareLink.trim()">
              Apply
            </button>
          </div>
          <small
            v-if="shareLinkStatus === 'ok'"
            class="badge success"
          >{{ shareLinkMsg }}</small>
          <small
            v-else-if="shareLinkStatus === 'fail'"
            class="muted warn-text"
          >{{ shareLinkMsg }}</small>
          <small v-else-if="shareLinkMsg" class="muted">{{ shareLinkMsg }}</small>
          <small v-else class="muted">
            Supports Google Maps long &amp; short URLs (incl.
            <code>maps.app.goo.gl/...</code>), Apple Maps, OpenStreetMap, or a
            plain <code>lat, lon</code> pair.
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

    <section class="card">
      <h3>Home Assistant mirror</h3>
      <p class="muted">
        Plumbing is built but disabled by default. When enabled, the backend re-publishes
        readings as MQTT discovery sensors so HA picks them up automatically.
      </p>
      <label class="cb">
        <input type="checkbox" v-model="haEnabled" />
        Enable HA mirror
      </label>
      <div class="grid two" :class="{ dim: !haEnabled }">
        <label>
          HA URL
          <input
            type="url"
            v-model="haUrl"
            placeholder="http://homeassistant.local:8123"
          />
        </label>
        <label>
          Discovery prefix
          <input v-model="haDiscoveryPrefix" placeholder="homeassistant" />
        </label>
        <label class="full">
          Long-lived token
          <input
            type="password"
            v-model="haToken"
            placeholder="leave blank to keep current"
            autocomplete="off"
          />
          <small v-if="settings.settings?.ha?.token_set" class="muted">
            (token currently set on server)
          </small>
        </label>
      </div>
      <div class="actions">
        <button type="button" @click="testHa" :disabled="!haEnabled || !haUrl">
          <Plug :size="14" /> Test connection
        </button>
        <span
          v-if="haTestStatus === 'ok'"
          class="badge success"
        >{{ haTestMsg }}</span>
        <span
          v-else-if="haTestStatus === 'fail'"
          class="badge danger"
        >{{ haTestMsg }}</span>
        <span
          v-else-if="haTestStatus === 'running'"
          class="muted"
        ><RefreshCw :size="12" /> testing…</span>
      </div>
    </section>

    <section class="card">
      <h3>Display units</h3>
      <p class="muted">
        How values are rendered in the UI. <strong>Auto</strong> uses the
        selected vehicle's stored Fuelio unit codes (miles+gallons for the
        Pilot's import). DB storage is unchanged — this is a render-only
        toggle. Persisted in your browser.
      </p>
      <div class="seg">
        <button
          type="button"
          :class="{ active: units.preference === 'auto' }"
          @click="setUnits('auto')"
        >
          Auto <small class="muted">(currently {{ units.resolved }})</small>
        </button>
        <button
          type="button"
          :class="{ active: units.preference === 'metric' }"
          @click="setUnits('metric')"
        >
          Metric <small class="muted">km / l / °C</small>
        </button>
        <button
          type="button"
          :class="{ active: units.preference === 'imperial' }"
          @click="setUnits('imperial')"
        >
          Imperial <small class="muted">mi / gal / °F</small>
        </button>
      </div>
    </section>

    <section class="card">
      <h3>Monitoring</h3>
      <label>
        Disk alert threshold (%)
        <input
          type="number"
          min="0"
          max="100"
          v-model.number="diskAlertPct"
          placeholder="80"
        />
      </label>
    </section>

    <div class="footer-actions">
      <button class="primary" type="button" @click="saveAll" :disabled="saveStatus === 'saving'">
        <Save :size="14" />
        {{ saveStatus === "saving" ? "Saving…" : "Save settings" }}
      </button>
      <span v-if="saveStatus === 'saved'" class="badge success">Saved</span>
      <span v-if="saveStatus === 'error'" class="badge danger">{{ saveError }}</span>
    </div>

    <!-- Devices: WiCAN → vehicle mapping -->
    <section class="card">
      <h3>
        Devices
        <button
          type="button"
          class="ghost"
          style="margin-left: auto; font-size: 0.78rem"
          @click="loadDevices"
          :disabled="devicesLoading"
        >
          <RefreshCw :size="12" /> {{ devicesLoading ? "…" : "Refresh" }}
        </button>
      </h3>
      <p class="muted">
        Tie a WiCAN OBD device (or a phone bridge) to a vehicle so its
        published metrics route correctly. Devices listed below
        <strong>without</strong> a vehicle have been dropping messages
        — pick a vehicle to start ingesting.
      </p>

      <div v-if="devicesError" class="banner warn">{{ devicesError }}</div>

      <table v-if="devices.length > 0" class="data" style="margin-top: 0.5rem">
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
                @click="unassignDevice(d.device_id)"
                title="Unmap"
              >
                <Trash2 :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted small">
        No devices seen yet. Once your WiCAN starts publishing it'll
        appear here.
      </p>
    </section>

    <!-- Storage / data retention -->
    <section class="card">
      <h3>
        <HardDrive :size="14" /> Storage
        <button
          type="button"
          class="ghost"
          style="margin-left: auto; font-size: 0.78rem"
          @click="loadStorage"
          :disabled="storageLoading"
        >
          <RefreshCw :size="12" /> {{ storageLoading ? "…" : "Refresh" }}
        </button>
      </h3>
      <p class="muted">
        How big is the database and how old is the oldest data? Use the
        Purge action below to drop OBD readings beyond a chosen age — the
        UI shows a preview before committing.
      </p>

      <div v-if="storageError" class="banner warn">{{ storageError }}</div>

      <table v-if="storageStats" class="data" style="margin-top: 0.6rem">
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
          <span class="muted small">Save settings below to apply</span>
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
            @click="commitPurge"
            :disabled="purgeBusy || !purgePreview"
          >
            <Trash2 :size="14" /> Commit purge
          </button>
        </div>
        <p v-if="purgePreview" class="muted small">
          Would delete <strong>{{ purgePreview.rows.toLocaleString() }}</strong> reading(s)
          before <code>{{ purgePreview.cutoff.slice(0, 19).replace("T", " ") }}</code>.
        </p>
        <p v-if="purgeMessage" class="muted small">{{ purgeMessage }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.settings {
  max-width: 760px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.banner {
  border-radius: var(--r-md);
  padding: 0.6rem 0.9rem;
}
.banner.warn {
  background: rgba(210, 153, 34, 0.12);
  border: 1px solid rgba(210, 153, 34, 0.3);
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
.footer-actions {
  display: flex;
  gap: 0.6rem;
  align-items: center;
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
</style>
