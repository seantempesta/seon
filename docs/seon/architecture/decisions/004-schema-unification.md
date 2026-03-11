---
type: decision
status: implemented
date: 2026-03-06
tags: [decision, architecture, schema, database, flow]
---

# ADR-004: Schema Unification (Malli as Single Source of Truth)

## Context

Seon maintained two parallel schema systems that diverged: Malli schemas for validation and hardcoded Datalevin schema maps for storage. 15 LMDB assertion failures in 3 days. Root cause: bad data bypassed Malli and crashed Datalevin's C layer (e.g., String where Keyword expected causes `(namespace value)` to throw through LMDB's JNI layer).

## Decision

Malli is the **single source of truth**. `schema/register!` carries all metadata -- type and persistence properties. The bridge derives Datalevin schemas automatically. No hardcoded Datalevin schemas anywhere.

## Phases

| Phase | What | Status |
|-------|------|--------|
| 1 | Validation gate in `db/transact!` -- Malli validates before Datalevin sees data | Done |
| 2 | Generative pipeline test (`assert-pipeline-roundtrip!`) -- generate, transact, pull, validate | Done |
| 3a-d | Eliminate hardcoded schemas in ctx, repl, trace, runtime, ingest (72+ attrs across 5 modules) | Done |
| 4 | Nippy inter-JVM channel (see [[architecture/decisions/001-nippy-serialization]]) | Done |
| 5 | Startup consistency check -- validate all registered schemas derive valid Datalevin types at boot | Done |
| 5b | Unified registration -- `register!` carries `:seon.db/identity`, `:seon.db/unique`; bridge reads from leaf properties; entity schema vars removed from production | Done |

## The Pattern

```clojure
;; This is ALL an agent writes. register! is the single surface.
(schema/register! :seon.foo/id [:string {:seon.db/identity true}])
(schema/register! :seon.foo/name :string)
(schema/register! :seon.foo/tags [:vector :keyword])
(schema/register! :seon.foo/parent :seon.db/ref)

;; Bridge translates :seon.db/* -> :db/* automatically.
;; db/transact! validates via Malli before Datalevin.
(db/transact! :seon [{:seon.foo/id "abc" :seon.foo/name "hello"}])
```

## Key Design Decisions Within

- **Persistence properties on leaf schema, not entity map entries.** `{:seon.db/identity true}` lives on the Malli schema via `register!`, not as `:db/unique` on a `:map` entry. One surface, define once, inherit everywhere. (See Phase 5b in design doc.)
- **`:seon.db/*` namespace, not `:db/*`.** Application code never writes raw Datalevin properties. The bridge translates. Clean separation of concerns.
- **Banned types** rejected at registration: `:any`, `:some`, `:nil`, `[:maybe X]` on persisted data, mixed-type enums.

## Still Open

- **`:any` sweep** -- remaining violations in `render.clj::html` and `flow/msg.clj::args/payload/value`. Wire protocol `:any` is the hardest case because types aren't known at schema-definition time (planned: dynamic validation against Malli registry at runtime, Phase 6).

## Details

- `docs/prds/schema-unification/design.md` -- full design with type mappings, phase details, behavioral findings
- `test/seon/db/pipeline_test.clj` -- generative roundtrip tests
- `test/seon/db/validation_test.clj` -- validation gate tests
- `test/seon/db/schema_roundtrip_test.clj` -- bridge contract (36 tests, 411 assertions)
- [[architecture/decisions/002-absence-over-nil]] for nil semantics
- [[architecture/decisions/003-ref-type]] for the ref type
- [[components/schema-system]] and [[components/database]] for component details
