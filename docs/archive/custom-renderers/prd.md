---
type: prd
status: completed
tags: [prd, archive]
---

# PRD: Custom Renderers

## Status: SUPERSEDED by spec-driven-rendering — renderers resolved via fn-spec linking

**Status:** Ready for Implementation
**Priority:** Medium
**Branch:** feature/namespace-ui
**Parent PRD:** `docs/prds/namespace-ui/prd.md` (Phase 7)

---

## Goals

1. **Domain-specific UIs** - Enable namespaces to provide custom views tailored to their data
2. **Runtime extensibility** - Allow agents to register custom renderers via ctx atoms
3. **View mode support** - Support `:tile`, `:half`, `:full` view modes for responsive layouts
4. **Graceful fallback** - Use default introspection when custom renderers aren't available

---

## Problem Statement

Currently, `/ns/{namespace}` routes can render custom views if a namespace has a public `render` function. But this approach has limitations:

1. **Static binding** - The `render` fn must exist at namespace load time
2. **No ctx awareness** - Render functions don't have access to session-specific ctx atoms
3. **No view modes** - All views render the same regardless of available space

**Impact:** Domains like trading, health, or finance cannot easily build session-aware dashboards that adapt to tile vs full-page layouts.

---

## Current State

### Render Convention (`src/seon/ns/routes.clj:77-89`)

```clojure
(defn- namespace-has-render?
  "Check if namespace has a public render function."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    (when-let [render-var (ns-resolve ns-obj 'render)]
      (and (var? render-var)
           (fn? @render-var)))))

(defn- call-namespace-render
  "Call the namespace's render function with format and id."
  [ns-sym format id]
  (let [render-fn @(ns-resolve (find-ns ns-sym) 'render)]
    (render-fn {:format format :id id})))

```

### View Multimethod (`src/seon/ns/view.clj:106-119`)

```clojure
(defmulti render*
  "Internal multimethod for rendering. Dispatches on [format view-type]."
  (fn [value format] [format (extract-view-type value)]))

```

### Example: Agent Observatory (`src/seon/ai/agent.clj:586-646`)

The `seon.ai.agent` namespace provides a `render` function that:
- Checks for `id` to switch between list/detail views
- Returns Hiccup HTML directly
- Uses `view/render` with typed values for sub-components

---

## Solution Design

### The `:seon.ui/render-fn` Convention

Namespaces (or agents operating on namespaces) can register a custom render function in their ctx atom:

```clojure
;; In agent working on seon.trading namespace
(swap! *ctx* assoc :seon.ui/render-fn 'seon.trading/render-dashboard)

```

The render function receives a standardized request map:

```clojure
(defn render-dashboard
  "Custom renderer for trading namespace.

   Request:
     {:view-mode :tile | :half | :full
      :format    :html | :ai | :human | :raw
      :id        \"optional-entity-id\" | nil
      :ctx       @*ctx*
      :db        <xtdb-connection>}

   Returns: Hiccup HTML (for :html format) or string (for other formats)"
  [{:keys [view-mode format id ctx db]}]
  (case view-mode
    :tile  (render-tile ctx)
    :half  (render-half-view ctx db)
    :full  (render-full-dashboard ctx db)))

```

### View Modes

| Mode | Use Case | Typical Size |
|------|----------|--------------|
| `:tile` | Dashboard mini-view | 200x150 px |
| `:half` | Side-by-side comparison | 50% viewport |
| `:full` | Dedicated view | Full viewport |

### Fallback Chain

```
1. Check ctx atom for :seon.ui/render-fn
   ↓ (if present, resolve and call with request)
2. Check namespace for public `render` function
   ↓ (existing behavior, src/seon/ns/routes.clj:77-89)
3. Use default introspection renderer
   ↓ (existing behavior, src/seon/ns/routes.clj:91-188)

```

---

## Implementation

### Phase 1: Ctx Lookup

**File:** `src/seon/ns/routes.clj`

Modify `render-namespace-content` to check ctx before namespace render:

```clojure
(defn- resolve-ctx-render-fn
  "Look up :seon.ui/render-fn from a namespace's ctx atom."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    ;; Look for a *ctx* var that's an atom containing :seon.ui/render-fn
    (when-let [ctx-var (ns-resolve ns-obj '*ctx*)]
      (when (instance? clojure.lang.IAtom @ctx-var)
        (when-let [render-fn-sym (:seon.ui/render-fn @@ctx-var)]
          (when-let [resolved (resolve render-fn-sym)]
            @resolved))))))

(defn- render-namespace-content
  [ns-sym session-id view-mode]
  (let [ctx-render-fn (resolve-ctx-render-fn ns-sym)]
    (cond
      ;; 1. Ctx-registered custom renderer
      ctx-render-fn
      (ctx-render-fn {:view-mode view-mode
                      :format :html
                      :id session-id
                      :ctx @@(ns-resolve (find-ns ns-sym) '*ctx*)
                      :db nil})  ; TODO: pass XTDB node

      ;; 2. Namespace-level render function (existing)
      (namespace-has-render? ns-sym)
      (call-namespace-render ns-sym :html session-id)

      ;; 3. Default introspection (existing)
      :else
      (render-introspection ns-sym session-id))))

```

### Phase 2: View Mode Detection

**File:** `src/seon/ns/routes.clj`

Detect view mode from query params or request context:

```clojure
(defn- detect-view-mode
  "Detect view mode from query params. Default is :full."
  [request]
  (let [params (parse-query-params request)
        mode (get params "view")]
    (case mode
      "tile" :tile
      "half" :half
      :full)))  ; default

```

### Phase 3: Schema Registration

**File:** `src/seon/ns/view.clj`

Add schemas for render requests:

```clojure
(schema/register! ::view-mode
  [:enum {:description "View size mode"}
   :tile :half :full])

(schema/register! ::render-request
  [:map
   [::view-mode ::view-mode]
   [::format ::format]
   [::id {:optional true} [:maybe ::id]]
   [::ctx {:optional true} :any]
   [::db {:optional true} :any]])

```

---

## Code References

| File | Line | Purpose |
|------|------|---------|
| `src/seon/ns/routes.clj` | 77-89 | `namespace-has-render?` + `call-namespace-render` |
| `src/seon/ns/routes.clj` | 91-188 | `render-namespace-content` fallback introspection |
| `src/seon/ns/view.clj` | 106-119 | `render*` multimethod dispatch |
| `src/seon/ai/agent.clj` | 586-646 | Example `render` function implementation |
| `src/seon/ns/introspect.clj` | - | Namespace introspection utilities |

---

## Constraints

- **No breaking changes** - Existing `render` functions must continue working
- **REPL-friendly** - Hot reload must work (clear cached render-fn on reload)
- **Security** - Only resolve render functions from `seon.*` namespaces
- **Performance** - Ctx lookup should be cheap (O(1) atom deref)

---

## Success Criteria

1. **Ctx registration works**

   ```clojure
   (swap! *ctx* assoc :seon.ui/render-fn 'seon.trading/custom-view)
   ;; Visit /ns/seon.trading -> custom view renders

   ```

2. **View modes passed through**

   ```
   GET /ns/seon.trading?view=tile -> render-fn receives {:view-mode :tile ...}
   GET /ns/seon.trading?view=half -> render-fn receives {:view-mode :half ...}
   GET /ns/seon.trading          -> render-fn receives {:view-mode :full ...}

   ```

3. **Fallback chain works**
   - Namespace with `:seon.ui/render-fn` in ctx → uses ctx renderer
   - Namespace with `render` fn but no ctx → uses namespace renderer
   - Namespace with neither → uses introspection

4. **Hot reload works**
   - Edit render function → page shows updated content after reload

---

## Test Criteria

```clojure
;; Test 1: Ctx render-fn detection
(deftest ctx-render-fn-detection-test
  (testing "resolves render-fn from ctx atom"
    ;; Setup: create namespace with ctx atom containing :seon.ui/render-fn
    ;; Assert: resolve-ctx-render-fn returns the function
    ))

;; Test 2: Fallback chain
(deftest fallback-chain-test
  (testing "ctx render-fn takes priority"
    ;; Namespace has both ctx render-fn AND public render fn
    ;; Assert: ctx render-fn is used
    )
  (testing "namespace render used when no ctx"
    ;; Namespace has public render fn, no ctx
    ;; Assert: namespace render fn is used
    )
  (testing "introspection when neither"
    ;; Namespace has neither
    ;; Assert: introspection view renders
    ))

;; Test 3: View modes
(deftest view-mode-detection-test
  (testing "parses view query param"
    (is (= :tile (detect-view-mode {:query-string "view=tile"})))
    (is (= :half (detect-view-mode {:query-string "view=half"})))
    (is (= :full (detect-view-mode {:query-string ""})))
    (is (= :full (detect-view-mode {})))))

```

---

## Deliverables

- [ ] `resolve-ctx-render-fn` function in `routes.clj`
- [ ] Updated `render-namespace-content` with fallback chain
- [ ] `detect-view-mode` function
- [ ] Schema registrations for `::view-mode` and `::render-request`
- [ ] Tests for ctx lookup, fallback chain, view mode detection
- [ ] Documentation in CLAUDE.md (if needed)

---

## Future Extensions

1. **DB connection passing** - Include XTDB node in render request
2. **SSE updates** - Custom renderers can return SSE-compatible handlers
3. **Tile registration** - Dashboard pulls tiles from registered namespaces
4. **Theme/layout support** - Custom renderers respect global theme settings
