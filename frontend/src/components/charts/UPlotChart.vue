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

function build() {
  if (!root.value) return;
  destroy();
  // Honor dynamic width based on container, leave height from caller.
  const opts: uPlot.Options = {
    ...props.options,
    width: root.value.clientWidth || props.options.width || 400,
    height: props.options.height ?? 240,
  };
  chart = new uPlot(opts, props.data, root.value);
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

watch(
  () => [props.data, props.options],
  () => build(),
  { deep: true },
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
