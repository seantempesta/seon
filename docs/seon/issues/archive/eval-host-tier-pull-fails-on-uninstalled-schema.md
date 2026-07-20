---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, architecture]
---

# Eval host tier pull fails when the tier attribute is uninstalled

## Problem

`seon.execution.host/pull-eval-host-coordinate!` pulls
`[:seon.execution.host/eval-socket-path]` at the turn's pinned database
value before every `eval-batch!` invocation. On a database where that
attribute has never been transacted (no agent has ever been host-tier),
Datahike rejects the pull selector as an unknown attribute, the tier read
errors, and — by the documented loud-failure design in `invoke-now!` —
EVERY eval-batch turn fails `:error` with "The agent's execution tier fact
could not be read." A registered-but-never-transacted optional attribute
must not take down the child lane that does not need it.

## Evidence (2026-07-20, default cluster, branch codex/runtime-reliability-refactor)

Live `seon.agent.turn/run-turn!` drive for `root` at basis-t 536871277:

```text
SEON-CORE-FAULT The agent's execution tier fact could not be read.
{:seon.error/cause "Bad entity attribute :seon.execution.host/eval-socket-path
 at (resolve-datom db 10 :seon.execution.host/eval-socket-path nil nil),
 not defined in current schema {:error :transact/schema, ...}"}

```

`schema/register! ::eval-socket-path` exists (`src/seon/execution/host.cljs:40`),
but registration alone does not install the Datahike attribute; installation
happens at first transact, so a cluster with zero host-tier agents pulls an
unknown attribute. Discovered by the frozen-turn-inputs retry-pinning lane
while live-proving `run-turn!`; unrelated to that change (the failure is in
the pre-existing tier lookup).

## Owner

`seon.execution.host` (`pull-eval-host-coordinate!` / the schema-install
boundary for `::eval-socket-path`).

## Acceptance

- On a database where `:seon.execution.host/eval-socket-path` was never
  transacted, an `eval-batch!` invocation for a child-tier agent routes to
  the child lane and succeeds (no tier-read error).
- A genuinely failed tier read (writer down, malformed fact) still surfaces
  loudly — the fix must distinguish "attribute uninstalled → no fact → child
  lane" from "read failed".
- One regression covering the uninstalled-attribute case.

## Resolution (2026-07-20, U4 lane)

Root cause: the tier lookup used a PULL, and Datahike rejects a pull
selector naming an uninstalled attribute; a Datalog query treats the same
unknown attribute as zero datoms. Reading a possibly-never-installed
optional attribute is legitimate presence semantics, so the one owner
(`seon.execution.host/pull-eval-host-coordinate!`) now reads the fact with
a presence query at the pinned database value. No error-string matching,
no swallowed failures: a real read failure still returns its error
envelope and fails the turn loudly, exactly as before.

Proof, live default cluster (basis after the fix, agent
`few-months-clap` minted via `POST /agents`): a real
`seon.agent.turn/run-turn!` drive with a scripted llm-fn closed
`{:seon.agent.turn/status :done, :seon.agent/eval-count 1}` where the
identical drive previously failed "The agent's execution tier fact could
not be read"; the recorded eval row reads back
`["(+ 20 22)" true "42"]`. A direct live probe confirmed the split: the
presence query returns `nil` while the old pull returns the
`:transact/schema` rejection envelope.

Regression: `uninstalled-attribute-query-is-no-fact-while-pull-rejects`
in `test/seon/host_registry_writer_test.clj` pins the boundary contract
against the real memory-backend writer (query → no fact; pull selector →
error value). Green inside the focused run (5 tests / 26 assertions).
