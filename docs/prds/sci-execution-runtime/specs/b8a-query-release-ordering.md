---
type: prd
status: active
tags: [prd, database]
---

# B8-A — query cleanup completes before the terminal response

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing —
including the Datahike release contract in `reference-code/datahike`.
Report: (a) a better seam if found; (b) the owners' exact terms.
**Stopping early to report is FREE.** If source contradicts this spec,
stop and report.

Authority: `docs/prds/sci-execution-runtime/research/w10-intermittents-investigation-2026-07-22.md`
§B8-A — every claim there is file:line-grounded.

## Goal

`complete-query-call!` (`src/seon/db/writer.clj:3058`) delivers the
terminal response and removes the active request BEFORE its `finally`
releases materialized database values — contradicting
`handle-request!`'s documented promise that completion runs "after
physical completion" (`writer.clj:4092`) and racing any observer of
completion (the historical intermittent). Fix: cleanup-before-delivery —
release materialized values (preserving the existing catch-and-log
release behavior, `writer.clj:2076`) and remove retained query state
BEFORE the response is delivered. Check the joined-owner cleanup
against the separate `finish-query-job!` release path
(`writer.clj:3018`) — both paths must satisfy the same ordering
contract; if finish-query-job! has the same defect, fixing it is in
scope (same mechanism, same unit).

## Falsifier (bake in as the regression — the investigation's latch design)

Two latches around `d/release-materialized-db`:
1. block release; 2. assert the response has NOT been delivered;
3. release the latch; 4. assert success + zero retained query state.
This replaces the flaky global `with-redefs` observation in
`writer_query_admission_test.clj:520` — rework that test's injection so
it cannot unwind while release is pending (its own defect, per the
investigation).

## Owned paths (touch nothing else)

- `src/seon/db/writer.clj` (complete-query-call! and, if defective,
  finish-query-job!)
- `test/seon/db/writer_query_admission_test.clj`

Protected: everything else. Another lane owns `src/seon/host/*` and
`src/seon/error/instrument.cljc`. No commits, no lifecycle ops.

## Gates

Focused query-admission + integration namespaces both orders, then full
`bin/test-writer` (baseline 356/2686 — record after).
