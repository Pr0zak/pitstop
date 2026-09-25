<script setup lang="ts">
// Phone-width navigation (< 700 px). Four primary destinations plus a
// "More" sheet carrying everything else, replacing the 15-icon rail that
// used to eat a quarter of a 390 px screen.
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { MoreHorizontal, X } from "lucide-vue-next";
import { PRIMARY_NAV, SECONDARY_NAV, TAB_NAV, isNavActive, type NavItem } from "@/lib/nav";
import { useModalA11y } from "@/composables/useModalA11y";

const route = useRoute();
const all: NavItem[] = [...PRIMARY_NAV, ...SECONDARY_NAV];
const tabs = computed(() => TAB_NAV.map((to) => all.find((i) => i.to === to)!).filter(Boolean));
const rest = computed(() => all.filter((i) => !TAB_NAV.includes(i.to)));
const moreActive = computed(() => rest.value.some((i) => isNavActive(i.to, route.path)));

const sheetOpen = ref(false);
const sheet = ref<HTMLElement | null>(null);
useModalA11y(sheetOpen, sheet, () => (sheetOpen.value = false));
watch(
  () => route.fullPath,
  () => (sheetOpen.value = false),
);
</script>

<template>
  <nav class="tabbar" aria-label="Primary">
    <RouterLink
      v-for="t in tabs"
      :key="t.to"
      :to="t.to"
      class="tab"
      :class="{ active: isNavActive(t.to, route.path) }"
      :aria-current="isNavActive(t.to, route.path) ? 'page' : undefined"
    >
      <component :is="t.icon" :size="20" aria-hidden="true" />
      <span>{{ t.label }}</span>
    </RouterLink>
    <button
      type="button"
      class="tab"
      :class="{ active: moreActive || sheetOpen }"
      aria-haspopup="dialog"
      :aria-expanded="sheetOpen"
      @click="sheetOpen = true"
    >
      <MoreHorizontal :size="20" aria-hidden="true" />
      <span>More</span>
    </button>
  </nav>

  <Teleport to="body">
    <div v-if="sheetOpen" class="sheet-overlay" @click.self="sheetOpen = false">
      <div ref="sheet" class="sheet" role="dialog" aria-modal="true" aria-label="More pages">
        <div class="sheet-head">
          <span class="t-label">More</span>
          <button type="button" class="ghost close" aria-label="Close menu" @click="sheetOpen = false">
            <X :size="18" />
          </button>
        </div>
        <div class="sheet-grid">
          <RouterLink
            v-for="i in rest"
            :key="i.to"
            :to="i.to"
            class="sheet-item"
            :class="{ active: isNavActive(i.to, route.path) }"
          >
            <component :is="i.icon" :size="20" aria-hidden="true" />
            <span>{{ i.label }}</span>
          </RouterLink>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.tabbar {
  display: none;
}
@media (max-width: 700px) {
  .tabbar {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 30;
    height: calc(var(--tabbar-h) + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    background: var(--c-bg1);
    border-top: 1px solid var(--c-line1);
  }
}
.tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 500;
  color: var(--c-ink3);
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0;
  text-decoration: none;
  position: relative;
}
.tab:hover {
  text-decoration: none;
  background: transparent;
}
.tab.active {
  color: var(--c-ink0);
}
.tab.active::before {
  content: "";
  position: absolute;
  top: 0;
  left: 30%;
  right: 30%;
  height: 2px;
  background: var(--c-accent);
  border-radius: 0 0 2px 2px;
}
.sheet-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 250;
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: var(--c-bg2);
  border-top: 1px solid var(--c-line1);
  border-radius: var(--r-lg) var(--r-lg) 0 0;
  padding: 0.75rem 1rem calc(1rem + env(safe-area-inset-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}
.close {
  padding: 0.35rem;
  display: inline-flex;
}
.sheet-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
}
.sheet-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  padding: 0.85rem 0.25rem;
  border-radius: var(--r-md);
  background: var(--c-bg3);
  color: var(--c-ink1);
  font-size: 0.82rem;
  text-decoration: none;
}
.sheet-item.active {
  outline: 1px solid var(--c-accent);
}
</style>
