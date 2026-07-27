---
type: issue
status: superseded
severity: friction
tags: [issue, database, agent]
---

# Database query tuple results are hard for agents to read

## Triage — 2026-07-23

REAL+INDEPENDENT (S), owned by `seon.db/query` schemas/examples and value
rendering. The current public schema is still `:any` at
`src/seon/db.cljc:528-545`, so scalar/tuple/collection/relation shapes remain
undiscoverable; this is not part of P4.

## Problem

Agents can confuse Datalog tuple/set results with entity maps and then write
invalid follow-up code.

## Evidence

The archived dual-path audit's C26 row records this legibility failure. The
query API currently returns the database's tuple shape without a discoverable
result explanation at the call boundary.

## Owner

`seon.db/query` schemas, examples, and result rendering.

## Acceptance

Behavioral agent probes distinguish scalar, tuple, collection, and relation
query shapes from self-describing schemas/examples or compact result rendering,
without adding a large prose context block or query-specific coercion.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
