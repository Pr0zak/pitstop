<script setup lang="ts">
// One loading / empty / error surface for every view. Error always offers
// Retry when the parent listens for it, so a failed request is never a
// dead end.
import { AlertTriangle, Inbox, Loader2, RotateCw } from "lucide-vue-next";

withDefaults(
  defineProps<{
    state: "loading" | "empty" | "error";
    title?: string | null;
    message?: string | null;
    /** Render without the card chrome (inside an existing card). */
    bare?: boolean;
    retryable?: boolean;
  }>(),
  { title: null, message: null, bare: false, retryable: true },
);
const emit = defineEmits<{ (e: "retry"): void }>();
</script>

<template>
  <div
    class="state-card"
    :class="[state, { card: !bare, bare }]"
    :role="state === 'error' ? 'alert' : 'status'"
    :aria-busy="state === 'loading' ? 'true' : undefined"
  >
    <span class="ic" aria-hidden="true">
      <Loader2 v-if="state === 'loading'" :size="16" class="spin" />
      <AlertTriangle v-else-if="state === 'error'" :size="16" />
      <Inbox v-else :size="16" />
    </span>
    <div class="body">
      <div v-if="title || state !== 'empty'" class="title">
        {{ title ?? (state === "loading" ? "Loading…" : state === "error" ? "Couldn't load this" : "") }}
      </div>
      <div v-if="message" class="msg">{{ message }}</div>
      <div v-if="$slots.default" class="extra"><slot /></div>
    </div>
    <button
      v-if="state === 'error' && retryable"
      type="button"
      class="retry"
      @click="emit('retry')"
    >
      <RotateCw :size="14" aria-hidden="true" /> Retry
    </button>
  </div>
</template>

<style scoped>
.state-card {
  display: flex;
  align-items: flex-start;
  gap: 0.7rem;
  color: var(--c-ink2);
  font-size: 0.9rem;
}
.state-card.bare {
  padding: 0.6rem 0;
}
.state-card.error {
  border-color: rgba(255, 58, 46, 0.3);
}
.state-card.error .ic {
  color: var(--c-danger);
}
.ic {
  display: inline-flex;
  padding-top: 2px;
  color: var(--c-ink3);
}
.body {
  flex: 1;
  min-width: 0;
}
.title {
  color: var(--c-ink1);
  font-weight: 500;
}
.msg {
  color: var(--c-ink3);
  font-size: 0.85rem;
  word-break: break-word;
}
.extra {
  margin-top: 0.4rem;
}
.retry {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  flex: none;
  font-size: 0.82rem;
  padding: 0.3rem 0.7rem;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
