---
type: milestone
status: partial
order: 4
---
# M4: Discoverable Codebase

When this milestone is crossed, the system can answer the question: "given this data shape, what functions work with it?" One mechanism serves all use cases -- rendering, transformation, event handling, validation, testing. An agent that needs to do something with data does not grep source files or hallucinate function names. It queries the graph, gets back a ranked list of compatible functions, and picks one.

Renderer discovery is already this pattern, working in production. M4 is the generalization: the same specificity algorithm applied to all function discovery, not just rendering.

## The Scenario

A new domain namespace `seon.health.metrics` stores body weight measurements. An agent needs to display them in the UI. It has never seen the health namespace before.

```clojure
;; The agent has data with this shape
(def sample {:seon.health/metric-id "weight-2024-03"
             :seon.health/value 82.5
             :seon.health/unit :kg
             :seon.health/recorded-at (java.util.Date.)})

;; Query: what functions can render this data as HTML?
(gq/discover {:seon.discover/input-keys #{:seon.health/metric-id
                                          :seon.health/value
                                          :seon.health/unit}
              :seon.discover/output-key :seon.render/html})
;; => [{:fn/name "seon.health.render/metric-card"
;;      :fn/match-score 3           ;; all 3 required keys match
;;      :fn/ns-distance 0}          ;; same namespace family
;;     {:fn/name "seon.render/default-entity"
;;      :fn/match-score 0           ;; generic fallback
;;      :fn/ns-distance 3}]

```

The agent did not need to know that `seon.health.render/metric-card` exists. The graph found it by matching the data's keys against function input schemas. The specificity ranking puts the domain-specific renderer first and the generic fallback second.

Now the same agent wants to transform raw CSV imports into metric entities:

```clojure
;; What functions produce health metrics from raw data?
(gq/discover {:seon.discover/input-keys #{:seon.import/csv-row}
              :seon.discover/output-key :seon.health/metric-id})
;; => [{:fn/name "seon.health.ingest/parse-metric"
;;      :fn/match-score 1
;;      :fn/ns-distance 0}]

```

Same query mechanism. Different intent. The discovery API does not care whether the caller wants to render, transform, validate, or test. It matches shapes.

## What This Requires

**Graph indexes full function schemas.** The ingest pipeline already extracts `:malli/schema` metadata and stores input/output specs as Datahike refs (`:seon.fn/input-spec`, `:seon.fn/output-spec`) pointing to spec entities with `contains-keys` and `optional-keys`. Output-key discovery works via `gq/functions-with-output-key`. What is missing is the generalized discovery API (`gq/discover` or similar) that accepts arbitrary input/output key combinations. See [[orchestrator/issues/graph-missing-schema-index]].

**One discovery API.** A single query function (`gq/discover` or similar) that accepts input keys, output keys, or both, and returns ranked matches. The ranking algorithm is the same one renderer discovery already uses: count of matched required keys for specificity, namespace proximity for tiebreaking. This generalizes [[concepts/renderer-discovery]] from "find render functions" to "find any function."

**One rendering system.** Currently two dispatch mechanisms coexist: the graph-backed specificity resolver in `seon.render` and the multimethod-based dispatch in `seon.ns.view`. These must converge into the single discovery mechanism. The namespace view should be a render function discovered by the graph, not a special case with its own dispatch.

**One SSE push path.** Three SSE push mechanisms exist (ctx watch-based, render-handler poll, deprecated `send!`). All should converge to a single push pattern that routes through the flow topology, making push observable and interceptable.

**One AI context builder.** Three overlapping context builders produce AI-readable text through different code paths. One discovery-based system should replace all three: query the graph for the agent's namespace, resolve context functions by schema matching, compose the result.

**Convention uniformity (M3) as prerequisite.** Discovery queries are only as good as the data they search. If half the functions lack schemas, discovery returns half the results. M3 ensures every function has a queryable shape; M4 builds the query system on top of that complete index.

## What Already Exists

- [[vision/capabilities/renderer-discovery]] -- complete. The proof of concept. `resolve-renderer` delegates to `gq/functions-with-output-key`, specificity algorithm ranks by matched keys, namespace proximity tiebreaks. Cache invalidated on code changes. This is M4's core algorithm, working in production for one use case.
- [[vision/capabilities/code-graph]] -- partial. Graph stores functions, specs (with contains-keys/optional-keys), call edges, namespace dependencies. Function-to-spec refs work. Output-key discovery works for any key via `gq/functions-with-output-key`. Missing: input-key discovery API, generalized `gq/discover` accepting arbitrary input/output combinations.
- [[vision/capabilities/code-documentation]] -- complete. `render/code.clj` generates docs from graph data. `compatible-functions` already finds functions by schema compatibility -- a limited form of discovery.
- [[vision/capabilities/namespace-introspection]] -- partial. Runtime introspection discovers functions, vars, atoms. Content negotiation serves HTML and AI formats. Missing: interactive data exploration, dead code cleanup.

## What Remains Honest

- [[orchestrator/issues/graph-missing-schema-index]] -- the critical blocker. Without schema indexing in the graph, discovery is impossible. The schema data exists at runtime (`malli.core/function-schemas`) but the ingest pipeline does not capture it.
- [[orchestrator/issues/overlap-three-rendering]] -- two rendering dispatch mechanisms coexist. Must converge.
- [[orchestrator/issues/overlap-three-sse-push]] -- three SSE push paths. Must converge.
- [[orchestrator/issues/overlap-three-ai-context]] -- three AI context builders. Must converge.
- [[orchestrator/issues/overlap-three-status-badges]] -- three badge implementations. Symptom of the same problem.
- [[orchestrator/issues/coupling-ns-routes-reactive]] -- namespace views bypass the standard push path.
- [[orchestrator/issues/dead-web-namespace-viewer]] -- dead files from the pre-discovery era.
- [[orchestrator/issues/lifecycle-coupling-bottleneck]] -- namespace lifecycle depends on 7 components. Discovery should decouple this.

Renderer discovery proves the pattern works. The gap is generalization: indexing all function schemas (not just render output), providing a unified query API, and converging the overlapping systems that predate discovery.

## How to Verify

```clojure
;; Discovery finds render functions (already works)
(let [renderers (gq/functions-with-output-key :seon.render/html)]
  (assert (pos? (count renderers))
          "Renderer discovery should return results"))

;; Discovery finds non-render functions by input schema
(let [fns (gq/discover {:seon.discover/input-keys #{:seon.db/db-name}
                         :seon.discover/output-key nil})]
  (assert (pos? (count fns))
          "Should find functions that accept :seon.db/db-name"))

;; Discovery finds transformation functions (input + output)
(let [fns (gq/discover {:seon.discover/input-keys #{:seon.db/db-name}
                         :seon.discover/output-key :seon.db/result})]
  (assert (every? :fn/spec fns)
          "Every discovered function has a schema"))

;; Only one rendering dispatch mechanism exists
;; (no multimethods for rendering, no parallel discovery paths)

;; Only one SSE push path exists
;; (all push routes through a single mechanism)

;; Graph contains schema data for all public functions
(let [all-fns (gq/all-public-functions)
      with-schema (filter :fn/input-keys all-fns)]
  (assert (= (count all-fns) (count with-schema))
          "Every public function should have indexed schemas"))

```

**M4 is fully crossed when:** `gq/discover` returns ranked results for arbitrary input/output key combinations, all rendering goes through schema-based discovery, all SSE push uses one path, and the graph indexes schemas for every public function.

## Dependencies

M4 depends on M3 (convention uniformity) completely. Discovery is a query over function schemas. If functions lack schemas (M3 incomplete), the query returns nothing useful. M3 is the data; M4 is the query.

M4 enables everything that follows. The agent development pipeline (M5) uses discovery to find tools and context. Autonomous namespace agents (M6) use discovery to find handlers for incoming messages. Progressive enhancement relies on discovery to match new functions to existing data flows. M4 is where the vision's core primitive becomes real.
