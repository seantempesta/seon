---
type: milestone
status: in-progress
order: 3
---
# M3: Convention Uniformity

When this milestone is crossed, every public function in the codebase follows the same shape: one map in, one map out, all keys namespaced, `:malli/schema` metadata on every `defn`. Every schema uses concrete types -- no `:any`, no `[:maybe X]`, no inline Datalog properties. The code graph can index everything because everything follows the same conventions.

This is not cleanup. This is the foundational work that makes M4 possible. Without uniform conventions, the graph indexes an incomplete picture. Function discovery queries return partial results or nothing at all. The vision's core primitive -- "given a data shape, find functions" -- requires that every function *has* a declared shape to find.

## The Scenario

An agent needs to calculate portfolio risk. It does not know what functions exist. It queries the graph.

```clojure
;; What functions accept trading positions?
(gq/functions-with-input-key :seon.trading/position)
;; => [{:fn/name "seon.trading.risk/calculate"
;;      :fn/input-spec ::risk-request
;;      :fn/output-spec ::risk-response}
;;     {:fn/name "seon.trading.render/position-table"
;;      :fn/input-spec ::position-render-request
;;      :fn/output-spec ::position-render-response}]

```

This query returns results because every function has a `:malli/schema` and the graph has indexed the input/output specs. If `seon.trading.risk/calculate` used positional arguments with no schema, it would be invisible. If it used bare `:position` instead of `:seon.trading/position`, the query would miss it.

Now the agent reads the schema to understand the interface:

```clojure
(malli.core/form (schema/resolve ::risk-request))
;; => [:map
;;     [:seon.trading/position ::position]
;;     [:seon.trading/market-data ::market-data]
;;     [:seon.risk/confidence-level {:optional true} ::confidence-level]]

```

Every key is namespaced. Every type is concrete. The agent knows exactly what to pass and what it gets back. No guessing. No reading source code. The schema IS the documentation.

## What This Requires

**Every public function: map-in, map-out, `:malli/schema`.** No exceptions. Private helper functions can use whatever signature they want. But every `defn` without `^:private` metadata must take a single map argument with namespaced keys and return a single map with namespaced keys, with a `:malli/schema` that declares both.

**Every schema: concrete types, namespaced keys.** No `:any`, no `:some`, no `[:maybe X]`. Every key in every `:map` schema is a fully namespaced keyword. Optional fields use `{:optional true}`, not `[:maybe X]` or nil values.

**No duplicate registrations.** Common schemas like `::db-name` and `::namespace` are registered exactly once in a canonical namespace and referenced everywhere else. The pattern is: `seon.schema` owns cross-cutting schemas, domain namespaces own their domain schemas.

**Dead code removed.** Files with no callers and no tests are deleted. `requiring-resolve` used to mask circular dependencies is replaced with proper architectural boundaries. Every file in `src/` serves a purpose or does not exist. Git has the history.

**No overlapping implementations.** One way to build AI context (not three). One way to wrap clj-kondo analysis (not three). One way to render status badges (not three). One way to push SSE updates (not three). When two implementations exist for the same purpose, one is chosen and the other is deleted.

**Graph indexes function schemas.** The code graph ingest pipeline already extracts `:malli/schema` metadata and stores input/output specs as Datahike refs (`:seon.fn/input-spec`, `:seon.fn/output-spec` pointing to `:seon.spec/*` entities with `:seon.spec/contains-keys`). Output-key discovery works via `gq/functions-with-output-key`. What is missing is the generalized discovery API: `functions-with-input-key` and the unified `gq/discover` that accepts arbitrary input/output key combinations.

## What Already Exists

- [[vision/capabilities/code-quality-pipeline]] -- complete. Dev hook validates syntax, runs affected tests, checks conventions, provides AI review. Blocks on failure.
- [[vision/capabilities/data-contracts]] -- complete. `schema/register!`, runtime instrumentation, startup consistency check.
- [[vision/capabilities/code-graph]] -- partial. Graph stores functions, call edges, namespace dependencies, and input/output specs as refs to spec entities. Output-key discovery works. Missing: input-key discovery, generalized `gq/discover` API.
- [[vision/capabilities/test-isolation]] -- partial. Direct-mode bypass and temp connection fixtures exist. Unified fixture not built. No `defspec` adoption.

## What Remains Honest

The issue list for M3 is long because convention uniformity touches every file.

**Schema gaps:**

- [[issues/archive/missing-malli-schema]] -- many public functions lack `:malli/schema`. This is the single biggest blocker.
- [[issues/archive/map-in-map-out-compliance]] -- many public functions use positional arguments.
- [[issues/archive/graph-missing-schema-index]] -- the graph does not index function schemas. Discovery cannot work without this.
- [[issues/archive/any-in-render-html]] -- render response schemas use `:any`.
- [[issues/archive/any-in-wire-protocol]] -- wire protocol uses `:any`.

**Duplication:**

- [[issues/archive/dup-db-name-schema]] -- 14 copies.
- [[issues/archive/dup-namespace-schema]] -- 20+ copies.
- [[issues/archive/dup-kondo-analysis]] -- clj-kondo wrapped in 3 namespaces.
- [[issues/archive/dup-connection-error]] -- duplicated predicate.
- [[issues/archive/dup-get-conn-runtime]] -- triplicated connection helper.
- [[issues/archive/dup-parse-form-body]] -- duplicated parser.

**Dead code:**

- [[issues/archive/dead-web-namespace-viewer]] -- replaced by `ns/routes`, still in src/.

**Coupling and overlap:**

- [[issues/archive/overlap-three-ai-context]] -- three AI context builders.
- [[issues/archive/overlap-three-rendering]] -- two rendering dispatch mechanisms.
- [[issues/archive/overlap-three-sse-push]] -- three SSE push paths.
- [[issues/archive/overlap-three-status-badges]] -- three badge implementations.
- [[issues/archive/coupling-graph-render]] -- ingest depends on render.
- [[issues/archive/coupling-circular-deps]] -- three circular dependency pairs.

This is a large surface area. The strategy is incremental: each agent session takes one namespace, adds schemas, removes dead code, deduplicates. The dev hook enforces conventions on every edit, so regressions are caught immediately. The work is mechanical but must be thorough.

## How to Verify

```clojure
;; Every public function has :malli/schema
(let [public-fns (gq/all-public-functions)
      missing (remove :fn/spec public-fns)]
  (assert (empty? missing)
          (str (count missing) " functions without :malli/schema")))

;; No :any in the Malli registry
(let [violations (schema/find-any-violations)]
  (assert (empty? violations)))

;; No duplicate schema registrations
;; Each schema keyword appears in exactly one register! call
;; (verified by grep: each ::key appears once in a register! form)

;; Graph indexes function schemas
(let [fns (gq/functions-with-input-key :seon.db/db-name)]
  (assert (pos? (count fns))
          "Graph should find functions that accept :seon.db/db-name"))

;; Zero dead code files
;; (every file in src/ has at least one caller or is a system entry point)

;; All tests pass
(user/run-tests)
;; => {:pass-count N, :fail-count 0}

```

**M3 is fully crossed when:** every public function has `:malli/schema` with map-in/map-out, zero `:any` in the registry, zero duplicate registrations, zero dead files, and the graph indexes function schemas.

## Dependencies

M3 depends on M2 (trustworthy data). The convention that every key has a concrete schema only works if the schema infrastructure (registration, validation, roundtrip) is solid.

M4 (discoverable codebase) depends on M3 completely. Function discovery is a graph query over function schemas. If functions lack schemas, the query returns nothing. M3 is the data quality work that makes M4's queries useful. Without M3, M4 is an empty search engine.
