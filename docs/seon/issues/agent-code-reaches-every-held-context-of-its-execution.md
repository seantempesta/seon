---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, custody, isolation, sci, agent, my-program]
---

# Agent code reaches every held context of its execution through the environment

## Evidence (source, HEAD a5cb41426; default pid 94554, 2026-09-26 ~00:55Z)

- `my.program/supplied-context` (`src/my/program.clj:411-430`) builds the agent-facing `:my.program/context` and copies
  `(select-keys environment [:seon.store/store :seon.agent/context-state])` into it.
- `:seon.agent/context-state` is the execution's live context roster: an atom mapping `[branch agent-id]` to the whole
  execution handle (connection, database, base and agent SCI contexts, projection, environment, routing and ownership
  flags; 16 keys measured on default). It is the *same atom* in the cluster handle and in the root agent's entry
  (identity probe `true` on default). It is carried into the environment at `src/seon/cluster/agent.clj:1047`.
- `my.agent/branch` (`src/my/agent.clj:73-76`) dereferences that roster to read its own `:seon.agent/mode`. Any
  `my.*` function receiving `:my.program/context` can equally dereference every other entry: another agent's SCI
  context, its connection, its routing, and therefore its armed graph and mailbox.

**Current state (30e6e8f75).** The context-state atom merged into the execution's roster (`:seon.agent/routing`,
held contexts under `:seon.agent/contexts`), and the environment and `my.program/supplied-context` now carry that
roster. Agent code therefore reaches the same held executions and, through the same value, the armed entries and
mailbox channels. The acceptance below reads `:seon.agent/routing` for `:seon.agent/context-state`.

## Why it is a defect

An agent's SCI world is its private universe (AGENTS "one JVM, many realities"). Prompt visibility grants no execution
permission, yet here the environment hands agent code the live objects of every agent held by the same execution. The
value `my.agent/branch` needs, the mode, was decided once by the acquisition (`acquire-context!`). It is a value of
this agent's own execution, not a lookup in a shared roster.

## Owner and acceptance

The owner is `seon.cluster.agent/acquire-context!` together with `my.program/supplied-context`.

Acceptance:
- The agent-facing context carries this agent's own `:seon.agent/mode` and `:seon.agent/branch` as values, and no
  roster.
- `:seon.agent/context-state` leaves the environment. Under the adopted state architecture (39a026318) the roster is
  reachable only through the execution that owns it and from the process root for status.
- One regression: from agent A's SCI context, no `my.*` call returns or dereferences agent B's held execution.

Found by `docs/research/agent-platform/system-state-opinion-2026-09-26.md` §9.
