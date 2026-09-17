---
type: research
status: landed
created: 2026-09-17
tags: [publication, projection, seon.db]
---

# Publication reports carry the commit's projection

## Evidence and seam

The post-reset platform log recorded the same armed return-contract refusal
for eight `seon.cluster.source-test` tests: `publish!` embedded a flat
`:seon.schema/missing-projection` read error under
`:seon.program/unresolved-report`, so `:seon.source/published` then reported
the absent required `:seon.db/basis-t`. The path was
`seon.cluster.source/database` -> Datahike `commit-as-db` ->
`seon.fn/unresolved-callers` -> three `seon.db/q` reads.

The dependency seam is Datahike's immutable commit database value. Seon's read
seam takes declarations first from that value's metadata
(`src/seon/db.clj:1081-1095` after this change), before considering a handed
projection. A raw `commit-as-db` value carried neither. This violated
AGENTS.md §2.1: the read worked or failed according to dynamic caller state,
not according to the database value handed to it.

`seon.db/carry-derived-projection` now derives one projection from the exact
commit's installed `:seon.schema` and function-contract rows through
`seon.schema/projection-from-database`, then attaches that immutable snapshot
to the value (`src/seon/db.clj:219`). `seon.cluster.source/database` applies
that constructor immediately after `commit-as-db`
(`src/seon/cluster/source.clj:164`). `seon.cluster.registry/reset-cluster!`'s
raw `commit-as-db` remains unchanged: it hands the value directly to
Datahike's `force-branch!` and performs no Seon read.

Publication and incremental upsert now share `unresolved-report!`
(`src/seon/cluster/source.clj:176`). A flat read error becomes the existing
`::publish-readback-failed` refusal naming `seon.fn/unresolved-callers` and
retaining its result. An unreadable report can no longer inhabit a successful
`:seon.source/published` value.

## Why the operator path passed

The reset operator was not proof that the raw commit value was self-contained.
`seon.cluster/refresh-source!` wraps the complete source refresh in
`schema/call-with-projection` using the packaged declaration projection
(`src/seon/cluster.clj:2479-2492`). Before this fix, the raw commit database's
queries therefore fell through to that dynamically handed projection. The
operator log returned basis transaction `536870921` and analyzed count `6386`.
The isolated source tests did not have that binding and exposed the missing
world. This was a caller-dependent success, not an armed-versus-unarmed
contract difference; the platform failure itself was produced by armed
contracts.

## Regression and fast evidence

`test/seon/cluster/source_database_test.clj` uses the canonical database
fixture with its declared commit-bearing fresh-store mode. It calls
`seon.cluster.source/database` and both `seon.db/q` and
`seon.fn/unresolved-callers` on a fresh Java thread, which inherits no Clojure
dynamic projection binding. The test asserts a real integer query result, a
carried projection, and an integer `:seon.db/basis-t`.

Fast results in the isolated HEAD-plus-owned-files worktree:

- `seon.cluster.source-database-test`: 1 test, 3 assertions, green.
- `seon.fn-test`: 60 tests, 420 assertions, green.
- `seon.cluster.source-test`: 17 tests, 85 assertions; 3 failures and 10
  errors on the unmodified held fixture file.

All eight originally named report failures now reach the foreign fixture
boundary instead: their first exception line is
`clojure.lang.ExceptionInfo: :malli.core/invalid-schema` in `malli.core`, for
`stale-incremental-upsert-preserves-the-newer-publication`,
`incremental-publication-does-not-change-an-existing-cluster`,
`incremental-upsert-seals-one-activation-on-the-expected-commit`,
`failed-and-stale-builds-preserve-the-published-head`,
`existing-clusters-remain-on-their-chosen-source-commit`,
`publication-advances-one-branch-and-retires-scratch`,
`incremental-upsert-derives-scalar-safety-from-the-installed-schema`, and
`an-activation-closure-with-empty-member-collections-seals`. None reaches the
old missing-`:seon.db/basis-t` return-contract refusal. The held
`test/seon/cluster/source_test.clj` belongs to
`post-reset-stale-fixtures-2` and was not edited.

The requested path-overlay command was attempted first. The shared tree
refused because the held `source_test.clj` is a changed caller. The clean
worktree then refused because no published program graph exists and named the
orchestrator-only `bin/test --prepare-head-base` dependency. The tallies above
come from plain `bin/test-fast` in that isolated worktree, whose only source
and test changes were this lane's three owned files. Cold and platform gates
remain the orchestrator's proof.

The supported MCP JVM evaluation tools were absent from this session, and
`bin/seon status` reported `default` as a stale advertisement with zero live
clusters. No default process was started, stopped, or reset.
