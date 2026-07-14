---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# Idle transcript misreports the mode-specific work budget

## Problem

The original transcript readline reported the batch default of 20 while an
idle cluster was in stream mode, even though `open-run!` seeded a separate
60-form fallback. The surface therefore misstated the next run's resource unit
and budget. The immediate mismatch and literal defaults are fixed; measured
local/paid profiles and outcome-oriented early stops remain open work.

## Evidence

The isolated ACME REPL rendered `loop 0/20` while the cluster was in
`:stream`. Source inspection shows `default-form-limit` is 60 and a later REPL
query proved the agents carried neither `:seon.agent/default-turn-limit` nor
`:seon.agent.run/default-turn-limit`; the apparent conflict was the idle
readline's unconditional `seon.agent.ctx/default-turn-limit` fallback, not a
stored masking datom. After the cluster switched to `:batch`, newly opened runs
correctly carried 20. This second probe falsified the initial stored-default
hypothesis and narrows the defect to presentation plus undersized defaults.

The first repair adds one `:seon.config/run` section whose resolved 100 batch
turns, 300 stream forms, and 1,800,000 ms deadline are scalar facts on the
config singleton. `ctx/run-policy`, `open-run!`, renewal/resume, and the idle
readline consume the frozen database value. Focused config/run/transcript tests
pass 41 tests and 174 assertions; the isolated live database returned those
three exact values after restart.

## Owner

The one run resource policy across `seon.config`, agent seeding,
`seon.agent.run/open-run!`, the transcript readline, and Inspect model/mode
comparisons.

## Acceptance

- One database-owned resource policy names the actual bounded units for batch
  and stream; idle and running transcript surfaces report the same effective
  value that `open-run!` will use.
- Defaults are generous safety ceilings. Successful completion, explicit wait,
  no-progress/error guards, and the deadline end normal runs before the ceiling.
- Local and paid model resource profiles are explicit data, not inferred from
  a provider name or loopback URL.
- A bound closure remains truthful, persisted, visible to parent/root, and
  continuable; it never masquerades as completion.
- Inspect compares batch and stream at equal outcome/resource budgets and
  records calls, forms, tokens, elapsed time, fabrication, and task success.
