import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";

const routes: RouteRecordRaw[] = [
  {
    path: "/",
    name: "overview",
    component: () => import("./views/OverviewView.vue"),
    meta: { title: "Overview" },
  },
  {
    path: "/setup",
    name: "setup",
    component: () => import("./views/SetupView.vue"),
    meta: { title: "Setup" },
  },
  {
    path: "/live",
    name: "live",
    component: () => import("./views/LiveView.vue"),
    meta: { title: "Live" },
  },
  {
    path: "/trips",
    name: "trips",
    component: () => import("./views/TripsView.vue"),
    meta: { title: "Trips" },
  },
  {
    path: "/trips/:id",
    name: "trip-detail",
    component: () => import("./views/TripDetailView.vue"),
    meta: { title: "Trip" },
  },
  {
    path: "/analytics",
    name: "analytics",
    component: () => import("./views/AnalyticsView.vue"),
    meta: { title: "Analytics" },
  },
  {
    path: "/fuel",
    name: "fuel",
    component: () => import("./views/FuelView.vue"),
    meta: { title: "Fuel" },
  },
  {
    path: "/fuel/import",
    name: "fuel-import",
    component: () => import("./views/FuelImportView.vue"),
    meta: { title: "Fuelio Import" },
  },
  {
    path: "/maintenance",
    name: "maintenance",
    component: () => import("./views/MaintenanceView.vue"),
    meta: { title: "Maintenance" },
  },
  {
    path: "/dtcs",
    name: "dtcs",
    component: () => import("./views/DtcsView.vue"),
    meta: { title: "DTCs" },
  },
  {
    path: "/debug",
    name: "debug",
    component: () => import("./views/DebugView.vue"),
    meta: { title: "Debug" },
  },
  {
    path: "/vehicles",
    name: "vehicles",
    component: () => import("./views/VehiclesView.vue"),
    meta: { title: "Vehicles" },
  },
  {
    path: "/profiles",
    name: "profiles",
    component: () => import("./views/ProfilesView.vue"),
    meta: { title: "Profiles" },
  },
  {
    path: "/settings",
    name: "settings",
    component: () => import("./views/SettingsView.vue"),
    meta: { title: "Settings" },
  },
  {
    path: "/logos",
    name: "logos",
    component: () => import("./views/LogosView.vue"),
    meta: { title: "Logo concepts" },
  },
  // catch-all → overview
  { path: "/:catchAll(.*)", redirect: "/" },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.afterEach((to) => {
  const title = to.meta?.title;
  if (typeof title === "string") {
    document.title = `${title} — pitstop`;
  } else {
    document.title = "pitstop";
  }
});

export default router;
