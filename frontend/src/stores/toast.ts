import { defineStore } from "pinia";
import { ref } from "vue";

export type ToastTone = "info" | "success" | "error";
export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
}

/** App-wide transient notices. Replaces alert() for outcomes. */
export const useToastStore = defineStore("toast", () => {
  const toasts = ref<Toast[]>([]);
  let seq = 0;

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }
  function push(message: string, tone: ToastTone = "info", ms = 4500) {
    const id = ++seq;
    toasts.value = [...toasts.value, { id, message, tone }];
    if (ms > 0) window.setTimeout(() => dismiss(id), ms);
    return id;
  }
  return {
    toasts,
    push,
    dismiss,
    success: (m: string) => push(m, "success"),
    error: (m: string) => push(m, "error", 7000),
    info: (m: string) => push(m, "info"),
  };
});

/** Pull a human message out of an axios / fetch error. */
export function errMessage(e: unknown, fallback = "Request failed"): string {
  const ax = e as { response?: { data?: { detail?: unknown } } };
  const detail = ax?.response?.data?.detail;
  if (typeof detail === "string" && detail) return detail;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}
