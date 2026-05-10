import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type { Vehicle } from "@/api/types";
import * as api from "@/api/endpoints";

const SEL_KEY = "pitstop_selected_vehicle";

export const useVehiclesStore = defineStore("vehicles", () => {
  const vehicles = ref<Vehicle[]>([]);
  const loaded = ref(false);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const selectedVehicleId = ref<string | null>(
    (() => {
      try {
        return localStorage.getItem(SEL_KEY);
      } catch {
        return null;
      }
    })(),
  );

  const selectedVehicle = computed<Vehicle | null>(
    () =>
      vehicles.value.find((v) => v.id === selectedVehicleId.value) ?? null,
  );

  /** Active vehicles only — what the dropdown shows by default and
   *  what auto-select picks. Inactive vehicles (e.g. an old daily
   *  driver kept around for historical fillup data) stay reachable
   *  via [archivedVehicles] but don't clutter the everyday UI. */
  const activeVehicles = computed<Vehicle[]>(() =>
    vehicles.value.filter((v) => v.active !== false),
  );
  const archivedVehicles = computed<Vehicle[]>(() =>
    vehicles.value.filter((v) => v.active === false),
  );

  function persistSelection() {
    try {
      if (selectedVehicleId.value)
        localStorage.setItem(SEL_KEY, selectedVehicleId.value);
      else localStorage.removeItem(SEL_KEY);
    } catch {
      /* ignore */
    }
  }

  function selectVehicle(id: string | null) {
    selectedVehicleId.value = id;
    persistSelection();
  }

  async function fetchVehicles(): Promise<Vehicle[]> {
    loading.value = true;
    error.value = null;
    try {
      const list = await api.listVehicles();
      vehicles.value = list;
      loaded.value = true;
      // Auto-select the first ACTIVE vehicle if none selected or the
      // previous selection is gone. Falls back to any vehicle (incl.
      // archived) so a user with only inactive vehicles still gets
      // something selected.
      if (
        !selectedVehicleId.value ||
        !list.find((v) => v.id === selectedVehicleId.value)
      ) {
        const firstActive = list.find((v) => v.active !== false);
        selectVehicle(firstActive?.id ?? list[0]?.id ?? null);
      }
      return list;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "failed to load vehicles";
      error.value = msg;
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function ensureLoaded(): Promise<void> {
    if (!loaded.value && !loading.value) await fetchVehicles();
  }

  return {
    vehicles,
    activeVehicles,
    archivedVehicles,
    loaded,
    loading,
    error,
    selectedVehicleId,
    selectedVehicle,
    selectVehicle,
    fetchVehicles,
    ensureLoaded,
  };
});
