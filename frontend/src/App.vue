<script setup lang="ts">
import { onMounted } from "vue";
import { RouterView, useRouter } from "vue-router";
import AppShell from "@/components/AppShell.vue";
import { setUnauthorizedHandler } from "@/api";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const auth = useAuthStore();

onMounted(() => {
  setUnauthorizedHandler((kind) => {
    if (kind === "query") auth.clearQueryToken();
    if (kind === "ingest") auth.clearIngestToken();
    if (router.currentRoute.value.path !== "/settings") {
      router.push({ path: "/settings", query: { reason: `auth_${kind}` } });
    }
  });
});
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
</template>
