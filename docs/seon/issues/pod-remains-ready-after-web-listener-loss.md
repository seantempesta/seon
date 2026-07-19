---
type: issue
status: open
severity: blocker
tags: [issue, pod, health, web]
---

# Pod remains ready after losing its web listener

## Problem

The operator can report the pod as alive and ready while its configured web
port refuses connections. During the reactive Datastar migration, a Malli
input failure in
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
opened and closed one feed, and stopped accepting connections. An initial
process check incorrectly inspected containment owner process group `75309`
rather than descriptor anchor group `75310`; it therefore did not establish
that workload PID `75311` had exited.

The source-frozen checkpoint at `b6961bac` did not reproduce the loss. Bun
workload PID `59340` remained alive and listening after feed open/close,
`/_seon/ready` returned HTTP 200, and both Datastar and reactive registrations
returned to zero. This issue therefore owns only the observed stale readiness
classification when the listener was unavailable; no Bun workload-exit claim
is retained without correct anchor-group or workload-identity evidence.
