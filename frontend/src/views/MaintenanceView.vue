<script setup lang="ts">
import { computed, ref, useId } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import { useModalA11y } from "@/composables/useModalA11y";
import { useToastStore, errMessage } from "@/stores/toast";
import * as api from "@/api/endpoints";
import type { Expense, Reminder } from "@/api/types";
import { Wrench, Check, AlertTriangle, Clock, Plus, History, X } from "lucide-vue-next";
import {
  fmtDate,
  fmtOdo,
  fmtDistance,
  fmtMoney,
  vehicleDistUnit,
} from "@/composables/useFormat";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import StateCard from "@/components/StateCard.vue";

const vehicles = useVehiclesStore();
const toast = useToastStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
// Reminder / expense odometers are in the vehicle's own distance unit.
const distSrc = computed(() => vehicleDistUnit(vehicles.selectedVehicle));

const { data, loading, error, reload } = useAsync(
  () => api.listReminders(vehicleId.value ?? undefined),
  [vehicleId],
);

// Every logged cost for the vehicle — feeds the Completed list and the
// start point of each reminder's progress bar.
const expensesQ = useAsync(
  () => (vehicleId.value ? api.listExpenses({ vehicle_id: vehicleId.value }) : Promise.resolve([] as Expense[])),
  [vehicleId],
);
const categoriesQ = useAsync(() => api.listCategories(), []);

function categoryName(id: number | null | undefined): string {
  if (id == null) return "—";
  return (categoriesQ.data.value ?? []).find((c) => String(c.id) === String(id))?.name ?? "—";
}
const expenseById = computed(() => {
  const m = new Map<string, Expense>();
  for (const e of expensesQ.data.value ?? []) m.set(e.id, e);
  return m;
});

/** Past services, newest first (templates and income excluded). */
const completed = computed<Expense[]>(() =>
  (expensesQ.data.value ?? [])
    .filter((e) => !e.is_template && !e.is_income)
    .sort((a, b) => (b.expense_date ?? "").localeCompare(a.expense_date ?? "")),
);
const showAllCompleted = ref(false);
const completedVisible = computed(() =>
  showAllCompleted.value ? completed.value : completed.value.slice(0, 10),
);

function severity(r: Reminder): "danger" | "warn" | "ok" {
  if (r.status === "overdue") return "danger";
  const milesClose = r.miles_remaining != null && r.miles_remaining < 100;
  const daysClose = r.days_remaining != null && r.days_remaining < 7;
  if (milesClose || daysClose) return "warn";
  return "ok";
}

/** Fraction (0–1) of the service interval used up — by distance or by
 *  date, whichever is further along. Null when the start point is unknown. */
function progress(r: Reminder): number | null {
  if (r.status === "overdue") return 1;
  const e = expenseById.value.get(r.expense_id);
  const fracs: number[] = [];
  // The reminder row IS the last service (remind_* hang off it), so its
  // own odo / date are the interval start.
  const startOdo = e?.odo;
  if (startOdo != null && r.remind_odo != null && r.current_odo != null && r.remind_odo > startOdo) {
    fracs.push((r.current_odo - startOdo) / (r.remind_odo - startOdo));
  }
  if (e?.expense_date && r.remind_date) {
    const start = Date.parse(e.expense_date);
    const end = Date.parse(r.remind_date);
    if (Number.isFinite(start) && Number.isFinite(end) && end > start) {
      fracs.push((Date.now() - start) / (end - start));
    }
  }
  if (!fracs.length) return null;
  return Math.max(0, Math.min(1, Math.max(...fracs)));
}

// ── Mark done (ConfirmDialog, toast on outcome) ────────────────────────
const doneTarget = ref<Reminder | null>(null);
const doneBusy = ref(false);
async function confirmDone() {
  const r = doneTarget.value;
  if (!r) return;
  doneBusy.value = true;
  try {
    await api.markReminderDone(r.expense_id);
    toast.success(`Marked "${r.title}" done`);
    doneTarget.value = null;
    await Promise.all([reload(), expensesQ.reload()]);
  } catch (e: unknown) {
    toast.error(errMessage(e, "Couldn't mark it done"));
  } finally {
    doneBusy.value = false;
  }
}

function describeDelta(r: Reminder): string {
  const parts: string[] = [];
  if (r.miles_remaining != null) {
    if (r.miles_remaining >= 0) parts.push(`${fmtDistance(r.miles_remaining, distSrc.value, 0)} left`);
    else parts.push(`${fmtDistance(Math.abs(r.miles_remaining), distSrc.value, 0)} over`);
  }
  if (r.days_remaining != null) {
    if (r.days_remaining >= 0) parts.push(`${r.days_remaining} d left`);
    else parts.push(`${Math.abs(r.days_remaining)} d over`);
  }
  return parts.join(" · ");
}

// ── Add service ───────────────────────────────────────────────────────
const addOpen = ref(false);
const addPanel = ref<HTMLElement | null>(null);
const addTitleId = useId();
const saving = ref(false);
const addError = ref<string | null>(null);
function blankForm() {
  const odoKm = vehicles.selectedVehicle?.latest_odo_km;
  const odo =
    odoKm != null ? Math.round(distSrc.value === "mi" ? odoKm * 0.621371 : odoKm) : undefined;
  return {
    title: "",
    expense_date: new Date().toISOString().slice(0, 10),
    odometer: odo as number | undefined,
    cost: undefined as number | undefined,
    cost_type_id: "" as number | "",
    notes: "",
    repeat_odo: undefined as number | undefined,
    repeat_months: undefined as number | undefined,
  };
}
const form = ref(blankForm());
function openAdd() {
  form.value = blankForm();
  addError.value = null;
  addOpen.value = true;
}
useModalA11y(addOpen, addPanel, () => (addOpen.value = false));

async function saveService() {
  if (!vehicleId.value) return;
  if (!form.value.title.trim()) {
    addError.value = "Give the service a name";
    return;
  }
  saving.value = true;
  addError.value = null;
  const f = form.value;
  // Reminder targets derive from the repeat interval, so a logged oil
  // change with "every 5,000" immediately shows up as Upcoming.
  const remindOdo = f.odometer != null && f.repeat_odo ? f.odometer + f.repeat_odo : null;
  let remindDate: string | null = null;
  if (f.repeat_months && f.expense_date) {
    const d = new Date(f.expense_date + "T12:00:00");
    d.setMonth(d.getMonth() + f.repeat_months);
    remindDate = d.toISOString().slice(0, 10);
  }
  try {
    await api.createExpense({
      vehicle_id: vehicleId.value,
      title: f.title.trim(),
      expense_date: f.expense_date,
      odo: f.odometer ?? null,
      cost: f.cost ?? 0,
      cost_type_id: f.cost_type_id === "" ? null : f.cost_type_id,
      notes: f.notes || null,
      repeat_odo: f.repeat_odo ?? null,
      repeat_months: f.repeat_months ?? null,
      remind_odo: remindOdo,
      remind_date: remindDate,
    });
    toast.success(`Logged "${f.title.trim()}"`);
    addOpen.value = false;
    await Promise.all([reload(), expensesQ.reload()]);
  } catch (e: unknown) {
    addError.value = errMessage(e, "Save failed");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="maint">
    <header class="head">
      <h1>Maintenance</h1>
      <button class="primary" type="button" :disabled="!vehicleId" @click="openAdd">
        <Plus :size="14" aria-hidden="true" /> Add service
      </button>
    </header>

    <StateCard v-if="loading && !data" state="loading" title="Loading reminders…" />
    <StateCard v-else-if="error" state="error" :message="error" @retry="reload()" />
    <template v-else-if="data">
      <div v-if="data.overdue.length === 0 && data.upcoming.length === 0" class="card empty">
        <Wrench :size="22" aria-hidden="true" />
        <h3>No active reminders</h3>
        <p class="muted">
          A reminder appears when a service has a repeat interval or a remind odometer / date.
          Use <strong>Add service</strong> above, or
          <RouterLink to="/fuel/import">import your Fuelio Costs</RouterLink>.
        </p>
      </div>

      <section v-if="data.overdue.length > 0" class="group">
        <header>
          <AlertTriangle :size="14" aria-hidden="true" /> Overdue ({{ data.overdue.length }})
        </header>
        <ul class="list">
          <li v-for="r in data.overdue" :key="r.expense_id" class="item danger">
            <div class="meta">
              <strong>{{ r.title }}</strong>
              <span v-if="r.category_name" class="muted small">{{ r.category_name }}</span>
              <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="100" :aria-label="`${r.title} interval used`">
                <span class="fill danger" style="width: 100%"></span>
              </div>
            </div>
            <div class="numbers">
              <span class="muted small num">
                {{ r.current_odo != null ? fmtOdo(r.current_odo, distSrc) : "—" }}
                <span v-if="r.remind_odo != null"> / due {{ fmtOdo(r.remind_odo, distSrc) }}</span>
                <span v-if="r.remind_date"> · {{ fmtDate(r.remind_date) }}</span>
              </span>
              <span class="badge danger">{{ describeDelta(r) || "overdue" }}</span>
            </div>
            <button class="primary" type="button" @click="doneTarget = r">
              <Check :size="14" aria-hidden="true" /> Mark done
            </button>
          </li>
        </ul>
      </section>

      <section v-if="data.upcoming.length > 0" class="group">
        <header>
          <Clock :size="14" aria-hidden="true" /> Upcoming ({{ data.upcoming.length }})
        </header>
        <ul class="list">
          <li v-for="r in data.upcoming" :key="r.expense_id" class="item" :class="severity(r)">
            <div class="meta">
              <strong>{{ r.title }}</strong>
              <span v-if="r.category_name" class="muted small">{{ r.category_name }}</span>
              <div
                v-if="progress(r) != null"
                class="bar"
                role="progressbar"
                aria-valuemin="0"
                aria-valuemax="100"
                :aria-valuenow="Math.round(progress(r)! * 100)"
                :aria-label="`${r.title} interval used`"
              >
                <span class="fill" :class="severity(r)" :style="{ width: (progress(r)! * 100).toFixed(1) + '%' }"></span>
              </div>
            </div>
            <div class="numbers">
              <span class="muted small num">
                {{ r.current_odo != null ? fmtOdo(r.current_odo, distSrc) : "—" }}
                <span v-if="r.remind_odo != null"> / due {{ fmtOdo(r.remind_odo, distSrc) }}</span>
                <span v-if="r.remind_date"> · {{ fmtDate(r.remind_date) }}</span>
              </span>
              <span class="badge" :class="severity(r) === 'warn' ? 'warn' : ''">
                {{ describeDelta(r) || "scheduled" }}
              </span>
            </div>
            <button type="button" @click="doneTarget = r">
              <Check :size="14" aria-hidden="true" /> Done
            </button>
          </li>
        </ul>
      </section>
    </template>

    <section v-if="vehicleId" class="group">
      <header><History :size="14" aria-hidden="true" /> Completed ({{ completed.length }})</header>
      <StateCard v-if="expensesQ.loading.value && !expensesQ.data.value" state="loading" />
      <StateCard v-else-if="expensesQ.error.value" state="error" :message="expensesQ.error.value" @retry="expensesQ.reload()" />
      <StateCard v-else-if="completed.length === 0" state="empty" title="No services logged yet." />
      <div v-else class="card no-pad">
        <div class="table-scroll">
          <table class="data">
            <thead>
              <tr>
                <th>Date</th>
                <th>Service</th>
                <th>Category</th>
                <th class="num">Odometer</th>
                <th class="num">Cost</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in completedVisible" :key="e.id">
                <td>{{ fmtDate(e.expense_date) }}</td>
                <td>{{ e.title }}</td>
                <td class="muted">{{ categoryName(e.cost_type_id) }}</td>
                <td class="num">{{ fmtOdo(e.odo, distSrc) }}</td>
                <td class="num">{{ fmtMoney(e.cost) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="completed.length > 10" class="more-row">
          <button type="button" class="ghost" @click="showAllCompleted = !showAllCompleted">
            {{ showAllCompleted ? "Show fewer" : `Show all ${completed.length}` }}
          </button>
        </div>
      </div>
    </section>

    <ConfirmDialog
      :open="doneTarget != null"
      :title="`Mark “${doneTarget?.title ?? ''}” done?`"
      message="Logs it as completed today at the current odometer and schedules the next one from its repeat interval."
      confirm-label="Mark done"
      tone="primary"
      :busy="doneBusy"
      @confirm="confirmDone"
      @cancel="doneTarget = null"
    />

    <Teleport to="body">
      <div v-if="addOpen" class="modal-mask" @click.self="addOpen = false">
        <form
          ref="addPanel"
          class="modal"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="addTitleId"
          @submit.prevent="saveService"
        >
          <header class="m-head">
            <h3 :id="addTitleId">Add service</h3>
            <button type="button" class="ghost" aria-label="Close" @click="addOpen = false"><X :size="14" /></button>
          </header>
          <label>
            Service
            <input v-model="form.title" autofocus placeholder="Oil change, tire rotation, …" required />
          </label>
          <div class="grid2">
            <label>
              Date
              <input v-model="form.expense_date" type="date" />
            </label>
            <label>
              Odometer ({{ distSrc }})
              <input v-model.number="form.odometer" type="number" step="1" />
            </label>
            <label>
              Cost ($)
              <input v-model.number="form.cost" type="number" step="0.01" />
            </label>
            <label>
              Category
              <select v-model="form.cost_type_id">
                <option value="">—</option>
                <option v-for="c in categoriesQ.data.value ?? []" :key="c.id" :value="c.id">{{ c.name }}</option>
              </select>
            </label>
            <label>
              Repeat every ({{ distSrc }})
              <input v-model.number="form.repeat_odo" type="number" step="100" placeholder="e.g. 5000" />
            </label>
            <label>
              Repeat every (months)
              <input v-model.number="form.repeat_months" type="number" step="1" placeholder="e.g. 6" />
            </label>
          </div>
          <label>
            Notes
            <textarea v-model="form.notes" rows="2" />
          </label>
          <p v-if="addError" class="error" role="alert">{{ addError }}</p>
          <div class="m-actions">
            <button type="button" @click="addOpen = false">Cancel</button>
            <button type="submit" class="primary" :disabled="saving">{{ saving ? "Saving…" : "Save" }}</button>
          </div>
        </form>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.maint {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
}
.head h1 {
  margin: 0;
}
.head button {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
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
/* Healthy = neutral rail. Coral is reserved for things needing action. */
.item.ok {
  border-left: 3px solid var(--c-line2);
}
.meta {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
}
.bar {
  height: 4px;
  border-radius: 2px;
  background: var(--c-bg4);
  overflow: hidden;
  margin-top: 0.3rem;
  max-width: 320px;
}
.fill {
  display: block;
  height: 100%;
  background: var(--c-ink3);
}
.fill.warn {
  background: var(--c-warn);
}
.fill.danger {
  background: var(--c-danger);
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
.no-pad {
  padding: 0;
  overflow: hidden;
}
.table-scroll {
  overflow-x: auto;
}
.more-row {
  display: flex;
  justify-content: center;
  padding: 0.4rem;
  border-top: 1px solid var(--c-line0);
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}
.modal {
  background: var(--c-bg2);
  border: 1px solid var(--c-line1);
  border-radius: var(--r-lg);
  width: 100%;
  max-width: 520px;
  padding: 1.2rem;
  max-height: calc(100vh - 2rem);
  overflow-y: auto;
}
.m-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.8rem;
}
.m-head h3 {
  margin: 0;
}
.modal label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: var(--c-muted);
  margin-bottom: 0.6rem;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 0.6rem;
}
.m-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
.error {
  color: var(--c-danger);
}
@media (max-width: 700px) {
  .item {
    grid-template-columns: 1fr;
    align-items: flex-start;
  }
  .numbers {
    align-items: flex-start;
  }
  .grid2 {
    grid-template-columns: 1fr;
  }
}
</style>
