---
type: prd
status: completed
tags: [prd, database, schema]
---
# Malli → Datalevin Schema Bridge

---

## Goal

One function: `malli-map->datalevin-schema`. Define entity schemas once in Malli, derive Datalevin schemas automatically. Eliminate hand-written Datalevin schema maps.

## What's Done

### Core bridge (`seon.db.schema`)

- `malli-type->datalevin-type` — leaf type mapping (keyword + predicate types)
- `malli-map->datalevin-schema` — walks `:map` schema, produces `{attr {:db/valueType ...}}`
- Handles: `:maybe`, `:vector`/`:set` (cardinality-many), `:enum`, nested `:map` (ref+component), `:malli.core/schema` (registry deref)
- `:db/*` properties in Malli entry props pass through verbatim

### Transaction metadata (`seon.db.tx`)

- Fully namespaced `:seon.db.tx/*` keywords
- `entity-schema` → `datalevin-schema` via bridge (eats own dog food)
- `build-tx-entity` builds `:db/current-tx` map with auto `::at`, `::caller`, `::source`
- Opt-in: `::session-id`, `::agent-ns`, `::op`, `::reason`

### `:inst` type registration (`seon.schema`)

- Registered `:inst` as keyword type wrapping `inst?` predicate
- Works everywhere: `[:map [:foo/at :inst]]` is valid Malli

### Tests (`seon.db.schema-test`)

- Bridge type mapping, enum, db-props passthrough, nested maps
- Self-consistency: bridge derives same schema as hand-written `tx/datalevin-schema`
- Cross-check against `seon.ctx/datalevin-schema`
- `build-tx-entity` source inference, metadata merging
- **Integration tested**: real Datalevin DBs, transact+query, uniqueness, cardinality-many, tx metadata
- Validated 5 live runtime entities from running system

### Cleaned up

- Deleted legacy trading schemas (OptionQuote, Greeks, etc.) from `seon.db.schema`
- Deleted `seon.generators`, `seon.generators_test`
- Fixed `seon.system` to use `seon.schema/registered-schemas`
- Fixed `seon.test-utils` — removed trading dependencies

## Gap Analysis (from live system audit)

667 registered Malli schemas across 46 namespaces. Per-database status:

### `:seon.ai` (currently schemaless) — BEST CANDIDATE FOR STEP 3

- AI sessions + messages written by `seon.ai.datalevin`
- Zero Datalevin schema today (schemaless writes)
- Need to: discover what attrs are actually written, register missing ones, build entity schema, derive Datalevin schema, pass to connection

### `:seon.runtime` (hand-written schema, 23 attrs)

- `seon.runtime/*` (8 attrs): ALL registered in Malli, bridge produces exact match ✓
- `seon.agent.run/*` (10 attrs): ALL MISSING from Malli registry
- `seon.flow.snap/*` (5 attrs): ALL MISSING from Malli registry
- Refs: `:seon.agent.run/runtime` needs explicit `{:db/valueType :db.type/ref}`

### `:seon.flow` (currently in master, schemaless)

- Flow traces written by `seon.flow.trace`
- Similar to `:seon.ai` — discover attrs, register, derive

### `seon.graph.ingest` (37 attrs, 0 registered)

- Largest gap. `seon.fn/*`, `seon.ns/*`, `seon.call/*`, `seon.spec/*`, `seon.var/*`
- Multiple ref attrs (call graph edges, spec links)

### `seon.ctx` (4 attrs, 2 registered, 1 type mismatch)

- `:seon.ctx/namespace` registered as `:symbol`, stored as string — needs alignment

### `seon.repl` (8 attrs, 0 registered)

- `form/*` namespace — all missing

## Step 3: Convert `:seon.ai` database (NEXT)

Best first candidate because:

1. Currently schemaless — adding structure, not migrating
2. High-write, append-mostly — good stress test
3. Aligns with Phase 5 rename (`seon` → `:seon.ai`)
4. Existing data can be validated

### Plan

1. Read `seon.ai.datalevin` to discover all attrs it writes
2. Register missing attrs via `schema/register!` in `seon.ai.datalevin`
3. Define `entity-schema` (Malli `:map`) for AI session + message entities
4. Derive `datalevin-schema` via bridge, merge with `tx/datalevin-schema`
5. Pass schema to connection (update `get-master-conn!` call)
6. Validate existing data against new schema
7. Run tests, verify writes still work with schema enforcement

### Key question

Datalevin is permissive with schema evolution — adding schema to a previously schemaless DB should work (new attrs get typed, old untyped data remains readable). Verify this in the REPL before committing.

## Design Reference

### Type mapping

| Malli | Datalevin | Notes |
|-------|-----------|-------|
| `:string` | `:db.type/string` | |
| `:int` | `:db.type/long` | |
| `:double` | `:db.type/double` | |
| `:boolean` | `:db.type/boolean` | |
| `:keyword` | `:db.type/keyword` | |
| `:symbol` | `:db.type/symbol` | |
| `:uuid` | `:db.type/uuid` | |
| `:inst` | `:db.type/instant` | Registered as custom Malli type |
| `[:maybe X]` | unwrap to X | Optional at entry level |
| `[:enum ...]` | infer from values | |
| `[:vector X]` / `[:set X]` | type + `:db.cardinality/many` | |
| `[:map ...]` nested | `:db.type/ref` + `:db/isComponent true` | |
| Registry ref | deref + recurse | Via `:malli.core/schema` handler |

### DB-specific metadata via entry properties

```clojure
[:map
 [:seon.ai/session-id {:db/unique :db.unique/identity} :string]
 [:seon.fn/input-spec {:db/valueType :db.type/ref} :int]  ; explicit ref
 [:seon.spec/contains-keys [:vector :keyword]]]            ; cardinality-many inferred
```

### Entity schema pattern (from `seon.db.tx` — the model)

```clojure
;; 1. Register individual attrs
(schema/register! ::at :inst)
(schema/register! ::caller :string)

;; 2. Compose entity schema from registered keys
(def entity-schema
  [:map
   [::at ::at]
   [::caller ::caller]
   [::source [:enum :agent :system :user :repl :migration]]])

;; 3. Derive Datalevin schema
(def datalevin-schema
  (dbs/malli-map->datalevin-schema entity-schema))
```

## Files

| File | Status |
|------|--------|
| `src/seon/db/schema.clj` | ✅ Bridge functions |
| `src/seon/db/tx.clj` | ✅ Transaction metadata |
| `src/seon/schema.clj` | ✅ `:inst` type registered |
| `test/seon/db/schema_test.clj` | ✅ Bridge + tx tests |
| `src/seon/ai/datalevin.clj` | 🔲 Add schema (Step 3) |
| `src/seon/runtime.clj` | 🔲 Migrate to bridge |
| `src/seon/graph/ingest.clj` | 🔲 Register attrs + migrate |
| `src/seon/ctx.clj` | 🔲 Fix type mismatch + migrate |
| `src/seon/flow/trace.clj` | 🔲 Add schema |
| `src/seon/repl/super.clj` | 🔲 Register attrs + migrate |
