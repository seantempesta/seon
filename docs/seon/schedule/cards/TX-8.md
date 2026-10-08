---
title: "Card TX-8: Evaluation and call rows stop repeating the transaction's provenance"
type: reference
status: active
created: 2026-10-08
---

# TX-8 Evaluation and call rows stop repeating the transaction's provenance

New row (group B and the readers; census slice 2: "−10 of 25 attributes per eval record"). Design:
`redo/pass-and-recording.md` §2.1 "What it deletes". Readers counted at HEAD `e7f120672`
(`rg` over `src`/`test`): `:seon.cluster.eval/at` 8 files / 35 refs (105 in tests), `:seon.cluster.eval/run` 10 / 53
(159), `:seon.cluster.eval/read-basis-transaction` 3 / 16 (9), `:seon.turn/opened-tx` 11 / 33 (176),
`:seon.turn/closed-tx` 12 / 51 (126). Each slice converts every reader of one attribute, then stops writing it.
Lane: Sonnet.

## The conversions (decided here)
| Attribute | Readers read instead |
|---|---|
| `:seon.cluster.eval/at` | `:db/txInstant` of the evaluation's `:seon.cluster.eval/id` datom (`seon.db/assertion-tx`, EN-1) |
| `:seon.cluster.eval/read-basis-transaction` | the evaluation's transaction's `:seon.db.transaction/basis-t` (TX-0) |
| `:seon.cluster.eval/run` | for the agent order: `:seon.cluster.eval/agent-ordinal` (S4-01, `[agent-id n]`); for the call row: the evaluation's transaction's `:seon.db.transaction/eval-request` → `[:seon.turn/id v]` |
| `:seon.turn/opened-tx` | the call row's transaction (`assertion-tx` of `:seon.turn/id`): written once, by its recording |
| `:seon.turn/closed-tx` | presence of the call row (a call row exists only once recorded, RP-14); "settled" = exists |

## TX-8a: `at` and `read-basis-transaction`
Files: `src/seon/problems.clj`, `src/seon/turn.clj`, `src/seon/bootstrap.clj`, `src/seon/cluster/status.clj`,
`src/seon/sci/eval.clj`, `src/seon/eval/drive.clj`, `src/seon/render/transcript.clj`, `src/seon/turn/batch.clj`
(writer: `evaluation-record` `:650-665`, `evaluate-sources` `:5001-5002`), `resources/seon/schemas/seon.cluster.eval.edn`
(both retired by description), and the tests `rg -ln 'seon.cluster.eval/at\b|read-basis-transaction' test`.
Proof: `seon.turn-test/an-evaluation-row-carries-no-instant-or-basis` (new): an MCP eval request's evaluation row has
neither attribute, and `seon.eval/of-agent`'s rows still answer `:t` and the instant from the transaction.
Commit: `bin/lane commit <lane> -m "TX-8a: evaluations take their instant and basis from their transaction" --test seon.turn-test --test seon.problems-test --test seon.cluster.status-test --test seon.render.transcript-test <files>`

## TX-8b: `opened-tx` and `closed-tx`
After RP-14 and RP-15d (no open run exists anywhere). Files: the 11 + 12 source files `rg -ln 'seon.turn/opened-tx|seon.turn/closed-tx' src`
list (`test.clj`, `render.clj`, `turn.clj`, `cluster.clj`, `cluster/status.clj`, `bootstrap.clj`, `cluster/agent.clj`,
`render/transcript.clj`, `render/walk.clj`, `turn/batch.clj`, `eval/drive.clj`, `context.clj`, `fault.clj`,
`cluster/boot.clj`, `cluster/message.clj`, `render/web.clj`), `seon.turn.edn` (`:turn` map members become absent).
Split by owner group if more than ~15 minutes: TX-8b1 group B (`turn.clj`, `turn/batch.clj`), TX-8b2 group A/D
(renders), TX-8b3 the rest. Proof: `seon.turn-test/a-call-row-is-written-once-with-no-open-or-close-refs` (new).

## TX-8c: `run`
After S4-01 (agent ordinal) and S4-03 (history from call rows). Files: `rg -ln 'seon.cluster.eval/run' src` (10).
Proof: `seon.eval-test/an-agents-evaluations-are-found-without-a-run-ref` (new): `seon.eval/of-agent` answers the
agent's evaluations in order and each one's call row, with no `:seon.cluster.eval/run` stored.

## Cost
Per eval record: ~5 attributes fewer (each ~1.5-2.5 node objects, 4-10 KB, on default: census §1); readers add one or
two EAVT seeks per entity they already hold.

## Dependencies
TX-0, EN-1 (`assertion-tx`); TX-8b after RP-14, RP-15d; TX-8c after S4-01, S4-03. NK-5 deletes `origin` separately.
Reset: none (retired attributes stay declared until adoption removes them).
