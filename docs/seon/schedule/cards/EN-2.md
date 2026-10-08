---
title: "Card EN-2: The one recording function takes the call's facts"
type: reference
status: active
created: 2026-10-08
---

# EN-2 The one recording function takes the call's facts

Ledger row EN-2 (S5-07a, group B). Design: `redo/entrants.md` §3 "One RECORD owner". Lane: Sonnet. Cited at HEAD
`e7f120672`.

## Change
- `seon.turn.batch/record-facts` (`src/seon/turn/batch.clj:678`) merges the request's
  `:seon.turn.batch/call-facts` (a map, optional) into the call row it hands `record-call`: model attempt rows (RP-14b),
  usage, delivered ids, answered basis go here; absent for MCP and tests.
- `seon.turn/record-evaluated-call` (`src/seon/turn.clj:1317`): `::record-evaluated-call-request` gains
  `[:seon.turn.batch/call-facts {:optional true} :map]`; its `run` map merges `(:seon.turn.batch/call-facts request)`
  before `assoc`ing `:db/id` (the attempt rows are separate entities: call-facts holds
  `:seon.turn.batch/entities [<attempt row> …]` appended to the transaction, and call-row attributes; split by that
  key).
- `record-facts` also prepends the request's optional `:seon.turn.batch/open-tx` (`:seon.store/transaction-data`)
  to its answer: the submitted run's open, written in the one recording (EN-8a).
- `seon.batch.edn` `:request`: `[:seon.turn.batch/call-facts {:optional true} [:map [:seon.turn.batch/entities {:optional true} [:vector :map]]]]` and
  `[:seon.turn.batch/open-tx {:optional true} :seon.store/transaction-data]`.
- `settle` itself is unchanged in shape (RP-7 owns its failure table).

## Proof
`seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure`, its JVM row (new deftest, the EN-2 table):
a JVM eval request through `seon.batch/evaluate-batch` with `turn.batch/jvm-request` and a world whose `:seon.db/writer`
refuses (`(fn [_ _] {:seon.error/message "refused" :seon.db/transaction-refused true :seon.error/type :seon.db.write/validation-refusal})`):
the batch's entries keep their shown values, and `(:seon.batch/diagnostics batch)` holds an error with
`:seon.error/type`. A second row: `call-facts {:seon.turn/reply "x"}` reaches the stored call row (`:seon.turn/reply` = "x").

## Files / commit
`src/seon/turn/batch.clj`, `src/seon/turn.clj`, `resources/seon/schemas/seon.batch.edn`, `resources/seon/schemas/seon.turn.edn` (the request schema), `test/seon/mcp_entrance_test.clj`.
`bin/lane commit <lane> -m "EN-2: settle's recording takes the call's facts" --test seon.mcp-entrance-test --test seon.turn-test src/seon/turn/batch.clj src/seon/turn.clj resources/seon/schemas/seon.batch.edn resources/seon/schemas/seon.turn.edn test/seon/mcp_entrance_test.clj`

## Cost
None. Dependencies: none (RP-7 later). Reset: none.
