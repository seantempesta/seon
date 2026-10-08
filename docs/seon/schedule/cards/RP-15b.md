---
title: "Card RP-15b: Convert the old turn fixtures, group 1"
type: reference
status: active
created: 2026-10-08
---

# RP-15b Convert the old turn fixtures, group 1 (of three)

Ledger row RP-15b-d (I-Q10; group B for `turn_test.clj`, otherwise test-only). Inventory:
`docs/research/agent-platform/2026-10-08/inventory/q10-open-provenance.md` §2: 24 test files, 47 lines open a run with
`seon.turn/open-tx` so later rows can ref it. After RP-14 no production path opens a run before its recording. Lane:
Sonnet. Cited at HEAD `e7f120672`.

## The one conversion (decided here)
Add `seon.test-support/recorded-call!`
`{:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id [:vector :string]] :seon.turn/id]}`
`[connection agent-id sources]` to `test/seon/test_support.clj`: mints `(seon.id/id)`, transacts
`[[:db.fn/call #'seon.turn/record-evaluated-call {…call row… :seon.turn/evaluations [<one terminal evaluation per source, :seon.eval/shown "nil">]}]]`
through `support/transacted!`, and answers the run id. Every fixture that opened a run to hang rows on calls it
instead (with `[]` sources when it needs only the call row: RP-7 allows a call row alone). A fixture whose subject IS
the open/close lifecycle (tests of `open-call`, `close-call`, `recover-call`) is deleted with that machinery: it tests
deleted code. Classify each site as one of the two before editing.

## This group's files (8)
`test/seon/test_support.clj` (the helper), `test/seon/turn_test.clj` (11 sites), `test/seon/data_shapes_test.clj` (7),
`test/seon/cluster/agent_test.clj` (4), `test/seon/turn_loop_test.clj` (3), `test/seon/db_test.clj` (3),
`test/seon/settlement_receipt_provenance_test.clj` (its `*open*` assertions at `:58-64` test the deleted carrier:
delete them), `test/seon/effect_test.clj` (`:859`).

## Proof
Each file's existing tests green after conversion (`--test` each namespace); `rg -n 'turn/open-tx' <these files>`
answers nothing.

## Commit
`bin/lane commit <lane> -m "RP-15b: turn fixtures record a call instead of opening a run (group 1)" --test seon.turn-test --test seon.data-shapes-test --test seon.cluster.agent-test --test seon.turn-loop-test --test seon.db-test --test seon.settlement-receipt-provenance-test --test seon.effect-test test/seon/test_support.clj test/seon/turn_test.clj test/seon/data_shapes_test.clj test/seon/cluster/agent_test.clj test/seon/turn_loop_test.clj test/seon/db_test.clj test/seon/settlement_receipt_provenance_test.clj test/seon/effect_test.clj`

## Dependencies
RP-15a. Reset: none.
