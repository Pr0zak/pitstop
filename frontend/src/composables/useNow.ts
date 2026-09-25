import { onBeforeUnmount, onMounted, ref } from "vue";

/** A reactive `Date.now()` that ticks every `ms` — for "Xm ago" labels. */
export function useNow(ms = 15_000) {
  const now = ref(Date.now());
  let t: number | null = null;
  onMounted(() => {
    t = window.setInterval(() => (now.value = Date.now()), ms);
  });
  onBeforeUnmount(() => {
    if (t != null) window.clearInterval(t);
  });
  return now;
}

/** "just now" / "4m ago" / "3h ago" / "2d ago" from an epoch-ms delta. */
export function agoLabel(thenMs: number | null | undefined, nowMs = Date.now()): string {
  if (thenMs == null || !Number.isFinite(thenMs)) return "never";
  const s = Math.max(0, Math.floor((nowMs - thenMs) / 1000));
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))}m ago`;
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86_400)}d ago`;
}
