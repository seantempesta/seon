---
title: "Card EN-8a: MCP SCI records through the one owner"
type: reference
status: active
created: 2026-10-08
---

# EN-8a MCP SCI records through the one owner

Ledger row EN-8a (S5-07c, group B). Design: `redo/entrants.md` §3 "One RECORD owner". Lane: Sonnet. Cited at HEAD
`e7f120672`.

## What exists
JVM eval requests record through `seon.turn.batch/settle` (`src/seon/turn/batch.clj:729`, reached from
`src/seon/mcp.clj:286`). MCP SCI eval requests run the fold with `(dissoc :seon.batch/settle)`
(`src/seon/turn.clj:5009`) inside `evaluate-sources` and record through `seon.turn/settle-batch!` (`:3542`) via
`settle-evaluated!` (`:3661`) from `resume-turn` (`:5132`).

## Change
For an MCP SCI submission (`:seon.turn.loop/submission` present and not a model eval request: the submission's run
request names no model attempt), `evaluate-sources` keeps `:seon.batch/settle` with
`:seon.turn.batch/record-call #'seon.turn/record-evaluated-call` and the submission's open as
`:seon.turn.batch/open-tx` (the `(:seon.db/tx-data submission)` that `with-submission` prepends today, `:3529-3536`),
which `record-facts` puts first in the recording transaction;
`resume-turn`'s MCP branch reads the batch's `:seon.batch/commit` instead of calling `settle-evaluated!`, and builds
its report from it (`:seon.turn.loop/basis` = the commit's `:seon.batch/after`). Post-commit installation
(`install-evaluated-rows!`, `resume-turn` `:5297-5316`) runs on the commit's report the same way.

## Proof
The EN-2 table's SCI row in `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure`: an MCP SCI
eval request whose world writer refuses keeps its values and answers the diagnostic; and the existing
`seon.cluster.evaluate-source-test`, `seon.repl-differential-test` rows stay green (the recording is equivalent).
Data assertion: after an MCP SCI `(defn f [] 1)`, the call row's transaction's `:seon.db.transaction/operation` is
`seon.turn.batch/settle` (TX-4's name), not `seon.turn/settle-batch!`.

## Files / commit
`src/seon/turn.clj`, `test/seon/mcp_entrance_test.clj`.
`bin/lane commit <lane> -m "EN-8a: MCP SCI eval requests record through settle" --test seon.mcp-entrance-test --test seon.cluster.evaluate-source-test --test seon.repl-differential-test --test seon.cluster.host-test src/seon/turn.clj test/seon/mcp_entrance_test.clj`

## Cost
Expected: unchanged commit count (1); `settle-batch!`'s measuring and fault windows are not run for this path
(S5-02, S5-05 delete them). Report the O probe's MCP SCI rows.

## Dependencies
EN-2, TX-4. Group B. Reset: none.
