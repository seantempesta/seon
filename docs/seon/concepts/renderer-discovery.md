---
type: concept
status: production
---
# Renderer Discovery

> Functions declare their output format in schema metadata. The scanner indexes them. Resolution matches data shape to renderer by specificity.

## The Pattern

Renderer discovery is a zero-registration pattern for connecting data to visualization. A render function declares what data it needs (input keys) and what it produces (`:seon.render/html` or `:seon.render/ai` in the output schema) via standard `:malli/schema` metadata. The [[components/code-graph]] scanner extracts this metadata and indexes it in Datalevin. At render time, a specificity algorithm finds the best match.

The resolution algorithm:
1. **Find candidates** — query Datalevin for functions whose output schema contains the target format key (e.g., `:seon.render/html`)
2. **Filter** — candidate's required input keys must be a subset of the available data keys
3. **Rank by specificity** — most required keys matched wins (more specific = better)
4. **Tiebreak** — namespace proximity (same ns > `.render` child > sibling > distant), then recency, then alphabetical

No registration step. No dispatch maps. Just author the function with the right schema, and the system discovers it.

## Current Implementation

This is **working in production**. Key files:

- `src/seon/render.clj` — `find-renderer` and `resolve-renderer` implement the specificity algorithm. `try-render` and `render` are the public API. Resolution results are cached and invalidated when the code graph updates.
- `src/seon/graph/query.clj` — `functions-with-output-key` queries Datalevin for render function candidates via ref joins on spec entities.
- `src/seon/graph/extract.clj` — the scanner extracts `:seon.render/html` and `:seon.render/ai` from function output schemas during code graph building.
- `src/seon/ns/routes.clj` — the namespace page renderer uses `resolve-renderer` to find the best HTML renderer for a namespace's available data keys.

Example render function pattern:

```clojure
(defn workout-view
  {:malli/schema [:=> [:cat [:map
                             [:seon.health.workout/exercises ...]
                             [:seon.health.workout/date ...]]]
                      [:map [:seon.render/html :any]]]}
  [{:seon.health.workout/keys [exercises date]}]
  {:seon.render/html [:div ...]})
```

The scanner sees `:seon.render/html` in the output, indexes the function with its required input keys `#{:seon.health.workout/exercises :seon.health.workout/date}`. When rendering data with those keys, this function wins by specificity.

Namespace proximity tiebreaking ensures that `seon.health.workout.render/workout-view` is preferred over `seon.getting-started.render/generic-view` when rendering workout data, even if both match.

## In the Unified Model

Renderer discovery extends naturally. As namespaces gain more render functions with different specificity levels, the algorithm handles it without configuration changes. New output format keys (beyond `:html` and `:ai`) can be added — the pattern is the same. The [[components/code-graph]] scanner already handles arbitrary schema keys.

## Key Schemas

```clojure
;; Output spec that triggers discovery
[:map [:seon.render/html :any]]   ; HTML renderer
[:map [:seon.render/ai :string]]  ; AI text renderer

;; Graph query result
{:seon.fn/qualified-name "seon.health.workout.render/workout-view"
 :required-keys #{:seon.health.workout/exercises :seon.health.workout/date}
 :seon.fn/updated-at #inst "..."}
```
