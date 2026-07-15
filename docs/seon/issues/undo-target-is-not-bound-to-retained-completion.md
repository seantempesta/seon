---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Bind undo to a retained completion head

## Problem

The immutable restore contract accepts `:seon.dev.restore.operation/undo`, but
no current owner derives its selected target from a prior completed restore's
recorded undo branch. A caller can provide any otherwise valid retained target
descriptor and label the operation `undo`. The operation keyword changes the
plan digest but does not prove inverse lineage.

## Evidence

`seon.dev.restore/derive-intent` validates that the selected descriptor is an
exact non-main branch in the same physical database, with the correct writer,
artifact flavor, runtime capability, and blob ordering. It does not consume a
`:seon.db.restore/*` completion fact or compare the selected branch to that
fact's `undo-branch`, `from-commit-id`, and `from-t`.

`undo-is-the-same-intent-and-command-contract` in
`test/seon/dev/restore_test.clj` confirms that changing restore to undo changes
only `operation` and `plan-digest`. No public restore/undo planner currently
owns the missing completion-to-target resolution. Exact dependency and state
transition grounding is in
[[../../prds/database-lifecycle-recovery/research/retained-head-restore-undo-contract-audit-2026-07-15]].

## Owner

The existing operator restore planner that resolves branch status and produces
the closed `seon.dev.restore/derive-intent` request. `seon.dev.restore` remains
the one intent/command contract; the writer admin and cold reconstruction paths
must not gain a second undo meaning.

## Acceptance

- Undo selects a completed restore id or retained undo branch and resolves one
  unique completion fact.
- The expected target coordinate is derived from that fact's database id,
  undo branch, source commit, and source `t`, then compared with the current
  durable branch head and full roster before intent publication.
- Missing, advanced, cross-database, ambiguous, or unrelated retained branches
  fail without publishing intent, materializing blobs, stopping processes, or
  invoking force.
- The new intent freezes the actual current main head as its source and creates
  a new undo branch from that head, preserving a redo point including ordinary
  writes made after the prior completion.
- After selection, undo uses the same branch preparation, blob proof, writer
  admin, cold reconstruction, completion, admission, retry, and crash-cut
  mechanisms as restore. There is no reverse-datom compiler, undo protocol, or
  second state machine.
