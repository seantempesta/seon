---
type: issue
status: resolved
severity: blocker
tags: [issue, source, indexing, database]
---

# Refuse incremental publication after an unreported source edit

## Problem

A changed-path publication must not seal the current whole-tree digest onto
database rows derived from an older unreported file. Complete publication must
also rebuild the database projection even when the requested digest equals the
published digest.

## Evidence

The initial review measured an uncommitted tree while commit `995ccec92`
already contained the agreement mechanism. Git history shows that commit
introduced `unreported-source-current?`, unconditional complete scratch
publication, complete desired-artifact replacement, and scalar delta rows.

The remaining current defect was narrower: `source/upsert!` rejected known
cardinality-many and component attributes but treated an attribute absent from
the installed Datahike schema as scalar-safe.

## Owner

`seon.cluster` current-source admission and `seon.cluster.source` publication.

## Acceptance

- Every unreported first-party file and schema resource matches the published
  artifact before incremental upsert.
- Any mismatch selects a complete scratch build.
- Complete publication repairs stale rows even under an equal digest.
- Incremental publication accepts only installed cardinality-one,
  non-component attributes.
- Two consecutive safe edits retain a complete file artifact.

## Resolution

Commit `e886b8e7c` makes scalar admission fail closed for unknown schema
attributes. A real temporary source-project regression performs two consecutive
safe edits, then a missed-X/reported-Y sequence, and reads both current function
sources from `current-src`. Separate database regressions corrupt a row beneath
an unchanged digest and prove complete publication repairs it, while unknown
and cardinality-many deltas both refuse without advancing the branch.

Focused verification passed 33 tests and 162 assertions with zero failures.
