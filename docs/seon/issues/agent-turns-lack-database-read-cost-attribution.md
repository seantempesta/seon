---
type: issue
status: open
tags: [database, agent, issue]
severity: friction
---

# Agent turns lack database read-cost attribution

## Triage — 2026-07-23

REAL+INDEPENDENT (L), owned by turn observability and the database read-evidence
projection. `src/seon/db.cljc:543-555` exposes query evidence, but no current
turn-level duration/resource aggregation closes the issue; P4 does not include
this observability acceptance.

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
[[../../prds/archive/reactive-render-units/research/agent-read-cost-live-measurement-2026-07-19]].

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
`:off`/`:aggregate`/`:trace` dial, and the debug-page waterfall.

## Load-measurement reconfirmation — 2026-07-25

The surviving JVM driver makes the missing attribution concrete. A current
one-form `/agents/run` request performs six source-derived transaction
boundaries inside its wall clock: message, run, turn, execution plan, running
eval, and terminal eval plus lifecycle/reply. It persists
`:seon.eval/duration-ms`, but not model-call duration, individual transaction
duration, context-derivation duration, or publish duration. The driver passes
the request message directly as eval context, and the terminal transaction
combines completion with the reply, so there is no separate context or publish
phase to time in this path.

An exact end-to-end waterfall therefore cannot be reconstructed from current
facts. Any subtraction that called the remainder “model”, “context”, or
“publish” would turn unmeasured work into a number. The 2026-07-25 saturation
work records this as the first observability resource to fail and makes no such
claim.

Host-context reads carry per-agent identity since U4 (2026-07-20):
`seon.host.context/*agent-id*` is bound around every host invocation
(`seon.host/run-invocation!` eval-batch branch and startup restore), and the
shared `seon.db` wrappers attach `:seon.db/user [:seon.agent/id id]` +
`:seon.db/process [:seon.db.process/id :seon.db.process/repl]` to query/pull
requests and as transaction metadata on writes — the empty-identity
aggregation gap for host-tier agents is closed.

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
