import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Dev proxy target. Override to point the dev server at another backend,
// e.g. `PITSTOP_API_TARGET=http://backend-host:8000 pnpm dev`.
const API_TARGET = process.env.PITSTOP_API_TARGET || "http://localhost:8000";
const WS_TARGET = API_TARGET.replace(/^http/, "ws");

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: API_TARGET,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
      "/ws": {
        target: WS_TARGET,
        ws: true,
        changeOrigin: true,
      },
    },
  },
});
