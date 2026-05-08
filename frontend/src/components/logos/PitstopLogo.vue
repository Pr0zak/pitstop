<script setup lang="ts">
import type { LogoName } from "./PitstopLogos";

interface Props {
  name: LogoName;
  size?: number;
  color?: string;
  // For 'wrench-gauge': the wrench accent. Defaults to the same color.
  accent?: string;
}
const props = withDefaults(defineProps<Props>(), {
  size: 32,
  color: "currentColor",
  accent: "currentColor",
});
</script>

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
    <!-- arc — speedo with needle dot -->
    <g v-if="name === 'arc'">
      <path
        d="M 10 44 A 22 22 0 0 1 54 44"
        :stroke="color"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="M 32 44 L 46 24"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
        opacity="0.55"
      />
      <circle cx="46" cy="24" r="4" :fill="color" />
      <circle cx="32" cy="44" r="2.5" :fill="color" />
    </g>

    <!-- arc-redline — base arc with last quarter painted warn-color -->
    <g v-if="name === 'arc-redline'">
      <path
        d="M 10 44 A 22 22 0 0 1 42 25"
        :stroke="color"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="M 42 25 A 22 22 0 0 1 54 44"
        :stroke="accent"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <path
        d="M 32 44 L 49 30"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
        opacity="0.55"
      />
      <circle cx="49" cy="30" r="4" :fill="accent" />
      <circle cx="32" cy="44" r="2.5" :fill="color" />
    </g>

    <!-- arc-ticks — arc with five perpendicular tick marks -->
    <g v-if="name === 'arc-ticks'">
      <path
        d="M 10 44 A 22 22 0 0 1 54 44"
        :stroke="color"
        stroke-width="3.5"
        stroke-linecap="round"
        fill="none"
      />
      <!-- Five tick marks at 180/135/90/45/0 degrees from center (32,44) -->
      <line x1="10" y1="44" x2="14" y2="44" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="16.5" y1="28.5" x2="19.4" y2="31.4" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="32" y1="22" x2="32" y2="26" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="47.5" y1="28.5" x2="44.6" y2="31.4" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="54" y1="44" x2="50" y2="44" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <!-- Needle at ¾ position -->
      <path
        d="M 32 44 L 47.5 28.5"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
      />
      <circle cx="32" cy="44" r="3" :fill="color" />
    </g>

    <!-- arc-dotted — arc rendered as a row of dots with brighter end dot -->
    <g v-if="name === 'arc-dotted'">
      <!-- 9 dots traced along the arc r=22, center (32,44), 180°→0° -->
      <circle cx="10" cy="44" r="2.2" :fill="color" />
      <circle cx="13.2" cy="35.6" r="2.2" :fill="color" />
      <circle cx="19.4" cy="28.6" r="2.2" :fill="color" />
      <circle cx="26.6" cy="23.6" r="2.2" :fill="color" />
      <circle cx="32" cy="22" r="2.2" :fill="color" />
      <circle cx="37.4" cy="23.6" r="2.2" :fill="color" />
      <circle cx="44.6" cy="28.6" r="2.2" :fill="color" />
      <circle cx="50.8" cy="35.6" r="2.2" :fill="color" />
      <circle cx="54" cy="44" r="3.4" :fill="accent" />
      <!-- Faint needle line + pivot -->
      <path
        d="M 32 44 L 54 44"
        :stroke="color"
        stroke-width="2.5"
        stroke-linecap="round"
        opacity="0.45"
      />
      <circle cx="32" cy="44" r="2.5" :fill="color" />
    </g>

    <!-- arc-segments — three coloured arc zones -->
    <g v-if="name === 'arc-segments'">
      <!-- green zone: 180°→120° -->
      <path
        d="M 10 44 A 22 22 0 0 1 21 25"
        stroke="#3fb950"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <!-- amber zone: 120°→60° -->
      <path
        d="M 21 25 A 22 22 0 0 1 43 25"
        stroke="#d29922"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <!-- red zone: 60°→0° -->
      <path
        d="M 43 25 A 22 22 0 0 1 54 44"
        :stroke="accent"
        stroke-width="5"
        stroke-linecap="round"
        fill="none"
      />
      <!-- Needle pointing into the amber zone -->
      <path
        d="M 32 44 L 32 22"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
      />
      <circle cx="32" cy="44" r="3" :fill="color" />
    </g>

    <!-- arc-needle — no arc; just the needle and pivot -->
    <g v-if="name === 'arc-needle'">
      <!-- Long needle from pivot to upper-right -->
      <path
        d="M 32 44 L 50 22"
        :stroke="color"
        stroke-width="5"
        stroke-linecap="round"
      />
      <!-- Tip dot for emphasis -->
      <circle cx="50" cy="22" r="4" :fill="color" />
      <!-- Pivot ring -->
      <circle
        cx="32"
        cy="44"
        r="6"
        :stroke="color"
        stroke-width="3"
        fill="none"
      />
      <circle cx="32" cy="44" r="1.6" :fill="color" />
    </g>

    <!-- arc-360 — full circular tach with a single 12-o-clock tick + needle -->
    <g v-if="name === 'arc-360'">
      <circle
        cx="32"
        cy="32"
        r="22"
        :stroke="color"
        stroke-width="3.5"
        fill="none"
      />
      <line x1="32" y1="10" x2="32" y2="14" :stroke="color" stroke-width="3" stroke-linecap="round" />
      <line x1="50" y1="32" x2="54" y2="32" :stroke="color" stroke-width="3" stroke-linecap="round" />
      <line x1="32" y1="50" x2="32" y2="54" :stroke="color" stroke-width="3" stroke-linecap="round" />
      <line x1="10" y1="32" x2="14" y2="32" :stroke="color" stroke-width="3" stroke-linecap="round" />
      <!-- Needle to ~2 o'clock -->
      <path
        d="M 32 32 L 47 21"
        :stroke="color"
        stroke-width="3.5"
        stroke-linecap="round"
      />
      <circle cx="47" cy="21" r="3.5" :fill="color" />
      <circle cx="32" cy="32" r="3.5" :fill="color" />
    </g>

    <!-- wrench-gauge — dial + wrench -->
    <g v-if="name === 'wrench-gauge'">
      <circle
        cx="32"
        cy="32"
        r="20"
        :stroke="color"
        stroke-width="3.5"
        fill="none"
      />
      <path
        d="M 32 32 L 32 18"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
      />
      <circle cx="32" cy="32" r="2.5" :fill="color" />
      <path
        d="M 42 42 L 50 50 M 46 38 a 5 5 0 1 0 -8 8"
        :stroke="accent"
        stroke-width="3.5"
        stroke-linecap="round"
        fill="none"
      />
    </g>

    <!-- lights — three start lights -->
    <g v-if="name === 'lights'">
      <rect
        x="22"
        y="6"
        width="20"
        height="52"
        rx="4"
        :stroke="color"
        stroke-width="3"
        fill="none"
      />
      <circle cx="32" cy="18" r="4" :fill="color" />
      <circle cx="32" cy="32" r="4" :fill="color" />
      <circle cx="32" cy="46" r="4" :fill="color" opacity="0.4" />
    </g>

    <!-- spark — spark plug + bolt -->
    <g v-if="name === 'spark'">
      <rect
        x="26"
        y="6"
        width="12"
        height="22"
        rx="2"
        :stroke="color"
        stroke-width="3"
        fill="none"
      />
      <line x1="26" y1="14" x2="38" y2="14" :stroke="color" stroke-width="2" />
      <line x1="26" y1="20" x2="38" y2="20" :stroke="color" stroke-width="2" />
      <path
        d="M 32 28 L 32 36"
        :stroke="color"
        stroke-width="3"
        stroke-linecap="round"
      />
      <path
        d="M 36 36 L 26 50 L 32 50 L 28 60 L 42 44 L 36 44 L 40 36 Z"
        :fill="color"
      />
    </g>

    <!-- wheel — three-spoke steering wheel -->
    <g v-if="name === 'wheel'">
      <circle
        cx="32"
        cy="32"
        r="22"
        :stroke="color"
        stroke-width="3.5"
        fill="none"
      />
      <circle cx="32" cy="32" r="6" :fill="color" />
      <line x1="32" y1="38" x2="32" y2="54" :stroke="color" stroke-width="3.5" stroke-linecap="round" />
      <line x1="27" y1="34" x2="13" y2="42" :stroke="color" stroke-width="3.5" stroke-linecap="round" />
      <line x1="37" y1="34" x2="51" y2="42" :stroke="color" stroke-width="3.5" stroke-linecap="round" />
    </g>

    <!-- tread-p — tire tread P -->
    <g v-if="name === 'tread-p'">
      <path
        d="M 16 56 L 16 12 L 38 12 a 12 12 0 0 1 0 24 L 24 36"
        :stroke="color"
        stroke-width="6"
        stroke-linecap="round"
        stroke-linejoin="round"
        fill="none"
      />
      <line x1="14" y1="20" x2="20" y2="20" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="14" y1="28" x2="20" y2="28" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="14" y1="36" x2="20" y2="36" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="14" y1="44" x2="20" y2="44" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
      <line x1="14" y1="52" x2="20" y2="52" :stroke="color" stroke-width="2.5" stroke-linecap="round" />
    </g>
  </svg>
</template>
