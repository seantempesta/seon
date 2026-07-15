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

In the plan-preload pilot's organic escalation, root inspected a worker plan
and successfully called `reopen!` on the worker's verified-done step. The
research identifies this as an authority mistake: planner/worker separation
was enforced only in the diffusion-buffer path, not at the function-called
plan transition.

## Owner

The one private `my.plan.internal` transition authority, with actor identity
owned by the runtime and authority derived from existing ownership,
escalation, planner, message, and transaction facts.

## Acceptance

- A planner cannot reopen another agent's completed step merely by possessing
  its id; the attempt returns a structured failure and preserves the datoms.
- Same-agent correction and an explicit, checkable human/worker handoff remain
  possible through one capability rule.
- Behavioral tests cover worker completion followed by planner reopen,
  authorized correction, and concurrent stale attempts without introducing a
  stored role enum or a second lifecycle path.

A planner may reconcile non-lifecycle fields only inside its exact active
delegated subtree and escalation episode. The grounded transition matrix is in
[[docs/prds/agent-runtime-correctness/research/plan-transition-authority-audit-2026-07-15]].
