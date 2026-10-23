<script setup lang="ts">
/**
 * One /analytics/insights card. Reading order is the point: status in
 * plain words, the server's headline sentence as the biggest text, the
 * number that backs it on the right, then the chart, supporting detail,
 * a tip, and the method tucked behind a disclosure. Nothing is computed
 * here beyond layout (ADR-013).
 */
import { computed } from "vue";
import { ChevronRight, Lightbulb } from "lucide-vue-next";
import type { InsightCard, InsightStatus } from "@/api/types";
import { fmtInsightNumber, valueWithUnit } from "@/lib/insightCharts";
import InsightBars from "./InsightBars.vue";
import InsightLines from "./InsightLines.vue";
import InsightPoints from "./InsightPoints.vue";

const props = defineProps<{ card: InsightCard; flash?: boolean }>();

const STATUS_LABEL: Record<InsightStatus, string> = {
  good: "Good",
  watch: "Keep an eye on",
  bad: "Needs attention",
  info: "Insight",
  learning: "Learning",
};

const big = computed(() =>
  props.card.value == null ? null : valueWithUnit(props.card.value, props.card.value_unit),
);
const progress = computed(() => {
  const p = props.card.progress;
  if (!p) return null;
  const pct = p.need > 0 ? Math.min(100, Math.max(0, (p.have / p.need) * 100)) : 0;
  return {
    pct,
    text: `${fmtInsightNumber(p.have)} of ${fmtInsightNumber(p.need)} ${p.unit}`,
  };
});
</script>

<template>
  <article
    :id="`insight-${card.id}`"
    class="card ins"
    :class="[`st-${card.status}`, { flash }]"
    tabindex="-1"
    :aria-labelledby="`insight-${card.id}-title`"
  >
    <header class="top">
      <h3 :id="`insight-${card.id}-title`" class="title">{{ card.title }}</h3>
      <span class="status" :class="card.status">
        <i class="dot" aria-hidden="true" />{{ STATUS_LABEL[card.status] }}
      </span>
    </header>

    <div class="main">
      <p class="headline">{{ card.headline }}</p>
      <div v-if="big" class="big" :class="card.status">
        <span class="num">{{ big.prefix }}{{ big.number }}</span>
        <span v-if="big.suffix" class="big-unit">{{ big.suffix }}</span>
      </div>
    </div>

    <div v-if="progress" class="progress">
      <div
        class="track"
        role="progressbar"
        :aria-valuenow="card.progress!.have"
        aria-valuemin="0"
        :aria-valuemax="card.progress!.need"
        :aria-label="`Data collected: ${progress.text}`"
      >
        <span class="fill" :style="{ width: `${progress.pct}%` }" />
      </div>
      <span class="ptext">{{ progress.text }}</span>
    </div>

    <div v-if="card.chart" class="chart">
      <InsightBars v-if="card.chart.kind === 'bars'" :chart="card.chart" />
      <InsightLines v-else-if="card.chart.kind === 'lines'" :chart="card.chart" />
      <InsightPoints v-else-if="card.chart.kind === 'points'" :chart="card.chart" />
    </div>

    <p v-if="card.detail" class="detail">{{ card.detail }}</p>

    <div v-if="card.tip" class="tip">
      <Lightbulb :size="15" class="tip-ic" aria-hidden="true" />
      <span>{{ card.tip }}</span>
    </div>

    <footer class="foot">
      <details class="how">
        <summary>
          <ChevronRight :size="13" class="chev" aria-hidden="true" />How we worked this out
        </summary>
        <p>{{ card.how }}</p>
      </details>
      <span v-if="card.sample" class="sample">{{ card.sample }}</span>
    </footer>
  </article>
</template>

<style scoped>
.ins {
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
  min-width: 0;
  scroll-margin-top: calc(var(--topbar-h) + 12px);
  transition: box-shadow 400ms, border-color 400ms;
}
.ins.st-learning {
  align-self: start;
}
.ins:focus {
  outline: none;
}
.ins.st-bad {
  border-left: 3px solid var(--c-danger);
}
.ins.st-watch {
  border-left: 3px solid var(--c-warn);
}
.ins.flash {
  border-color: var(--c-line2);
  box-shadow: 0 0 0 2px var(--c-info-soft), 0 0 0 1px var(--c-info);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
}
.title {
  margin: 0;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--c-ink3);
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
  border: 1px solid var(--c-line1);
  color: var(--c-ink2);
}
.status .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.status.good {
  color: var(--c-success);
  background: var(--c-success-soft);
  border-color: rgba(74, 222, 128, 0.28);
}
.status.watch {
  color: var(--c-warn);
  background: var(--c-warn-soft);
  border-color: rgba(255, 176, 32, 0.28);
}
.status.bad {
  color: var(--c-danger);
  background: var(--c-danger-soft);
  border-color: rgba(255, 58, 46, 0.3);
}
.status.info {
  color: var(--c-info);
  background: var(--c-info-soft);
  border-color: rgba(96, 165, 250, 0.28);
}
.status.learning {
  color: var(--c-ink2);
  background: var(--c-bg3);
}

.main {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
}
.headline {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 16px;
  line-height: 1.4;
  font-weight: 500;
  color: var(--c-ink0);
  text-wrap: pretty;
}
.big {
  flex: none;
  max-width: 42%;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  text-align: right;
}
.big .num {
  font-size: 30px;
  line-height: 1;
  letter-spacing: -0.02em;
  font-weight: 500;
  color: var(--c-ink0);
}
.big.watch .num {
  color: var(--c-warn);
}
.big.bad .num {
  color: var(--c-danger);
}
.big-unit {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.25;
  color: var(--c-ink2);
}

.progress {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}
.track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: var(--c-bg4);
  overflow: hidden;
}
.fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--c-ink2);
}
.ptext {
  font-size: 12.5px;
  color: var(--c-ink2);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.chart {
  margin-top: 0.1rem;
}

.detail {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--c-ink2);
}

.tip {
  display: flex;
  gap: 0.55rem;
  align-items: flex-start;
  padding: 0.55rem 0.7rem;
  border-radius: var(--r-sm);
  background: var(--c-warn-soft);
  border: 1px solid rgba(255, 176, 32, 0.18);
  font-size: 13.5px;
  line-height: 1.45;
  color: var(--c-ink1);
}
.tip-ic {
  flex: none;
  margin-top: 2px;
  color: var(--c-warn);
}

.foot {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.3rem 1rem;
  margin-top: auto;
  padding-top: 0.15rem;
  font-size: 12px;
  color: var(--c-ink3);
}
.how {
  flex: 1 1 auto;
  min-width: 0;
}
.how summary {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  cursor: pointer;
  list-style: none;
  color: var(--c-ink2);
  border-radius: var(--r-sm);
}
.how summary::-webkit-details-marker {
  display: none;
}
.how summary:hover {
  color: var(--c-ink0);
}
.chev {
  transition: transform 120ms;
}
.how[open] .chev {
  transform: rotate(90deg);
}
.how p {
  margin: 0.4rem 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--c-ink2);
}
.sample {
  flex: none;
  padding-top: 1px;
}

@media (max-width: 520px) {
  .main {
    flex-direction: column-reverse;
    gap: 0.35rem;
  }
  .big {
    max-width: none;
    flex-direction: row;
    align-items: baseline;
    gap: 0.4rem;
    text-align: left;
  }
  .big .num {
    font-size: 26px;
  }
  .big-unit {
    margin-top: 0;
  }
}
</style>
