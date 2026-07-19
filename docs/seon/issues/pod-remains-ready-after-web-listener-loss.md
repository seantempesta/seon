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
input failure in `seon.web.datastar/observe-connection!` occurred after startup;
two immediate requests to port 7890 were refused while `bin/seon status --edn`
still reported the pod ready with the same PID.

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
connection refused. The Datastar contract failure is owned by the reactive
migration; this issue owns the stale readiness classification.
