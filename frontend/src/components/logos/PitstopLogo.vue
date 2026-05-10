<script setup lang="ts">
import type { LogoName } from "./PitstopLogos";

interface Props {
  name: LogoName;
  size?: number;
  color?: string;
  // Warn-color used for the redline segment + ticks. Defaults to color.
  accent?: string;
}
const props = withDefaults(defineProps<Props>(), {
  size: 32,
  color: "currentColor",
  accent: "currentColor",
});

// Stable per-instance gradient id so multiple logos on one page
// don't collide when they share the same name (only the -fill
// variant uses it, but cheap to keep general).
const gid = `pl-${Math.random().toString(36).slice(2, 9)}`;
</script>

<!--
  All variants share the same geometry:
    centre   (32, 33)   — visual centre nudged 1px down
    radius   24
    cool arc -135°  → +95°
    redline  +95°  → +135°
    majors at -135, -90, -45, 0, +45, +90, +135 (every 45°)

  Refinements layer onto the baseline rather than rewriting it,
  so a quick visual diff reveals what each variant changes.
-->

<template>
  <svg
    :width="props.size"
    :height="props.size"
    viewBox="0 0 64 64"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    role="img"
    aria-label="pitstop logo"
  >
    <!-- Baseline ─────────────────────────────────────────────── -->
    <g v-if="name === 'arc-ticks-redline'">
      <path
        d="M 15.03 50 A 24 24 0 1 1 55.86 39.04"
        :stroke="color" stroke-width="3.5" stroke-linecap="butt"
        fill="none" opacity="0.92"
      />
      <path
        d="M 55.86 39.04 A 24 24 0 0 1 48.97 50"
        :stroke="accent" stroke-width="3.5" stroke-linecap="round"
        fill="none"
      />
      <line x1="15.03" y1="50"    x2="18.21" y2="46.82" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="13.76" y1="38.27" x2="18.13" y2="37.10" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="20.74" y1="22.74" x2="23.92" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="32"    y1="9"     x2="32"    y2="13.5" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="43.26" y1="22.74" x2="40.08" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="50.24" y1="38.27" x2="45.87" y2="37.10" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <line x1="48.97" y1="50"    x2="45.79" y2="46.82" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <circle cx="32" cy="33" r="2" :fill="accent"/>
    </g>

    <!-- + Needle ─────────────────────────────────────────────── -->
    <!--
      Same baseline; adds a tapered needle from the hub pointing at
      the redline boundary tick (50.24, 38.27 — the +90° major).
      Needle stops at 80 % of radius so it never crashes into the
      arc, and the tip reads as a triangular pointer rather than a
      blunt line.
    -->
    <g v-if="name === 'arc-ticks-redline-needle'">
      <path
        d="M 15.03 50 A 24 24 0 1 1 55.86 39.04"
        :stroke="color" stroke-width="3.5" stroke-linecap="butt"
        fill="none" opacity="0.92"
      />
      <path
        d="M 55.86 39.04 A 24 24 0 0 1 48.97 50"
        :stroke="accent" stroke-width="3.5" stroke-linecap="round"
        fill="none"
      />
      <line x1="15.03" y1="50"    x2="18.21" y2="46.82" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="13.76" y1="38.27" x2="18.13" y2="37.10" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="20.74" y1="22.74" x2="23.92" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="32"    y1="9"     x2="32"    y2="13.5" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="43.26" y1="22.74" x2="40.08" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="50.24" y1="38.27" x2="45.87" y2="37.10" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <line x1="48.97" y1="50"    x2="45.79" y2="46.82" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <!--
        Needle tip = hub + 0.80 * (boundary_tick - hub)
                   = (32, 33) + 0.80 * (50.24-32, 38.27-33)
                   = (46.59, 37.22)
        Triangle: tip + two base points 2 px on either side of the
        perpendicular through hub→tip, giving a pointer that reads
        as a needle even at 16 px.
      -->
      <path
        d="M 46.59 37.22 L 31.45 34.07 L 32.55 31.93 Z"
        :fill="color"
      />
      <circle cx="32" cy="33" r="2.6" :fill="accent"/>
    </g>

    <!-- + Fine sub-ticks ─────────────────────────────────────── -->
    <!--
      Baseline + six minor sub-ticks halfway between the majors,
      drawn at radius 23 → 21.5, half the major weight. Cool side
      uses `color`; the sub-ticks at +112.5° fall inside the
      redline so they pick up `accent`.
    -->
    <g v-if="name === 'arc-ticks-redline-fine'">
      <path
        d="M 15.03 50 A 24 24 0 1 1 55.86 39.04"
        :stroke="color" stroke-width="3.5" stroke-linecap="butt"
        fill="none" opacity="0.92"
      />
      <path
        d="M 55.86 39.04 A 24 24 0 0 1 48.97 50"
        :stroke="accent" stroke-width="3.5" stroke-linecap="round"
        fill="none"
      />
      <line x1="15.03" y1="50"    x2="18.21" y2="46.82" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="13.76" y1="38.27" x2="18.13" y2="37.10" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="20.74" y1="22.74" x2="23.92" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="32"    y1="9"     x2="32"    y2="13.5" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="43.26" y1="22.74" x2="40.08" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="50.24" y1="38.27" x2="45.87" y2="37.10" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <line x1="48.97" y1="50"    x2="45.79" y2="46.82" :stroke="accent" stroke-width="1.4" stroke-linecap="round"/>
      <!-- Sub-ticks at -112.5°, -67.5°, -22.5°, +22.5°, +67.5°, +112.5° -->
      <line x1="13.21" y1="44.20" x2="15.20" y2="42.97" :stroke="color"  stroke-width="0.9" stroke-linecap="round" opacity="0.7"/>
      <line x1="16.07" y1="30.18" x2="17.93" y2="31.61" :stroke="color"  stroke-width="0.9" stroke-linecap="round" opacity="0.7"/>
      <line x1="25.79" y1="14.05" x2="27.06" y2="16.05" :stroke="color"  stroke-width="0.9" stroke-linecap="round" opacity="0.7"/>
      <line x1="38.21" y1="14.05" x2="36.94" y2="16.05" :stroke="color"  stroke-width="0.9" stroke-linecap="round" opacity="0.7"/>
      <line x1="47.93" y1="30.18" x2="46.07" y2="31.61" :stroke="color"  stroke-width="0.9" stroke-linecap="round" opacity="0.7"/>
      <line x1="50.79" y1="44.20" x2="48.80" y2="42.97" :stroke="accent" stroke-width="0.9" stroke-linecap="round" opacity="0.85"/>
      <circle cx="32" cy="33" r="2" :fill="accent"/>
    </g>

    <!-- · Bold ───────────────────────────────────────────────── -->
    <!--
      Baseline silhouette but drawn with heavier weights so the mark
      survives at favicon / sidebar size. Arc 4.5 (was 3.5), ticks
      2 (was 1.4), hub 2.8 (was 2). Tick endpoints reflect the new
      tick length (+1 px each side along the radial).
    -->
    <g v-if="name === 'arc-ticks-redline-bold'">
      <path
        d="M 15.03 50 A 24 24 0 1 1 55.86 39.04"
        :stroke="color" stroke-width="4.5" stroke-linecap="butt"
        fill="none" opacity="0.95"
      />
      <path
        d="M 55.86 39.04 A 24 24 0 0 1 48.97 50"
        :stroke="accent" stroke-width="4.5" stroke-linecap="round"
        fill="none"
      />
      <line x1="15.03" y1="50"    x2="18.91" y2="46.12" :stroke="color"  stroke-width="2" stroke-linecap="round"/>
      <line x1="13.76" y1="38.27" x2="19.10" y2="36.85" :stroke="color"  stroke-width="2" stroke-linecap="round"/>
      <line x1="20.74" y1="22.74" x2="24.62" y2="26.62" :stroke="color"  stroke-width="2" stroke-linecap="round"/>
      <line x1="32"    y1="9"     x2="32"    y2="14.5" :stroke="color"  stroke-width="2" stroke-linecap="round"/>
      <line x1="43.26" y1="22.74" x2="39.38" y2="26.62" :stroke="color"  stroke-width="2" stroke-linecap="round"/>
      <line x1="50.24" y1="38.27" x2="44.90" y2="36.85" :stroke="accent" stroke-width="2" stroke-linecap="round"/>
      <line x1="48.97" y1="50"    x2="45.09" y2="46.12" :stroke="accent" stroke-width="2" stroke-linecap="round"/>
      <circle cx="32" cy="33" r="2.8" :fill="accent"/>
    </g>

    <!-- · Filled redline (gradient cap) ──────────────────────── -->
    <!--
      Cool body strokes thinner (3) so the redline tip reads as a
      thicker cap; the redline itself is drawn twice — first as the
      baseline accent stroke, then a thicker overlay capped by a
      linearGradient that fades from cool → accent across the +95°
      boundary. Hot-metal feel without losing legibility.
    -->
    <g v-if="name === 'arc-ticks-redline-fill'">
      <defs>
        <linearGradient :id="gid" x1="55.86" y1="39.04" x2="48.97" y2="50" gradientUnits="userSpaceOnUse">
          <stop offset="0" :stop-color="color" stop-opacity="0.4"/>
          <stop offset="0.4" :stop-color="accent" stop-opacity="1"/>
          <stop offset="1" :stop-color="accent" stop-opacity="1"/>
        </linearGradient>
      </defs>
      <path
        d="M 15.03 50 A 24 24 0 1 1 55.86 39.04"
        :stroke="color" stroke-width="3" stroke-linecap="butt"
        fill="none" opacity="0.92"
      />
      <path
        d="M 55.86 39.04 A 24 24 0 0 1 48.97 50"
        :stroke="`url(#${gid})`" stroke-width="5" stroke-linecap="round"
        fill="none"
      />
      <line x1="15.03" y1="50"    x2="18.21" y2="46.82" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="13.76" y1="38.27" x2="18.13" y2="37.10" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="20.74" y1="22.74" x2="23.92" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="32"    y1="9"     x2="32"    y2="13.5" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <line x1="43.26" y1="22.74" x2="40.08" y2="25.92" :stroke="color"  stroke-width="1.4" stroke-linecap="round" opacity="0.9"/>
      <circle cx="32" cy="33" r="2" :fill="accent"/>
    </g>
  </svg>
</template>
