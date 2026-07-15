---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Preserve timeout absence through the Inspect pod solver

## Problem

The pod composition door derived an absent request timeout from database run
policy, but the common Inspect solver always replaced absence with a
300-second Python constant. Native milestone and planning callers repeated
that fallback, so admitted Inspect tasks never exercised the database-owned
default.

## Dependency ledger

- `seon_inspect.solver.pod_run` owns the JSON and HTTP transport boundary.
- `_resolve_timeout_ms` owns per-sample metadata over per-run argument
  precedence for standard, milestone, SWE-bench, and cluster solvers.
- `pod_milestone_driver` is the selected P0 native task caller; planning uses
  the same `pod_run` continuity boundary.
- The pod's `POST /agents/run` handler owns absent-timeout derivation through
  `seon.agent.run/effective-deadline-ms`.

## Acceptance

- Explicit sample/run timeouts remain exact JSON and transport bounds.
- Absence remains absent in the JSON request and does not gain a Python
  behavioral default.
- Milestone and planning callers use the common optional boundary.
- Focused solver, milestone, and planning tests pass.

## Resolution

The resolving commit makes `pod_run`'s timeout optional, includes it in JSON
and the HTTP read bound only when explicitly selected, and makes
`_resolve_timeout_ms` preserve absence after applying metadata-over-run
precedence. Milestone and planning delete their duplicate fallback. Focused
solver, milestone, and planning coverage passes 78 tests.
