<script setup lang="ts">
import { ref, onMounted, computed, watch } from "vue";
import { useRoute } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useSettingsStore } from "@/stores/settings";
import { Save, Plug, RefreshCw } from "lucide-vue-next";

const route = useRoute();
const auth = useAuthStore();
const settings = useSettingsStore();

const localQueryToken = ref("");
const localIngestToken = ref("");
const tokensSaved = ref(false);

const haEnabled = ref(false);
const haUrl = ref("");
const haToken = ref(""); // empty means "don't change"
const haDiscoveryPrefix = ref("homeassistant");
const haTestStatus = ref<"idle" | "ok" | "fail" | "running">("idle");
const haTestMsg = ref<string | null>(null);

const homeLat = ref<number | null>(null);
const homeLon = ref<number | null>(null);
const diskAlertPct = ref<number | null>(null);

const saveStatus = ref<"idle" | "saving" | "saved" | "error">("idle");
const saveError = ref<string | null>(null);

const reasonHint = computed(() => {
  const r = route.query.reason;
  if (r === "auth_query") return "Your QUERY token was rejected (401). Please re-enter.";
  if (r === "auth_ingest") return "Your INGEST token was rejected (401). Please re-enter.";
  return null;
});

onMounted(async () => {
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
    }
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
    } = {
      ha: {
        enabled: haEnabled.value,
        url: haUrl.value || null,
        discovery_prefix: haDiscoveryPrefix.value,
      },
      home: { lat: homeLat.value, lon: homeLon.value },
      disk_alert_pct: diskAlertPct.value,
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
        <button type="button" @click="geolocate">Use current location</button>
      </div>
    </section>

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
</style>
