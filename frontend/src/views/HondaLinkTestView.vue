<script setup lang="ts">
import { ref, computed } from "vue";
import { Plug, RefreshCw, ShieldAlert } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { HondaLinkTestResult } from "@/api/endpoints";

const email = ref("");
const password = ref("");
const status = ref<"idle" | "running" | "ok" | "fail">("idle");
const message = ref<string | null>(null);
const result = ref<HondaLinkTestResult | null>(null);

const canSubmit = computed(
  () => status.value !== "running" && email.value.trim() !== "" && password.value !== "",
);

async function runTest() {
  status.value = "running";
  message.value = null;
  result.value = null;
  try {
    const r = await api.testHondaLink(email.value.trim(), password.value);
    result.value = r;
    // Drop the password from memory the moment the request returns; the
    // field keeps whatever the user typed so they can retry.
    if (r.ok) {
      status.value = "ok";
      const df = r.dashboard ? Object.keys(r.dashboard) : [];
      message.value = df.length
        ? `Honda returned: ${df.join(", ")}`
        : "Connected, but no fuel/odometer fields were returned.";
    } else {
      status.value = "fail";
      const last = r.steps.length ? r.steps[r.steps.length - 1] : null;
      message.value = last ? `${last.step}: ${last.detail}` : "Test failed.";
    }
  } catch (e: unknown) {
    status.value = "fail";
    message.value = e instanceof Error ? e.message : "Request failed.";
  }
}
</script>

<template>
  <div class="hondalink">
    <h1>HondaLink connection test</h1>

    <section class="card warn-card">
      <p class="muted">
        <ShieldAlert :size="14" /> This signs in to Honda's servers with your
        HondaLink email and password to check whether your vehicle's data is
        reachable. It is <strong>read-only</strong> — it never locks, unlocks,
        or starts the car. Your credentials are used for this one request and
        are <strong>not saved</strong> anywhere. This uses Honda's unofficial
        app API; on a 2019 Pilot the dashboard call is likely to be refused.
      </p>
    </section>

    <section class="card">
      <h3>HondaLink account</h3>
      <div class="grid two">
        <label class="full">
          Email
          <input
            type="email"
            v-model="email"
            placeholder="you@example.com"
            autocomplete="off"
          />
        </label>
        <label class="full">
          Password
          <input
            type="password"
            v-model="password"
            placeholder="your HondaLink password"
            autocomplete="off"
            @keyup.enter="canSubmit && runTest()"
          />
        </label>
      </div>
      <div class="actions">
        <button type="button" class="primary" :disabled="!canSubmit" @click="runTest">
          <Plug :size="14" /> Test connection
        </button>
        <span v-if="status === 'running'" class="muted">
          <RefreshCw :size="12" class="spin" /> contacting Honda… (may take up to a minute)
        </span>
        <span v-else-if="status === 'ok'" class="badge success">{{ message }}</span>
        <span v-else-if="status === 'fail'" class="badge danger">{{ message }}</span>
      </div>
    </section>

    <section v-if="result" class="card">
      <h3>Result</h3>

      <ol class="steps">
        <li v-for="(s, i) in result.steps" :key="i" :class="s.ok ? 'good' : 'bad'">
          <span class="dot" />
          <span class="name">{{ s.step }}</span>
          <span class="detail">{{ s.detail }}</span>
        </li>
      </ol>

      <div v-if="result.vehicles.length" class="vehicles">
        <h4>Vehicles on the account</h4>
        <ul>
          <li v-for="(v, i) in result.vehicles" :key="i">
            {{ v.model_year }} {{ v.model }} <span class="muted">(VIN {{ v.vin_last4 }})</span>
          </li>
        </ul>
      </div>

      <template v-if="result.dashboard">
        <h4>Dashboard data</h4>
        <pre>{{ JSON.stringify(result.dashboard, null, 2) }}</pre>
      </template>
      <p v-else class="muted">
        No dashboard data was returned. This is the expected result when the
        vehicle's telematics generation isn't served by Honda's current API.
        Smartcar is the sanctioned alternative for fuel, odometer, oil life,
        and tire pressure.
      </p>
    </section>
  </div>
</template>

<style scoped>
.hondalink {
  max-width: 760px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.warn-card {
  border-color: var(--c-warn, var(--c-line));
}
.warn-card .muted {
  margin: 0;
  line-height: 1.5;
}
.grid.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}
.grid.two .full {
  grid-column: 1 / -1;
}
label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.85rem;
  color: var(--c-ink1, inherit);
}
.actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 1rem;
  flex-wrap: wrap;
}
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.steps li {
  display: grid;
  grid-template-columns: auto 7rem 1fr;
  align-items: center;
  gap: 0.6rem;
  font-size: 0.85rem;
}
.steps .dot {
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 50%;
  background: var(--c-line);
}
.steps li.good .dot {
  background: var(--c-ok, #3fb950);
}
.steps li.bad .dot {
  background: var(--c-danger, #f85149);
}
.steps .name {
  font-weight: 600;
  text-transform: capitalize;
}
.steps .detail {
  color: var(--c-ink2, var(--c-ink1));
}
.vehicles ul {
  margin: 0.25rem 0 0;
  padding-left: 1.1rem;
}
h4 {
  margin: 1rem 0 0.4rem;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--c-ink2, var(--c-ink1));
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
