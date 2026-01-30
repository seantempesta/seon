# PRD: Reactive UI Architecture

**Status:** Research Complete, Ready for Prototyping
**Branch:** `feature/sse-live-reload`
**Date:** 2026-01-30
**Namespace:** `seon.web.reactive` (to be created)

---

## Executive Summary

Build a Reagent-like experience for server-side Clojure. Agents write pure Clojure (atoms + render functions), the framework handles reactivity, persistence, and browser updates via Datastar/SSE.

**The Goal:**
```clojure
;; Agent writes this - no framework concepts leak through
(defn add-signal! [{:keys [name price]}]
  (swap! *ctx* update :signals conj {:name name :price price}))

(defn render [_]
  [:main
   [:ul (for [s (:signals @*ctx*)] [:li (:name s)])]
   [:form {:on:submit :add-signal!}
    [:input {:field :name}]
    [:input {:field :price :type "number"}]
    [:button "Add"]]])
```

That's it. No signals, no Datastar attributes, no SSE handling.

---

## Problem Statement

Current state:
- Agents must understand Datastar (`data-on:click`, `data-bind`, signals)
- Manual SSE refresh calls
- No clean abstraction between "what I want" and "how the browser works"
- Form handling requires understanding client-side signals

Desired state:
- Agents write pure Clojure
- `*ctx*` atom is the single source of truth
- Changes to `*ctx*` automatically push to browser
- Forms "just work" - collect data, call function with map

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         AGENT CODE                               │
│  (Pure Clojure: atoms, functions, hiccup)                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │  HICCUP TRANSFORM  │
                    │  :on:click → data- │
                    │  :field → data-bind│
                    └─────────┬─────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                      FRAMEWORK LAYER                             │
│  - Action endpoint (/action/:ns/:fn)                            │
│  - Signal extraction + Malli coercion                           │
│  - *ctx* atom management + watches                              │
│  - SSE push on ctx change                                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │     DATASTAR      │
                    │  (SSE + morphing)  │
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │      BROWSER      │
                    │  (thin terminal)   │
                    └───────────────────┘
```

---

## Research Findings

### Datastar SDK Patterns

From analyzing `reference-code/datastar-clojure/`:

1. **Dual-Mode Handler** - Check `(d*/datastar-request? request)`:
   - If true → return `->sse-response` with fragments
   - If false → return full HTML page

2. **Fragment-Based Updates** - `patch-elements!` sends HTML fragments

3. **Signal Auto-Sync** - Datastar bundles all `data-bind` signals into POST body automatically

4. **Key Files:**
   - `libraries/sdk/src/main/starfederation/datastar/clojure/api.clj` - Core API
   - `libraries/sdk-http-kit/` - http-kit adapter
   - `src/dev/examples/forms/` - Form handling examples

### Hiccup Transformation Strategy

**The key abstraction.** Transform clean hiccup to Datastar-compatible HTML.

Agent writes:
```clojure
[:button {:on:click :increment} "Add"]
[:input {:field :user-name}]
```

Framework transforms via `clojure.walk/postwalk`:
```clojure
[:button {:data-on:click "@post('/action/ns/increment')"} "Add"]
[:input {:name "user-name" :data-bind:user-name true}]
```

### Signal → Clojure Data Flow

1. **Client** - User fills form, Datastar maintains signals locally
2. **Submit** - Datastar POSTs all signals as JSON: `{"symbol": "AAPL", "quantity": "100"}`
3. **Server** - Extract via `get-signals` or Ring params
4. **Coerce** - Malli decodes string "100" → integer 100
5. **Execute** - Call `(create-order! {:symbol "AAPL" :quantity 100})`
6. **Update** - Function swaps `*ctx*`, watch triggers
7. **Push** - Framework calls `patch-elements!` with new HTML

### ClojureScript Options (probably not needed)

| Option | Size | Notes |
|--------|------|-------|
| Scittle (SCI) | ~400KB | Full Clojure interpreter in browser |
| Squint | ~1-5KB | CLJS syntax → mutable JS objects |
| Cherry | ~350KB | Real CLJS without build step |

**Recommendation:** Start with pure Datastar. Only add CLJS for specific JS interop needs.

---

## Two-Tier Storage Model

| Layer | Backing | Use Case |
|-------|---------|----------|
| `*ctx*` | STM durable atom | Fast prototyping, UI state, per-instance |
| Datalevin | Shared database | Heavy queries, cross-agent data |

**Progression:** Prototype with `*ctx*`, graduate to Datalevin. Same Malli schemas.

**Note:** Previous docs referenced XTDB-backed ctx. That's superseded. `*ctx*` is now STM-backed.

---

## Multi-Instance Model

Same namespace code, different data:

```
seon.email:work     → *ctx* with work email data
seon.email:personal → *ctx* with personal email data

Same code, different instances, different screens
```

---

## Implementation Phases

### Phase 0: Hiccup Transformer (LOW RISK)

**Goal:** Validate the transformation approach works.

**Deliverables:**
1. `seon.web.reactive.transform` namespace
2. `transform-hiccup` function using `clojure.walk/postwalk`
3. Tests covering all attribute transformations

**Transformations:**
```clojure
;; Actions
{:on:click :fn-name}     → {:data-on:click "@post('/action/ns/fn-name')"}
{:on:submit :fn-name}    → {:data-on:submit "@post('/action/ns/fn-name')"}

;; Fields
{:field :name}           → {:name "name" :data-bind:name true}
{:field :price :type "number"} → {:name "price" :data-bind:price true :type "number"}

;; Passthrough
{:class "foo" :id "bar"} → {:class "foo" :id "bar"}
```

**Test:**
```clojure
(deftest transform-test
  (is (= (transform-hiccup 'seon.test
           [:button {:on:click :increment} "Add"])
         [:button {:data-on:click "@post('/action/seon.test/increment')"} "Add"])))
```

### Phase 1: Action Endpoint (LOW RISK)

**Goal:** Single endpoint handles all action invocations.

**Route:** `POST /action/:namespace/:function`

**Flow:**
1. Extract namespace and function from path
2. Extract signals from request body (Datastar sends JSON)
3. Resolve function in namespace
4. Call function with signals map
5. Return empty 200 (ctx watch handles SSE push)

**Deliverables:**
1. `seon.web.reactive.actions` namespace
2. Ring handler for `/action/:ns/:fn`
3. Signal extraction helper

### Phase 2: Reactive `*ctx*` (MEDIUM RISK)

**Goal:** Changes to ctx automatically push to connected clients.

**Deliverables:**
1. `seon.web.reactive.ctx` namespace
2. `make-ctx` function - creates durable atom with watch
3. Watch triggers SSE push via existing `seon.web.sse` infrastructure

**Integration with existing SSE:**
```clojure
(add-watch ctx-atom :sse-push
  (fn [_ _ old new]
    (when (not= old new)
      ;; Use existing Flow infrastructure for targeted push
      (flow/emit-change! {:seon.sse/event-type :ctx-change
                          :seon.sse/namespace ns-sym}))))
```

### Phase 3: Malli Coercion (LOW RISK)

**Goal:** Automatic type coercion from string signals to proper types.

**Example:**
```clojure
;; Incoming signals (all strings from form)
{"quantity" "100" "price" "150.50" "active" "true"}

;; After Malli coercion based on function schema
{:quantity 100 :price 150.50 :active true}
```

**Deliverables:**
1. `seon.web.reactive.coerce` namespace
2. Integration with Malli schema registry
3. Auto-decode based on function parameter schemas

### Phase 4: Multi-Instance (MEDIUM RISK)

**Goal:** Same namespace, multiple independent instances.

**URL scheme:**
```
/ns/seon.email           → default instance
/ns/seon.email:work      → "work" instance
/ns/seon.email:personal  → "personal" instance
```

**Deliverables:**
1. Instance registry in `seon.web.reactive.instances`
2. Instance-scoped `*ctx*` binding
3. Instance-scoped SSE push

### Phase 5: Polish & Edge Cases (LOW RISK)

- Loading indicators (`:data-indicator`)
- Debouncing for text inputs
- Error handling and display
- JS interop escape hatches

---

## Prototype Plan

### Step 1: Transformer Spike

Create a minimal transformer and test manually:

```clojure
(ns seon.web.reactive.transform-test
  (:require [clojure.walk :as walk]
            [clojure.string :as str]))

(defn transform-attrs [ns-sym attrs]
  (reduce-kv
    (fn [m k v]
      (cond
        (= k :field)
        (-> m
            (assoc :name (name v))
            (assoc (keyword (str "data-bind:" (name v))) true))

        (str/starts-with? (name k) "on:")
        (let [event (subs (name k) 3)
              url (str "/action/" ns-sym "/" (name v))]
          (assoc m (keyword (str "data-on:" event))
                   (str "@post('" url "')")))

        :else (assoc m k v)))
    {}
    attrs))

(defn transform-hiccup [ns-sym hiccup]
  (walk/postwalk
    (fn [form]
      (if (and (vector? form)
               (keyword? (first form))
               (map? (second form)))
        (update form 1 #(transform-attrs ns-sym %))
        form))
    hiccup))
```

### Step 2: Manual Integration Test

1. Create test namespace with render function
2. Apply transformer to output
3. Serve via existing SSE infrastructure
4. Verify Datastar picks up attributes correctly

### Step 3: Action Endpoint Test

1. Add `/action/:ns/:fn` route
2. Create test function that swaps ctx
3. Submit form, verify function called with signals
4. Verify SSE pushes update

### Step 4: Full Loop Test

1. Render → Transform → Serve
2. User interaction → Action endpoint → Function call
3. ctx swap → Watch → SSE push
4. Browser updates via Datastar morphing

---

## Files to Create

```
src/seon/web/reactive/
├── transform.clj      ; Hiccup transformer
├── actions.clj        ; Action endpoint handler
├── ctx.clj            ; Durable ctx atom factory
├── coerce.clj         ; Malli signal coercion
└── instances.clj      ; Multi-instance registry

test/seon/web/reactive/
├── transform_test.clj
├── actions_test.clj
├── ctx_test.clj
└── integration_test.clj
```

---

## Integration Points

### With Existing SSE (`seon.web.sse`, `seon.web.sse.flow`)

- Use existing `refresh-all!` initially, migrate to targeted push
- Flow infrastructure routes updates to correct clients
- Leverage existing Datastar event formatting

### With Namespace Routes (`seon.ns.routes`)

- Integrate transformer into `render-namespace-content`
- Action endpoint works alongside existing namespace view

### With Schema Registry (`seon.schema`)

- Malli coercion uses existing registry
- Function parameter schemas guide type conversion

---

## Success Criteria

1. **Agent simplicity** - No Datastar concepts in agent code
2. **Reactivity** - ctx change → browser update < 100ms
3. **Forms work** - Submit form → function called with typed map
4. **Testable** - Each phase independently testable
5. **Incremental** - Can ship phases independently

---

## Open Questions

1. **Debounce config** - Per-field? Per-form? Global?
2. **Error display** - Auto-inject error divs? Let agent handle?
3. **Loading states** - Auto-add indicators? Optional?
4. **Instance lifecycle** - How created/destroyed? Timeout?

---

## References

- `docs/architecture/durable-ctx-design.md` - Full architecture vision
- `reference-code/datastar-clojure/` - Datastar SDK source
- `reference-code/hyperlith/` - Hyperlith patterns
- `src/seon/web/sse.clj` - Current SSE implementation
- `src/seon/web/sse/flow.clj` - Flow-based SSE (Phase 1 done)
- `docs/reference/datastar-quick-reference.md` - Datastar patterns

---

## Session Transfer Notes

If continuing this work in a new session:

1. Read this PRD first
2. Check `src/seon/web/sse/flow.clj` - Flow infrastructure already built
3. Start with Phase 0 (transformer) - lowest risk, validates approach
4. Branch is `feature/sse-live-reload`
5. Server runs via `./bin/run`, REPL on port 7888
