<script setup lang="ts">
// Renders a formatted quantity ("1,040 °F", "12.3 psi") with the number in
// tabular mono and the unit muted and smaller — one look for every tile.
import { computed } from "vue";

const props = defineProps<{ text: string; unit?: string | null }>();
const parts = computed(() => {
  if (props.unit != null) return { v: props.text, u: props.unit };
  const m = /^(.*?[\d—%.,+-]+)\s+([^\d\s][^\d]*)$/.exec(props.text.trim());
  return m ? { v: m[1], u: m[2] } : { v: props.text, u: "" };
});
</script>

<template>
  <span class="qty"><span class="v">{{ parts.v }}</span><span v-if="parts.u && parts.v !== '—'" class="unit">{{ parts.u }}</span></span>
</template>

<style scoped>
.qty {
  display: inline-flex;
  align-items: baseline;
  gap: 0.3em;
}
.v {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
}
.unit {
  font-family: "Geist", sans-serif;
  font-size: 0.55em;
  font-weight: 500;
  color: var(--c-ink3);
}
</style>
