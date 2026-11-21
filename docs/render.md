# Rendering, Living Canvas, 3-Level Progressive UI, and Single-Source Context

Seon renders HTML for humans and structured context for LLMs from ONE reactive walk (`seon.render`, `seon.render.*`, `seon.model.wire`), guaranteeing that what appears in the browser matches what agents receive on the wire.

## 1. Incremental `datahike.reads` Walk & Reagent Event Wiring (`seon.render`, `seon.store`)

`seon.render/step-walk` tracks per-component `datahike.reads/reads-index` read-sets so untouched components do zero work on commits or high-frequency streams (`0.02 ms/frame` measured `[M]`, >400x headroom inside an `8.33 ms` 120 fps frame budget).

- **Cache-transparent read tracking (`seon.store/lru-get!`):** Datahike index cache hits capture and replay read ranges into `datahike.index.interface/*reads*` (`seon.chronicle-test/lru-get-preserves-datahike-reads-on-cache-hit-test`), guaranteeing cached reads stay 100% reactive across live SSE ticks and page reloads.
- **Auto-injected stable IDs & fault isolation:** `render/ensure-root-id` injects deterministic `:id` attributes for clean Datastar Idiomorph morphing, and every component/action executes inside an isolated `try/catch` fault card boundary.
- **Standard Reagent events:** `:on-click`, `:on-submit`, and `:on-change` accept `#(...)`, `[fn args]`, bare `fn`/`var`, or quoted `'(ns/fn args)`. `POST /call` validates `^:seon/callable` (`seon.web.actions`), dispatches asynchronously onto `ui-ch`, and coalesces targeted SSE DOM morphs at `8 ms` (`120 Hz`) over `/events`.
- **Clojure-data CSS (`seon.web.styles`, `seon.web.css`):** UI styles compile cleanly from Clojure data structures without duplicating raw CSS blobs.
- **Schema-attached tiles (`seon.render.tiles`, `seon.render.ns-tile`):** Malli schemas declare `{:seon.render/hiccup ns/fn}` (`:seon.health/report`, `:seon.code.ns/entity`, `:seon.code.var/entity`, `:seon.code.doc/entity`, `:seon.schema/entity`, `:seon.task/entity`, `:seon.agent/entity`, `:seon.bench.run/entity`, `:seon.ctx/snapshot`). `ns-tile` renders router subsystem tables for coordinators and leaf views (`Public API`, `Private helpers`, `Requires` aliases via `ns-require-aliases`, covering tests, auto-linked docs, working schemas, health alerts) for workers.

## 2. Inside-Out User Viewport, Living Canvas & Chronicle (`seon.render.canvas`, `seon.render.chronicle`)

User navigation state (`:seon.user/viewing`, `:seon.user/view-mode`, `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/as-of-tx`) lives exclusively on `:user/default` in Datahike. Any agent can move the user's browser live via `(user/show-user! ...)`.

- **Collapsible Recursive Left Sidebar (`#seon-sidebar`):** Walks `:seon.agent/parent` refs recursively with live streaming status indicators and 1-click expand/collapse.
- **`:canvas` (Agent-Perspective Composable Living Canvas, `seon.render.canvas`, `seon.render.canvas.live`, `seon.render.chronicle`):**
  - **Uniform Agent Perspective (Zero `:agent/root` Special-Casing):** Viewing ANY agent shows (1) **Parent Request** (`parent-request-section`: clickable `↑ parent`, assigned `:seon.task/*`, incoming `:seon.agent.call/*`), (2) **Live Self Work** (`self-work-section` + `render-in-flight-panel`: instant user ack, pulsing status, own universal merge-gate pills `[tests ✓/✗ · ready/merged]`, 1-click live thinking toggle, and live expandable `Thinking & actions` showing latest `:intent` collapsed and full Thinking / Intent / Code / Output / Result expanded — mounted even above custom `:seon.agent/render-fn`!), (3) **Children & Subagents Progress** (`render-work-progress`: recursive tree of outgoing calls + child agents with live intents and merge-gate readiness — clicking any child jumps straight into its Canvas), and (4) **System Pulse** (`#cv-bench-runs` live benchmarks + Chronicle Living Hero `#cv-living-hero`).
  - **Composable Render Functions & History:** Agents evolve their UI without discarding prior work via `(seon.render.canvas.live/compose-live-canvas ctx {:visual ...})` + `(user/set-render! 'ns/fn)` (or `(user/canvas! ...)`). Every `(user/canvas!)`, `(user/set-render!)`, or render-fn edit advances top-bar **`◀ N / M ▶ · Live`** Datahike `d/as-of` history.
  - **Single Source of Truth for Humans & Agents:** The exact same `parent-request-section` and `render-work-progress` Hiccup functions drive both the Browser Canvas and coordinating agents' `<neighborhood-activity>` via `render/hiccup->text`, plus `(user/monitor)`.
- **`:workspace` (Chronological Chat + Live Context Tiles):** Calm chronological timeline on the left, live auto-discovered `@seon.ctx/ctx` and `result/e<n>` tiles on the right (`seon.render.workspace` / `seon.render.ui`).
- **`:debug` (Zero-Cost Wire & System X-Ray, `seon.render.debug`):** Exact LLM wire request, live `@seon.ctx/ctx` pull snapshot, side-by-side Human HTML vs Model Text (`render/hiccup->text`), per-unit `datahike.reads` sets, test verdicts, and 3-way branch diffs.
## 3. Single-Source Wire Format, XML Session Framing & Ephemeral Tail (`seon.render.wire`, `seon.render.tail`, `seon.model.wire`)

Wire assembly enforces prefix-cache-friendly ordering (`86–96%` KV cache hit rates measured `[M]`):

1. **Byte-identical `system` prompt** (KV prefix anchor).
2. **Example sessions (`:seon.agent/examples`):** Framed in `<session id="example/..." kind="example" from="seon">` using the exact same `user`/`assistant`/`tool` wire roles.
3. **Live session opening:** `<agent ...>` + static `<walk-context>` (`ctx-tile`, `task-tile`, `ns-tile` projected via `render/hiccup->text`).
4. **Append-only conversation turns:** Exact `user`, `assistant` (`content` + `reasoning_content` + `tool_calls`), and `tool` (`result/e<n>`) messages.
5. **Ephemeral tail (`seon.render.tail`):** `branch-status` (with turn budget), `system-health`, `neighborhood-activity`, `chronicle-tail`, and — when a human tab is viewing — one ephemeral `live-viewport` screenshot (`target/seon-browser`), replaced every turn and never persisted in history.
