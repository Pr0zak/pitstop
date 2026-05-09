<script setup lang="ts">
/**
 * Connection / status pill — claude.ai/design canonical.
 *
 * Four primary states map onto the four telemetry conditions the bridge
 * surfaces:
 *
 *   healthy    — broker + WiCAN both up, frames flowing
 *   connecting — handshake in progress (bus reconnect, BLE pair, etc.)
 *   degraded   — connection alive but stale (no frames in N seconds)
 *   offline    — explicit failure (broker unreachable, BLE disconnected)
 *
 * The "neutral" variant is the bare-metal pill with no semantic color,
 * used by the sidebar version chip and similar.
 *
 * Healthy dots get a soft halo so a glance at the page picks up the
 * "live" signal even without reading the label. Connecting dots pulse
 * (ps-pulse-dot animation) to reinforce the in-progress feel.
 */
type PillState = "healthy" | "connecting" | "degraded" | "offline" | "neutral";

const props = withDefaults(
  defineProps<{
    state?: PillState;
    label?: string;
    dot?: boolean;
    mono?: boolean;
    compact?: boolean;
  }>(),
  {
    state: "healthy",
    dot: true,
    mono: false,
    compact: false,
  },
);
</script>

<template>
  <span
    class="pill"
    :class="[`state-${props.state}`, { compact: props.compact, mono: props.mono }]"
  >
    <span
      v-if="props.dot"
      class="dot"
      :class="{ pulse: props.state === 'connecting', halo: props.state === 'healthy' }"
    />
    <span class="label"><slot>{{ props.label }}</slot></span>
  </span>
</template>

<style scoped>
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.4;
  white-space: nowrap;
  border: 1px solid var(--c-line1);
  background: rgba(255, 255, 255, 0.04);
  color: var(--c-ink2);
}
.pill.compact {
  padding: 2px 8px;
  font-size: 11px;
}
.pill.mono .label {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--c-ink3);
}
.dot.pulse {
  animation: ps-pulse-dot 1.6s cubic-bezier(0.2, 0.7, 0.3, 1) infinite;
}
.dot.halo {
  box-shadow: 0 0 6px currentColor;
}

@keyframes ps-pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

/* ── State colour overrides ─── */
.state-healthy {
  background: rgba(74, 222, 128, 0.10);
  border-color: rgba(74, 222, 128, 0.30);
  color: var(--c-success);
}
.state-healthy .dot {
  background: var(--c-success);
  color: var(--c-success);
}

.state-connecting {
  background: rgba(255, 176, 32, 0.10);
  border-color: rgba(255, 176, 32, 0.30);
  color: var(--c-warn);
}
.state-connecting .dot {
  background: var(--c-warn);
}

.state-degraded {
  background: rgba(255, 176, 32, 0.08);
  border-color: rgba(255, 176, 32, 0.24);
  color: var(--c-warn);
}
.state-degraded .dot {
  background: var(--c-warn);
}

.state-offline {
  background: rgba(255, 58, 46, 0.10);
  border-color: rgba(255, 58, 46, 0.28);
  color: var(--c-danger);
}
.state-offline .dot {
  background: var(--c-danger);
}

.state-neutral {
  /* falls through to defaults */
}
</style>
