---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Restore writer admin transition is unimplemented

## Partial implementation

The no-listener restore-admin selector in `seon.db.server`, the
invocation-local observational transition in `seon.db.registry`, and the
portable closed request/result contract in `seon.db.restore-admin`. The
transition fences the exact old, prepared, undo, and roster state; calls
guarded `force-branch!` at most once; and proves fresh read-back, primary EAVT
equivalence, declared secondary Merkle-root equivalence, parentage, roster,
and release before success.

Focused JVM proof covers primary-only response-loss convergence, stale-roster refusal,
connect and pre-force operation failures, first and second release failures,
fresh read-back connect failure, force evidence after every post-force
failure, explicit unknown effect after an unexpected invocation failure, and
atomic replacement at the intent-specific result path.

The real file-backed Proximum falsifier remains closed: selected Datahike
`force-branch!` flushes a source-branch secondary while labeling the resulting
key map with destination branch `:db`. Target and forced main reopen with the
same primary EAVT and the same one-vector KNN result but different secondary
Merkle roots. Seon rejects that state as restore divergence. This issue cannot
close until Datahike branches every versioned secondary to the forced
destination before publishing the new primary head and the full file-backed
proof converges.

## Problem

The maintained Datahike fork has guarded, read-back-verified
`force-branch!`, but Seon has no exclusive no-listener invocation that can use
it for restore or undo. `seon.db.server/-main` currently starts the ordinary
writer, whose startup opens publisher and request sockets; registry connection
initialization installs a transaction listener and may run the main database
initializer. Calling that path during a root move would violate exclusive
administration and could write or publish from a stale connection.

The protocol and registry also lack a closed force result, full
branch-head/roster read-back, and the desired-state retry that distinguishes
old main, already-forced main, and divergence after result loss.

## Evidence

- `seon.db.server/-main` recognizes embedding preflight or starts
  `writer/start!`; there is no admin selector.
- `writer/start!` calls `uds/start-publisher!` and
  `uds/start-request-server!`.
- `writer/initialize-connection!` installs `d/listen`, and a main open invokes
  the composed database initializer.
- `registry/ensure-database!` publishes every successful connection in the
  process registry; it has no invocation-local observational open.
- `seon.db.protocol` version 2 has typed native create/release/delete but no
  restore-admin result.
- Selected Datahike `417649...` checks only expected/current commit and its
  computed commit-id read-back. It explicitly requires external exclusive
  access and returns `nil`; Seon must prove the complete coordinate, parent,
  value, roster, and release contract around it.

Full grounding and the failure matrix are in
[[../../prds/database-lifecycle-recovery/research/restore-writer-admin-transition-audit-2026-07-15]].

## Owner

`seon.db.protocol`, `seon.db.registry`, `seon.db.writer`, and
`seon.db.server`, consuming the exact immutable intent owned by restore Slice
2. The Babashka operator orders the process boundary but never opens Datahike
or Konserve itself.

## Acceptance

- One invocation of the existing writer artifact consumes the validated
  Slice-2 intent without starting UDS, publisher, REPL, listener, embedding
  initialization, or another registry.
- Exact main, prepared-target, undo, and full-roster fences fail before force.
- The wrapper calls guarded `force-branch!` at most once, then proves the full
  forced coordinate, target parent, primary/secondary value, unchanged roster,
  and awaited connection release.
- Retry at old main applies; retry at the exact desired forced state returns
  `already-applied` with zero force calls; every other head fails as divergence.
- One portable closed result union rejects unknown or partial data and cannot
  report success without proved release.
- Slice-2 field names and result transport are referenced from their one owner,
  not guessed or duplicated in the writer.
