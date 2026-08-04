---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime, concurrency, durability]
---

# Make concurrent definition receipts agree with the durable program row

## Problem

Two concurrent redefinitions of one function can both settle as successful
while the live Var and durable program row disagree. The later successful
receipt can publish no durable replacement, so a context rebuild silently
changes behavior.

## Evidence

Runs `streams-same-run-a` and `streams-same-run-b` both returned a Var face for
`streams.same/collision-value`. A's defining receipt committed at transaction
`536871077`; B's committed later at `536871081`. History contained only A's
`:seon.fn/source` assertion at `536871077`. The shared live Var returned B,
while the durable program row remained A. After restarting the scratch cluster,
the function returned `{:writer :a, :iteration 1}`.

A separate 1,000-update-per-writer collision using `alter-var-root` observed
only complete A or B maps and no torn root, so the defect is durable admission,
not JVM Var atomicity. The complete queries are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The per-run candidate-context and durable-placement transaction boundary.

## Acceptance

- Concurrent redefinitions serialize to one declared winner.
- A losing divergent definition gets a flat refusal value; it never receives a
  success-shaped receipt.
- Immediately after settlement and after context rebuild, the live Var source
  equals the one durable `:seon.fn/source` row.
- Repeated collisions retain whole Var roots with no torn value.
