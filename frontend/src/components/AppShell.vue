<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import Sidebar from "./Sidebar.vue";
import TopBar from "./TopBar.vue";
import BottomTabBar from "./BottomTabBar.vue";
import ToastHost from "./ToastHost.vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const vehicles = useVehiclesStore();
const route = useRoute();
const router = useRouter();

onMounted(async () => {
  if (!auth.hasQueryToken) return;
  // Best-effort vehicle load; individual views surface their own errors.
  try {
    await vehicles.ensureLoaded();
  } catch {
    /* the auth interceptor already routes to /settings on 401 */
  }
});

// ?vehicle=<id|slug> works on every route: it selects that vehicle, then
// drops itself from the URL so the global picker stays the one source of
// truth (a sticky param would fight the picker on the next switch).
watch(
  [() => route.query.vehicle, () => vehicles.loaded],
  ([q, loaded]) => {
    const want = Array.isArray(q) ? q[0] : q;
    if (!want || !loaded) return;
    const hit = vehicles.vehicles.find((v) => v.id === want || v.slug === want);
    if (hit) vehicles.selectVehicle(hit.id);
    const query = { ...route.query };
    delete query.vehicle;
    void router.replace({ query });
  },
  { immediate: true },
);
</script>

<template>
  <div class="shell">
    <a href="#main" class="skip-link">Skip to content</a>
    <Sidebar />
    <div class="main">
      <TopBar />
      <main id="main" class="content" tabindex="-1">
        <slot />
      </main>
    </div>
    <BottomTabBar />
    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  background: var(--c-bg);
}
.main {
  flex: 1;
  margin-left: var(--sidebar-w);
  display: flex;
  flex-direction: column;
  min-width: 0;
  transition: margin-left 0.15s ease;
}
.shell:has(.sidebar.collapsed) .main {
  margin-left: var(--sidebar-w-collapsed);
}
.content {
  padding: 1.5rem;
  flex: 1;
  min-width: 0;
  outline: none;
}
.skip-link {
  position: absolute;
  left: -9999px;
  top: 0.5rem;
  z-index: 400;
  background: var(--c-bg3);
  padding: 0.4rem 0.8rem;
  border-radius: var(--r-sm);
}
.skip-link:focus {
  left: 0.5rem;
}

/* Phone width: the rail is gone, the bottom tab bar takes over. */
@media (max-width: 700px) {
  .main,
  .shell:has(.sidebar.collapsed) .main {
    margin-left: 0;
  }
  .content {
    padding: 1rem 0.85rem calc(var(--tabbar-h) + 1.25rem + env(safe-area-inset-bottom));
  }
}
</style>
