---
title: "Card RP-15a: Crash recovery from open effect receipts"
type: reference
status: active
created: 2026-10-08
---

# RP-15a Crash recovery from open effect receipts

Ledger row RP-15a (S6-04b, group B). Design: `redo/pass-and-recording.md` §4 (as folded: recovery reads the open
receipts and interrupts those whose eval request has no recorded call row). Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
Boot closes every open run (`seon.cluster` boot recovery `src/seon/cluster.clj:2949-2985`, writing
`:seon.boot/recovered-runs`) through `seon.turn/recover-tx` → `recover-call` (`src/seon/turn.clj:1500-1530`), which
stamps the run's unfinished evaluations (`interrupt-stamps` `:342`) and effects (`effect/interruption-stamps`
`src/seon/effect.clj:385`, by the run ref) and closes the run. After RP-14 no run is open before its recording, so
there is nothing to close; the only durable work an interrupted eval request can leave is an effect receipt opened
before the handler ran.

## Change
- CHANGE `seon.effect/interruption-stamps` (`effect.clj:385`): arity `[database now]`, contract
  `[:=> [:cat :seon.db/database-value :inst] [:vector :seon.schema/value]]`: every receipt with no
  `:seon.effect/result-edn` and no `:seon.effect/interrupted-at` (`(d/datoms … :aevt :seon.effect/id)` minus those;
  open receipts are few) whose opening transaction's `:seon.db.transaction/eval-request` (TX-0; the receipt's
  `:seon.effect/id` datom's tx) names no call row (`(nil? (db/pull db [:db/id] [:seon.turn/id eval-request]))`) gets
  today's stamps (`:seon.effect/interrupted-at`, notify moved to `to`). A receipt whose opening transaction has no
  eval request (written outside one) is interrupted too: nothing will record it.
- CHANGE boot recovery (`cluster.clj:2965-2985`): one transaction of
  `(into (schedule/recover-tx db now) (effect/interruption-stamps db now))`; no open-run query.
- DELETE `seon.turn/recover-tx`, `recover-call`, `interrupt-stamps` (if no other caller), the
  `:seon.boot/recovered-runs` member (`cluster.clj:2949`, `seon.boot.edn`), and `close-escaped-run!`'s use of
  `recover-tx` (`turn.clj:5509-5535`) and `seon.cluster.agent`'s (`src/seon/cluster/agent.clj:1439-1460`): an escaped
  eval request has nothing open to close; each of these callers now only releases its in-memory state. Helpers a
  fixture still calls stay until RP-15d (`rg -n '<name>' test`).

## Proof
`seon.cluster.turn-test/a-crash-before-record-keeps-only-durable-effects` (new): an eval request runs a stub
capability whose handler never returns before the test calls boot recovery's function directly on the branch
connection (the eval request's recording never happened: run the capability through `seon.effect/request!` inside
`seon.turn.world/with-world` of a world with `:seon.turn/id "crash-1"` and no recording); then the receipt has
`:seon.effect/interrupted-at`, no call row `[:seon.turn/id "crash-1"]` exists, and a receipt whose eval request IS
recorded (a second, completed eval request) is untouched.

## Files / commit
`src/seon/effect.clj`, `src/seon/cluster.clj`, `src/seon/turn.clj`, `src/seon/cluster/agent.clj`,
`resources/seon/schemas/seon.boot.edn`, `test/seon/cluster/turn_test.clj`.
`bin/lane commit <lane> -m "RP-15a: boot recovery interrupts the open receipts of unrecorded eval requests; run recovery deleted" --test seon.cluster.turn-test --test seon.effect-test --test seon.cluster.boot-test --test seon.turn-test src/seon/effect.clj src/seon/cluster.clj src/seon/turn.clj src/seon/cluster/agent.clj resources/seon/schemas/seon.boot.edn test/seon/cluster/turn_test.clj`

## Cost
Boot: one AEVT scan of `:seon.effect/id` (one datom per receipt ever, a few hundred) and one pull per open receipt;
today's open-run query and per-run recovery go.

## Dependencies
RP-14, EN-1 (receipts lose `:seon.effect/run`), TX-0. Group B, plus `effect.clj` (group E) and `cluster.clj` (group M):
claim all three.

## Reset needs
None.
