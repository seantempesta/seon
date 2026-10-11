---
title: "Card AE-3 Every reader of agent facts calls the record once per eval request"
type: reference
status: active
created: 2026-10-08
---

# AE-3 Every reader of agent facts calls the record once per eval request

Index: [README-agent-entity](README-agent-entity.md). Lane: Sonnet (scripted sweep, then hand fixes).

## What becomes true
An eval request reads `seon.agent/record` once, when its world is built (`seon.turn.world`), and the record travels
in the world as a value supplied by schema name (`:seon.agent/record`). The held task, its proofs, the block and the
next work derive from it; no function looks up the home by hand.

## Functions
- CHANGE, each to take or read the record: `seon.context/current-state`, `seon.render.ns/held-task`,
  `seon.turn/next-agent-work`, `seon.turn/task-proofs`, `my.task` (the three `home-connection` reads),
  `my.program/request-merge!`, `seon.cluster.status` (its home read).
- DELETE `seon.task/agent-task` (its callers read `:seon.agent/assignments` of the record, oldest held first) and
  `seon.task/home` (callers use `seon.agent/home`); `home-connection` stays for task writes.

## Deleted
Seven `(or (some-> (task/home db) db/db) db)` lookups; `seon.task/agent-task`.

## Proof
`seon.turn-work-cost-test/an-eval-request-reads-the-agent-record-once` (new, measured cost through
`seon.profile/measure`, through `seon.mcp/call` as a branch agent holding a task): one eval request of `(+ 1 2)`
makes exactly one armed call of `seon.agent/record` and none of `seon.agent/home` outside it. Red today (no record;
several home lookups per request). CX-2's and cd1e3455c's proofs (`seon.turn-work-test/a-branch-agent-assigned-after-its-fork-opens-its-model-turn`)
stay green.

## Cost
One record read per eval request instead of three to five home lookups; report the eval request's ms before and after.

## Dependencies
AE-1, AE-2. Commit with `--test seon.turn-work-test --test seon.turn-work-cost-test --test seon.context-test --test seon.task-ingestion-test`.
