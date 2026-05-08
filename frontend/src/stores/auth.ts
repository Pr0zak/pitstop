import { defineStore } from "pinia";
import { ref, computed } from "vue";

const QUERY_KEY = "pitstop_query_token";
const INGEST_KEY = "pitstop_ingest_token";

function readLs(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}
function writeLs(key: string, value: string) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}

export const useAuthStore = defineStore("auth", () => {
  const queryToken = ref<string>(readLs(QUERY_KEY));
  const ingestToken = ref<string>(readLs(INGEST_KEY));

  const hasQueryToken = computed(() => queryToken.value.length > 0);
  const hasIngestToken = computed(() => ingestToken.value.length > 0);

  function setQueryToken(t: string) {
    queryToken.value = t.trim();
    writeLs(QUERY_KEY, queryToken.value);
  }
  function setIngestToken(t: string) {
    ingestToken.value = t.trim();
    writeLs(INGEST_KEY, ingestToken.value);
  }
  function clearQueryToken() {
    setQueryToken("");
  }
  function clearIngestToken() {
    setIngestToken("");
  }

  return {
    queryToken,
    ingestToken,
    hasQueryToken,
    hasIngestToken,
    setQueryToken,
    setIngestToken,
    clearQueryToken,
    clearIngestToken,
  };
});
