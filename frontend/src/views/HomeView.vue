<script setup lang="ts">
import { ref, onMounted } from "vue";
import axios from "axios";

const status = ref<string>("loading…");
const version = ref<string>("");

onMounted(async () => {
  try {
    const h = await axios.get("/api/health");
    status.value = h.data.status;
    const v = await axios.get("/api/version");
    version.value = v.data.version;
  } catch (e) {
    status.value = "unreachable";
  }
});
</script>

<template>
  <main class="home">
    <h1>pitstop</h1>
    <p class="muted">vehicle telemetry &amp; fuel tracker</p>
    <p>backend: <strong>{{ status }}</strong></p>
    <p v-if="version">version <code>{{ version }}</code></p>
  </main>
</template>

<style scoped>
.home {
  max-width: 640px;
  margin: 6rem auto;
  padding: 2rem;
}
.muted {
  color: var(--c-muted);
}
code {
  background: var(--c-surface-2);
  padding: 0.1em 0.4em;
  border-radius: 4px;
}
</style>
