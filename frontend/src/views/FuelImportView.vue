<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { RouterLink } from "vue-router";
import { Upload, FilePlus, X, ChevronLeft } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { FuelioImportPreview, Vehicle } from "@/api/types";

const files = ref<File[]>([]);
const preview = ref<FuelioImportPreview | null>(null);
const committed = ref<FuelioImportPreview | null>(null);
const error = ref<string | null>(null);
const isLoading = ref(false);
const isCommitting = ref(false);
const dragOver = ref(false);

// Optional attach target for per-vehicle sync exports (vehicle-N-sync.csv)
// which omit the Vehicle section. Selecting a vehicle here routes all
// rows in the upload(s) to that existing vehicle by slug instead of
// requiring the importer to upsert from a Vehicle row.
const vehicles = ref<Vehicle[]>([]);
const attachSlug = ref<string>("");
onMounted(async () => {
  try {
    vehicles.value = await api.listVehicles();
  } catch {
    /* non-fatal — user can still import full exports */
  }
});

function onPick(e: Event) {
  const input = e.target as HTMLInputElement;
  if (input.files) addFiles(Array.from(input.files));
  input.value = "";
}
function onDrop(e: DragEvent) {
  e.preventDefault();
  dragOver.value = false;
  if (!e.dataTransfer) return;
  addFiles(Array.from(e.dataTransfer.files));
}
function addFiles(list: File[]) {
  for (const f of list) {
    if (f.name.endsWith(".zip") || f.name.endsWith(".csv")) {
      files.value.push(f);
    }
  }
}
function remove(idx: number) {
  files.value.splice(idx, 1);
}

async function runDryRun() {
  if (files.value.length === 0) return;
  isLoading.value = true;
  error.value = null;
  preview.value = null;
  committed.value = null;
  try {
    preview.value = await api.importFuelio(
      files.value,
      true,
      attachSlug.value || null,
    );
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "import failed";
  } finally {
    isLoading.value = false;
  }
}

async function commit() {
  if (files.value.length === 0) return;
  if (!window.confirm("Commit this import? Existing rows with matching guids will be updated.")) {
    return;
  }
  isCommitting.value = true;
  error.value = null;
  try {
    committed.value = await api.importFuelio(
      files.value,
      false,
      attachSlug.value || null,
    );
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "commit failed";
  } finally {
    isCommitting.value = false;
  }
}

function reset() {
  files.value = [];
  preview.value = null;
  committed.value = null;
  error.value = null;
}

const previewSummary = computed(() => preview.value ?? committed.value);
const previewJson = computed(() =>
  previewSummary.value ? JSON.stringify(previewSummary.value, null, 2) : "",
);
</script>

<template>
  <div class="import">
    <header class="head">
      <RouterLink to="/fuel" class="back">
        <ChevronLeft :size="14" /> Fuel
      </RouterLink>
      <h1>Fuelio import</h1>
    </header>

    <section class="card">
      <h3>Upload export</h3>
      <p class="muted">
        Upload one or more <code>vehicle-N-sync.csv.zip</code> exports from Fuelio. The dry
        run shows you exactly what would change without committing anything.
      </p>
      <div
        class="dropzone"
        :class="{ active: dragOver }"
        @dragenter.prevent="dragOver = true"
        @dragleave.prevent="dragOver = false"
        @dragover.prevent
        @drop="onDrop"
      >
        <FilePlus :size="22" />
        <p>Drag &amp; drop zip(s) or CSV files here</p>
        <label class="picker primary">
          <Upload :size="14" /> Browse
          <input type="file" multiple accept=".zip,.csv" @change="onPick" hidden />
        </label>
      </div>

      <div class="attach-row">
        <label for="attach-vehicle">
          Attach to existing vehicle
          <span class="muted small">
            (required for <code>vehicle-N-sync.csv</code>; leave blank for full exports)
          </span>
        </label>
        <select id="attach-vehicle" v-model="attachSlug">
          <option value="">— Auto-detect from file —</option>
          <option v-for="v in vehicles" :key="v.id" :value="v.slug">
            {{ v.name }} ({{ v.slug }})
          </option>
        </select>
      </div>

      <ul v-if="files.length" class="files">
        <li v-for="(f, i) in files" :key="f.name + i">
          <span class="name">{{ f.name }}</span>
          <span class="muted small">{{ (f.size / 1024).toFixed(1) }} KB</span>
          <button class="ghost" type="button" @click="remove(i)" title="Remove">
            <X :size="14" />
          </button>
        </li>
      </ul>

      <div class="actions">
        <button type="button" @click="reset" :disabled="files.length === 0 && !preview && !committed">
          Clear
        </button>
        <span class="spacer"></span>
        <button
          type="button"
          @click="runDryRun"
          :disabled="files.length === 0 || isLoading"
        >
          {{ isLoading ? "Running…" : "Dry run preview" }}
        </button>
        <button
          class="primary"
          type="button"
          @click="commit"
          :disabled="!preview || isCommitting"
        >
          {{ isCommitting ? "Committing…" : "Commit import" }}
        </button>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section v-if="committed" class="card success-card">
      <h3>Import complete</h3>
      <p class="muted">
        The selected exports were applied to the database. Re-importing the same files is
        a no-op.
      </p>
    </section>

    <section v-if="previewSummary" class="card">
      <h3>{{ committed ? "Result" : "Dry-run summary" }}</h3>
      <div class="summary-grid">
        <div v-if="previewSummary.vehicles" class="block">
          <h4>Vehicles</h4>
          <ul>
            <li v-for="v in previewSummary.vehicles" :key="v.guid">
              {{ v.name }}
              <span class="muted small">{{ v.existing ? "existing" : "new" }}</span>
            </li>
          </ul>
        </div>
        <div v-if="previewSummary.fillups" class="block">
          <h4>Fillups</h4>
          <dl>
            <dt>Total in file</dt>
            <dd>{{ previewSummary.fillups.total }}</dd>
            <dt>New</dt>
            <dd>{{ previewSummary.fillups.new }}</dd>
            <dt>Updated</dt>
            <dd>{{ previewSummary.fillups.updated }}</dd>
          </dl>
        </div>
        <div v-if="previewSummary.expenses" class="block">
          <h4>Expenses</h4>
          <dl>
            <dt>Total in file</dt>
            <dd>{{ previewSummary.expenses.total }}</dd>
            <dt>New</dt>
            <dd>{{ previewSummary.expenses.new }}</dd>
            <dt>Updated</dt>
            <dd>{{ previewSummary.expenses.updated }}</dd>
          </dl>
        </div>
      </div>
      <div v-if="previewSummary.warnings && previewSummary.warnings.length" class="block">
        <h4>Warnings</h4>
        <ul class="warnings">
          <li v-for="(w, i) in previewSummary.warnings" :key="i">{{ w }}</li>
        </ul>
      </div>
      <details>
        <summary>Raw response</summary>
        <pre>{{ previewJson }}</pre>
      </details>
    </section>
  </div>
</template>

<style scoped>
.import {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-width: 880px;
}
.head {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}
.head h1 {
  margin: 0;
}
.back {
  color: var(--c-muted);
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
}
.dropzone {
  margin-top: 0.7rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.6rem;
  padding: 2rem;
  border: 2px dashed var(--c-border);
  border-radius: var(--r-md);
  color: var(--c-muted);
  text-align: center;
}
.dropzone.active {
  border-color: var(--c-accent);
  background: var(--c-accent-soft);
}
.attach-row {
  margin-top: 0.7rem;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.attach-row label {
  font-size: 0.85rem;
  color: var(--c-muted);
}
.attach-row select {
  padding: 0.4rem 0.6rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  color: var(--c-text);
  width: 100%;
}
.picker {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  cursor: pointer;
}
.files {
  list-style: none;
  padding: 0;
  margin: 0.7rem 0 0 0;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.files li {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.4rem 0.6rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-sm);
}
.files .name {
  flex: 1;
}
.actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-top: 0.7rem;
}
.error {
  color: var(--c-danger);
  margin-top: 0.5rem;
}
.success-card {
  border-color: rgba(63, 185, 80, 0.3);
  background: rgba(63, 185, 80, 0.06);
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1rem;
  margin: 0.5rem 0;
}
.block h4 {
  font-size: 0.85rem;
  color: var(--c-muted);
  margin: 0 0 0.4rem 0;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.block dl {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.2rem 0.6rem;
  margin: 0;
}
.block dt {
  color: var(--c-muted);
}
.block dd {
  margin: 0;
  font-weight: 500;
}
.warnings {
  color: var(--c-warn);
  padding-left: 1.2em;
  margin: 0;
}
details {
  margin-top: 0.7rem;
}
pre {
  max-height: 400px;
  overflow: auto;
}
.small {
  font-size: 0.78rem;
}
</style>
