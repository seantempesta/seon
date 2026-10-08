---
title: "Card EN-7: Reuse a clean test world"
type: reference
status: active
created: 2026-10-08
---

# EN-7 Reuse a clean test world

Ledger row EN-7 (F2, group E; task `seon.test/a-changed-check-builds-a-world-per-request`). Design:
`redo/entrants.md` §3 "The clean world, cached". Measured: `clean-request-handle` 152-159 ms per request on an
unchanged program (entrants §1). Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.test/clean-request-handle` (`src/seon/test.clj:1845`) names the checkpoint branch by the digest of
`[base cluster effective process]` and, per request, branches privately off it and writes
`program-overlay-tx`'s rows (`:1662`), the execution's differing program rows: a validated commit per request even
when the overlay is the same as the last request's.

## Change
The overlay joins the checkpoint key: the checkpoint branch name digests
`[base cluster effective process overlay-digest]`, where `overlay-digest` is `seon.id/digest` of the overlay's
`program-overlay-tx` rows' definition digests (sorted `[identity digest]` pairs, never printed text). The overlay is
written once, on the checkpoint, by its preparer (`prepare-checkpoint!` `:1773` gains the overlay rows in its one
transaction: TX-5 makes it one). A request on an unchanged program is then one `branch!` off that checkpoint and a
base-context hit; no write.

## Functions
- CHANGE `clean-request-handle`: compute `overlay` rows first, then `overlay-digest`, then the checkpoint name; the
  private request branch carries no overlay write.
- CHANGE `prepare-checkpoint!` (private, `:1773`): arity gains `overlay-tx`
  (`[:=> [:cat :seon.store/store :seon.store/branch :seon.cluster/name :seon.config/effective :seon.db.process/id :seon.source/commit-id :seon.db/database-value :seon.store/transaction-data] :nil]`).

## Proof
`seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data` (named by the ledger; created by S7-01: if
S7-01 has not landed, create it here as a two-request test in `test/seon/test_test.clj` named
`seon.test-test/a-second-proof-on-an-unchanged-program-reuses-its-clean-world`): two requests on an unchanged program;
the second writes zero transactions on any checkpoint branch (count transactions by the checkpoint branch's basis-t
before/after: equal), and its `clean-request-handle` stage (`seon.pass-probe` window stages, EN-0) is under 15 ms
(T row).

## Commit
`bin/lane commit <lane> -m "EN-7: the program overlay joins the clean world's checkpoint key" --test seon.test-test --test seon.pass-probe-test src/seon/test.clj test/seon/test_test.clj`

## Dependencies
F1 (landed), TX-5 (one checkpoint transaction). Reset: none.
