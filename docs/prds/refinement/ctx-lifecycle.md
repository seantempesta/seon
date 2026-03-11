---
type: prd
status: draft
tags: [prd, database]
---
# Spec-Driven *ctx* Atom Lifecycle

## Context

Steps 1-5 of the render pipeline are done. But the **dynamic namespace lifecycle** isn't wired: namespaces declare `::*ctx*` specs but nothing creates validated ctx atoms, injects vars, or connects the page renderer to SSE push.

**Goal:** When a namespace registers `::*ctx*`, the system creates a Malli-validated ctx atom. No bad data can enter it. The atom is auto-injected into any function call from the webserver whose input spec includes `*ctx*`. Changes push SSE to browser. State persists to Datalevin (entity ID = instance ID, just syncing atom state after validation).

## Key Design Decisions

1. **`::*ctx*` spec = "this namespace is dynamic"** — declaring it triggers the whole lifecycle
2. **No bad data ever** — atom validator rejects non-conforming `swap!` with Malli explanation. The spec IS the contract.
3. **Auto-injection** — if a function's input spec includes a `*ctx*` key, the webserver injects the ctx atom value automatically when calling it. Functions don't need to know about the instance system.
4. **`*conn*` for persistence** — declaring `::*ctx*` also wires up a `*conn*` (Datalevin connection) for the namespace. State syncs to Datalevin after successful validation. Entity ID = instance ID.
5. **Future: `::*conn*` with Datalevin schema** — deferred, focus on `*ctx*` first.

## Implementation Steps

### Step 1: `::ctx-schema` option in `seon.ctx/create!`

**File:** `src/seon/ctx.clj`

Add `::ctx-schema` option (Malli schema keyword). When provided:

- Set atom `:validator` that validates entire state against the schema on every `swap!`
- Invalid → throws `ex-info` with `:spec`, `:errors` (Malli humanized explanation)
- Valid → state accepted, watches fire (persistence, SSE push)

Persistence stores: entity ID = instance ID, `:seon.ctx/data` = EDN of validated state.

### Step 2: `seon.ns.lifecycle` module (new)

**File:** `src/seon/ns/lifecycle.clj`

| Function | What it does |
|----------|-------------|
| `dynamic-namespace?` | Checks `:seon.ns/dynamic?` in Datalevin (set by scanner when `::*ctx*` spec found) |
| `ctx-spec-key` | `'seon.health.workout` → `:seon.health.workout/*ctx*` |
| `initial-value` | Calls namespace's `initial-state` fn if present; falls back to `mg/generate` from spec |
| `find-page-render-fn` | Queries Datalevin for `:seon.fn/page-renderer? true`, `requiring-resolve`s it |
| `make-render-fn` | Wraps page renderer: raw `ctx-value` → `{ctx-key ctx-value}` → renderer → `:seon.render/html` |
| `inject-vars!` | `intern` + `.setDynamic` for `*ctx*` (atom) and `*conn*` (Datalevin conn) into namespace |
| `resolve-instance` | Given ns-sym + optional instance-id: if ID given → look up; if nil → query Datalevin for most recent instance for this namespace; if none → return nil |
| `ensure-instance!` | Calls `resolve-instance`. If found → resume from Datalevin state. If nil → create fresh from `initial-value` + `ctx/create!` with `ctx-schema` + `persist? true` + `track-clients? true`. Inject vars. Return `{:instance-id :ctx-atom}` |

### Step 3: Auto-injection in webserver function calls

**File:** `src/seon/ns/routes.clj` (in `function-call-handler`)

When the webserver calls a namespace function (e.g., `POST /ns/seon.health.workout/add-set!`):

1. Check the function's input spec (from Datalevin: `:seon.fn/render-input-keys` or input spec's contains-keys)
2. If any key ends in `*ctx*` → inject current `@*ctx*` atom value under that key
3. If any key ends in `*conn*` → inject `*conn*` under that key
4. The function receives a complete input map with ctx/conn auto-populated
5. If the function mutates ctx (via the injected atom reference or return value), the validation + SSE chain fires

### Step 4: Update routes — spec-driven reactive detection

**File:** `src/seon/ns/routes.clj`

Replace old detection:

- `namespace-has-reactive-render?` → `lifecycle/dynamic-namespace?`
- `get-initial-state` → `lifecycle/initial-value`
- `get-render-content-fn` → `lifecycle/find-page-render-fn`
- Instance creation → `lifecycle/ensure-instance!`

**Instance resolution** (in `ensure-instance!`):

1. `?instance=abc123` → use that specific instance
2. No `?instance=` → find the **most recent** instance for this namespace in Datalevin
3. No instances exist → create one, persist it, redirect with `?instance=` in URL

Handler flow:

```
?format=ai|raw → render-for-format (done)
dynamic? → ensure-instance!(ns, instance-id-or-nil)
           → resolves to existing or creates new
           → if no ?instance in URL, redirect to include it (bookmarkable)
           → serve reactive page
else → introspection view (reuse existing)

```

### Step 5: Workout as proof

**Files:** `src/seon/health/workout.clj`

- Already has `::*ctx*` spec and public `workouts` var
- Add `initial-state` fn returning `{::workouts workouts}`
- `workout/render.clj` already has `page-render` — no changes

### Step 6: Instance resume from Datalevin

Built into `resolve-instance` + `ensure-instance!`:

1. `resolve-instance` with explicit ID → query Datalevin for `:seon.ctx/instance-id` = that ID
2. `resolve-instance` with nil → query Datalevin for most recent instance where `:seon.ctx/namespace` = this ns
3. If found: parse `:seon.ctx/data` (EDN string) as saved state
4. Validate against `::*ctx*` spec (data may be stale if spec evolved)
5. Valid → create atom with saved state. Invalid → log, fall back to `initial-value`
6. Inject vars, wire SSE — same as fresh creation

### Step 7: Shutdown backup + restart restore

**File:** `src/seon/ns/lifecycle.clj` and `src/seon/ctx.clj`

**On shutdown:** `backup-all-instances!` — iterate all ctx atoms in registry, force-flush pending persistence.
**On restart:** `restore-instances!` — query Datalevin for all persisted instances, recreate if spec still valid.

Wire into Integrant halt/suspend in `src/seon/core.clj`.

### Step 8: Remove old reactive detection

Remove from `routes.clj`: `namespace-has-reactive-render?`, `get-initial-state`, `get-render-content-fn`

## Files to Modify

| File | Change |
|------|--------|
| `src/seon/ctx.clj` | Add `::ctx-schema` for whole-state Malli validation |
| `src/seon/ns/lifecycle.clj` | **New.** Ctx creation, var injection, spec detection |
| `src/seon/ns/routes.clj` | Replace old reactive detection; auto-inject ctx in function calls |
| `src/seon/health/workout.clj` | Add `initial-state` fn |
| `src/seon/core.clj` | Wire `backup-all-instances!` into shutdown hook |
| `test/seon/ns/lifecycle_test.clj` | **New.** Tests |

## Verification

```clojure
;; Bad data rejected
(swap! seon.health.workout/*ctx* assoc :bad-key 1)
;; → throws "ctx does not conform to spec"

;; Valid update + SSE push
(swap! seon.health.workout/*ctx* update :seon.health.workout/workouts conj
       {:seon.health.workout/exercise "Pull-up" :seon.health.workout/sets 3
        :seon.health.workout/reps 10 :seon.health.workout/weight 0})

;; Instance resume after restart
(reset)
@seon.health.workout/*ctx* ;; → state survived restart

```

**Note:** Pages not backed by `*ctx*` atoms (e.g., the agent observatory) use explicit `refresh-all!` calls from their data write functions. See `seon.ai.datalevin/maybe-refresh-sse!` and the `agent-registry` watch in `seon.web.agents/init!`.
