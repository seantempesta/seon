---
name: datahike
description: "Use Seon's database owner for Datalog, pull, transactions, refs, temporal values, and read evidence. Use for database queries, schema-bridge behavior, or since-diff diagnosis."
---

# Datahike — one database owner

Read [the turn PRD](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§13–§15 before changing record, temporal refresh, or result storage.
The data-modeling skill owns shape choices; this skill owns operations.

## Explicit values and transaction authority

Use `seon.db`. Its query and pull owners accept explicit immutable
database values or agent-supplied defaults
(`src/seon/db.clj:1177`, `:1277`, `:1323`).
Its transaction owner accepts an explicit connection or the agent's
connection and returns errors as values (`:2193`).
A host JVM probe should supply explicit custody.

Datahike accepts a transaction map with `:tx-data` and optional
`:tx-meta`, or a raw vector/sequence. A map lacking `:tx-data`
refuses (`reference-code/datahike/src/datahike/api/impl.cljc:30`).
Use those dependency keys rather than inventing a wrapper.

A database value is immutable. Hand one value through a pure
derivation; obtain a new value between forms when a preceding form
transacted. Decisions that must hold at write time belong inside
`[:db.fn/call f request]`: Datahike calls `f` with the
mid-transaction database and splices its returned transaction data
(`reference-code/datahike/src/datahike/db/transaction.cljc:1152`).
Never teach the retired process-custody example as the turn fence.

## Schema and references

Query the installed declarations before using an attribute.
The schema population refuses duplicates and exposes canonical forms
(`src/seon/schema/edn.clj:303`, `:348`).
The bridge derives ref type, identity, cardinality, components, indexes,
and history properties (`src/seon/schema/datahike.clj:122`, `:231`).

Identity is a natural key; a ref stores the referenced entity identity,
not its arbitrary domain string. Join through the ref to query a target
attribute. Cardinality-many is a set. Upsert omission leaves existing
facts unchanged; use an explicit retraction to clear them. Stored
nilable forms refuse (`src/seon/schema/datahike.clj:165`).

For fixture operations use `seon.test-support/with-database`, which
supplies a branch of the canonical populated base
(`test/seon/test_support.clj:587`).
Do not build a small hand-rostered schema to impersonate production.

## Temporal values and read evidence

`seon.db/history`, `as-of`, and `since` preserve explicit and
agent-default forms (`src/seon/db.clj:1547`, `:1560`, `:1574`).
Datahike's `as-of` predicate includes the time point;
`since` excludes it
(`reference-code/datahike/src/datahike/db.cljc:143`).
A current-only query is not evidence that a retracted fact never existed.

`seon.db/read-evidence` retains dependency plans and revisions
without database values or read payloads by default
(`src/seon/db.clj:468`). Read-result retention is explicit for
process-local semantic replay. `read-evidence-current?` compares
revisions and may replay supported reads to compare their results
(`:561`). Query execution captures evidence through the dependency's
`q-with-evidence` path (`:1177`).

These are the existing mechanisms to inspect before adding a refresh
index or cache. Their presence does not prove the whole-history
since-query target is implemented.

## Since-query diff — target

For every distinct read form in the agent's history, use its latest
evaluation's read evidence and `:t`. If a named dependency changed
since then, append a fresh evaluation of the same form in a system
turn. Include generated and agent-written reads. Exclude any form
that transacts or requests an effect.

An empty result still has read dependencies. Insertions and retractions
must invalidate the appropriate observation. Missing evidence is
unknown, never an empty set that proves health. Do not replace this
algorithm with message-only refresh or one handler per block.

System turns store ordinary evaluations; previous shown text never
changes. Compaction retracts evaluations and the next system turn
regenerates the opening. A system turn holding wakes' results answers
those wakes under the transaction `:t` rule.

## Result lifetime — target

The agent's persistent SCI context holds actual result objects and
private defs/atoms. The database stores shown text, source, out, error,
and read evidence. It does not store result EDN, print nodes, result
blobs, or a restoration ladder. The single profile rendering happens
at evaluation time; restart loses the object, not the saved text.

Stable evaluation identity comes from `seon.id/evaluation`
(`src/seon/id.clj:49`). `my.turn/evals` and `my.turn/eval`
inspect evaluation facts under §15, including read evidence and
whether the live object remains available.

For a dependency defect, inspect its checked-out source and gitlink
rather than retaining a commit hash in this skill. Verify behavior
on the canonical fixture and through the owning dependency's actual
test task. Historical notes may explain a mechanism but do not
override the current source or PRD.
