<script setup lang="ts">
/**
 * Analytics → Tires. Three pieces off GET /analytics/tires:
 *   1. Cold-pressure trend, four corners in psi, outside temp on a right-hand
 *      axis (the MPG chart's overlay pattern) — cold pressure tracks the
 *      weather at ~1 psi per 10 °F, so a drop with the temperature is not a
 *      leak, one tire dropping alone is.
 *   2. Per-corner cards: current cold psi, cold trend (psi/week) with the
 *      provisional slow-leak flag, spread vs the highest tire with the shared
 *      2-psi LOW rule, median warm-up.
 *   3. Daily max tire temp per corner vs outside temp (same axis: both temps).
 * With a door placard set on the vehicle: a dashed baseline on the cold-
 * pressure chart ("placard 35 psi") and, per corner card, the server's
 * verdict on the current cold pressure ("−4 psi vs placard", below spec at
 * ≥ 3 psi under, under-inflated at ≥ 25 % under).
 * Empty state when the window has no tire data; never draws zeros.
 */
import { computed } from "vue";
import type uPlot from "uplot";
import StateCard from "@/components/StateCard.vue";
import InsightChart from "@/components/charts/InsightChart.vue";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import { convTempC, nf, tempUnitLabel } from "@/composables/useFormat";
import { niceTicks, padRange } from "@/lib/fuelInsights";
import { fixedScales, insightAxes, insightColors } from "@/lib/insightChartOpts";
import { coldPressureChart, dayLabel, dayTicks, populatedDays, tireTempChart, type TireDailyChart } from "@/lib/tireAnalytics";
import {
  TIRE_CORNERS,
  TIRE_CORNER_COLOR,
  TIRE_CORNER_LABEL,
  fToC,
  fmtPlacardDelta,
  fmtPsiPerWeek,
  fmtTirePressure,
  isSlowLeak,
  placardBaselinesPsi,
  placardLabel,
  tireSpreadPsi,
  tpmsReadings,
  type TireCorner,
} from "@/lib/vehicleSignals";

const props = defineProps<{
  vehicleId: string;
  days: number;
  /** Window tag next to the heading (the page's window name, or a cap note). */
  tag?: string;
}>();

const q = useAsync(
  () => api.getTireAnalytics(props.vehicleId, props.days),
  [() => props.vehicleId, () => props.days],
);

const DAY_MS = 86_400_000;
const fToDisplay = (f: number) => convTempC(fToC(f));
const outsideColor = () => insightColors().ink;

function cornerSeries(c: TireCorner): uPlot.Series {
  return {
    label: TIRE_CORNER_LABEL[c],
    stroke: TIRE_CORNER_COLOR[c],
    width: 1.6,
    // Join across days without a cold reading — it's a trend, and the dots
    // mark where the real samples are.
    spanGaps: true,
    points: { show: true, size: 6, width: 1.5, fill: TIRE_CORNER_COLOR[c] },
  };
}

interface Built {
  data: uPlot.AlignedData;
  opts: uPlot.Options;
  hover: { x: number; y: number }[];
  tips: { title: string; lines: { label: string; color: string; text: string }[]; outside: string | null }[];
  hasOutside: boolean;
}

function build(
  ch: TireDailyChart,
  fmtV: (v: number) => string,
  outsideOwnAxis: boolean,
  baselines: number[] = [],
): Built | null {
  if (!ch.hasCorner) return null;
  const x0 = ch.x[0] - DAY_MS / 2;
  const x1 = ch.x[ch.x.length - 1] + DAY_MS / 2;
  // Baselines join the range so the placard line is always on the plot.
  const vals = [
    ...TIRE_CORNERS.flatMap((c) => ch.corners[c]).filter((v): v is number => v != null),
    ...baselines,
  ];
  const outsideVals = ch.outside.filter((v): v is number => v != null);
  const yVals = outsideOwnAxis ? vals : [...vals, ...outsideVals];
  let y = padRange(yVals, 0.15);
  if (y[1] - y[0] < 4) {
    const mid = (y[0] + y[1]) / 2;
    y = [mid - 2, mid + 2];
  }
  const scales = fixedScales([x0, x1], y);
  const axes = insightAxes(dayTicks(ch.days), (v) => nf(0).format(v));
  const series: uPlot.Series[] = [{}, ...TIRE_CORNERS.map(cornerSeries)];
  const data: (number | null)[][] = [ch.x, ...TIRE_CORNERS.map((c) => ch.corners[c])];
  for (const b of baselines) {
    series.push({
      label: `placard ${b}`,
      stroke: outsideColor(),
      width: 1,
      dash: [6, 4],
      spanGaps: true,
      points: { show: false },
    });
    data.push(ch.x.map(() => b));
  }
  if (ch.hasOutside) {
    const outside: uPlot.Series = {
      label: "Outside",
      stroke: outsideColor(),
      width: 1.2,
      dash: [4, 3],
      spanGaps: false,
      points: { show: true, size: 4, fill: outsideColor() },
    };
    if (outsideOwnAxis) {
      const ty = padRange(outsideVals);
      scales.temp = { range: () => (ty[1] - ty[0] < 4 ? [ty[0] - 2, ty[1] + 2] : ty) };
      outside.scale = "temp";
      axes.push({
        scale: "temp",
        side: 1,
        splits: (_u, _i, min, max) => niceTicks(min, max, 4),
        values: (_u, splits) => splits.map((v) => `${nf(0).format(v)}°`),
        grid: { show: false },
        ticks: { show: false },
        size: 40,
      });
    }
    series.push(outside);
    data.push(ch.outside);
  }
  const idx = populatedDays(ch);
  return {
    data: data as uPlot.AlignedData,
    opts: { width: 600, height: 220, scales, axes, series },
    hover: idx.map((i) => {
      const ys = TIRE_CORNERS.map((c) => ch.corners[c][i]).filter((v): v is number => v != null);
      return { x: ch.x[i], y: Math.max(...ys) };
    }),
    tips: idx.map((i) => ({
      title: dayLabel(ch.days[i]),
      lines: TIRE_CORNERS.map((c) => ({
        label: TIRE_CORNER_LABEL[c],
        color: TIRE_CORNER_COLOR[c],
        text: ch.corners[c][i] != null ? fmtV(ch.corners[c][i] as number) : "—",
      })),
      outside: ch.outside[i] != null ? `${nf(0).format(ch.outside[i] as number)} ${tempUnitLabel()} outside` : null,
    })),
    hasOutside: ch.hasOutside,
  };
}

const placard = computed(() => {
  const a = q.data.value;
  if (!a || (a.placard_front_kpa == null && a.placard_rear_kpa == null)) return null;
  return { frontKpa: a.placard_front_kpa ?? null, rearKpa: a.placard_rear_kpa ?? null };
});
const placardText = computed(() => placardLabel(placard.value));
const coldChart = computed(() => {
  const a = q.data.value;
  if (!a || !a.has_data) return null;
  return build(
    coldPressureChart(a, fToDisplay),
    (v) => `${Math.round(v)} psi`,
    true,
    placardBaselinesPsi(placard.value),
  );
});
const tempChart = computed(() => {
  const a = q.data.value;
  if (!a || !a.has_data) return null;
  return build(
    tireTempChart(a, (c) => convTempC(c), fToDisplay),
    (v) => `${Math.round(v)} ${tempUnitLabel()}`,
    false,
  );
});

interface CornerCard {
  corner: TireCorner;
  label: string;
  cold: string;
  coldAt: string | null;
  slope: string;
  samples: number;
  leak: boolean;
  spread: string | null;
  low: boolean;
  warmup: string | null;
  /** "−4 psi vs placard"; null without a placard / cold reading. */
  vsPlacard: string | null;
  underInflated: boolean;
  belowSpec: boolean;
}
const cards = computed<CornerCard[]>(() => {
  const a = q.data.value;
  if (!a) return [];
  const byCorner = new Map(a.corners.map((h) => [h.corner, h]));
  const cold = Object.fromEntries(
    TIRE_CORNERS.map((c) => [c, byCorner.get(c)?.current_cold_kpa ?? null]),
  ) as Record<TireCorner, number | null>;
  const lows = tpmsReadings(cold);
  return lows.map((r) => {
    const h = byCorner.get(r.corner);
    const spread = tireSpreadPsi(cold, r.corner);
    const at = h?.current_cold_at ? new Date(h.current_cold_at) : null;
    return {
      corner: r.corner,
      label: r.label,
      cold: fmtTirePressure(h?.current_cold_kpa ?? null),
      coldAt: at ? at.toLocaleDateString([], { month: "short", day: "numeric" }) : null,
      slope: fmtPsiPerWeek(h?.slope_psi_per_week ?? null),
      samples: h?.slope_samples ?? 0,
      leak: isSlowLeak(h?.slope_psi_per_week ?? null, h?.slope_samples ?? 0),
      spread:
        spread == null ? null : Math.round(spread) === 0 ? "highest" : `−${Math.abs(Math.round(spread))} psi vs highest`,
      low: r.low,
      warmup: h?.warmup_median_psi != null ? `+${nf(1).format(h.warmup_median_psi)} psi` : null,
      vsPlacard: (() => {
        const d = fmtPlacardDelta(h?.below_placard_psi ?? null);
        return d == null ? null : `${d} psi vs placard`;
      })(),
      underInflated: !!h?.under_inflated,
      belowSpec: !!h?.below_spec,
    };
  });
});
const minSamples = computed(() => q.data.value?.slow_leak_min_samples ?? 4);
const parkHours = computed(() => q.data.value?.cold_park_hours ?? 3);
</script>

<template>
  <section class="card wide tires-section">
    <header class="head-inline">
      <h3>Tires <span v-if="tag" class="tag-fixed">{{ tag }}</span></h3>
      <span class="muted small">cold = first reading after parked ≥ {{ parkHours }} h · pressure in psi</span>
    </header>
    <StateCard v-if="q.loading.value && !q.data.value" state="loading" bare />
    <StateCard v-else-if="q.error.value" state="error" bare :message="q.error.value" @retry="q.reload()" />
    <StateCard
      v-else-if="!q.data.value?.has_data"
      state="empty"
      bare
      title="No tire data in this window."
      message="Tire pressures arrive from the WiCAN's TPMS polling (or the phone's Mode 22 polling while driving)."
    />
    <template v-else>
      <div class="corner-cards">
        <div
          v-for="c in cards"
          :key="c.corner"
          class="corner-card"
          :class="{ low: c.low, leak: c.leak, under: c.underInflated }"
        >
          <div class="cc-head">
            <i class="sw" :style="{ background: TIRE_CORNER_COLOR[c.corner] }" aria-hidden="true" />
            <span class="cc-name">{{ c.label }}</span>
            <span v-if="c.underInflated" class="flag danger">under-inflated</span>
            <span v-else-if="c.belowSpec" class="flag warn">below spec</span>
            <span v-if="c.low" class="flag warn">low</span>
            <span v-if="c.leak" class="flag danger">possible slow leak</span>
          </div>
          <div class="cc-big num">{{ c.cold }}</div>
          <div class="cc-sub muted small">cold<template v-if="c.coldAt"> · {{ c.coldAt }}</template></div>
          <dl class="cc-stats">
            <dt>Trend</dt>
            <dd class="num">
              <template v-if="c.samples >= minSamples">{{ c.slope }}</template>
              <span v-else class="muted">needs {{ minSamples }} cold readings ({{ c.samples }})</span>
            </dd>
            <dt>Spread</dt>
            <dd class="num">{{ c.spread ?? "—" }}</dd>
            <template v-if="placard">
              <dt>Placard</dt>
              <dd class="num" :class="{ 'warn-ink': c.belowSpec, 'danger-ink': c.underInflated }">
                {{ c.vsPlacard ?? "—" }}
              </dd>
            </template>
            <dt>Warm-up</dt>
            <dd class="num">{{ c.warmup ?? "—" }}</dd>
          </dl>
        </div>
      </div>

      <h4 class="sub-h">Cold pressure</h4>
      <div class="insight-legend">
        <span v-for="c in TIRE_CORNERS" :key="c"><i class="sw" :style="{ background: TIRE_CORNER_COLOR[c] }"></i>{{ TIRE_CORNER_LABEL[c] }}</span>
        <span v-if="coldChart?.hasOutside"><i class="sw outside"></i>Outside temp ({{ tempUnitLabel() }}, right axis)</span>
        <span v-if="placardText"><i class="sw outside"></i>{{ placardText }}</span>
      </div>
      <StateCard v-if="!coldChart" state="empty" bare title="No cold readings yet." message="A tire is read cold after the car has been parked a few hours." />
      <InsightChart
        v-else
        :data="coldChart.data"
        :options="coldChart.opts"
        :hover="coldChart.hover"
        mode="x"
        label="Daily cold tire pressure per corner with outside temperature"
      >
        <template #tip="{ index }">
          <span class="k">{{ coldChart.tips[index].title }}</span><br />
          <template v-for="l in coldChart.tips[index].lines" :key="l.label">
            <span class="k">{{ l.label }}</span> <b>{{ l.text }}</b>&nbsp;
          </template>
          <template v-if="coldChart.tips[index].outside"><br /><span class="k">{{ coldChart.tips[index].outside }}</span></template>
        </template>
      </InsightChart>

      <h4 class="sub-h">Tire temperature (daily max)</h4>
      <div class="insight-legend">
        <span v-for="c in TIRE_CORNERS" :key="c"><i class="sw" :style="{ background: TIRE_CORNER_COLOR[c] }"></i>{{ TIRE_CORNER_LABEL[c] }}</span>
        <span v-if="tempChart?.hasOutside"><i class="sw outside"></i>Outside temp (daily mean)</span>
      </div>
      <StateCard v-if="!tempChart" state="empty" bare title="No tire temperatures in this window." />
      <InsightChart
        v-else
        :data="tempChart.data"
        :options="tempChart.opts"
        :hover="tempChart.hover"
        mode="x"
        label="Daily maximum tire temperature per corner with outside temperature"
      >
        <template #tip="{ index }">
          <span class="k">{{ tempChart.tips[index].title }}</span><br />
          <template v-for="l in tempChart.tips[index].lines" :key="l.label">
            <span class="k">{{ l.label }}</span> <b>{{ l.text }}</b>&nbsp;
          </template>
          <template v-if="tempChart.tips[index].outside"><br /><span class="k">{{ tempChart.tips[index].outside }}</span></template>
        </template>
      </InsightChart>
      <p class="muted small foot">
        Cold pressure moves about 1 psi per 10 °F (≈5.6 °C) of outside temperature, so all
        four falling together with the weather is normal; one tire falling alone is the tell. The slow-leak flag
        (≤ {{ fmtPsiPerWeek(q.data.value!.slow_leak_psi_per_week) }} on {{ minSamples }}+ cold readings) is
        provisional. Days are UTC.
      </p>
    </template>
  </section>
</template>

<style scoped>
.head-inline {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 0.6rem;
  margin-bottom: 0.4rem;
  flex-wrap: wrap;
}
.head-inline h3 {
  margin: 0;
}
.corner-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
  gap: 0.6rem;
  margin: 0.4rem 0 1rem;
}
.corner-card {
  border: 1px solid var(--c-line1);
  border-radius: var(--r-md);
  padding: 0.6rem 0.75rem;
  min-width: 0;
}
.corner-card.low {
  border-color: rgba(255, 176, 32, 0.55);
  background: var(--c-warn-soft);
}
.corner-card.leak,
.corner-card.under {
  border-color: var(--c-danger);
}
.warn-ink {
  color: var(--c-warn);
}
.danger-ink {
  color: var(--c-danger);
}
.cc-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.4rem;
  font-size: 0.78rem;
}
.sw {
  display: inline-block;
  width: 12px;
  height: 3px;
  border-radius: 2px;
}
.insight-legend .sw.outside {
  height: 0;
  border-top: 1.5px dashed var(--c-ink2);
  border-radius: 0;
}
.cc-name {
  color: var(--c-ink2);
  font-weight: 500;
  letter-spacing: 0.04em;
}
.flag {
  font-size: 0.68rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.flag.warn {
  color: var(--c-warn);
}
.flag.danger {
  color: var(--c-danger);
}
.cc-big {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 1.5rem;
  font-weight: 500;
  color: var(--c-ink0);
  margin-top: 0.2rem;
}
.corner-card.low .cc-big {
  color: var(--c-warn);
}
.cc-sub {
  font-size: 0.72rem;
}
.cc-stats {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 0.2rem 0.6rem;
  margin: 0.5rem 0 0;
  font-size: 0.8rem;
}
.cc-stats dt {
  color: var(--c-ink3);
}
.cc-stats dd {
  margin: 0;
  text-align: right;
}
.sub-h {
  margin: 0.8rem 0 0.3rem;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--c-ink1);
}
.foot {
  margin: 0.6rem 0 0;
  font-size: 0.75rem;
  line-height: 1.4;
}
.num {
  font-variant-numeric: tabular-nums;
}
.small {
  font-size: 0.78rem;
}
</style>
