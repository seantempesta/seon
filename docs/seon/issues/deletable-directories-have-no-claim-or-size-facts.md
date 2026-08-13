---
type: issue
status: open
severity: blocker
tags: [issue, operator, database, class/n4, wave/directory-claims]
---

# Observe and claim every deletable directory

## Problem

No database fact declares who owns a scratch/operator/test directory, whether
it is live or ephemeral, or how much disk it occupies. Status cannot show
per-root cost or low free space, and cleanup substitutes names, mtimes, and
process-text heuristics for ownership.

## Evidence

The 2026-08-04 preserved census contains 64 isolated operator roots occupying
138.03 GiB and 463 other directories occupying 103.76 GiB. One root is 88.09
GiB and another is 29.92 GiB. The machine reached about 2.8 GB free without an
operator warning. Separately, the incident recorded in
[[shared-bootstrap-drive-root-disappears-during-live-experiments]] deleted a
live experiment workspace because no declared directory claim existed to
answer liveness/ownership.

## Owner

The operator-root/process lifecycle and its database-backed observation
surface; fixture and experiment launchers consume that one mechanism.

## Acceptance

- Before directory creation, a stable parent database records its canonical
  path, exact owner ref, process identity when applicable, parent claim, and
  reap-on-owner-exit disposition. The claim authority is outside the claimed
  directory.
- Every deletion is authorized by the exact active/released claim. A prefix,
  mtime, or `ps` substring is never ownership.
- Size observation events record apparent bytes, allocated bytes, file count,
  filesystem usable/total bytes, and transaction provenance. Current size is
  derived from the latest event.
- `bin/seon status` renders per-root claim, liveness, disposition, size, sample
  basis, and free space.
- Startup commits and prints a loud low-free-space observation under the R41
  mode without imposing a quota or refusing work.
- Owner exit reaps and awaits exact children before removing process records,
  releasing claims, or deleting a root.

## Implemented boundary

The external claim-first authority, exact process liveness, per-root footprint
observation, low-space boot warning, configured log retention, truthful reset
cleanup, and fixture child reaping are implemented. The remaining acceptance
work is the scheduler lane's conservative ephemeral-root reaper and database
maintenance-result facts; it consumes the existing `seon.operator` functions
rather than adding another cleanup implementation.
