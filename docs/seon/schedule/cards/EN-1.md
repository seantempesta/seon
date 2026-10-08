---
title: "Card EN-1: An effect receipt names its eval request on its transaction"
type: reference
status: active
created: 2026-10-08
---

# EN-1 An effect receipt names its eval request on its transaction

Ledger row EN-1 (S6-01, group E; revised by the census fold-in: no `:seon.effect/pass` attribute). Design:
`redo/entrants.md` §3 "Effect provenance on the transaction"; `redo/pass-and-recording.md` §2.1. Lane: Sonnet. Cited at
HEAD `e7f120672`.

## What exists
`seon.effect/request*` (`src/seon/effect.clj:735`) builds the open request with
`:seon.effect/run [:seon.turn/id (:seon.turn/id *request-context*)]` (`:856-858`) and `:seon.effect/form-ordinal`;
`open-call` (`:227-248`) resolves `evaluation-eid` (`:210-225`) and stores `:seon.effect/eval` when the evaluation row
exists. Readers: `receipt-identities` (`:58-66`, render), `interruption-stamps` (`:385-408`, by run ref). `:seon.effect/run`
is an indexed ref to a call row that may not exist yet.

## Change
- `request*`: drop `:seon.effect/run` from the open request; the write that opens the receipt passes
  `:seon.db.transaction/eval-request (:seon.turn/id *request-context*)` in its transaction request map (TX-0's stamp
  reads it; the world may not be bound on a background thread, so pass it explicitly). The effect id stays
  `(id/digest 12 [::id eval-request ordinal effect-ordinal])` (`:850-852`).
- `open-call`: delete the `evaluation-eid` resolution and `:seon.effect/eval`; DELETE `evaluation-eid`.
- `receipt-identities`: the `:run` identity becomes the eval request id read from the receipt's opening transaction
  (`(:seon.db.transaction/eval-request (db/pull database [:seon.db.transaction/eval-request] (:tx (first (d/datoms database :eavt receipt-eid :seon.effect/id)))))`
  through a new reader, since `seon.db` has none (`rg 'defn .*tx' src/seon/db.clj` finds only tx builders): ADD
  `seon.db/assertion-tx` `{:malli/schema [:=> [:cat :seon.db/database-value :int :qualified-keyword] [:maybe :int]]}`
  `[database eid attribute]` = the `:tx` of `(first (d/datoms database :eavt eid attribute))`.
- `interruption-stamps` (`:385`): its query keeps `?run` as the eval request id **value** (arity unchanged in shape:
  `[database eval-request now]`, contract `[:=> [:cat :seon.db/database-value :string :inst] [:vector :seon.schema/value]]`):
  open receipts (no result, no interruption) whose `assertion-tx` of `:seon.effect/id` carries that eval request.
  Its one caller, `seon.turn/recover-call` (`src/seon/turn.clj:1528`), passes the run's `:seon.turn/id`. RP-15a
  replaces both.
- Schema `seon.effect.edn`: `:run` and `:eval` stay declared as retired keys (description "Retired: no longer
  written") until an adoption removes them.
- `turn.clj:1897-1905` (`read-only-evaluation?`'s `:seon.effect/run` query) goes with S4-06's refresh deletion; if S4-06
  has not landed, change that query to the opening-transaction rule in a patch to group B's holder.

## Proof
`seon.effect-test/a-receipt-names-its-eval-request-on-its-transaction` (new): an MCP eval request runs a stub
capability (the existing effect-test fixture capability); the receipt has no `:seon.effect/run` and no
`:seon.effect/eval`; the transaction of its `:seon.effect/id` datom carries `:seon.db.transaction/eval-request` equal
to the call row's `:seon.turn/id`.

## Files / commit
`src/seon/effect.clj`, `src/seon/db.clj`, `src/seon/turn.clj` (one call site), `resources/seon/schemas/seon.effect.edn`,
`test/seon/effect_test.clj` and the receipt fixtures that write `:seon.effect/run` (`rg -ln 'seon.effect/run' test`).
`bin/lane commit <lane> -m "EN-1: an effect receipt names its eval request on its opening transaction" --test seon.effect-test --test seon.background-test --test seon.turn-test src/seon/effect.clj src/seon/db.clj src/seon/turn.clj resources/seon/schemas/seon.effect.edn test/seon/effect_test.clj <fixture files>`

## Cost
One fewer indexed ref per receipt; reads of a receipt's eval request are entity → transaction (two EAVT seeks).

## Dependencies
TX-0. Reset: none.
