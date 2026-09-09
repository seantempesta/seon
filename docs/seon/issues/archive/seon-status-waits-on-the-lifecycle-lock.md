---
type: defect
status: resolved
severity: friction
tags: [operator, dev-cluster, class/availability]
---

# `bin/seon status` waits on the operator lifecycle lock

Observed 2026-09-08 15:28: `bin/seon status` printed only
`! waiting N ms for the operator lifecycle lock … held by pid 57035 running
init --dev default --changed …` for over 36 s and was killed before it
printed anything. With seven lanes editing, the hook holds that lock almost
continuously (each adoption is a lock holder), so a read-only status query
is unavailable exactly when the orchestrator needs it to see what is
happening. Every monitor and sweep step that starts with `bin/seon status`
inherits the wait.

Status reads recorded process descriptors and advertisements; it changes
nothing. It should read them without the lifecycle lock, or with a bounded
try-lock that reports the holder and continues. Owner:
`script/seon/fresh_operator.clj` (lifecycle lock acquisition).

## Resolution — hook-async, 2026-09-08

Status now reads process descriptors and advertisements without acquiring
the lifecycle lock or reconciling root custody. The default command avoids
recursive disk scans and database requests; `status --verbose` supplies
those additional observations without taking the lifecycle lock.

The committed reproduction script `test/seon/dev/status_lock_probe.py`
measured **0.239742 s**, exit 0, while real `init --dev default` PID 81443
held the lock. Kernel probes confirmed contention before and after status,
and the holder bytes remained identical. The init subsequently converged.
The final path-limited operator gate passed **36 tests / 239 assertions**,
zero failures or errors. The platform checkpoint passed **82 tests / 486 assertions**, zero failures
or errors. Exact output is retained in the
[landing measurements](../../../prds/context-generation/research/hook-async-measurements-2026-09-08.json).
