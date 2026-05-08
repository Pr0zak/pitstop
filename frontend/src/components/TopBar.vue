<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "@/stores/auth";
import VehiclePicker from "./VehiclePicker.vue";
import { ShieldAlert, ShieldCheck } from "lucide-vue-next";

const auth = useAuthStore();
const tokenStatus = computed(() => (auth.hasQueryToken ? "ok" : "missing"));
</script>

<template>
  <header class="topbar">
    <div class="left">
      <VehiclePicker />
    </div>
    <div class="right">
      <RouterLink
        to="/settings"
        class="token-pill"
        :class="{ ok: tokenStatus === 'ok', missing: tokenStatus === 'missing' }"
        :title="tokenStatus === 'ok' ? 'Query token configured' : 'Query token missing — open Settings'"
      >
        <ShieldCheck v-if="tokenStatus === 'ok'" :size="14" />
        <ShieldAlert v-else :size="14" />
        <span class="lbl">{{ tokenStatus === "ok" ? "auth ok" : "auth needed" }}</span>
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
}
.token-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.3rem 0.6rem;
  border-radius: 999px;
  font-size: 0.78rem;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface-2);
  color: var(--c-muted);
  text-decoration: none;
}
.token-pill.ok {
  color: var(--c-success);
  border-color: rgba(63, 185, 80, 0.3);
}
.token-pill.missing {
  color: var(--c-warn);
  border-color: rgba(210, 153, 34, 0.3);
}
.token-pill:hover {
  text-decoration: none;
  filter: brightness(1.1);
}
</style>
