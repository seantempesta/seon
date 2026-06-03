---
type: architecture
status: active
tags: [architecture, flow, database]
---

# Datahike-Reactive Architecture

> Functions declare what they read and write via Malli specs. The system handles pulling, transacting, and reactive dispatch. Datahike IS the reactive backbone.

## Overview

Every function in Seon is a pure data transformer: it takes a map, returns a map. The system uses the function's `:malli/schema` to determine:

- **What to pull** — identity keys (`{:seon.db/identity true}`) in the input spec tell the system which Datahike entity to read
- **What to inject** — keys with `:default/fn` or `:default` are filled automatically if the caller doesn't provide them
- **What to write** — identity keys in the output spec tell the system which entity to update
- **What to trigger** — after a write, the tx-bus fires and the [[concepts/renderer-discovery|shape graph]] discovers downstream functions whose input specs match the changed attributes

The agent writes functions and schemas. They never call `d/transact!`. The system controls all writes.

## Working Implementation

Fully tested in `src/seon/test/bootstrap_v2.clj` — 29 tests, 0 failures. Self-contained with embedded Datahike.

## How It Works

### 1. Define Schemas

Schemas use `::` (namespace-local) keywords. Identity keys have `{:seon.db/identity true}`. State keys have `:default/fn` that provides initial values.

```clojure
;; Identity — tells the system WHICH entity
(schema/register! ::ns-id
  [:string {:seon.db/identity true
            :default/fn '(fn [_] "seon.test.bootstrap-v2")}])

;; Domain data — defaults provide initial state
(schema/register! ::workouts
  [:vector {:default/fn '(fn [_] [])} ::workout-set])

(schema/register! ::screen
  [:enum {:default :home} :home :active :history])

```

These are normal [[components/schema-system|Malli schemas]] registered via `schema/register!`. The properties (`:seon.db/identity`, `:default/fn`, `:default`) are standard Malli properties — the same mechanism used by [[architecture/decisions/004-schema-unification|`:seon.db/identity` in the database bridge]].

### 2. Write Functions

Functions are classified by their specs — the agent doesn't choose a category:

**Pure** — no identity key. Just transforms data.

```clojure
(defn calculate-volume
  {:malli/schema [:=> [:cat [:map [::weight ::weight] [::reps ::reps]]]
                      [:map [::volume :double]]]}
  [{::keys [weight reps]}]
  {::volume (* weight (double reps))})

```

**Stateful** — identity key in input AND output. Reads from and writes to an entity.

```clojure
(defn add-workout!
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]        ;; ← identity: which entity
                                  [::workouts ::workouts]   ;; ← pulled from entity (has default)
                                  [::exercise ::exercise]   ;; ← from caller
                                  [::weight ::weight]
                                  [::reps ::reps]]]
                      [:map [::ns-id ::ns-id]               ;; ← write back to this entity
                            [::workouts ::workouts]]]}      ;; ← updated value
  [{::keys [ns-id workouts exercise weight reps]}]
  {::ns-id ns-id
   ::workouts (conj workouts {::exercise exercise ::weight weight ::reps reps})})

```

The function doesn't know about Datahike. It receives a map, returns a map.

### 3. The System Handles Everything

When `(call! {::fn-var #'add-workout! ::args {::exercise "Squat" ::weight 100.0 ::reps 5}})`:

```
Step 1: RESOLVE INPUTS
  Input spec: [::ns-id ::workouts ::exercise ::weight ::reps]
  - ::ns-id has {:seon.db/identity true} + :default/fn → defaults to "seon.test.bootstrap-v2"
  - System pulls entity from Datahike where ::ns-id = "seon.test.bootstrap-v2"
  - Entity has ::workouts [...] → merged into args
  - ::exercise, ::weight, ::reps → from caller args
  - m/decode fills any remaining defaults

Step 2: CALL FUNCTION
  (add-workout! {::ns-id "seon.test.bootstrap-v2"
                 ::workouts [...]  ;; from entity
                 ::exercise "Squat" ::weight 100.0 ::reps 5})  ;; from caller
  Returns: {::ns-id "seon.test.bootstrap-v2" ::workouts [...updated...]}

Step 3: TRANSACT RESULT
  Output spec has ::ns-id (identity key) → write back to entity:
  (d/transact conn [{::ns-id "seon.test.bootstrap-v2"
                      ::workouts [...updated...]}])

Step 4: REACTIVE CASCADE (automatic via tx-bus)
  Transaction report: #{::workouts} changed
  Shape graph query: which functions need ::workouts?
  → update-weekly-volume needs [::ns-id ::workouts]
  → dispatch it (same steps 1-3)
  → returns {::ns-id "..." ::weekly-volume 500.0 ::weekly-sets 1}
  → transact → report → shape graph → suggest-next-weight → ...
  → cycle prevention stops when a function would be called twice

```

### 4. Consumer Pruning

Not all downstream functions fire. The system checks if anyone wants their output:

```clojure
;; Browser tab registers as a consumer
(register-connection! {::conn-id "browser-1"
                       ::conn-type :browser
                       ::render-key :seon.render/html
                       ::consuming-keys #{::weekly-volume ::suggestions}})

;; Now: update-weekly-volume fires (::weekly-volume is consumed)
;; Now: suggest-next-weight fires (::suggestions is consumed)
;; If nobody consumed these keys, they'd be pruned

```

State updates (functions with identity key in output) always fire — they're modifying the entity, not just computing data. Consumer pruning only applies to non-identity output keys.

## Key Components

### Dependent Default Transformer

Enhanced version of Malli's `mt/default-value-transformer` from `reference-code/malli/docs/tips.md`. Two enhancements:

1. `:default/fn` receives the **accumulating map** — later entries can depend on earlier ones (entry order = dependency order)
2. Dereferences through Malli ref schemas — defaults on registered schemas flow through to entries that reference them

### Shape Graph Cache

Shape graph queries (function→shape→entry joins in [[components/code-graph|Datahike]]) are cached in a `defonce` atom. Cache key = set of changed attribute keywords. Invalidated when the namespace is re-indexed (code reload).

9x faster lookups (12μs → 1.3μs per 1000 calls). Cache misses fall through to [[components/code-graph|`seon.graph.query`]] Datalog queries.

### Transaction Listener

The per-db conn-process flow exposes a tx-bus — every successful `d/transact` publishes its tx-report. The reactive dispatcher subscribes once per db:

```clojure
(tx-bus/subscribe! :seon
  (fn [tx-report]
    ;; Extract changed attrs from tx-data
    ;; Query shape graph cache for matching functions
    ;; Filter by cycle prevention (dynamic var *processing-chain*)
    ;; Filter by consumer pruning
    ;; Dispatch each matching function
    ))

```

The tx-bus delivers reports synchronously on the conn-process thread, so recursive reactive chains work naturally — each downstream transact publishes again on the same bus.

### Connection Registry

Tracks active consumers (REPL sessions, browser tabs, agent connections):

```clojure
{::conn-id "browser-1"
 ::conn-type :browser          ;; :repl, :browser, :agent
 ::render-key :seon.render/html ;; what output format
 ::consuming-keys #{::volume ::weekly-volume}}  ;; what data this connection wants

```

`active-consumers` returns the union of all `::consuming-keys` across connections. The reactive chain uses this for pruning.

## Entity Refs + Atom as Cached Pull

Entities reference each other via Datahike refs — not EDN-serialized collections. Each workout is its own entity with `:db/id`. The namespace entity has `::workouts` as a cardinality-many ref.

The atom holds the result of a recursive pull:

```clojure
;; The atom IS a Datahike pull result — :db/id at every level
{:db/id 3
 ::ns-id "seon.test.bootstrap-v2"
 ::screen :home
 ::bodyweight 85.0
 ::workouts [{:db/id 1 ::workout-id "w-001" ::exercise "Squat" ::weight 100.0 ::reps 5}
             {:db/id 2 ::workout-id "w-002" ::exercise "Bench" ::weight 80.0 ::reps 8}]}

```

When the agent does `(swap! *ctx* assoc-in [::workouts 0 ::weight] 120.0)`:
1. Watch fires with old + new atom states
2. `diff-to-tx` compares by `:db/id` at each level
3. Produces minimal transact: `[{:db/id 1 ::weight 120.0}]`
4. `d/transact` updates just that workout entity
5. tx-bus publishes → reactive chain

The agent uses normal Clojure data manipulation (`swap!`, `assoc-in`, `update`). The system handles persistence automatically.

### Creating New Entities via Functions

`add-workout!` returns a new workout entity (with `::workout-id`, without `:db/id`). The system:
1. Transacts the new workout entity → Datahike assigns `:db/id`
2. Adds a ref from the namespace entity's `::workouts` to the new workout
3. tx-bus publishes → downstream reactions

## Performance

| Metric | Value | Notes |
|--------|-------|-------|
| Chains/second | ~36 | Each chain = 4+ store writes (separate entities) |
| Shape cache speedup | 9x | Eliminates Datalog join overhead |
| Store write latency | ~5ms | Sync write is the throughput floor |
| Shape entities indexed | 138 | Across full codebase |
| Entry entities indexed | 333 | Across full codebase |

Slightly slower than the EDN approach (~49/sec) because each workout is a separate transact. Batchable for higher throughput.

### Design Consideration: Ref Traversal for Reactivity

When a workout entity's `::weight` changes, the transaction report shows `::weight` changed on the workout entity — NOT `::workouts` on the namespace entity. Functions that react to `::workouts` (like `update-weekly-volume`) won't fire from individual workout edits.

This is correct for add/remove operations (which DO change the `::workouts` ref). For attribute edits on referenced entities to cascade, the shape graph would need to understand ref traversal: "a change to any entity reachable via `::workouts` should trigger functions that read `::workouts`." This is a future enhancement.

## Public API

```clojure
;; Lifecycle
(init! {::conn domain-conn ::graph-conn graph-conn})
;; → subscribes to tx-bus, returns {::results-acc atom}

(call! {::conn conn ::fn-var #'add-workout! ::args {::exercise "Squat" ...}
        ::results-acc results-acc})
;; → resolves inputs, calls function, transacts result
;; → tx-bus publishes; reactive cascade dispatches automatically
;; → returns {:result direct-result :chain [[fn-name result] ...]}

(shutdown! {::conn conn})
;; → unsubscribes from tx-bus, clears cache and connections

;; Consumer management
(register-connection! {::conn-id "repl-1" ::conn-type :repl
                       ::render-key :seon.render/ai
                       ::consuming-keys #{::volume}})
(unregister-connection! {::conn-id "repl-1"})

```

## Related

- [[components/schema-system]] — `schema/register!` and `:seon.db/identity` properties
- [[components/code-graph]] — shape graph storage, function discovery queries
- [[architecture/decisions/004-schema-unification]] — Malli as single source of truth
- [[concepts/renderer-discovery]] — same specificity matching used for function discovery
- `docs/prds/shape-graph/design.md` — shape graph PRD with full data model
- `docs/prds/datalevin-reactive/design.md` — detailed PRD with transaction operations (historical name; describes the same pattern that now runs on Datahike)
- `docs/prds/namespace-bootstrap/design.md` — v1 in-memory routing (predecessor)
- `src/seon/test/bootstrap_v2.clj` — working implementation (29 tests)
- `src/seon/test/bootstrap.clj` — v1 for comparison
