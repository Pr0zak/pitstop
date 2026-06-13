<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";

interface Props {
  data: uPlot.AlignedData;
  options: uPlot.Options;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: "ready", chart: uPlot): void;
}>();

const root = ref<HTMLDivElement | null>(null);
let chart: uPlot | null = null;
let ro: ResizeObserver | null = null;
// Tracks whether the user has manually zoomed the x-axis. Toggled by a
// `setScale` hook so we can avoid resetting the viewport on data refreshes
// and restore it across options-driven rebuilds. uPlot's runtime scale
// object also carries dataMin/dataMax but those aren't in its public typing,
// so we compare against the full data extent we know from props.
let userZoomedX: { min: number; max: number } | null = null;

function fullXExtent(): { min: number; max: number } | null {
  const xs = props.data?.[0] as number[] | undefined;
  if (!xs || xs.length === 0) return null;
  return { min: xs[0], max: xs[xs.length - 1] };
}

function build() {
  if (!root.value) return;
  destroy();
  // Honor dynamic width based on container, leave height from caller.
  const opts: uPlot.Options = {
    ...props.options,
    width: root.value.clientWidth || props.options.width || 400,
    height: props.options.height ?? 240,
    hooks: {
      ...props.options.hooks,
      setScale: [
        ...(props.options.hooks?.setScale ?? []),
        (u: uPlot, key: string) => {
          if (key !== "x") return;
          const x = u.scales.x;
          const full = fullXExtent();
          if (
            x.min == null ||
            x.max == null ||
            (full && x.min <= full.min && x.max >= full.max)
          ) {
            userZoomedX = null;
          } else {
            userZoomedX = { min: x.min, max: x.max };
          }
        },
      ],
    },
  };
  chart = new uPlot(opts, props.data, root.value);
  // Restore the user's zoom across an options-driven rebuild (series toggle,
  // colour change) so it doesn't yank the viewport back to full range.
  if (userZoomedX) {
    try {
      chart.setScale("x", { min: userZoomedX.min, max: userZoomedX.max });
    } catch {
      /* incompatible new data range — leave at auto */
    }
  }
  emit("ready", chart);
}

function destroy() {
  if (chart) {
    try {
      chart.destroy();
    } catch {
      /* ignore */
    }
    chart = null;
  }
}

onMounted(() => {
  build();
  if (typeof ResizeObserver !== "undefined" && root.value) {
    ro = new ResizeObserver(() => {
      if (chart && root.value) {
        chart.setSize({
          width: root.value.clientWidth,
          height: props.options.height ?? 240,
        });
      }
    });
    ro.observe(root.value);
  }
});

// Reference-watch, not deep-watch. Parents produce fresh arrays/objects via
// computed(), so identity changes are sufficient — and a deep traversal of
// large point arrays on every flush was expensive. When only `data` changes,
// uPlot.setData() updates in place and PRESERVES the current zoom; a full
// rebuild (which resets zoom) is reserved for an options-identity change.
watch(
  () => props.data,
  (next) => {
    if (!chart) {
      build();
      return;
    }
    // resetScales=false keeps the current x-zoom on a data refresh; when the
    // user hasn't zoomed we let uPlot re-fit to the new data extent.
    chart.setData(next, userZoomedX == null);
  },
);
watch(
  () => props.options,
  () => build(),
);

onBeforeUnmount(() => {
  if (ro) {
    ro.disconnect();
    ro = null;
  }
  destroy();
});
</script>

<template>
  <div ref="root" class="uplot-host" />
</template>

<style scoped>
.uplot-host {
  width: 100%;
}
</style>

<style>
/* Dark-mode tweaks for uPlot (global since uPlot uses fixed class names). */
.uplot,
.uplot .u-title {
  color: var(--c-text);
}
.uplot .u-legend {
  color: var(--c-muted);
  font-size: 0.78rem;
}
.uplot .u-axis,
.uplot .u-axis-tick {
  color: var(--c-muted);
}
</style>
