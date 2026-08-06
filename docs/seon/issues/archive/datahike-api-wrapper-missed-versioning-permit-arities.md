---
type: issue
status: resolved
severity: blocker
tags: [issue, database, datahike, operator]
---

# Keep public Datahike arities aligned with versioning arities

## Problem

The maintained fork widened `datahike.versioning/branch!` and
`delete-branch!` to accept an options map carrying an already-held
reachability permit, but their `datahike.api.impl` wrappers retained only the
older arities. Seon's cluster registry calls the public API, so every fresh
boot carrying the sweep gate's roster permit failed before a cluster branch
could be created.

## Evidence

- `reference-code/datahike/src/datahike/versioning.cljc:212-321` accepts the
  options arities and reads
  `:datahike.gc-guard/reachability-permit`.
- Before the repair,
  `reference-code/datahike/src/datahike/api/impl.cljc:336-342` exposed only
  three arguments for `branch!` and two for `delete-branch!`.
- `src/seon/cluster/registry.clj:183-190` passes four arguments to public
  `datahike.api/branch!` when it already holds the roster permit.
- A fresh scratch-root start reached the operator's `store` boot phase and
  failed with `Wrong number of args (4) passed to:
  datahike.api.impl/branch!`. The branch was absent afterward, so the export
  verb proof could not begin.

## Owner

The public Datahike API wrappers and their shared API specification. An
internal versioning arity and its public wrapper are one additive contract and
must change together.

## Acceptance

- Public `branch!` and `delete-branch!` forward opts with the tier's forced
  `:sync?` value.
- Only internal functions that already accept opts receive public opts
  arities.
- A fork regression holds one roster permit across public branch creation and
  deletion.
- A fresh Seon scratch root boots through branch creation.

## Closed 2026-08-06

Resolved in Datahike fork commit `10540578248eaa686c1f88a7fe57644ee4c9f993`
and pinned by Seon commit `558d22b62`. The public wrappers now mirror the
internal opts arities, the API specification publishes both call shapes, and
`datahike.test.versioning-test` proves one supplied permit crosses public
branch creation and deletion. Fork verification passed 30 tests / 210
assertions in the versioning focus and 66 tests / 387 assertions in the API
focus, with zero failures.
