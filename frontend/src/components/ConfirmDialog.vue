<script setup lang="ts">
// Reusable confirm modal. Replaces native window.confirm()/alert(), which
// render with the OS chrome and look jarring on the dark theme. Extracted
// from TripsView's in-app delete confirm so every destructive action shares
// one look. Driven by v-model:open so callers can `await` a Promise-free
// open/confirm/cancel flow via events.
withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    message?: string | null;
    confirmLabel?: string;
    cancelLabel?: string;
    /** "danger" paints the confirm button red for destructive actions. */
    tone?: "danger" | "primary";
    busy?: boolean;
  }>(),
  {
    message: null,
    confirmLabel: "Confirm",
    cancelLabel: "Cancel",
    tone: "danger",
    busy: false,
  },
);
const emit = defineEmits<{
  (e: "confirm"): void;
  (e: "cancel"): void;
  (e: "update:open", value: boolean): void;
}>();

function cancel() {
  emit("update:open", false);
  emit("cancel");
}
function confirm() {
  emit("confirm");
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="confirm-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-title"
      @click.self="cancel"
    >
      <div class="confirm-modal">
        <h3 id="confirm-title">{{ title }}</h3>
        <p v-if="message" class="muted confirm-message">{{ message }}</p>
        <slot />
        <div class="confirm-actions">
          <button type="button" class="ghost" :disabled="busy" @click="cancel">
            {{ cancelLabel }}
          </button>
          <button
            type="button"
            :class="tone === 'danger' ? 'danger' : 'primary'"
            :disabled="busy"
            @click="confirm"
          >
            {{ busy ? "…" : confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
  padding: 1rem;
}
.confirm-modal {
  background: var(--c-surface, #14121d);
  border: 1px solid var(--c-border-soft, #2a2d33);
  border-radius: 10px;
  padding: 1.2rem 1.4rem;
  width: min(420px, calc(100% - 2rem));
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
}
.confirm-modal h3 {
  margin: 0;
  font-size: 1.05rem;
}
.confirm-message {
  margin: 0;
  font-size: 0.9rem;
}
.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.3rem;
}
.confirm-actions button {
  font-size: 0.9rem;
  padding: 0.4rem 1rem;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid var(--c-border-soft, #2a2d33);
  background: transparent;
  color: var(--c-text, #e7e9ee);
}
.confirm-actions button.danger {
  background: #b91c1c;
  color: white;
  border-color: #b91c1c;
}
.confirm-actions button.primary {
  background: var(--c-accent, #ff5b3a);
  color: white;
  border-color: var(--c-accent, #ff5b3a);
}
.confirm-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
