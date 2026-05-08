import { defineStore } from "pinia";
import { ref } from "vue";
import type { Settings } from "@/api/types";
import * as api from "@/api/endpoints";

export const useSettingsStore = defineStore("settings", () => {
  const settings = ref<Settings | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function fetchSettings() {
    loading.value = true;
    error.value = null;
    try {
      settings.value = await api.getSettings();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "failed to load settings";
      error.value = msg;
    } finally {
      loading.value = false;
    }
  }

  async function patchSettings(payload: Partial<Settings>) {
    settings.value = await api.updateSettings(payload);
  }

  async function testHa() {
    return api.testHa();
  }

  return { settings, loading, error, fetchSettings, patchSettings, testHa };
});
