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
- **`:canvas` (Living Canvas, Chronicle & Live Benchmark Monitor):**
  - **Live Benchmark Runs (`#cv-bench-runs`):** Mounted above the hero (`seon.render.tiles/bench-run-tile`), streaming active and recent `:seon.bench.run/*` batches over Datastar SSE (per-trial status, eval counts, boot/elapsed time, shared proxy tokens, KV-cache hit rate, and live error snippets).
  - **Chronicle Living Hero (`#cv-living-hero` & `#cv-live-delta`):** Renders durable `:seon.summary/*` milestones recorded via `(user/record-summary! ...)` alongside live namespace activity chips (`seon.render.chronicle`).
  - **Self-Replacing Canvas & Datahike Time-Travel (`seon.render.canvas`, `seon.render.markdown`):** Custom `(user/canvas! ...)` or `:seon.agent/render-fn` output with quiet collapsed `▸ Thinking & actions` folds (`render-activity-fold`) and top-bar **`◀ N / M ▶ · Live`** `d/as-of` stepping.
- **`:workspace` (Chronological Chat + Live Context Tiles):** Calm chronological timeline on the left, live auto-discovered `@seon.ctx/ctx` and `result/e<n>` tiles on the right (`seon.render.workspace` / `seon.render.ui`).
- **`:debug` (Zero-Cost Wire & System X-Ray, `seon.render.debug`):** Exact LLM wire request, live `@seon.ctx/ctx` pull snapshot, side-by-side Human HTML vs Model Text (`render/hiccup->text`), per-unit `datahike.reads` sets, test verdicts, and 3-way branch diffs.

## 3. Single-Source Wire Format, XML Session Framing & Ephemeral Tail (`seon.render.wire`, `seon.render.tail`, `seon.model.wire`)

Wire assembly enforces prefix-cache-friendly ordering (`86–96%` KV cache hit rates measured `[M]`):

1. **Byte-identical `system` prompt** (KV prefix anchor).
2. **Example sessions (`:seon.agent/examples`):** Framed in `<session id="example/..." kind="example" from="seon">` using the exact same `user`/`assistant`/`tool` wire roles.
3. **Live session opening:** `<agent ...>` + static `<walk-context>` (`ctx-tile`, `task-tile`, `ns-tile` projected via `render/hiccup->text`).
4. **Append-only conversation turns:** Exact `user`, `assistant` (`content` + `reasoning_content` + `tool_calls`), and `tool` (`result/e<n>`) messages.
5. **Ephemeral tail (`seon.render.tail`):** `branch-status` (with turn budget), `system-health`, `neighborhood-activity`, `chronicle-tail`, and — when a human tab is viewing — one ephemeral `live-viewport` screenshot (`target/seon-browser`), replaced every turn and never persisted in history.
