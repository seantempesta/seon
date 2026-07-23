---
type: decision
status: active
date: 2026-07-23
tags: [decision, architecture, config, runtime]
---

# ADR-010: Protective limits are loud circuit breakers

## Context

A hidden or routinely reached limit becomes an accidental scheduler: it
silently throttles normal work, obscures capacity defects, and makes behavior
depend on literals scattered through runtime code. Fuel, deadlines, output
caps, pools, queues, heap ceilings, connection counts, and body bounds all need
one policy.

## Decision

Every protective limit is a resolved database configuration fact with:

- a closed Malli schema and explicit unit;
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
- Bounded queues and pools remain safety containment, while admission and
  scheduling are designed explicitly.
- New limits follow the same schema, provenance, calibration, and error law.

## Related

- [[laws]] — the timeless limit laws.
- [[data-model]] — configuration facts.
- [[agent-runtime]] — guarded eval and claimant bounds.
- [[toolkit]] — flat capability steering errors.
