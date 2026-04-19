---
type: prd
status: completed
tags: [prd, database, flow, agent]
---
# Research: Effect Annotations on Malli Schemas for Parallel Test Execution

---

## 1. How We Already Annotate Schemas — The Mechanism

### Where properties live

Malli schema properties are the second element in a schema vector, when it is a map:

```clojure
[:string {:min 1 :seon.db/identity true}]
;;        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ properties

```

For `:=>` (function) schemas, properties go in the same position:

```clojure
[:=> {:seon.fn/effects #{:db/read}} [:cat ::request] ::response]
;;   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ properties on the function schema

```

### Confirmed in Malli source

Reading `reference-code/malli/src/malli/core.cljc` line 2138-2209, the `-=>-schema` implementation:

- Line 2149: `(-check-children! :=> properties children 2 3)` — properties are accepted
- Line 2187: `(-properties [_] properties)` — properties are returned by `m/properties`
- Line 2153: properties flow through to form, cache, and checker

**Verdict: Arbitrary `:seon.*` properties on `:=>` schemas are fully supported.** Malli passes them through without validation. `(m/properties (m/schema [:=> {:seon.fn/effects #{:db/read}} [:cat :int] :int]))` returns `{:seon.fn/effects #{:db/read}}`.

### How the bridge reads properties

In `src/seon/db/schema.clj` line 89-96, `seon-db-props->db-props`:

```clojure
(defn- seon-db-props->db-props [schema]
  (let [props (m/properties schema)]
    (cond-> {}
      (:seon.db/identity props)   (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props)     (assoc :db/unique :db.unique/value)
      (:seon.db/value-type props) (assoc :db/valueType (:seon.db/value-type props)))))

```

This reads properties from the Malli leaf schema (on map entries, not the `:=>` schema). For effect annotations, we would read properties from the `:=>` schema instead — same mechanism, different schema type.

### How `m/function-schemas` stores collected data

In `reference-code/malli/src/malli/instrument.clj` line 48-50:

```clojure
(defn -collect! [v]
  (let [{:keys [ns name] :as m} (meta v)]
    (when-let [s (-schema v)]
      (m/-register-function-schema! (-> ns str symbol) name s (m/-unlift-keys m "malli")))))

```

And in `core.cljc` line 3086:

```clojure
(swap! -function-schemas* assoc-in [key ns name]
  (merge data {:schema (f ?schema), :ns ns, :name name}))

```

The `:schema` key holds the compiled Malli schema object. So after instrumentation, we can read effects via:

```clojure
(let [schemas (m/function-schemas)]
  (for [[ns-sym fns] schemas
        [fn-sym {:keys [schema]}] fns
        :let [effects (:seon.fn/effects (m/properties schema))]
        :when effects]
    {:fn (symbol (str ns-sym) (str fn-sym))
     :effects effects}))

```

---

## 2. Effect Taxonomy — Survey of All `:malli/schema` Functions

I surveyed every function with `:malli/schema` metadata across `src/seon/`. Total: **~100 functions**. Here is the categorization:

### Category Counts

| Category | Count | Examples |
|----------|-------|---------|
| **Pure** | ~25 | `health.metrics/compute-bmi`, `ctx.history/map-diff`, `ctx.history/apply-delta`, `ctx.history/reverse-delta`, `ctx.history/empty-delta?`, `dev.lint/*`, `dev.repair/*`, `dev.codebase/clojure-file?`, `dev.codebase/file->namespace`, `dev.codebase/file->test-namespace`, `dev.suggestions/*`, `db.schema/malli-type->datalevin-type`, `db.schema/malli-map->datalevin-schema` |
| **DB read** | ~15 | `graph.query/dependents-of`, `graph.query/dependencies-of`, `graph.query/call-graph`, `graph.query/callers-of`, `graph.query/functions-in-ns`, `graph.query/search-functions`, `graph.query/functions-with-output-key`, `runtime/instance`, `runtime/instances`, `runtime/running-sessions`, `runtime/agent-runs`, `dev.context/get-last-review-time`, `dev.context/edits-since-last-review`, `orchestrator.session/get-agent-session`, `orchestrator.session/list-agent-sessions` |
| **DB write** | ~20 | `graph.ingest/ingest-namespace!`, `graph.ingest/ingest-analysis!`, `graph.ingest/ingest-file!`, `runtime/register!`, `runtime/unregister!`, `runtime/mark-crashed!`, `runtime/start-agent-run!`, `runtime/complete-agent-run!`, `dev.context/record-edit`, `dev.context/record-review`, `dev.context/record-todos`, `health.workout/add-set!`, `db.datalevin.backup/backup!`, `db.datalevin.backup/restore!` |
| **Ctx read** | ~10 | `ctx/get-state`, `ctx/get-data`, `ctx/get-meta`, `ctx/channels`, `ctx/channel-count`, `ctx/instance-exists?`, `ctx/instances-for-namespace`, `ctx/namespaces`, `ctx/namespace-count` |
| **Ctx write** | ~5 | `ctx/update-state!`, `ctx/subscribe-channel!`, `ctx/unsubscribe-channel!`, `ctx/set-render-fn!`, `ctx/persist!` |
| **IO** | ~10 | `ai.gemini/generate`, `ai.gemini/ask`, `ai.gemini/search`, `ai.gemini/calculate-cost`, `dev.review/call-gemini`, `dev.review/review-edits`, `dev.analysis/analyze-file` |
| **Lifecycle** | ~5 | `runtime/cleanup-stale-instances!`, `runtime/hydrate-cache!`, `runtime/reset-registry!`, `ns.lifecycle/ensure-instance!`, `ns.lifecycle/backup-all-instances!` |
| **Mixed** | ~10 | `dev.hook/process-edit` (IO+DB), `ns.lifecycle/resolve-instance!` (ctx+DB), `runtime/snapshot-flow-topology!` (DB+lifecycle), `dev.verify/run-unit-tests` (IO), `dev.verify/run-gen-tests` (IO) |

### Functions Without `:malli/schema` (Intentionally)

Several categories of functions explicitly omit `:malli/schema`:

- **Process-spawning**: `ai.claude/launch-agent!` — returns runtime objects (channels, atoms)
- **Connection management**: `db.datalevin.conn/*` — manager contains non-generatable atoms
- **Flow status**: `flow.status/*` — deals with mutable flow state
- **Render functions**: `render.code/*`, `render.default_page/*` — some still missing schemas
- **Agent logging**: `ai.agent.log/*` — fire-and-forget side effects

---

## 3. Proposed Annotation Design

### Syntax

```clojure
;; Pure function — no annotation needed. Purity is the default.
(defn compute-bmi
  {:malli/schema [:=> [:cat ::request] ::response]}
  [{::keys [weight-kg height-cm]}] ...)

;; DB-reading function
(defn dependents-of
  {:malli/schema [:=> {:seon.fn/effects #{:db/read}}
                  [:cat ::request] ::response]}
  [{::keys [db-name ns-name]}] ...)

;; DB-writing function (implies :db/read too)
(defn register!
  {:malli/schema [:=> {:seon.fn/effects #{:db/write}}
                  [:cat ::request] ::response]}
  [{::keys [namespace status]}] ...)

;; Ctx-mutating function
(defn update-state!
  {:malli/schema [:=> {:seon.fn/effects #{:ctx/write}}
                  [:cat ::request] ::response]}
  [{::keys [instance-id update-fn]}] ...)

;; IO function (network, filesystem)
(defn ask
  {:malli/schema [:=> {:seon.fn/effects #{:io/network}}
                  [:cat ::request] ::response]}
  [{::keys [prompt]}] ...)

;; Mixed effects
(defn process-edit
  {:malli/schema [:=> {:seon.fn/effects #{:db/write :io/network}}
                  [:cat ::request] ::response]}
  [{::keys [file-path]}] ...)

```

### Effect Vocabulary

| Effect | Meaning | Test Implication |
|--------|---------|-----------------|
| (none) | Pure function | Full parallel, generative tests, no fixtures |
| `:db/read` | Reads from Datalevin | Needs DB connection, parallel OK |
| `:db/write` | Writes to Datalevin | Needs isolated DB or serialization |
| `:ctx/read` | Reads from ctx atom | Needs ctx fixture, parallel OK |
| `:ctx/write` | Writes to ctx atom | Needs isolated ctx per test |
| `:io/network` | HTTP calls, external APIs | Needs mocks or real network |
| `:io/filesystem` | Reads/writes files | Needs temp directory |
| `:io/process` | Spawns processes | Needs cleanup |
| `:lifecycle` | Creates/destroys resources | Serialized, cleanup required |

### Reading Effects Programmatically

```clojure
(defn fn-effects
  "Read effect annotations from a function's :malli/schema."
  [fn-var]
  (when-let [schema (:malli/schema (meta fn-var))]
    (let [s (m/schema schema)]
      (:seon.fn/effects (m/properties s)))))

;; From the function-schemas registry (after instrumentation):
(defn all-fn-effects
  "Return {qualified-symbol #{effects}} for all instrumented functions."
  []
  (into {}
    (for [[ns-sym fns] (m/function-schemas)
          [fn-sym {:keys [schema]}] fns
          :let [effects (:seon.fn/effects (m/properties schema))]]
      [(symbol (str ns-sym) (str fn-sym)) (or effects #{})])))

;; Query: all pure functions
(defn pure-functions []
  (into [] (comp (filter (fn [[_ effects]] (empty? effects)))
                 (map first))
        (all-fn-effects)))

```

### Should We Annotate WHICH Database?

Not initially. Most functions use a db-name parameter (`:seon.runtime`, `:seon.ai`, etc.) but the effect category is what matters for test scheduling. If two DB-write functions operate on different databases, they could theoretically run in parallel, but the complexity of tracking that isn't worth it yet. Start with coarse categories, refine later if needed.

### Should Effects Be Inherited?

Yes conceptually, no in the annotation. If `save-workout!` calls `db/transact!`, the annotation should declare `:db/write`. We can VERIFY this via the code graph (see section 4), but the annotation is the declaration and the graph is the verifier.

---

## 4. Inference Feasibility — Can the Code Graph Detect Effects?

### What the graph tracks

From `src/seon/graph/ingest.clj`, the graph stores:

- **`:seon.call/from-fn`** and **`:seon.call/to-fn`** — function-level call edges (ref to `:seon.fn/qualified-name`)
- **`:seon.call/row`** — source line of the call

From `src/seon/graph/query.clj`:

- `call-graph` — outgoing edges from a function
- `callers-of` — incoming edges to a function
- `transitive-dependents-of` — transitive closure at namespace level

### Can we query "all functions that transitively call db/transact!"?

**Yes, with a transitive closure query.** The graph has the data. Here's how:

```clojure
;; Direct callers of db/transact!
(gq/callers-of {::gq/db-name :seon.runtime
                ::gq/ns-name "seon.db"
                ::gq/fn-name "transact!"})

;; To get transitive callers, walk the call graph backwards:
(defn transitive-callers-of [db-name ns-name fn-name]
  (loop [frontier #{(str ns-name "/" fn-name)}
         visited #{}]
    (let [new-callers (->> frontier
                           (mapcat (fn [qn]
                             (let [[ns fn] (clojure.string/split qn #"/")]
                               (map :seon.call/from-fn
                                    (gq/callers-of {::gq/db-name db-name
                                                    ::gq/ns-name ns
                                                    ::gq/fn-name fn})))))
                           set
                           (#(clojure.set/difference % visited frontier)))]
      (if (empty? new-callers)
        (disj (into visited frontier) (str ns-name "/" fn-name))
        (recur new-callers (into visited frontier))))))

```

### Effect inference rules

| If function calls... | Inferred effect |
|---------------------|-----------------|
| `seon.db/transact!` | `:db/write` |
| `seon.db/query`, `seon.db/pull-by-name` | `:db/read` |
| `seon.ctx/update-state!`, `seon.ctx/persist!` | `:ctx/write` |
| `seon.ctx/get-state`, `seon.ctx/get-data` | `:ctx/read` |
| `seon.ai.gemini/ask`, `seon.ai.gemini/generate` | `:io/network` |
| `clojure.java.io/*`, `slurp`, `spit` | `:io/filesystem` |

### Graph completeness

The call graph is built from clj-kondo static analysis (`src/seon/graph/analyzer.clj`). It captures:

- Direct function calls (e.g., `(db/transact! ...)`)
- Qualified var references

It does **not** capture:

- **Dynamic dispatch**: `(defmethod ...)` calls — the multimethod call is recorded but not which method runs
- **Higher-order functions**: `(map some-fn coll)` — `some-fn` is recorded as a var usage, but the call through `map` may not create a call edge
- **`resolve`/`requiring-resolve`**: e.g., `ingest.clj` line 568 uses `(resolve 'seon.graph.extract/extract-graph-from-file)` — invisible to static analysis
- **Interop**: Java method calls are not tracked
- **core.async go-blocks**: calls inside `go`/`go-loop` are captured but the asynchronous nature is not

### Feasibility verdict

**Inference is feasible for ~80% of cases.** The code graph can detect most direct and transitive effects. The remaining 20% (dynamic dispatch, higher-order, resolve) would need manual annotations. The combination is powerful:

1. **Annotate the "leaf" effectful functions** (db/transact!, db/query, ctx/update-state!, etc.) — there are only ~15 of these
2. **Infer effects transitively** via the call graph for everything else
3. **Verify annotations match inference** — flag mismatches (declared pure but calls db/transact!)

---

## 5. Parallel Test Execution Design

### Scheduling by effect type

| Effect Category | Parallelism | Fixture Required | Strategy |
|----------------|-------------|-----------------|----------|
| Pure | Fully parallel | None | Thread pool, no coordination |
| DB read only | Parallel with each other | Shared test DB | Read-only snapshot connection |
| DB write | Isolated | Own temp DB per test | `with-test-datalevin` per test |
| Ctx read | Parallel with each other | Shared ctx | Snapshot of ctx atom |
| Ctx write | Isolated | Own ctx per test | Fresh atom per test |
| IO/network | Parallel (if mocked) | Mocks | Mock registry per test |
| Lifecycle | Serialized | Full system | Run last, sequentially |

### Execution phases

```
Phase 1: Pure tests (all in parallel, thread pool)
    |
Phase 2: Read-only tests (parallel, shared DB/ctx)
    |
Phase 3: Write tests (parallel with isolation — each gets own DB/ctx)
    |
Phase 4: Lifecycle tests (sequential)

```

### What kaocha offers

Reading `reference-code/kaocha/notes.org`, parallelism is listed as a TODO item ("- [ ] parallelism (?)"). Kaocha does NOT have built-in parallel execution.

However, kaocha's architecture supports custom test types via multimethods (`kaocha.testable/-load` and `-run`). We could write a custom test type that:

1. Loads test namespaces
2. Groups tests by effect category
3. Runs each group with appropriate parallelism

But this is fighting kaocha's architecture. Kaocha is designed around sequential test execution with hooks. The parallel scheduling is better done at a lower level.

### Alternative: Built-in parallel runner

Since Seon's test runner is already custom (`user/run-tests` calls kaocha programmatically via `seon.dev.verify`), we can implement parallelism directly:

```clojure
(defn run-tests-parallel
  "Run tests with effect-aware parallelism."
  [{::keys [namespaces]}]
  (let [;; 1. Collect all test vars and their effects
        test-vars (collect-test-vars namespaces)
        grouped (group-by-effect test-vars)

        ;; 2. Run pure tests in parallel
        pure-results (pmap run-pure-test (:pure grouped))

        ;; 3. Run read-only tests in parallel (shared DB)
        read-results (with-shared-db
                       (fn [db]
                         (pmap #(run-read-test % db) (:db/read grouped))))

        ;; 4. Run write tests in parallel (isolated DBs)
        write-results (pmap run-isolated-write-test (:db/write grouped))

        ;; 5. Run lifecycle tests sequentially
        lifecycle-results (mapv run-lifecycle-test (:lifecycle grouped))]

    (merge-results pure-results read-results write-results lifecycle-results)))

```

---

## 6. Flow-Based Test Runner — Can We Use core.async.flow?

### The pattern match

The existing flow topology has exactly the pattern we need:

| Flow Concept | Test Equivalent |
|-------------|-----------------|
| Writer process (serialized) | DB-write tests (one at a time per DB) |
| Reader process (parallel) | Pure/read-only tests (many at once) |
| Reply router | Result aggregation |
| Namespace isolation | Test isolation per effect group |

### How it would work

```clojure
;; Test flow topology
{:procs
 {:seon.test/pure-runner
  {:proc (flow/process #'pure-test-step)
   :args {:parallelism 8}}

  :seon.test/read-runner
  {:proc (flow/process #'read-test-step)
   :args {:db-conn shared-conn :parallelism 4}}

  :seon.test/write-runner
  {:proc (flow/process #'write-test-step)
   :args {:db-factory db-factory-fn}}

  :seon.test/result-sink
  {:proc (flow/process #'result-aggregator-step)}}

 :conns
 [[[:seon.test/pure-runner :out/result] [:seon.test/result-sink :in/result]]
  [[:seon.test/read-runner :out/result] [:seon.test/result-sink :in/result]]
  [[:seon.test/write-runner :out/result] [:seon.test/result-sink :in/result]]]}

```

### Is this worth it?

**Not for the first iteration.** The flow infrastructure adds complexity (process lifecycle, message envelopes, error handling) that isn't needed for test scheduling. A simpler approach using `pmap` or `future` with thread pools would work and be easier to debug.

Flow-based test execution becomes valuable when:

- Tests run across multiple JVMs (like agent JVMs)
- Test results need to stream to the UI in real-time via SSE
- Resource contention is dynamic (not just static effect categories)

**Defer flow-based scheduling to v2.** Start with in-process parallelism.

---

## 7. Recommendation — What to Build First

### Phase 1: Effect vocabulary + annotation (small, high value)

1. Define the `:seon.fn/effects` property convention in `CONVENTIONS.md`
2. Add a helper function in `seon.schema` or a new `seon.fn.effects` namespace:

   ```clojure
   (defn fn-effects [fn-var] ...)
   (defn all-fn-effects [] ...)
   (defn pure-functions [] ...)

   ```

3. Annotate the ~15 "leaf" effectful functions (db/transact!, db/query, ctx ops, gemini calls)
4. Add a compliance check: "function with `:malli/schema` that calls db/transact! but doesn't declare `:db/write`"

**Effort**: ~2 hours. **Value**: Immediately queryable metadata for all instrumented functions.

### Phase 2: Effect inference from code graph (medium, high value)

1. Add transitive caller query to `seon.graph.query`
2. Build `infer-effects` that walks the call graph from known effectful leaves
3. Compare inferred vs declared effects — report mismatches
4. This becomes a new compliance check in `seon.dev.compliance`

**Effort**: ~4 hours. **Value**: Automatic effect detection, catches undeclared side effects.

### Phase 3: Auto-generate generative tests for pure functions (medium, high value)

1. Query all pure functions (no effects declared, no effects inferred)
2. For each, `mi/check` already does exactly this — but we can run them in parallel
3. Build `run-pure-gen-tests` that uses `pmap` over pure functions

**Effort**: ~3 hours. **Value**: Automatic property testing for all pure functions.

### Phase 4: Parallel test execution (large, medium value)

1. Group test vars by effect category of the functions they test
2. Run pure test namespaces in parallel (thread pool)
3. Run DB-read tests in parallel with shared connection
4. Run DB-write tests with isolation (`with-test-datalevin` per test)

**Effort**: ~8 hours. **Value**: Faster test suite, but current suite runs in ~10s so speedup is limited.

### Defer

- **Flow-based test runner**: Only valuable when tests span JVMs or need real-time UI streaming
- **Resource-level tracking** (which DB, which ctx): Start with coarse categories, refine if contention appears
- **kaocha custom test type**: kaocha's architecture fights parallelism; our custom runner is better

### Key Insight

The most valuable outcome isn't parallel test execution (the suite is fast enough). It's the **effect metadata itself**: a machine-readable declaration of what each function does. This enables:

- Agents discovering which functions are safe to compose
- Automatic compliance checks (undeclared effects)
- Documentation generation ("these functions are pure, these touch the DB")
- Future: dependency injection for testing (mock only what's needed)

The parallelism is a bonus. The metadata is the product.
