---
type: issue
status: open
severity: blocker
tags: [issue, database, performance, class/p1, context]
---

# `seon.db` reads rebuild the schema projection on every call when none is handed

## Problem

`seon.db/q`'s `read-declarations` (`src/seon/db.clj:501`) is
`(delay (or (schema/handed-projection) (schema/projection-from-database
(schema-database database))))`. Whenever no projection is handed — the
host prepl (`eval_clj` jvm mode), scripts, the MCP tools, any
`(seon.operator/connection …)` caller — decoding the result forces the
delay and DERIVES THE COMPLETE PROJECTION FROM THE DATABASE PER CALL.
Measured 2026-09-02 on `ctxprobe` (2,362 schema rows): raw
`datahike.api/q` 0.11 ms; the same query through `seon.db/q` 2,373 ms,
of which forcing the projection = 5,680 ms on the next call (GC
variance; 587–651 ms on repeats). The handed (door) path is 48 ms —
still ~500× raw, unexplained.

This is the fetch-at-call-time class named in AGENTS.md §2.1 ("the same
defect that reads stale state also recomputes a projection on every
call — 217 s vs 6.2 s in one wake path") and the population-revision
prelude storm killed by `e8c8ea6d0`, now on the ONE database namespace
every agent query goes through. "Context is queries" cannot stand on a
query wrapper that costs seconds.

## Owner

`seon.db` (`read-declarations`, `decode-query-result`,
`decode-pull-result`) with `seon.schema/projection-from-database`.

## Direction (derive once per revision, ride the value)

The projection is a pure function of the schema database's committed
identity. Derive it ONCE per schema revision and let it ride the value
it derives from (AGENTS.md §2.1: "derived state rides the value it
derives from — a validator on its projection") — keyed by
`datahike.db/committed-value-identity` of the schema database, the same
identity Datahike keys its own caches on, so an uncommitted or foreign
value derives fresh and a committed one reuses. No global memo keyed by
anything weaker. Then measure the handed path's remaining 48 ms
(decode per attribute? `edn-encoded?` asks the projection per key).

## Acceptance

Ten consecutive `seon.db/q` calls in jvm mode on one database value
cost within 2× of raw `datahike.api/q` after the first; a schema
transaction invalidates (the next call derives anew — asserted, not
assumed); the door path drops below 5 ms for the family query above.
One regression per claim.
