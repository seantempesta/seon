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
| Proximum | `org.replikativ/proximum` `0.1.25`; maintained checkout `5f7142d532aa173071f5651af91414b983d7320f` | `reference-code/proximum/src/proximum/versioning.clj`, `writing.clj`, `vectors.clj`, and `protocols.clj` |
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

The first isolated Datahike prototype native-branched live versioned
secondaries before `db->stored`, let that existing serializer flush each
temporary owner once, and closed all owners before primary publication.
Synthetic versioned-index falsifiers proved branch/flush/close order and a
branch failure with the primary head unchanged: the focused versioning suite
passed 21 tests and 144 assertions across its configured test variants.

That prototype is not a complete fix. Exact Proximum source establishes a
deeper native boundary:

- `proximum.versioning/branch!` checks the native branch roster and rejects an
  existing destination at lines 83-88, then creates the destination mmap,
  graph, snapshot, head, and roster at lines 96-117. A crash after this native
  publication but before Datahike publishes its primary head leaves a branch
  artifact that makes the same restore retry fail with `Branch already
  exists`.
- `proximum.writing/load-commit` reads `snapshot-branch` and chooses
  `(or snapshot-branch branch)` at lines 225-227. Consequently, changing only
  the Datahike key map's `:branch` does not attach a source snapshot to `:db`;
  the snapshot's recorded prepared branch wins.
- `datahike.index.secondary.proximum/-sec-branch` delegates directly to that
  rejecting `proximum.versioning/branch!` at selected bridge lines 203-206.
  Datahike has no native operation with which to atomically replace or exactly
  adopt an existing Proximum destination.

Therefore branch-before-serialize is necessary but insufficient. Deleting the
old native destination before forking is also invalid: until the Datahike head
publishes, that old destination remains the secondary state named by the
authoritative old primary head. A failure between deletion and recreation
would corrupt the state that must remain readable.

## Smallest dependency patch and proof

The dependency order has two owners and still exposes only Datahike's one
existing public `force-branch!` operation:

1. Proximum needs a guarded native force/replace primitive. It must take an
   exact committed source and expected destination head, stage the vector mmap
   plus graph snapshot without disturbing the live destination, atomically
   publish a destination-owned snapshot, and return an owner whose actual
   branch is the destination. Repeating the same request after lost response
   must exactly adopt or converge; a different source or destination head must
   fail closed. Every source, staged, replaced, and returned resource owner
   must be closed on success and failure.
2. Datahike then strengthens `force-branch!` in place to preflight every
   declared versioned secondary, invoke its native guarded force before any
   primary head publication, flush the returned destination owner exactly
   once through `db->stored`, build the stored key maps and audit roots from
   that result, and close every temporary owner. An optional internal
   secondary capability is acceptable; a second public force API is not.

The integration must inject a failure after native secondary publication but
before Datahike primary publication, then prove a second force from the same
selected snapshot to the same destination converges. It must also prove that a
different retry is rejected and that the old primary plus old destination
secondary remain readable before primary publication.

The Datahike regression must use the file backend and registered Proximum
bridge with a real vector. It must compare fresh destination and source KNN
results plus audit roots, inject secondary preflight/branch/flush failure
before head publication, inject the post-secondary/pre-primary response-loss
window, and retain the guarded stale-main test. It must exercise a prepared
branch forced onto an already-existing `:db`, not only `:db` branched to a new
name. Only after that dependency proof passes should Seon's existing
file-backed falsifier be changed from expected divergence to applied plus
already-applied retry.
