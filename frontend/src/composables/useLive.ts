import { onMounted, onUnmounted, watch, computed } from "vue";
import { useLiveStore } from "@/stores/live";

/**
 * Mounts a live websocket subscription for the given (reactive) vehicle id.
 * Auto-detaches on unmount and on vehicle id change.
 */
export function useLive(vehicleIdRef: { value: string | null }) {
  const live = useLiveStore();
  let attachedId: string | null = null;

  function attachIfReady() {
    const id = vehicleIdRef.value;
    if (id && id !== attachedId) {
      if (attachedId) live.detach(attachedId);
      live.attach(id);
      attachedId = id;
    } else if (!id && attachedId) {
      live.detach(attachedId);
      attachedId = null;
    }
  }

  onMounted(attachIfReady);
  watch(() => vehicleIdRef.value, attachIfReady);
  onUnmounted(() => {
    if (attachedId) {
      live.detach(attachedId);
      attachedId = null;
    }
  });

  const metrics = computed(() => {
    const id = vehicleIdRef.value;
    if (!id) return {};
    return live.sessions[id]?.metrics ?? {};
  });
  const status = computed(() => {
    const id = vehicleIdRef.value;
    if (!id) return "idle" as const;
    return live.sessions[id]?.status ?? ("idle" as const);
  });

  return { metrics, status };
}
