---
type: issue
status: open
severity: blocker
tags: [issue, database, pod, flow]
---

# Preserve healthy readers during writer recovery

## Problem

After the JVM writer child exits unexpectedly, `bin/seon up` now safely drains
its retained containment generation and starts a replacement. The same command
still enters the general build path, which quiesces and replaces the healthy
Bun pod and watcher before it knows whether any reader artifact changed.

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
- Commits `8eb01131`, `3dbb8385`, and `850e476f` repaired the refusal without
  weakening `ensure!`: reconciliation checks the existing containment-aware
  process status before and after the build interval and sends every non-live
  retained generation through `clean-or-force!`.
- A second immediate writer `SIGKILL` followed by `bin/seon up` returned ready
  with `recover: forced reason=unexpected-exit`. A real agent then sent its
  user message and completed in one turn at basis transaction 536872775.
- That successful recovery still reported `rebuild-readers`; the prior pod and
  watcher identities were replaced even though the writer artifact was reused.

## Owner

The build/reconciliation decision in `seon.dev.cli/reconcile-development!` and
the canonical manifest checks in `seon.dev.artifact`. The containment recovery
transition is now correct and remains the required writer safety authority.

## Acceptance

- `bin/seon up` notices a recorded unexpected writer exit, reaps its
  containment owner through the normal protocol, and starts one replacement
  generation without entering a reader rebuild when current source and outputs
  still match the published manifest.
- The healthy Bun pod and watcher keep their process identities and the pod
  reconnects to the replacement writer without losing its database session.
- Reconciliation never replaces a live or uncertain foreign process and
  remains idempotent when the writer is already healthy.
