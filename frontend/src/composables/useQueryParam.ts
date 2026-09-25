import { computed, type WritableComputedRef } from "vue";
import { useRoute, useRouter } from "vue-router";

/**
 * Two-way bind a string route query param. Writes use router.replace so
 * tab / filter changes don't pile up history entries; the default value is
 * dropped from the URL to keep links short.
 */
export function useQueryParam<T extends string>(
  key: string,
  fallback: T,
  allowed?: readonly T[],
): WritableComputedRef<T> {
  const route = useRoute();
  const router = useRouter();
  return computed<T>({
    get() {
      const raw = route.query[key];
      const v = (Array.isArray(raw) ? raw[0] : raw) ?? null;
      if (v == null) return fallback;
      if (allowed && !allowed.includes(v as T)) return fallback;
      return v as T;
    },
    set(v: T) {
      const query = { ...route.query };
      if (v === fallback) delete query[key];
      else query[key] = v;
      void router.replace({ query });
    },
  });
}
