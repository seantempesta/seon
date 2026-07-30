---
type: issue
status: open
severity: blocker
tags: [issue, source, indexing, database]
---

# Refuse incremental publication after an unreported source edit

## Problem

A changed-path publication verifies the artifact's commit but does not prove
that unreported first-party files still match the published artifact. A missed
edit in file X can therefore be followed by a reported edit in file Y; the
operation publishes the whole current-tree digest while retaining X's stale
database rows.

## Evidence

`docs/prds/sci-execution-runtime/research/current-src-adversarial-review-2026-07-30.md`
traces the mismatch through `src/seon/cluster.clj:559-618`. Complete
publication can then short-circuit on digest equality in
`src/seon/cluster/source.clj:125-128`, allowing the stale graph to survive.

## Owner

`seon.cluster` current-source admission and `seon.cluster.source` complete
publication.

## Acceptance

- Every unreported first-party file and schema resource must still match the
  published artifact before an incremental upsert is allowed.
- Any mismatch selects a complete scratch build.
- Complete publication proves row agreement rather than treating digest
  equality alone as sufficient.
- A missed-X, reported-Y regression cannot publish stale X rows under the new
  source digest.
