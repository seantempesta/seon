# Plan: Unified Recursive Render Pipeline

## Context

Unify Seon's rendering into one spec-driven system. Namespaces declare data shapes and render functions via Malli specs. The system auto-discovers renderers, builds render context, and serves the right format per caller. Progressive enhancement: agents add renderers for specific shapes, immediately improving display everywhere.

## Design Decisions

1. **`::*ctx*` spec** → declaring this in a namespace means "spin up a ctx atom per instance." Contents validated by Malli watch on every swap!.
2. **`*ctx*` and `*conn*`** → injected as dynamic vars in the namespace. Functions access them by declaring these keys in their input specs.
3. **Companion `.render` namespaces** → use parent's specs directly (fully qualified). Scanner matches by spec keys, not function location.
4. **Page renderer detection** → a function is a page renderer iff its input spec contains a `*ctx*` key AND its output spec contains `:seon.render/html`. Detected by `link-fns-to-specs` in `extract.clj`, stored as `:seon.fn/page-renderer? true` in Datalevin. No explicit declarations anywhere.
5. **No naming hacks** → render function identity comes entirely from spec shape. No `ns-resolve` for magic names, no registration calls. The graph is the single source of truth.

## Full Example

### Data namespace: `seon.health.workout`

```clojure
(ns seon.health.workout
  (:require [seon.schema :as schema]
            [seon.web.components :as ui]))

;;; Data schemas
(schema/register! ::exercise [:string {:description "Exercise name"}])
(schema/register! ::sets     [:int {:min 1 :description "Number of sets"}])
(schema/register! ::reps     [:int {:min 1 :description "Reps per set"}])
(schema/register! ::weight   [:number {:min 0 :description "Weight in kg"}])

(schema/register! ::workout-set
  [:map [::exercise ::exercise] [::sets ::sets]
        [::reps ::reps] [::weight ::weight]])

(schema/register! ::workouts [:vector ::workout-set])

;;; Dynamic state spec — declaring this tells the system:
;;; "create a *ctx* atom per instance, validated against this shape"
(schema/register! ::*ctx*
  [:map
   [::workouts ::workouts]
   [::selected-exercise {:optional true} ::exercise]])

;;; Public data (available in ::render/ns-vars)
(def workouts
  [{::exercise "Squat"          ::sets 5 ::reps 5 ::weight 100}
   {::exercise "Bench Press"    ::sets 5 ::reps 5 ::weight 80}
   {::exercise "Deadlift"       ::sets 1 ::reps 5 ::weight 120}
   {::exercise "Overhead Press" ::sets 5 ::reps 5 ::weight 50}
   {::exercise "Barbell Row"    ::sets 5 ::reps 5 ::weight 70}])
```

### Render namespace: `seon.health.workout.render`

```clojure
(ns seon.health.workout.render
  "Render functions for seon.health.workout data.
   Uses parent namespace's specs directly — they're fully qualified."
  (:require [seon.schema :as schema]
            [seon.web.components :as ui]))

;;; Item renderer — static, no ctx needed
;;; Scanner detects this as a render fn because the response spec
;;; contains :seon.render/html and :seon.render/ai

(schema/register! ::workout-set-render-request
  [:map
   [:seon.health.workout/exercise :seon.health.workout/exercise]
   [:seon.health.workout/sets     :seon.health.workout/sets]
   [:seon.health.workout/reps     :seon.health.workout/reps]
   [:seon.health.workout/weight   :seon.health.workout/weight]])

(schema/register! ::workout-set-render-response
  [:map
   [:seon.render/html :any]
   [:seon.render/ai :string]])

(defn workout-set-render
  "Render a single workout set for all formats.

   Request keys:
     :seon.health.workout/exercise - Exercise name
     :seon.health.workout/sets     - Number of sets
     :seon.health.workout/reps     - Reps per set
     :seon.health.workout/weight   - Weight in kg

   Response keys:
     :seon.render/html - Hiccup table row
     :seon.render/ai   - Compact text summary"
  {:malli/schema [:=> [:cat ::workout-set-render-request]
                      ::workout-set-render-response]}
  [{:seon.health.workout/keys [exercise sets reps weight]}]
  {:seon.render/html
   [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
    [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} exercise]
    [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str sets)]
    [:td {:class "py-2 px-3 text-text-200 text-sm text-center"} (str reps)]
    [:td {:class "py-2 px-3 text-text-200 text-sm text-right"} (str weight " kg")]]
   :seon.render/ai
   (str exercise " — " sets "x" reps " @ " weight "kg")})

;;; Page renderer — uses ctx atom for live data
;;; Scanner detects this as the PAGE renderer because its input spec
;;; includes :seon.health.workout/*ctx* — a key from ::ns-data

(schema/register! ::page-render-request
  [:map
   [:seon.health.workout/*ctx* :seon.health.workout/*ctx*]])

(schema/register! ::page-render-response
  [:map
   [:seon.render/html :any]
   [:seon.render/ai :string]])

(defn page-render
  "Page renderer for seon.health.workout.
   Receives the ctx atom value (deref'd). Composes workout-set-render.

   Request keys:
     :seon.health.workout/*ctx* - Current ctx atom value

   Response keys:
     :seon.render/html - Full page hiccup
     :seon.render/ai   - Text summary"
  {:malli/schema [:=> [:cat ::page-render-request] ::page-render-response]}
  [{workout-ctx :seon.health.workout/*ctx*}]
  (let [workouts (:seon.health.workout/workouts workout-ctx)]
    {:seon.render/html
     [:main#morph
      [:div {:class "mb-4"}
       [:h1 {:class "text-lg font-semibold tracking-tight font-mono"}
        "seon.health.workout"]
       [:p {:class "text-text-400 text-sm mt-2"}
        "Workout tracking. " (count workouts) " exercises."]]
      [:section {:class "mb-6"}
       (ui/section-header "TODAY'S WORKOUT")
       [:div {:class "bg-base-850 rounded overflow-hidden"}
        [:table {:class "w-full"}
         [:thead
          [:tr {:class "border-b border-base-700"}
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Exercise"]
           [:th {:class "text-center py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "Sets"]
           [:th {:class "text-center py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "Reps"]
           [:th {:class "text-right py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-24"} "Weight"]]]
         [:tbody
          (map workout-set-render workouts)]]]]]
     :seon.render/ai
     (str "Workout: " (count workouts) " exercises. "
          (clojure.string/join ", "
            (map #(:seon.render/ai (workout-set-render %)) workouts)))}))
```

### What the system does when browser hits `/ns/seon.health.workout`

```
1. Route negotiates format: Accept: text/html → :html

2. Build ::ns-data map:
   - Introspect namespace → functions, specs, vars, atoms
   - Check Datalevin for page renderer for this namespace
   - Found: page-render with input key :seon.health.workout/*ctx*
   - *ctx* key detected → this namespace is dynamic

3. Instance lifecycle:
   - Check ?instance= param. If absent, find default or create new.
   - Create ctx atom initialized from ::*ctx* spec shape:
     {::workouts [...default...], ::selected-exercise nil}
   - Inject *ctx* atom into namespace as dynamic var
   - Inject *conn* as namespace Datalevin connection
   - Add Malli validation watch on ctx atom

4. Build render input:
   {:seon.health.workout/*ctx* @*ctx*    ;; deref'd value
    :seon.render/ns-vars {...}
    :seon.render/format :html}

5. Call page-render → returns {:seon.render/html [:main#morph ...]}

6. Wrap in base-page, serve via SSE
   - Watch on *ctx* atom → re-render on change → patch browser
   - Dev hook reload → re-render → patch browser
```

### What curl gets

```bash
curl http://localhost:8080/ns/seon.health.workout?format=ai
# → "Workout: 5 exercises. Squat — 5x5 @ 100kg, Bench Press — 5x5 @ 80kg, ..."

curl http://localhost:8080/ns/seon.health.workout?format=raw
# → {:seon.health.workout/workouts [{:seon.health.workout/exercise "Squat" ...} ...]}
```

### What an AI agent gets (REPL)

```clojure
(seon.render/render-namespace {::render/ns-data (build-ns-data 'seon.health.workout :ai)})
;; → "Workout: 5 exercises. Squat — 5x5 @ 100kg, ..."
```

---

## Implementation Steps

### Step 1: Render core

**File: `src/seon/render.clj`**

New specs: `::ns-data`, `::render-namespace-request`, `::render-namespace-response`

New functions:
- `for-html` — recursive HTML rendering (mirrors `for-ai`)
- `render-namespace` — find page renderer or use default, extract format
- `default-namespace-render` — generic catchall using `for-html`/`for-ai`
- `find-page-renderer` — query Datalevin for render fn whose input keys overlap with ns-data keys

Reuse: `src/seon/ns/introspect.clj:introspect` for building namespace data.

### Step 2: Scanner enhancements

**File: `src/seon/graph/scanner.clj`**

After `link-fns-to-specs`:
- `detect-page-renderers` — find render fns whose input keys overlap with `::ns-data` structure. Mark `:seon.fn/page-renderer? true`
- `detect-dynamic-needs` — check for `*ctx*` or `*conn*` suffix in render input keys. Store `:seon.fn/needs-ctx?`, `:seon.fn/needs-conn?`
- `detect-ctx-specs` — find `::*ctx*` spec registrations. Mark namespace as dynamic in Datalevin.

### Step 3: Routes — content negotiation + ns-data

**File: `src/seon/ns/routes.clj`**

- `negotiate-format` — `?format=` > `Accept` header > default `:ai`
- `build-ns-data` — introspect namespace, check for dynamic needs, create/find instance if needed
- Format dispatch: `:html` → SSE, `:ai` → text/plain, `:raw` → application/edn
- Backward compat: try new system first, fall back to existing

### Step 4: Workout refactor

**File: `src/seon/health/workout.clj`**
- Keep data schemas and `workout-set-render` (move to `.render` namespace or keep inline — both work)
- Add `workout-render` page renderer (or in `.render` companion)
- Add `::*ctx*` spec for dynamic state
- Remove old `render` fn
- Make `workouts` public

**File: `src/seon/health/workout.render` (optional companion)**
- If we create it: contains `workout-set-render` and `page-render`
- If not: keep in parent namespace. Both work identically.

### Step 5: Graph ingestion — connect scanner to Datalevin (DONE)

**Status: Complete. The render pipeline discovers functions via Datalevin.**

The wiring was already in place (startup scanner + dev hook incremental ingestion) but two bugs prevented it from working:
1. `render_test.clj` set a test connection override via `set-conn!` but never cleaned it up, causing `render.clj`'s `get-conn` to return a stale empty local DB instead of the system connection.
2. `ingest-incremental!` transacted specs AFTER functions, but functions reference specs via lookup refs, causing "Nothing found for entity id" errors during incremental ingestion.

**Fixes applied:**
- Test fixtures in `render_test.clj` and `workout_test.clj` now call `(render/set-conn! nil)` in `finally` blocks to clean up the override.
- `ingest-incremental!` now transacts specs BEFORE functions, matching `ingest-analysis!`'s order.

**What exists:**
- `scanner/scan-source` now detects `defn` forms (returns fn entities alongside spec entities)
- `scanner/link-fns-to-specs` marks render fns with `:seon.fn/render-input-keys`
- `ingest/ingest-file!` does scan + link + transact for a single file
- `render/find-renderer` queries Datalevin for matching render fns
- `render/for-html` and `for-ai` call `find-renderer` recursively for each map

**What's needed:**
1. **Get the Datalevin connection correctly.** The system key is `:seon.db.datalevin/connections` (a map), NOT `:seon.db/conn`. Check `src/seon/system.clj` and `src/seon/db/datalevin.clj` for how to get the graph DB connection. The `render.clj` functions use `get-conn` internally — check how THAT works and make `ingest-file!` use the same approach.
2. **Ingest on startup.** Scan `src/seon/` directory and ingest all files into Datalevin when the system starts. Could be an Integrant component (`:seon.graph/scanner` already exists in the system — check what it does).
3. **Ingest on reload.** After the dev hook reloads code, re-scan the changed file(s) and re-ingest. The hook knows which files changed.
4. **Verify end-to-end:** After ingestion, `curl localhost:8080/ns/seon.health.workout?format=ai` should show workout-specific rendering (not generic map printing).

**Key files to read:**
- `src/seon/system.clj` — find `:seon.graph/scanner` component and `:seon.db.datalevin/connections`
- `src/seon/db/datalevin.clj` — how connections are managed
- `src/seon/render.clj` — `get-conn` function (how render.clj finds its connection)
- `src/seon/graph/ingest.clj` — `ingest-file!` and `ingest-analysis!`
- `src/seon/dev/hook.clj` — post-reload callbacks

### Step 6: Graph freshness (polish)

- Cache invalidation after re-scan
- SSE hash change detection → browser patch

---

## Files to Modify

| File | Change |
|------|--------|
| `src/seon/render.clj` | Specs, `for-html`, `render-namespace`, default renderer |
| `src/seon/graph/scanner.clj` | Page renderer detection, dynamic needs, ctx spec detection |
| `src/seon/ns/routes.clj` | Content negotiation, ns-data construction, format dispatch |
| `src/seon/health/workout.clj` | `::*ctx*` spec, `workout-render`, remove old `render` |
| `test/seon/health/workout_test.clj` | Updated tests |
| `test/seon/render_test.clj` | `for-html`, `render-namespace` tests |

---

## Verification

```bash
# Browser: custom page, live updates via SSE
open https://localhost:3030/ns/seon.health.workout

# AI: text summary
curl http://localhost:8080/ns/seon.health.workout?format=ai

# Generic namespace: default renderer
curl http://localhost:8080/ns/seon.schema?format=ai

# Raw EDN
curl http://localhost:8080/ns/seon.health.workout?format=raw
```

```clojure
;; REPL: direct access
(seon.render/render-namespace {::render/ns-data ns-data})
(seon.render/for-html arbitrary-data-map)
(seon.render/for-ai arbitrary-data-map)

;; Agent modifies ctx → browser updates
(swap! seon.health.workout/*ctx*
       update ::workout/workouts conj
       {::workout/exercise "Pull-up" ::workout/sets 3 ::workout/reps 10 ::workout/weight 0})
;; → SSE detects change → browser re-renders
```
