<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from "vue";
import { RouterLink, useRoute } from "vue-router";
import {
  LayoutDashboard,
  LayoutGrid,
  Activity,
  Route,
  BarChart3,
  Fuel,
  Wrench,
  AlertTriangle,
  Bug,
  Car,
  FileJson,
  HelpCircle,
  Map as MapIcon,
  Settings as SettingsIcon,
  ChevronLeft,
  ChevronRight,
  Github,
} from "lucide-vue-next";
import PitstopLogo from "@/components/logos/PitstopLogo.vue";
import UpdateModal from "@/components/UpdateModal.vue";
import {
  LOGOS,
  STORAGE_KEY as LOGO_KEY,
  DEFAULT_LOGO,
  type LogoName,
} from "@/components/logos/PitstopLogos";
import { getVersion, getLatestGithubRelease, compareVersions } from "@/api/endpoints";

const serverVersion = ref<string | null>(null);
const serverSha = ref<string | null>(null);
const latestVersion = ref<string | null>(null);

// True only when the deployed server is strictly behind the latest
// GitHub release (UX-2). Stays false when the comparison can't be
// made (offline, GitHub rate-limit, dev build "v0.0.0-…").
const updateAvailable = computed<boolean>(() => {
  const cur = serverVersion.value;
  const latest = latestVersion.value;
  if (!cur || !latest) return false;
  if (cur.startsWith("0.0.0") || cur === "dev") return false;
  return compareVersions(cur, latest) < 0;
});

async function loadVersion() {
  try {
    const v = await getVersion();
    serverVersion.value = v.version;
    serverSha.value = v.git_sha;
  } catch {
    /* ignore — /version isn't critical */
  }
}
async function loadLatestRelease() {
  const r = await getLatestGithubRelease();
  if (r) {
    latestVersion.value = r.tag_name.replace(/^v/i, "");
  }
}

const logoName = ref<LogoName>(DEFAULT_LOGO);
function loadLogo() {
  try {
    const v = localStorage.getItem(LOGO_KEY) as LogoName | null;
    // Validate: stale localStorage values from removed variants
    // would render an empty SVG. Fall back to the default.
    if (v && LOGOS.some((l) => l.name === v)) logoName.value = v;
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
  void loadVersion();
  void loadLatestRelease();
  window.addEventListener("pitstop-logo-changed", onLogoChanged);
});
onBeforeUnmount(() => {
  window.removeEventListener("pitstop-logo-changed", onLogoChanged);
});

const updateModalOpen = ref<boolean>(false);

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

// Primary nav (top of sidebar) → live/operational items.
// Secondary nav (bottom) → admin / setup / config that the user
// touches once and rarely returns to.
const items = [
  { to: "/", label: "Overview", icon: LayoutDashboard },
  { to: "/fleet", label: "Fleet", icon: LayoutGrid },
  { to: "/live", label: "Live", icon: Activity },
  { to: "/trips", label: "Trips", icon: Route },
  { to: "/heatmap", label: "Map", icon: MapIcon },
  { to: "/analytics", label: "Analytics", icon: BarChart3 },
  { to: "/fuel", label: "Fuel", icon: Fuel },
  { to: "/maintenance", label: "Maintenance", icon: Wrench },
  { to: "/dtcs", label: "DTCs", icon: AlertTriangle },
];
const secondaryItems = [
  { to: "/vehicles", label: "Vehicles", icon: Car },
  { to: "/profiles", label: "Profiles", icon: FileJson },
  { to: "/debug", label: "Debug", icon: Bug },
  { to: "/setup", label: "Setup", icon: HelpCircle },
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
        <!-- White cool body so the mark reads on the dark plate; redline
             stays warm coral so it pops the same way it does in the live
             gauge cluster. Mirrors the design's PitstopLauncher tile. -->
        <PitstopLogo :name="logoName" :size="22" color="#e7e9ee" accent="#ff5b3a" />
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
    <nav class="nav-secondary">
      <RouterLink
        v-for="item in secondaryItems"
        :key="item.to"
        :to="item.to"
        class="nav-item secondary"
        :class="{ active: isActive(item.to) }"
        :title="collapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="18" />
        <span v-if="!collapsed" class="label">{{ item.label }}</span>
      </RouterLink>
    </nav>
    <div v-if="!collapsed" class="footer">
      <span
        v-if="serverVersion"
        class="version"
        :title="serverSha ? `git ${serverSha.slice(0, 7)}` : undefined"
      >
        {{ serverVersion }}
      </span>
      <button
        v-if="updateAvailable"
        type="button"
        class="update-badge"
        :title="`v${latestVersion} available — click to upgrade in place`"
        @click="updateModalOpen = true"
      >
        ↑ v{{ latestVersion }}
      </button>
      <button
        v-else
        type="button"
        class="check-updates-link"
        title="Check for updates"
        @click="updateModalOpen = true"
      >
        check for updates
      </button>
      <a
        href="https://github.com/Pr0zak/pitstop"
        target="_blank"
        rel="noopener noreferrer"
        class="github-link"
        title="View on GitHub"
      >
        <Github :size="14" />
      </a>
    </div>
    <button class="collapse" @click="toggle" :title="collapsed ? 'Expand' : 'Collapse'">
      <ChevronRight v-if="collapsed" :size="16" />
      <ChevronLeft v-else :size="16" />
    </button>
    <UpdateModal :open="updateModalOpen" @close="updateModalOpen = false" />
  </aside>
</template>

<style scoped>
.sidebar {
  background: var(--c-bg1);
  border-right: 1px solid var(--c-line0);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  transition: width 0.15s ease;
}
/* On narrow viewports the AppShell forces an icons-only layout via
   margin-left override; the sidebar matches by clamping its width and
   centering the icons regardless of the user-saved collapsed flag. */
@media (max-width: 700px) {
  .sidebar {
    width: var(--sidebar-w-collapsed) !important;
  }
  .sidebar .label,
  .sidebar .brand-name,
  .sidebar .collapse {
    display: none !important;
  }
  .sidebar .nav-item {
    justify-content: center;
    padding: 0.55rem 0;
  }
}
.brand {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0 1rem;
  height: var(--topbar-h);
  border-bottom: 1px solid var(--c-line0);
  text-decoration: none;
}
.brand:hover {
  text-decoration: none;
}
.logo {
  width: 34px;
  height: 34px;
  border-radius: var(--r-md);
  background: linear-gradient(155deg, var(--c-bg3) 0%, var(--c-bg) 80%);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.06),
    inset 0 0 0 1px rgba(255, 255, 255, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.brand-name {
  font-weight: 600;
  font-size: 1rem;
  letter-spacing: -0.018em;
  color: var(--c-ink0);
}
nav {
  display: flex;
  flex-direction: column;
  padding: 0.5rem;
  gap: 2px;
  flex: 1;
  overflow-y: auto;
}
nav.nav-secondary {
  flex: 0 0 auto;
  border-top: 1px solid var(--c-line0);
  margin-top: auto;
  padding-top: 0.4rem;
}
.nav-item.secondary {
  font-size: 0.85rem;
  color: var(--c-muted);
  opacity: 0.85;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  padding: 0.55rem 0.7rem;
  border-radius: var(--r-sm);
  color: var(--c-ink2);
  text-decoration: none;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  position: relative;
  transition: background 100ms, color 100ms;
}
.nav-item:hover {
  background: var(--c-bg3);
  color: var(--c-ink0);
  text-decoration: none;
}
.nav-item.active {
  background: var(--c-bg3);
  color: var(--c-ink0);
}
/* Coral redline rail on the active item — visual echo of the gauge
   cluster's redline segment. Sits at the leading edge instead of the
   pill background so the row stays calm but you can still scan which
   page is open at a glance. */
.nav-item.active::before {
  content: "";
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  background: var(--c-accent);
  border-radius: 1px;
}
.collapsed .nav-item {
  justify-content: center;
  padding: 0.55rem 0;
}
.label {
  flex: 1;
}
.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.4rem 0.9rem;
  font-size: 0.72rem;
  color: var(--c-muted);
  border-top: 1px solid var(--c-line0);
}
.footer .version {
  letter-spacing: 0.02em;
}
.footer .github-link {
  display: inline-flex;
  align-items: center;
  color: var(--c-muted);
  text-decoration: none;
}
.footer .github-link:hover {
  color: var(--c-ink0);
}
.footer .update-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
  background: var(--c-accent, #f97316);
  color: white;
  font-size: 0.7rem;
  font-weight: 500;
  text-decoration: none;
  letter-spacing: 0.02em;
  border: 0;
  cursor: pointer;
  font-family: inherit;
}
.footer .update-badge:hover {
  filter: brightness(1.1);
}
.footer .check-updates-link {
  background: none;
  border: 0;
  color: var(--c-ink3);
  font-size: 0.68rem;
  cursor: pointer;
  font-family: inherit;
  text-decoration: underline dotted;
  padding: 0 0.3rem;
  letter-spacing: 0.02em;
}
.footer .check-updates-link:hover {
  color: var(--c-ink0);
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
