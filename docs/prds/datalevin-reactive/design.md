---
type: prd
status: draft
tags: [prd, architecture, flow]
---

# Datalevin-Reactive: Entity-Driven Function Dispatch

## Status: Experimental

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

## Key Question

**Performance:** Each step in the chain is a Datalevin write + read. For embedded LMDB this should be sub-millisecond. The stress test will measure actual throughput.
