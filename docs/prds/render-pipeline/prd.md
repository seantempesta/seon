# PRD: Unified Render Pipeline

## Status: Ready to Implement

## Summary

Wire the spec-driven rendering system end-to-end and unify all context/rendering code paths. This PRD captures the remaining work to complete the vision described in `spec-driven-rendering/prd.md` and `datalevin-migration/prd.md` Phase 3.

**The core principle**: Everything is specs and functions. Code is read into Datalevin so we can query the full graph. Rendering is determined by querying Datalevin for the most specific function that accepts the data shape and returns `:seon.render/html` or `:seon.render/ai`. When code changes, the scanner updates Datalevin, resolution caches invalidate, and re-renders push to all connected clients — whether AI (flow channels) or HTML (SSE/flow channels).

No manual registries. No per-instance render-fn storage. Turtles all the way down.

## Architecture

```
Code Change (edit, Super REPL eval, agent form)
    │
    ▼
Scanner (seon.graph.scanner + analyzer)
    │ updates Datalevin master DB
    ▼
Cache Invalidation
    │ clears resolution cache for affected data shapes
    ▼
Push Re-renders
    ├── :seon.render/html → flow out-port → SSE adapter → browser
    └── :seon.render/ai  → flow out-port → agent channel
```

### How Rendering Works

1. **Data lives in ctx atoms** — each instance has a `seon.ctx` atom with domain data
2. **Ctx changes trigger rendering** — watcher fires on atom change
3. **Renderer resolved from Datalevin** — `seon.render/find-renderer` queries for functions whose input keys match the data's keys and whose output spec contains the target format key
4. **Resolution is cached** — keyed by `[format (set (keys data))]`, invalidated when scanner runs
5. **Rendered output pushed to clients** — HTML clients get Datastar SSE patches, AI clients get text via flow channels

### What Replaces What

| Old | New | Status |
|-----|-----|--------|
| `seon.render/*renderers` atom | `seon.render/find-renderer` (Datalevin query) | Algorithm exists, not wired |
| `seon.render/register-renderer!` | Write a function with `:malli/schema` metadata | Convention, no code needed |
| `seon.web.reactive.instance` (render-fn per instance) | `seon.ctx` + Datalevin render resolution | Need to migrate callers |
| `seon.web.reactive.ctx` (render-fn per namespace) | `seon.ctx` + Datalevin render resolution | Need to migrate callers |
| `seon.primer.render` (key-based registry) | Datalevin render resolution | Need to verify |

## Prerequisites

These are already built and working:

- [x] Datalevin master DB with code index schema (`seon.fn/*`, `seon.spec/*`, etc.)
- [x] Scanner pipeline: startup scan + dev hook feed `ingest-analysis!` / `ingest-incremental!`
- [x] Spec scanner: `seon.graph.scanner` extracts `schema/register!` calls, builds spec entities
- [x] Function-spec linking: `link-fns-to-specs` matches fns to specs, pre-computes `render-input-keys`
- [x] `find-renderer` algorithm in `seon.render` (lines 244-293)
- [x] `seon.ctx` unified context system with Datalevin persistence + SSE push
- [x] Topological context builder (`seon.graph.context`) with recursive pull + toposort

## Phases

### Phase 1: First Render Function (Proof of Life) [COMPLETE]

**Goal**: One working render function discovered from Datalevin, producing output for both formats.

1. **Create `seon.health.workout.render`** (or similar domain `.render` namespace):

   ```clojure
   (ns seon.health.workout.render
     (:require [seon.schema :as schema]))

   (schema/register! ::workout-html-and-ai
     [:map
      [:seon.render/html :any]
      [:seon.render/ai :string]])

   (defn workout-set
     "Renders a workout set for both HTML and AI."
     {:malli/schema [:=> [:cat :seon.health.workout/log-workout-request]
                         ::workout-html-and-ai]}
     [{:seon.health.workout/keys [exercise sets reps weight]}]
     {:seon.render/html [:tr [:td exercise] [:td (str sets "x" reps)] [:td (str weight "kg")]]
      :seon.render/ai   (str exercise " — " sets "x" reps " @ " weight "kg")})
   ```

2. **Verify scanner picks it up**: After startup scan or dev hook, query Datalevin for the function entity — confirm `:seon.fn/render-input-keys` and `:seon.fn/output-spec` are populated.

3. **Verify `find-renderer` discovers it**: Call `(render/find-renderer conn {:seon.health.workout/exercise "Squat" ...} :html)` — should return `"seon.health.workout.render/workout-set"`.

**Files**: `src/seon/health/workout/render.clj` (new), test file

### Phase 2: Wire Resolution + Cache + Cleanup [COMPLETE]

**Goal**: `seon.render/render` uses Datalevin discovery, old manual registry removed.

1. **Add resolution cache** to `seon.render`:

   ```clojure
   (defonce ^:private resolution-cache (atom {}))
   ;; Key: [format (set (keys data))], Value: qualified-name string
   ;; Invalidated by scanner via (invalidate-render-cache!)
   ```

2. **Rewire `seon.render/render`**:
   - Try cache → try `find-renderer` → fallback to `pprint-clipped`
   - Remove `*renderers` atom, `register-renderer!`, `get-renderer`, `clear-renderers!`
   - Keep `typed`, `schema-of`, `for-ai`, `render-seq`

3. **Wire scanner invalidation**: After `ingest-analysis!` or `ingest-incremental!`, call `render/invalidate-render-cache!`

4. **Add `pprint-clipped` fallback**: Truncated pretty-print (max 500 chars) for data with no matching renderer.

**Files**: `src/seon/render.clj`, `src/seon/graph/ingest.clj` (add invalidation call)

### Phase 3: Client Tracking in seon.ctx [COMPLETE]

**Goal**: `seon.ctx` tracks connected clients per instance for targeted push.

1. **Add `::ctx/clients` to registry entries** — atom of set (channels or flow ports)
2. **`register-client!` / `unregister-client!`** — manage connected clients
3. **Render-on-change watch**: When ctx atom changes AND clients connected:
   - Resolve renderer from Datalevin via `find-renderer`
   - For each client, render in the client's format (`:html` or `:ai`)
   - Push rendered output
4. **Scanner-triggered re-render**: When scanner updates graph, re-resolve renderers for all active ctx instances with clients. If resolution changed, re-render and push.

**Files**: `src/seon/ctx.clj`

### Phase 4: Migrate seon.ns.routes [COMPLETE]

**Goal**: `seon.ns.routes` uses `seon.ctx` instead of `seon.web.reactive.instance`.

This is mostly mechanical — same lifecycle concepts, different namespace:

| Old (`reactive.instance/*`) | New (`ctx/*`) |
|---|---|
| `create-instance!` | `ctx/create!` |
| `destroy-instance!` | `ctx/destroy!` |
| `instance-ctx` / `get-instance` | `ctx/get-atom` / `ctx/get-value` |
| `set-render-fn!` | Not needed — resolved from Datalevin |
| `register-client!` | `ctx/register-client!` |
| `unregister-client!` | `ctx/unregister-client!` |
| `force-push!` | `ctx/force-push!` |

**Files**: `src/seon/ns/routes.clj` (20+ call sites to update)

### Phase 5: Migrate seon.web.browser + Delete Old Code [COMPLETE]

**Goal**: Remove all old rendering infrastructure.

1. **Migrate `seon.web.browser`** from `reactive.ctx/clients` → `ctx/clients`
2. **Delete `src/seon/web/reactive/instance.clj`**
3. **Delete `src/seon/web/reactive/ctx.clj`**
4. **Delete `src/seon/primer/render.clj`** (if superseded)
5. **Delete dead code**: `seon.ai.agent/xtdb-node` atom

### Phase 6: Flow Channels for All Clients

**Goal**: Both HTML and AI clients receive renders via flow channels.

Currently SSE push goes directly to http-kit channels. The target architecture routes through the flow topology:

```
ctx change → namespace step → render resolution →
  ├── :html out-port → SSE adapter step → http-kit channels
  └── :ai out-port → agent flow channel
```

This means a namespace step has typed out-ports for each render format. The SSE adapter is a thin flow step that converts hiccup→HTML and writes to http-kit channels.

**This phase can be deferred** — direct SSE push works fine as a stepping stone. The flow routing adds value when we want backpressure, load balancing, or crash isolation for the rendering pipeline.

### Phase 7: Complete Scanner (`:malli/schema` extraction)

**Goal**: Functions with `:malli/schema` metadata are linked to specs via source analysis.

1. Add edamame parsing of `(defn ^{:malli/schema [...]} ...)` to `seon.graph.scanner`
2. Extract `[:=> [:cat input-spec] output-spec]` forms
3. Use extracted specs as primary in `link-fns-to-specs`, naming convention as fallback

**Files**: `src/seon/graph/scanner.clj`

## Verification

After each phase:

1. Run focused tests for changed namespaces
2. Run full suite: `clojure -M:test -m kaocha.runner` — 0 failures
3. After Phase 1: verify render function appears in Datalevin, `find-renderer` discovers it
4. After Phase 4: verify namespace UI still renders correctly
5. After Phase 5: verify no references to deleted files remain

## Related PRDs

- **[`spec-driven-rendering`](../spec-driven-rendering/prd.md)** — Data model, scanner, resolution algorithm (foundational)
- **[`datalevin-migration`](../datalevin-migration/prd.md)** — Database platform, unified ctx, connection manager
- **[`super-repl`](../super-repl/prd.md)** — Flow harness, agent JVMs, code routing
- **[`namespace-ui`](../namespace-ui/prd.md)** — Presentation layer consuming the render pipeline

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| No render-fn storage in ctx | Renderers are discovered from Datalevin, not manually registered |
| Push-based invalidation | Scanner knows when code changes; no need to poll |
| Cache keyed by data shape | Same keys → same renderer. Cheap equality check. |
| Flow channels deferred (Phase 6) | Direct SSE works as stepping stone; flow adds backpressure later |
| `:malli/schema` extraction deferred (Phase 7) | Naming convention works for now; extraction adds coverage |
