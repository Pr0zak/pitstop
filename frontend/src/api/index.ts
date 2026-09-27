import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from "axios";

// Base URL: same origin in prod (Caddy proxies /api/* → backend), Vite proxies in dev.
const API_BASE = "/api";

function readToken(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

let onUnauthorized: ((kind: "query" | "ingest") => void) | null = null;

export function setUnauthorizedHandler(
  handler: ((kind: "query" | "ingest") => void) | null,
) {
  onUnauthorized = handler;
}

function makeClient(tokenKey: string, kind: "query" | "ingest"): AxiosInstance {
  const inst = axios.create({
    baseURL: API_BASE,
    timeout: 30_000,
  });

  inst.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = readToken(tokenKey);
    if (token) {
      config.headers.set("Authorization", `Bearer ${token}`);
    }
    return config;
  });

  inst.interceptors.response.use(
    (resp) => resp,
    (err) => {
      if (err?.response?.status === 401) {
        try {
          localStorage.removeItem(tokenKey);
        } catch {
          /* ignore */
        }
        if (onUnauthorized) onUnauthorized(kind);
      }
      return Promise.reject(err);
    },
  );

  return inst;
}

export const apiQuery = makeClient("pitstop_query_token", "query");
export const apiIngest = makeClient("pitstop_ingest_token", "ingest");

// Convenience getter for the WebSocket URL — encodes the token in the query string,
// since browsers don't allow custom headers on WebSocket connections.
// With query auth off on the backend there is no token and the param is
// omitted; the socket is still opened.
export function liveSocketUrl(vehicleId: string): string {
  const token = readToken("pitstop_query_token");
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  const params = new URLSearchParams({ vehicle_id: vehicleId });
  if (token) params.set("token", token);
  return `${proto}//${host}/ws/live?${params.toString()}`;
}
