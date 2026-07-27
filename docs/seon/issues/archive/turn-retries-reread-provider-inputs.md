---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, database, architecture]
---

# Freeze one turn input across provider retries

Owned by the [[../../prds/archive/frozen-turn-inputs/roadmap]] chunk (impurity rows
I6-I8; the named `bounded-llm-attempt!` conn-deref evidence is recorded
there as already fixed in source). Stays open until that chunk's stage 1
and 5 land commit plus live proof.

Stage 1 landed 2026-07-20 (retry-pinning lane): I6-I8 closed with commit
plus live proof — the attempt cap resolves once into the turn's frozen
resolution (`seon.ai/resolved-config-from-rows`), the final pull pins to
the close transaction's returned `:db-after` database value, and a missing
pinned `:seon.db/db` is a loud `:core-bug` error value at `run-turn!`.
Acceptance items three and four below are green (regressions
`retry-attempts-share-one-frozen-attempt-cap`,
`run-turn-pins-the-final-pull-to-the-close-transaction`,
`run-turn-without-a-pinned-database-value-fails-loudly`; live 429 drive
with a concurrent transaction, evidence in the roadmap's Current
position). Remains open for the chunk's stage 5 byte-identity gate.

## Problem

One agent turn does not currently consume one immutable database-derived
input. Each provider attempt reads the ambient connection again, so a retry
can change model configuration, system text, retry policy, or recorded
coordinate after the prompt was rendered.

## Evidence

`seon.agent.turn/bounded-llm-attempt!` dereferences `seon.db/*conn*`, calls
`seon.ai/resolved-config`, and obtains a head coordinate inside the retry
thunk. `llm-retry-strategy` independently calls the ambient
`seon.ai/agent-max-retries`, while provider adapters resolve the system text
from ambient configuration unless the request supplies it. The existing
`retry-persists-ordered-immutable-config-drift` test explicitly expects the
second attempt to use a later model, timeout, endpoint, and commit.

The database protocol already supports one coordinate-pinned `execute-many`,
but the context renderer is still a synchronous symbol-dispatch pipeline over
local Datahike values and lazy entities. There is therefore no honest partial
provider-only cut: it would preserve a second local observation path for the
prompt and merely move the inconsistency.

## Owner

`seon.agent.turn` owns one coordinate-pinned turn acquisition.
`seon.agent.ctx` and its transcript, namespaces, plan, canvas, and root-only
renderers own pure consumption of the ordinary acquisition results. Provider
configuration resolution consumes the agent, AI config, and cluster config
maps from that same result.

## Acceptance

- One turn resolves a head once and performs one coordinate-pinned
  `execute-many` before rendering or provider dispatch.
- Core context rendering consumes only ordinary member results; no turn path
  dereferences a connection, traverses a Datahike entity, or silently re-reads
  the head.
- Prompt, system text, provider resolution, retry count, REPL mode, current
  namespace, and every attempt row retain the same coordinate even when a
  concurrent transaction lands before retry two.
- The close transaction's returned coordinate pins the final asynchronous
  pull, so a later head cannot change the returned turn.
- A failed required member produces one error value and zero provider calls.

## Triage — 2026-07-23

DISSOLVES into the P4 loop-migration slice: resumable database run-state steps
and the U12 pod-kill/restart proof subsume the remaining frozen step-input and
byte-identity acceptance.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
