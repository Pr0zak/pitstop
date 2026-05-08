/**
 * Browser-side log shipper for pitstop.
 *
 * Buffers entries in memory (cap 50) and POSTs them to /api/logs every 10 s.
 * Silently drops batches if no INGEST token is configured. On flush failure,
 * logs to console (raw console.warn — never console.error, to avoid recursive
 * capture from the window-error catcher) and drops the batch.
 *
 * Privacy: callers are responsible for sanitising context. The window-error
 * installer in this module includes only `pathname`, `user_agent`, and
 * `stack` — no search params, cookies, localStorage, or token values.
 */

import { ingestLogs } from "@/api/endpoints";
import type { LogIngestEntry, LogLevel } from "@/api/types";

const SESSION_KEY = "pitstop_session_id";
const INGEST_TOKEN_KEY = "pitstop_ingest_token";
const FLUSH_INTERVAL_MS = 10_000;
const MAX_BUFFER = 50;

let buffer: LogIngestEntry[] = [];
let flushTimer: number | null = null;
let installed = false;

function hasIngestToken(): boolean {
  try {
    return !!localStorage.getItem(INGEST_TOKEN_KEY);
  } catch {
    return false;
  }
}

function genId(): string {
  // Avoid relying on crypto.randomUUID in older browsers.
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    try {
      return crypto.randomUUID();
    } catch {
      /* fallthrough */
    }
  }
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function getOrCreateBrowserSessionId(): string {
  try {
    const existing = localStorage.getItem(SESSION_KEY);
    if (existing) return existing;
    const fresh = genId();
    localStorage.setItem(SESSION_KEY, fresh);
    return fresh;
  } catch {
    // localStorage unavailable (private mode, etc) — fall back to a per-tab id.
    return `web-ephemeral-${Date.now().toString(36)}`;
  }
}

interface ShipLogInput {
  level: LogLevel;
  message: string;
  context?: Record<string, unknown> | null;
  vehicle_id?: string | null;
}

/** Public helper for views that want to emit a client-side trace point. */
export function shipLog(input: ShipLogInput): void {
  const entry: LogIngestEntry = {
    source: "web",
    level: input.level,
    message: input.message,
    device_id: getOrCreateBrowserSessionId(),
    client_ts: new Date().toISOString(),
    context: input.context ?? null,
    vehicle_id: input.vehicle_id ?? null,
  };
  enqueue(entry);
}

function enqueue(entry: LogIngestEntry): void {
  buffer.push(entry);
  // Drop oldest if we overflow, so a misbehaving page can't OOM the tab.
  if (buffer.length > MAX_BUFFER) {
    buffer = buffer.slice(buffer.length - MAX_BUFFER);
  }
}

async function flush(): Promise<void> {
  if (buffer.length === 0) return;
  if (!hasIngestToken()) {
    // Without an INGEST token the backend would 401; drop silently.
    buffer = [];
    return;
  }
  const batch = buffer.slice();
  buffer = [];
  try {
    await ingestLogs(batch);
  } catch (err: unknown) {
    // Use console.warn to avoid being re-captured by the window-error catcher
    // (which only listens to uncaught errors / unhandled rejections, not warns).
    // eslint-disable-next-line no-console
    console.warn(
      "[pitstop] log shipper flush failed; dropping",
      batch.length,
      "entries",
      err instanceof Error ? err.message : err,
    );
    // Intentionally do NOT re-queue: prevents tight retry loops if the
    // backend is down or the token has expired.
  }
}

function buildErrorEntry(
  message: string,
  stack: string | null,
): LogIngestEntry {
  return {
    source: "web",
    level: "error",
    message,
    device_id: getOrCreateBrowserSessionId(),
    client_ts: new Date().toISOString(),
    context: {
      // pathname only — never search params (might contain GPS or filters
      // that fingerprint the user). No cookies, no localStorage values.
      url: window.location.pathname,
      user_agent: navigator.userAgent,
      stack,
    },
  };
}

function onError(e: ErrorEvent): void {
  const message =
    e.message ??
    (e.error instanceof Error ? e.error.message : null) ??
    "uncaught error";
  const stack = e.error instanceof Error ? (e.error.stack ?? null) : null;
  enqueue(buildErrorEntry(message, stack));
}

function onUnhandledRejection(e: PromiseRejectionEvent): void {
  const reason = e.reason;
  let message = "unhandled promise rejection";
  let stack: string | null = null;
  if (reason instanceof Error) {
    message = reason.message || message;
    stack = reason.stack ?? null;
  } else if (typeof reason === "string") {
    message = reason;
  } else if (reason && typeof reason === "object") {
    try {
      message = JSON.stringify(reason);
    } catch {
      message = String(reason);
    }
  }
  enqueue(buildErrorEntry(message, stack));
}

/**
 * Install window-level error catchers and start the flush timer.
 * Idempotent — safe to call once from main.ts.
 */
export function installLogShipper(): void {
  if (installed) return;
  installed = true;

  window.addEventListener("error", onError);
  window.addEventListener("unhandledrejection", onUnhandledRejection);

  flushTimer = window.setInterval(() => {
    void flush();
  }, FLUSH_INTERVAL_MS);

  // Best-effort flush on tab close / hide.
  window.addEventListener("beforeunload", () => {
    void flush();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void flush();
  });
}

/** Test-only: clear pending state so unit tests don't leak. */
export function _resetForTests(): void {
  buffer = [];
  if (flushTimer !== null) {
    clearInterval(flushTimer);
    flushTimer = null;
  }
  installed = false;
}
