---
title: "Card CC-1: The reply counts the commits its request made"
type: reference
status: active
created: 2026-10-09
---

# CC-1 The reply counts the commits its request made

Proof card for step 1 ([README](README-commit-cost.md)). Lane: Sonnet; bow out after fifteen minutes with the measured
cause.

## What exists
`seon.cost/transactions` in the reply is `seon.profile/sink-counts` over the world's call sink, counting calls of
`seon.db/transact-call` (`seon.profile/transaction-entrances`). Measured 2026-10-09 against branch head advances:
a root JVM-mode request answered `:transactions 0` while it committed once on `:cluster-default` (its recording runs
outside the sink window); a `defn` with one re-executed reaching test answered `:transactions 4` while the agent's
branch advanced by 3 commits (one transact call either reached a test-world overlay or was coalesced by Datahike's
writer into another report's commit). The step-1 cards prove their saving with this count, so it must be the durable
commits.

## Change
First find which: count `:datahike/commit-id`s distinct across the request's tx reports. Then count commits, not
calls: the reply's `:seon.cost/transactions` is the number of distinct commit ids the request's transact calls
returned, including the recording `settle` writes for a JVM-mode request; a call refused before the writer is not a
commit. Fix it at the producer (`seon.profile/sink-counts` or the recording's sink window in `seon.turn`), not by a
second counter.

## Functions
- CHANGE `seon.profile/sink-counts` or the caller that excludes the recording (`seon.turn` cost assembly,
  `seon.mcp` reply).

## Proof
`seon.turn-test/a-replys-transactions-are-its-durable-commits` (new): on an agent branch, `:ping` answers 1 and the
branch advanced 1; a root JVM-mode `:ping` answers 1 and `:cluster-default` advanced 1; a `defn` with one reaching test
answers what its branch advanced (3 today).

## Cost
Expected: one set of commit ids per request, a few µs.

## Durability
Unchanged.

## Commit
`bin/lane commit <lane> -m "CC-1: the reply counts the commits its request made" --test seon.turn-test/a-replys-transactions-are-its-durable-commits <paths>`

## Dependencies
None. No gitlink bump.
