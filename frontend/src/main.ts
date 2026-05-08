import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { installLogShipper } from "./lib/logShipper";
import "./assets/main.css";

// Catch uncaught errors + unhandled promise rejections and ship them to the
// backend log depot. Buffered + flushed every 10 s; silently drops batches
// when no INGEST token is configured.
installLogShipper();

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.mount("#app");
