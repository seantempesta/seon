---
type: issue
status: active
tags: [issue, database, pod, flow]
---

# Atomic client authority cut is in progress

## Evidence

Writer commit `fed32bb8` settled canonical same-transaction schema admission.
The client replacement plan in
[[../../prds/database-authority-mesh/research/atomic-client-cold-start-replacement-plan-2026-07-16]]
requires provenance, schema preparation, bootstrap acquisition, program
reconstruction, runtime owners, readiness, quiescence, and stop to cross the
authority boundary before the client changes its open path.

The first consumer cohort removes local Datahike provenance and runtime-schema
preparation. Until the later cohorts land, current connection-shaped client
callers and local database test fixtures intentionally fail instead of selecting
a local compatibility path.

## Invariant during the cut

No lifecycle build, restart, live-runtime proof, or source-freeze checkpoint is
admissible between cohorts. A partial commit is only a source-coherent
dependency boundary for the next cohort; it is not a runnable client state.
Do not restore a local fallback, dual schema installer, replica adapter, or
connection-shaped overload to make an intermediate checkout appear runnable.

## Acceptance

Close and archive this issue only when the atomic client cut:

- opens one direct database session;
- completes provenance, canonical schema admission, bootstrap, reconstruction,
  recovery/resume, restore and web readiness through ordinary async data;
- drains interests and work before closing that session;
- removes client Datahike/Konserve/replica/connection reachability; and
- passes the complete focused, CLJS, writer, operator, cold-start, restart,
  quiescence, and stop proof named by the replacement plan.
