---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, flow]
---

# Let the operator reconcile an unexpected writer exit

## Problem

After the JVM writer child exits unexpectedly, its containment owner remains
long enough for `bin/seon up` to refuse replacement as
`managed-process-present`. Recovery currently requires a full
`bin/seon restart`, which also replaces the healthy Bun pod and watcher.

## Evidence

- Killing the writer child on 2026-07-18 left the watcher and pod alive and
  made `bin/seon status` report the writer `containment-uncertain` and
  `not-ready`.
- `bin/seon up` refused with required transition `clean-or-force` even after
  the writer was recorded dead.
- `bin/seon restart` safely recognized `unexpected-exit`, forced cleanup of
  the stale containment generation, reopened the same database, and returned
  all three components ready, but unnecessarily restarted the healthy pod and
  watcher.

## Owner

The existing process reconciliation transition in `script/seon/dev/process.clj`
and the one `bin/seon` operator. Recovery must preserve containment identity and
cleanup evidence; it must not bypass the managed-process safety refusal.

## Acceptance

- `bin/seon up` or the existing supervisor notices a recorded unexpected
  writer exit, reaps its containment owner through the normal protocol, and
  starts one replacement generation.
- The healthy Bun pod and watcher keep their process identities and the pod
  reconnects to the replacement writer without losing its database session.
- Reconciliation never replaces a live or uncertain foreign process and
  remains idempotent when the writer is already healthy.
