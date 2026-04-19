---
type: milestone
status: not-started
order: 6
---
# M6: The Eval Pipeline

When this milestone is crossed, agents develop through the REPL exclusively. No file editing, no line numbers, no `clojure_replace`. The agent evals forms. The pipeline validates them against discoverable constraint functions before accepting. `*ctx*` is the agent's world -- schemas, functions, tests, history, issues -- all live data, rendered into AI context by the same specificity-based discovery used for everything else. `(seon/persist!)` graduates work to disk. This is bootstrap number two.

The shift is fundamental: the filesystem becomes a persistence format, not the source of truth. The graph database is the system. The REPL is the only interface agents need. And the constraints that enforce code quality are themselves functions with specs -- discoverable, extensible, replaceable. Adding a new convention means writing a function. Turtles all the way down.

## The Scenario

An agent is assigned to steward `seon.trading.signals`. It starts with a `*ctx*` atom containing the namespace's current inventory: two schemas (`:live`), one function (`:persisted`), one test (`:pass`), and one issue ("analyze function has no test coverage").

The agent evals a form:

```clojure
(schema/register! ::confidence [:double {:min 0.0 :max 1.0}])

```

The REPL interceptor catches this. It discovers all constraint functions matching `::eval/form -> ::constraint/result` in the graph and runs them. The constraints check: no `:any` in the schema? Types Datalevin-compatible? Generator produces valid samples? All pass. The schema is registered. `*ctx*` updates: `:seon.repl/schemas` gains an entry with status `:live`, 0 consumers.

The agent defines a function:

```clojure
(defn analyze
  "Analyze a ticker for trading signals."
  {:malli/schema [:=> [:cat ::analyze-request] ::analyze-response]}
  [{::keys [ticker]}]
  {::signal-type :hold ::confidence 0.5})

```

The interceptor runs constraints: `:malli/schema` present? Schema concrete (no `:any`)? Map-in/map-out? All referenced schemas registered? All pass. The function compiles. `*ctx*` updates: `:seon.repl/functions` gains an entry, status `:live`, `tested? false`. The `::confidence` schema's consumer count increments to 1.

The agent writes a test. The interceptor runs it immediately, records which instrumented functions were called, and updates `*ctx*`: the test appears in `:seon.repl/tests` with result `:pass`, and `analyze` gets `tested? true`.

The agent calls `(seon/persist!)`. The pipeline checks: all functions tested? All schemas concrete? No orphan schemas? Yes. It generates the `(ns ...)` form from `*ctx*` requires, writes the `.clj` file, updates the Datalevin graph. Status transitions from `:live` to `:persisted`. Other agents can now discover these functions.

Throughout, the agent's AI context is rendered from `*ctx*` -- composable per-key renderers produce XML sections. The agent sees its schemas, functions, tests, issues, and history without any special context-building code.

## What This Requires

**REPL interceptor.** Every eval passes through a pipeline that classifies the form (`defn`, `schema/register!`, `deftest`, `require`, other), discovers and runs constraint functions, updates `*ctx*`, and reports structured results. Invalid forms are rejected with clear errors -- never compiled, never registered.

**Constraint functions as discoverable specs.** Each constraint is a function with schema `[:=> [:cat ::eval/form] ::constraint/result]`. The pipeline discovers all matching functions in the graph and runs them. Adding a constraint means writing a function. No pipeline code changes.

**`*ctx*` as the agent's world.** The ctx atom carries the full namespace inventory: schemas with status and consumer counts, functions with test coverage, tests with results, requires, history, and validation issues. Status is recomputed after every eval, never manually set.

**Composable AI renderers.** Per-key renderer functions produce `{:seon.render/ai "..."}` with XML section delimiters. The ctx walk finds the most specific renderer for each key-value pair. Generic fallbacks handle anything without a specific renderer. Agents can override by writing more specific functions in their JVM.

**Persist pipeline.** `(seon/persist!)` validates all requirements, generates the `(ns ...)` form from `*ctx*` state, writes the source file, updates the Datalevin graph, and transitions status from `:live` to `:persisted`. Like `git commit` -- explicit, agent-controlled.

**Two-state status model.** `:live` (in memory, this JVM only) and `:persisted` (on disk, in graph, discoverable). No intermediate states. Status is derived from current truth: has tests? schemas concrete? consumers exist?

## What Already Exists

- [[vision/capabilities/repl-eval-pipeline]] -- partial. `eval-form!` evaluates via flow, stores in Datalevin with versioning. No constraint enforcement.
- [[vision/capabilities/unified-context]] -- complete. Ctx atoms with validation, persistence, SSE push. The container is ready; the REPL-specific content is not.
- [[vision/capabilities/renderer-discovery]] -- complete. Specificity-based function discovery. The mechanism for AI renderers is production-ready.
- [[vision/capabilities/flow-topology]] -- complete. The routing backbone for eval requests exists.
- The full design is in `docs/prds/agent-repl-interface/prd.md` -- 6 phases, all specified.

## How to Verify

```clojure
;; Eval a defn without :malli/schema -- rejected
(let [result (repl/eval! "(defn bad [x] x)")]
  (assert (= :rejected (:seon.repl/status result)))
  (assert (str/includes? (:seon.repl/message result) ":malli/schema")))

;; Eval a defn with :any in schema -- rejected
(let [result (repl/eval! "(defn bad {:malli/schema [:=> [:cat :any] :any]} [m] m)")]
  (assert (= :rejected (:seon.repl/status result))))

;; Eval a valid defn -- accepted, ctx updated
(let [result (repl/eval! "(defn good {:malli/schema [:=> [:cat ::req] ::resp]} [{::keys [x]}] {::y x})")]
  (assert (= :accepted (:seon.repl/status result)))
  (assert (some #(= ::good (:seon.repl/name %))
                (:seon.repl/functions @*ctx*))))

;; Constraint discovery -- adding a new constraint function is picked up
(let [before (count (repl/discover-constraints))
      _ (repl/eval! "(defn my-constraint {:malli/schema [:=> [:cat ::eval/form] ::constraint/result]} [form] {:ok true})")
      after (count (repl/discover-constraints))]
  (assert (= (inc before) after)))

;; Persist writes .clj file and updates graph
(let [_ (seon/persist!)
      file (io/file "src/seon/trading/signals.clj")]
  (assert (.exists file))
  (assert (str/includes? (slurp file) "defn good")))

```

## Dependencies

**Requires M5 (Observable System)** -- agents need to see their namespace state rendered in real time. The composable renderer infrastructure from M5 is reused for AI context rendering.

**Requires M3 (Discoverable Codebase)** -- constraint functions are discovered via graph queries. The graph must index function schemas for the pipeline to find constraints.

**Enables M7 (Namespace as Process)** -- custom step functions are authored through the eval pipeline. The namespace model requires the eval pipeline's constraint enforcement to ensure step functions meet the flow contract.

**Enables M8 (Autonomous Agents)** -- agents that develop through the REPL are the foundation for agents that develop autonomously. Without the eval pipeline, there is no quality gate between agent intent and production code.

Related concept: [[concepts/progressive-enhancement]]. The pipeline ships with minimal constraints and grows as new constraint functions are written.
