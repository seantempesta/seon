---
type: issue
status: open
severity: cleanup
tags: [issue, runtime, concurrency]
---

# Give (pid, start-instant) liveness one owner

## Problem

"Is the process that wrote this record still alive?" is answered by
`ProcessHandle/of pid` plus a start-instant comparison, and that answer is
about to have two implementations. The existing one is private to the boot
namespace and shaped around an advertisement map; the B2 ancestor needs the
same answer about a `:building-<pid>-<start-millis>-<uuid>` scratch branch, so
it will grow its own copy unless a shared owner exists.

Two copies of a liveness rule drift in exactly the way that matters: one
tolerates the platform's millisecond truncation and the other does not, and a
process is then judged dead by one caller and alive by the other. The
consequence for the ancestor is concrete — a live build's scratch branch
reclaimed under it, or a dead build's scratch never reclaimed.

## Evidence

- `src/seon/cluster.clj:344-359` — `matching-live-process?`, private, takes an
  advertisement map, tolerates millisecond truncation.
- `src/seon/cluster.clj:218-227` — `current-process-identity`, private, is the
  producing half of the same identity.
- `src/seon/cluster/ancestor.clj` (draft contract, 2026-07-27) — `ensure!`
  reclaims a `:building-*` branch whose owner is dead and refuses
  `::build-in-progress` when it is alive; it has no shared predicate to call.

## Owner

The process-identity pair `(pid, start-instant)` and its liveness predicate.
The vocabulary is already settled — `script/seon/dev/process.clj` ↔ JDK
`ProcessHandle` — and the fresh tree's owner should be one small public
surface in `seon.cluster` (or a namespace below it): produce this process's
identity, and answer whether a given identity is live.

## Acceptance

- One public function answers liveness for an arbitrary (pid, start-instant),
  and one public function produces this process's identity.
- `seon.cluster`'s advertisement reader and `seon.cluster.ancestor`'s scratch
  reclaim both call it; neither carries its own `ProcessHandle` code.
- A recycled pid with a different start instant reads as dead in both callers,
  proven once rather than per caller.
