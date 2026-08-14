---
type: issue
status: open
severity: friction
tags: [issue, agent, performance, wave/agent-context]
---

# Keep root maintenance context within the provider budget

## Problem

Routine root maintenance runs in the Drive 1 cluster could not reach the model
because their minimum-distance prompt already exceeded the 32,768-token
provider budget. The maintenance mechanism therefore records work but cannot
perform it.

## Evidence

Preserved run facts in `tmp/drive-1-root`, cluster `default`, carry three
terminal errors:

```text
At render distance 0 the prompt still needs 40664 estimated tokens against a 32768-token budget ... It was not sent.
At render distance 0 the prompt still needs 61326 estimated tokens against a 32768-token budget ... It was not sent.
At render distance 0 the prompt still needs 83950 estimated tokens against a 32768-token budget ... It was not sent.
```

The triggers were root maintenance receipts for process census and dead-root
reaping. The last process-census failure opened and closed at
`2026-08-14T06:05:01Z` without transmission.

## Owner

Root-agent context derivation and the maintenance task surface.

## Acceptance

Each scheduled root maintenance task derives a bounded context that fits the
shipped provider before transmission, or records a typed task-specific
refusal that changes the next maintenance action. A live proof reaches one
maintenance provider attempt without weakening the global context contract.
