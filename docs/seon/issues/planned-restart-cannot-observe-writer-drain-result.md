---
type: issue
status: open
severity: blocker
tags: [issue, database, flow, architecture]
---

# Make writer drain proof consumable by the operator

## Problem

The JVM writer now returns release failures through its in-process
`writer/stop!` and `server/stop!` results, but the Babashka operator cannot
observe either result. A managed writer stopped by `SIGTERM` may disappear and
be restarted even when its shutdown hook printed an incomplete release. The
operator therefore cannot distinguish a proved clean writer drain from process
absence.

This blocks an exact clean-restart claim. It does not reopen the already-fixed
UDS admission, handler-join, or Datahike release contracts.

## Evidence

`seon.db.transport.uds/close-request-server!` closes admission and joins every
admitted connection worker. `seon.db.registry/release-database!` retains a
failed database identity, and `seon.db.writer/stop!` plus
`seon.db.server/stop!` return `stopped? false` with release failures.

`seon.db.server/-main` consumes that result only inside a JVM shutdown hook and
prints it to stderr. `seon.dev.process/stop!` sends signals, waits for process-
group absence, clears the process record, and returns `nil`; it neither receives
the stop result nor a complete final `{database-id, branch, commit-id, t}`
coordinate. Log text and a disappearing PID are not a typed lifecycle result.

The refreshed source audit and ordered clean-restart slice are recorded in
[[clean-planned-restart-quiescence-refresh-2026-07-15]].

## Owner

The existing managed-process lifecycle must carry one generation-matched
terminal result from `seon.db.writer/stop!` through `seon.db.server` to
`seon.dev.process`. Reuse the process terminal-result mechanism required for
stable subtree containment; do not add a second writer registry, parse logs, or
infer release success from process exit.

## Acceptance

- Closing writer request admission joins every admitted handler before the
  final complete coordinate is frozen and registered connections are released.
- The writer/server terminal result contains the exact attachment coordinates,
  every release result, and `stopped? false` for any unproved release.
- `bin/seon restart` reports a clean writer drain only after consuming the
  generation-matched result; a missing, malformed, stale, or failed result is
  not reclassified as clean because the process disappeared.
- A force/crash fallback remains available, records no clean claim, and lets
  the existing cold-boot recovery decide durable agent ownership.
- Focused writer/server/process tests inject release failure and terminal-result
  loss, and a default restart proves equal or descendant complete coordinates
  across the pod response, writer result, and reopened writer/replica.
