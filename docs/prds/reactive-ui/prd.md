# PRD: Reactive UI Architecture

**Status:** Phase 4 Complete - Instance-Based Architecture ✅
**Branch:** `feature/sse-live-reload`
**Date:** 2026-01-31
**Namespace:** `seon.web.reactive`

---

## Executive Summary

Build a Reagent-like experience for server-side Clojure. Agents write pure Clojure (atoms + render functions), the framework handles reactivity, persistence, and browser updates via Datastar/SSE.

**The Goal:**
```clojure
;; Agent writes this - no framework concepts leak through
(defn initial-state []
  {:signals []})

(defn add-signal! [{:seon.reactive/keys [ctx] :keys [name price]}]
  (swap! ctx update :signals conj {:name name :price price}))

(defn render-content [{:keys [signals]}]
  [:main
   [:ul (for [s signals] [:li (:name s)])]
   [:form {:on:submit :add-signal!}
    [:input {:field :name}]
    [:input {:field :price :type "number"}]
    [:button "Add"]]])
```

---

## Completed Phases

### Phase 0: Hiccup Transformer ✅ DONE

Transforms clean hiccup to Datastar-compatible HTML:
- `:on:click :fn-name` → `data-on:click="@post('/action/ns/fn-name')"`
- `:field ::symbol` → `name="seon.trading/symbol" data-bind="seon.trading/symbol"`

**Files:** `src/seon/web/reactive/transform.clj`

### Phase 1: Action Endpoint ✅ DONE

Single endpoint handles all action invocations:
- Route: `POST /action/:namespace/:function`
- JSON body parsing with namespace preservation
- Function resolution and invocation

**Files:** `src/seon/web/reactive/actions.clj`

### Phase 2: Reactive `*ctx*` ✅ DONE

Changes to ctx automatically push to connected clients:
- Durable atom with SSE watch
- Fragment-based DOM updates via Datastar

**Files:** `src/seon/web/reactive/ctx.clj`

### Hot Reload ✅ FIXED

Using `requiring-resolve` for late binding. Edit code → see changes immediately.

**Files:** `src/seon/web/server.clj`

---

### Phase 3: Browser Bridge ✅ DONE

REPL-to-browser execution bridge. Execute JavaScript or ClojureScript in connected browsers and get results back as Clojure data.

**Architecture:**
1. REPL calls `(browser/eval! 'seon.web.reactive.demo "document.title")`
2. Server sends SSE event with script injection to connected clients
3. Browser executes JavaScript, POSTs result back to `/api/browser/result`
4. Server delivers result to waiting promise
5. REPL receives result as string

**Files:**
- `src/seon/web/browser.clj` - REPL API and result delivery
- `resources/public/js/scittle.js` - ClojureScript interpreter for browser

### Browser Bridge API Reference

```clojure
(require '[seon.web.browser :as browser])

;;; ---------------------------------------------------------------------------
;;; Connection Checking
;;; ---------------------------------------------------------------------------

;; Check if any browser clients are connected
(browser/connected? 'seon.web.reactive.demo)
;; => true

;; Get the set of connected client channels
(browser/clients 'seon.web.reactive.demo)
;; => #{#object[AsyncChannel ...]}

;;; ---------------------------------------------------------------------------
;;; JavaScript Execution
;;; ---------------------------------------------------------------------------

;; Execute JavaScript in connected browsers
(browser/eval! 'seon.web.reactive.demo "document.title")
;; => "Reactive Demo"

;; Get element text content
(browser/eval! 'seon.web.reactive.demo
               "document.querySelector('#span-count').textContent")
;; => "5"

;; Get multiple values as JSON array
(browser/eval! 'seon.web.reactive.demo
               "Array.from(document.querySelectorAll('#list-items li span')).map(e => e.textContent)")
;; => "[\"item1\",\"item2\",\"item3\"]"

;; With custom timeout (default 5000ms)
(browser/eval! 'seon.web.reactive.demo "slowOperation()" :timeout-ms 10000)

;;; ---------------------------------------------------------------------------
;;; ClojureScript Execution (via Scittle)
;;; ---------------------------------------------------------------------------

;; Execute ClojureScript forms in browser
(browser/cljs! 'seon.web.reactive.demo '(+ 1 2 3))
;; => "6"

;; DOM access via ClojureScript
(browser/cljs! 'seon.web.reactive.demo
               '(.-textContent (js/document.querySelector "#span-count")))
;; => "5"

;; More complex expressions
(browser/cljs! 'seon.web.reactive.demo
               '(let [items (js/document.querySelectorAll "#list-items li span")]
                  (mapv #(.-textContent %) (array-seq items))))
;; => "[\"item1\" \"item2\" \"item3\"]"

;; Verify Scittle is loaded
(browser/eval! 'seon.web.reactive.demo "typeof scittle")
;; => "object"
```

### Error Handling

```clojure
;; Throws ExceptionInfo on no connected clients
(browser/eval! 'seon.web.reactive.demo "document.title")
;; => ExceptionInfo: No connected browser clients {:ns seon.web.reactive.demo :hint "Open the page in a browser first"}

;; Throws on timeout
(browser/eval! 'seon.web.reactive.demo "slowOp()" :timeout-ms 100)
;; => ExceptionInfo: Browser eval timeout {:ns ... :code "slowOp()" :timeout-ms 100}

;; Throws on JavaScript error
(browser/eval! 'seon.web.reactive.demo "nonexistent.property")
;; => ExceptionInfo: Browser eval error: nonexistent is not defined {:ns ... :code ... :error "..."}
```

### Client Tracking

Clients are tracked per-namespace in the ctx registry (`seon.web.reactive.ctx`):

```clojure
(require '[seon.web.reactive.ctx :as ctx])

;; Get client count
(ctx/client-count 'seon.web.reactive.demo)
;; => 2

;; Get client channels directly
(ctx/clients 'seon.web.reactive.demo)
;; => #{#object[AsyncChannel ...]}

;; Force push current state to all clients
(ctx/force-push! 'seon.web.reactive.demo)
```

### Implementation Notes

- Uses Datastar's `datastar-patch-elements` event to inject `<script>` elements
- Scripts self-remove after execution (clean DOM)
- Results POST to `/api/browser/result` as JSON
- Pending evals tracked with 60s auto-cleanup for stale requests
- `cljs!` wraps `eval!` by calling Scittle's `scittle.core.eval_string()`

---

### Phase 4: Instance-Based Architecture ✅ DONE

Each browser tab gets its own isolated instance with independent state.

**URL Pattern:**
```
GET  /ns/seon.web.reactive.demo              → Creates instance, redirects to ?instance=xxxx
GET  /ns/seon.web.reactive.demo?instance=a1b2 → Serves page for instance a1b2
POST /ns/seon.web.reactive.demo?instance=a1b2 → SSE connection for instance a1b2
POST /ns/seon.web.reactive.demo/increment!?instance=a1b2 → Action call for instance
```

**How It Works:**

1. User visits `/ns/seon.web.reactive.demo`
2. `routes.clj` detects reactive namespace (has `render-content` fn)
3. Creates new instance with `initial-state` value
4. Redirects to `?instance=xxxx` (4-char hex ID)
5. Each tab gets its own instance with isolated state
6. Actions receive `{:seon.reactive/ctx atom ...}` in signals

**Creating a Reactive Namespace:**

```clojure
(ns seon.trading.signals
  "A reactive namespace for trading signals.")

;; Optional: Initial state for new instances (default: {})
(defn initial-state []
  {:signals []
   :filter nil})

;; Required: Render function takes ctx VALUE, returns hiccup
(defn render-content [{:keys [signals filter]}]
  [:div#app
   [:h1 "Trading Signals (" (count signals) ")"]
   [:ul (for [s signals] [:li (:symbol s) " - $" (:price s)])]
   [:form {:on:submit :add-signal!}
    [:input {:field :symbol :placeholder "Symbol"}]
    [:input {:field :price :type "number"}]
    [:button "Add"]]])

;; Action functions receive ctx in signals
(defn add-signal! [{:seon.reactive/keys [ctx] :keys [symbol price]}]
  (when (and ctx symbol price)
    (swap! ctx update :signals conj {:symbol symbol :price (parse-double price)})))
```

**Files:**
- `src/seon/web/reactive/instance.clj` - Instance registry and lifecycle
- `src/seon/ns/routes.clj` - Routing with instance detection and SSE

### Instance API Reference

```clojure
(require '[seon.web.reactive.instance :as instance])

;; Create instance programmatically
(instance/create-instance! {::instance/namespace 'seon.trading
                            ::instance/initial-value {:signals []}})
;; => {::instance/id "a1b2" ::instance/namespace ... ::instance/created-at ...}

;; Get instance info
(instance/get-instance {::instance/id "a1b2"})
;; => {::instance/id "a1b2" ::instance/atom #<Atom...> ::instance/clients #<Atom#{...}>}

;; Get ctx atom directly
(::instance/atom (instance/instance-ctx {::instance/id "a1b2"}))
;; => #<Atom {:signals [...] :filter nil}>

;; Set render function (framework does this automatically)
(instance/set-render-fn! {::instance/id "a1b2"
                          ::instance/render-fn render-content})

;; Client management (framework handles this)
(instance/register-client! {::instance/id "a1b2" ::instance/channel ch})
(instance/unregister-client! {::instance/id "a1b2" ::instance/channel ch})
(instance/client-count {::instance/id "a1b2"})
;; => {::instance/count 2}

;; Force push current state to all clients
(instance/force-push! {::instance/id "a1b2"})

;; List all instances
(instance/list-instances {})
;; => {::instance/instances ["a1b2" "c3d4" "e5f6"]}

;; Cleanup
(instance/destroy-instance! {::instance/id "a1b2"})
```

---

## Future Phases

### Phase 5: Polish & Edge Cases

- Loading indicators (`:data-indicator`)
- Debouncing for text inputs
- Error handling and display
- JS interop escape hatches
- Instance cleanup (auto-destroy after inactivity)

---

## Key Files Reference

| File | Purpose | Status |
|------|---------|--------|
| `src/seon/web/reactive/transform.clj` | Hiccup → Datastar | ✅ Working |
| `src/seon/web/reactive/instance.clj` | Instance registry + SSE push | ✅ Working |
| `src/seon/web/reactive/ctx.clj` | Legacy global ctx (deprecated) | ⚠️ Legacy |
| `src/seon/web/reactive/actions.clj` | Action endpoint signal extraction | ✅ Working |
| `src/seon/web/reactive/demo.clj` | Demo page (instance-based) | ✅ Working |
| `src/seon/ns/routes.clj` | Namespace routing + instance detection | ✅ Working |
| `src/seon/web/browser.clj` | REPL-to-browser bridge (`eval!`, `cljs!`) | ✅ Working |
| `src/seon/web/server.clj` | HTTP server + JSON middleware | ✅ Working |
| `resources/public/js/scittle.js` | ClojureScript interpreter for browser | ✅ Working |
| `resources/public/js/seon-debug.js` | Debug panel | ✅ Working |
| `resources/public/js/datastar.js` | Datastar v1.0.0-RC.7 | ✅ Working |

---

## Design Decisions

### SSE over WebSocket

Simpler, better firewall traversal, matches existing patterns.

### Scittle for Browser ClojureScript

Send Clojure to browser for execution with JS interop. ~400KB interpreter.

### Instance-based isolation

Each browser tab gets its own instance with independent state. Instances are tracked in a registry with 4-char hex IDs. Browser connections stored as channels per instance.

### `/ns/namespace/fn` URL pattern

Keep close to Clojure concepts. Namespace is the route, function is the path segment.

---

## Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         AGENT CODE                               │
│  initial-state: fn [] → initial ctx value                       │
│  render-content: fn [ctx-value] → hiccup                        │
│  action-fn!: fn [{:seon.reactive/ctx atom ...}] → side effects  │
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
│  - Instance registry (per-tab isolation)                        │
│  - Action endpoint (/ns/:ns/:fn?instance=xxxx)                  │
│  - Signal extraction + ctx injection                            │
│  - SSE push on ctx change (via atom watch)                      │
│  - Browser bridge (eval!, cljs!)                                │
│  - Client tracking per instance                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │     DATASTAR      │
                    │  (SSE + morphing)  │
                    └─────────┬─────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                        BROWSER                                   │
│  - Thin UI terminal (receives SSE, sends actions)               │
│  - Scittle interpreter (ClojureScript execution)                │
│  - Result callback (POST /api/browser/result)                   │
└─────────────────────────────────────────────────────────────────┘

Browser Bridge Flow:
┌───────────┐   eval!/cljs!   ┌───────────┐   SSE event   ┌───────────┐
│   REPL    │ ───────────────>│  SERVER   │ ────────────> │  BROWSER  │
│           │                 │           │               │           │
│  waits    │                 │ pending   │               │ executes  │
│  on       │ <───────────────│ promise   │ <──────────── │ JS/CLJS   │
│  promise  │   delivers      │ registry  │   POST result │ + Scittle │
└───────────┘                 └───────────┘               └───────────┘
```

---

## Datastar Quick Reference

### SSE Event Format

```
event: datastar-patch-elements
data: selector #my-element
data: mode outer
data: elements <div id="my-element">Updated content</div>

```

### Patch Modes

| Mode | Behavior |
|------|----------|
| `outer` | Default. Morph element and contents |
| `inner` | Morph only inner HTML |
| `prepend` | Insert as first child |
| `append` | Insert as last child |

### Signal Binding (VALUE SYNTAX)

Use value syntax to preserve names exactly:
```html
<input data-bind="item-name">  <!-- signal: "item-name" -->
```

Key syntax applies camelCase conversion (avoid):
```html
<input data-bind:item-name>    <!-- signal: "itemName" - BAD -->
```

---

## References

- `reference-code/datastar-clojure/` - Datastar SDK source
- `reference-code/datastar/` - Datastar TypeScript source
- `docs/reference/datastar-quick-reference.md` - Datastar patterns
