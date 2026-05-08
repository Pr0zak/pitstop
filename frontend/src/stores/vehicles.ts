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
      // Auto-select first vehicle if none selected or selected no longer exists.
      if (
        !selectedVehicleId.value ||
        !list.find((v) => v.id === selectedVehicleId.value)
      ) {
        selectVehicle(list[0]?.id ?? null);
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
