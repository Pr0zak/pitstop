<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "@/stores/auth";
import { useVehiclesStore } from "@/stores/vehicles";
import { useLive } from "@/composables/useLive";
import { useNow, agoLabel } from "@/composables/useNow";
import VehiclePicker from "./VehiclePicker.vue";
import { ShieldAlert } from "lucide-vue-next";

const auth = useAuthStore();
const vehicles = useVehiclesStore();
const vehicleId = computed(() => (auth.hasQueryToken ? vehicles.selectedVehicleId : null));
const { status, lastFrameAt } = useLive(vehicleId);
const now = useNow(10_000);

// Streaming = socket open AND a frame captured in the last 60 s. The
// live store's own status flips to "open" on connect before any frame
// arrives, so the frame time is the honest signal.
const lastSeenMs = computed<number | null>(() => {
  const frame = lastFrameAt.value;
  const seen = vehicles.selectedVehicle?.last_seen_at
    ? Date.parse(vehicles.selectedVehicle.last_seen_at)
    : null;
  const candidates = [frame, seen].filter((v): v is number => v != null && Number.isFinite(v));
  return candidates.length ? Math.max(...candidates) : null;
});
const streaming = computed(
  () =>
    status.value === "open" &&
    lastFrameAt.value != null &&
    now.value - lastFrameAt.value < 60_000,
);
const liveLabel = computed(() =>
  streaming.value ? "Streaming" : `Last seen ${agoLabel(lastSeenMs.value, now.value)}`,
);
</script>

<template>
  <header class="topbar">
    <div class="left">
      <VehiclePicker />
    </div>
    <div class="right">
      <RouterLink
        v-if="auth.hasQueryToken && vehicles.selectedVehicle"
        to="/live"
        class="live-pill"
        :class="{ on: streaming }"
        :title="streaming ? 'Receiving live frames — open Live' : 'Open Live'"
      >
        <span class="dot" aria-hidden="true" />
        <span class="lbl">{{ liveLabel }}</span>
      </RouterLink>
      <RouterLink
        v-if="!auth.hasQueryToken"
        to="/settings"
        class="token-pill"
        title="Query token missing or rejected — open Settings"
      >
        <ShieldAlert :size="14" aria-hidden="true" />
        <span class="lbl">Auth needed</span>
      </RouterLink>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 1rem;
  border-bottom: 1px solid var(--c-border-soft);
  background: var(--c-bg);
  position: sticky;
  top: 0;
  z-index: 10;
}
.left,
.right {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: 0;
}
.live-pill,
.token-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.3rem 0.65rem;
  border-radius: 999px;
  font-size: 0.78rem;
  border: 1px solid var(--c-line0);
  background: var(--c-bg2);
  color: var(--c-ink2);
  text-decoration: none;
  white-space: nowrap;
}
.live-pill:hover,
.token-pill:hover {
  text-decoration: none;
  border-color: var(--c-line2);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-ink4);
}
.live-pill.on {
  color: var(--c-ink0);
}
.live-pill.on .dot {
  background: var(--c-success);
  box-shadow: 0 0 0 3px var(--c-success-soft);
  animation: pulse 2s ease-in-out infinite;
}
.token-pill {
  color: var(--c-warn);
  border-color: rgba(255, 176, 32, 0.3);
}
@keyframes pulse {
  50% {
    box-shadow: 0 0 0 5px transparent;
  }
}
@media (prefers-reduced-motion: reduce) {
  .live-pill.on .dot {
    animation: none;
  }
}
</style>
