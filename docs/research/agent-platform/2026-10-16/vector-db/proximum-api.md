---
title: "Proximum fork seon-vectors: sync, restore and force contract after G0"
type: research
status: draft contract, written before the implementation lands; updated in place as it changes
created: 2026-10-16
---

# Proximum `seon-vectors`: the contract the Datahike adapter builds against

Lane proximum-fork (Opus). Fork `git@github.com:seantempesta/proximum.git`, branch `seon-vectors`, cut from
`9846d3e` (the sha `deps.edn` pins). Authority: [report.md](report.md) G0 and G6. The Datahike side (lane
datahike-vectors, `datahike.index.secondary.proximum`) reads this page instead of the fork's diff.

## What changes for a caller

**Snapshots no longer carry mmap evidence.** `proximum.protocols/sync!` writes no `vectors-generation-<commit>.bin`
and no `:mmap-generation`, `:mmap-generation-bytes` or `:mmap-generation-sha256` key. A commit names its vectors only
by what it already stored: the vectors address PSS root (`:vectors-addr-pss-root`, chunk id -> immutable konserve key
`[:vectors :chunk uuid]`) and the edges address PSS root. Snapshots written by `9846d3e` still load; their generation
keys are ignored and their chunks are read from konserve.

**A clean owner is one with a commit.** `(proximum.protocols/current-commit owner)` is non-nil exactly when the owner
equals a durable commit: `proximum.hnsw/update-hnsw-index` clears `:commit-id` on every mutation, and `sync!`,
`restore-index`, `branch!` and `force-branch!` set it. This replaces the adapter's
`generation-addressed-owner?` (a konserve read per Datahike commit, G2): the adapter's `-sec-flush` re-exports a clean
owner's key map without reading or syncing, and syncs a dirty one.

**`sync!` costs what the commit changed.** It writes the dirty vector chunk(s), the dirty edge chunks, the PSS path
nodes and the commit snapshot plus branch head, then records beside the working mmap which chunk addresses it now
holds (`<mmap>.chunks`, a few bytes per 1,000 vectors). Nothing is proportional to capacity or to N.

**Restore loads only chunks whose address differs.** `proximum.writing/load-commit`, `proximum.core/load`,
`proximum.protocols/restore-index` and `proximum.versioning/force-branch!` open a working mmap and load, from konserve,
only the vector chunks whose recorded address differs from the commit's. The edge graph is still read whole from its
chunks, as before.

**One owner per working mmap.** Each owner (one `proximum.vectors/VectorStore`, shared by the index values derived
from it) holds `vectors-<branch>.bin` in its `mmap-dir` while no other live owner does (its `.lock` file, taken with
`FileChannel/tryLock`); otherwise it gets a private `vectors-work-<branch>-<uuid>.bin`, cloned from the branch's file
when the filesystem can clone, which `close!` deletes. So two owners of one branch in one process (the live
connection and a force, a historical restore, a second connect) never write the same bytes.

**`force-branch!` is unchanged in shape.** `(proximum.versioning/force-branch! source destination
expected-destination-commit)`: `source` must be clean and equal its branch head; the destination head is replaced by
one atomic `konserve.core/update` that accepts the expected commit or an already-applied retry; the result is an owner
attached to `destination` at the source's commit. Its crash safety rests on the snapshot naming immutable konserve
chunks: whichever head is durable, restoring it reproduces that commit exactly. The former refusal
`:force-branch/legacy-source` (a source without generation evidence) is gone; every committed snapshot is forceable.

**`branch!` clones on macOS.** The mmap copy uses `cp -c` (APFS clonefile) on macOS and `cp --reflink` elsewhere;
`proximum.vectors/test-reflink-support` reports true on APFS. Lazy branching (G4) stays the adapter's.

## Unchanged

`insert`, `delete`, `search`, `search-filtered`, `branch!`, `load-commit`, `gc!`, the key map shape the adapter stores
(`{:type :proximum :branch :commit-id :merkle-root :mmap-dir :store-config}`), and Java 22+.

## For the adapter (datahike-vectors)

- Delete `generation-addressed-owner?`; `-sec-flush` tests `(pproto/current-commit current)`.
- Nothing reads `:mmap-generation*` any more. The guarded-force tests in `secondary_integration_test.clj` keep their
  assertions on head and commit identity; any assertion on generation files or evidence keys goes.
- `gc!` no longer sweeps `vectors-generation-*.bin`; files left by `9846d3e` stores are orphans to delete by hand.

## Status

Implementation in progress on `seon-vectors`; the final sha and measurements are added here when pushed.
