---
type: issue
status: open
severity: blocker
tags: [issue, operator, flow]
---

# Release the apply writer before returning success

## Problem

A successful `bin/seon cluster apply default` returns while its temporary writer
generation is still alive. The immediately following `bin/seon up` correctly
refuses to replace that managed process without clean-or-force evidence.

## Evidence

- Fresh apply succeeded in 37.41 seconds and returned
  `:seon.cluster.apply/ok? true`.
- The immediate `bin/seon up` failed after 26.71 seconds with
  `Refusing to replace a managed process without clean-or-force evidence.`
- `bin/seon status` reported only writer generation
  `c4f7c37f-3e3b-4e68-98f1-f1fdf3d9cfa9` alive; watcher, host, pod, and
  web-render were absent.
- `bin/seon down` used the supervisor-owned transition and recorded the writer
  clean. No direct process kill was used.

## Owner

The cluster-apply lifecycle in the operator and its writer containment owner.
The command that starts the temporary writer must observe and publish its clean
completion before reporting apply success.

## Acceptance

- `bin/seon cluster apply default` returns only after its temporary writer is
  clean or absent.
- An immediate `bin/seon up` does not require an intervening `down`.
- The managed-process replacement refusal remains strict.
