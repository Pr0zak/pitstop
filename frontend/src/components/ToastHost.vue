<script setup lang="ts">
import { X } from "lucide-vue-next";
import { useToastStore } from "@/stores/toast";

const toast = useToastStore();
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <div
        v-for="t in toast.toasts"
        :key="t.id"
        class="toast"
        :class="t.tone"
        :role="t.tone === 'error' ? 'alert' : 'status'"
      >
        <span class="msg">{{ t.message }}</span>
        <button type="button" class="ghost x" aria-label="Dismiss notification" @click="toast.dismiss(t.id)">
          <X :size="14" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  right: 1rem;
  bottom: 1rem;
  z-index: 300;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-width: min(420px, calc(100vw - 2rem));
}
@media (max-width: 700px) {
  .toast-host {
    bottom: calc(var(--tabbar-h) + 0.75rem + env(safe-area-inset-bottom));
    right: 0.75rem;
    left: 0.75rem;
  }
}
.toast {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.6rem 0.6rem 0.6rem 0.9rem;
  border-radius: var(--r-md);
  background: var(--c-bg3);
  border: 1px solid var(--c-line1);
  border-left: 3px solid var(--c-info);
  color: var(--c-ink1);
  font-size: 0.88rem;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.4);
}
.toast.success {
  border-left-color: var(--c-success);
}
.toast.error {
  border-left-color: var(--c-danger);
}
.msg {
  flex: 1;
}
.x {
  padding: 0.2rem;
  display: inline-flex;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity 160ms, transform 160ms;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
