---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, database, render]
created: 2026-09-15
---

# `doc` and `dir` allocate gigabytes per call

## Resolution — 2026-09-15

Implementation: `d5e5b870e`.

The database value now carries its projection state through supplier and
explicit connection acquisition. `read-declarations` uses origin metadata
before thread bindings. The existing per-projection codec cache is retained;
documentation rendering and its pull-in-find query are unchanged.

Default, hot-reloaded `seon.db` with contracts re-armed: full SCI doc costs
13.6–18.3 ms / 23.95 MB and dir 8.6–9.5 ms / 14.37 MB. Supplier wildcard
pull-many costs 0.52–0.65 ms / 1.37 MB. The final isolated armed tests pass
45 tests / 326 assertions, including nested/wildcard allocation and time
bounds, a fallback-warning check, and temporal-origin decoding.

[The landing note](../../../prds/context-generation/research/doc-dir-cost-2026-09-15.md)
contains exact before/after bytes, probes, the separate platform and persistent
recording failures, final gate status, and implementation
commit. In-place adoption remains a separate boundary: concurrent test-run
identity facts refused program reconciliation, so the live proof is a hot
reload, not successful adoption. Raw unowned reads still derive and now log
one warning per forced call; that residual belongs to
[the narrowed projection issue](../seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md).

## Problem

Every agent turn that inspects a namespace pays a query storm. Measured on
`default` (HEAD `85c992d16`, 2026-09-15 18:30Z), thread-allocated bytes via
`com.sun.management.ThreadMXBean`, calling
`seon.sci.eval/program-documentation` directly with one database value:

| call | elapsed | allocated | rows |
|---|---:|---:|---:|
| `program-documentation db 'my.message` (first call on this db value) | 8,406 ms | 46,317 MB | 4 |
| `seon.schema/projection-from-database db` | 506 ms | 1,770 MB | 22 forms |
| `program-documentation db 'my.message` (second call, same db value) | 531 ms | 1,774 MB | 4 |

Through SCI evaluation mode on the same cluster: `(doc my.message/send)` recorded
`:seon.eval/allocated-bytes 2,572,248,464` in 683 ms; two calls in one form
1,250 MB / 280 ms; `(dir my.note)` 686 MB / 187 ms. Run 9's agent called
`dir`/`doc` five times in thirty turns.

## Suspects (verify, do not assume)

- the query at `src/seon/sci/eval.clj` `program-documentation` uses
  `[:find [(pull ?function selector) ...] :in $ selector ?name …]` with a
  nested selector over arities/input-refs/output-refs; Datahike's planner
  and result cache on a fresh db value may re-derive everything (the
  46 GB first call);
- `seon.schema/projection-from-database` at 1.7 GB per call is itself the
  per-call rebuild class (`seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed`);
  the warm 1.7 GB matches it exactly, so the documentation path may be
  rebuilding the projection on every call.

## Wanted

A `doc`/`dir` call in an agent turn costs milliseconds and megabytes; the
projection rides the database value it derives from (law 2.1); one
regression asserting an allocation bound on the same harness.

## Cause established (2026-09-15 19:10Z, orchestrator probes on `default`)

Datahike answers the same reads in 0–10 ms. The cost is `seon.db`'s
`read-declarations` (`src/seon/db.clj:907`): when no projection is handed
through the dynamic var it rebuilds the projection from the database on
every read — 1,774 MB / ~500 ms floor for any selector, and `'[*]` adds a
per-attribute `edn-encoded?` recomputation keyed on the fresh projection
instance (46 GB). The same `seon.db/pull-many` inside
`schema/call-with-projection` with the cluster's projection state: 0 ms,
1 MB, including `'[*]`. Agent evaluations run where that binding is absent
(run 9's `dir`/`doc` took 294–976 ms each), so every agent read pays it.
Fix direction (lane doc-dir-cost): the database value carries its
projection state as metadata, attached where `seon.db` mints values (the
call-preparation supplier and `seon.db/db`); the rebuild fallback becomes
loud. The "reusable projection" fingerprint path is itself 8.8 s / 35 GB
because it re-queries every function's source text.
