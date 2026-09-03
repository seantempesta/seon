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

## 2026-09-03 implementation and measurement

`schema/projection-from-database` now retains the one-argument derivation in a
bounded cache keyed only by `datahike.db/committed-value-identity`. The cached
value is a delay, so concurrent callers share one derivation. The two-argument
reusable-projection arity stays caller-owned because its reusable projection
may carry process-local predicate functions absent from the pure fingerprint.
Speculative and wrapped database values still derive fresh. A live schema-row
transaction changed the commit identity, produced a different projection in
642.05 ms, and the repeated lookup returned that identical projection in 0.04
ms.

The handed-path decomposition over 10,000 warm calls was: complete wrapper
0.220 ms; decode 0.121 ms; `edn-encoded?` alone 0.109 ms; attribute validation
0.044 ms; raw `q-with-evidence` 0.005 ms. Codec classification now rides the
projection's existing compiled-state holder, and raw database values no longer
pay an `IHistory` protocol lookup at each schema access. After those changes,
the same decomposition was: complete wrapper 0.048 ms; decode 0.0006 ms;
attribute validation 0.015 ms; malformed-pattern validation 0.011 ms; raw query
0.004 ms.

The exact family query measured 0.089–0.138 ms with projection-state handed,
well under 5 ms. Ten uncached executions of the family query (a distinct
ordinary input prevents Datahike's result cache from turning the dependency
work into a near-zero cache lookup) measured 11.17–13.61 ms raw and
10.68–13.89 ms through `seon.db/q`, within 2×. The repeated identical-query
cache-hit floor remains 0.012–0.121 ms raw versus 0.144–0.405 ms through
`seon.db/q`; the absolute wrapper cost is fixed, but that literal cache-hit
ratio is not yet within 2×. Keep this issue open until the owner rules whether
that ratio requires a second decoded-result cache or the acceptance criterion
should compare actual query executions rather than Datahike cache hits.
