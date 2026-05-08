import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type { LiveMessage } from "@/api/types";
import { liveSocketUrl } from "@/api";

interface VehicleLiveState {
  vehicleId: string;
  ws: WebSocket | null;
  refCount: number;
  metrics: Record<string, { value: number | string | null; time: number }>;
  status: "idle" | "connecting" | "open" | "stale" | "disconnected";
  lastMessageAt: number | null;
  reconnectAttempt: number;
  reconnectTimer: number | null;
  staleTimer: number | null;
}

const STALE_AFTER_MS = 5_000;
const DISCONNECT_AFTER_MS = 30_000;

export const useLiveStore = defineStore("live", () => {
  const sessions = ref<Record<string, VehicleLiveState>>({});

  function get(vehicleId: string): VehicleLiveState | null {
    return sessions.value[vehicleId] ?? null;
  }

  function ensureSession(vehicleId: string): VehicleLiveState {
    let s = sessions.value[vehicleId];
    if (!s) {
      s = {
        vehicleId,
        ws: null,
        refCount: 0,
        metrics: {},
        status: "idle",
        lastMessageAt: null,
        reconnectAttempt: 0,
        reconnectTimer: null,
        staleTimer: null,
      };
      sessions.value[vehicleId] = s;
    }
    return s;
  }

  function scheduleStaleCheck(s: VehicleLiveState) {
    if (s.staleTimer != null) {
      window.clearTimeout(s.staleTimer);
    }
    s.staleTimer = window.setTimeout(() => {
      const now = Date.now();
      if (!s.lastMessageAt) return;
      const elapsed = now - s.lastMessageAt;
      if (elapsed > DISCONNECT_AFTER_MS) {
        s.status = "disconnected";
      } else if (elapsed > STALE_AFTER_MS) {
        s.status = "stale";
      }
      scheduleStaleCheck(s);
    }, 1_000);
  }

  function connect(vehicleId: string) {
    const s = ensureSession(vehicleId);
    if (s.ws && (s.ws.readyState === WebSocket.OPEN || s.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const url = liveSocketUrl(vehicleId);
    if (!url) {
      s.status = "disconnected";
      return;
    }
    s.status = "connecting";
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      s.status = "disconnected";
      scheduleReconnect(s);
      return;
    }
    s.ws = ws;
    ws.onopen = () => {
      s.status = "open";
      s.reconnectAttempt = 0;
      s.lastMessageAt = Date.now();
      scheduleStaleCheck(s);
    };
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as LiveMessage;
        if (msg.metric) {
          s.metrics[msg.metric] = {
            value: msg.value_num ?? msg.value_text ?? null,
            time: Date.parse(msg.time) || Date.now(),
          };
        }
        s.lastMessageAt = Date.now();
        s.status = "open";
      } catch {
        /* ignore malformed payloads */
      }
    };
    ws.onerror = () => {
      // Don't change status here — onclose will handle it.
    };
    ws.onclose = () => {
      s.ws = null;
      if (s.refCount > 0) {
        s.status = "disconnected";
        scheduleReconnect(s);
      } else {
        s.status = "idle";
      }
    };
  }

  function scheduleReconnect(s: VehicleLiveState) {
    if (s.reconnectTimer != null) {
      window.clearTimeout(s.reconnectTimer);
    }
    if (s.refCount <= 0) return;
    s.reconnectAttempt += 1;
    const delay = Math.min(30_000, 500 * 2 ** Math.min(s.reconnectAttempt, 6));
    s.reconnectTimer = window.setTimeout(() => {
      s.reconnectTimer = null;
      connect(s.vehicleId);
    }, delay);
  }

  function attach(vehicleId: string) {
    const s = ensureSession(vehicleId);
    s.refCount += 1;
    if (!s.ws) connect(vehicleId);
  }

  function detach(vehicleId: string) {
    const s = get(vehicleId);
    if (!s) return;
    s.refCount = Math.max(0, s.refCount - 1);
    if (s.refCount === 0) {
      if (s.reconnectTimer != null) {
        window.clearTimeout(s.reconnectTimer);
        s.reconnectTimer = null;
      }
      if (s.staleTimer != null) {
        window.clearTimeout(s.staleTimer);
        s.staleTimer = null;
      }
      if (s.ws) {
        try {
          s.ws.close();
        } catch {
          /* ignore */
        }
      }
      s.status = "idle";
    }
  }

  function metricsFor(vehicleId: string) {
    return computed(() => sessions.value[vehicleId]?.metrics ?? {});
  }
  function statusFor(vehicleId: string) {
    return computed(() => sessions.value[vehicleId]?.status ?? "idle");
  }

  return {
    sessions,
    attach,
    detach,
    metricsFor,
    statusFor,
  };
});
