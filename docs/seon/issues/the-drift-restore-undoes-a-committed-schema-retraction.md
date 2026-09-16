---
type: issue
status: open
severity: friction
created: 2026-09-17
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
