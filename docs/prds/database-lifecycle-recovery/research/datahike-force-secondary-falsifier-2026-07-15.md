---
type: research
status: complete
tags: [database, flow, research]
---

# Datahike force-secondary falsifier

## Dependency ledger

| Dependency | Selected source | Relevant mechanism |
|---|---|---|
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike/src/datahike/versioning.cljc`, `writing.cljc`, and `index/secondary.cljc` |
| Proximum bridge | selected through the writer alias | `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj` |
| Proximum | maintained checkout | `reference-code/proximum/src/proximum/hnsw.clj`, `versioning.clj`, and `audit.clj` |
| Seon | current branch | `src/seon/db/registry.clj`, `src/seon/embed.clj`, and `test/seon/db/restore_admin_test.clj` |

## Shortest falsifier

The retained JVM fixture uses a file backend, installs the declared Proximum
index, writes one normalized 1536-float vector on the selected branch, creates
prepared-target and undo branches, releases both setup connections, and calls
the invocation-local restore transition. The transition forces main, releases
the first connection, and opens a fresh read-back connection.

Observed on fresh read-back:

- main time equals the selected target time;
- main has exactly the selected target commit as parent;
- target and main EAVT sequences are equal;
- both declare and restore `:seon.embed/index`;
- the same vector query returns one hit from both indexes; and
- `datahike.index.audit/-merkle-root` differs between target and main.

The transition returns restore divergence with `force-invoked? true` and
connection state `released`. This is intentionally a passing fail-closed test,
not restore graduation evidence.

## Root cause

`datahike.versioning/branch!` already has a secondary-specific path. Its
`branch-secondary-indices` helper either calls `IVersionedSecondaryIndex`
`-sec-branch` on an exact live source or uses `branch-from-key-map` for a
detached stored snapshot, then flushes the new branch and closes its temporary
owner.

`force-branch!` does not use that mechanism. It associates the destination
branch into the Datahike DB config and passes the original live secondary map
to `db->stored`. `db->stored` calls `-sec-flush` on those source-branch live
instances with the destination branch argument. The Proximum bridge syncs its
internal index owner and returns a key map whose `:branch` comes from that
argument. This creates branch labeling and audit-root evidence that does not
represent a native destination-secondary transition.

## Smallest dependency patch and proof

Strengthen the one existing `force-branch!` mechanism; do not add a Seon copy
or a second force API. Before primary writes, derive destination-secondary
owners from the exact selected stored/live snapshot using the same capability
preflight and native branch operations as `branch-secondary-indices`. Flush
those destination owners once, build the forced stored DB and secondary audit
roots from their returned key maps, and close every temporary owner on both
success and failure. Publish the roster and primary head only after every
secondary write is complete.

The Datahike regression must use the file backend and registered Proximum
bridge with a real vector. It must compare fresh destination and source KNN
results plus audit roots, inject secondary preflight/branch/flush failure
before head publication, and retain the guarded stale-main test. Only after
that dependency proof passes should Seon's existing file-backed falsifier be
changed from expected divergence to applied plus already-applied retry.
