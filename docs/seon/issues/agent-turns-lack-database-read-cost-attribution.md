---
type: issue
status: open
tags: [database, agent, issue]
severity: friction
---

# Agent turns lack database read-cost attribution

## Problem

The durable turn/eval graph records exact prompt and model bytes, token counts,
eval source, and provider attempts, but it cannot answer how many database
operations context construction performed, whether those queries hit the
Datahike cache, how much engine work they charged, or how much typed-request
latency they consumed.

A current-artifact live run at Seon `15acdaf9` took 211,126 ms, rendered about
28k context tokens for each of 37 turns, and repeated `my.plan/active!` 27
times. Its three explicit database eval forms are not the actual read count:
the execution child performs composed reads that are invisible in eval source.
See
[[../../prds/reactive-render-units/research/agent-read-cost-live-measurement-2026-07-19]].

## Owner

Strengthen the existing `seon.db.internal/run-with-read-evidence` scope and
ordinary execution result/turn capture. Datahike's existing cache/resource
evidence is the producer authority. The existing agent debug page is the
consumer. Do not add a log scraper, profiler registry, second database, or
public-function wrapper census.

## Current state (2026-07-20)

Read requests now carry the requesting identity and the writer attributes
spend per requester:

- Protocol v12: `query`/`pull`/`pull-many`/`schema`/`index-page` requests and
  every `execute-many` member accept optional `:seon.db/user` and
  `:seon.db/process` — the exact write-side provenance vocabulary
  (`src/seon/db/protocol.cljc`).
- The pod attaches the ambient fiber identity
  (`seon.db.internal/selected-provenance` over tx-context + agent scope) to
  every outgoing read (`seon.db/query`, `pull`, `pull-many`, `execute-many`
  members).
- The writer records per-request spend (engine `:datahike.resource/*`
  evidence for queries, duration for every read) into one bounded
  most-recently-active rollup keyed by identity —
  `seon.db.writer/read-spend`, LRU-bounded via the fork's existing
  `datahike.lru/weighted-lru` (256 identities, structural eviction).
- Callers already see their own query cost on the response envelope
  (`:datahike.query/resource-evidence`; `seon.db/query-with-evidence` on the
  pod, and the same-named wrapper in the JVM host context).

Remaining for full acceptance below: pull-path resource evidence (the fork's
`pull-spec` charges the budget but never publishes evidence — mirror
`datahike.query`'s `publish-evidence!` at
`reference-code/datahike/src/datahike/query.cljc:4519` into
`pull_api.cljc/pull-spec`), turn-level aggregation onto the durable turn, the
`:off`/`:aggregate`/`:trace` dial, and the debug-page waterfall. Host-context
reads are still identity-less (the shared wrapper closure has no per-agent
scope) and aggregate under the empty identity.

## Acceptance

- Database configuration plus environment override selects `:off`,
  `:aggregate`, or bounded diagnostic `:trace` behavior.
- `:off` has a proven no-measurement path.
- `:aggregate` attributes operation counts, request duration, Datahike
  cache/resource evidence, context/model/eval duration, tokens, and applicable
  reactive delivery facts to the exact durable turn.
- `/agent/{id}/debug` derives a compact waterfall and totals from the same turn
  projection; absent evidence is visibly unmeasured, never zero.
- Existing dependency-plan capture, reactive invalidation, result values,
  retries, failures, and resource cleanup remain byte/behavior compatible.
- Focused tests and a live representative run prove attribution and measured
  overhead before this issue closes.
