import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { installLogShipper } from "./lib/logShipper";
import { useAuthStore } from "./stores/auth";
import "./assets/main.css";

// Catch uncaught errors + unhandled promise rejections and ship them to the
// backend log depot. Buffered + flushed every 10 s; silently drops batches
// when the backend needs an INGEST token and none is configured.
installLogShipper();

const app = createApp(App);
app.use(createPinia());
app.use(router);
// Learn whether the backend enforces tokens before any view decides to
// gate on one (views check auth in onMounted).
void useAuthStore()
  .loadConfig()
  .finally(() => app.mount("#app"));
