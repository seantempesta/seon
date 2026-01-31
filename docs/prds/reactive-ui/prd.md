# PRD: Reactive UI Architecture

**Status:** ✅ IN PROGRESS - Core reactive loop working (counter + forms)
**Branch:** `feature/sse-live-reload`
**Date:** 2026-01-30
**Namespace:** `seon.web.reactive` (to be created)

---

## Executive Summary

Build a Reagent-like experience for server-side Clojure. Agents write pure Clojure (atoms + render functions), the framework handles reactivity, persistence, and browser updates via Datastar/SSE.

**The Goal:**
```clojure
;; Agent writes this - no framework concepts leak through
;; Namespaced keywords throughout - Malli validates automatically
(defn add-signal! [{::keys [name price]}]  ; namespaced destructuring
  (swap! *ctx* update ::signals conj {::name name ::price price}))

(defn render [_]
  [:main
   [:ul (for [s (::signals @*ctx*)] [:li (::name s)])]
   [:form {:on:submit :add-signal!}
    [:input {:field ::name}]                ; namespaced field
    [:input {:field ::price :type "number"}]
    [:button "Add"]]])
```

That's it. No signals, no Datastar attributes, no SSE handling. Data flows with full qualification from browser to server and back.

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

## Data Handling Architecture (CRITICAL)

**Core Principle:** Namespaced keywords everywhere. No data loss at any stage.

This aligns with Seon's core philosophy: fully qualified keys are queryable, schemas are discoverable, and agents can reason about data shapes without hallucination.

### The Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ BROWSER                                                                      │
│                                                                              │
│  [:input {:field ::trading/symbol}]                                         │
│           ↓ (transform.clj)                                                 │
│  <input name="seon.trading/symbol" data-bind="seon.trading/symbol">         │
│           ↓ (user types "AAPL")         ^^^^ VALUE SYNTAX (preserves name)  │
│  Datastar signal: {"seon.trading/symbol": "AAPL"}                           │
└─────────────────────────────────────────────────────────────────────────────┘
                              ↓ POST (JSON body)
┌─────────────────────────────────────────────────────────────────────────────┐
│ SERVER (actions.clj)                                                         │
│                                                                              │
│  1. Parse JSON body → {"seon.trading/symbol" "AAPL", "seon.trading/qty" "100"}
│  2. Keywordize with namespace preservation → {:seon.trading/symbol "AAPL"   │
│                                               :seon.trading/qty "100"}      │
│  3. Lookup action fn schema (Malli) → [:map [:seon.trading/symbol :string]  │
│                                             [:seon.trading/qty :int]]       │
│  4. Coerce via Malli → {:seon.trading/symbol "AAPL"                         │
│                         :seon.trading/qty 100}  ; string→int                │
│  5. Call action fn with validated, typed, namespaced map                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Requirements

1. **Namespace Preservation**
   - Field `:seon.trading/symbol` → HTML name `"seon.trading/symbol"` → keyword `:seon.trading/symbol`
   - Never strip namespaces. Use `/` as the delimiter in HTML (valid in `name` attributes).

2. **Schema-Driven Coercion**
   - Action functions MUST have Malli schemas (`:malli/schema` metadata)
   - Framework extracts parameter schema and coerces signals automatically
   - Type mismatches → validation error → user feedback (not silent failure)

3. **No Magic, No Loss**
   - Raw JSON body preserved for debugging
   - Every transformation step is explicit and reversible
   - Agents can inspect any stage of the pipeline via REPL

4. **Alias Support**
   - Within a namespace, `::symbol` expands to `:seon.trading/symbol`
   - Hiccup transform must resolve aliases at transform time (has access to ns context)

### Example: Full Round-Trip

```clojure
(ns seon.trading
  (:require [seon.web.reactive.ctx :as ctx]))

;; Schema defines the contract
(def Order
  [:map
   [::symbol :string]
   [::quantity :int]
   [::price :double]])

;; Action function with schema metadata
(defn create-order!
  "Create a new trading order."
  {:malli/schema [:=> [:cat Order] :any]}
  [{::keys [symbol quantity price]}]
  (swap! *ctx* update ::orders conj
         {::symbol symbol ::quantity quantity ::price price ::created-at (System/currentTimeMillis)}))

;; Render with namespaced fields
(defn render [{::keys [orders]}]
  [:div
   [:form {:on:submit :create-order!}
    [:input {:field ::symbol :placeholder "AAPL"}]
    [:input {:field ::quantity :type "number"}]
    [:input {:field ::price :type "number" :step "0.01"}]
    [:button "Submit Order"]]
   [:ul (for [{::keys [symbol quantity]} orders]
          [:li (str symbol " x " quantity)])]])
```

**What happens on submit:**
1. Transform: `::symbol` → `:seon.trading/symbol` → `name="seon.trading/symbol"`
2. Datastar collects: `{"seon.trading/symbol": "AAPL", "seon.trading/quantity": "100", ...}`
3. Server parses JSON, keywordizes: `{:seon.trading/symbol "AAPL", :seon.trading/quantity "100", ...}`
4. Framework looks up `create-order!` schema, sees it expects `Order`
5. Malli coerces: `"100"` → `100` (int), `"150.50"` → `150.5` (double)
6. Calls `(create-order! {:seon.trading/symbol "AAPL" :seon.trading/quantity 100 :seon.trading/price 150.5})`

### Implementation Notes

**CRITICAL: Datastar camelCase conversion - SOLVED**

Datastar's KEY SYNTAX (`data-bind:item-name`) applies camelCase conversion:
- `data-bind:item-name` → signal `itemName` → JSON `{"itemName": "value"}`

**Solution: Use VALUE SYNTAX instead:**
- `data-bind="item-name"` → signal `item-name` → JSON `{"item-name": "value"}`

Value syntax (from `bind.ts` line 25: `key != null ? modifyCasing(key, mods) : value`)
passes the value through unchanged when `key` is null.

The transform layer outputs `{:data-bind "item-name"}` which renders as `data-bind="item-name"`.

**Keywordizing with namespaces and camelCase→kebab-case:**
```clojure
(defn camel->kebab
  "Convert camelCase to kebab-case.
   'itemName' → 'item-name'
   'userName' → 'user-name'"
  [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

(defn keywordize-signal
  "Convert string key to proper keyword.
   - Handles namespaced: 'seon.trading/symbol' → :seon.trading/symbol
   - Handles camelCase: 'itemName' → :item-name"
  [k]
  (if (string? k)
    (let [[ns-part name-part] (str/split k #"/" 2)]
      (if name-part
        ;; Namespaced key - preserve namespace, convert name
        (keyword ns-part (camel->kebab name-part))
        ;; Simple key - just convert camelCase
        (keyword (camel->kebab k))))
    k))

(defn keywordize-namespaced
  "Convert string keys to namespaced keywords.
   'seon.trading/symbol' → :seon.trading/symbol
   'itemName' → :item-name"
  [m]
  (into {}
    (map (fn [[k v]] [(keywordize-signal k) v]))
    m))
```

**Alias resolution at transform time:**
```clojure
(defn resolve-field-keyword
  "Resolve ::alias to full keyword using namespace context."
  [ns-sym kw]
  (if (= (namespace kw) (name ns-sym))
    kw  ; already fully qualified
    (keyword (name ns-sym) (name kw))))  ; expand alias
```

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
[:input {:name "user-name" :data-bind "user-name"}]  ; value syntax
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

;; Fields - VALUE SYNTAX (preserves names, avoids camelCase conversion)
{:field ::symbol}        → {:name "seon.trading/symbol" :data-bind "seon.trading/symbol"}
{:field :seon.trading/price :type "number"}
                         → {:name "seon.trading/price" :data-bind "seon.trading/price" :type "number"}

;; Simple keywords
{:field :item-name}      → {:name "item-name" :data-bind "item-name"}

;; Passthrough
{:class "foo" :id "bar"} → {:class "foo" :id "bar"}
```

**Key insight:** Uses VALUE SYNTAX (`data-bind="name"`) instead of KEY SYNTAX (`data-bind:name`) because Datastar's key syntax applies camelCase conversion. Value syntax preserves names exactly.

**Test:**
```clojure
(deftest transform-test
  (is (= (transform-hiccup 'seon.trading
           [:button {:on:click :increment} "Add"])
         [:button {:data-on:click "@post('/action/seon.trading/increment')"} "Add"]))

  ;; Namespaced field - preserved exactly
  (is (= (transform-hiccup 'seon.trading
           [:input {:field :seon.trading/symbol}])
         [:input {:name "seon.trading/symbol" :data-bind "seon.trading/symbol"}])))
```

### Phase 1: Action Endpoint (LOW RISK)

**Goal:** Single endpoint handles all action invocations with proper data handling.

**Route:** `POST /action/:namespace/:function`

**Flow:**
1. Parse JSON body from request (Datastar sends `application/json`)
2. Keywordize with namespace preservation: `"seon.trading/symbol"` → `:seon.trading/symbol`
3. Extract namespace and function from URL path
4. Resolve function var in namespace
5. Read `:malli/schema` from var metadata (if present)
6. Coerce signals via Malli string-transformer
7. Validate against schema (return error if invalid)
8. Call function with typed, validated, namespaced map
9. Return 200 (ctx watch handles SSE push)

**Data handling (CRITICAL):**
```clojure
;; Raw request body (JSON string)
"{\"seon.trading/symbol\":\"AAPL\",\"seon.trading/quantity\":\"100\"}"

;; Step 1: Parse JSON
{"seon.trading/symbol" "AAPL", "seon.trading/quantity" "100"}

;; Step 2: Keywordize with namespace preservation
{:seon.trading/symbol "AAPL", :seon.trading/quantity "100"}

;; Step 6: Malli coercion (using function's :malli/schema)
{:seon.trading/symbol "AAPL", :seon.trading/quantity 100}  ; int!
```

**Deliverables:**
1. `seon.web.reactive.actions` namespace
2. Ring handler for `/action/:ns/:fn`
3. `parse-signals` - JSON parse + namespaced keywordize
4. Integration point for Phase 3 coercion

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

### Phase 3: Schema-Driven Coercion (LOW RISK)

**Goal:** Automatic type coercion using standard Malli `:malli/schema` metadata. Zero extra configuration.

**How it works:**

Action functions already have Malli schemas (project convention):
```clojure
(defn create-order!
  "Create a new trading order."
  {:malli/schema [:=> [:cat Order] :any]}  ; standard Malli function schema
  [{::keys [symbol quantity price]}]
  ...)
```

The framework reads this schema and uses `malli.transform/string-transformer`:
```clojure
(require '[malli.core :as m]
         '[malli.transform :as mt])

(defn coerce-signals
  "Coerce string signals to types defined in function schema."
  [action-var signals]
  (if-let [schema (-> action-var meta :malli/schema)]
    ;; Extract input schema from [:=> [:cat InputSchema] OutputSchema]
    (let [input-schema (-> schema second second)  ; get InputSchema from [:cat InputSchema]
          coercer (m/coercer input-schema mt/string-transformer)]
      (coercer signals))
    ;; No schema - pass through as-is
    signals))
```

**Example transformation:**
```clojure
;; Function schema
{:malli/schema [:=> [:cat [:map
                           [:seon.trading/symbol :string]
                           [:seon.trading/quantity :int]
                           [:seon.trading/price :double]]]
                :any]}

;; Incoming signals (all strings from Datastar)
{:seon.trading/symbol "AAPL"
 :seon.trading/quantity "100"
 :seon.trading/price "150.50"}

;; After Malli coercion
{:seon.trading/symbol "AAPL"      ; string stays string
 :seon.trading/quantity 100       ; string → int
 :seon.trading/price 150.5}       ; string → double
```

**Validation errors:**
```clojure
;; If coercion/validation fails
{:seon.trading/quantity "not-a-number"}

;; Framework catches error and returns user-friendly response
{:seon.reactive/error {:field :seon.trading/quantity
                       :message "Expected integer, got: not-a-number"}}
```

**Deliverables:**
1. `seon.web.reactive.coerce` namespace
2. `coerce-signals` function that reads `:malli/schema` metadata
3. Error formatting for validation failures
4. Integration with action endpoint (Phase 1)
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
        (let [field-str (if (qualified-keyword? v)
                          (str (namespace v) "/" (name v))
                          (name v))]
          (-> m
              (assoc :name field-str)
              (assoc :data-bind field-str)))  ; VALUE SYNTAX

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

## TODO: Research

### Unified Page Header/Layout System

**Problem:** Datastar, Tailwind CSS, fonts, and debug scripts are included separately in multiple files:
- `src/seon/web/html.clj` - main app layout
- `src/seon/web/reactive/demo.clj` - demo page (own full HTML)
- `src/seon/primer/html.clj` - primer layout

This leads to:
- Version mismatches (RC.6 vs RC.7)
- Inconsistent includes (some have debug panel, some don't)
- Maintenance burden when updating dependencies

**Solution needed:**
- Single `seon.web.layout` or `seon.web.head` namespace
- One place to define: Datastar, Tailwind, fonts, debug tools
- All pages use this shared header
- Pages only define their body content

**Pattern to follow:**
```clojure
;; seon.web.layout
(defn page [{:keys [title body]}]
  [:html
   (shared-head title)  ; Datastar, CSS, fonts, debug
   [:body body]])

;; Usage in any page
(layout/page {:title "Demo" :body [:main ...]})
```

### Agent-Friendly Test HTML

**Rule:** All interactive elements in test/demo pages MUST have `id` attributes for reliable browser automation.

**Pattern:**
```clojure
;; GOOD - agent can click by ID
[:button {:id "btn-increment" :on:click :increment!} "+"]
[:button {:id "btn-decrement" :on:click :decrement!} "-"]
[:input {:id "input-item-name" :field :item-name}]
[:form {:id "form-add-item" :on:submit :add-item!} ...]

;; BAD - agent has to use fragile coordinate clicking
[:button {:on:click :increment!} "+"]
```

**Naming convention:**
- Buttons: `btn-<action>` (e.g., `btn-save`, `btn-delete-item`)
- Inputs: `input-<field>` (e.g., `input-username`, `input-price`)
- Forms: `form-<purpose>` (e.g., `form-login`, `form-add-item`)
- Sections: `section-<name>` (e.g., `section-counter`, `section-items`)

**Why IDs:**
- Browser automation can use `find` tool with ID
- Or click directly via `ref` from `read_page`
- More reliable than text matching or coordinates
- Works even if layout changes

**Transform layer consideration (future):**
Could auto-generate IDs from `:on:click` action names:
```clojure
;; Input
[:button {:on:click :increment!} "+"]
;; Auto-transformed to
[:button {:id "action-increment" :data-on:click "..."} "+"]
```

---

## References

- `docs/architecture/durable-ctx-design.md` - Full architecture vision
- `reference-code/datastar-clojure/` - Datastar SDK source
- `reference-code/hyperlith/` - Hyperlith patterns
- `src/seon/web/sse.clj` - Current SSE implementation
- `src/seon/web/sse/flow.clj` - Flow-based SSE (Phase 1 done)
- `docs/reference/datastar-quick-reference.md` - Datastar patterns

---

## ✅ RESOLVED: Hot Reload Working

**Status: FIXED - 2026-01-30**

### Root Cause: Orphaned Vars

When clj-reload reloads a namespace, it can **create new Var objects**. If the server captures a Var reference at startup (even using `#'handler`), it holds the OLD Var object which becomes "orphaned" - it exists in memory but is no longer linked to the namespace symbol.

The REPL sees the new Var (because it resolves the symbol fresh), but the server's captured reference points to the orphaned old Var that never gets updated.

### The Fix: Late Binding with `requiring-resolve`

In `src/seon/web/server.clj`, instead of capturing the handler Var at startup:

```clojure
;; OLD (broken): Captures Var object at startup - becomes orphaned on reload
handler-var (or handler #'routes/handler)
request-handler (fn [req]
                  ((-> handler-var ...) req))

;; NEW (working): Resolves symbol to CURRENT Var on every request
handler-sym 'seon.web.routes/handler
request-handler (fn [req]
                  (let [current-handler (requiring-resolve handler-sym)]
                    ((-> current-handler ...) req)))
```

`requiring-resolve` looks up the symbol in the namespace at request time, always returning the current Var regardless of how many times the namespace has been reloaded.

### Verification

```bash
# 1. Edit demo.clj (change title text)
# 2. Dev hook automatically reloads (shows "Reloaded: seon.web.reactive.demo, ...")
# 3. curl http://localhost:8080/reactive-demo shows new text immediately
# NO SERVER RESTART NEEDED
```

### Key Insight

The problem was NOT middleware closures (attempted fix didn't work). The problem was **Var identity** - clj-reload creates new Var objects, and any code holding the old Var object is stuck with stale code forever.

### Files Changed

| File | Change |
|------|--------|
| `src/seon/web/server.clj` | Use `requiring-resolve` instead of captured Var |
| `src/seon/dev/hook.clj` | Added "Reloaded: ..." feedback to show which namespaces changed |
| `env/dev/clj/user.clj` | Fixed `user/search` to use correct namespace keywords |

---

---

## Current Status (2026-01-30)

### What's Working ✅

1. **Counter buttons** - Click +/- and the count updates in real-time via SSE
2. **Form submission** - Add items via form, signals extracted correctly
3. **SSE infrastructure** - Server pushes updates, browser receives them
4. **Hiccup transformation** - `:on:click` → `data-on:click`, `:field` → `data-bind`
5. **Debug panel** - `seon-debug.js` tracks Datastar events properly
6. **Local Datastar** - Using v1.0.0-RC.7 from submodule, not CDN
7. **JSON body parsing** - Middleware parses Datastar POST bodies

### Fixed Issues ✅

1. **JSON body parsing** - Added `wrap-json-body` middleware to `server.clj`
2. **Datastar camelCase conversion** - Changed to VALUE SYNTAX (`data-bind="item-name"`)
   instead of KEY SYNTAX (`data-bind:item-name`) to avoid Datastar's automatic
   camelCase conversion. Value syntax preserves names exactly.

### Remaining Work

1. **Live input** - Test typing updates the display (`:field` + `data-on:input`)
2. **Namespaced keywords** - Full implementation per Data Handling Architecture section
3. **Malli coercion** - Phase 3 implementation

### Key Files

| File | Purpose | Status |
|------|---------|--------|
| `src/seon/web/reactive/transform.clj` | Hiccup → Datastar | ✅ Working |
| `src/seon/web/reactive/ctx.clj` | Reactive ctx + SSE push | ✅ Working |
| `src/seon/web/reactive/actions.clj` | Action endpoint | ✅ Working |
| `src/seon/web/reactive/demo.clj` | Demo page | ✅ Working |
| `src/seon/web/server.clj` | HTTP server + JSON middleware | ✅ Working |
| `resources/public/js/seon-debug.js` | Debug panel | ✅ Working |
| `resources/public/js/datastar.js` | Datastar v1.0.0-RC.7 | ✅ Working |

---

## Session Transfer Notes

If continuing this work in a new session:

1. Hot reload is working - no blocker
2. Branch is `feature/sse-live-reload`
3. Server runs via `./bin/run`, REPL on port 7888
4. **Use Gemini search WITH file context:** `(user/search "query" :files ["src/..."])`
5. Next: Continue with Phase 0 (Hiccup Transformer) - it's already implemented in `src/seon/web/reactive/transform.clj`

---

## Datastar Architecture (from source)

This section documents Datastar's actual behavior based on reading `reference-code/datastar/` TypeScript source and `reference-code/datastar-clojure/` SDK source.

### SSE Event Format (Exact Specification)

Datastar expects SSE events in the standard Server-Sent Events format. The SDK generates events like this:

```
event: datastar-patch-elements
data: selector #my-element
data: mode outer
data: elements <div id="my-element">Updated content</div>

event: datastar-patch-signals
data: signals {"count": 5, "name": "test"}

```

**Key details from `libraries/sdk/src/main/starfederation/datastar/clojure/api/sse.clj`:**
- Each event starts with `event: <event-type>\n`
- Optional `id: <event-id>\n` for replay support
- Optional `retry: <milliseconds>\n` for reconnection timing
- Data lines prefixed with `data: ` followed by `<key> <value>`
- Event ends with a blank line (`\n\n`)

**Event Types (from `consts.clj`):**
- `datastar-patch-elements` - Update DOM elements
- `datastar-patch-signals` - Update client-side signals

**Data Line Prefixes (from `consts.clj`):**
- `selector ` - CSS selector for target element
- `mode ` - Patch mode (outer, inner, append, etc.)
- `elements ` - HTML content (multi-line supported)
- `useViewTransition ` - Enable view transitions
- `signals ` - JSON string for signal updates
- `onlyIfMissing ` - Only patch if signal doesn't exist
- `namespace ` - HTML namespace (html, svg, mathml)

### Patch Modes

From `library/src/plugins/watchers/patchElements.ts`:

| Mode | Behavior |
|------|----------|
| `outer` | **Default.** Morph (smart diff) element and its contents |
| `inner` | Morph only the inner HTML |
| `replace` | Replace element entirely (no morphing) |
| `remove` | Remove the element |
| `prepend` | Insert new content as first child |
| `append` | Insert new content as last child |
| `before` | Insert new content before the element |
| `after` | Insert new content after the element |

**ID-based targeting:** When no `selector` is specified and mode is `outer` or `replace`, Datastar extracts the `id` attribute from the incoming HTML element and uses that to find the target.

### How `data-bind` Works (from `bind.ts`)

`data-bind:signalName` creates two-way binding:

1. **Signal Creation** - If signal doesn't exist, creates it with current input value
2. **Input → Signal** - Listens for `input` and `change` events, updates signal via `mergePaths()`
3. **Signal → Input** - Uses `effect()` to watch signal, updates input value when signal changes

**Syntax variations:**
```html
<!-- Key-based: signal name from attribute key -->
<input data-bind:username>        <!-- signal: "username" -->
<input data-bind:user-name>       <!-- signal: "user-name" -->

<!-- Value-based: signal name from attribute value -->
<input data-bind="userName">      <!-- signal: "userName" -->
```

**Type handling:**
- `type="number"` or `type="range"` → number conversion
- `type="checkbox"` → boolean or value string
- `type="radio"` → value when checked
- `type="file"` → array of `{name, contents, mime}` objects

### How `data-on` Works (from `on.ts`)

`data-on:event="expression"` attaches event listeners:

```html
<button data-on:click="$count++">Add</button>
<form data-on:submit="@post('/submit')">...</form>
```

**Event modifiers (via mods):**
- `.prevent` - calls `evt.preventDefault()`
- `.stop` - calls `evt.stopPropagation()`
- `.capture` - adds in capture phase
- `.passive` - passive listener
- `.once` - removes after first trigger
- `.outside` - triggers when clicking outside element
- `.window` - attaches to window instead of element

**Form submit special handling:**
- If element is `<form>` and event is `submit`, automatically calls `preventDefault()`
- This prevents page navigation, letting Datastar handle the request

### How `@post()` Works (from `fetch.ts`)

`@post('/url')` (and `@get`, `@put`, `@patch`, `@delete`) initiates SSE fetch:

**Default behavior (`contentType: 'json'`):**
1. Collects ALL signals using `filtered({ include: /.*/, exclude: /(^|\.)_/ })`
2. Signals starting with `_` are excluded (private)
3. Converts to JSON and sends in request body
4. Sets `Content-Type: application/json`
5. Sets `Accept: text/event-stream, text/html, application/json`
6. Sets `Datastar-Request: true` header

**Form behavior (`contentType: 'form'`):**
```html
<form data-on:submit="@post('/submit', {contentType: 'form'})">
```
1. Finds closest `<form>` element (or uses `selector` option)
2. Validates form (`checkValidity()`)
3. Collects FormData from form
4. Sends as `application/x-www-form-urlencoded` (or `multipart/form-data` if enctype set)

**Request flow:**
1. Dispatches `datastar-fetch` event with type `started`
2. Makes fetch request
3. Parses SSE stream, dispatches events for each message
4. Dispatches `finished` when done (or `error`/`retrying` on failures)

### Signal Flow on Form Submit

**Step-by-step with `contentType: 'json'`:**

1. User fills form inputs bound with `data-bind`
2. Each keystroke updates signals via `mergePaths()`
3. User clicks submit → `@post('/action/ns/fn')` runs
4. `filtered()` collects all non-private signals: `{input1: "value1", input2: "value2"}`
5. JSON body: `{"input1":"value1","input2":"value2"}`
6. Server receives POST with JSON body
7. Server extracts signals: `(json/read-str (:body request))`

**Step-by-step with `contentType: 'form'`:**

1. User fills form inputs with `name` attributes
2. Form fields maintain their values (no signals needed)
3. User clicks submit → `@post('/action/ns/fn', {contentType: 'form'})` runs
4. `FormData` collected from form element
5. Encoded body: `input1=value1&input2=value2`
6. Server receives POST with form-encoded body
7. Server extracts via Ring params middleware: `(:params request)`

### Reading Signals on Server (Clojure SDK)

From `libraries/sdk/src/main/starfederation/datastar/clojure/api/signals.clj`:

```clojure
(defn get-signals [request]
  (if (= :get (:request-method request))
    ;; GET: signals in query param "datastar"
    (get-in request [:query-params "datastar"])
    ;; POST/PUT/etc: signals in body
    (:body request)))
```

**Important:** Returns raw string (JSON or form-encoded). You must parse it:
```clojure
(require '[cheshire.core :as json])
(json/parse-string (d*/get-signals request) keyword)
```

### Response Handling

Datastar accepts multiple response types (from `fetch.ts`):

| Content-Type | Handling |
|--------------|----------|
| `text/event-stream` | Standard SSE parsing (default) |
| `text/html` | Dispatch as `datastar-patch-elements` |
| `application/json` | Dispatch as `datastar-patch-signals` |
| `text/javascript` | Execute as script |

**Headers for non-SSE responses:**
- `datastar-selector` → CSS selector override
- `datastar-mode` → Patch mode override
- `datastar-only-if-missing` → Signal merge behavior

### Custom Events Dispatched

Datastar dispatches `datastar-fetch` custom events on `document`:

```typescript
type DatastarFetchEvent = {
  type: 'started' | 'finished' | 'error' | 'retrying' | 'retries-failed' |
        'datastar-patch-elements' | 'datastar-patch-signals'
  el: HTMLOrSVGElement  // The element that initiated the fetch
  argsRaw: Record<string, string>  // Parsed SSE data lines
}
```

**This is what `seon-debug.js` listens for to track SSE activity.**

---

## Implementation Stages (Testable)

Each stage is independently testable with specific expectations.

### Stage 1: Static Button Click → Action Called

**Goal:** Verify the click-to-action pipeline works without signals.

**Setup:**
```clojure
;; src/seon/web/reactive/demo.clj
(def *ctx* (atom {:count 0}))

(defn increment! [_signals]
  (swap! *ctx* update :count inc))

(defn render []
  [:div {:id "counter"}
   [:span "Count: " (:count @*ctx*)]
   [:button {:on:click :increment!} "Add"]])
```

**Test procedure:**
1. Visit http://localhost:8080/reactive-demo
2. Open browser DevTools Network tab
3. Click "Add" button
4. **Verify:** POST request to `/action/seon.web.reactive.demo/increment!`
5. **Verify:** Request has header `Datastar-Request: true`
6. **Verify:** Server logs show `increment!` called
7. **Verify:** `@*ctx*` has `:count` incremented

**What can go wrong:**
- Hiccup transform not generating `data-on:click` correctly
- Action route not matching
- Function not resolving in namespace

### Stage 2: Form Submit → Action Receives Signals

**Goal:** Verify signals are extracted from POST body and passed to action.

**Setup:**
```clojure
(defn add-item! [{:keys [item-name]}]
  (when (seq item-name)
    (swap! *ctx* update :items conj item-name)))

(defn render []
  [:form {:on:submit :add-item!}
   [:input {:field :item-name :placeholder "Enter item"}]
   [:button "Add Item"]])
```

**Test procedure:**
1. Type "Test Item" in the input field
2. Click "Add Item" button
3. **Verify:** POST body contains `{"item-name": "Test Item"}` (or form-encoded equivalent)
4. **Verify:** Action function receives `{:item-name "Test Item"}`
5. **Verify:** `@*ctx*` has "Test Item" in `:items`

**Debug strategy if failing:**
1. Check Network tab → Request payload
2. Check server logs → What does `(d*/get-signals req)` return?
3. Check action → Is the function being called with the right args?

### Stage 3: SSE Push Updates DOM

**Goal:** Verify ctx changes push HTML fragments to browser.

**Setup:** Use Stage 1 counter, but focus on the SSE response.

**Test procedure:**
1. Click "Add" button
2. **Verify:** Response is `text/event-stream`
3. **Verify:** Response contains `event: datastar-patch-elements`
4. **Verify:** Response contains HTML fragment with updated count
5. **Verify:** Browser DOM updates without page reload

**Debug strategy:**
1. Check Response tab in DevTools → See raw SSE data
2. Check `window.SEON_DEBUG.rawEvents` → Parsed event data
3. Check DOM → Does the `#counter` element have new content?

### Stage 4: Morphing Preserves Input State

**Goal:** Verify DOM updates don't reset user input.

**Setup:**
```clojure
(defn render []
  [:div {:id "form-area"}
   [:input {:id "name-input" :field :name}]
   [:button {:on:click :save!} "Save"]
   [:span "Saved items: " (count (:items @*ctx*))]])
```

**Test procedure:**
1. Type "Partial text" in input field
2. Click "Save" button (triggers ctx change and SSE push)
3. **Verify:** Input field still contains "Partial text"
4. **Verify:** Saved count updated
5. **Verify:** Cursor position preserved (if possible)

**Why this matters:** Datastar's morph algorithm should preserve input state. If it's replacing instead of morphing, user experience breaks.

### Stage 5: Live Input Updates (data-on:input)

**Goal:** Verify real-time typing updates work.

**Setup:**
```clojure
(defn update-preview! [{:keys [input-text]}]
  (swap! *ctx* assoc :preview input-text))

(defn render []
  [:div {:id "live-preview"}
   [:input {:field :input-text
            :data-on:input "@post('/action/demo/update-preview!')"}]
   [:div "Preview: " (:preview @*ctx*)]])
```

**Test procedure:**
1. Type "Hello" in input (one character at a time)
2. **Verify:** Preview updates after each character
3. **Verify:** No visible lag or flicker
4. **Verify:** Typing "Hello World" shows full text in preview

**Consideration:** May need debouncing for performance. Test both immediate and debounced modes.

### Stage 6: Error Handling

**Goal:** Verify errors are handled gracefully.

**Test procedure:**
1. Call action that throws exception
2. **Verify:** Browser shows error (debug panel or console)
3. **Verify:** Page doesn't break
4. **Verify:** Subsequent actions still work

---

## Debug Code Cleanup (seon-debug.js)

### Changes Made

The debug code is already well-structured. Key improvements:

1. **Keep:** The `datastar-fetch` event listener - this is the correct way to track Datastar activity
2. **Keep:** Raw event storage for inspection (`DEBUG.rawEvents`)
3. **Keep:** Status tracking (started/finished/error/retrying)

### Recommended Tweaks

```javascript
// In logEvent function - reduce noise
function logEvent(type, detail) {
  // ... existing code ...

  // Only log to console for important events, not every update
  if (type !== 'datastar-patch-elements' || DEBUG.sse.patchCount <= 3) {
    console.log('[seon-debug]', type, detail || '');
  }
}
```

### Console Commands for Debugging

Add these to the debug panel documentation:

```javascript
// See last 5 raw SSE events
window.SEON_DEBUG.rawEvents.slice(-5)

// See current signals (if Datastar exposes them)
window._ds?.signals

// Check if Datastar is loaded
document.querySelector('script[src*="datastar"]')

// Manually trigger a test fetch
fetch('/action/seon.web.reactive.demo/increment!', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
    'Datastar-Request': 'true'
  },
  body: '{}'
}).then(r => r.text()).then(console.log)
```
