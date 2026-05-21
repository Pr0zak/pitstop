<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { checkUpdates, triggerUpgrade, getVersion, type UpdateCheck } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";

defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: "close"): void }>();

const auth = useAuthStore();
const info = ref<UpdateCheck | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

// Upgrade lifecycle: 'idle' → 'kicking' → 'pulling' → 'done' | 'timeout'.
// We can't stream progress (the API process dies mid-upgrade) so the
// frontend just polls /version until it flips to the target tag.
type Phase = "idle" | "kicking" | "pulling" | "done" | "timeout";
const phase = ref<Phase>("idle");
const target = ref<string>("");
const elapsedS = ref(0);
let pollTimer: number | null = null;
let elapsedTimer: number | null = null;

const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_S = 120;

const readyToUpgrade = computed(
  () =>
    info.value?.update_available === true &&
    !!info.value.latest_version &&
    !!auth.ingestToken,
);

async function reload() {
  loading.value = true;
  error.value = null;
  try {
    info.value = await checkUpdates();
    if (info.value.error) error.value = info.value.error;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function close() {
  if (phase.value === "kicking" || phase.value === "pulling") return;
  emit("close");
}

async function startUpgrade() {
  if (!info.value?.latest_version) return;
  target.value = info.value.latest_version;
  phase.value = "kicking";
  error.value = null;
  try {
    await triggerUpgrade(target.value);
    phase.value = "pulling";
    startPolling();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    phase.value = "idle";
  }
}

function startPolling() {
  elapsedS.value = 0;
  elapsedTimer = window.setInterval(() => {
    elapsedS.value += 1;
    if (elapsedS.value >= POLL_TIMEOUT_S && phase.value === "pulling") {
      phase.value = "timeout";
      stopPolling();
    }
  }, 1000);
  pollTimer = window.setInterval(async () => {
    try {
      const v = await getVersion();
      const running = v.version.replace(/^v/, "");
      const wanted = target.value.replace(/^v/, "");
      if (running === wanted) {
        phase.value = "done";
        stopPolling();
      }
    } catch {
      // backend may be restarting — keep polling
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer != null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
  if (elapsedTimer != null) {
    window.clearInterval(elapsedTimer);
    elapsedTimer = null;
  }
}

function reloadApp() {
  window.location.reload();
}

onMounted(() => {
  void reload();
});
onBeforeUnmount(() => {
  stopPolling();
});
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="close">
    <div class="modal">
      <header class="modal-head">
        <h2>Updates</h2>
        <button class="close-btn" @click="close" :disabled="phase === 'kicking' || phase === 'pulling'">
          ×
        </button>
      </header>

      <div class="modal-body">
        <div v-if="loading && !info" class="muted">Checking GitHub…</div>

        <div v-else-if="error && phase === 'idle'" class="err">
          {{ error }}
        </div>

        <template v-else-if="info">
          <div class="version-row">
            <div>
              <div class="label">Running</div>
              <div class="big-version">v{{ info.current_version }}</div>
              <div class="muted small" v-if="info.current_sha && info.current_sha !== 'unknown'">
                {{ info.current_sha.slice(0, 7) }}
              </div>
            </div>
            <div class="arrow">→</div>
            <div>
              <div class="label">Latest</div>
              <div class="big-version" :class="{ pulse: info.update_available }">
                {{ info.latest_version ?? '—' }}
              </div>
              <div class="muted small" v-if="info.latest_published_at">
                {{ new Date(info.latest_published_at).toLocaleDateString() }}
              </div>
            </div>
          </div>

          <div v-if="!info.update_available && phase === 'idle'" class="ok">
            You're up to date.
          </div>

          <div v-if="info.update_available && info.latest_body && phase === 'idle'" class="release-notes">
            <h3>Release notes</h3>
            <pre>{{ info.latest_body }}</pre>
          </div>

          <div v-if="phase === 'kicking'" class="progress">
            <div class="spinner" /> Spawning upgrader container…
          </div>

          <div v-if="phase === 'pulling'" class="progress">
            <div class="spinner" />
            <div>
              Pulling <code>{{ target }}</code> and restarting backend + frontend.
              <div class="muted small">
                Elapsed {{ elapsedS }}s / {{ POLL_TIMEOUT_S }}s timeout
              </div>
              <div class="muted small">
                The backend will restart mid-upgrade — the page may briefly
                show errors, that's normal.
              </div>
            </div>
          </div>

          <div v-if="phase === 'done'" class="ok">
            Upgrade complete — running {{ target }}. Reload the page to
            pick up the new frontend.
          </div>

          <div v-if="phase === 'timeout'" class="err">
            Didn't see the new version after {{ POLL_TIMEOUT_S }}s. The
            upgrader may still be running — check <code>docker logs
            pitstop-upgrader-*</code> on the CT.
          </div>
        </template>
      </div>

      <footer class="modal-foot">
        <button v-if="phase === 'done'" class="btn primary" @click="reloadApp">
          Reload page
        </button>
        <template v-else>
          <button class="btn" @click="reload" :disabled="loading || phase === 'kicking' || phase === 'pulling'">
            Refresh
          </button>
          <button
            v-if="readyToUpgrade && phase === 'idle'"
            class="btn primary"
            @click="startUpgrade"
          >
            Upgrade to {{ info!.latest_version }}
          </button>
          <button
            v-if="!auth.ingestToken && info?.update_available"
            class="btn"
            disabled
            title="Set the ingest token in Settings to enable in-app upgrades"
          >
            Ingest token required
          </button>
          <button class="btn" @click="close" :disabled="phase === 'kicking' || phase === 'pulling'">
            Close
          </button>
        </template>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex; align-items: center; justify-content: center;
  z-index: 100;
  padding: 1rem;
}
.modal {
  background: var(--c-surface);
  border: 1px solid var(--c-line0);
  border-radius: 10px;
  width: min(560px, 95vw);
  max-height: 90vh;
  display: flex; flex-direction: column;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.4);
}
.modal-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.9rem 1.1rem;
  border-bottom: 1px solid var(--c-line0);
}
.modal-head h2 { margin: 0; font-size: 1rem; font-weight: 600; }
.close-btn {
  background: none; border: 0; color: var(--c-ink3);
  font-size: 1.5rem; line-height: 1; cursor: pointer; padding: 0 0.4rem;
}
.close-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.modal-body {
  padding: 1rem 1.1rem;
  overflow-y: auto;
  flex: 1;
}
.modal-foot {
  display: flex; gap: 0.5rem; justify-content: flex-end;
  padding: 0.7rem 1.1rem;
  border-top: 1px solid var(--c-line0);
}
.btn {
  background: var(--c-surface2, var(--c-surface));
  border: 1px solid var(--c-line0);
  color: var(--c-ink0);
  padding: 0.45rem 0.85rem;
  border-radius: 6px;
  font-size: 0.85rem;
  cursor: pointer;
}
.btn:hover:not(:disabled) { background: var(--c-line0); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.primary {
  background: var(--c-accent);
  border-color: var(--c-accent);
  color: #000;
}
.btn.primary:hover:not(:disabled) { filter: brightness(1.1); }
.version-row {
  display: flex; align-items: center; gap: 1rem;
  padding: 0.5rem 0 1rem 0;
}
.version-row > div:first-child, .version-row > div:last-child { flex: 1; }
.label { font-size: 0.7rem; color: var(--c-ink3); text-transform: uppercase; letter-spacing: 0.08em; }
.big-version {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 1.4rem;
  font-weight: 600;
}
.big-version.pulse { color: var(--c-accent); }
.arrow { font-size: 1.4rem; color: var(--c-ink3); }
.muted { color: var(--c-ink3); }
.small { font-size: 0.78rem; }
.ok { padding: 0.7rem; background: rgba(74, 222, 128, 0.08); border-left: 3px solid #4ade80; border-radius: 4px; }
.err { padding: 0.7rem; background: rgba(255, 58, 46, 0.08); border-left: 3px solid #ff3a2e; border-radius: 4px; color: var(--c-ink0); white-space: pre-wrap; }
.release-notes h3 { font-size: 0.8rem; margin: 0.5rem 0 0.3rem; color: var(--c-ink3); text-transform: uppercase; letter-spacing: 0.06em; }
.release-notes pre {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 0.78rem;
  white-space: pre-wrap;
  max-height: 240px;
  overflow-y: auto;
  padding: 0.6rem;
  background: var(--c-surface2, rgba(0,0,0,0.2));
  border-radius: 4px;
  margin: 0;
}
.progress {
  display: flex; gap: 0.7rem; align-items: flex-start;
  padding: 0.7rem;
  background: var(--c-surface2, rgba(255,255,255,0.03));
  border-radius: 4px;
}
.spinner {
  width: 18px; height: 18px;
  border: 2px solid var(--c-line0);
  border-top-color: var(--c-accent);
  border-radius: 50%;
  animation: spin 700ms linear infinite;
  flex-shrink: 0;
  margin-top: 2px;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
code { font-family: 'Geist Mono', ui-monospace, monospace; font-size: 0.85em; }
</style>
