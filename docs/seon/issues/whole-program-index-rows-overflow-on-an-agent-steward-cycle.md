---
type: issue
status: resolved
severity: defect
created: 2026-09-24
tags: [issue, publication, seon.fn]
---

# Whole-program index rows overflow the stack on an agent steward cycle

## Problem

`seon.fn/published-index-rows` with one argument (the whole program) throws
`java.lang.StackOverflowError` on default. `seon.fn/index!` with a populated
`:seon.source/database` and `seon.cluster` (cluster.clj:2608) call that arity.

`[:seon.ns/name my.agents.root]` holds `:seon.ns/steward`, a plain ref to the
agent entity 42410. The agent has no program identity, so `reference`
(fn.clj, `published-index-rows`) inlines it as a row. The agent's component
runtime and turns point back to the agent through `:seon.turn/agent` and
`:seon.runtime/agent`. The recursion never ends.

## Evidence

MCP `eval_clj`, JVM pid 99265, 2026-09-24 ~19:22Z, with the old per-row code
and again with the batched code of lane fix-f4-batched-index-rows:
`(seon.fn/published-index-rows (seon.db/db (seon.cluster.boot/connection "default")))`
threw StackOverflowError. The first identity that overflows is
`[:seon.ns/name my.agents.root]`. `seon.fn-test/indexing-refuses-an-already-populated-branch`
is red with the same StackOverflowError on HEAD `36af5c620`. Before the batching,
the same test instead ran past its 63 s remainder.

## Owner

`seon.fn` and the program/agent partition. `:seon.ns/steward` is an agent fact
on a program row. The partition that the entity schemas declare must stop
program rows from following refs into agent entities. This lane did not choose
that rule.

## Resolution (lane fix-index-rows-steward-cycle, 2026-09-24)

The partition is declared: the agent entity schema carries
`:seon.program/partition :seon.data`, and `seon.program/program-attributes`
derives the program attributes from the projection. `published-index-rows`
now writes a ref to an entity with no program row identity and no program
attribute as that entity's declared `:db.unique/identity` pair
(`[:seon.agent/id "root"]`), and `reached-entities` stops descending at such
entities. No cycle guard: the partition rules the cycle out. Evidence and
timings: `docs/prds/agent-platform/landing/lane-fix-index-rows-steward-cycle-2026-09-24.md`.
