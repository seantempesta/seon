---
title: "Card EN-3 (S5-06): A JVM eval request acquires no SCI world"
type: reference
status: active
created: 2026-10-08
---

# EN-3 (S5-06) A JVM eval request acquires no SCI world

Ledger row S5-06 / EN-3 (group E). agent-loop.md §3.2 (MCP JVM row): "its result is bound into the agent's SCI
result owner only when that context is already held, otherwise its handle answers gone-value data with the class".
Lane: Sonnet. Cited at HEAD `e7f120672`.

## What exists
`seon.mcp/jvm-run` (`src/seon/mcp.clj:265-280`) calls `(agent/acquire-context! handle agent-id)` to get a ctx for
result handles, so a JVM eval request of an idle agent builds (or forks) its SCI world.

## Change
- ADD `seon.cluster.agent/held-context`
  `{:malli/schema [:=> [:cat :seon.agent/context-source :seon.agent/id] [:maybe :seon.sci.eval/ctx]]}`
  `[handle agent-id]`: `(some-> (get-in @(:seon.agent/routing handle) [:seon.agent/contexts [branch agent-id]]) <deref the held entry> :seon.sci.eval/ctx)`,
  where `[branch agent-id]` is the key `acquire-context!` acquires under (`acquire-once! roster [branch agent-id] …`,
  `src/seon/cluster/agent.clj:1081`) and an entry still acquiring (no connection yet) counts as not held. Never
  creates.
- `jvm-run`: `:seon.sci.eval/ctx (agent/held-context handle agent-id)`, assoc'd only when non-nil.
- With no ctx, `seon.turn.batch/jvm-show` (`src/seon/turn/batch.clj:911`) binds no handle; the reply's
  `:seon.repl/result` is absent and the value is printed as today. A later SCI read of that handle answers the existing
  gone-value data (`rg -n 'gone' src/seon/sci/eval.clj` names it) with the value's class: when no ctx holds the value,
  jvm-show stores `:seon.eval/value-class` (it already does for evaluations) so the gone answer can name it.

## Proof
`seon.mcp-entrance-test/a-jvm-call-does-not-acquire-an-sci-world` (new): a fresh agent's first call is JVM mode
`"(+ 1 2)"`; afterwards `(seon.cluster.agent/held-context handle agent-id)` (JVM read) is nil and the reply's value is
`3`. Then an SCI call makes the context; a following JVM call's reply carries `:seon.repl/result`, and reading that
handle in SCI answers `3`.

## Files / commit
`src/seon/mcp.clj`, `src/seon/cluster/agent.clj`, `src/seon/turn/batch.clj` (only if jvm-show needs the class), `test/seon/mcp_entrance_test.clj`.
`bin/lane commit <lane> -m "EN-3: a JVM eval request binds its result only into a context already held" --test seon.mcp-entrance-test --test seon.cluster.mcp-test src/seon/mcp.clj src/seon/cluster/agent.clj test/seon/mcp_entrance_test.clj`

## Cost
Expected: a JVM eval request of an idle agent no longer pays a context acquisition (PB-04 measured host+agent creation
137-141 MB for a first pass; the acquisition share is RP-0's to name). Report the O probe's JVM row.

## Dependencies
None. Reset: none.
