---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, process, lifecycle]
---

# Let a living operator supersede an exact dead root creator

## Problem

A readable root claim refused a later operator whenever an inherited
ephemeral-owner identity differed from its recorded creator. The refusal did
not distinguish an exact live creator from an exact dead one, so a killed
initialization chain could permanently block the shared repository root.

The lane launcher exports `SEON_OPERATOR_EPHEMERAL_OWNER_PID` to descendants.
The fresh operator applied that declaration to the shared repository root as
well as explicit isolated roots, allowing a durable root to acquire stale
ephemeral ownership metadata.

## Evidence

The shared claim recorded PID 45466 with start instant
`2026-08-05T18:38:25.009Z`; the exact-identity probe reported it dead. The
current lane inherited a distinct live ephemeral-owner PID, so `bin/seon init`
returned `:seon.operator/root-creator-mismatch` before publication.

## Owner

`resources/seon/operator/state.clj` owns exact process-identity liveness and
the locked root-claim transition. `script/seon/fresh_operator.clj` owns the
projection of a lane's explicit ephemeral owner into operator commands.

## Acceptance

- A different exact live creator still causes a loud mismatch refusal.
- A different exact dead creator is superseded under the lifecycle lock.
- The claim records the prior creator, its former ephemeral marking, and the
  supersession instant.
- Re-claiming from a real operator clears stale ephemeral and reap-on-exit
  facts.
- The shared repository root ignores a lane-inherited ephemeral owner, while
  an explicit isolated root retains it.
- Complete publication, forced default refork, and default start reach READY.

## Resolution

Resolved by the commit containing this note. The one claim transition now
uses `process-identity-alive?` to choose refusal or supersession. The default
root projection supplies no ephemeral owner, and only explicit isolated roots
consume the lane-owner environment declaration. Recurring tests cover both
identity rails and both root projections.
