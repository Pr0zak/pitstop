<script setup lang="ts">
// Standalone route kept for bookmarks; the same panel lives in
// Settings → Integrations.
import { RouterLink } from "vue-router";
import HondaLinkTest from "@/components/HondaLinkTest.vue";
</script>

<template>
  <div class="hondalink-view">
    <p class="crumb muted small">
      <RouterLink to="/settings#integrations">Settings › Integrations</RouterLink>
    </p>
    <h1>HondaLink connection test</h1>
    <section class="card">
      <HondaLinkTest />
    </section>
  </div>
</template>

<style scoped>
.hondalink-view {
  max-width: 760px;
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.crumb {
  margin: 0;
}
h1 {
  margin: 0 0 0.4rem;
}
</style>
