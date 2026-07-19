---
type: research
status: complete
tags: [research, agent, database, pod]
---

# Inspect product-scenario contracts

## Decision

Namespace-targeted launch, cross-agent function reuse and repair,
execution-child recovery, and pod restart use one native Inspect mechanism.
The agent work enters through the existing `POST /agents/run` door. The scorer
does not trust replies: after the work, it consumes one ordinary database
snapshot containing the exact agents, namespace refs, messages, function
history, eval/turn status, recovery facts, diagnostic-blob ref, child process
identities, and post-restart read result required by that scenario.

The static HTTP door intentionally cannot prove the whole scenario alone. It
returns only the driven agent's request-window evidence; it neither owns a pod
restart nor exposes arbitrary cross-agent database pulls. Live execution
therefore needs the pending ownership-fenced Inspect target lease to provide
two narrow host operations: restart its exact pod and read the final immutable
database value. This is not another agent execution transport.

## Dependency ledger

- Inspect AI source is `reference-code/inspect-ai` at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`. The implementation uses its
  ordinary `Task`, `MemoryDataset`, custom solver, scorer, `Score`, and native
  accuracy reduction seams.
- Seon's execution seam is `seon_inspect.solver.pod_run`, which posts to
  `/agents/run`, reuses the explicit root agent ID, and retains request-window
  evidence.
- The restart/read-back split follows the existing two-phase planning driver,
  but does not call the deliberately unavailable legacy cluster lifecycle.
- Required database attributes and states are grounded in
  `docs/seon/architecture/data-model.md` and
  `docs/seon/architecture/agent-runtime.md`.

## Offline contracts

The four checks fail closed over database snapshots:

- namespace: exactly one nonterminated resident, two explicitly routed
  messages to its stable agent ID, and first eval in the requested namespace;
- reuse and repair: consumer calls the qualified function without redefining
  it, repair advances the same function identity, no parallel function exists,
  and a fresh test passes;
- child recovery: eval and turn are interrupted, recovery joins the failed
  child and diagnostic blob, exactly one replacement exists, the crashing eval
  was not replayed, later work succeeds, and a sibling remains done; and
- pod restart: pod identity changes while database and agent identities remain,
  followed by a successful database read of the pre-restart value.

Every scenario has a known-good and one decisive known-bad fixture evaluated
through the real native scorer. The live driver tests also prove both phases
reuse root, restart changes the second URL, final evidence is read only after
work, and an unowned restart fails loudly.

Measured evidence:

- focused product/proof/milestone/generator slice: **145 passed**, 13
  dependency-deprecation warnings, 4.27 seconds;
- complete offline Inspect suite: **523 passed, 8 skipped**, 13 dependency-
  deprecation warnings, 20.06 seconds; and
- expanded offline proof: **24 arms**, all declared primary means matched and
  the command exited zero.

## Live graduation dependency

Do not run these scenarios until the operator exposes the ownership-fenced
Inspect lease. Its final database reader must return the documented facts from
one database value and retain basis transaction plus commit ID in the native
Inspect log. Then run each fixed scenario three consecutive times and retain
the native logs; model narration remains supporting evidence only.

## Lease and typed read implementation

Inspect now consumes the completed isolated operator lifecycle directly:
`bin/seon branch open|restart|close|status NAME`. The retained branch name is
the ownership identity, status supplies the dynamic pod URL and complete
operator identity bytes, restart must change those bytes, and release closes
that exact retained branch. No default-cluster lifecycle operation is used.

The pod exposes one loopback-peer-only
`POST /_seon/operator/product-evidence` operation. Its JSON request contains
`seon.db/query` as an EDN Datalog string and optional `seon.db/args`. The
handler acquires one ordinary database value, calls the existing `seon.db/query`
once against that value, and returns `seon.db/ok?`, the database value's
Datahike fields, and JSON-safe ordinary `seon.db/result` data. It cannot eval
code, write data, or select another database.

Focused proof:

- Python lease, product-scenario, and legacy cluster tests: **46 passed**;
- CLJS serve/router tests: **30 tests, 113 assertions**, all pass.

The coordinated live fixed-scenario checkpoint opens a unique retained branch,
drives both phases through its `/agents/run` URL, reads the final facts through
the product-evidence operation, restarts only through `branch restart` for the
restart row, and closes the same branch in `finally`. The top-level runner must
provide each scenario's explicit Datalog projection into the documented scorer
snapshot; the generic transport intentionally does not contain scenario names
or hidden query templates.
