---
type: issue
status: open
tags: [database, agent, issue]
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
