---
title: "Context review round 2: the block's must-reads reworked at their producer"
type: research
status: complete
created: 2026-10-12
---

# Context review round 2

The current-state block for the three reviewed tasks, before and after the must-read rework in
`seon.ref/references` and `seon.context/current-state`.

## Method

Default ran af0a94471 (JVM mode reads, no restart). Each block is the value of
`(seon.context/current-state db agent-id)` evaluated in SCI mode through `seon.mcp/call`, where `db` is a
`seon.db/with` of the agent's branch head holding a fixture agent `ctx-dump-<handle>` and an assignment of the task
(task revision from `seon.task/task-revision`); nothing is committed. "Before" ran as agent `block-base`, which holds
HEAD's definitions; "after" ran as agent `block-rework`, whose branch holds the changed `seon.ref` and `seon.context`
definitions and schemas. The whole-block size is the reply's `:large-value` finding, `:seon.eval/value-tokens`
(`seon.ai.tokens/estimate`); the printed reply is cut at the running JVM's 2,000-token display budget.

## Sizes (estimated tokens, whole block)

| Task | Before | After |
|---|---|---|
| `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` | 3,755 | 3,078 |
| `seon.ai/contract-output-observed?` | 1,370 | 1,063 |
| `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` | 12,638 | 2,873 |

Every block is under the 4,096-token `:large-value` bound after. The merge task's must-reads were 398 reaching tests of
`seon.db/vector-query-call` by name; a function now lists by name only the task's own proofs among its reaching tests,
counts the rest (`:seon.ref/test-count`) and offers `(my.program/tests-reaching ...)`.

## What changed in the data

- A program function's must-read shows its contract as the agent writes it (`:malli/schema`, data), not the stored
  `:seon.fn/spec` text; its operations are the source read, plus the reaching-tests list when tests are counted.
- `(doc ...)` and `(my.program/callers ...)` operations are gone (the doc line and the whole caller list are shown).
- Schema-key must-reads carry their form and no operation; `:seon.ref/line` and a doc section's `:seon.ref/tokens`
  are gone.
- The task's own proofs are not repeated as must-reads (the proofs section shows them).
- A problem carries `:seon.ref/message`: what failed and what to do.
- `:seon.context/start` is `{:seon.render/operations [(in-ns 'the.ns)]}`: a form to run. The old
  `(my.program/source 'task/sym)` read the task's symbol, which names no definition.

## Not changed, with evidence

- `:proof-status :pending` in the review captures is the capture method, not the producer: the fixture agent sits on
  main, which holds no test runs. The real agent `attempt/a82982ee0f9e` on its own branch reads `:red` for two proofs
  with every failing assertion's `:expected-text` and `:actual-text` (`seon.context/head-entries`), and `:green` for
  `seon.sci.forgive-test/the-repairs-account-for-every-change`.
- Failure claims stay the runner's stored text (`seon.test.runner` stores the printer's shown text).
