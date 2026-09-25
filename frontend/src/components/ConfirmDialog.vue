<script setup lang="ts">
// Reusable confirm modal. Replaces native window.confirm()/alert(), which
// render with the OS chrome and look jarring on the dark theme. Driven by
// v-model:open; Esc cancels and focus is trapped while open.
import { ref, useId } from "vue";
import { useModalA11y } from "@/composables/useModalA11y";

const props = withDefaults(
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

const panel = ref<HTMLElement | null>(null);
const titleId = useId();

function cancel() {
  if (props.busy) return;
  emit("update:open", false);
  emit("cancel");
}
function confirm() {
  emit("confirm");
}
useModalA11y(() => props.open, panel, cancel);
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="confirm-overlay" @click.self="cancel">
      <div
        ref="panel"
        class="confirm-modal"
        role="alertdialog"
        aria-modal="true"
        :aria-labelledby="titleId"
      >
        <h3 :id="titleId">{{ title }}</h3>
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
            autofocus
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
  background: var(--c-bg2);
  border: 1px solid var(--c-line1);
  border-radius: var(--r-lg);
  padding: 1.2rem 1.4rem;
  width: min(440px, calc(100% - 2rem));
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
}
.confirm-modal h3 {
  margin: 0;
  font-size: 1.05rem;
  text-transform: none;
  letter-spacing: -0.01em;
  color: var(--c-ink0);
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
</style>
