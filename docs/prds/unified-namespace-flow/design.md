---
type: prd
status: draft
tags: [prd, flow, architecture]
---

# Unified Namespace Flow System

## Status: Design (2026-03-14)

Unifying Seon's routing, state management, and rendering around a single set of primitives. Everything routes through the dispatch layer. Agents write plain functions with schemas. The system handles routing, state injection, rendering, and process isolation transparently.

**Builds on:** [[prds/unified-flow/design]], [[prds/schema-unification/design]], [[prds/spec-driven-rendering/prd]], [[prds/render-pipeline/prd]]

**Supersedes:** [[prds/super-repl/prd]], [[prds/agent-repl-interface/prd]]

---

## Core Thesis

Every namespace is an **actor** ([[concepts/namespace-as-process]]) with isolated state and functions. The system routes data to functions based on **Malli schema matching** ([[concepts/renderer-discovery]]) — specificity (most required input keys matched) determines which function handles a request. Namespace authors write plain functions with `:malli/schema` metadata. The system handles routing, `::ctx` injection, rendering, and delivery.

Two execution modes share one interface:

- **In-process** — function runs in the core Seon JVM (fast, no serialization)
- **Separate process** — function runs in an agent JVM (sandboxed, TCP/Nippy, [[architecture/decisions/006-separate-jvm]])

The agent never knows or cares which mode it's in. It requires namespaces, calls functions, gets results. The dispatch layer routes transparently.

**Related:** [[architecture/overview]], [[conventions]], [[concepts/request-reply]]

---

## The Agent's Experience

An agent interacts with Seon by writing Clojure functions with Malli schemas ([[conventions]]). This section defines the complete API surface an agent sees.

### 1. Define Types and State

Types are registered via `schema/register!` ([[components/schema-system]]). All keys are namespaced keywords matching their code namespace.

```clojure
(ns seon.health.workout
  (:require [seon.schema :as schema]))

;; === Domain types ===
(schema/register! ::exercise-name :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::set [:map [::weight ::weight] [::reps ::reps]])
(schema/register! ::exercise [:map
                              [::exercise-name ::exercise-name]
                              [::sets [:vector ::set]]])
(schema/register! ::workout [:map
                             [::exercises [:vector ::exercise]]
                             [::started-at {:optional true} :inst]])

;; === Namespace state ===
;; ::ctx is a reserved key per namespace (expands to :seon.health.workout/ctx)
(schema/register! ::ctx
  [:map
   [::screen [:enum :home :active :history]]
   [::current-workout {:optional true} ::workout]
   [::history [:vector ::workout]]])
```

### 2. Initialize State

The system detects initializers by spec: input does NOT contain `::ctx`, output DOES contain `::ctx`. This distinguishes initializers from stateful functions (which have `::ctx` in both input and output).

```clojure
(defn initial-ctx
  "Returns default state. System merges persisted state over this on resume."
  {:malli/schema [:=> [:cat :map] ::ctx]}
  [_]
  {::screen  :home
   ::history []})
```

The system calls `(initial-ctx {})` at namespace startup, then merges any persisted state from Datalevin over the result.

### 3. Write Functions

All public functions follow map-in/map-out with `:malli/schema`. If `::ctx` appears in the input spec, the system injects it. If `::ctx` appears in the output spec, the system applies it as new state. Callers never pass or see `::ctx`.

```clojure
;; Mutation (! suffix): takes caller args + ::ctx, returns updated ::ctx + data
(defn start-workout!
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]
                            [::workout ::workout]]]}
  [{::keys [ctx]}]
  (let [w {::exercises [] ::started-at (java.util.Date.)}]
    {::ctx     (assoc ctx ::screen :active ::current-workout w)
     ::workout w}))

(defn log-set!
  {:malli/schema [:=> [:cat [:map [::exercise-name ::exercise-name]
                                  [::set ::set]
                                  [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]
                            [::workout ::workout]]]}
  [{::keys [exercise-name set ctx]}]
  (let [w (update (::current-workout ctx) ::exercises
                  add-set-to-exercise exercise-name set)]
    {::ctx     (assoc ctx ::current-workout w)
     ::workout w}))

;; Read: takes ::ctx, returns computed data (no ::ctx in output = no state change)
(defn total-volume
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::volume :double]]]}
  [{::keys [ctx]}]
  {::volume (->> (::history ctx)
                 (mapcat ::exercises)
                 (mapcat ::sets)
                 (reduce (fn [acc s] (+ acc (* (::weight s) (::reps s)))) 0.0))})
```

### 4. Render

Render functions return `:seon.render/html` or `:seon.render/ai`. The system discovers them via the [[components/code-graph]] and calls them automatically based on the connection type ([[concepts/renderer-discovery]]).

Hiccup composition uses Reagent-style `[fn {args}]` vectors. The system walks the hiccup tree, resolves function references, calls them, and splices the `:seon.render/html` result. Sub-renderers get `::ctx` auto-injected if their spec requires it.

```clojure
;; Hiccup is a recursive Malli schema — validated at runtime, sub-ms perf.
;; Defined in seon.render, not aliased to wire types.
;; Structure: primitives | [:tag {attrs} & children] | (seq of hiccup)
;; See research/hiccup-schema.md for full definition and REPL validation results.
;; HTML attr maps use [:map-of :keyword :any] — acceptable boundary (HTML is polymorphic).

(defn render-exercise
  {:malli/schema [:=> [:cat [:map [::exercise ::exercise]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [exercise]}]
  {:seon.render/html
   [:div {:class "bg-base-900 p-2 rounded"}
    [:span {:class "text-amber-400 text-xs"} (::exercise-name exercise)]
    (for [s (::sets exercise)]
      [:span {:class "text-xs text-300"} (str (::weight s) "x" (::reps s))])]})

(defn render-workout
  {:malli/schema [:=> [:cat [:map [::workout ::workout]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [workout]}]
  {:seon.render/html
   [:div {:class "space-y-2"}
    (for [ex (::exercises workout)]
      ;; Reagent-style: system resolves this, calls render-exercise, splices result
      [render-exercise {::exercise ex}])]})

(defn render-page
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [ctx]}]
  {:seon.render/html
   [:main#morph {:class "p-3 bg-base-950 font-mono text-50"}
    [:h1 {:class "text-sm text-cream"} "Workout"]
    (case (::screen ctx)
      :home
      [:div
       [:button {:on:click :start-workout!} "Start Workout"]
       [:h2 {:class "text-xs text-400 mt-3"} "History"]
       (for [w (::history ctx)]
         [render-workout {::workout w}])]

      :active
      [:div
       [render-workout {::workout (::current-workout ctx)}]
       [:form {:on:submit:form :log-set!}
        [:div {:class "flex gap-2 mt-2"}
         [:input {:field ::exercise-name :placeholder "Exercise"
                  :class "bg-base-800 text-50 p-1 text-xs rounded"}]
         [:input {:field ::weight :type "number" :step "2.5"
                  :class "bg-base-800 text-50 p-1 text-xs rounded w-16"}]
         [:input {:field ::reps :type "number"
                  :class "bg-base-800 text-50 p-1 text-xs rounded w-12"}]]
        [:button {:type "submit"
                  :class "bg-amber-600 text-xs px-3 py-1 rounded mt-2"}
         "Log Set"]]
       [:button {:on:click :finish-workout!
                 :class "text-red-400 text-xs mt-2"}
        "Finish"]]

      :history
      [:div (for [w (::history ctx)]
              [render-workout {::workout w}])])]})

;; AI rendering for agent REPLs
(defn render-ai-summary
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [:seon.render/ai :string]]]}
  [{::keys [ctx]}]
  {:seon.render/ai
   (str (count (::history ctx)) " past workouts"
        (when-let [w (::current-workout ctx)]
          (str ", active: " (count (::exercises w)) " exercises")))})
```

Note: render-page uses the existing `transform.clj` conventions (`:on:click`, `:on:submit:form`, `:field`) — NOT raw Datastar attributes. The transform layer converts these to Datastar directives. This is already working in the current codebase.

**Always POST:** All browser→server calls use `@post`. POST supports request bodies (form data, signals), works with SSE keep-alive on hidden tabs, and removes the need to guess read vs write from function names. Simpler = better. See `research/datastar-dsl.md` for full DSL specification.

### 5. Send Data

`seon.flow.dispatch/send!` routes data to interested parties. The `:seon.flow.dispatch/to` key controls targeting:

```clojure
(ns seon.trading
  (:require [seon.flow.dispatch :as dispatch]
            [seon.health.workout :as workout]))

;; Direct function call — system injects ::ctx, caller gets clean result
(workout/total-volume {})
;; => {:seon.health.workout/volume 1620.0}

;; AI-friendly summary
(workout/render-ai-summary {})
;; => {:seon.render/ai "2 past workouts"}

;; --- dispatch/send! for data routing ---

;; Broadcast — system finds ALL functions whose input spec matches these keys
(dispatch/send! {:seon.trading/market-closed true
                 :seon.trading/reason "Holiday"})

;; Targeted to one namespace — only functions in seon.health.workout considered
(dispatch/send! {:seon.flow.dispatch/to :seon.health.workout
                 :seon.trading/event :market-closed})

;; Targeted to multiple namespaces
(dispatch/send! {:seon.flow.dispatch/to #{:seon.trading :seon.health.nutrition}
                 :seon.health/event :workout-completed
                 :seon.health/volume 12500.0})

;; Targeted to specific functions
(dispatch/send! {:seon.flow.dispatch/to #{:seon.trading/on-health-update
                                     :seon.health.nutrition/on-workout-done}
                 :seon.health/workout-completed true})
```

`:seon.flow.dispatch/to` accepts:

| Value | Meaning |
|-------|---------|
| absent | Broadcast — match all functions system-wide |
| `:seon.foo` (keyword) | Match functions in that namespace |
| `:seon.foo/bar` (qualified keyword) | Call that specific function |
| `#{...}` (set) | Match functions in any of the targets (mix of ns and fn refs) |

### 6. Subscribe to Data

Any public function whose input spec matches incoming data is a subscriber ([[concepts/subscriptions]]). The system calls it when matching data arrives via `dispatch/send!`.

```clojure
;; In seon.health.workout — called when trading data flows through the system
(defn on-market-closed
  "Track market closures to suggest workout times."
  {:malli/schema [:=> [:cat [:map [:seon.trading/market-closed :boolean]
                                  [:seon.trading/reason :string]
                                  [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]]]}
  [{:seon.trading/keys [reason] ::keys [ctx]}]
  {::ctx (update ctx ::suggested-times conj
           {::reason (str "Market closed: " reason)
            ::suggested-at (java.util.Date.)})})
```

Subscription routing is **opt-in via metadata** to prevent accidental matches:

```clojure
(defn on-market-closed
  {:malli/schema [...]
   :seon.flow.dispatch/subscribe true}  ;; required for subscription routing
  ...)
```

Without `:seon.flow.dispatch/subscribe true`, the function is only callable via direct call or targeted `send!`. This prevents a function from accidentally matching broadcast data just because its spec happens to overlap.

---

## Process Model

### Two Modes, One Interface

```
                    ┌─────────────────────────────────┐
                    │      seon.flow.dispatch           │
                    │   (specificity resolution,       │
                    │    ctx injection/application)    │
                    └──────────┬──────────────────────┘
                               │
              ┌────────────────┴────────────────┐
              ▼                                 ▼
     ┌─────────────────┐              ┌──────────────────┐
     │   In-Process    │              │  Separate JVM    │
     │                 │              │                  │
     │ requiring-resolve              │ TCP/Nippy bridge │
     │ → call function │              │ → proxy function │
     │ → return result │              │ → return result  │
     └─────────────────┘              └──────────────────┘
```

Both paths go through the same dispatch layer. The dispatch layer handles:

1. **Resolve** the function (specificity matching or direct reference)
2. **Inject** `::ctx` if the function's input spec requires it
3. **Call** the function (in-process or via TCP/Nippy)
4. **Apply** `::ctx` if the function's output spec contains it
5. **Route** non-ctx return keys to renderers or subscribers

### When to Use Each

| Mode | Use Case | How |
|------|----------|-----|
| In-process | Core system, stable namespaces, performance-critical | Default for `src/seon/` namespaces |
| Separate JVM | New development, agent-written code, untrusted code | Configured per namespace in runtime registry |

The [[components/runtime]] registry (`seon.runtime`) tracks each namespace's location:

```clojure
{:seon.runtime/namespace "seon.health.workout"
 :seon.runtime/location  :in-process}    ;; or :external

{:seon.runtime/namespace "seon.trading.strategy"
 :seon.runtime/location  :external
 :seon.runtime/jvm-id    "jvm-001"}
```

### Agent Transparency

An agent in a separate JVM sees proxy namespaces ([[components/harness]], [[research/inter-jvm-calls]]). When it calls `(workout/total-volume {})`, the proxy:

1. Serializes the call via Nippy ([[architecture/decisions/001-nippy-serialization]])
2. Sends over TCP to the core process
3. Core process dispatch layer handles ctx injection + function call
4. Result serializes back via Nippy
5. Agent receives the result

The agent never imports flow, dispatch, or wire protocol code. It just calls functions.

---

## System Architecture

### Dispatch Layer

The dispatch layer is the routing backbone. Every function call — whether from browser POST, REPL eval, agent call, or `dispatch/send!` — routes through it.

```
Input arrives (HTTP POST, REPL form, send!, proxy call)
  │
  ▼
Parse input (form data → map, coerce types via Malli string transformer)
  │
  ▼
Resolve function (direct reference or specificity matching via graph)
  │
  ▼
Inject ::ctx (if function's input spec contains ::ctx, merge from ctx atom)
  │
  ▼
Call function (in-process: requiring-resolve; external: TCP/Nippy)
  │
  ▼
Apply ::ctx (if function's output spec contains ::ctx, swap! atom)
  │
  ▼
Strip ::ctx from result (callers never see it)
  │
  ▼
Route result keys:
  - Browser connection → find renderer for result keys → SSE push HTML
  - REPL connection → return raw data
  - AI connection → find AI renderer → return formatted text
  - Subscribers → find functions matching result keys → call them
```

### Specificity Resolution (Generalized)

Currently proven in [[components/renderer|render.clj]] for finding renderers ([[concepts/renderer-discovery]]). Generalize to ALL function routing:

```clojure
(defn resolve-function
  "Find the best function matching available-keys that produces target-output-key.
   Returns qualified function name or nil."
  {:malli/schema [:=> [:cat [:map [::db-name :keyword]
                                  [::available-keys [:set :keyword]]
                                  [::output-key :keyword]
                                  [::target-ns {:optional true} :string]]]
                      [:map [::qualified-name {:optional true} :string]]]}
  ...)
```

Resolution algorithm (unchanged from `render.clj`):

1. Query `functions-with-output-key` from Datalevin graph
2. Filter to functions whose required input keys are a subset of available-keys
3. Rank by: most required keys matched > namespace proximity > alphabetical
4. Return the best match

**New:** Add `functions-matching-input` query to [[components/code-graph]] (the inverse — given data keys, find functions whose input spec overlaps). Used by `dispatch/send!` for broadcast routing.

### Ctx Injection and Application

The ctx injection layer ([[components/context]]) reads a function's Malli schema to determine behavior:

| Input spec has `::ctx`? | Output spec has `::ctx`? | Behavior |
|-------------------------|--------------------------|----------|
| No | No | Pure function, no state interaction |
| Yes | No | Read-only state access |
| No | Yes | Initializer (creates initial state) |
| Yes | Yes | Stateful mutation |

**In the flow-first model**, ctx injection and application happen inside the namespace flow step, not via atom reads/writes:

```clojure
;; Inside the namespace step's :call handler:
(let [ctx    (::ctx state)                              ;; from step state, not atom
      args   (assoc (:args msg) ::ctx ctx)              ;; inject
      result (f args)                                    ;; call
      new-ctx (or (::ctx result) ctx)                   ;; extract
      state' (assoc state ::ctx new-ctx)]               ;; apply to step state
  [state'
   (cond-> {:reply [...]}
     (not= new-ctx ctx)
     (assoc :render [{:ctx new-ctx}]                    ;; SSE push via flow
            :persist [{:ctx new-ctx}]))])                ;; always emit, sliding-buffer debounces
```

The atom is updated as a side-effect (for fast in-process reads) but is not the source of truth -- the step state is. See [[research/ctx-flow-sync]] for the full flow-first architecture and REPL prototype results.

**Atom role in flow-first:**

| Concern | Old model | Flow-first model |
|---------|-----------|-----------------|
| Source of truth | Atom | Flow step state |
| Reads | `@ctx-atom` | `@ctx-atom` (read cache, kept in sync) |
| Writes | `swap!` atom directly | Step updates state, side-effect updates atom |
| SSE push | Atom watch `::sse-push` | Flow `:render` output via out-port mult |
| Persistence | Atom watch `::persist` (debounced) | Flow `:persist` output, `sliding-buffer 1` debounces via writer backpressure |
| REPL changes | `swap!` triggers watches | `swap!` triggers `::flow-sync` watch -> flow inject |

### Rendering Pipeline

The rendering fallback chain, triggered after every ctx change (function call or external update):

1. Result already has `:seon.render/html` -> use it directly
2. Result keys match a render function's input spec -> call it via specificity resolution
3. No renderer found -> generic data display (pretty-print for REPL, data viewer for browser)

**Flow-first rendering path:**

```
namespace step -> :render out-port -> async/mult -> per-connection channels -> SSE push
```

The namespace step emits `:render` events to an out-port channel whenever ctx changes. An `async/mult` fans the event to all connected browser tabs. Each connection's rendering loop calls `render-and-push!` (render function -> hiccup -> transform -> HTML -> SSE event).

This replaces the current dual-watch approach (`::sse-push` broadcast + `::client-push` targeted) with a single flow-based path. Both function calls and external `swap!` changes converge at the step's `:render` output.

**Hiccup tree walking** (Reagent-style composition):

The system post-walks the hiccup tree looking for `[fn-ref {args-map}]` vectors:

- `fn-ref` is a function var or symbol -- resolved via `requiring-resolve`
- `{args-map}` is the props map passed to the sub-renderer
- System calls the function, gets `{:seon.render/html hiccup}`, splices the hiccup
- Sub-renderers get `::ctx` auto-injected if their spec requires it
- Recursive -- sub-renderers can contain their own `[fn {args}]` references

After hiccup resolution, the existing `transform.clj` converts agent-friendly attributes to Datastar directives:

| Agent writes | Transform produces |
|---|---|
| `{:on:click :start-workout!}` | `{:data-on-click "@post('/ns/seon.health.workout/start-workout!')"}` |
| `{:on:click :total-volume}` | `{:data-on-click "@post('/ns/seon.health.workout/total-volume')"}` |
| `{:on:submit:form :log-set!}` | `{:data-on-submit "@post('/ns/.../log-set!', {contentType:'form'})"}` |
| `{:field ::exercise-name}` | `{:name ":seon.health.workout/exercise-name"}` |

All calls use POST -- simpler, supports request bodies, and keeps SSE alive on hidden tabs.

### Connection Model

Every external connection is described by the same shape:

```clojure
(schema/register! :seon.conn/origin [:enum :browser :repl :agent :timer])
(schema/register! :seon.conn/buffer [:enum :dropping :sliding :blocking])

(schema/register! :seon.conn/connection
  [:map
   [:seon.conn/id :string]
   [:seon.conn/origin :seon.conn/origin]
   [:seon.conn/namespace :string]
   [:seon.conn/instance-id {:optional true} :string]
   [:seon.conn/buffer :seon.conn/buffer]
   [:seon.conn/buffer-size :int]])
```

| Origin | Buffer | Why |
|--------|--------|-----|
| Browser | `:sliding 1` | Latest state only, Datastar patches DOM efficiently |
| REPL | `:blocking 64` | Developer wants every result |
| Agent | `:sliding 16` | Recent messages, drop old if behind |
| Timer | (source-only) | No connection -- just injects data |

**Implementation: Out-port channels with `async/mult`**

Connections are NOT flow processes. core.async.flow does not support dynamic process addition, but browser tabs connect and disconnect at runtime. Instead, the namespace step writes render events to an out-port channel, and an `async/mult` distributes to per-connection channel taps.

```
namespace step -> :render out-port (channel) -> async/mult
                                                  |
                                         tap ─────┼───── tap ─────── tap
                                          |        |        |
                                     [browser-1] [browser-2] [REPL]
                                     sliding(1)  sliding(1)  blocking(64)
```

**Dynamic lifecycle:**

- Browser connects (SSE request) -> `async/tap` on render mult -> per-connection channel with `sliding-buffer 1`
- Browser disconnects -> `async/untap` -> channel closed
- Tab reconnects -> new tap on same mult, fresh channel
- No flow topology rebuild needed at any point

This was validated in the REPL prototype -- dynamically adding connections via `async/tap` immediately started receiving render events. See [[research/ctx-flow-sync]] for test results.

### Data Subscriptions via `dispatch/send!`

When `dispatch/send!` is called:

1. Strip `:seon.flow.dispatch/to` from the data map
2. If `:seon.flow.dispatch/to` is present, filter candidate functions to those targets
3. If `:seon.flow.dispatch/to` is absent (broadcast), consider ALL functions with `:seon.flow.dispatch/subscribe true` metadata
4. For each candidate, check if the data keys satisfy its required input keys
5. Rank by specificity (most required keys matched wins)
6. Call matching functions, injecting `::ctx` as needed

**Loop detection:** Each propagation carries a visited set. If a function's output triggers another function that's already been visited, skip it. Depth limit of 8 as a safety net.

**Rendering is NOT a subscription.** Rendering is triggered by the dispatch layer after every browser-context function call. It always runs. Subscriptions are opt-in via `:seon.flow.dispatch/subscribe true`.

---

## Namespace Hierarchy

`seon.flow` stays as the umbrella for the async routing backbone. Only `harness` renames — everything else stays put or is new/additive. See "Namespace Changes" section below for full details.

### Key Structural Decisions

- **`seon.flow.*`** holds all flow infrastructure: topology, dispatch, process management, wire protocol, tracing
- **`seon.flow.harness` → `seon.flow.process`** — frees "harness" for `seon.agent.harness` (AI agent lifecycle)
- **`seon.flow.dispatch`** — NEW namespace for specificity resolution, ctx injection, `send!`
- **`seon.ctx`** stays separate but simplified -- becomes a read cache + registry + `::flow-sync` watch. All side-effects (SSE, persistence) removed in favor of flow outputs. See [[research/ctx-flow-sync]] "Revised: Flow-First Architecture"
- **`seon.db`**, **`seon.render`**, **`seon.graph`** — unchanged, well-named
- **`seon.flow.msg`**, **`seon.flow.trace`**, **`seon.flow.status`**, **`seon.flow.topology`** — unchanged, well-named

---

## Decisions

### Settled

1. **Map-in/map-out everywhere** ([[conventions#Map In, Map Out]]). Every public function takes one map, returns one map. All keys namespaced. No exceptions.

2. **`::ctx` injection is spec-driven.** If `::ctx` is in the input spec, inject. If in output spec, apply. No registration, no naming convention.

3. **Initializer detection by spec.** Input lacks `::ctx` + output has `::ctx` = initializer. Takes a map arg (not zero-arg) — aligns with core.async.flow's init arity which always receives a map, and with our map-in/map-out convention. System calls `(initial-ctx {})` at startup, merges persisted state over the result. See `research/flow-init-pattern.md`.

4. **Specificity resolution is general.** Same algorithm for renderers, function routing, and subscription matching. Lives in `seon.flow.dispatch.resolve`.

5. **`:seon.render/hiccup` is a recursive Malli schema**, not a `:any` alias. Three branches: primitives (string/int/double/boolean/nil), nodes (`[:tag {attrs} & children]`), and fragments (sequences from `map`/`for`). Sub-millisecond validation for realistic pages. HTML attr maps use `[:map-of :keyword :any]` (acceptable — HTML attrs are genuinely polymorphic). See `research/hiccup-schema.md`.

6. **Subscriptions are opt-in.** Functions need `:seon.flow.dispatch/subscribe true` in var metadata to be eligible for broadcast routing. Prevents accidental matches.

7. **Process transparency via dispatch layer** ([[research/inter-jvm-calls]]). Whether in-process or separate JVM, the dispatch layer handles ctx injection, function resolution, and result routing identically.

8. **`dispatch/send!` targeting.** `:seon.flow.dispatch/to` accepts keyword (namespace), qualified keyword (function), set of either, or absent (broadcast).

9. **Rendering is not subscription.** Rendering always runs for browser connections. Subscriptions are a separate, opt-in mechanism.

10. **Hiccup tree walking for composition.** `[fn-ref {args}]` vectors in hiccup are resolved recursively, Reagent-style.

### Open Questions

**A. Per-namespace flow process granularity**

Each namespace needs its own flow process for isolation ([[concepts/namespace-as-process]]). But core.async.flow doesn't support dynamic process addition ([[research/flow-init-pattern]]).

Options:
- Pre-allocate all namespace processes at startup (rebuild topology on namespace add/remove)
- Use a pool of generic worker processes, dispatch to any available
- One flow per namespace, but only for namespaces with `::ctx` (dynamic namespaces)

Need to measure: what's the cost of N flow processes? Start with one-per-dynamic-namespace.

**B. `::ctx` atom bidirectional sync** -- DECIDED, see [[research/ctx-flow-sync]]

The atom is read/write -- agents can `swap!` it from the REPL ([[orchestrator/issues/atom-watches-bypass-flow]]). Sync mechanism researched, prototyped, and revised to flow-first.

**Decision:** Flow-first architecture with sentinel-guarded bidirectional sync. ALL effects (SSE push, persistence, rendering) go through flow -- no atom watches for side-effects. The atom is a read cache only. One remaining watch: `::flow-sync` injects `:ctx-updated` into flow when external code changes the atom.

**Key findings from REPL prototyping:**

- Sentinel approach proven convergent under concurrent stress (5/5 trials, 40 concurrent ops)
- Idempotent loop prevention (equality check) diverges under load -- rejected
- Flow-based persistence via `sliding-buffer 1`: step emits `:persist` with full ctx state on every change, per-namespace channel to writer uses `sliding-buffer 1`. Writer backpressure naturally debounces -- 50 rapid calls with 20ms writer produced only 2 persists, final state always persisted.
- Connections via out-ports + `async/mult`: dynamic tap/untap for browser connections without flow topology rebuilds
- See [[research/ctx-flow-sync]] "Revised: Flow-First Architecture" for full analysis and REPL session results

**C. Streaming / partial results**

Functions like LLM calls need to emit partial results. Options:
- Return a channel or lazy-seq that the system drains
- `seon/yield` dynamic var bound during flow execution

Defer until a concrete use case drives the design.

**D. Queue behavior for subscriptions**

Functions can specify how they receive subscription data:

```clojure
{:seon.flow.dispatch/subscribe true
 :seon.flow.dispatch/queue :sliding        ;; :immediate (default), :sliding, :dropping, :batch
 :seon.flow.dispatch/queue-size 10         ;; for :sliding/:dropping
 :seon.flow.dispatch/batch-interval-ms 60000}  ;; for :batch — flush every 60s
```

This is metadata on the var, not part of the Malli schema. All queue keywords live in `seon.flow.dispatch` — the code that processes them.

---

## Custom Flow Functions

Most namespaces use the **default namespace step function** that handles ctx injection, function calls, render output, and persistence. But some namespaces need custom flow behavior.

### The Default Namespace Step Function

The default step handles the standard lifecycle:

```
Inputs:
  :call        - Function call request {::fn, ::args, ::id}
  :ctx-updated - External ctx change (from atom swap! via ::flow-sync watch)

Processing (for :call):
  1. Read ::ctx from step state
  2. Inject ::ctx into args (if function spec requires it)
  3. Call function
  4. Extract ::ctx from result (if function spec returns it)
  5. Update step state with new ::ctx

Outputs:
  :reply   - Reply envelope for reply-router (request/reply callers)
  :render  - Render event to out-port (async/mult -> per-connection SSE)
  :persist - Persist event with full ctx state on every ctx change
             (sliding-buffer 1 on channel to writer debounces via backpressure)

Side-effect:
  Reset! atom with new ctx (sentinel-guarded to prevent watch loop)
```

### Custom Namespace Step Functions

Some namespaces define their own flow step function instead of using the default. Examples:

- **`seon.db.datalevin.writer`** -- handles DB writes with connection pooling, retry logic, timeout. Already exists as `infra-writer-step`.
- **`seon.db.datalevin.reader`** -- handles DB reads with connection pooling. Already exists as `infra-reader-step`.
- Future: external API pollers, WebSocket managers, scheduled data importers.

### Detection Mechanism

The system detects custom flow functions via var metadata:

```clojure
;; In seon.db.datalevin.writer:
(defn infra-writer-step
  "Custom flow step for DB writes."
  {:seon.flow/step true}  ;; <-- detection marker
  ;; 4-arity step-fn protocol
  ([] {:ins {...} :outs {...} :workload :io})
  ([args] ...)
  ([state transition] ...)
  ([state input-id msg] ...))
```

The topology builder scans namespace vars for `:seon.flow/step` metadata. If found, it uses the custom step instead of the default namespace step. The custom step is responsible for its own I/O contract (inputs, outputs, state management).

**Convention:** The custom step var should be named descriptively (not just `flow-step`), and the `:seon.flow/step` metadata is the detection mechanism. This is consistent with how `:malli/schema` detects instrumentable functions and `:seon.render/html` detects renderer functions.

### How Writer/Reader Demonstrate the Pattern

The infrastructure writer (`seon.db.datalevin.writer/infra-writer-step`) already IS a custom flow step function. It:

- Defines its own inputs/outputs (`:seon.flow.in/request` -> `:seon.flow.out/reply` + `:seon.flow.out/error`)
- Manages its own state (connection pool, write counts, owned connections)
- Handles its own lifecycle (close connections on stop, log stats on pause)
- Uses the standard msg envelope format for integration with the reply-router

This is the proof that the system is fully decoupled: the writer defines its behavior in its own namespace, and the topology builder detects and wires it in. Namespace authors can do the same for any custom processing that doesn't fit the default ctx-injection-and-function-call pattern.

---

## Parallel Systems to Merge

These are current systems that overlap with the unified design. Each should be absorbed or replaced. This PRD resolves many open issues:

- [[orchestrator/issues/state-three-mechanisms]] — ctx, runtime, flow/ping unified under dispatch
- [[orchestrator/issues/coupling-ns-routes-reactive]] — split into dispatch.http + ns.introspect.view
- [[orchestrator/issues/overlap-three-rendering]] — one specificity algorithm in dispatch.resolve
- [[orchestrator/issues/overlap-three-sse-push]] — ctx SSE push driven by dispatch layer
- [[orchestrator/issues/no-custom-namespace-behavior]] — custom flow step functions for advanced cases
- [[orchestrator/issues/no-unified-namespace-model]] — this PRD IS the unified model
- [[orchestrator/issues/no-broadcast-signals]] — `dispatch/send!` with targeting
- [[orchestrator/issues/no-live-subscriptions]] — `:seon.flow.dispatch/subscribe` metadata
- [[orchestrator/issues/any-in-render-html]] — `:seon.render/hiccup` recursive schema
- [[orchestrator/issues/atom-watches-bypass-flow]] — ctx atom syncs via flow (Phase 6)

### 1. Manual ctx injection in `ns/routes.clj`

**Current:** `resolve-and-call` in [[components/namespace-lifecycle|ns/routes.clj]] manually looks up `*ctx*` and `*conn*` dynamic vars by name, injects their values into the function call input.

**Replace with:** Spec-driven injection via [[components/context]]. Read the function's `:malli/schema`, detect `::ctx` in input keys, inject from ctx atom. No var name conventions needed.

### 2. Renderer-specific resolution in `render.clj`

**Current:** `resolve-renderer` in [[components/renderer]] hardcodes `:seon.render/html` as the target output key. The specificity algorithm is coupled to rendering ([[orchestrator/issues/coupling-graph-render]]).

**Replace with:** Extract specificity algorithm to `seon.flow.dispatch.resolve`. `render.clj` becomes a thin wrapper: `(dispatch.resolve/resolve-function {...::output-key :seon.render/html})`.

### 3. Separate rendering and dispatch paths

**Current:** HTTP POSTs go through `ns/routes.clj` function-call-handler (dispatch). Page loads go through `namespace-page` (rendering). These are separate code paths with duplicated ctx injection logic.

**Replace with:** Both route through the dispatch layer. HTTP POST → dispatch → call function → route result to renderer. Page load → dispatch → call render-page → return HTML.

### 4. `*ctx*` and `*conn*` dynamic vars

**Current:** Dynamic namespaces create `*ctx*` and `*conn*` vars that hold atoms. These are looked up by name in `ns/routes.clj`.

**Replace with:** The ctx atom is managed by `seon.ctx` (already is). The dispatch layer injects `::ctx` value from the atom based on the function's spec. No dynamic vars needed — the dispatch layer knows which namespace's ctx to inject.

### 5. `send-message!` pattern in example namespaces

**Current (in this doc's old version):** Each namespace had a `send-message!` function that appended to a `::messages` vector in ctx.

**Replace with:** `dispatch/send!` with targeting. No per-namespace inbox needed. Functions subscribe to the data shapes they care about.

---

## Namespace Changes

### Renames (harness → process)

Frees "harness" for AI agent lifecycle. Minimal blast radius (~15 files).

| Current | Target | Lines |
|---|---|---|
| `seon.flow.harness` | `seon.flow.process` | 269 |
| `seon.flow.harness.bridge` | `seon.flow.process.bridge` | 255 |
| `seon.flow.harness.channel` | `seon.flow.process.channel` | 158 |
| `seon.flow.harness.proxy` | `seon.flow.process.proxy` | 109 |
| `seon.flow.agent_runner` | `seon.flow.process.runner` | 108 |

Test files move in parallel:

| Current | Target | Lines |
|---|---|---|
| `test/seon/flow/harness_test.clj` | `test/seon/flow/process_test.clj` | 266 |
| `test/seon/flow/harness/bridge_test.clj` | `test/seon/flow/process/bridge_test.clj` | 139 |
| `test/seon/flow/harness/channel_test.clj` | `test/seon/flow/process/channel_test.clj` | 120 |

Import updates needed in ~7 files: `topology.clj`, `system.clj`, `session.clj`, and 4 integration tests.

### Moves (consolidate under the right parent)

| Current | Target | Rationale |
|---|---|---|
| `seon.flow.pool` | `seon.flow.process.pool` | Pool manages JVM processes, belongs under process |
| `seon.web.reactive.transform` | `seon.render.transform` | Hiccup transform is a render concern |
| `seon.web.reactive.actions` | `seon.flow.dispatch.actions` | Action resolution is dispatch |

Test files:

| Current | Target |
|---|---|
| `test/seon/flow/pool_test.clj` | `test/seon/flow/process/pool_test.clj` |
| `test/seon/flow/pool_integration_test.clj` | `test/seon/flow/process/pool_integration_test.clj` |

### New Files

| File | Purpose | Phase |
|---|---|---|
| `src/seon/flow/dispatch.clj` | Specificity resolution, ctx injection, `send!` | 1-4 |
| `src/seon/flow/dispatch/resolve.clj` | Generic specificity algorithm (extracted from render.clj) | 1 |
| `src/seon/flow/dispatch/http.clj` | HTTP POST/GET → dispatch (extracted from ns/routes.clj) | 2 |
| `src/seon/flow/dispatch/actions.clj` | Signal action resolution (moved from web/reactive/) | 2 |
| `src/seon/render/transform.clj` | Hiccup → Datastar (moved from web/reactive/) | 3 |
| `src/seon/agent/harness.clj` | LLM tool routing, agent lifecycle | future |
| `test/seon/flow/dispatch_test.clj` | Dispatch layer tests | 1-4 |
| `test/seon/flow/dispatch/resolve_test.clj` | Resolution algorithm tests | 1 |

### Unchanged (stay where they are)

| Namespace | Why |
|---|---|
| `seon.flow.topology` | Core flow wiring, well-named |
| `seon.flow.msg` | Wire protocol, well-named |
| `seon.flow.trace` | Observability, well-named |
| `seon.flow.status` | Health checks, well-named |
| `seon.ctx` | State management, separate concern from flow |
| `seon.runtime` | Namespace registry, well-named |
| `seon.render` | Rendering (thinner after resolution extraction) |
| `seon.db` | Database access, well-named |
| `seon.schema` | Type definitions, well-named |
| `seon.graph.*` | Code analysis, well-named |
| `seon.ns.lifecycle` | Instance lifecycle, well-named |
| `seon.ns.introspect` | Namespace enumeration, well-named |
| `seon.agent` | AI provider registry, well-named |
| `seon.agent.env` | Agent environment, well-named |

### Files Modified (no move, code extracted out)

| File | What Changes | Lines |
|---|---|---|
| `src/seon/render.clj` | Specificity algorithm → `dispatch/resolve.clj`, keeps rendering | 798 → ~650 |
| `src/seon/ns/routes.clj` | Dispatch logic → `dispatch/http.clj`, keeps introspection view | 1002 → ~500 |
| `src/seon/ctx.clj` | Add `::flow-sync` watch, remove `::sse-push`/`::client-push` watches | 855 |
| `src/seon/graph/query.clj` | Add `functions-matching-input` query | extend |
| `src/seon/graph/scanner.clj` | Add `:seon.flow.dispatch/subscribe` metadata extraction | extend |

### Full Namespace Hierarchy (post-implementation)

```
seon.schema                         — type definitions (register!)
seon.db                             — database access (transact!, query)
seon.db.schema                      — Malli → Datalevin bridge
seon.db.tx                          — transaction metadata
seon.db.datalevin.conn              — connection manager
seon.db.datalevin.server            — external Datalevin JVM
seon.db.datalevin.reader            — query execution
seon.db.datalevin.writer            — transact execution

seon.ctx                            — namespace state atoms, persistence
seon.runtime                        — namespace registry (what's running, where)

seon.flow.topology                  — request!, reply-router, topology builder
seon.flow.msg                       — wire protocol envelopes
seon.flow.trace                     — flow event persistence
seon.flow.status                    — health and observability
seon.flow.dispatch                  — NEW: specificity resolution, ctx injection, send!
seon.flow.dispatch.resolve          — NEW: generic specificity algorithm
seon.flow.dispatch.http             — NEW: HTTP POST/GET → dispatch layer
seon.flow.dispatch.actions          — MOVED: signal action resolution
seon.flow.process                   — RENAMED: per-namespace JVM management
seon.flow.process.bridge            — RENAMED: agent-side function execution
seon.flow.process.channel           — RENAMED: TCP/Nippy wire
seon.flow.process.proxy             — RENAMED: transparent remote proxies
seon.flow.process.pool              — MOVED: warm JVM pool
seon.flow.process.runner            — RENAMED: agent runner

seon.render                         — rendering (HTML, AI, raw)
seon.render.transform               — MOVED: hiccup → Datastar

seon.graph.extract                  — unified graph extraction
seon.graph.ingest                   — persist entities to Datalevin
seon.graph.query                    — Datalog queries on knowledge graph
seon.graph.scanner                  — clj-kondo analysis helpers

seon.ns.lifecycle                   — instance creation, validation
seon.ns.introspect                  — namespace var/fn enumeration
seon.ns.routes                      — THINNED: introspection views only

seon.agent                          — AI provider registry
seon.agent.harness                  — NEW (future): LLM tool routing
seon.agent.env                      — agent environment setup

seon.web.server                     — HTTP server (http-kit)
seon.web.routes                     — route dispatch
seon.web.sse                        — SSE infrastructure
seon.web.components                 — reusable UI components
seon.web.html                       — HTML generation utilities
```

### Keyword Namespace Changes

All keyword namespaces change to match their new code namespaces:

| Current | New | Used In |
|---|---|---|
| `:seon.flow.harness/*` | `:seon.flow.process/*` | process config, step state |
| `:seon.flow.pool/*` | `:seon.flow.process.pool/*` | pool config |

No keyword changes needed for namespaces that stay put (`msg`, `trace`, `status`, `topology`).

New keywords (all match their code namespace):

| Keyword | Code Namespace | Purpose |
|---|---|---|
| `:seon.flow.dispatch/to` | `seon.flow.dispatch` | send! targeting |
| `:seon.flow.dispatch/subscribe` | `seon.flow.dispatch` | subscription opt-in |
| `:seon.flow.dispatch/queue` | `seon.flow.dispatch` | queue behavior |
| `:seon.flow.dispatch/queue-size` | `seon.flow.dispatch` | queue capacity |
| `:seon.flow.dispatch/batch-interval-ms` | `seon.flow.dispatch` | batch flush interval |

### Blast Radius Summary

| Category | Files | LOC |
|---|---|---|
| Renamed (harness → process) | 5 src + 3 test | 1,316 |
| Moved (pool, transform, actions) | 3 src + 2 test | ~1,300 |
| New files | 5 src + 2 test | ~800 (estimated) |
| Modified (extraction) | 5 src | ~2,600 (net reduction) |
| Import-only updates | ~10 | — |
| **Total files touched** | **~35** | |

---

## Implementation Plan

### Phase 1: Generalize Resolution

Extract specificity algorithm from [[components/renderer|render.clj]] into `seon.flow.dispatch.resolve`. Builds on [[prds/spec-driven-rendering/prd|spec-driven rendering PRD]].

- Create `seon.flow.dispatch.resolve` with `resolve-function`
- Add `functions-matching-input` query to [[components/code-graph|graph/query.clj]]
- Make `render.clj` delegate to `dispatch.resolve` for renderer discovery
- Existing tests pass, rendering behavior unchanged

**New:** `src/seon/flow/dispatch/resolve.clj`, `test/seon/flow/dispatch/resolve_test.clj`
**Modified:** `src/seon/graph/query.clj`, `src/seon/render.clj`

### Phase 2: Spec-Driven Ctx Injection

Replace manual var-lookup injection with spec-driven injection.

- Create `seon.flow.dispatch` with `call-with-ctx`
- Wire into `ns/routes.clj:resolve-and-call` as replacement for `inject-ctx-conn`
- Extract dispatch half of `ns/routes.clj` → `seon.flow.dispatch.http`
- Remove `*ctx*` and `*conn*` dynamic var conventions
- Test with existing dynamic namespaces (workout, getting_started)

**New:** `src/seon/flow/dispatch.clj`, `src/seon/flow/dispatch/http.clj`
**Modified:** `src/seon/ns/routes.clj` (thinned), `src/seon/ctx.clj`

### Phase 3: Hiccup Tree Walker + Render Moves

Add Reagent-style `[fn {args}]` resolution to the rendering pipeline.

- Walk hiccup tree post-order, resolve `[fn-ref {args}]` vectors
- Move `web/reactive/transform.clj` → `render/transform.clj`
- Move `web/reactive/actions.clj` → `flow/dispatch/actions.clj`
- Layer: hiccup resolution → transform → HTML string

**New:** hiccup walker in `src/seon/render.clj`
**Moved:** `src/seon/render/transform.clj`, `src/seon/flow/dispatch/actions.clj`

### Phase 4: `dispatch/send!`

Implement data routing with targeting.

- `send!` in `seon.flow.dispatch`
- Broadcast: query `functions-matching-input` filtered by `:seon.flow.dispatch/subscribe`
- Targeted: filter to specified namespaces/functions
- Call matching functions via dispatch layer (ctx injection applies)
- Loop detection via visited set + depth limit 8

**Modified:** `src/seon/flow/dispatch.clj`, `src/seon/graph/scanner.clj`

### Phase 5: Namespace Renames (harness → process)

Mechanical rename, one focused commit, no other work in progress.

- `seon.flow.harness*` → `seon.flow.process*` (5 src + 3 test files)
- `seon.flow.pool` → `seon.flow.process.pool` (1 src + 2 test files)
- `seon.flow.agent_runner` → `seon.flow.process.runner` (1 src file)
- Update imports in ~10 files
- Update keyword namespaces: `:seon.flow.harness/*` → `:seon.flow.process/*`

### Phase 6: Flow-First Ctx Integration

Wire ctx atoms to flow processes with ALL effects through flow. See [[research/ctx-flow-sync]] "Revised: Flow-First Architecture" for full design and REPL prototype results.

**Phase 6a: Bidirectional sync (additive)**

- Add `::flow-sync` watch to `ctx/create!` (sentinel-guarded, injects `:ctx-updated` into flow)
- Add sentinel (`::flow-writing?`) to ctx registry entries
- Each dynamic namespace gets a flow process with `:call`, `:ctx-updated` inputs
- Flow step maintains `::ctx` in step state (source of truth), updates atom as side-effect
- All existing watches still active (additive only -- no behavior change)

**Phase 6b: Render via flow**

- Add `:render` out-port to namespace step (external channel + `async/mult`)
- Wire connection manager: `async/tap` on browser connect, `async/untap` on disconnect
- Per-connection rendering loop reads from tap channel, calls `render-and-push!`
- Verify SSE push works through flow path
- Remove `::sse-push` and `::client-push` watches from `ctx.clj`

**Phase 6c: Persist via flow**

- Add `:persist` output to namespace step -- step emits full ctx state on every ctx change
- Configure `sliding-buffer 1` on the channel from each namespace step's `:persist` output to the writer step
- Each namespace has its own channel -- no cross-namespace interference
- Writer receives latest state when ready (natural backpressure debouncing)
- Wire `:persist` output to infrastructure writer step (existing `infra-writer-step`)
- Verify persistence works through flow path
- Remove `::persist` watch from `ctx.clj`

Note: Per-namespace `sliding-buffer 1` channels were validated in REPL prototyping. Previous analysis found data loss with shared buffers between namespaces -- our architecture avoids this by using separate channels per namespace. See [[research/ctx-flow-sync]] "Flow Channel/Buffer Options for Debouncing" for full analysis.

**Phase 6d: Cleanup**

- Verify atom has only `::flow-sync` watch remaining
- Remove dead code from `ctx.clj`: `do-persist!`, `sse-push!`, `push-to-client!`, `render-and-push!`, `make-client-watch`, client tracking, scheduler/scheduled-task in registry
- Update `ctx/create!` to not add persist/SSE watches
- Update `ctx/destroy!` to clean up `::flow-sync` watch only
- Simplify registry entry shape (remove `::scheduler`, `::scheduled-task`, `::clients`)

**New:** `src/seon/flow/dispatch/step.clj` (default namespace step function)
**Modified:** `src/seon/ctx.clj` (simplified), `src/seon/flow/topology.clj` (namespace flow wiring)

---

## Reference Example: Complete Namespace

This shows the full agent experience — a namespace author writes only domain logic. The system provides everything else. UI follows the [[prds/namespace-ui/design-system|Phosphor Terminal design system]].

```clojure
(ns seon.health.workout
  (:require [seon.schema :as schema]))

;; === Domain types ===
(schema/register! ::exercise-name :string)
(schema/register! ::weight :double)
(schema/register! ::reps :int)
(schema/register! ::set [:map [::weight ::weight] [::reps ::reps]])
(schema/register! ::exercise [:map
                              [::exercise-name ::exercise-name]
                              [::sets [:vector ::set]]])
(schema/register! ::workout [:map
                             [::exercises [:vector ::exercise]]
                             [::started-at {:optional true} :inst]])
(schema/register! ::volume :double)

;; === Namespace state ===
(schema/register! ::ctx
  [:map
   [::screen [:enum :home :active :history]]
   [::current-workout {:optional true} ::workout]
   [::history [:vector ::workout]]])

;; === Initializer (no ::ctx in input, ::ctx in output) ===
(defn initial-ctx
  {:malli/schema [:=> [:cat :map] ::ctx]}
  [_]
  {::screen  :home
   ::history []})

;; === Mutations ===
(defn start-workout!
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]
                            [::workout ::workout]]]}
  [{::keys [ctx]}]
  (let [w {::exercises [] ::started-at (java.util.Date.)}]
    {::ctx     (assoc ctx ::screen :active ::current-workout w)
     ::workout w}))

(defn log-set!
  {:malli/schema [:=> [:cat [:map [::exercise-name ::exercise-name]
                                  [::set ::set]
                                  [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]
                            [::workout ::workout]]]}
  [{::keys [exercise-name set ctx]}]
  (let [exercises (::exercises (::current-workout ctx))
        updated   (add-set-to-exercise exercises exercise-name set)
        w         (assoc (::current-workout ctx) ::exercises updated)]
    {::ctx     (assoc ctx ::current-workout w)
     ::workout w}))

(defn finish-workout!
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]]]}
  [{::keys [ctx]}]
  {::ctx (-> ctx
             (update ::history conj (::current-workout ctx))
             (dissoc ::current-workout)
             (assoc ::screen :home))})

;; === Reads ===
(defn total-volume
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [::volume ::volume]]]}
  [{::keys [ctx]}]
  {::volume (->> (::history ctx)
                 (mapcat ::exercises)
                 (mapcat ::sets)
                 (reduce (fn [acc s] (+ acc (* (::weight s) (::reps s)))) 0.0))})

;; === Subscriptions ===
(defn on-market-closed
  "Track market closures to suggest workout times."
  {:malli/schema [:=> [:cat [:map [:seon.trading/market-closed :boolean]
                                  [:seon.trading/reason :string]
                                  [::ctx ::ctx]]]
                      [:map [::ctx ::ctx]]]
   :seon.flow.dispatch/subscribe true}
  [{:seon.trading/keys [reason] ::keys [ctx]}]
  {::ctx (update ctx ::history
           (fn [h] (conj h {::exercises []
                            ::started-at (java.util.Date.)})))})

;; === Renderers ===
(defn render-exercise
  {:malli/schema [:=> [:cat [:map [::exercise ::exercise]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [exercise]}]
  {:seon.render/html
   [:div {:class "bg-base-900 p-2 rounded"}
    [:span {:class "text-amber-400 text-xs"} (::exercise-name exercise)]
    (for [s (::sets exercise)]
      [:span {:class "text-xs text-300"} (str (::weight s) "x" (::reps s))])]})

(defn render-workout
  {:malli/schema [:=> [:cat [:map [::workout ::workout]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [workout]}]
  {:seon.render/html
   [:div {:class "space-y-2"}
    (for [ex (::exercises workout)]
      [render-exercise {::exercise ex}])]})

(defn render-page
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [:seon.render/html :seon.render/hiccup]]]}
  [{::keys [ctx]}]
  {:seon.render/html
   [:main#morph {:class "p-3 bg-base-950 font-mono text-50"}
    [:h1 {:class "text-sm text-cream"} "Workout"]
    (case (::screen ctx)
      :home
      [:div
       [:button {:on:click :start-workout!
                 :class "bg-amber-600 text-xs px-3 py-1 rounded"}
        "Start Workout"]
       [:h2 {:class "text-xs text-400 mt-3"} "History"]
       (for [w (::history ctx)]
         [render-workout {::workout w}])]

      :active
      [:div
       [render-workout {::workout (::current-workout ctx)}]
       [:form {:on:submit:form :log-set!}
        [:div {:class "flex gap-2 mt-2"}
         [:input {:field ::exercise-name :placeholder "Exercise"
                  :class "bg-base-800 text-50 p-1 text-xs rounded"}]
         [:input {:field ::weight :type "number" :step "2.5"
                  :class "bg-base-800 text-50 p-1 text-xs rounded w-16"}]
         [:input {:field ::reps :type "number"
                  :class "bg-base-800 text-50 p-1 text-xs rounded w-12"}]]
        [:button {:type "submit"
                  :class "bg-amber-600 text-xs px-3 py-1 rounded mt-2"}
         "Log Set"]]
       [:button {:on:click :finish-workout!
                 :class "text-red-400 text-xs mt-2"}
        "Finish"]]

      :history
      [:div (for [w (::history ctx)]
              [render-workout {::workout w}])])]})

(defn render-ai-summary
  {:malli/schema [:=> [:cat [:map [::ctx ::ctx]]]
                      [:map [:seon.render/ai :string]]]}
  [{::keys [ctx]}]
  {:seon.render/ai
   (str (count (::history ctx)) " past workouts"
        (when-let [w (::current-workout ctx)]
          (str ", active: " (count (::exercises w)) " exercises")))})
```

### Cross-Namespace Usage

```clojure
(ns seon.trading
  (:require [seon.flow.dispatch :as dispatch]
            [seon.health.workout :as workout]))

;; Direct call — system injects workout's ::ctx, returns clean result
(workout/total-volume {})
;; => {:seon.health.workout/volume 1620.0}

;; AI summary
(workout/render-ai-summary {})
;; => {:seon.render/ai "2 past workouts"}

;; Broadcast — all subscribers with matching input specs get called
(dispatch/send! {:seon.trading/market-closed true
             :seon.trading/reason "Holiday"})
;; workout/on-market-closed is called because its input spec matches
```

### Browser Interaction Loop

```
User loads /ns/seon.health.workout
  → dispatch: call render-page, inject ::ctx
  → hiccup walker: resolve [render-workout {..}] → [render-exercise {..}]
  → transform: {:on:click :start-workout!} → {:data-on-click "@post(...)"}
  → SSE push: full HTML to browser

User clicks "Start Workout"
  → POST /ns/seon.health.workout/start-workout!
  → dispatch: inject ::ctx, call start-workout!
  → result: {::ctx updated, ::workout new-workout}
  → apply: swap! ctx atom with new ::ctx
  → strip: remove ::ctx from result, left with {::workout new-workout}
  → render: find render-workout matching #{::workout} → HTML
  → SSE push → browser morphs DOM

User fills form, clicks "Log Set"
  → POST with form data {::exercise-name "Squat" ::weight 225.0 ::reps 1}
  → dispatch: coerce types via Malli, inject ::ctx, call log-set!
  → same render chain → browser updates
```

---

## Research Documents

| Document | Key Finding |
|----------|-------------|
| `research/hiccup-schema.md` | Recursive Malli schema works, sub-ms validation, three branches |
| `research/datastar-dsl.md` | Always POST, `:on:EVENT` DSL, `:field` for form inputs, security adequate |
| `research/flow-init-pattern.md` | Flow init always takes map arg, no dynamic process addition |
| `research/inter-jvm-calls.md` | Full call trace both directions, ctx injection gap identified |
| `research/keyword-naming.md` | All `:seon/` keywords → `:seon.flow.dispatch/`, `:seon.wire/dynamic` |

---

## Design Influences

- **Actor model (Erlang/Elixir):** Isolated state, message passing, per-actor process
- **Reagent (ClojureScript):** `[component {props}]` hiccup composition
- **Malli:** Schema-driven contracts, function discovery, validation
- **core.async.flow:** Step functions, topology, lifecycle management (implementation detail)
- **Datalevin graph:** Function discovery by spec keys, ref joins
- **REPL:** Read-eval-print-loop as the universal interface pattern
