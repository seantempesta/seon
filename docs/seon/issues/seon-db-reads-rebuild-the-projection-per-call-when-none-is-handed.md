---
type: issue
status: open
severity: friction
tags: [issue, database, performance, class/p1, context]
---

# `seon.db` reads rebuild the schema projection on every call when none is handed

## Native decoding gate repaired — 2026-09-14

`6785c980c` avoids logical projection construction for native non-string
storage types and removes the query-find cache's unconditional projection
lookup. `eef44fcc3` preserves database-free relation queries. The unchanged
full DB plus help-trial isolated gate passes 44 tests / 297 assertions.
Ten uncached queries measured 32,135,210 ns raw / 27,681,375 ns wrapped in
the fast harness. No timing bound was relaxed and no global cache was added.

This resolves the named native-query performance gate. The broader issue
remains open: an unhanded caller decoding a string-backed logical value
still needs the schema projection. Entry points should carry their acquired
projection; this slice does not claim to repair every such caller.

## Context-renders verification, 2026-09-14

The unchanged HEAD database owner at `4f9d8286e` reproduces the canonical
armed `seon.db-test/ten-unhanded-queries-stay-within-twice-raw-query-cost`
failure: **5,635,710,666 ns wrapped / 35,316,833 ns raw** for ten queries.
With the collection-bound read-evidence fix, the same check measured
**4,639,791,251 ns / 39,107,918 ns**. Its evidence sink is unbound, so
`query-index-patterns` does not execute on this path. The new collection
input regression fails on HEAD and passes with the fix; the timing
assertion remains unchanged. Repair of this separate owner is awaiting
scope authorization; it is not claimed as a green DB gate.

## Transaction-feedback verification, 2026-09-09

The original db.clj at 05510a6d4 reproduced this failure in the canonical
fast harness. After correcting the database tests' other fixture defects,
the transact-feedback fast run had 46 tests / 328 assertions, one failure,
zero errors: ten-unhanded-queries-stay-within-twice-raw-query-cost. The
transaction preflight's six regression groups passed. This read-path defect
is outside the assigned transact path; it was not hidden by relaxing the
timing assertion. See the transaction-feedback landing note for exact gates.

## Problem

Evidence-listens re-observation, 2026-09-09: the three-worker owned-path
gate measured 5,712,210,957 ns for ten wrapped queries versus 29,635,416 ns
raw; a later one-worker gate measured 6,099,677,583 ns versus 29,331,375 ns.
This is the same named regression, with no evidence sink bound; the new
`query-index-patterns` path therefore does not execute. Both read-evidence
and runtime-listen regressions passed. The timing assertion stays intact.

`seon.db/q`'s `read-declarations` (`src/seon/db.clj:501`) is
`(delay (or (schema/handed-projection) (schema/projection-from-database
(schema-database database))))`. Whenever no projection is handed — the
host prepl (`eval_clj` jvm mode), scripts, the MCP tools, any
`(seon.operator/connection …)` caller — decoding the result forces the
delay and DERIVES THE COMPLETE PROJECTION FROM THE DATABASE PER CALL.
Measured 2026-09-02 on `ctxprobe` (2,362 schema rows): raw
`datahike.api/q` 0.11 ms; the same query through `seon.db/q` 2,373 ms,
of which forcing the projection = 5,680 ms on the next call (GC
variance; 587–651 ms on repeats). The supplied-projection SCI path is 48 ms —
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
assumed); the SCI evaluation path drops below 5 ms for the family query above.
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

## 2026-09-07: the identity cache misses on every commit, by construction

Measured on the isolated scratch cluster `projection-lane`
(`tmp/projection-lane-root`), full method and tables in
[projection-to-writer-landing-2026-09-07.md](../../prds/context-generation/research/projection-to-writer-landing-2026-09-07.md).

The 2026-09-03 identity cache repaired repeated reads of ONE database value.
It cannot help a caller that reads after a commit: a commit mints a new
`datahike.db/committed-value-identity`, so the next unbound `seon.db` call on
that connection rebuilds. Cold rebuild measured 507 / 566 / 579 / 670 ms.

This is what a two-form source turn actually pays. Submitting
`"(+ 1 1)\n(* 3 4)"` through `seon.cluster.agent/submit-source!` from a thread
with no projection bound: 728 / 693 / 814 / 756 ms wall, of which 599-697 ms is
`submit-source!` itself and ONE cold rebuild at `read-declarations`
(`src/seon/db.clj:630`). The same submission wrapped in
`schema/call-with-projection-state` with the cluster handle's own
`:seon.sci.eval/projection-state`: 168 / 187 / 147 / 146 ms wall, 54-73 ms in
`submit-source!`, zero rebuilds. The run loop itself derived the projection
**zero** times in all eight trials and costs 76-117 ms — flow procs already run
under `seon.cluster/projection-executor`.

Two directions, both open:

- **Bind at every entry point that is not a flow proc** — `submit-source!`
  first, then the web request threads, the MCP tools, and `bin/seon`. One
  `schema/call-with-projection-state` each; measured 5× on the turn.
- **Reuse on a cache miss** — offer the previously derived projection to
  `derive-projection-from-database` as its reusable projection when the
  identity cache misses. Measured 507-670 ms → 43-62 ms for every unbound
  caller, no second cache. The guard: a reusable projection may carry
  process-local predicate functions absent from its pure fingerprint
  (`src/seon/schema.clj:2496-2500`), so the reused value must come from the
  database-derived cache itself and never from a caller.

`seon.cluster.loop/turn` now binds the handle's projection state explicitly, so
the turn no longer depends on which executor ran the proc; regression
`a-turn-hands-its-clusters-projection-to-every-database-call`
(`test/seon/cluster/turn_test.clj`) runs the pass on a bare thread and asserts
zero derivations.

Components verification, 2026-09-08: `seon.ai/agent-overlay` explicitly rebuilt
`projection-from-database` even with a handed projection. A thread dump of the
armed `situation-totality-property` shows `max-episode-runs → agent-overlay →
projection-from-database → build-projection`. The overlay only needs its key
set: live `:seon.schema/references` already names those leaves (2 ms query).
The components change queries those refs and deletes the projection rebuild
from this read path. This does not close the other entry-point findings above.

## Re-verified at HEAD (2026-09-15)

OPEN, CONFIRMED. Severity: friction.

surface: context-generation

At HEAD `91d5547b5`, `src/seon/db.clj:907–913` creates a fresh delayed projection for an unhanded read; `:932–944` forces it for string-backed attributes. `src/seon/schema.clj:2445–2465` derives on each one-argument call and explicitly has no process-global cache. Native decoding was fixed by `6785c980c`; the residual remains. Read-only default MCP JVM probe (20,000 ms bound):

```clojure
(let [database @(seon.operator/connection "default")]
  (mapv (fn [_]
          (let [start (System/nanoTime)
                result (seon.db/pull database [:seon.agent/id]
                                     [:seon.agent/id "root"])]
            {:result result
             :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)}))
        (range 2)))
```

Observed `[{:result {:seon.agent/id "root"}, :elapsed-ms 17442.284416} {:result {:seon.agent/id "root"}, :elapsed-ms 1989.76425}]`, total MCP evaluation 19,436 ms. This is default's loaded JVM, alongside the matching HEAD derivation path, not an exact-adoption proof or an attribution of all elapsed time to compilation. Downgraded to friction: the reads complete, and this does not reproduce a blocked ordinary turn with its supplied projection. Fix sketch: carry the acquired projection into unhanded entry points and reuse it against the observed database basis.
