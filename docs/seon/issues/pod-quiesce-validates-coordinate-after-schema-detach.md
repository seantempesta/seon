---
type: issue
status: open
severity: blocker
tags: [issue, flow, database, pod]
---

# Validate the final pod coordinate before schema projection detach

## Problem

The first two fully contained default restarts classified the pod shutdown as
forced even though its containment generation and requested drain trigger were
exact. `drain-runtime-owners!` detached the active schema projection before it
resolved the final database coordinate. The later coordinate validation then
looked up its symbolic schema in the intentionally empty projection and failed
with `:malli.core/invalid-schema`.

Writer shutdown remained clean with one released database, and watcher
shutdown remained clean. The aggregate correctly refused to claim a clean
restart because the pod returned `:seon.client/quiesced? false`.

## Owner

`seon.client/drain-runtime-owners!` owns the inverse order. It must resolve the
final immutable coordinate while the active projection is valid, then detach
the projection, release the connection, and return generation-bound evidence.

## Acceptance

- Focused lifecycle proof asserts coordinate resolution precedes projection
  detach.
- A fully contained default restart reports the pod, writer, watcher, and
  aggregate clean.
- The pod result carries its exact process generation and final complete
  database coordinate; writer evidence reports every attachment released.
- A following restart remains clean and preserves the same database
  attachment without replay or replacement.
