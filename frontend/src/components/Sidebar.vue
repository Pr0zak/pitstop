<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { RouterLink, useRoute } from "vue-router";
import {
  LayoutDashboard,
  Activity,
  Route,
  BarChart3,
  Fuel,
  Wrench,
  AlertTriangle,
  Car,
  FileJson,
  Settings as SettingsIcon,
  ChevronLeft,
  ChevronRight,
} from "lucide-vue-next";
import PitstopLogo from "@/components/logos/PitstopLogo.vue";
import {
  STORAGE_KEY as LOGO_KEY,
  DEFAULT_LOGO,
  type LogoName,
} from "@/components/logos/PitstopLogos";

const logoName = ref<LogoName>(DEFAULT_LOGO);
function loadLogo() {
  try {
    const v = localStorage.getItem(LOGO_KEY) as LogoName | null;
    if (v) logoName.value = v;
  } catch {
    /* ignore */
  }
}
function onLogoChanged(e: Event) {
  const detail = (e as CustomEvent<LogoName>).detail;
  if (detail) logoName.value = detail;
}
onMounted(() => {
  loadLogo();
  window.addEventListener("pitstop-logo-changed", onLogoChanged);
});
onBeforeUnmount(() => {
  window.removeEventListener("pitstop-logo-changed", onLogoChanged);
});

const COLLAPSED_KEY = "pitstop_sidebar_collapsed";
const collapsed = ref<boolean>(
  (() => {
    try {
      return localStorage.getItem(COLLAPSED_KEY) === "1";
    } catch {
      return false;
    }
  })(),
);

function toggle() {
  collapsed.value = !collapsed.value;
  try {
    localStorage.setItem(COLLAPSED_KEY, collapsed.value ? "1" : "0");
  } catch {
    /* ignore */
  }
}

const route = useRoute();

const items = [
  { to: "/", label: "Overview", icon: LayoutDashboard },
  { to: "/live", label: "Live", icon: Activity },
  { to: "/trips", label: "Trips", icon: Route },
  { to: "/analytics", label: "Analytics", icon: BarChart3 },
  { to: "/fuel", label: "Fuel", icon: Fuel },
  { to: "/maintenance", label: "Maintenance", icon: Wrench },
  { to: "/dtcs", label: "DTCs", icon: AlertTriangle },
  { to: "/vehicles", label: "Vehicles", icon: Car },
  { to: "/profiles", label: "Profiles", icon: FileJson },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
];

function isActive(to: string): boolean {
  if (to === "/") return route.path === "/";
  return route.path === to || route.path.startsWith(to + "/");
}

const widthVar = computed(() =>
  collapsed.value ? "var(--sidebar-w-collapsed)" : "var(--sidebar-w)",
);
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }" :style="{ width: widthVar }">
    <RouterLink to="/logos" class="brand" title="Pick a logo">
      <div class="logo">
        <PitstopLogo :name="logoName" :size="22" color="var(--c-accent)" accent="var(--c-warn)" />
      </div>
      <span v-if="!collapsed" class="brand-name">pitstop</span>
    </RouterLink>
    <nav>
      <RouterLink
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
        :title="collapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="18" />
        <span v-if="!collapsed" class="label">{{ item.label }}</span>
      </RouterLink>
    </nav>
    <button class="collapse" @click="toggle" :title="collapsed ? 'Expand' : 'Collapse'">
      <ChevronRight v-if="collapsed" :size="16" />
      <ChevronLeft v-else :size="16" />
    </button>
  </aside>
</template>

<style scoped>
.sidebar {
  background: var(--c-surface);
  border-right: 1px solid var(--c-border-soft);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  transition: width 0.15s ease;
}
.brand {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0 1rem;
  height: var(--topbar-h);
  border-bottom: 1px solid var(--c-border-soft);
}
.logo {
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  background: var(--c-accent);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 0.85rem;
  letter-spacing: 0.02em;
  flex-shrink: 0;
}
.brand-name {
  font-weight: 600;
  font-size: 1rem;
  letter-spacing: -0.01em;
}
nav {
  display: flex;
  flex-direction: column;
  padding: 0.5rem;
  gap: 2px;
  flex: 1;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  padding: 0.55rem 0.7rem;
  border-radius: var(--r-sm);
  color: var(--c-muted);
  text-decoration: none;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
}
.nav-item:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
  text-decoration: none;
}
.nav-item.active {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.collapsed .nav-item {
  justify-content: center;
  padding: 0.55rem 0;
}
.label {
  flex: 1;
}
.collapse {
  margin: 0.5rem;
  padding: 0.4rem;
  background: transparent;
  border: 1px solid var(--c-border-soft);
  color: var(--c-muted);
  display: flex;
  align-items: center;
  justify-content: center;
}
.collapse:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}
</style>
