/**
 * Display-units preference. Storage in the DB stays canonical (km/h for OBD
 * speed, °C for OBD temps, etc.); this store decides only how those values
 * are *rendered* in the UI.
 *
 * Three modes:
 *   - 'auto'      — read from the currently-selected vehicle's stored
 *                   dist_unit / fuel_unit codes (Fuelio's per-vehicle
 *                   preference, populated by the importer).
 *   - 'metric'    — kph / °C / l / l/100km / km
 *   - 'imperial'  — mph / °F / gal / mpg / mi
 *
 * Persisted to localStorage as `pitstop_units`. Default is 'auto'.
 */
import { defineStore } from "pinia";
import { computed, ref, watch } from "vue";
import { useVehiclesStore } from "./vehicles";

export type UnitSystem = "auto" | "metric" | "imperial";
export type ResolvedUnitSystem = "metric" | "imperial";

const KEY = "pitstop_units";

export const useUnitsStore = defineStore("units", () => {
  const vehicles = useVehiclesStore();

  const preference = ref<UnitSystem>(loadPreference());

  function loadPreference(): UnitSystem {
    try {
      const v = localStorage.getItem(KEY);
      if (v === "metric" || v === "imperial" || v === "auto") return v;
    } catch {
      /* ignore */
    }
    return "auto";
  }

  function setPreference(p: UnitSystem) {
    preference.value = p;
    try {
      localStorage.setItem(KEY, p);
    } catch {
      /* ignore */
    }
  }

  watch(preference, (p) => {
    try {
      localStorage.setItem(KEY, p);
    } catch {
      /* ignore */
    }
  });

  // Auto resolution: lookup_units codes from the imported Fuelio data.
  //   dist_unit: 0=km, 1=mi
  //   fuel_unit: 0=l, 1=us_gal, 2=uk_gal
  // We collapse "any non-metric" → imperial; the mi/gal pairing is what
  // American users want, and we don't try to model UK gallons distinctly
  // for a display-only toggle.
  const resolved = computed<ResolvedUnitSystem>(() => {
    if (preference.value === "metric") return "metric";
    if (preference.value === "imperial") return "imperial";
    const v = vehicles.selectedVehicle;
    if (!v) return "imperial"; // sensible default when no vehicle picked
    if (v.dist_unit === 1 || v.fuel_unit === 1 || v.fuel_unit === 2) return "imperial";
    return "metric";
  });

  return {
    preference,
    resolved,
    setPreference,
  };
});
