---
title: "Card CX-3 No system turn before a model pass"
type: reference
status: done (55ede54a9)
created: 2026-10-08
---

# CX-3 No system turn before a model pass

Ledger row CX-3 (group B; replaces S4-06). Spec: agent-loop.md §3.4 (context is rendered live from current facts; no
pre-turn refresh, no stored freshness evidence). Lane: Sonnet. Deletion.

## What becomes true

`seon.turn/open-turn` opens the call row directly: no `system-turn` re-runs stored read-only evaluations before a
model pass, so no `:seon.repl/changes` diffs and no pulls the agent never wrote are recorded, and the 6,206
`seon.error/for-agent` calls of the live run's refresh disappear. Settlement stops writing each evaluation's stored
`:seon.cluster.eval/read-evidence` (its only consumer is the system turn; lane codec-print). The in-memory render
caching by read evidence (`seon.render/refresh-read-evidence`) is a different mechanism and stays.

## Deleted (by name; every caller converted)

- In `open-turn`: the `system-turn` call and the `:seon.turn/refused-system-agent` branch.
- `seon.turn/system-plan`, `seon.turn/generated-read-fault` and private helpers left without callers
  (`read-only-evaluation?`, `unit-read` if present: grep after the cut).
- The settlement's `:seon.cluster.eval/read-evidence` assoc and `seon.turn/receipt-read-evidence-tx`'s eval part if
  callerless.
- `{:seon.cluster.eval/read-evidence …}` from `seon.eval/history-selector` and `of-agent`'s default selector; the web
  reader of it in `seon.render.web` drops that mapcat.
- NOT here: `system-turn` itself (the web context action still calls it; CX-8) and the attribute schema (CX-10).
Net ≈ −150 source lines; tests asserting a refresh re-run are deleted with it.

## Proof

`seon.turn-test/a-model-pass-opens-without-a-system-turn` (new): two fixture-model passes with an unrelated write
between them; the agent's call-row count rises by exactly the two model rows, and the second pass's evaluations
carry no `:seon.cluster.eval/read-evidence`. D: the `seon.pass-probe/run-model` profile has no `seon.turn/system-turn`
and no `seon.error/for-agent` rows.

## Cost

D. Expected: the model pass loses one system pass per turn (the live run's 6,206 calls); report `run-model` before
and after.

## Dependencies and order

CX-1 (so the model's history no longer depends on refreshed rows). Files: `src/seon/turn.clj`, `src/seon/eval.clj`,
`src/seon/render/web.clj` (one reader), `test/seon/turn_test.clj`, `test/seon/turn_error_test.clj`. `--test
seon.turn-test --test seon.turn-error-test --test seon.rereads-test --test seon.cluster.turn-test`.
