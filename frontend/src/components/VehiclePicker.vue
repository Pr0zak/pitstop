<script setup lang="ts">
import { computed, ref, onMounted } from "vue";
import { ChevronDown, Car } from "lucide-vue-next";
import { useVehiclesStore } from "@/stores/vehicles";

const vehicles = useVehiclesStore();
const open = ref(false);

onMounted(() => {
  void vehicles.ensureLoaded();
});

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
    class="vehicle-picker"
    @click.stop
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
  <!-- click outside listener -->
  <Teleport to="body">
    <div v-if="open" class="picker-mask" @click="open = false" />
  </Teleport>
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
  z-index: 50;
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
.picker-mask {
  position: fixed;
  inset: 0;
  z-index: 40;
}
</style>
