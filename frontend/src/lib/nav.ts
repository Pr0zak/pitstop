import {
  LayoutDashboard,
  LayoutGrid,
  Activity,
  Route,
  BarChart3,
  Fuel,
  Wrench,
  AlertTriangle,
  Car,
  FileJson,
  HelpCircle,
  Map as MapIcon,
  Settings as SettingsIcon,
} from "lucide-vue-next";
import type { Component } from "vue";

export interface NavItem {
  to: string;
  label: string;
  icon: Component;
}

/** Operational pages — top of the sidebar. */
export const PRIMARY_NAV: NavItem[] = [
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

/** Admin / setup pages touched once and rarely revisited. Debug, Logos and
 *  HondaLink live under Settings → Developer / Integrations; their routes
 *  still resolve for bookmarks. */
export const SECONDARY_NAV: NavItem[] = [
  { to: "/vehicles", label: "Vehicles", icon: Car },
  { to: "/profiles", label: "Profiles", icon: FileJson },
  { to: "/setup", label: "Setup", icon: HelpCircle },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
];

/** Bottom tab bar (< 700 px): four destinations + a More sheet. */
export const TAB_NAV = ["/", "/live", "/trips", "/fuel"];

/** Routes that belong to a nav entry without being a prefix of it. */
const ALIASES: Record<string, string> = {
  "/debug": "/settings",
  "/logos": "/settings",
  "/hondalink-test": "/settings",
};

export function isNavActive(to: string, path: string): boolean {
  const p = ALIASES[path] ?? path;
  if (to === "/") return p === "/";
  return p === to || p.startsWith(to + "/");
}
