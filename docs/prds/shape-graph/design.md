---
type: prd
status: draft
tags: [prd, architecture, flow]
---

# Shape Graph: Recursive Schema Indexing for Function Discovery

## Status: Design

## Problem

The code graph indexes function specs as flat lists of top-level keys (`:seon.spec/contains-keys`). This works for simple cases but fails when:

1. **Inline schemas** aren't indexed at all — functions with `[:map ...]` in `:malli/schema` get `nil` for input/output specs
2. **Nested shapes** aren't traversable — knowing a function takes `::ctx` doesn't tell you it also needs `::workouts` inside `::ctx`
3. **Shape matching** is shallow — we can't ask "which functions can process data shaped like this?" recursively

## Goal

Store the **full shape tree** of every function's input and output specs in Datalevin as a graph of connected entities. Enable recursive shape matching: given a data map, traverse its structure and find all functions whose input shape is satisfiable.

## Key Insight

Malli schemas are already trees. Each registered spec resolves to a form. Maps have entries. Entries have keys and value types. Value types may themselves be maps (refs to other shapes). This tree maps directly to Datalevin's EAV model with refs.

Named specs (`::ctx`) and inline specs (`[:map [::ctx ::ctx]]`) produce the **same shape entities**. The name is optional metadata, not the identity. The shape IS the identity.

## Data Model

### Shape Entity

Every `:map` schema (whether named or inline) becomes a shape entity.

```clojure
:seon.shape/id          [:string {:seon.db/identity true}]  ;; hash of normalized form, or spec key
:seon.shape/spec-key    [:keyword {:optional true}]          ;; if from register!, e.g. ::ctx
:seon.shape/namespace   :string                              ;; owning namespace
:seon.shape/entries     [:vector :seon.db/ref]               ;; refs to entry entities
```

### Entry Entity

Each key in a map schema becomes an entry entity.

```clojure
:seon.entry/id          [:string {:seon.db/identity true}]   ;; shape-id + key
:seon.entry/key         :keyword                              ;; fully qualified key
:seon.entry/optional    :boolean                              ;; {:optional true}?
:seon.entry/value-type  :keyword                              ;; :string, :int, :double, :keyword, :enum, :vector, :map, :fn, etc.
:seon.entry/value-shape [:seon.db/ref {:optional true}]       ;; if value is a :map, ref to its shape entity
:seon.entry/collection  [:keyword {:optional true}]           ;; :vector or :set if collection of shapes
```

### Function Spec Links

```clojure
:seon.fn/input-shape    [:seon.db/ref {:optional true}]      ;; ref to input shape entity
:seon.fn/output-shape   [:seon.db/ref {:optional true}]      ;; ref to output shape entity
```

### Example: `add-workout!`

```
Input shape (id: "shape-add-workout-input"):
  entries:
    {key: ::ctx,      optional: false, value-type: :map, value-shape: → shape-ctx}
    {key: ::exercise, optional: false, value-type: :string}
    {key: ::weight,   optional: false, value-type: :double}
    {key: ::reps,     optional: false, value-type: :int}

Shape ::ctx (id: "shape-seon.test.bootstrap/ctx"):
  spec-key: :seon.test.bootstrap/ctx
  entries:
    {key: ::screen,   optional: false, value-type: :enum}
    {key: ::workouts, optional: false, value-type: :vector, collection: :vector, value-shape: → shape-workout-set}
    {key: ::bodyweight, optional: true, value-type: :double}
    ...

Shape ::workout-set (id: "shape-seon.test.bootstrap/workout-set"):
  spec-key: :seon.test.bootstrap/workout-set
  entries:
    {key: ::exercise, optional: false, value-type: :string}
    {key: ::weight,   optional: false, value-type: :double}
    {key: ::reps,     optional: false, value-type: :int}
```

### Named vs Inline: Same Shape

```clojure
;; Named — produces shape with spec-key
(schema/register! ::ctx [:map [::screen ::screen] [::workouts ::workouts]])
;; → shape entity with :seon.shape/spec-key = ::ctx

;; Inline — produces identical shape, no spec-key
[:map [::ctx ::ctx] [::exercise ::exercise]]
;; → shape entity, :seon.shape/spec-key absent
;; entries have the same structure
```

Both are queryable the same way. The spec-key is a bonus for display/debugging, not for matching.

## Shape Matching Queries

### "Which functions accept data with these keys?"

```datalog
;; Given data keys: #{::exercise ::weight ::reps}
;; Find functions where ALL required (non-optional, non-injectable) input entries
;; have keys that are in the data set OR have :default/fn (injectable)

[:find ?fn-name
 :in $ ?data-keys
 :where
 [?fn :seon.fn/input-shape ?shape]
 [?fn :seon.fn/qualified-name ?fn-name]
 ;; All required entries must be satisfiable
 [?shape :seon.shape/entries ?entry]
 [?entry :seon.entry/key ?k]
 [?entry :seon.entry/optional false]
 ;; Key is either in data OR injectable (has default/fn on its schema)
 (or [(?data-keys ?k)]
     [... check if schema for ?k has :default/fn ...])]
```

For recursive matching (nested maps), use Datalevin's pull with depth:

```clojure
;; Pull full shape tree
(d/pull db '[* {:seon.shape/entries
                [* {:seon.entry/value-shape ...}]}]  ;; recursive pull
        shape-eid)
```

### "What does this function produce?"

```datalog
[:find ?key
 :in $ ?fn-name
 :where
 [?fn :seon.fn/qualified-name ?fn-name]
 [?fn :seon.fn/output-shape ?shape]
 [?shape :seon.shape/entries ?entry]
 [?entry :seon.entry/key ?key]]
```

### Loop Detection

Since shapes reference other shapes via `:seon.entry/value-shape`, cycles are possible (e.g., a shape that contains itself). Detect during ingestion:

```clojure
(defn detect-cycles [shape-id visited]
  (if (visited shape-id)
    [shape-id]  ;; cycle found
    (let [entries (get-entries shape-id)
          nested (keep :seon.entry/value-shape entries)]
      (mapcat #(detect-cycles % (conj visited shape-id)) nested))))
```

## Building the Shape Graph

### Source: Malli Schema Registry + Scanner

1. **Named specs** — walk `(seon.schema/registered-schemas)`. For each `:map` spec, create a shape entity with entries. Recursively resolve refs to other named specs.

2. **Inline schemas from `:malli/schema`** — the scanner already extracts these (`scan-fn-schemas`). Parse the `:cat` to get input, parse the return type for output. Create shape entities from inline `:map` forms.

3. **Deduplication** — if an inline `[:map [::exercise ::exercise] [::weight ::weight] [::reps ::reps]]` has the same entries as registered `::workout-set`, they should share the same shape entity. Identity = normalized set of (key, type, optional?) tuples.

### Integration with Existing Pipeline

The shape graph replaces the current flat `:seon.spec/contains-keys` approach. The extract pipeline changes:

```
Current:
  scan → extract specs (flat keys) → link fns to specs by name → ingest

New:
  scan → extract shapes (recursive tree) → link fns to shapes → ingest

  Where "extract shapes" means:
  1. Walk all registered specs, create shape entities for :map types
  2. Walk all fn-schemas, create shape entities for inline :map types
  3. Deduplicate shapes by structure
  4. Link functions to input/output shapes
```

### Migration: Backward Compatible

Keep `:seon.spec/contains-keys` for now (existing queries use it). Add shape entities alongside. Migrate queries one by one. Eventually remove flat keys.

## Execution Model: Reactive Cascade with Pruning

Given a data map, the system builds an **execution graph** before running anything:

1. **Match** — find functions whose input shapes are satisfiable by available data + injectable entries
2. **Cascade** — for each match, check its output shape's keys against other functions' input shapes
3. **Prune** — remove functions whose outputs have no consumers (no downstream functions, no registered browser/REPL connections)
4. **Cycle detect** — if a function appears twice in the graph, mark the edge as a stopping point
5. **Execute** — run the acyclic, pruned graph. State updates (`::ctx` in output) apply after each step. Cascade continues with updated state.

### Consumer Registration

Consumers register interest in data shapes. A consumer is anything that wants data:

- **Browser tab** — SSE connection registers "I want shapes containing `::volume` and `::workouts`"
- **REPL session** — agent requests a specific output
- **Downstream function** — its input shape consumes another function's output
- **Subscriber function** — `:seon.flow.dispatch/subscribe true` metadata

A function without consumers in the current graph is pruned. When a browser tab connects and registers interest in `::volume`, `total-volume` becomes a live node. When the tab disconnects, it gets pruned again.

### Execution Order

```
Data arrives: {::exercise "Squat" ::weight 100.0 ::reps 5}

Build graph:
  add-workout! (match: 3 keys)
    output: {::ctx}
    → update-weekly-volume (input needs ::ctx, subscribed)
        output: {::ctx}
        → [CYCLE: ctx-producing chain already visited, STOP]
    → total-volume (input needs ::ctx, browser subscribed to ::volume)
        output: {::volume ::sets}
        → [LEAF: no further consumers]

Prune:
  calculate-relative-strength needs ::ctx but no one wants ::strength-ratios → PRUNED

Execute (topological order):
  1. add-workout! → {::ctx updated} → apply to atom
  2. update-weekly-volume → {::ctx updated} → apply to atom → STOP (cycle boundary)
  3. total-volume → {::volume 500.0 ::sets 1} → SSE push to browser
```

### No Execution = No Cost

If no browser is connected and no downstream function is subscribed, data that only produces reads is never computed. The system does zero unnecessary work. Connecting a consumer dynamically activates the relevant computation chain.

---

## Implementation Plan

### Phase 1: Shape Entity Schema + Ingestion

- Register shape/entry attributes in Datalevin
- Write `schema-form->shape-entities` that recursively walks a Malli form and produces shape + entry entities
- Handle: `:map`, `:vector`, `:set`, leaf types, refs to named specs
- Handle deduplication by structural identity
- Add to extract pipeline: create shapes for both named and inline schemas

**Files:** `src/seon/graph/extract.clj`, `src/seon/graph/ingest.clj`

### Phase 2: Link Functions to Shapes

- Replace `link-fns-to-specs` with `link-fns-to-shapes`
- For each function with `:malli/schema`, parse input/output, find or create the shape entity, set `:seon.fn/input-shape` and `:seon.fn/output-shape` refs

**Files:** `src/seon/graph/extract.clj`

### Phase 3: Shape-Based Discovery Queries

- `functions-matching-shape` — given a data map's keys, find functions whose input shape is satisfiable
- `functions-producing-key` — given an output key, find functions (replaces `functions-with-output-key`)
- Recursive matching for nested maps

**Files:** `src/seon/graph/query.clj`

### Phase 4: Wire into Bootstrap POC

- Use shape queries in `seon.test.bootstrap` for data-driven routing
- Test: data arrives → shape match → function called → output shape feeds back → cascading matches

**Files:** `src/seon/test/bootstrap.clj`

## Verification

```clojure
;; After Phase 1-2: verify shapes are indexed
(seon.db/query :seon.runtime
  '[:find ?fn ?key
    :where
    [?f :seon.fn/qualified-name ?fn]
    [?f :seon.fn/input-shape ?s]
    [?s :seon.shape/entries ?e]
    [?e :seon.entry/key ?key]]
;; Should show add-workout! → #{::ctx ::exercise ::weight ::reps}

;; After Phase 3: verify discovery
(discover-functions {:available-keys #{::exercise ::weight ::reps}})
;; Should find add-workout! (::ctx is injectable via default/fn)
```

## Validated Findings (from Malli + Datalevin source review)

### Malli Walking API

- Use **`m/children`** (not `m/entries`) for map schemas — returns `[key props child-schema]` tuples
- For `:vector`, `(first (m/children s))` returns inner type
- **Ref resolution:** keyword refs have `(m/type s)` = `:malli.core/schema`. Check `(m/-ref-schema? s)`, then `(m/deref s)` to get actual schema
- **Cycle guard required:** self-referential schemas in the global registry cause StackOverflow on `m/schema`. Track visited keys BEFORE calling `m/deref`
- **`m/form`** returns stable EDN (use for structural identity). **`m/schema`** returns Schema objects (use for walking)
- 7 schemas in codebase fail `m/schema` resolution due to load ordering — walker needs try/catch

### Datalevin Capabilities

- Recursive pull with `...` fully supported, unlimited depth, cycle-safe (tracks visited eids)
- `[:vector :seon.db/ref]` → `{:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref}` — confirmed
- `:db/isComponent true` works but **entries should NOT be components** — they're shared across shapes for dedup
- String identity on `:seon.shape/id` works fine. Keyword would be slightly faster but less flexible.

### Injectable Detection — CRITICAL CORRECTION

`:default/fn` is a property of the **map entry**, not the value schema. In `[:map [::ctx {:default/fn ...} ::ctx]]`, the `:default/fn` is on the entry properties, not on `::ctx`'s schema.

**The same key can be injectable in one shape and required in another.** `::ctx` has `:default/fn` in the `::system` schema but NOT in `add-workout!`'s input schema.

**Decision:** Store `:seon.entry/injectable true` on the entry entity during ingestion. Query against the entry, not the key globally.

### Scale

- ~340 shape entities, ~950 entry entities across the full codebase
- Walking all 311 map schemas: 4.5ms
- Trivial for Datalevin

### Edge Cases for Non-Map Types

| Type | `m/type` | Shape entity? | Store as |
|------|----------|---------------|----------|
| `:string`, `:int`, etc. | leaf type | No | `:seon.entry/value-type :string` |
| `:enum` | `:enum` | No | `:seon.entry/value-type :enum` |
| `[:fn pred]` | `:fn` | No | `:seon.entry/value-type :fn` (opaque) |
| `[:or ...]` | `:or` | No | `:seon.entry/value-type :or` |
| `[:vector :map]` | `:vector` | Yes (inner map) | `:seon.entry/collection :vector` + value-shape ref |
| `:seon.db/ref` | `:seon.db/ref` | No | `:seon.entry/value-type :seon.db/ref` |
| `:seon.flow/dynamic` | `:seon.flow/dynamic` | No | `:seon.entry/value-type :seon.flow/dynamic` |
