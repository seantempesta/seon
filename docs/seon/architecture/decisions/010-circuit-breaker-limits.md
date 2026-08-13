---
type: decision
status: active
date: 2026-07-23
tags: [decision, architecture, config, runtime]
---

# ADR-010: Protective limits are loud circuit breakers

## Context

A hidden or routinely reached bound becomes an accidental scheduling
mechanism: it
silently throttles normal work, obscures capacity defects, and makes behavior
depend on literals scattered through runtime code. SCI has one `time-limit`;
bounded projections, queues, heap ceilings, connection counts, and body sizes
are explicit data or capacity boundaries.

## Decision

Every protective limit is a resolved database configuration fact with:

- a declared, rigorously validated Malli schema with open map shapes and an
  explicit unit;
- a docstring naming the protected resource and firing meaning;
- calibration provenance;
- a default at least one hundred times legitimate measured P99.9 work; and
- one loud failure path that records a fault and returns a flat steering error
  naming the governing config key.

Limits abort runaway or unsafe work. They are never normal throughput governors,
fairness quanta, or silent drop policies. Runtime code contains no numeric
fallback for a required protective fact. Missing configuration fails readiness
or the operation as a structured configuration error.

## Consequences

- Normal work does not encounter protective limits.
- Capacity tuning changes facts, not call sites.
- A fired limit is observable and attributable.
- Bounded Flow workload channels and `executor-for :io` / `:compute` remain
  safety containment, while procs, `step-fn`s, `conns`, and the `graph-def`
  express scheduling explicitly.
- New limits follow the same schema, provenance, calibration, and error law.

## Related

- `AGENTS.md` §2.3 — the binding event-driven and bounded-execution law.
- [[data-model]] — configuration facts.
- [[agent-runtime]] — SCI `time-limit` and cluster JVM bounds.
- [[architecture]] — the capability boundary and flat steering errors.
