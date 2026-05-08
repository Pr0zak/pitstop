import { ref, watch, type Ref, type WatchSource } from "vue";

interface UseAsyncOptions<T> {
  immediate?: boolean;
  initial?: T | null;
}

/**
 * Tiny wrapper for "fetch on mount + on dep change" patterns. Keeps loading/error/data
 * refs together. Does NOT cancel in-flight requests — newer call wins.
 */
export function useAsync<T>(
  fn: () => Promise<T>,
  deps: WatchSource[] = [],
  opts: UseAsyncOptions<T> = {},
): {
  data: Ref<T | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
} {
  const data = ref<T | null>(opts.initial ?? null) as Ref<T | null>;
  const loading = ref(false);
  const error = ref<string | null>(null);
  let token = 0;

  async function reload() {
    const myToken = ++token;
    loading.value = true;
    error.value = null;
    try {
      const result = await fn();
      if (myToken === token) data.value = result;
    } catch (e: unknown) {
      if (myToken === token) {
        error.value = e instanceof Error ? e.message : "request failed";
      }
    } finally {
      if (myToken === token) loading.value = false;
    }
  }

  if (deps.length > 0) {
    watch(deps, () => void reload(), { immediate: opts.immediate ?? true });
  } else if (opts.immediate ?? true) {
    void reload();
  }

  return { data, loading, error, reload };
}
