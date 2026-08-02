---
type: issue
status: resolved
severity: friction
tags: [issue, database, datahike]
---

# Blob reachability names one attribute by hand and ignores history

## Problem

Seon's blobs live in the SAME konserve store as the Datahike index, so
`datahike.gc/gc-storage!` would sweep them unless the mark is extended.
`seon.cluster.registry/collect!` does extend it — correctly, by one
fact-derived hop before `konserve.gc/sweep!`. But the derivation has two
defects that will bite as soon as a second digest attribute exists (the
session-image `:seon.code.def/blob` in
`docs/prds/sci-execution-runtime/plan/stateless-resume-design-2026-08-01.md`
§3 is the next one).

1. **The reachable set names one attribute by hand.**
   `branch-result-blobs` asks `(contains? (:schema db)
   :seon.cluster.eval/result-blob)` and queries that single attribute. A
   new blob-referencing attribute means editing this function — a hand
   list, which the standing rule forbids. The digest attributes are
   derivable: they are exactly the schema attributes whose declared form is
   `:seon.blob/digest`.

2. **The mark reads only each branch's CURRENT db.** History stays on
   (ruling #23: "I really want time travel features"). A superseded
   blob-referencing datom is still reachable through the history value, so
   a sweep computed from current dbs deletes blobs that time travel still
   needs. Receipts are never superseded today, which is why this has not
   fired; session-image entries ARE superseded on every redefinition.

## Evidence

- `src/seon/cluster/registry.clj:284-297` — `branch-result-blobs`, the
  hand-named attribute and the current-db query.
- `src/seon/cluster/registry.clj:305-331` — `collect!`, the `with-redefs`
  extension of `konserve.gc/sweep!`.
- `reference-code/datahike/src/datahike/gc.cljc:83-146` — `gc-storage!`
  computes reachable keys per branch and calls `konserve.gc/sweep!` with
  that set; anything not in the set and older than the safe point is
  deleted.
- `reference-code/konserve/src/konserve/gc.cljc:8-40` — `sweep!` deletes
  every key not in the whitelist.
- `src/seon/blob.clj:11-30` — `seon.blob` writes into
  `(:store @connection)`, i.e. the Datahike store.

## Acceptance criteria

- The reachable blob set is DERIVED from the schema (every attribute
  declared as `:seon.blob/digest`), so adding a blob-referencing attribute
  requires no edit here.
- The mark includes blob digests reachable through history, or the decision
  not to is recorded with the retention rule that replaces it.
- A regression: write a blob, supersede its referencing datom, run
  `collect!`, and assert the blob is still readable at the earlier basis.

## Owner

Store/GC owner (`seon.cluster.registry`), coordinating with whoever lands
the session-image blob attribute.

## Resolution

Resolved by `78b1e6eca` (`Restore faithful SCI session values first`).
`seon.cluster.registry/blob-digest-attributes` derives every digest-bearing
attribute from the admitted schema, and `branch-blobs` queries a Datahike
history value for each roster branch. The same commit added the superseded-
datom regression and the second live digest attribute
`:seon.code.def/blob`.
