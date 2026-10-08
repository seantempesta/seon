---
title: "Card EN-6: A test request commits twice; the run's instants are its transactions'"
type: reference
status: active
created: 2026-10-08
---

# EN-6 A test request commits twice; the run's instants are its transactions'

Ledger row EN-6 (F3, group E; corrected by the census fold-in). Census: a direct one-member request is already 2
durable commits on the recording branch (admission 18, completion 10 datoms; `tx-census-astra.md`, 300-382 ms); a
cold MCP check is 8 attempts on three branches (`tx-census-opus.md` §2), of which the checkpoint's 4 are TX-5's and one
is the enclosing eval request's recording. Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.test/run` (`src/seon/test.clj:2236`): per batch of `limit` members (`:2366` `partition-all`), one admission
(`admit-run`, `:2412`) and one `commit-results!` (`:2496-2499`), plus `record-exit!` (`:2449-2464`) only for a member
that exits after the record (the rare path). The run entity stores `:seon.test.run/at` (`runner.clj:1253`) and
`:seon.test.run/selection-tx`.

## Change
- Keep admission (OD-Q5) and the per-batch record (it already holds every member of the batch). With the default
  limit the common request is one batch: assert it.
- Stop writing `:seon.test.run/at` and `:seon.test.run/selection-tx`; their readers read the run's admission
  transaction instead: `(db/assertion-tx database run-eid :seon.test.run/id)` (EN-1's reader) and its `:db/txInstant`.
  Readers: `test.clj:764`, `:1046`, `:1240`, `:1361`, `:1377`, `:2392`, `runner.clj:1253` (writer), `:1314-1335`,
  `:1378`, `:1714`, `:1865`, `:1900`, `render/test.clj:105`. The `:seon.test/run-at` value fields they fill keep their
  names.
- `:seon.test.member/completed-tx` and `terminated-tx` stay (transition refs; fold-in §2.1 "Kept on purpose").
- Schema: `:seon.test.run/at` and `:selection-tx` marked retired (description) until adoption removes them.

## Proof
`seon.test-test/an-executed-proof-request-commits-twice` (new): a request of three members by identity on a fresh
agent; the transactions on the recording branch whose datoms touch that run (query each by the run's entity id on
`:seon.test.run/*` and `:seon.test.member/*` attributes, AEVT per attribute) number exactly 2; the run has no
`:seon.test.run/at`; `seon.test.runner/latest-results` still answers the three results with their instants.

## Files / commit
`src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/render/test.clj`, `resources/seon/schemas/seon.test.run.edn`, `test/seon/test_test.clj`.
`bin/lane commit <lane> -m "EN-6: a test request commits admission and one record; its instants are its transactions'" --test seon.test-test --test seon.test.runner-test --test seon.render.test-test src/seon/test.clj src/seon/test/runner.clj src/seon/render/test.clj resources/seon/schemas/seon.test.run.edn test/seon/test_test.clj`

## Cost
Two attributes fewer per run; reads add one EAVT seek each.

## Dependencies
EN-4, F1 (landed), TX-0, EN-1 (`assertion-tx`). Reset: none.
