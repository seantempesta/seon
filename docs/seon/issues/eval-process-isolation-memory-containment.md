---
type: issue
status: open
severity: blocker
tags: [issue, agent, flow, architecture]
---

# Arbitrary eval allocation lacks hard process memory containment

## Problem

Datahike query/pull execution and retained eval values now have synchronous
budgets, but arbitrary JavaScript or dependency code can still allocate between
runtime checkpoints. The CLJS sandbox catches model mistakes; it is not a
security or hard memory boundary. A pathological predicate or direct JS form
can therefore exhaust the pod process even though it cannot exhaust the sole
writer through the bounded database API.

## Evidence

The 2026-07-14 memory-safety implementation adds work, result-node, and shallow-
weight budgets inside maintained Datahike and bounded structural admission for
`result/<id>`. Those mechanisms deliberately avoid claiming that they can
preempt arbitrary synchronous code. The live default pod recovered from 100
budget-exhausted queries, and a 300 KB retained result became a compact
descriptor, but neither probe is a process-level adversarial allocation test.

## Owner

The agent runtime's process-isolation and restart boundary, coordinated with
the one operator. Do not add a second evaluator, move arbitrary eval into the
writer, or describe SCI as a security boundary.

## Acceptance

- A bounded worker/process contract contains arbitrary synchronous eval memory
  and CPU failure without losing or wedging the writer, supervisor, or cluster.
- The agent loop records a structured failure and can continue or restart from
  database state.
- The default live cluster proves recovery from a killed or exhausted worker.
- Inspect uses the same runtime contract rather than a bespoke drive path.
