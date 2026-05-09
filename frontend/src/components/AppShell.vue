<script setup lang="ts">
import { onMounted } from "vue";
import Sidebar from "./Sidebar.vue";
import TopBar from "./TopBar.vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const vehicles = useVehiclesStore();

onMounted(async () => {
  if (!auth.hasQueryToken) return;
  // Best-effort vehicle load; individual views surface their own errors.
  try {
    await vehicles.ensureLoaded();
  } catch {
    /* the auth interceptor already routes to /settings on 401 */
  }
});
</script>

<template>
  <div class="shell">
    <Sidebar />
    <div class="main">
      <TopBar />
      <div class="content">
        <slot />
      </div>
    </div>
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
}

/* Web-mobile (≤ 700 px) — narrow viewports get the icons-only sidebar
   automatically so the content has full breathing room. The user can
   still expand it manually on tablet sizes. The design's full bottom-
   tab-bar layout is a follow-up; this is the no-code-shape change
   that gets the live cluster + tables + cards usable at 390 wide
   without breaking the desktop layout. */
@media (max-width: 700px) {
  .main {
    margin-left: var(--sidebar-w-collapsed);
  }
  .content {
    padding: 1rem 0.85rem;
  }
}
</style>
