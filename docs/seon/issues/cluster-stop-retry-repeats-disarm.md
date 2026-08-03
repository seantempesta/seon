---
type: issue
status: active
severity: high
tags: [issue, runtime, lifecycle, flow]
---

# Cluster stop retry repeats already-completed disarm

## Problem

`seon.cluster/stop!` correctly restores an instance after a later resource
release fails, but the restored value does not record which teardown layers
already completed. A retry calls `disarm-agents!` again. The first pass closed
the armer input after its quiescence event, so the second pass refuses at
`src/seon/cluster.clj:1660` with “The cluster armer input closed before
quiescence.” The retry never reaches the resource release that originally
failed.

This is the remaining edge of the previously resolved
`docs/seon/issues/archive/cluster-stop-release-failure-becomes-unaddressable.md`:
the instance remains addressable, but teardown is not resumable from its actual
standing layers.

## Evidence

The explicit `seon.cluster.boot-test/a-failed-stop-remains-addressable-and-retryable`
gate on 2026-08-03 reproduced the boundary after the search-index flow joined
the ordinary cluster graph. The injected root-store release failure propagated
and left the exact instance addressable as intended. The next `stop!` then
failed at armer quiescence because that channel was already closed. This is not
a Lucene or search failure; every preceding live search/boot pass completed
without a core fault after the search proc's Flow return-shape fix.

## Owner

`seon.cluster` teardown state. The instance restored after failure must describe
only resources that still stand, or every release operation must be honestly
idempotent against an already-completed lower layer.

## Acceptance

- The existing injected root-store failure test passes through its second
  `stop!` without repeating armer quiescence.
- The retry reaches and completes the originally failed release.
- The advertisement and registry entry disappear only after all remaining
  layers release.
- No teardown completion is inferred from a clock or swallowed exception.
