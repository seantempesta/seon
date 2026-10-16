---
title: "Proximum fork seon-vectors: sync, restore and force contract after G0"
type: research
status: implemented on seon-vectors 46bbec0b00083af37c9074191cc35049aad832cb (pushed); deps.edn bump waits for a root restart batch
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

- Delete `generation-addressed-owner?`; `-sec-flush` tests `(pproto/current-commit current)`. This must land in the
  same restart batch as the bump: with the stock adapter, no snapshot carries generation evidence, so every Datahike
  commit syncs Proximum and writes a Proximum commit (measured: an unrelated commit 91-100 ms and 9 Proximum files,
  against 57-60 ms and none with the G2 change).
- Nothing reads `:mmap-generation*` any more. The guarded-force tests in `secondary_integration_test.clj` keep their
  assertions on head and commit identity; any assertion on generation files or evidence keys goes.
- `gc!` no longer sweeps `vectors-generation-*.bin`; files left by `9846d3e` stores are orphans to delete by hand.

## Status and measurements

Fork commits on `seon-vectors` (pushed): `055470f` (compaction abort cleanup read its target from the wrong place) and
`46bbec0b00083af37c9074191cc35049aad832cb` (G0). The fork's suite: 187 tests, 9,991 assertions, 0 failures. New tests
in `proximum.working-mmap-test`; the generation tests of `proximum.branching-test` are converted or deleted with their
machinery.

Measured with [vector_bench.clj](vector_bench.clj) (plus a reconnect row) on scratch stores, Datahike `88d05cf8` with
the G2 adapter change shadowed in scratch, same machine and session (load 6-18, so ratios and bytes are the
evidence). "1-vector commit" is one `d/transact` of one vector.

| case | before (`9846d3e`) | after (`46bbec0`) |
|---|---|---|
| 768 d, N=10k, cap 20k: 1-vector commit | 285-290 ms, 184 MB mmap written | 180-189 ms, 0 B mmap written |
| 768 d, N=10k, cap 110k: 1-vector commit | 693-720 ms, 1,014 MB mmap (report) | 182-198 ms, 0 B mmap |
| 768 d, N=100k, cap 110k: 1-vector commit | 767-815 ms, 1,014 MB mmap (report) | 240-246 ms, 0 B mmap |
| Proximum konserve bytes per 1-vector commit | 4.15 MB at 10k, 12.1 MB at 100k | 4.0 MB at 10k, 11.7 MB at 100k (edge chunks, G6) |
| unrelated commit (no vector), with index | 58-60 ms | 57-60 ms (baseline without index 54-60 ms) |
| `d/branch!` then first branch commit, 10k | 70 ms, then 312 ms and 123 MB mmap | 102 ms, then 262 ms and 0 B mmap |
| reconnect (release + connect), 10k | 96 ms | 12 ms (no vector chunk loaded) |
| reconnect, 100k | not measured | 27 ms |
| kNN k=10 | 1.15 ms at 10k | 1.08 ms at 10k, 1.96 ms at 100k |

The remaining 1-vector commit cost is Proximum's konserve writes: about 30 dirty edge chunks plus the vector chunk and
PSS nodes, 53-110 files each written with `{:sync? true}` (G7). One unexplained number: at 100k the first commit on a
new Datahike branch took 1,072 ms (before: 789 ms), with no mmap bytes written; not isolated.

**G6 (edge chunk size), measured, not changed.** Proximum alone, 256 d, M=16, `PersistentEdgeIndex` CHUNK_SIZE
rebuilt at 1,024, 256 and 64 nodes:

| N=100k | 1,024 | 256 | 64 |
|---|---|---|---|
| bytes per 1-vector commit (median) | 3.85 MB | 1.16 MB | 0.41 MB |
| dirty edge chunks per commit | 28 | 32 | 33 |
| 1-vector commit | 186-257 ms | 362 ms | 289-332 ms |
| bulk build of 100k | 29 s | 39 s | 55-57 s |
| kNN k=10 | 0.80-0.85 ms | 1.10 ms | 1.21-1.27 ms |
| restore (edges read whole) | 49-132 ms, 1,555 files | 389 ms, 2,466 files | 352-723 ms, 5,180 files |

At N=10k: 1.37 MB, 0.84 MB and 0.30 MB per commit; 199, 268 and 288 ms. A commit dirties about 30 chunks at every size,
so the bytes fall with the chunk while the time follows the per-key konserve write, and build, query and restore get
slower. CHUNK_SIZE stays 1,024: smaller chunks trade time for storage written, and the constant is part of the edge
address encoding (a changed size cannot read an existing store). Storage per commit is better attacked through G7
(compression, deferred sync) and retention.

## Open

- Each owner leaves a 0-byte `vectors-<branch>.bin.lock`, and `.chunks` records sit beside branch files; old
  `vectors-generation-*.bin` files from `9846d3e` stores are orphans nothing deletes.
- A private working mmap (a second live owner of a branch: force, historical restore) loads every vector chunk from
  konserve, O(N); only the branch's own file is reused.
- An owner never closed keeps its lock until the JVM exits, so later owners of that branch get private files.
