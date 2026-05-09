<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { ChevronDown, Car } from "lucide-vue-next";
import { useVehiclesStore } from "@/stores/vehicles";

const vehicles = useVehiclesStore();
const open = ref(false);
const root = ref<HTMLElement | null>(null);

onMounted(() => {
  void vehicles.ensureLoaded();
  document.addEventListener("click", onDocClick, true);
});

onBeforeUnmount(() => {
  document.removeEventListener("click", onDocClick, true);
});

/**
 * Close the dropdown when the click lands outside the picker root. Using
 * a capture-phase document listener instead of a body-teleported mask div
 * — the mask was covering the dropdown's option buttons (different
 * stacking contexts) so taps on a vehicle never reached the option's
 * click handler. This handler fires *before* the bubbling target click,
 * but checks `root.contains(target)` so clicks on the dropdown options
 * still work.
 */
function onDocClick(e: MouseEvent) {
  if (!open.value) return;
  const target = e.target as Node | null;
  if (target && root.value && !root.value.contains(target)) {
    open.value = false;
  }
}

const selected = computed(() => vehicles.selectedVehicle);
const showPicker = computed(() => vehicles.vehicles.length > 1);

function pick(id: string) {
  vehicles.selectVehicle(id);
  open.value = false;
}
</script>

<template>
  <div
    v-if="vehicles.vehicles.length > 0"
    ref="root"
    class="vehicle-picker"
    @keydown.esc="open = false"
  >
    <button
      v-if="showPicker"
      class="trigger"
      type="button"
      @click="open = !open"
    >
      <Car :size="16" />
      <span class="name">{{ selected?.name ?? "Select vehicle" }}</span>
      <ChevronDown :size="14" />
    </button>
    <div v-else class="static">
      <Car :size="16" />
      <span class="name">{{ selected?.name ?? "—" }}</span>
    </div>
    <div v-if="open && showPicker" class="dropdown">
      <button
        v-for="v in vehicles.vehicles"
        :key="v.id"
        type="button"
        class="option"
        :class="{ selected: v.id === selected?.id }"
        @click="pick(v.id)"
      >
        <span class="opt-name">{{ v.name }}</span>
        <span v-if="v.year || v.make || v.model" class="opt-sub muted">
          {{ [v.year, v.make, v.model].filter(Boolean).join(" ") }}
        </span>
      </button>
    </div>
  </div>
  <div v-else class="vehicle-picker">
    <span class="muted">No vehicles</span>
  </div>
</template>

<style scoped>
.vehicle-picker {
  position: relative;
  display: flex;
  align-items: center;
}
.trigger,
.static {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.4rem 0.7rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 0.9rem;
}
.trigger:hover {
  background: var(--c-surface-3);
}
.static {
  cursor: default;
}
.name {
  font-weight: 500;
}
.dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  min-width: 220px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-soft);
  /* High enough to clear any sticky-positioned chrome above the picker
     (TopBar) without competing with toast/snackbar layers (which sit
     much higher). */
  z-index: 100;
  padding: 0.3rem;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  text-align: left;
  background: transparent;
  border: none;
  padding: 0.5rem 0.6rem;
  border-radius: var(--r-sm);
  cursor: pointer;
  color: var(--c-text);
  gap: 0.1rem;
}
.option:hover {
  background: var(--c-surface-2);
}
.option.selected {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.opt-sub {
  font-size: 0.78rem;
}
</style>
