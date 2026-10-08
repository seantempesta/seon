---
title: "Card EN-8b: The model eval request records through the one owner"
type: reference
status: active
created: 2026-10-08
---

# EN-8b The model eval request records through the one owner

Ledger row EN-8b (S5-07d, group B). Lane: Sonnet. Cited at HEAD `e7f120672`.

## Change
As EN-8a for the model path: the model submission (RP-10/RP-11b) records through `settle` with
`:seon.turn.batch/call-facts` holding the attempt rows (RP-14b), delivered ids and answered basis (S6-05). Then
DELETE `seon.turn/settle-batch!` (`:3542`), `settle-evaluated!` (`:3661`), `settle-batch-refusal!` (`:3833`),
`with-submission` (`:3512`) once `settle!` (`:3948`, the error-only settlement) no longer needs it (give `settle!`
the same open prefix through `call-facts`, or delete `settle!` if every failure now records through `settle` with
zero entries: RP-7's call-row-alone path; choose the latter, and convert `fail!` in `call-turn` to it),
`with-fault-windows` (`:3498`) after S5-05, `receipt-settle-batch-tx` (`:981`), and their schema members.

## Proof
`seon.cluster.turn-test/a-model-reply-pass-commits-its-evaluations-once` (RP-10's, marked) and the EN-2 table's model
row: a fixture model eval request whose writer refuses keeps its values. D: `rg -n 'settle-batch!|settle-evaluated!|with-submission' src` answers nothing.

## Files / commit
`src/seon/turn.clj`, `resources/seon/schemas/seon.turn.loop.edn`, `test/seon/cluster/turn_test.clj`, `test/seon/mcp_entrance_test.clj`.
`bin/lane commit <lane> -m "EN-8b: model eval requests record through settle; the second recording owner is deleted" --test seon.cluster.turn-test --test seon.mcp-entrance-test --test seon.turn-test --test seon.turn-work-test --test seon.turn-loop-test src/seon/turn.clj resources/seon/schemas/seon.turn.loop.edn test/seon/cluster/turn_test.clj test/seon/mcp_entrance_test.clj`

## Dependencies
EN-8a, RP-7, RP-10, RP-11b, S5-05. Reset: none.
