---
type: issue
status: resolved
severity: blocker
tags: [issue, flow]
---

# Prepare managed pod readiness parents before spawn

## Problem

The public branch operator derived a nested descriptor HTTP port file, but the
generic process owner spawned the pod without first creating that file's
parent. The pod exited with `ENOENT` when it tried to publish readiness at
`tmp/seon-operator/branch-ports/<runtime>.port`.

## Evidence

The first live `bin/seon branch open proof-1044` reached the managed pod and
failed opening its descriptor-derived port file. The existing unwind removed
the lifecycle record, target route, native branch, and branch-private paths;
the source default target remained ready.

`seon.dev.process/ensure!` previously called `clear-readiness!` and then spawn
ownership directly. Log directories happened to be prepared by `log-file`, but
the readiness coordinate had no matching preparation owner.

## Owner

`seon.dev.process` now derives readiness paths once. Reconciliation deletes
only the exact stale files, creates their parent directories before invoking
spawn ownership, and never removes a parent directory during cleanup.

## Acceptance

Resolved by `bb6f10f7`. The regression passes both an ordinary descriptor port
and a nested branch port through `ensure!`, observes each parent before spawn,
then proves branch cleanup preserves a sibling file and the shared parent.
The focused branch/process/CLI gate ran 40 tests and 244 assertions with no
failures.
