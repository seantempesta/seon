---
type: prd
status: draft
tags: [prd, database, agent]
---
# Agent Briefing: Malli→Datalevin Schema Bridge — Convert `:seon.ai` Database

## Context

We built a bridge that derives Datalevin schemas from Malli schemas. One source of truth: define entity shapes in Malli, get database schemas for free. It's tested and working against real Datalevin DBs.

## What Exists

### Bridge code

- **`src/seon/db/schema.clj`** — `malli-map->datalevin-schema` takes a Malli `:map` schema, returns a Datalevin attribute schema map. Handles all leaf types, `:maybe`, `:vector`/`:set` (cardinality-many), `:enum`, nested `:map` (ref+component), registry refs (`:malli.core/schema` deref). `:db/*` entry properties pass through verbatim.
- **`src/seon/db/tx.clj`** — Transaction metadata. `:seon.db.tx/*` namespaced keywords. `entity-schema` is the Malli source of truth, `datalevin-schema` is derived via the bridge. `build-tx-entity` creates a `{:db/id :db/current-tx ...}` map with auto `::at`, `::caller`, `::source` + opt-in `::session-id`, `::agent-ns`, `::op`, `::reason`.
- **`src/seon/schema.clj`** — Global Malli registry. `register!` adds schemas. `:inst` is registered as a custom keyword type (Malli only ships `inst?` predicate). 667 schemas registered across 46 namespaces.
- **`test/seon/db/schema_test.clj`** — Tests for bridge + tx metadata. 28 tests, 117 assertions passing.

### The pattern (from `seon.db.tx` — the model to follow)

```clojure
;; 1. Register individual attrs
(schema/register! ::at :inst)
(schema/register! ::caller :string)

;; 2. Compose entity schema referencing registered keys
(def entity-schema
  [:map
   [::at ::at]
   [::caller ::caller]
   [::source [:enum :agent :system :user :repl :migration]]])

;; 3. Derive Datalevin schema
(def datalevin-schema
  (dbs/malli-map->datalevin-schema entity-schema))

;; 4. Merge tx metadata schema into every DB's schema
(merge datalevin-schema tx/datalevin-schema)
```

### Key design decisions

- **Fully namespaced keywords everywhere** — `:seon.ai/role` not `:role`. Global, consistent.
- **`:db/*` props on Malli entries** for database concerns: `[:seon.ai/session-id {:db/unique :db.unique/identity} :string]`
- **Refs** need explicit `{:db/valueType :db.type/ref}` in entry props (no Malli type maps to ref automatically).
- **`:inst`** works as a Malli keyword type (registered in `seon.schema`).
- **Registry refs** work: `[:map [:foo/bar :foo/bar]]` where `:foo/bar` is a registered schema — the bridge derefs and recurses.

## Your Task: Convert `:seon.ai` database

The AI sessions/messages database is currently **schemaless** — `seon.ai.datalevin` writes data with no Datalevin schema. This is the best first candidate because we're adding structure, not migrating.

### Files to read

1. **`src/seon/ai/datalevin.clj`** — the AI data layer. Find all `d/transact!` or `seon.db/transact!` calls. Discover every attribute being written (`:seon.ai/role`, `:seon.ai/content`, etc.)
2. **`src/seon/db/schema.clj`** — understand the bridge API
3. **`src/seon/db/tx.clj`** — the model for how to structure your work
4. **`src/seon/db/datalevin/conn.clj`** — how connections are created, where schema is passed. Look at `get-master-conn!` which is what AI data uses today (with nil schema).
5. **`docs/prds/refinement/malli-datalevin-bridge.md`** — full PRD with gap analysis
6. **`docs/prds/refinement/phase5-connection-api.md`** — the connection unification plan (context for where this is headed)

### Steps

1. **Audit `seon.ai.datalevin`** — list every attribute written to the master DB. Group by entity type (session, message, etc.)
2. **Check which attrs are already registered** — `(seon.schema/schemas-in-namespace "seon.ai")` should show some. Note gaps.
3. **Register missing attrs** — add `(schema/register! ::foo :type)` calls in `seon.ai.datalevin` for any unregistered attrs
4. **Define entity schemas** — one `:map` per entity type (session entity, message entity). Use registered keys as values: `[::role ::role]`. Add `:db/*` props where needed (unique identity on session-id, etc.)
5. **Derive datalevin-schema** — `(dbs/malli-map->datalevin-schema entity-schema)`, merge with `tx/datalevin-schema`
6. **Pass schema to connection** — update the `get-master-conn!` call to include `::conn/schema`
7. **Verify in REPL**:
   - Does the schema-enriched connection accept the existing data? (Datalevin should handle adding schema to a previously schemaless DB)
   - Validate existing entities against the new Malli schema
   - Write new data with tx metadata via `build-tx-entity`
   - Query tx metadata back
8. **Run tests** — `(user/run-tests '[seon.db.schema-test seon.db-test])` plus any AI-specific tests

### Things to watch for

- **Schemaless → schema migration**: Datalevin should be permissive (new schema adds types, old data stays readable). Test this assumption in the REPL before committing.
- **Serialized data**: If any attrs store EDN strings (like `:seon.ctx/data`), they're just `:string` in the schema even if the content is structured.
- **Don't modify the bridge** (`seon.db.schema`) unless you find a bug. The bridge is tested and stable.
- **Merge `tx/datalevin-schema`** into the final schema so tx metadata attrs are declared.
