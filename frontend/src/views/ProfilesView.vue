<script setup lang="ts">
import { ref, computed } from "vue";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Profile } from "@/api/types";
import { Plus, Save, X, FileJson } from "lucide-vue-next";

const { data: profiles, loading, error, reload } = useAsync(() => api.listProfiles(), []);

const selectedId = ref<string | null>(null);
const detail = ref<Profile | null>(null);
const editorText = ref<string>("");
const detailLoading = ref(false);
const detailError = ref<string | null>(null);
const saving = ref(false);
const saveError = ref<string | null>(null);

const isNew = computed(() => selectedId.value === "__new__");

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
    saveError.value = "Invalid JSON in profile body";
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
    } else {
      const updated = await api.updateProfile(detail.value.id, {
        name: detail.value.name,
        description: detail.value.description,
        body,
      });
      detail.value = updated;
      editorText.value = JSON.stringify(updated.body ?? {}, null, 2);
      await reload();
    }
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : "save failed";
  } finally {
    saving.value = false;
  }
}

async function remove() {
  if (!detail.value || isNew.value) return;
  if (!window.confirm(`Delete profile "${detail.value.name}"?`)) return;
  try {
    await api.deleteProfile(detail.value.id);
    selectedId.value = null;
    detail.value = null;
    await reload();
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : "delete failed";
  }
}
</script>

<template>
  <div class="profiles">
    <header class="head">
      <h1>PID profiles</h1>
      <button class="primary" type="button" @click="newProfile">
        <Plus :size="14" /> New profile
      </button>
    </header>

    <div class="layout">
      <aside class="list card no-pad">
        <div v-if="loading" class="muted hint">Loading…</div>
        <div v-else-if="error" class="muted hint">Failed: {{ error }}</div>
        <div v-else-if="!profiles || profiles.length === 0" class="muted hint">
          No profiles yet.
        </div>
        <button
          v-for="p in profiles ?? []"
          :key="p.id"
          type="button"
          class="row-btn"
          :class="{ selected: p.id === selectedId }"
          @click="selectProfile(p.id)"
        >
          <FileJson :size="14" />
          <span>
            <strong>{{ p.name }}</strong>
            <small v-if="p.description" class="muted">{{ p.description }}</small>
          </span>
        </button>
      </aside>

      <section class="editor">
        <div v-if="!selectedId" class="card">
          <p class="muted">Select a profile from the list, or create a new one.</p>
        </div>
        <div v-else-if="detailLoading" class="card">
          <p class="muted">Loading profile…</p>
        </div>
        <div v-else-if="detailError" class="card">
          <p class="muted">Failed to load: {{ detailError }}</p>
        </div>
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
            <label>Profile JSON (WiCAN AutoPID format)</label>
            <textarea v-model="editorText" rows="20" spellcheck="false" />
          </div>
          <p v-if="saveError" class="error">{{ saveError }}</p>
          <div class="actions">
            <button v-if="!isNew" class="danger" type="button" @click="remove">
              <X :size="14" /> Delete
            </button>
            <span class="spacer"></span>
            <button class="primary" type="button" @click="save" :disabled="saving">
              <Save :size="14" /> {{ saving ? "Saving…" : "Save" }}
            </button>
          </div>
        </div>
      </section>
    </div>
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
</style>
