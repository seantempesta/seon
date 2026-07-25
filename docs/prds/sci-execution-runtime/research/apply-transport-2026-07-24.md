---
type: research
status: active
tags: [prd, database, runtime, operator]
---

# Apply transport measurement — 2026-07-24

## Decision

Set the selected system manifest's initialization page size to 512 rows. The
current release population then needs 16 sequential initialization requests
instead of 95 at 64 rows or 28 at 256 rows. The largest measured 512-row
request is 1,005,331 encoded bytes, below the fixed 4 MiB protocol ceiling.

Do not page `seon.runtime.state/reconcile!`. The suspected per-row config write
path does not exist: reconcile compiles the complete exact diff into one
transaction vector and submits it through `seon.db/transact!` once under one
expected-database fence. Splitting that transaction would add wire round trips
and would break its atomic replace/retract contract.

## Dependency ledger

- Datahike is the maintained checkout at
  `reference-code/datahike` commit
  `caf526850084a9d5846ccd9ea34251fe411e0d6b`, selected by the root
  `deps.edn` `:writer` alias.
  `reference-code/datahike/src/datahike/writing.cljc` applies one transaction
  to one immutable database value; `writer.cljc` commits the resulting report.
- Transit encoding uses `com.cognitect/transit-clj` 1.0.333 and
  `com.cognitect/transit-cljs` 0.8.280. The maintained Transit CLJS source is
  `reference-code/transit-cljs` commit
  `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce`.
- `src/seon/db/protocol.cljc` `initialization-pages` is the one population
  paging mechanism. It partitions schema, attributes, program rows, and
  initial data by `:seon.db.initialization/page-rows`.
- `src/seon/db/session.cljs` `ensure-pages!` sends those pages sequentially.
  `src/seon/db/transport/uds.cljc` enforces the negotiated frame bound; the
  protocol hard ceiling is 4 MiB.
- `src/seon/runtime/state.cljs` `compile-reconcile-tx` produces the complete
  config diff. `reconcile!` calls `seon.db/transact!` once with that vector and
  one `:seon.db/expected-db`. `src/seon/db/writer.clj`
  `prepare-transaction!` checks the immutable database value and invokes
  Datahike once.
- First-party proof surfaces are
  `test/seon/db/writer_initialization_test.clj` for bounded initialization
  frames and `test/seon/runtime/state_test.cljs` for one fenced reconcile
  submission and zero-write convergence.

## Measurements

The accepted baseline for this attack is the owner's source-frozen default
cycle: reset 7.44 seconds and apply 38.61 seconds with 64-row pages.
The earlier R45 S3 full-cycle comparison measured 46.00 seconds at 64 rows and
36.44 seconds at 256 rows.

The current `out/client/page-plan.edn` population was reassembled as raw
initialization data, then passed back through the production
`seon.db.protocol/initialization-pages` and
`ensure-database-request` constructors. Each request was encoded with the
selected Transit implementation:

| Page rows | Requests | Largest encoded request | Total encoded bytes |
|---:|---:|---:|---:|
| 256 | 28 | 646,927 | 4,344,698 |
| 512 | 16 | 1,005,331 | 4,338,805 |

Both largest frames are below `protocol/maximum-frame-bytes` (4,194,304).

A clean 95-page pass in
`logs/operator/pod/807a175e-8e9a-49d3-a7ac-4b753e3ab706.log` spans
2.128 seconds from page 0 to page 94. At that measured transport cadence,
16 pages floor near 0.35 seconds, removing about 1.78 seconds from apply.

The config-reconcile suspicion was falsified independently:

- `state/reconcile!` has used one transaction submission since commit
  `289de93476`;
- production boot logs generally show a changed nine-operation config
  reconcile in about 1.1 seconds; and
- the focused regression submits 600 desired entities and observes exactly one
  `db/transact!` request carrying all 600 operations.

## Residual

Initialization transport coarsening cannot by itself reduce the 38.61-second
apply to 10 seconds. Its measured removable floor is about 1.8 seconds on the
current population. Config reconcile is already one transaction, so changing
it cannot recover a nonexistent per-row transport tax. The remaining apply
time belongs to process launch, client/base load, initial-agent work, and
operator setup/teardown; those owners must be measured after the canonical
AOT/CDS cutover.
