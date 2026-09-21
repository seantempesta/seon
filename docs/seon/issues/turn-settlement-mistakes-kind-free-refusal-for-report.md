---
type: issue
status: open
severity: blocker
created: 2026-09-21
tags: [issue, turn, database, error]
---

# Turn settlement mistakes a kind-free refusal for a transaction report

Live `default` PID 19386 recorded signature
`1f3311909efaaf42cf900d6185f1652e95726b7a96b5016d5b64105d7d95dafd`
five times for root's turn `8ca443e36fcb`. The occurrence at
2026-09-21T20:05:45.864Z says `seon.sci.eval/install-evaluated-rows!` received
nil at `:seon.db/db`, called from `seon.turn` line 4813. A read-only MCP pull
at database basis 536870959 confirmed those identities and count in 16 ms.

The call obtains its database from `(:db-after outcome)`, not from lazy SCI
acquisition. `settle-batch!` obtains `outcome` from explicit-connection
`seon.db/transact!` through `blob/with-publication!` and `phase`. The database
API returns either a transaction report or a flat refusal. Its error contract
does not require the legacy `:seon.error/kind` field, but batch settlement and
its consumer checked only that field. Such a refusal bypasses refusal
settlement, lacks `:db-after`, and reaches the installation contract as nil.

The original underlying writer refusal was not retained in the observed
installation fault; its full cause remains unknown. No transaction was
replayed to recover it. The page-package timeout signature
`786ada69cf1ff11fd1b3f0ab71d5816962a77722da402e5b2af9af5b78b5ef1b`
occurred nearby but has not been established as a cause or consequence.

A second read-only probe at basis 536870960 (15 ms) passed a kind-free flat
error value through the loaded `seon.turn/phase`, without submitting a write:
the value was preserved, the legacy kind check was false, and `:db-after` was
absent. The affected turn still had no `:seon.turn/closed-tx`. This proves the
classification gap; it does not identify the lost writer diagnostic.

The bounded repair recognizes both existing legacy errors and the canonical
flat shape at batch/single settlement checks and their recording-failure
checks. The original refusal flows into the existing refusal settlement and
is returned as `:refused-outcome`; the failed batch is not retried. Optional
kind is omitted from settlement requests when absent, rather than stored nil.
No SCI acquisition or installation behavior changes.

The existing canonical regression
`seon.turn-loop-test/a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again`
now covers both a thrown commit failure and a real writer refusal with its
optional kind removed. It retains the writer's request identity, checks that
every begun evaluation settles, the turn closes, and the next message makes
another turn eligible. It uses the existing failure-injection seam, real SCI
evaluation, canonical database, and actual refusal-recording transaction.

No test JVM, operator action, adoption, paid execution, or live write was run
by the implementation lane. Root's canonical gate and post-adoption proof are
still required; this issue remains open.
