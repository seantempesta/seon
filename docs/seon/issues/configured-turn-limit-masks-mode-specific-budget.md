---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# Idle transcript misreports the mode-specific work budget

## Problem

The transcript readline reports the batch default of 20 while an idle cluster
is in stream mode, even though `open-run!` would seed a stream run with its
separate 60-form fallback. The surface therefore misstates the next run's
resource unit and budget. More broadly, both small magic counts are poor normal
stoppers when task completion, stagnation, errors, deadlines, and explicit
resource policy provide more meaningful outcomes.

## Evidence

The isolated ACME REPL rendered `loop 0/20` while the cluster was in
`:stream`. Source inspection shows `default-form-limit` is 60 and a later REPL
query proved the agents carried neither `:seon.agent/default-turn-limit` nor
`:seon.agent.run/default-turn-limit`; the apparent conflict was the idle
readline's unconditional `seon.agent.ctx/default-turn-limit` fallback, not a
stored masking datom. After the cluster switched to `:batch`, newly opened runs
correctly carried 20. This second probe falsified the initial stored-default
hypothesis and narrows the defect to presentation plus undersized defaults.

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
