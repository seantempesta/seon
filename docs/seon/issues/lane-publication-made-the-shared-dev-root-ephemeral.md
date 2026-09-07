---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, wave/operator-process-identity]
---

# A lane's hook publication made the shared development root ephemeral

## Problem

`bin/codex-agent` exports `SEON_OPERATOR_EPHEMERAL_OWNER_PID=$$` so roots a
lane creates die with the lane. The edit hook runs `bin/seon --root ROOT init
--dev CLUSTER` for the shared development root and inherited that variable.
`claim-root-under-lock!` let the lane's identity supersede the root's dead
short-lived creator and relabel the root ephemeral with
`reap-on-owner-exit? true`. Two consequences: every non-lane caller was
refused with `:seon.operator/root-creator-mismatch` while the lane lived, and
the reaper would have stopped and removed the development root on the lane's
exit.

## Evidence

2026-09-07 03:49–03:50Z, `data/operator/claims/roots/4d0e6035-….edn` for
`tmp/juniper-context-live`: creator pid 73106 (lane `debug-units-0906`) then
73590 (lane `agent-units-0906`), `:ephemeral? true`, `:reap-on-owner-exit?
true`, after a supersession chain of short-lived `bin/seon` pids. `bin/seon
--root tmp/juniper-context-live status` and `init --dev` refused with the
mismatch while the JVM (pid 69815) served the page normally.

## Resolution

`resources/seon/operator/state.clj` `claim-root-under-lock!`: a declared
ephemeral owner applies only to a new lifecycle, an unclaimed root, or a root
that is already ephemeral. An existing non-ephemeral root keeps its durable
lifecycle and the caller's ordinary process identity becomes the creator.
Regression: `seon.operator-test/superseding-a-durable-root-never-makes-it-ephemeral`.
The live claim was repaired by hand to non-ephemeral with a dead creator so
ordinary supersession resumed.

## Remaining

The creator of a durable root is still whichever short-lived `bin/seon`
process last touched it, so two concurrent operator commands on one root
refuse each other by design rather than by the control lock alone. The
honest creator of a live root is the JVM holding its store; making read-only
and publication commands independent of that identity is
[[operator-status-refuses-foreign-live-root]].
