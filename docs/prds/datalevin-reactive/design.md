---
type: prd
status: draft
tags: [prd, architecture, flow]
---

# Datalevin-Reactive: Entity-Driven Function Dispatch

## Status: v2 Foundation Complete

### v2 Foundation (2026-03-18)

All phases implemented in `src/seon/test/bootstrap_v2.clj` (29 tests, 8 test vars):

- **Clean public API** -- `init!`, `call!`, `shutdown!` lifecycle; `register-connection!`, `unregister-connection!` for consumers
- **d/listen! reactive dispatch** -- transaction listener fires automatically after every `d/transact!`, discovers downstream functions, dispatches them recursively
- **Shape graph cache** -- `defonce` atom caches attr-set to matching function names; 9x faster lookups; invalidated on re-index
- **Consumer pruning** -- functions only fire if their non-identity output keys have active consumers; no consumers = no pruning (run everything)
- **Generalized `resolve-inputs`** -- works for any entity type with identity key, not just namespace state
- **Connection registry** -- `*connections` atom tracks REPL/browser/agent consumers with consuming-keys sets
- **Cycle prevention** -- `*processing-chain*` dynamic var (set of visited fn names) bound during listener dispatch

### Test Results (29 pass, 0 fail)

- `entity-dispatch-test` -- entity creation, persistence, accumulation
- `reactive-chain-test` -- `add-workout!` triggers `update-weekly-volume`; `record-bodyweight!` triggers `suggest-next-weight`
- `pure-function-test` -- no entity interaction for identity-free functions
- `cycle-prevention-test` -- chain terminates (visited set)
- `listener-test` -- verifies `d/listen!` fires automatically on every `d/transact!`
- `consumer-pruning-test` -- registered consumer enables function; unregistered prunes it; no consumers = no pruning
- `clean-api-test` -- full `init!` -> `call!` -> verify -> `shutdown!` lifecycle
- `stress-test` -- 100 rapid calls, ~49 chains/second with caching, all workouts accumulated

### Key Findings

- **LMDB sync writes are the throughput floor**: ~200 tx/sec for upserts, giving ~50 chains/sec (3 tx per chain). Shape cache eliminates query overhead (9x faster) but transact dominates.
- **d/listen! works with embedded Datalevin**: listeners fire synchronously inside `d/transact!`, enabling recursive reactive chains without manual wiring
- **Consumer pruning is effective**: identity keys in output are correctly treated as routing keys (not data), so pruning checks only non-identity output keys
- EDN serialization needed for nested collections (Datalevin doesn't support nested maps)
- Enhanced `dependent-default-transformer` needed to deref through Malli ref schemas for defaults
- **>1000 chains/sec would require write batching** -- async transact doesn't help (still LMDB sync under the hood); batching multiple downstream writes into one tx would

## Problem

The in-memory routing model (`route-data!` in bootstrap v1) keeps state in atoms and routes data through functions in-memory. Datalevin is used for persistence but not as the reactive backbone. This means:
- Two sources of truth (atom + Datalevin)
- Reactions are in-memory graph traversals, not durable
- No audit trail of what fired and why

## Thesis

**Functions declare which entities they operate on via identity keys in their specs.** The system pulls entity data for inputs, transacts function outputs back. Transaction reports trigger downstream function discovery via the shape graph. Datalevin IS the reactive backbone — not just storage.

**The agent just writes functions with specs.** No `d/transact!`, no atom management, no explicit wiring. The identity key in the spec is the only new concept, and it already exists (`:seon.db/identity true`).

## Classification by Spec

| Input spec pattern | Type | System behavior |
|---|---|---|
| No identity keys, no defaults | **Pure** | Call with provided args, return result |
| Identity key + defaulted keys | **Stateful** | Pull entity by ID, merge attrs, call, transact output |
| Identity key in output only | **Creator** | Transact output as new entity |
| Defaults but no identity key | **Namespace read** | Pull from namespace entity via defaults |

## Data Model

### Namespace State Entity

Each namespace has ONE state entity, identified by `::ns-id`:

```clojure
;; In Datalevin
{::ns-id "seon.test.bootstrap-v2"    ;; identity attribute
 ::screen :home
 ::workouts [{...} {...}]
 ::bodyweight 85.0
 ::weekly-volume 500.0}

```

### Domain Entities

Namespaces can also manage collections of domain entities:

```clojure
{:seon.workout/id "w-001"            ;; identity attribute
 ::exercise "Squat"
 ::weight 100.0
 ::reps 5
 ::date #inst "2026-03-18"}

```

### Identity Key Convention

The identity key in a function's input spec tells the system which entity to operate on:

```clojure
;; Operates on namespace state entity
(defn add-workout!
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]       ;; identity → which entity
                                  [::workouts ::workouts]  ;; pulled from entity
                                  [::exercise ::exercise]  ;; from caller
                                  [::weight ::weight]
                                  [::reps ::reps]]]
                      [:map [::ns-id ::ns-id]
                            [::workouts ::workouts]]]}     ;; written back
  [{::keys [ns-id workouts exercise weight reps]}]
  {::ns-id ns-id
   ::workouts (conj workouts {::exercise exercise ::weight weight ::reps reps})})

```

### Default for Namespace Identity

```clojure
(schema/register! ::ns-id
  [:string {:seon.db/identity true
            :default/fn (fn [] "seon.test.bootstrap-v2")}])

```

Agent calls `(add-workout! {::exercise "Squat" ::weight 100.0 ::reps 5})` — `::ns-id` defaults, `::workouts` pulled from entity. Clean.

## Reactive Chain

```
1. Agent calls: (add-workout! {::exercise "Squat" ::weight 100.0 ::reps 5})

2. System resolves inputs:
   ::ns-id → default → "seon.test.bootstrap-v2"
   ::workouts → pull from entity where ::ns-id = "seon.test.bootstrap-v2"
   ::exercise, ::weight, ::reps → from caller

3. Call function → returns {::ns-id "..." ::workouts [...updated...]}

4. System sees identity key in output → transact:
   (d/transact! conn [{::ns-id "seon.test.bootstrap-v2"
                        ::workouts [...updated...]}])

5. Transaction report: entity X changed attrs #{::workouts}

6. Transaction listener checks shape graph:
   "Which functions have ::workouts in their input spec?"
   → update-weekly-volume needs #{::workouts}
   → call it (pull ::workouts from entity, now updated)
   → returns {::ns-id "..." ::weekly-volume 500.0 ::weekly-sets 1}
   → transact back

7. Another transaction report: #{::weekly-volume ::weekly-sets} changed
   → shape graph: who wants these? → maybe suggest-next-weight
   → ...chain continues until no more matches

8. Return accumulated results to REPL

```

## Implementation

### Namespace: `seon.test.bootstrap-v2`

File: `src/seon/test/bootstrap_v2.clj`

All code in one file. Uses embedded Datalevin (same pattern as v1).

### Part 1: Schemas with Identity + Defaults (~30 lines)

```clojure
(schema/register! ::ns-id
  [:string {:seon.db/identity true
            :default/fn (fn [] "seon.test.bootstrap-v2")}])

(schema/register! ::workouts
  [:vector {:default/fn (fn [] [])}   ;; empty if not in entity yet
   ::workout-set])

(schema/register! ::screen
  [:enum {:default :home} :home :active :history])

;; ... other schemas with defaults for initial state

```

### Part 2: Pure Functions (~20 lines)

```clojure
(defn calculate-volume
  {:malli/schema [:=> [:cat [:map [::weight ::weight] [::reps ::reps]]]
                      [:map [::volume :double]]]}
  [{::keys [weight reps]}]
  {::volume (* weight reps)})

```

### Part 3: Stateful Functions (~40 lines)

Functions with identity keys in input AND output specs:

```clojure
(defn add-workout!
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::workouts ::workouts]
                                  [::exercise ::exercise]
                                  [::weight ::weight]
                                  [::reps ::reps]]]
                      [:map [::ns-id ::ns-id]
                            [::workouts ::workouts]]]}
  ...)

(defn record-bodyweight!
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::bodyweight :double]]]
                      [:map [::ns-id ::ns-id]
                            [::bodyweight :double]]]}
  ...)

(defn update-weekly-volume
  {:malli/schema [:=> [:cat [:map [::ns-id ::ns-id]
                                  [::workouts ::workouts]]]
                      [:map [::ns-id ::ns-id]
                            [::weekly-volume :double]
                            [::weekly-sets :int]]]}
  ...)

```

### Part 4: Entity-Aware Dispatch (~60 lines)

```clojure
(defn resolve-entity
  "Given a function's input spec, find identity keys and pull the entity."
  [{::keys [conn input-schema args]}]
  ;; 1. Walk input schema entries
  ;; 2. Find entries whose schema has {:seon.db/identity true}
  ;; 3. Get the identity value (from args or default)
  ;; 4. Pull entity from Datalevin by identity
  ;; 5. Merge entity attrs into args (defaults for missing keys)
  ...)

(defn apply-result!
  "If result has identity key, transact the output back to the entity."
  [{::keys [conn result]}]
  ;; 1. Find identity keys in result
  ;; 2. Transact: entity identified by identity key gets output attrs
  ...)

(defn dispatch!
  "Resolve entity, decode, call function, apply result."
  [{::keys [conn fn-var args]}]
  (let [resolved (resolve-entity {...})
        decoded (m/decode input-schema resolved (dependent-default-transformer))
        result (fn-var decoded)]
    (apply-result! {:conn conn :result result})
    result))

```

### Part 5: Transaction Listener (~40 lines)

```clojure
(defn on-transaction!
  "Called after every transact. Checks shape graph for downstream functions."
  [{::keys [conn tx-report visited]}]
  (let [changed-attrs (set (map :a (:tx-data tx-report)))
        ;; Find functions whose input spec needs these attrs
        matching (functions-matching-data {:available-keys changed-attrs ...})
        ;; Filter already-visited (cycle prevention)
        new-matches (remove #(visited (:seon.fn/qualified-name %)) matching)]
    (doseq [match new-matches]
      (let [fn-var (requiring-resolve (symbol (:seon.fn/qualified-name match)))
            result (dispatch! {:conn conn :fn-var fn-var :args {}})]
        ;; Recursive: this transact triggers another on-transaction!
        ;; visited set prevents cycles
        ))))

```

### Part 6: Tests (~60 lines)

```clojure
(deftest entity-dispatch-test
  ;; Seed entity in Datalevin
  ;; Call add-workout! with just event data
  ;; Verify entity updated in Datalevin
  ;; Verify downstream functions fired
  ;; Verify cycle detection works
  ;; Verify pure functions don't trigger transacts
  )

(deftest reactive-chain-test
  ;; Add workout → triggers update-weekly-volume → triggers suggest-next-weight
  ;; Verify full chain executed
  ;; Verify final entity has all accumulated attrs
  )

(deftest stress-test
  ;; Rapid-fire 100 add-workout! calls
  ;; Verify all chains complete
  ;; Measure transaction throughput
  )

```

## What This Proves vs v1

| Aspect | v1 (in-memory) | v2 (datalevin-reactive) |
|--------|----------------|------------------------|
| State | Atom + Datalevin persist | Datalevin only (atom is read cache) |
| Routing | In-memory graph traversal | Transaction report → shape match |
| Audit trail | None | Every step is a transaction |
| Source of truth | Atom | Datalevin entity |
| Reaction trigger | Function return values | Datalevin transactions |
| Identity | Implicit (namespace) | Explicit (identity key in spec) |

## Performance: Caching + Throughput

### Current: ~50 chains/second

Each chain = 1 initial dispatch + 2 downstream functions = 3 writes + 3 pulls + 3 shape graph queries. The bottleneck is the shape graph queries (Datalog joins across fn→shape→entry entities), not LMDB writes.

### Shape Graph Cache

Shape graph lookups are **code-time data** — they only change when code is reloaded/scanned. Cache aggressively, invalidate on graph rescan.

```clojure
;; Cache: attr-set → vector of matching function names
;; Invalidated by: graph/ingest (already calls invalidate-render-cache!)
(defonce shape-match-cache (atom {}))

(defn functions-matching-data-cached [{::keys [available-keys]}]
  (let [key (set available-keys)]
    (or (@shape-match-cache key)
        (let [result (functions-matching-data {...})]
          (swap! shape-match-cache assoc key result)
          result))))

```

**Invalidation:** `graph/ingest/ingest-namespace!` already calls `render/invalidate-render-cache!`. Add `invalidate-shape-match-cache!` alongside it. Same trigger, same lifecycle.

### Async Transactions

Datalevin supports `d/transact-async` — returns a future, batches writes, 100k+ tx/sec vs 10k sync. For reactive chains where we don't need to read back between steps, use async:

```clojure
;; Fast path: fire-and-forget downstream reactions
(d/transact-async conn tx-data)
;; Only use sync d/transact! when the next step needs to read the result

```

### Transaction Listeners

`d/listen!` registers callbacks on a connection — called after every transact. Cleaner than wrapping `d/transact!`:

```clojure
(d/listen! conn :reactive-dispatch
  (fn [tx-report]
    (on-transaction! {:conn conn :tx-report tx-report})))

```

This replaces the manual "transact then check" pattern. The listener fires automatically.

### Target: >1000 chains/second

With cached shape lookups + async transactions for downstream reactions:
- Shape match: ~0 ms (cache hit)
- Entity pull: <1ms (LMDB)
- Async transact: <0.1ms (returns immediately)
- Sync transact (initial only): ~1ms

Estimated: ~1ms per chain = 1000 chains/sec.

## Transaction Operations

### What Schema-Driven Specs Can Express

| Operation | Spec form | How |
|-----------|-----------|-----|
| Entity create/update | Map output with identity key | `{::ns-id "..." ::workouts [...]}` → upsert |
| Field update | Key in output | Output key → write to entity |
| Ref creation | `:seon.db/ref` typed output | Lookup ref or entity ID |
| Component nesting | Nested `:map` in schema | `:db/isComponent true` via bridge |
| Cardinality many | `[:vector ...]` in schema | Multiple `:db/add` datoms |

### What Needs Explicit Convention

| Operation | Convention | Why |
|-----------|-----------|-----|
| Entity deletion | `{:seon.db/retract-entity <id>}` in output | No map form for retractEntity |
| Field retraction | Absent key + `{:optional true}` in output spec | Distinguish "unchanged" from "remove" |
| CAS (optimistic locking) | Defer — use `d/with-transaction` when needed | Needs old-value comparison |
| Partial set removal | `{:seon.db/retract-from <key> <values>}` | Can't express via map output |

For v2 POC, the 95% case (map upsert) is sufficient. Entity deletion and CAS are future extensions.
