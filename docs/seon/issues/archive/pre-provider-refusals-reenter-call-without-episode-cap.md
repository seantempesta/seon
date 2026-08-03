---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, database, flow, testing]
---

# Close pre-provider refusals through the episode cap

## Problem

A prompt or capture refusal records a durable error but leaves the held run
open without a plan. `next-agent-work` consequently derives the same `:call`
again, producing an unbounded transaction loop that never reaches the existing
episode cap.

## Evidence

The retained failing gate exceeded core.async's 1,024 pending-put limit while
recording the same `:seon.cluster.loop/prompt-failed` value. A virtual-thread
dump showed the turn still executing `call-turn` and `refused!`; it had not
missed completion delivery. Lint refusals do not spin because their receipts
are terminal, their runs close, and `seon.cluster.work` derives corrective
runs only below `:seon.config.run/max-episode-runs`.

## Owner

The `seon.cluster.loop/call-turn` pre-provider terminal seam and
`seon.cluster.work` refusal-continuation derivation.

## Acceptance

- A prompt/capture refusal records one error and closes its held run in one
  transaction.
- Presence of a run-attributed error with no model attempt derives the same
  corrective continuation already used by lint refusals.
- Repeated deterministic prompt refusal opens no more runs than the existing
  episode cap and makes no provider call.
- The agent fixture supplies the retained-context channel through the same
  render proc shape as live boot.

## Resolution

The containing path-limited repair terminalizes pre-provider refusals with the
existing error and run-close transaction data. Work derivation recognizes the
fact shape—run-attributed error present and model attempt absent—without a kind
list, new counter, or clock, and reuses the original trigger only below the
existing episode cap. The integrated regression deliberately removes the
otherwise-live fixture channel and proves three configured episode runs,
three flat refusals, zero attempts, zero provider calls, and no fourth turn.
