---
type: issue
status: open
severity: blocker
tags: [issue, pod, health, web]
---

# Pod remains ready after losing its web listener

## Problem

The operator can report the pod as alive and ready after the Bun workload has
exited and its configured web port refuses connections. During the reactive
Datastar migration, a Malli input failure in
`seon.web.datastar/observe-connection!` first exposed the problem. A later
retry fixed that input, opened and closed one root feed, and then reproduced
the same stale status without logging an application exception.

## Acceptance

- Pod readiness continuously reflects the maintained HTTP listener, not only
  process liveness or an earlier startup publication.
- Losing the listener withdraws readiness or terminates the pod so the
  supervisor can recover it.
- A live regression closes or faults the listener after initial readiness and
  observes a non-ready pod before another request is admitted.

## Evidence

After a clean operator restart, the pod published readiness. Root-feed startup
then logged `:malli.core/invalid-input` from
`seon.web.datastar/observe-connection!`. The process remained classified
alive/ready, but `curl http://127.0.0.1:7890/agents/run` failed twice with
connection refused.

After that request-shape defect was fixed, the pod again published readiness,
opened and closed one feed, and stopped accepting connections. Process-group
inspection showed only containment owner PID `75309`; descriptor workload PID
`75311` was absent. `result.json.adopted` still contained only
`{"status":"adopted"}`, and `bin/seon status` classified the containment owner
as the live pod. The Datastar migration owns the workload exit if it proves to
be application-caused; this issue owns the stale operator classification and
missing exit evidence either way.
