<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Reminder } from "@/api/types";
import { Wrench, Check, AlertTriangle, Clock } from "lucide-vue-next";
import { fmtDate, fmtOdo, fmtMiles } from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);

const { data, loading, error, reload } = useAsync(
  () => api.listReminders(vehicleId.value ?? undefined),
  [vehicleId],
);

function severity(r: Reminder): "danger" | "warn" | "ok" {
  if (r.status === "overdue") return "danger";
  const milesClose = r.miles_remaining != null && r.miles_remaining < 100;
  const daysClose = r.days_remaining != null && r.days_remaining < 7;
  if (milesClose || daysClose) return "warn";
  return "ok";
}

async function markDone(r: Reminder) {
  if (!window.confirm(`Mark "${r.title}" as done?`)) return;
  try {
    await api.markReminderDone(r.expense_id);
    await reload();
  } catch (e: unknown) {
    alert(e instanceof Error ? e.message : "failed");
  }
}

function describeDelta(r: Reminder): string {
  const parts: string[] = [];
  if (r.miles_remaining != null) {
    if (r.miles_remaining >= 0)
      parts.push(`${fmtMiles(r.miles_remaining)} left`);
    else parts.push(`${fmtMiles(Math.abs(r.miles_remaining))} over`);
  }
  if (r.days_remaining != null) {
    if (r.days_remaining >= 0) parts.push(`${r.days_remaining} d left`);
    else parts.push(`${Math.abs(r.days_remaining)} d over`);
  }
  return parts.join(" · ");
}
</script>

<template>
  <div class="maint">
    <header class="head">
      <h1>Maintenance reminders</h1>
    </header>

    <div v-if="loading && !data" class="card">
      <p class="muted">Loading reminders…</p>
    </div>
    <div v-else-if="error" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>
    <template v-else-if="data">
      <div
        v-if="data.overdue.length === 0 && data.upcoming.length === 0"
        class="card empty"
      >
        <Wrench :size="22" />
        <h3>No active reminders</h3>
        <p class="muted">
          Reminders come from your Fuelio "Costs" entries (with a remind odometer or date).
          <RouterLink to="/fuel/import">Import your Fuelio history</RouterLink>
          to populate them, or add expenses on the
          <RouterLink to="/maintenance">Maintenance</RouterLink>
          page once we wire it up.
        </p>
      </div>

      <section v-if="data.overdue.length > 0" class="group">
        <header>
          <AlertTriangle :size="14" /> Overdue ({{ data.overdue.length }})
        </header>
        <ul class="list">
          <li v-for="r in data.overdue" :key="r.expense_id" class="item danger">
            <div class="meta">
              <strong>{{ r.title }}</strong>
              <span v-if="r.category_name" class="muted small">{{ r.category_name }}</span>
            </div>
            <div class="numbers">
              <span class="muted small">
                {{ r.current_odo != null ? fmtOdo(r.current_odo) : "—" }}
                <span v-if="r.remind_odo != null"> / target {{ fmtOdo(r.remind_odo) }}</span>
                <span v-if="r.remind_date"> · due {{ fmtDate(r.remind_date) }}</span>
              </span>
              <span class="badge danger">{{ describeDelta(r) || "overdue" }}</span>
            </div>
            <button class="primary" type="button" @click="markDone(r)">
              <Check :size="14" /> Mark done
            </button>
          </li>
        </ul>
      </section>

      <section v-if="data.upcoming.length > 0" class="group">
        <header>
          <Clock :size="14" /> Upcoming ({{ data.upcoming.length }})
        </header>
        <ul class="list">
          <li
            v-for="r in data.upcoming"
            :key="r.expense_id"
            class="item"
            :class="severity(r)"
          >
            <div class="meta">
              <strong>{{ r.title }}</strong>
              <span v-if="r.category_name" class="muted small">{{ r.category_name }}</span>
            </div>
            <div class="numbers">
              <span class="muted small">
                {{ r.current_odo != null ? fmtOdo(r.current_odo) : "—" }}
                <span v-if="r.remind_odo != null"> / target {{ fmtOdo(r.remind_odo) }}</span>
                <span v-if="r.remind_date"> · due {{ fmtDate(r.remind_date) }}</span>
              </span>
              <span class="badge" :class="severity(r) === 'warn' ? 'warn' : ''">
                {{ describeDelta(r) || "scheduled" }}
              </span>
            </div>
            <button type="button" @click="markDone(r)">
              <Check :size="14" /> Done
            </button>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.maint {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.empty {
  text-align: center;
  padding: 2.5rem 1rem;
}
.empty h3 {
  margin: 0.5rem 0 0.3rem 0;
}
.group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.group > header {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--c-muted);
  font-size: 0.78rem;
  font-weight: 600;
}
.list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.item {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 0.7rem;
  align-items: center;
  padding: 0.7rem 0.9rem;
  background: var(--c-surface);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-md);
}
.item.danger {
  border-left: 3px solid var(--c-danger);
}
.item.warn {
  border-left: 3px solid var(--c-warn);
}
.item.ok {
  border-left: 3px solid var(--c-accent);
}
.meta {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-width: 0;
}
.numbers {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.25rem;
}
.small {
  font-size: 0.78rem;
}
.item button {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
}
@media (max-width: 700px) {
  .item {
    grid-template-columns: 1fr;
    align-items: flex-start;
  }
  .numbers {
    align-items: flex-start;
  }
}
</style>
