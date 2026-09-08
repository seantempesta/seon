---
type: defect
status: open
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
