---
type: issue
status: resolved
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

## Resolution

Commit `f2e1dd476` makes terminal admission compare a function declaration
with the run's immutable opening database value inside Datahike's serial
writer. If another run changed the durable row after this run opened, a
different proposed row refuses with
`:seon.cluster.run/program-row-changed-after-open`. Identical declaration is
an assertion-free success, and a declaration previously written by the same
run remains deliberately revisable.

## Acceptance evidence

`receipt-settlement-owns-agent-scoped-agent defs-facts` opens two runs before either
publishes the shared function, admits the first source, refuses the second
run's divergent source, observes the first durable row unchanged, then admits
the second run's identical declaration as a no-op. Runtime installation is
separately gated on the successful settlement transaction report by
`runtime-declarations-install-only-from-a-successful-terminal-db-after`.
