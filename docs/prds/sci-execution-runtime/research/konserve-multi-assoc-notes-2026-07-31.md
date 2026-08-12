---
type: research
status: active
tags: [research, datahike, architecture]
---

# Konserve filestore ordered multi-assoc — 2026-07-31

## Outcome

Implemented and committed locally in the maintained Konserve fork as
`737697d` (`Implement ordered filestore multi-key operations`). Publication is
owned by the fork-publication lane. The protocol, crash proof, and combined
fusion result are complete; the issue remains open because the unfused
18-object path regressed despite retaining exactly the fallback's operations.

## Dependency ledger

- Maintained konserve fork: `reference-code/konserve` at
  `b5c99bc02a71` before this repair.
- Maintained Datahike fork: `reference-code/datahike` at
  `9b3be9d59cb0`; this task reads it but does not
  edit it.
- Persistent sorted set: `0.4.137`, selected by the maintained Datahike fork;
  this is the first pin containing the concurrent-read correction required by
  the fusion configuration (`reference-code/datahike/deps.edn`).
- Konserve backing contract:
  `reference-code/konserve/src/konserve/impl/storage_layout.cljc:171-210`.
  `PMultiWriteBackingStore` accepts an ordered sequence of serialized blobs;
  `DefaultStore` exposes multi-key capability only when both multi-write and
  multi-read backing protocols are present
  (`reference-code/konserve/src/konserve/impl/defaults.cljc:632-667`).
- Datahike producer:
  `reference-code/datahike/src/datahike/writing.cljc:497-552`. It emits index
  children before parents, then schema metadata, the immutable commit record,
  and the mutable branch head last. Its existing per-key fallback durably
  completes each write before issuing the next.
- Existing benchmark owner:
  `tmp/perf-fsync/probe3-lib.clj` plus `probe3.clj` and `probe4.clj`; conditions
  and prior results are recorded in
  [[transact-throughput-regression-2026-07-31]].

## Falsifier and acceptance boundary

The recurring fork test starts a real child JVM against a project-local file
store, pauses at named multi-write stages, kills the child forcibly, reopens
the store, and asserts that the branch head and reachable facts are either the
complete old commit or the complete new commit. A prefix may leave unreachable
content-addressed blobs; it may never publish a branch head whose dependencies
are absent. Coordination is event-driven; any clock is only a loud foreign-
process backstop.

SIGKILL proves process-crash behavior but does not discard the kernel page
cache. The power-loss claim therefore also requires a crash-window proof over
the specified persistence primitives.

## Crash-model constraint discovered before implementation

`Files.move(..., ATOMIC_MOVE)` makes one rename atomic to observers; it does
not make a sequence of directory updates durable. Java's `FileChannel.force`
only guarantees changes made through that channel, and POSIX.1-2024 explicitly
allows later cached directory effects to reach storage while earlier effects
are missing after a crash. Consequently, writing and forcing every `.new`
blob, performing all ordered renames, and forcing the directory only once at
the end weakens Datahike's current guarantee: a crash before that final force
may retain the branch-head rename while losing an earlier dependency rename.

The zero-trade implementation must retain a durable barrier before the mutable
branch head can become durable, and must force the directory after publishing
that head before reporting success. A one-directory-force design is admissible
only if it adds a separately proven durable recovery mechanism; merely relying
on syscall order or atomic visibility is not sufficient. The controlling
specifications are Oracle's [`FileChannel.force`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
contract, which is scoped to the channel's file; Oracle's
[`Files.move`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))
contract, which promises one atomic move but no post-crash durability; and
POSIX.1-2024's [directory-operation persistence
model](https://pubs.opengroup.org/onlinepubs/9799919799/basedefs/V1_chap04.html),
which explicitly allows a later effect to reach storage while an earlier
effect is absent after a crash.

The minimal exact algorithm is therefore the fallback's operation order inside
one backing-protocol call:

1. serialize and write the next item to a unique staging file;
2. force and close that staging file when `:sync-blob?` is enabled;
3. atomically move it to the final name;
4. force the directory before starting the next item; and
5. return only after the last directory force.

An attempted variant wrote every staging file before the ordered publication
loop. It preserved the same barriers but increased the 18-object median to
`163.405 ms`; forcing each staged file immediately still measured
`157.371 ms`. Both variants were deleted. The retained path matches the
fallback's force/move/directory-force sequence exactly. The file-force count
and directory-barrier count cannot be reduced portably without a durable
recovery log or a stronger filesystem transaction primitive.

| kill position | admissible durable state after reopen |
|---|---|
| while writing or forcing a temporary file | already completed keys form a durable ordered prefix; the current final key remains old |
| during ordered move `i` | moves before `i` are a durable prefix; `i` is atomically old or new; later finals remain old |
| while moving the final branch head | every dependency is already durable; the head is atomically old or new |
| after the last directory force | the complete new batch is durable |

Datahike's externally reachable state is thus fully old or fully new even
though a pre-head crash may leave unreachable content-addressed orphans. That
is the intended distinction between logical commit atomicity and a physically
atomic multi-file write.

Two implementation details are part of the same correctness boundary:

- `PMultiWriteBackingStore`'s docstring still says every backend is atomic,
  while `konserve.core/multi-assoc` correctly promises ordering everywhere and
  atomicity only where the backend supports it. The backing contract must be
  reconciled before a filesystem can honestly implement it.
- Advertising multi-key capability also requires `PMultiReadBackingStore`.
  A filestore implementation returns closeable file channels, but
  `DefaultStore/-multi-get` currently does not close returned blobs. Closing
  on both success and failure needs recurring coverage so enabling the
  capability does not introduce a descriptor leak.

The implementation also uses unique staging names so concurrent batches do
not share `<key>.new`, and opens multi-read blobs read-only so a delete race
cannot recreate a missing key. Multi-delete forces the directory after each
successful delete, retaining the fallback's durable applied-prefix behavior.

## Proof

The recurring Konserve test
`test/konserve/filestore_multi_key_test.clj` covers capability advertisement,
the exact force/move/directory-force event order, duplicate-key sequence
order, read-resource cleanup, and four forced child-process kills. The full
fork gate at `737697d` is **85 tests, 1,585 assertions, 0 failures, 0 errors**.

The repository-local real-Datahike harness is
`tmp/bench/konserve_multi_assoc_datahike_kill.clj` with its child process in
`tmp/bench/konserve_multi_assoc_datahike_child.clj`. It replaces only the
Konserve dependency with `reference-code/konserve`, creates a fresh Datahike
file database per crash stage, kills the writer child, reopens the database,
and compares the branch commit ID with queried facts. Its final clean run:

| forced-kill stage | reopened state |
|---|---|
| `:staged` | fully old |
| `:after-first-move` | fully old |
| `:before-last-move` | fully old |
| `:after-last-move` before the final directory force | fully new |

No run exposed a new branch head with missing or old facts. As stated above,
SIGKILL is a process-crash falsifier; the per-item file and directory barriers
plus the specifications cited above establish the power-loss argument.

## Measurements

Final paired runs used the exact probe in `tmp/perf-fsync/probe3-lib.clj`, fresh
approximately 21,000-datom stores, five warmups plus 25 samples, one serial
caller, and history enabled. The old column uses pinned Konserve
`b5c99bc`; the new column replaces only that dependency with local commit
`737697d`.

| configuration | blobs | old median / p95 | new median / p95 | throughput |
|---|---:|---:|---:|---:|
| default | 18 | 93.732 / 107.203 ms | 156.093 / 160.848 ms | 11 → 6 tx/s |
| fusion + diff buffer | 1 | 20.019 / 21.140 ms | **18.012 / 19.123 ms** | 50 → 56 tx/s |

The investor-relevant combined path is a 10.0% median reduction and clears the
`<=25 ms` durable-commit acceptance at both median and p95. The unfused result
is a 66.5% latency regression. The recurring stage timings confirm that the 18
file forces and 18 directory forces still dominate; the additional gap has
not been isolated, so it is recorded rather than explained speculatively.
This fork should be enabled together with the already-selected fusion and diff
buffer configuration, not as a standalone default-path performance claim.
