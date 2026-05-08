# UI design workflow

## Tooling

- **[claude.ai/design](https://claude.ai/design)** — primary tool for visual mockups, layout planning, and component variant exploration. Use it before writing Vue code for any new view.
- **myvitals** — reference for dark-theme aesthetic + sidebar layout. Mirror that look unless we have a reason to diverge.
- **uPlot** — all time-series charts. Don't reach for Chart.js / D3 / ECharts.
- **MapLibre GL** — all maps (trip routes, fillup stations).
- **No icon framework** at launch — inline SVGs or one of the lightweight sets (Lucide via `lucide-vue-next`).

## When to mockup vs build

| Scenario | Approach |
|---|---|
| Genuinely new view (no analog in myvitals) | Mockup in claude.ai/design first → review → build |
| Variant of an existing view (e.g. Trips list inspired by myvitals' Workouts list) | Skip the mockup; clone the existing pattern |
| Reorg of an existing layout | Quick claude.ai/design A/B if the change is non-trivial |
| One component (button, badge) | Build directly |

## Pages with the highest design ROI

These are the views where time spent in claude.ai/design pays back the most:

1. **Live gauges** — analog/digital mix, what's primary vs secondary, density vs whitespace
2. **Trip detail** — split between map + timeline, how brushing works
3. **Fuel stations map** — clustering UX, popover content, fly-to behavior
4. **Maintenance reminders dashboard** — overdue vs upcoming, severity color coding

## Anti-patterns to avoid

- Wall-of-charts. If the page has more than 4 charts above the fold, simplify.
- "Tron-y" automotive UI clichés (neon gradients, racing fonts). Default to clean and quiet.
- Skeuomorphic gauges that aren't readable at a glance.
- Hidden settings. The Settings page should expose every toggle, no secret-handshake config.
