import { defineStore } from "pinia";
import { ref, computed } from "vue";
import axios from "axios";

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

  // Whether the backend enforces each scope (GET /auth/config). A homelab
  // deploy with blank QUERY_TOKEN / INGEST_TOKEN in .env turns auth off, and
  // then the UI must not gate anything on a token. Defaults to "required" so
  // an unreachable backend never silently looks unauthenticated.
  const queryRequired = ref(true);
  const ingestRequired = ref(true);
  const queryOk = computed(() => !queryRequired.value || hasQueryToken.value);
  const ingestOk = computed(() => !ingestRequired.value || hasIngestToken.value);

  async function loadConfig() {
    try {
      const { data } = await axios.get<{ query_required: boolean; ingest_required: boolean }>(
        "/api/auth/config",
        { timeout: 5_000 },
      );
      queryRequired.value = data.query_required !== false;
      ingestRequired.value = data.ingest_required !== false;
    } catch {
      /* keep the safe default: tokens required */
    }
  }

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
    queryRequired,
    ingestRequired,
    queryOk,
    ingestOk,
    loadConfig,
    setQueryToken,
    setIngestToken,
    clearQueryToken,
    clearIngestToken,
  };
});
