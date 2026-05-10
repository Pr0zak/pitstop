<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import PitstopLogo from "@/components/logos/PitstopLogo.vue";
import { LOGOS, STORAGE_KEY, DEFAULT_LOGO, type LogoName } from "@/components/logos/PitstopLogos";
import { Check } from "lucide-vue-next";

const selected = ref<LogoName>(DEFAULT_LOGO);
const justSaved = ref(false);

function load() {
  try {
    const v = localStorage.getItem(STORAGE_KEY) as LogoName | null;
    if (v && LOGOS.some((l) => l.name === v)) selected.value = v;
  } catch {
    /* ignore */
  }
}

function pick(name: LogoName) {
  selected.value = name;
  try {
    localStorage.setItem(STORAGE_KEY, name);
    justSaved.value = true;
    setTimeout(() => (justSaved.value = false), 1500);
    // Notify other components in the same tab.
    window.dispatchEvent(new CustomEvent("pitstop-logo-changed", { detail: name }));
  } catch {
    /* ignore */
  }
}

onMounted(load);

const previewBgs = computed(() => [
  { id: "surface", label: "Surface", bg: "var(--c-surface)" },
  { id: "bg", label: "Background", bg: "var(--c-bg)" },
  { id: "accent", label: "Accent", bg: "var(--c-accent)" },
]);
</script>

<template>
  <div class="logos">
    <h1>Logo refinements</h1>
    <p class="muted">
      Baseline mark plus four refinements. Click one to set it as the brand
      mark in the sidebar and top bar — selection is stored in this browser's
      localStorage. Each renders as inline SVG; no raster assets.
    </p>
    <p v-if="justSaved" class="badge success saved">
      <Check :size="12" /> Saved — selection: <code>{{ selected }}</code>
    </p>

    <div class="grid">
      <div
        v-for="logo in LOGOS"
        :key="logo.name"
        class="card logo-card"
        :class="{ active: selected === logo.name }"
        @click="pick(logo.name)"
      >
        <div class="card-head">
          <div class="hero">
            <PitstopLogo
              :name="logo.name"
              :size="80"
              color="var(--c-text)"
              accent="var(--c-warn)"
            />
          </div>
          <div class="meta">
            <h3>{{ logo.label }}</h3>
            <p class="muted small">{{ logo.description }}</p>
            <span v-if="selected === logo.name" class="badge success">
              <Check :size="11" /> selected
            </span>
          </div>
        </div>

        <div class="contexts">
          <div
            v-for="bg in previewBgs"
            :key="bg.id"
            class="ctx"
            :style="{ background: bg.bg }"
          >
            <PitstopLogo
              :name="logo.name"
              :size="20"
              :color="bg.id === 'accent' ? '#fff' : 'var(--c-text)'"
              :accent="bg.id === 'accent' ? '#fff' : 'var(--c-text)'"
            />
            <span class="ctx-label">{{ bg.label }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.logos {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.saved {
  align-self: flex-start;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}
.logo-card {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
  cursor: pointer;
  transition:
    border-color 0.15s,
    transform 0.15s,
    box-shadow 0.15s;
}
.logo-card:hover {
  border-color: var(--c-border);
  transform: translateY(-2px);
}
.logo-card.active {
  border-color: var(--c-accent);
  box-shadow: 0 0 0 2px var(--c-accent-soft);
}
.card-head {
  display: flex;
  gap: 1rem;
  align-items: center;
}
.hero {
  width: 96px;
  height: 96px;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-md);
}
.meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}
.meta h3 {
  margin: 0;
  font-size: 1rem;
  text-transform: none;
  color: var(--c-text);
  letter-spacing: 0;
}
.small {
  font-size: 0.78rem;
  margin: 0;
}
.contexts {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0.4rem;
}
.ctx {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.2rem;
  padding: 0.5rem;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-border-soft);
}
.ctx-label {
  font-size: 0.7rem;
  color: var(--c-muted);
}
</style>
