---
type: landing
lane: one-commit-a-opus (B again, owner-approved 2026-09-27)
date: 2026-09-27
status: implemented; static checks green; measurement pending restart; kept only if clearly faster; RESET NEEDED
---

# B, second time: reply when the settlement is applied, durable in the background

B is fcee85161, deleted in de67fdd1a because the MCP bridge re-read the connection and got no value. Cut 1
(be5ae598f) removed that blocker: the reply carries the settlement's own value (`:seon.db/db`), and MCP
reads the run there (`seon.cluster.agent/run-observation`).

## What came back

- **Datahike fork (`reference-code/datahike`, 2ace4cee, pushed).** The writer's `transact!` has an
  `:applied` port again. It answers once the writer has applied a transaction, in writer order, before the
  batch commits. This is c6c0f80d restored; its revert was df4bcddb.
- **`seon.db/transact-applied!`.** The one general fire-and-forget write. It goes through `transact!`'s
  validated path (`transact-through`) and answers the applied report together with its pending commit
  (`:seon.db/durable`). A failed commit goes to the caller's `durable-failed!`, off the writer's thread.
- **`seon.turn/settle-batch!`.** Its success commit is the one opt-in. When a durable commit fails,
  `settlement-lost!` records a fault naming the lost runs and stops the agent.
- **`seon.blob/with-publication!`.** It holds the reachability permit until the applied write's commit
  resolves.

## What is new compared with the first B

**The next pass waits for the commit instead of reading a newer uncommitted value.**
- The pass keeps the report's `:seon.turn.loop/basis` and `:seon.db/durable` in the proc state.
- `await-settled!` waits for the previous commit only when the connection has not caught up with the basis.
  The wait uses `await-durable!`, bounded by `:seon.config.agent/turn-completion-backstop-ms`.
- So every read of the next pass sees the run: the work derivation, the SCI fork, the forms, the install
  gate, the program-change check and the trigger.
- The first B only swapped in the step's own database value, while `resume-turn` still read the connection.

## Who reads a just-settled run, and on which value

| Reader | Value it reads |
| --- | --- |
| MCP `run-form` | The reply's `:seon.db/db`, through `run-observation`. `:seon.db/durable` is removed before printing. |
| The agent's next context and next pass | The committed value, reached through `await-settled!`. |
| `seon.turn/virtual-turn!` (fixtures, web page) | Waits for `:seon.db/durable` before returning, so fixtures that then read the connection see the run. |
| `test/seon/cluster/evaluate_source_test.clj` (`shown`, `settled`, note provenance, fault, one-commit-per-form) | The reply's `:seon.db/db`. |
| `test/seon/cluster/agent_test.clj` (`settle-run`) | The reply's `:seon.db/db`. |
| `test/seon/sci/render_source_test.clj` | The reply's `:seon.db/db`. |
| `test/seon/cluster/host_test.clj` (`hosted-run-receipts`) | Receipts on the reply's value; it waits for the commit because its callers then read the connection. |

## Measurement (to run after the restart)

- Script: `docs/research/agent-platform/landing/reply-when-applied-probe-2026-09-27.clj`.
- It samples 20 × `(inc 1)` and 10 × `defn` on a fresh branch.
- It reports answer p50/p95 against durable p50/p95.
- The first B measured 40.0 / 46.1 ms answered against 133.9 / 145.5 ms durable.
- B is kept only if it is clearly faster. Otherwise it is deleted whole again.
