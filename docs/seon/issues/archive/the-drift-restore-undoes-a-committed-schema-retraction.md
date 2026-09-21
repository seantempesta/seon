---
type: issue
status: resolved
severity: friction
created: 2026-09-17
resolved: 2026-09-17
tags: [testing, runner, schema-registry, drift, class]
---

# The drift restore undoes a committed schema retraction

## Problem

The runner's drift detector now snapshots the schema-key set around an
in-process run and restores the entering set afterwards (`f3b61b975`, class
1 of the write-storm fix). On 2026-09-17 (pid 88182) a live probe turn
DELIBERATELY retracted four `:probe*` declarations from `default`; the
next in-process run's restore put them back into the live registry because
the entering snapshot still held them. The cluster's projection then
disagreed with its own committed facts until an explicit advance from
`projection-from-database` repaired it (observed and repaired by the
live-projection lane).

A restore that reasserts state the authority has since retracted is a
mirror acting against the writer — the same class the projection fix just
closed from the other side.

## Fix shape

The restore derives from FACTS, not from the entering snapshot: after a run,
the registry/projection is advanced to `projection-from-database` at the
cluster's current basis (which already reflects any committed retraction),
and drift is reported as the difference between the run's exit state and
that derived state — by key, both directions. A regression: a declaration
retracted by a committed transaction during the run stays retracted after
the restore, and the report names it as a committed change, not as drift.

Related: `a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection`
(resolved), `a-failing-turn-write-refires-without-bound-and-fills-the-store`.

## Resolved

`c79a157fd` (2026-09-17). `restore-live-cluster-schema!` derives the
projection from `projection-from-database` at the connection the snapshot
hands it and installs it through `env/advance-projection!` — the seam
adoption and evaluation already advance through, so one restore path. The
entering key set is kept as EVIDENCE FOR NAMING only: a key the run added
without committing is `::drift-added` and is restored away; a key a committed
transaction retracted is `::committed-removed` and stays retracted; the two
additions are `::drift-removed` and `::committed-added`.
`schema-restore-drift` is the one place a row becomes a verdict, so only a
run's own uncommitted change is a test error. `::snapshot-schema-keys` left
the ambient drift detector with it — a before/after key-set diff cannot tell
a leak from a committed retraction, and keeping it would have left two owners
answering the same event differently.

Regressions (canonical fixture, contracts armed, in `seon.test.runner-test`):
`a-committed-retraction-survives-the-restore-and-is-named-a-committed-change`
commits a retraction AND an addition mid-run and proves the retraction stays
retracted and both are named committed changes;
`a-registration-that-committed-nothing-is-restored-away-and-named-drift`;
`a-run-that-touched-no-declaration-reports-nothing`, which also covers the
typed `::schema-authority-unavailable` observation.

Evidence and the measured numbers:
[drift-restore-derives-from-facts-2026-09-17](../../prds/steward-platform/research/drift-restore-derives-from-facts-2026-09-17.md).
That note also names the one PROTECTED hunk this lane could not touch:
`test/seon/test_support_test.clj:520-554`
(`a-synthetic-schema-registration-leaves-the-registry-byte-identical`)
asserts the superseded snapshot contract and is superseded by the second
regression above.
