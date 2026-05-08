<script setup lang="ts">
defineProps<{ title: string; task?: string; description?: string }>();
</script>

<template>
  <div class="placeholder">
    <h1>{{ title }}</h1>
    <p class="muted" v-if="description">{{ description }}</p>
    <div class="card stub">
      <h3>Coming soon</h3>
      <p class="muted">
        This view is a placeholder<span v-if="task"> — see {{ task }}</span>.
        Once the API is ready we'll wire it up.
      </p>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  max-width: 720px;
}
.stub {
  margin-top: 1rem;
}
</style>
