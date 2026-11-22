# Rendering: ONE Rule-Projected Surface, Upgrade Ladder & Single-Source Wire

Seon decouples data from rendering into **one reactive pipeline** (`seon.render.surface`, `seon.render.fit`, `seon.render.entities`, `seon.signals`, `seon.render.wire`) — driving both real-estate-adaptive 120 fps browser HTML (`:target :html`) and KV-cache-safe OpenAI Wire context (`:target :wire`) from the exact same Datahike ref graph and pure Hiccup functions.

## 1. Programmatic Ref Graph & Mechanical Renderer Discovery (`seon.render.fit`, `seon.render.entities`)

All relationships live as standard Malli/Clojure/Jolt/Datahike `:db.type/ref` edges:
- **Schemas (`:seon.schema/*`):** `:seon.schema/keys`, `:seon.schema/optional-keys`, `:seon.schema/ref-targets`.
- **Vars (`:seon.code.var/*`):** `:seon.code.var/input`, `:seon.code.var/optional-input`, `:seon.code.var/output`, `:seon.code.var/calls`, plus `:seon.code.ns/requires-ns`, `:seon.test/var-ref`, and `:seon.code.doc/references`.
- **Renderers are ordinary pure `ns-map -> ns-map` functions:** Any pure var (empty `:seon.code.var/ops`, non-private, non-test, non-`^:seon.flow/edge`) whose `:seon.code.var/output` includes `:seon.ui/hiccup` is discovered mechanically via `seon.render.fit/discover-renderers` (indexed `:avet` intersection of `:seon.code.var/input` and `:seon.code.var/output`).
- **Multi-level complete thoughts (`seon.render.entities`):** Core entities (`var`, `error` + nested `fix`, `task`, `schema`, `doc`, `test`, `eval`, `ns`, `agent`, `rule`) each have 2–3 pure renderers (`chip`/`badge`/`row` -> `sig`/`card` -> `full`) tagged with integer `:data-min-width` / `:seon.ui/min-width` hints, alongside pure Hiccup ephemeral tail renderers (`render-branch-status-tail`, `render-scoped-health-tail`, `render-activity-tail`, `render-inbox-tail`, `render-system-health-tail`, `render-viewport-tail`) and schema tiles (`seon.render.tiles`, `seon.render.ns-tile/ns-tile`, `seon.render.lineage/task-lineage-tile`, `seon.error/render`).

## 2. 3-Signal Native Rank & Dual-Target Complete-Thought Upgrade Ladder (`seon.render.fit`, `seon.ctx.project`)

Candidates from `seon.ctx/related-updates` and generic 2-read world discovery (`seon.ctx.project/agent-world-datoms`) pass through one pure rank-and-fit pipeline:

1. **3-Signal Native Datahike Rank (`seon.render.fit/rank-candidates`):** Scores each candidate with zero synthetic `age` machinery using tunable weights (`:seon.config/rank-weights`, read by `seon.render.fit/read-rank-weights`):
   - **Urgency / Anchor:** Open `:seon.error/status`, matching `:seon.fix/*`, failing reach tests, branch-edited vars, active tasks, and pinned refs.
   - **Recency:** Native Datahike `max ?tx` / `:seon.agent.eval/turn` normalized across the candidate pool (re-touching an older entity snaps it straight back to the top).
   - **Cumulative Touches / Convergence:** Distinct eval touches plus converging rule/ref paths hitting the entity.
2. **2-Pass Complete-Thought Upgrade Ladder (`seon.render.fit/upgrade-ladder`, `seon.render.fit/fit-projections`):** Pulls each ranked entity once, runs its eligible pure `:seon.ui/hiccup` renderers, and measures actual `:tokens`, `:chars`, `:min-width`, `:area`, and `:lines` via `seon.render.fit/measure-hiccup`. Pass 1 picks the smallest complete thought for top-ranked items; Pass 2 upgrades in rank order (`:breadth-first` or `:depth-first`) while within budget — never clipping mid-thought:
   - **`:target :html` (Responsive Real-Estate Fit):** Browser viewport `:seon.user/viewport-w` x `:seon.user/viewport-h` syncs debounced onto `:user/default` via `seon.web/set-viewport!`. `seon.render.fit/resolve-slot-budget` computes container slot geometry (`:seon.ui/slot`, `:seon.ui/avail-w`, `:area-budget`, `:max-items`), picking rich cards/panels on wide desktop (`1440x900`) and compact chips/bars in sidebars or narrow mobile (`430x900`) — zero arbitrary `@media` layout cutoffs.
   - **`:target :wire` (KV-Cache Token-Budget Fit):** Fits `:seon.view/token-budget` and `:seon.view/max-items` using the `already-shown` self-emptying queue (`seon.ctx.project/compute-shown-delta`): unchanged items cost `0` repeated tokens, re-touched `?tx` deltas re-warm cleanly, upgrades append only new detail, and output orders least-volatile -> most-volatile for KV-cache prefix stability.

## 3. Five Ambient Operational Signals (`seon.signals`)

`seon.signals/all-signals` derives 5 ambient signals via indexed Datahike reads in `< 5 ms` (cached per commit-id via `seon.store/lru-get!` with `datahike.reads` replay):
- **Flow (`:seon.signal/flow`, `seon.signals/read-flow-signal`):** Running, waiting, stalled, blocked, and ready agent counts + live pulse state.
- **Pressure (`:seon.signal/pressure`, `seon.signals/read-pressure-signal`):** Eval tick/byte allocation percentages against `:seon.config/eval-ticks` / `:seon.config/eval-bytes` and UI frame-ms pressure.
- **Context (`:seon.signal/context`, `seon.signals/read-context-signal`):** KV-cache prefix reuse %, token budget utilization, eval success %, and prefix busts.
- **Proof (`:seon.signal/proof`, `seon.signals/read-proof-signal`):** Cached green / failing / unproved tests and branch merge readiness.
- **Health (`:seon.signal/health`, `seon.signals/read-health-signal`):** Structural errors, open errors, warnings, and oversized namespaces.

Rendered ambiently as status dots, hairline heat bars, and subtle `:calm` / `:warm` / `:hot` shading/pulse via `seon.signals/render-signals-bar` (`>= 220px`) and `seon.signals/render-signals-panel` (`>= 560px`), auto-selected by the Upgrade Ladder (no car metaphors).

## 4. ONE Surface Walker, 120 fps Shell & Generic `d/as-of` Time-Travel (`seon.render.surface`, `seon.render`, `seon.render.ui`)

- **ONE Surface Walker (`seon.render.surface/render-surface`, `seon.render.surface/build-surface-model`):** Powers `:canvas`, `:workspace`, and `:debug` inside `#seon-view-unit` in `< 12 ms` cold / `< 2 ms` warm (`[M]` `:canvas` `6.31 ms`), displaying fitted entities, ambient signals, live conversation/activity folds, and (in `:debug`) exact OpenAI Wire messages, Upgrade Ladder trace, `seon.rule` firing trace, and side-by-side Human HTML vs `seon.render/hiccup->text` Model Text.
- **Generic Entity-Derived Time-Travel (`seon.render.data/view-history-txs`, `seon.render.surface/render-surface-timeline`):** Computes sorted distinct Datahike transaction IDs across all projected entities, agent-linked refs (task, pins, curated, errors), user messages, and replies — powering `<-` / `->` `d/as-of` scrubbing uniformly across `:canvas`, `:workspace`, and `:debug`.
- **Inside-Out Viewport & Incremental `datahike.reads` Walk (`seon.render/step-walk`, `seon.render.walk`, `seon.render.ui/page-unit-specs`):** Navigation state (`:seon.user/viewing`, `:seon.user/view-mode`, `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/as-of-tx`, `:seon.user/viewport-w`, `:seon.user/viewport-h`) lives exclusively on `:user/default` (live-movable via `(user/show-user! ...)`). `seon.render/step-walk` tracks per-unit `datahike.reads` (including lazy sequences and `seon.store/lru-get!` read-replay) across 6 mounted units (`#seon-css` from `seon.web.styles/full-css`, `#seon-header`, `#seon-sidebar`, `#seon-view-unit`, `#seon-stream-unit`, `#seon-composer-unit`) with `seon.render/ensure-root-id`, `^:seon/callable` Reagent events (`seon.web.actions`), `120 Hz` (`8 ms`) coalesced Datastar SSE morphs, Chronicle milestones (`seon.render.chronicle`), and live benchmark tiles (`seon.render.tiles/bench-run-tile`).

## 5. Single-Source OpenAI Wire & Eval Output (`seon.render.wire`, `seon.model.wire`, `seon.render.print`)

`seon.render.wire/build-live-wire-request` and `seon.model.wire/format-eval-output` share the exact same Hiccup -> `seon.render/hiccup->text` pipeline (`86–96%` KV-cache hit rates `[M]`):
1. **Byte-identical `system` prompt** (KV prefix anchor) + curated `:seon.agent/examples` sessions.
2. **Merge-base-anchored `<walk-context>`:** Derived via `seon.rule.context/derive-context` + `seon.ctx/related-updates` -> `seon.render.fit/fit-projections` (`:target :wire`, `already-shown` queue, least-volatile -> most-volatile ordering) -> `seon.render/hiccup->text`.
3. **Append-only conversation turns (`user` / `assistant` / `tool`):** Every eval result runs `seon.model.wire/format-eval-output` — preferring discovered `:seon.ui/hiccup` renderers (`seon.render.fit/discover-renderers`) and falling back to the 4-stage breadth-before-depth schema printer (`seon.render/render-value`, `seon.render.print.*`) -> `seon.render/hiccup->text`, with exact `(user/expand! ...)` drill-ins and live schema breadcrumbs.
4. **Pure Hiccup `<ephemeral-tail>`:** Rendered from `seon.render.entities` tail renderers (branch status + turn budget, scoped/system health, neighborhood activity, unread inbox, Chronicle anchors, and optional live `WKWebView` screenshot from `target/seon-browser`), replaced every turn and never persisted in history.
