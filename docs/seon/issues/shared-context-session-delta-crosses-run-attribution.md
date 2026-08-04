---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime, concurrency, durability]
---

# Isolate session deltas from other runs' context mutations

## Problem

One run can persist another concurrent run's live definition as its own
session-image change. The shared cluster context is therefore not only visible
across runs; it corrupts durable attribution.

## Evidence

On scratch cluster `concurrency-streams-0804`, runs
`streams-contracted-a` and `streams-contracted-b` concurrently declared
`streams.shared/alpha` and `streams.shared/beta`. The program rows were
correctly attributable by terminal transaction: `alpha` at transaction
`536871061` belonged to A, and `beta` at `536871062` belonged to B. But the
same transaction `536871062` also asserted `:seon.code.def/id
"streams.shared/alpha"`, making B's terminal transaction claim A's definition.

`seon.sci.eval/changed-session-defs` compares the complete mutable context
before and after one evaluation, while `seon.cluster.loop/session-image-tx`
persists every reported candidate. A concurrent mutation falls inside that
process-wide comparison.

The complete repro and fact queries are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The ruled per-run `sci/fork` execution-context wave. Session delta derivation
must compare one run's candidate context, never the live cluster context.

## Acceptance

- Two concurrent runs declaring different names in one namespace each persist
  only their own program/session rows.
- Every durable definition row is attributable to its defining run and agent.
- A deterministic collision regression queries terminal transactions and
  proves no cross-run `:seon.code.def` assertion.
