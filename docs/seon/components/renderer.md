---
type: component
status: production
tags: [component, web]
---
# Renderer

> Discovers and dispatches render functions by matching data shape against function specs stored in the code graph.

## Purpose

The renderer is Seon's **discovery mechanism**. Rather than registering renderers explicitly, functions declare their input/output schemas via Malli metadata. The [[components/code-graph]] scanner ingests these into Datahike. At render time, the renderer queries the graph to find the function whose required input keys best match the data at hand. This pattern means any namespace can provide rendering for any data shape — no coordination required.

Two output formats flow through the system: `:seon.render/html` (hiccup) and `:seon.render/ai` (concise string). A render function returns a map containing one or both keys, and the caller picks what it needs.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.render` | `src/seon/render.clj` | Core resolution, caching, recursive renderers, schema rendering |
| `seon.render.default-page` | `src/seon/render/default_page.clj` | Fallback two-panel page for dynamic namespaces without custom renderers |
| `seon.render.code` | `src/seon/render/code.clj` | Code/docs rendering from graph: compatible-functions, render-ns-docs, context-for-agent |
| `seon.render.example` | `src/seon/render/example.clj` | Example: position renderer demonstrating the convention |
| `seon.ns.view` | `src/seon/ns/view.clj` | Multimethod-based view system dispatching on `[format view-type]` metadata |
| `seon.render.sci` | `src/seon/render/sci.cljs` | Wall-clock-bounded SCI invocation of agent-authored live-tile fns (`invoke-bounded`) |

## Public API Surface

### Resolution Functions (the core three)

| Function | Purpose | When to use |
|----------|---------|-------------|
| `find-renderer` | Find best renderer by data keys + format. Returns qualified-name string. | Low-level: when you need the name, not the var |
| `resolve-renderer` | Find best renderer by available-keys + target-ns. Returns resolved var. | Page rendering: specificity + namespace proximity tiebreak |
| `find-page-renderer` | Find renderer with most key overlap against ns-data. Returns qualified-name. | Namespace page rendering (looser match — at least one key overlap) |

**Key difference:** `find-renderer` requires ALL required keys present in data. `find-page-renderer` only requires at least one overlapping key. `resolve-renderer` adds namespace proximity as a tiebreaker and returns the resolved var directly.

### Rendering Functions

| Function | Signature | Returns |
|----------|-----------|---------|
| `render` | `(render value format)` | Format-appropriate output with graph lookup against Datahike + fallback |
| `try-render` | `(try-render data format)` | Rendered value or nil (no fallback) |
| `for-ai` | `(for-ai v)` | Recursive string rendering for AI agents |
| `for-html` | `(for-html v)` | Recursive hiccup rendering with smart type dispatch |
| `render-namespace` | `(render-namespace {::ns-data ... ::format ...})` | Full namespace rendering pipeline |
| `render-schema` | `(render-schema schema-form)` | Malli schema as human-readable hiccup table |
| `render-seq` | `(render-seq values format)` | Map render over sequence |

### Utilities

| Function | Purpose |
|----------|---------|
| `typed` | Attach `:seon/schema` metadata to a value |
| `schema-of` | Extract `:seon/schema` from value metadata |
| `humanize` | Keyword/string to human-readable label ("api-key" -> "API Key") |
| `namespace-web-params` | Auto-namespace query params ("?sort-by=weight" on `/ns/seon.health.workout` -> `{:seon.health.workout/sort-by "weight"}`) |
| `has-renderer?` | Boolean check for renderer existence |
| `invalidate-render-cache!` | Clear resolution + output-key caches (called by scanner after graph update) |

## Dependencies

**Uses:**

- [[components/code-graph]] — `gq/functions-with-output-key` is the discovery query that powers all resolution
- [[components/database]] — `seon.db.datahike.conn-process` for the embedded Datahike conn (render cache queries)
- [[components/schema-system]] — `seon.schema` for schema registration
- `seon.runtime` — merged schema

**Used by:**

- [[components/namespace-lifecycle]] — `lifecycle/find-page-render-fn` delegates to graph query, `make-render-fn` wraps result
- `seon.ns.routes` — calls `resolve-renderer`, `find-page-renderer`, `for-html`, `namespace-web-params`
- `seon.render.default-page` — uses `try-render`, `humanize`, `for-html` for fallback page
- `seon.render.code` — uses `namespace-proximity` for doc renderer resolution

## How Data Flows

### Specificity Algorithm (the heart of renderer resolution)

```
1. Query graph: functions-with-output-key {:seon.render/html}
   -> Returns candidates with :required-keys and :optional-keys computed from input specs

2. Filter: candidate's required-keys must be SUBSET of available data keys
   (find-renderer: strict subset; find-page-renderer: at least one overlap)

3. Rank by specificity:
   a. Most required keys matched (more specific = better)
   b. Namespace proximity tiebreak (same ns=0, .render child=1, sibling=2, distant=3)
   c. Alphabetical qualified-name (deterministic final tiebreak)

4. requiring-resolve the winner -> call it -> extract format key from result

```

### Resolution Cache

The cache key is `[format (set (keys data))]` — format + key shape. Cached results include the resolved var or `::no-renderer` sentinel. Cache is invalidated when:

- `invalidate-render-cache!` is called (triggered by code graph scanner after rescan)
- This also calls `gq/invalidate-output-key-cache!` to clear the upstream query cache

### Render Dispatch (in `render`)

```
1. If value is a map and format is :html or :ai -> try graph resolution against Datahike
   (internally: call-datahike-renderer, which uses resolve-renderer-from-datahike)
2. If a renderer is found -> return its output
3. Fallbacks:
   - :raw -> return value as-is
   - :human -> pprint-clipped (500 chars)
   - :ai -> pprint-clipped (500 chars)
   - :html -> [:pre [:code (pprint-clipped value)]]

```

Note: `for-ai` and `for-html` also use `call-datahike-renderer` when recursing into maps, so they share the same resolution path but with additional recursive fallback rendering for non-domain data.

### for-ai / for-html (recursive renderers)

Both recursively walk data structures. At each map, they attempt `call-datahike-renderer` first. If no renderer exists:

- `for-ai`: produces `{key1 val1, key2 val2}` string notation
- `for-html`: renders maps as definition-list tables, vectors-of-maps as full tables with humanized headers, sequences as `<ul>` lists, Malli schema forms as field-spec tables via `render-schema`

### How a Render Function Gets Discovered

A function becomes discoverable by following the naming convention:

```clojure
;; In seon.render.example:
(defn position-render
  "Render a position for all formats."
  {:malli/schema [:=> [:cat ::position-render-request] ::position-render-response]}
  [{::keys [ticker quantity price]}]
  {:seon.render/html [:div.position-card ...]
   :seon.render/ai (str ticker " x" quantity)})

```

The scanner sees `:seon.render/html` in the output spec's contains-keys -> stores in graph -> `functions-with-output-key` finds it -> resolution matches by input keys.

Namespace proximity tiebreaking ensures that `seon.health.workout.render/workout-view` is preferred over `seon.getting-started.render/generic-view` when rendering workout data, even if both match.

### Bounded agent live-tiles (SCI safety net)

An agent customizes its panel by wiring `:seon.render.live-canvas/content` to a fn symbol; `render-agent-tile` invokes it. AGENT-authored tile fns (ns not `seon.*` / `clojure.*` / `cljs.*`) now run through `seon.render.sci/invoke-bounded`, which interprets the fn under SCI with a wall-clock `:interrupt-fn`. A non-terminating tile (`(loop [] (recur))`, `(while true)`, runaway interpreted recursion) ABORTS in-process at the deadline instead of freezing the single pod thread. The core `welcome`, core section fns, and literal hiccup stay on the fast compiled `html-render` path untouched — only agent-authored symbols go through SCI. Behind env `SEON_TILE_SCI` (default on; `=0` disables).

**Pure safety net, never brittle.** ONLY the wall-clock interrupt triggers recovery. ANY other SCI outcome — no stored source, an env-reconstruction gap, a genuine throw — falls back to the compiled `html-render` path. `invoke-bounded` is outer-guarded so it never throws. Bounding can only ever CATCH HANGS; it never turns a working tile into an error and never crashes the pod.

**Legible recovery, always a nice tile.** On a HANG, reset the tile to `welcome` (retract `:seon.render.live-canvas/content`) and post the agent ONE deduped force'd message. On a THROW, show the calm "Updating this panel" card (not a scary error), KEEP the content (so a fix takes effect), and notify the agent once (deduped).

**Bounding requires indexed source.** SCI bounds by INTERPRETING the tile fn's SOURCE, so bounding only applies when the source is available. Runtime agent-authored fns have it (teed to `:seon.fn/source`). A fn COMPILED from a source file is bounded only when it has been INDEXED (has a `:seon.fn/source` row) — otherwise it renders on the UNBOUNDED compiled path. See [[components/extra-src]] for how downstream compiled fns get indexed (and thus bounded). The lexical env (the ns's `:require` `:as` aliases / `:refer` / `:refer :all` + own-ns helpers) is reconstructed from the DB so aliased tiles resolve under SCI.

**Caveats.** Covers INTERPRETED loops/recursion (the reproduced freeze). NOT covered (needs the deferred Layer-2 killable worker): a tile that calls a native host loop (compiled CLJS / JS `while(true)`) or a native regex (ReDoS) — the interrupt only fires on interpreted code. `:advanced` is unsupported (Closure DCE/inlining); `:simple` release is not yet validated (the pod runs dev `:none`). See the PRD [[prds/agent-runtime/tile-isolation-prd-2026-06-21]] and the validation/test doc [[prds/agent-runtime/sci-interrupt-validation-2026-06-21]].

## Design Decisions

1. **No registration ceremony.** Functions are discovered purely by their Malli schema metadata. Write a function with `:seon.render/html` in output spec, and the system finds it.

2. **Key-shape matching, not type dispatch.** Resolution matches on the *set of keys* present in data, not on a type tag. This means the same data can match different renderers depending on which keys are available.

3. **Two resolution paths coexist.** `seon.render` does graph-backed key-shape matching against Datahike. `seon.ns.view` does multimethod dispatch on `[format view-type]` metadata. The view system is used for generic value rendering (numbers, strings, atoms); the render system is used for domain-specific rendering.

4. **Cache keyed on key-shape, not data.** Two maps with the same keys always resolve to the same renderer, regardless of values. This makes the cache effective.

5. **Namespace proximity as tiebreaker.** When two renderers match equally on key count, the one closer in namespace hierarchy wins. This allows domain namespaces to override generic renderers.

## Refactoring Opportunities

- **`::html` registered as `:any`** — `(schema/register! ::html :any)` is a code smell per project conventions. The html output should have a more specific type (hiccup vector or string).
- **`seon.render` and `seon.ns.view` overlap** — both provide multi-format rendering with different dispatch mechanisms. The view system (`render*` multimethod) handles generic Clojure values; the render system (graph-backed resolution against Datahike) handles domain data. These could be unified under one resolution path.
- **`routes.clj` passes a raw Datahike conn to `resolve-renderer`** but `resolve-renderer`'s signature expects a `db-name` keyword (same as `find-renderer`). The function signatures are consistent — `db-name` for both — but the caller in `routes.clj` (line 486) passes a conn object. This is an API contract violation at the call site, not a signature difference.
- **`default-page/render-default-page` has no `:malli/schema`** and no test file.
- **`render.clj` routes.clj coupling** — routes.clj does string manipulation (`str/replace` on HTML) to inject toggle buttons into rendered output. This should be part of the rendering pipeline.
