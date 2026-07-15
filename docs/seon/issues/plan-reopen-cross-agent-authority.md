---
type: issue
status: open
severity: friction
tags: [issue, agent, database, architecture]
---

# Cross-agent planners can reopen worker-completed steps

## Problem

A planner that knows a worker's step id can reopen that completed step even
when the worker already verified it. The database records step ownership, but
the lifecycle transition does not define or enforce who may reverse another
agent's completion.

## Evidence

Every plan step carries `:my.plan/agent`. `my.plan/reopen!` accepts only
`:my.plan/id`, checks status, then writes `:open` and retracts
`:my.plan/completed-at`; it does not compare the current agent context with the
step owner or require an explicit handoff.

The same issue applies to `active!`, `done!`, `needs!`, `move!`, and `drop!`:
instrumented `:seon.agent/id` injection fills an absent field but permits an
explicit caller value, so it cannot serve as authorization proof.

Top-level source review on 2026-07-15 found that
`seon.db/current-agent-id` is not by itself the missing proof either. It reads
an AsyncLocalStorage value, while the public `seon.db/with-agent` function can
nestedly replace that value for an eval. Reading the ambient value inside
`my.plan` would remove the obvious request-map override but would still let
agent code select a foreign actor deliberately. Hard authority therefore
depends on the process/capability boundary: the parent runtime must stamp the
actor from its owned task capability when it performs the database mutation,
and the eval child must have neither an actor-selection operation nor an
independent writer.

In the plan-preload pilot's organic escalation, root inspected a worker plan
and successfully called `reopen!` on the worker's verified-done step. The
research identifies this as an authority mistake: planner/worker separation
was enforced only in the diffusion-buffer path, not at the function-called
plan transition.

## Owner

The one private `my.plan.internal` transition authority, with actor identity
stamped by the parent-owned execution capability and authority derived from
existing ownership, escalation, planner, message, and transaction facts. The
process-containment slice owns that unforgeable actor boundary; plan CAS and
scope rules must not claim it early from the mutable public ALS scope.

## Acceptance

- A planner cannot reopen another agent's completed step merely by possessing
  its id; the attempt returns a structured failure and preserves the datoms.
- Same-agent correction and an explicit, checkable human/worker handoff remain
  possible through one capability rule.
- Behavioral tests cover worker completion followed by planner reopen,
  authorized correction, and concurrent stale attempts without introducing a
  stored role enum or a second lifecycle path.
- A deliberate eval-side `seon.db/with-agent` scope cannot change the actor
  stamped on the parent-side plan mutation or its transaction provenance.

A planner may reconcile non-lifecycle fields only inside its exact active
delegated subtree and escalation episode. The grounded transition matrix is in
[[docs/prds/agent-runtime-correctness/research/plan-transition-authority-audit-2026-07-15]].
The unforgeable actor dependency and its first bounded proof are specified in
[[docs/prds/agent-runtime-correctness/research/process-death-containment-audit-2026-07-15]];
the plan slice must not claim actor-security graduation before that parent-owned
task capability exists.
