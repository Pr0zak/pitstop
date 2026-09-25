<script setup lang="ts">
import { ref, computed } from "vue";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Profile } from "@/api/types";
import { Plus, Save, X, FileJson, Wand2 } from "lucide-vue-next";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import StateCard from "@/components/StateCard.vue";
import { useToastStore, errMessage } from "@/stores/toast";

const toast = useToastStore();

const { data: profiles, loading, error, reload } = useAsync(() => api.listProfiles(), []);

const selectedId = ref<string | null>(null);
const detail = ref<Profile | null>(null);
const editorText = ref<string>("");
const detailLoading = ref(false);
const detailError = ref<string | null>(null);
const saving = ref(false);
const saveError = ref<string | null>(null);

const isNew = computed(() => selectedId.value === "__new__");

/** Live JSON validation with a line / column for the first error. Engines
 *  report either "(line L column C)" or "at position N"; both map to L:C. */
const jsonError = computed<string | null>(() => {
  const text = editorText.value;
  if (!text.trim()) return "Empty — the body must be a JSON object";
  try {
    JSON.parse(text);
    return null;
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    const lc = /line (\d+) column (\d+)/.exec(msg);
    if (lc) return `Line ${lc[1]}, col ${lc[2]}: ${msg.replace(/\s*\(line \d+ column \d+\)/, "")}`;
    const pos = /position (\d+)/.exec(msg);
    if (pos) {
      const upTo = text.slice(0, Number(pos[1]));
      const line = upTo.split("\n").length;
      const col = upTo.length - upTo.lastIndexOf("\n");
      return `Line ${line}, col ${col}: ${msg.replace(/\s*(in JSON )?at position \d+.*$/, "")}`;
    }
    return msg;
  }
});
function formatJson() {
  if (jsonError.value) return;
  editorText.value = JSON.stringify(JSON.parse(editorText.value), null, 2);
}

async function selectProfile(id: string) {
  selectedId.value = id;
  detailError.value = null;
  saveError.value = null;
  detailLoading.value = true;
  try {
    detail.value = await api.getProfile(id);
    editorText.value = JSON.stringify(detail.value.body ?? {}, null, 2);
  } catch (e: unknown) {
    detailError.value = e instanceof Error ? e.message : "failed to load profile";
  } finally {
    detailLoading.value = false;
  }
}

function newProfile() {
  selectedId.value = "__new__";
  detail.value = { id: "", name: "", description: "", body: {} };
  editorText.value = "{}";
  saveError.value = null;
}

async function save() {
  if (!detail.value) return;
  saveError.value = null;
  let body: unknown;
  try {
    body = JSON.parse(editorText.value);
  } catch {
    saveError.value = jsonError.value ?? "Invalid JSON in profile body";
    return;
  }
  saving.value = true;
  try {
    if (isNew.value) {
      const created = await api.createProfile({
        name: detail.value.name,
        description: detail.value.description,
        body,
      });
      await reload();
      await selectProfile(created.id);
      toast.success(`Created ${created.name}`);
    } else {
      const updated = await api.updateProfile(detail.value.id, {
        name: detail.value.name,
        description: detail.value.description,
        body,
      });
      detail.value = updated;
      editorText.value = JSON.stringify(updated.body ?? {}, null, 2);
      await reload();
      toast.success(`Saved ${updated.name}`);
    }
  } catch (e: unknown) {
    saveError.value = errMessage(e, "save failed");
  } finally {
    saving.value = false;
  }
}

const confirmDelete = ref(false);
const deleting = ref(false);
async function remove() {
  if (!detail.value || isNew.value) return;
  deleting.value = true;
  const name = detail.value.name;
  try {
    await api.deleteProfile(detail.value.id);
    confirmDelete.value = false;
    selectedId.value = null;
    detail.value = null;
    await reload();
    toast.success(`Deleted ${name}`);
  } catch (e: unknown) {
    confirmDelete.value = false;
    saveError.value = errMessage(e, "delete failed");
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <div class="profiles">
    <header class="head">
      <h1>PID profiles</h1>
      <button class="primary" type="button" @click="newProfile">
        <Plus :size="14" aria-hidden="true" /> New profile
      </button>
    </header>

    <div class="layout">
      <aside class="list card no-pad">
        <StateCard v-if="loading" state="loading" bare class="hint" />
        <StateCard v-else-if="error" state="error" bare class="hint" :message="error" @retry="reload()" />
        <StateCard v-else-if="!profiles || profiles.length === 0" state="empty" bare class="hint" title="No profiles yet." />
        <button
          v-for="p in profiles ?? []"
          :key="p.id"
          type="button"
          class="row-btn"
          :class="{ selected: p.id === selectedId }"
          :aria-current="p.id === selectedId ? 'true' : undefined"
          @click="selectProfile(p.id)"
        >
          <FileJson :size="14" aria-hidden="true" />
          <span>
            <strong>{{ p.name }}</strong>
            <small v-if="p.description" class="muted">{{ p.description }}</small>
          </span>
        </button>
      </aside>

      <section class="editor">
        <StateCard v-if="!selectedId" state="empty" title="Select a profile from the list, or create a new one." />
        <StateCard v-else-if="detailLoading" state="loading" title="Loading profile…" />
        <StateCard v-else-if="detailError" state="error" :message="detailError" @retry="selectProfile(selectedId!)" />
        <div v-else-if="detail" class="card editor-card">
          <div class="meta">
            <label>
              Name
              <input v-model="detail.name" placeholder="e.g. honda-pilot-2019" />
            </label>
            <label>
              Description
              <input v-model="detail.description" />
            </label>
          </div>
          <div class="body">
            <div class="body-head">
              <label for="profile-json">Profile JSON (WiCAN AutoPID format)</label>
              <button type="button" class="ghost fmt" :disabled="!!jsonError" @click="formatJson">
                <Wand2 :size="13" aria-hidden="true" /> Format
              </button>
            </div>
            <textarea
              id="profile-json"
              v-model="editorText"
              rows="20"
              spellcheck="false"
              :class="{ invalid: jsonError }"
              :aria-invalid="jsonError ? 'true' : undefined"
              aria-describedby="profile-json-status"
            />
            <p id="profile-json-status" class="json-status" :class="jsonError ? 'bad' : 'ok'" role="status">
              {{ jsonError ?? "Valid JSON" }}
            </p>
          </div>
          <p v-if="saveError" class="error">{{ saveError }}</p>
          <div class="actions">
            <button v-if="!isNew" class="danger" type="button" @click="confirmDelete = true">
              <X :size="14" aria-hidden="true" /> Delete
            </button>
            <span class="spacer"></span>
            <button class="primary" type="button" @click="save" :disabled="saving || !!jsonError">
              <Save :size="14" aria-hidden="true" /> {{ saving ? "Saving…" : "Save" }}
            </button>
          </div>
        </div>
      </section>
    </div>

    <ConfirmDialog
      v-model:open="confirmDelete"
      :title="`Delete profile “${detail?.name ?? ''}”?`"
      message="Vehicles using it fall back to no profile. This can't be undone."
      confirm-label="Delete"
      :busy="deleting"
      @confirm="remove"
    />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.head .primary {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}
.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 1rem;
  align-items: start;
}
.list {
  display: flex;
  flex-direction: column;
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.hint {
  padding: 1rem;
}
.row-btn {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.6rem 0.8rem;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--c-border-soft);
  border-radius: 0;
  text-align: left;
  cursor: pointer;
  color: var(--c-text);
}
.row-btn:hover {
  background: var(--c-surface-2);
}
.row-btn.selected {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.row-btn span {
  display: flex;
  flex-direction: column;
}
.row-btn small {
  font-size: 0.75rem;
}
.editor-card {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
}
.meta {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 0.6rem;
}
.meta label,
.body label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.8rem;
  color: var(--c-muted);
}
textarea {
  width: 100%;
  min-height: 320px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85rem;
  background: var(--c-surface-2);
  color: var(--c-text);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  padding: 0.6rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.actions button {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}
.error {
  color: var(--c-danger);
}
.body-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.25rem;
}
.fmt {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.8rem;
  padding: 0.2rem 0.5rem;
}
textarea.invalid {
  border-color: rgba(255, 58, 46, 0.6);
}
.json-status {
  margin: 0.3rem 0 0;
  font-size: 0.8rem;
  font-family: 'Geist Mono', ui-monospace, monospace;
}
.json-status.ok {
  color: var(--c-success);
}
.json-status.bad {
  color: var(--c-danger);
}
@media (max-width: 700px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .meta {
    grid-template-columns: 1fr;
  }
}
</style>
