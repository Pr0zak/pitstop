<script setup lang="ts" generic="T extends string">
// One time-window chip row for every view that scopes data by period.
// The parent owns the value (normally via useQueryParam("window", …)) so
// the selection round-trips through ?window= and survives reloads / links.
defineProps<{
  modelValue: T;
  options: readonly { value: T; label: string; title?: string }[];
  label?: string;
}>();
const emit = defineEmits<{ (e: "update:modelValue", v: T): void }>();
</script>

<template>
  <div class="chip-row window-chips" role="group" :aria-label="label ?? 'Time window'">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      class="chip"
      :aria-pressed="modelValue === o.value"
      :title="o.title"
      @click="emit('update:modelValue', o.value)"
    >
      {{ o.label }}
    </button>
  </div>
</template>
