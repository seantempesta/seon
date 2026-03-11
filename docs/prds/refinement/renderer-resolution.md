# Design: Renderer Resolution

**Status:** Implemented (core algorithm + web param namespacing + default page template + type-aware rendering + tests)
**Date:** 2026-02-23, updated 2026-02-24
**Context:** Replaces the heuristic in `render-pipeline.md` section 4 ("Page renderer detection")

---

## The Problem

How does the system know which function should render a namespace's page? The old answer — "input spec has `*ctx*` key AND output spec has `:seon.render/html`" — is a heuristic that creates ambiguity:

- A function taking `*ctx*` might not be a renderer (it could be business logic)
- Multiple functions could match the criteria
- No way to handle static rendering (no `*ctx*`, just `def` vars)
- No clear path for cross-namespace rendering

We need a design that is **unambiguous**, **progressive**, and **automatic**.

---

## Core Principle: Specs + Data Sources

**A spec is a contract. A renderer needs a contract AND a data source.**

The spec says "I can handle this shape." But without data flowing in, it's just a potential. A renderer becomes active when the system can match its input spec against available data.

**A renderer is any function whose output spec contains `:seon.render/html`.**

That's the only renderer detection rule. No metadata flags, no naming conventions. The output spec is the declaration.

---

## Data Sources

Data has to come from somewhere. These are the sources the system knows about, ordered by specificity (strongest signal that you're rendering a page → weakest):

| Source | Key pattern | Description | Injection |
|--------|-------------|-------------|-----------|
| **Web params** | `:ns/sort-by`, `:ns/page` | GET/POST params from browser | Auto-namespaced from `?key=value` |
| **`*ctx*` atom** | `:ns/*ctx*` | Per-instance reactive state | Auto-deref'd and injected |
| **`*conn*` Datalevin** | `:ns/*conn*` | Namespace-shared database | Auto-injected from system |
| **`def` vars** | Matches registered specs | Static namespace data | Matched by spec shape |
| **Generated** | From spec | No real data, proof-of-life | `mg/generate` from schema |

A function that says "I need web params + `*ctx*` + `*conn*`" is clearly a full page renderer. A function that only needs two specific data keys is a component renderer. **The number and type of data sources a function requires determines its role.**

---

## Auto-Injection: The Agent Writes Pure Functions

The critical design decision: **agents never wire data sources themselves.** They declare what they need via input spec. The system provides it.

```clojure
;; The agent writes this. It never touches atoms, connections, or HTTP.
(defn page-render
  {:malli/schema [:=> [:cat ::page-render-request] ::page-render-response]}
  [{workout-ctx :seon.health.workout/*ctx*
    sort-by     :seon.health.workout/sort-by}]  ;; ← optional web param
  {:seon.render/html [:main#morph ...]
   :seon.render/ai   "..."})
```

The system sees the input spec and auto-injects:

- `:seon.health.workout/*ctx*` → deref the ctx atom
- `:seon.health.workout/sort-by` → extract from `?sort-by=` query param

The function is pure: map in, map out. Testable without HTTP, atoms, or databases.

---

## Web Parameter Injection

Query params on `/ns/<namespace>` are auto-namespaced:

```
GET /ns/seon.health.workout?sort-by=weight&page=2

Available keys:
  :seon.health.workout/sort-by  →  "weight"
  :seon.health.workout/page     →  "2"
```

**Rule:** `?<key>=<value>` on `/ns/<namespace>` produces `:<namespace>/<key>` in the available data.

**System-reserved params** (never injected as namespace data):

- `?instance=` → routing to specific ctx instance
- `?format=` → content negotiation (html/ai/raw)
- `?view=` → display mode (introspect)

Everything else belongs to the namespace. An agent that wants pagination just registers the spec:

```clojure
(schema/register! ::page [:int {:min 1 :description "Page number"}])
(schema/register! ::per-page [:int {:min 1 :max 100 :description "Items per page"}])
```

And adds them as optional keys to the input spec. Malli validates the values before the function sees them — `?page=garbage` is rejected automatically.

POST body params (from Datastar `@post(url, {contentType:'form'})`) use qualified keyword `name` attrs — parsed and merged into the available data. GET function calls (`GET /ns/:ns/:fn?key=value`) also auto-namespace params.

---

## Resolution Algorithm

When the browser hits `/ns/seon.health.workout`:

```
1. COLLECT available data:
   - Web params: {::workout/sort-by "weight"} (from query string)
   - *ctx* value: {::workout/workouts [...], ::workout/selected-exercise nil}
   - *conn*: <datalevin-connection>
   - def vars: workout/workouts = [{...} ...]

2. BUILD available keys set:
   #{:seon.health.workout/*ctx*
     :seon.health.workout/*conn*
     :seon.health.workout/sort-by
     :seon.health.workout/workouts
     :seon.health.workout/selected-exercise}

3. FIND candidate renderers:
   All functions where:
   - Output spec contains :seon.render/html
   - ALL required input spec keys ⊆ available keys

4. RANK by specificity:
   - Count of required input keys (more = more specific = better)
   - Ties broken by namespace proximity:
     same ns > .render child ns > sibling ns > distant ns

5. CALL winner with auto-injected data map

6. SERVE result (HTML via SSE, or text/edn for other formats)
```

**If zero candidates:** fall back to namespace introspection view (always available).
**If multiple candidates at same specificity:** scanner warns at scan time (ambiguity = bug).

---

## Progressive Rendering Levels

These levels emerge naturally from the algorithm. No special code per level.

### Level 0: Namespace Introspection (always available)

No custom renderer needed. Shows vars, functions, schemas, atoms.
Every namespace gets this for free.

### Level 1: Data Shape Renderers

A function renders specific data shapes (e.g., a single workout set).
Within the introspection view, data matching this shape uses the custom renderer instead of raw EDN.

```clojure
;; Renders any map with these 4 keys:
(defn workout-set-render
  {:malli/schema [:=> [:cat ::workout-set-request] ::workout-set-response]}
  [{::workout/keys [exercise sets reps weight]}]
  {:seon.render/html [:tr ...]
   :seon.render/ai   (str exercise " — " sets "x" reps " @ " weight "kg")})
```

Works with static `def` data. No instance, no atom, no runtime.

### Level 2: Ctx Page Renderer

A function takes the whole `*ctx*` atom value. Takes over the full page view.

```clojure
;; Renders the full page using ctx state:
(defn page-render
  {:malli/schema [:=> [:cat ::page-render-request] ::page-render-response]}
  [{workout-ctx ::workout/*ctx*}]
  {:seon.render/html [:main#morph ...] :seon.render/ai "..."})
```

The system spins up an instance, creates the ctx atom, and auto-injects.

### Level 3: Ctx + Web Params

Same as Level 2, but the function also accepts query/POST params.

```clojure
(schema/register! ::page-render-request
  [:map
   [::workout/*ctx* ::workout/*ctx*]
   [::workout/sort-by {:optional true} ::workout/sort-by]
   [::workout/show-history? {:optional true} :boolean]])
```

More input keys = more specific = wins over plain ctx renderer.

### Level 4: Conn-Backed Renderer (future)

Function takes `*conn*` for database queries. Same injection pattern.
When `*conn*` becomes reactive (watches on Datalevin), this enables
database-driven reactive rendering.

---

## Cross-Namespace Rendering

Any renderer can declare it renders data from ANY namespace:

```clojure
;; In seon.calendar namespace:
(schema/register! ::day-view-request
  [:map
   [:seon.calendar/*ctx* :seon.calendar/*ctx*]
   ;; This renderer can also show workout data:
   [:seon.health.workout/workouts {:optional true} :seon.health.workout/workouts]])
```

When the calendar page has workout data available, this renderer matches.
If the workout namespace has its own renderer for the same keys, specificity
(key count) determines who wins for that context.

---

## Specificity Example

Given data: `{::workout/exercise "Squat" ::workout/sets 5 ::workout/reps 5 ::workout/weight 100}`

| Renderer | Required keys | Candidate? | Key count |
|----------|--------------|------------|-----------|
| A: page-render | `{::workout/*ctx*}` | NO — `*ctx*` not in data | — |
| B: workout-set-render | `{::exercise, ::sets, ::reps, ::weight}` | YES — all 4 present | **4 (wins)** |
| C: exercise-summary | `{::exercise, ::sets}` | YES — both present | 2 |

Given data: `{::workout/exercise "Squat" ::workout/sets 5}` (only 2 keys):

| Renderer | Required keys | Candidate? | Key count |
|----------|--------------|------------|-----------|
| B: workout-set-render | `{::exercise, ::sets, ::reps, ::weight}` | NO — `::reps`, `::weight` missing | — |
| C: exercise-summary | `{::exercise, ::sets}` | YES — both present | **2 (wins)** |

The more specific renderer always wins. If a renderer requires keys that aren't available, it's not a candidate. No ambiguity.

---

## Static Rendering (def vars)

A namespace with only `def` vars and no `*ctx*` still gets custom rendering:

```clojure
(ns seon.health.workout)
(def workouts [{::exercise "Squat" ::sets 5 ::reps 5 ::weight 100} ...])
```

The introspection view iterates the namespace's public vars. For each var value:

1. Check if the value is a map with namespaced keys (or a collection of such maps)
2. Find the best-matching renderer for that shape
3. Use it instead of raw EDN display

No instance needed. No runtime overhead. An agent writes `def` vars with sample data and immediately gets rendered output.

---

## What the Graph Stores

The scanner extracts and stores in Datalevin:

```
For each function with :seon.render/html in output spec:
  :seon.fn/qualified-name     "seon.health.workout.render/page-render"
  :seon.fn/render-input-keys  #{:seon.health.workout/*ctx*}  ;; required keys
  :seon.fn/render-optional-keys #{:seon.health.workout/sort-by}  ;; optional keys
  :seon.fn/page-renderer?     true  ;; derived: has *ctx* key in input

For each namespace:
  :seon.ns/name               "seon.health.workout"
  :seon.ns/dynamic?           true  ;; has ::*ctx* spec
  :seon.ns/data-sources       #{:ctx :conn :def-vars}  ;; what's available
```

The resolution query:

```datalog
[:find ?qname ?key-count
 :in $ ?available-keys
 :where
 [?e :seon.fn/render-input-keys ?required-keys]
 ;; all required keys are in available keys
 [(clojure.set/subset? ?required-keys ?available-keys)]
 [?e :seon.fn/qualified-name ?qname]
 [(count ?required-keys) ?key-count]]
;; order by ?key-count DESC, pick first
```

---

## Implementation Changes

| File | Change |
|------|--------|
| `src/seon/graph/extract.clj` | Extract optional vs required input keys separately. Store `:seon.fn/render-optional-keys`. |
| `src/seon/ns/lifecycle.clj` | Collect available data from all sources. Build available keys set. |
| `src/seon/ns/routes.clj` | Auto-namespace query params. Merge all data sources. Call resolution algorithm. Shared `resolve-and-call` for GET/POST with Malli coercion. |
| `src/seon/render.clj` | `resolve-renderer` — the specificity algorithm. Used for both page and component rendering. |

### What stays the same

- `ctx.clj` — ctx atom creation, watches, persistence (unchanged)
- `transform.clj` — hiccup transformation (updated: uses keyword `name` attrs, no encoding layer)
- `sse.clj` — SSE infrastructure (unchanged)
- Workout namespace code — already structured correctly

---

## What's Built

### Core Resolution (`src/seon/render.clj`)

- **`resolve-renderer`** — Full specificity algorithm: finds functions with `:seon.render/html` in output spec, filters by available keys subset, ranks by key count, tiebreaks by namespace proximity.
- **`find-renderer`** — Datalevin-based renderer lookup with caching (`resolution-cache`). Cache invalidated on scanner rescan.
- **`find-page-renderer`** — Page-level resolution: finds renderer with most key overlap against ns-data.
- **`namespace-web-params`** — Auto-namespaces `?key=value` query params under target namespace. System-reserved params (`instance`, `format`, `view`) excluded.
- **`render-namespace`** — Main entry point: finds best page renderer via Datalevin, calls it, extracts format key from result. Falls back to `default-namespace-render`.

### Humanization & Schema Rendering (`src/seon/render.clj`)

- **`humanize`** — Transforms keywords/strings to human-readable labels. Strips namespace, converts kebab-case to Title Case, handles special abbreviations (ID, URL, SSE, API, etc.), strips asterisks from `*ctx*`-style names.
- **`render-schema`** — Renders Malli `:map` schemas as HTML tables with Field/Type/Required columns. Non-map schemas render as type badges. Resolves Malli types to human labels (`:string` -> "Text", `:int` -> "Number", `:enum` -> "One of: ...").
- **`for-html`** — Recursive HTML rendering: detects Malli schema forms and uses `render-schema`; vectors-of-maps become proper HTML tables with humanized headers; maps become definition-list tables; primitives get type-appropriate styling.

### Default Page Template (`src/seon/render/default_page.clj`)

- **`render-default-page`** — Two-panel layout for any namespace with `*ctx*` but no custom renderer:
  - LEFT panel: Markdown narrative (`:seon.render.default/narrative` key, rendered via `markdown.core`)
  - RIGHT panel: Auto-rendered data keys from ctx, using `render/try-render` for custom renderers and type-specific fallbacks:
    - Numbers: stat cards (large bold number + label)
    - Strings: prose rendering (multiline -> `<pre>`, single line -> `<p>`)
    - Everything else: `render/for-html` (handles schemas, maps, vectors-of-maps)
  - BOTTOM: Chat input stub with message history
  - TOP: Namespace name + introspect link
- View Transitions via `style="view-transition-name: ..."` on panels (works with Datastar's `useViewTransition`)
- Schema registrations for chat-related keys: `::ctx/messages`, `::ctx/uploads`, `::ctx/user-input`

### What's NOT Built Yet

- **Level 1 (data shape renderers)** — Introspection view doesn't yet use shape-matched renderers for individual var values
- **Cross-namespace rendering** — Design exists but no implementation
- **`?format=ai` end-to-end** — `render-namespace` supports it but route handler doesn't fully wire it
- **POST FormData keyword parsing** — Implemented (qualified keyword `name` attrs), needs E2E browser test
- **`*conn*` injection** — Route handler doesn't inject Datalevin connections into renderer input maps yet

## Verification Plan

### From browser

```
1. http://localhost:8080/ns/seon.health.workout
   → Redirects to ?instance=xxxx
   → Renders workout table via page-render (Level 2)
   → SSE pushes updates on ctx mutation

2. http://localhost:8080/ns/seon.health.workout?sort-by=weight
   → page-render receives sort-by in input map (Level 3)

3. http://localhost:8080/ns/seon.schema
   → No custom renderer → introspection view (Level 0)
```

### From AI/curl

```bash
curl http://localhost:8080/ns/seon.health.workout?format=ai
# → "Workout: 5 exercises. Squat — 5x5 @ 100kg, ..."

curl http://localhost:8080/ns/seon.health.workout?format=raw
# → {:seon.health.workout/workouts [{...} ...]}
```

### From REPL (reactive)

```clojure
;; Mutate ctx → browser updates without refresh
(swap! seon.health.workout/*ctx*
       update :seon.health.workout/workouts conj
       {:seon.health.workout/exercise "Pull-up"
        :seon.health.workout/sets 3
        :seon.health.workout/reps 10
        :seon.health.workout/weight 0})
```

### Static rendering

```clojure
;; A namespace with just def vars, no *ctx*:
;; The introspection view uses workout-set-render for each
;; var whose value matches the ::workout-set spec shape.
```
