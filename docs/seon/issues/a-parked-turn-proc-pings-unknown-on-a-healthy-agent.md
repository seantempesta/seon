---
type: issue
status: open
severity: friction
tags: [issue, oversight, agent, flow]
---

# A parked turn proc pings "unknown" on a healthy agent

## Problem

After B6 (`f4b9e007c`) `runtime_status` lists each agent's own procs. On
the freshly reset default (2026-09-18 ~12:45Z, pid 53925, basis 536871074)
root shows `mailbox` 26 passes / ping reply, `schedule` 1 pass / reply, and
`turn` with `:seon.oversight/ping "unknown"` — while root has no open turn,
no fault entity exists, the mailbox and turn buffers report `dropped 0`, and
the eight errored evaluations are the seeded opening history, not new work.

Either the turn proc genuinely does not answer pings while parked between
turns (then "unknown" is the observation lying about a parked proc, and the
oversight ping must reach a parked proc or report `parked` as its own
state), or the proc is dead and nothing else shows it (then B6 item 2 — a
proc death is a fault with a durable failed state — is the missing half).
The observation cannot tell these apart today, which is the class B6 exists
to end.

## Owner

The oversight/agent proc owner (`src/seon/cluster/agent.clj`, the ping
seam landed in B6). Regression: a parked turn proc answers the ping with a
parked state; a killed one becomes a fault and a failed state.
