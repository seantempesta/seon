---
type: review
date: 2026-09-27
status: complete; adoption requires fixes
verdict: ACCEPT-AFTER-FIXES
reviewed-commit: b8d92d10d5b7ed40c78a2c5ca9ac0e1eb75a0225
verification: source and primary-document review; no tests or restarts
---

# Review of konserve's deferred filestore synchronization

**Verdict: ACCEPT-AFTER-FIXES.** The two-directory-barrier algorithm is sound for
new, previously unreferenced files on local APFS, with successful synchronization
and hardware honoring Apple's commands. A separate full device flush for every
blob is unnecessary. However, the patch selects that algorithm from
`:immutable?` alone. That marker neither establishes that the pathname is new nor
authorizes weakening a standalone write's durability. Fix those two conditions
before adoption, and preserve the original writer-descriptor synchronization on
Linux. The exact bounded correction is specified below.

This is a review of source, not a report of an observed power-loss failure or an
installed change. The starting evidence is
[the commit-floor report](commit-floor-2026-09-26.md) at Seon commit `5471c0f61`.
Its 3.78 ms versus 0.03 ms and 71–76 ms versus 14–15 ms measurements were not
repeated. Readback and elapsed time establish neither crash consistency nor
durability.

## Scope and source identities

- Konserve: `b8d92d10d5b7ed40c78a2c5ca9ac0e1eb75a0225`, compared with parent
  `5b39fddd6ae58bae7d08880daecd1ad7242ea273`. References below are to that commit.
- Datahike: `67b00071e020a7ccf310985d01b28128cdadec34`, the inspected checkout.
- Seon's `deps.edn:37–39` still named konserve `5b39fddd…` when inspected. The
  fork checkout alone is not evidence of dependency adoption or JVM installation.
- Apple XNU documentation: `e3723e1f17661b24996789d8afc084c0c3303b26`
  (`xnu-11417.101.15`). OpenJDK source: `jdk-25+36`; this identifies the inspected
  implementation, not the running JVM. External sources were retrieved on
  2026-09-26.

## What the implementation actually synchronizes

`reference-code/konserve/src/konserve/filestore.clj:43–53,219–237` establishes:

1. `-sync-data` closes over a **blob pathname**, reopens it with
   `RandomAccessFile(path, "r")`, and invokes `FileDescriptor.sync`.
2. `-sync-store` calls `sync-base`, which opens **the store's base directory**
   with `FileChannel.open(base, [])`, calls `force(true)`, then closes it. It
   does not open a sentinel, the branch-head file, a device, or every child.
3. All normal keys and their `.new` files are direct children of that directory:
   `impl/defaults.cljc:47–48,91–93`; moves use `ATOMIC_MOVE` and
   `REPLACE_EXISTING` (`filestore.clj:200–206`). No recursive directory sync is
   involved.
4. Any non-nil custom `filesystem` skips directory synchronization entirely. The
   code comments mention Jimfs, but the condition covers every custom provider.
   Such a provider cannot inherit a physical-durability claim from this protocol.

The JDK distinctions are real. On macOS,
[FileDispatcherImpl.c:44–66](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/macosx/native/libnio/ch/FileDispatcherImpl.c#L44)
calls `fcntl(fd, F_FULLFSYNC)`; it falls back to `fsync` only for a non-local
filesystem. Local failures propagate. In contrast,
[FileDescriptor_md.c:60–65](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/unix/native/libjava/FileDescriptor_md.c#L60)
calls `IO_Sync`, defined as `fsync` in
[io_util_md.h:62](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/unix/native/libjava/io_util_md.h#L62).
Consequently, a successful Java `force` on an arbitrary remote mount is not
proof of the macOS device-wide barrier assumed here.

## 1. Rename metadata and synchronization on another descriptor

**An unrelated file: no general guarantee for an unsynchronized rename. The
containing directory: yes, by synchronizing that directory's metadata and then
flushing the device.** These are different operations.

Apple specifies `F_FULLFSYNC` as an `fsync` of the supplied descriptor followed
by a device-cache flush. Its current manual explicitly includes APFS. A device
flush cannot flush directory metadata still resident only in the filesystem's
memory. A full sync on file B therefore does not, merely because B shares a
device, promise to submit an outstanding rename in directory D. Conversely, a
full sync on D includes the synchronization of D itself. The patch uses D.
[Apple fcntl(2), F_FULLFSYNC](https://github.com/apple-oss-distributions/xnu/blob/e3723e1f17661b24996789d8afc084c0c3303b26/bsd/man/man2/fcntl.2#L229)

POSIX.1-2024's `fsync` application guidance explicitly identifies synchronizing
the directory as the way to synchronize its entries and attributes. Its general
transfer requirements remain implementation-defined; POSIX by itself does not
supply Apple's device-cache guarantee.
[POSIX fsync](https://pubs.opengroup.org/onlinepubs/9799919799/functions/fsync.html)

APFS has copy-on-write metadata and crash protection. Those properties do not
turn every completed rename into an immediate durable commit, nor do they make
directory sync recursively synchronize file contents.
[Apple APFS overview](https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/APFS_Guide/Introduction/Introduction.html)
Apple's `rename(2)` additionally promises preservation of a destination instance
through a crash during replacement. That protects the old-or-new namespace
transition; it does not promise that unflushed replacement contents survive.
[Apple rename(2):58–81](https://github.com/apple-oss-distributions/xnu/blob/e3723e1f17661b24996789d8afc084c0c3303b26/bsd/man/man2/rename.2#L58)
[POSIX rename](https://pubs.opengroup.org/onlinepubs/9799919799/functions/rename.html)

The conclusion for APFS directory synchronization is a composition of these
documented interfaces. It is not an audit of APFS's private checkpoint code.
There is no need to assume that syncing one arbitrary vnode commits every dirty
vnode in the volume. The pre-head `-sync-store` already targets the correct
directory; adding a separate `fsync(dir)` immediately before the same successful
`F_FULLFSYNC(dir)` would duplicate its first phase.

## 2. Is fsync of the blob sufficient before a later full flush?

**Yes, for successfully synchronized data on the same device.** Apple's current
manual states that a completed `F_FULLFSYNC` persists previously fsynced data
on that device, including data from other descriptors. Its description of
`F_BARRIERFSYNC` expressly discusses ordering across multiple descriptors.
[Apple fcntl(2):206–244](https://github.com/apple-oss-distributions/xnu/blob/e3723e1f17661b24996789d8afc084c0c3303b26/bsd/man/man2/fcntl.2#L206)

Plain macOS `fsync` submits modified file data and attributes to the device,
but does not ensure persistence of the device's volatile cache. Writes can be
reordered there. Thus the later full flush is necessary for the proposed
durability promise; `close`, readback, and `fsync` alone are insufficient.
[Apple fsync(2)](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/fsync.2.html)

The sequence for new files is therefore:

```text
each node:     write tmp; fsync(tmp); rename(tmp, node)
commit record: write tmp; fsync(tmp); rename(tmp, commit)
head:         write tmp; fsync(tmp)
              F_FULLFSYNC(store directory)       [A]
              rename(tmp, head)
              F_FULLFSYNC(store directory)       [B]
              report successful durable commit
```

A includes all completed node/record renames and all previously fsynced blob
contents, including the new head's temporary contents. B persists the head
replacement. This is O(N) blob writes and fsyncs with two full flushes per
ordinary head publication, rather than two full flushes per key. It assumes an
already durably created store directory; synchronizing that directory does not
establish the durability of a newly created ancestor chain. Store-directory
creation is an existing boundary, not repaired by this patch.

## 3. Crash windows and Datahike's publication order

The active filestore lacks the multi-read/multi-write backing protocols required
by `impl/defaults.cljc:663–666`. Datahike therefore takes the individual-write
path in `writing.cljc:550–572`. It waits for schema metadata, all pending index
writes, and the immutable commit record before issuing the mutable head write.
The asynchronous commit-record wait occurs **before starting** the head call.
`write-pending-kvs!` awaits every node operation (`writing.cljc:423–434`).
`:sync?` chooses blocking versus channel APIs; `:sync-blob?` controls storage
synchronization. Asynchronous API use does not disable durability.

The head stores a copy of the root record, not just a commit-id pointer. Both
the index addresses it contains and its historical commit record must survive.
The initialization path (`writing.cljc:722–735`) and `force-branch!`
(`versioning.cljc:404–444`) also put values before the mutable publication.

For **new keys**, with an existing durable old head and successful I/O:

| Power-loss window | Recoverable state under the stated filesystem/device contract |
|---|---|
| During node/record writes, or before A completes | Old head remains authoritative. New unreferenced files may be absent, partial, or complete. |
| After A completes, before the head rename | Old head; all new dependencies and head temporary contents are durable. |
| During/after the head rename, before B completes | Old or new head. A has made every dependency of the new head durable already. Commit acknowledgment has not occurred. |
| After B succeeds | New head and its dependencies are durable. |

A failed pre-barrier prevents the head move through `<?-`; a failed post-barrier
must not be reported as success. In the latter case the head may already have
changed, but its dependencies were made durable by A. A process-only crash is
different from power loss: kernel and device caches generally remain available.
The added test exercises neither loss of those caches nor the error cases.

### Finding A: replacing an already referenced immutable key invalidates the proof

**Required fix.** `:immutable?` is metadata, not a create-only operation.
`core.cljc:348–374` describes `assoc` as an overwrite. Its full-overwrite path
skips the existence probe and old-value read
(`impl/defaults.cljc:297–311,347–361`), and the final move replaces the existing
file. Datahike's persistent-set storage also queues generated addresses without
an on-disk absence check (`index/persistent_set.cljc:411–425`). Content addressing
does not prohibit writing the same address twice.

Consider an already durable head H referencing N. A later operation writes the
same immutable N again, even with identical logical contents:

```text
durable H -> old N
write replacement N.new; fsync(N.new); rename(N.new, N)
power loss before any subsequent mutable write/barrier
```

The old inode has been replaced without a full flush of the replacement.
The documented macOS ordering permits the rename's metadata to reach stable
storage while replacement data remains volatile. H was durable before this
operation; waiting to publish a new head cannot protect it. This is a permitted
failure ordering, not an experimentally demonstrated APFS failure. Accepting the
patch would require an additional APFS data-ordering guarantee not established
by the cited interfaces. The parent implementation fully forced replacement
contents before renaming them.

### Finding B: standalone immutable writes lose their existing durability contract

**Required fix.** `core.cljc:354–357` still describes immutable values as durably
recorded. The patch can acknowledge such a write, emit its write hook, and never
perform another mutable write. On macOS its data and name are then not promised
power-loss durability. On Linux its final name is not promised durability.
Immutability and publication through a later head are independent properties.
An external consumer can also reference the returned key without using this
store's mutable-write path.

## 4. Linux: ordering survives; behavior is not unchanged

On Linux the inspected JDK's
[UnixFileDispatcherImpl.force0](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c#L138)
uses `fsync` for `force(true)`. Linux `fsync` includes device-cache persistence
and file metadata, but separately requires a directory fsync for directory
entries. Thus file fsyncs followed by directory fsync before and after the head
rename preserve the successful-I/O commit ordering. They still weaken standalone
immutable completion, and they add a directory sync for mutable writes.
[Linux fsync(2)](https://man7.org/linux/man-pages/man2/fsync.2.html)

There is a second change: `update-blob` now closes the writing channel before
opening a fresh descriptor to synchronize. Linux tracks writeback errors against
an open file description. Opening initializes a fresh error cursor, and fsync
checks errors since that cursor. An already observed error need not be reported
to a newly opened descriptor. An unseen error can still be reported; reopening
does **not** always erase errors. Nevertheless, it forfeits the original writer's
error-observation interval. A later directory sync is not a substitute for
checking that file's writeback result.
[Linux v6.12 open.c:914–918](https://github.com/torvalds/linux/blob/v6.12/fs/open.c#L914)
[filemap.c:711–759](https://github.com/torvalds/linux/blob/v6.12/mm/filemap.c#L711)
[Linux errseq documentation](https://www.kernel.org/doc/html/latest/core-api/errseq.html)

That is a source-backed error-handling regression risk, not a tested reproducer.
Keep the original writer's `-sync` before `-close` on Linux. There is no macOS
full-flush saving from changing that descriptor there.

## Exact correction recommended before adoption

Keep the existing algorithm and narrow its eligibility; no new cache, queue or
transaction manager is needed.

1. Add an explicit, default-off filestore configuration option, for example
   `:defer-immutable-sync?`. It opts into **new immutable keys becoming durable
   through a later successfully completed mutable write in this same store**.
   `:immutable?` alone retains the old per-write guarantee. Scope the first
   implementation to the native local APFS case reviewed here; Linux, remote
   mounts and custom providers retain the parent's path. Enable it only for
   callers whose publication follows the proved ordering. Document that early
   immutable write hooks/completion report staging, not durable publication.
2. Defer only a key that was absent **before the operation opened or created the
   destination**, checked under the existing per-key write serialization.
   For an existing immutable key, use the patch's mutable sequence instead:
   `fsync(tmp) -> full-sync(directory) -> replace -> full-sync(directory)`.
   This forces replacement contents before exposing them even if the key is
   already reachable from an old head. Do not infer absence from `[nil nil]`:
   that is the overwrite fast path, not a lookup result. Do not probe only
   inside `update-blob`: `io-operation:342` has already opened the destination
   with `CREATE`. Capture and pass the pre-create existence decision instead.
   A pre-read is valid only within serialization covering all writers; otherwise
   use a create-only installation primitive or the fully synchronized path.
3. With deferral disabled/ineligible, execute exactly the parent's sequence:
   write original channel, `-sync` that channel, close, atomic move,
   `-sync-store`. This preserves Linux's original descriptor and public
   durability behavior. Change the new protocol documentation to state these
   prerequisites instead of asserting that every immutable key is unreferenced.

In pseudocode, the only key allowed to skip both directory barriers is:

```text
defer-key = explicitly enabled local-APFS deferral
            AND sync-blob enabled AND not in-place
            AND immutable metadata
            AND destination absent under the write's serialization
```

All opt-in writes still fsync their temporary blob. Every opt-in write that is
not `defer-key` executes A and B. Existing-key replacements therefore pay the
barriers; the common new-node commit still pays only its head's two full flushes.
The additional cost is one existence check per candidate new key, O(N), with no
whole-store scan. No new performance estimate is claimed.

If that qualification is undesirable, the smaller conservative fallback is to
keep the parent's per-key durability. The evidence does not justify adopting
the unqualified `:immutable?` inference to preserve the measured speedup.

## 5. Alternatives and database precedents

**F_BARRIERFSYNC before the head, F_FULLFSYNC after it.** Apple's current manual
specifies ordering, not persistence on return, for a barrier. After fsyncing all
blob contents, a barrier on the directory can submit and order the earlier
renames before the head replacement; a final full directory sync then supplies
durable acknowledgment. This could replace A and retain B. It needs native
access and supported hardware, and does not remove Findings A or B. Replacing
both full flushes with barriers changes the durability promise.
[Apple fcntl(2), F_BARRIERFSYNC](https://github.com/apple-oss-distributions/xnu/blob/e3723e1f17661b24996789d8afc084c0c3303b26/bsd/man/man2/fcntl.2#L206)

**One directory fsync per commit.** A lone post-head fsync/full-sync leaves a
crash interval before it completes in which the head may persist ahead of its
dependencies. A lone pre-head sync does not durably acknowledge the head's own
rename. There must be an ordering boundary before publication and a durability
boundary after it, or an actual atomic storage transaction covering both.
The patch already batches directory work to those two boundaries.

**SQLite.** Upstream documents `PRAGMA fullfsync`, default off, and a separate
checkpoint setting. Its atomic-commit discussion acknowledges the cost and
stronger macOS flush guarantee. This is an explicit tradeoff, not proof that
plain macOS fsync provides power-loss durability.
[SQLite fullfsync](https://www.sqlite.org/pragma.html#pragma_fullfsync)
[SQLite atomic commit §9.2](https://www.sqlite.org/atomiccommit.html#_broken_flush_implementations)
At SQLite `2acb2ea9089c604d5786f56ee3e50a0479a268dd`, `full_fsync` still uses
`F_FULLFSYNC` when requested and falls back to fsync; `unixSync` separately
handles the journal's directory entry. This source contains no
`F_BARRIERFSYNC`. Do not conflate upstream SQLite with Apple's system build or
an experimental SQLite branch.
[SQLite os_unix.c](https://github.com/sqlite/sqlite/blob/2acb2ea9089c604d5786f56ee3e50a0479a268dd/src/os_unix.c)
Apple separately recommends barriers for applications needing ordering, and
reserves full sync for a stronger persistence expectation.
[Apple reducing disk writes](https://developer.apple.com/documentation/xcode/reducing-disk-writes)

**LMDB and konserve-lmdb.** Version matters. LMDB 0.9.33 maps its macOS data
sync to plain fsync. The inspected current source, `a76084d1c36d05c28c19c5a28cade30cd173aac8`,
instead uses `F_FULLFSYNC`, writes and flushes pages before its metapage, and
explicitly synchronizes macOS metapage writes. The relevant changes include
ITS#10296 and ITS#10454; citing the old implementation as present-day approval
of plain fsync would be incorrect.
[LMDB 0.9.33 mdb.c:129–133](https://github.com/LMDB/lmdb/blob/LMDB_0.9.33/libraries/liblmdb/mdb.c#L129)
[Current LMDB data synchronization](https://github.com/LMDB/lmdb/blob/a76084d1c36d05c28c19c5a28cade30cd173aac8/libraries/liblmdb/mdb.c#L171)
[Current LMDB commit order](https://github.com/LMDB/lmdb/blob/a76084d1c36d05c28c19c5a28cade30cd173aac8/libraries/liblmdb/mdb.c#L4204)
[Current LMDB metapage sync](https://github.com/LMDB/lmdb/blob/a76084d1c36d05c28c19c5a28cade30cd173aac8/libraries/liblmdb/mdb.c#L4490)
LMDB's documented `MDB_NOMETASYNC`, `MDB_NOSYNC`, and `MDB_MAPASYNC` tradeoffs
also distinguish database integrity from durability; they are not equivalent to
fully durable default commits.
[LMDB environment flags](https://github.com/LMDB/lmdb/blob/a76084d1c36d05c28c19c5a28cade30cd173aac8/libraries/liblmdb/lmdb.h#L582)

Konserve-lmdb advertises atomic multi-key writes, which fit Datahike's existing
`multi-assoc` branch and move commit ordering into one dependency transaction.
It uses an external native LMDB library, so its loaded native revision and flags
must be verified, not inferred from the Clojure package. It is a plausible
architectural simplification but a backend/format migration, not a smaller
filestore patch. No adoption or performance claim is made here.
[Konserve-lmdb README at 109dc78c](https://github.com/replikativ/konserve-lmdb/blob/109dc78c5dc053f5d96f576dc04c0d7c2b75bb4b/README.md)

## Verification limits and disposition

Only this review document was authored. No Clojure changes, tests, fault
injection, runtime evaluation, restart, dependency update or installation was
performed. The added fork test reads values back and checks metadata and
temporary filenames (`test/konserve/filestore_test.clj:85–109`); it is not
power-loss evidence. No new timing or memory measurements exist for this review.

The directory-fd and cross-file cache-flush arguments are supported. The claim
that every immutable key remains unreferenced until a later mutable write is
not supported by the actual overwrite API. Adoption should wait for explicit
deferral semantics, protection of already published keys, and retention of the
original synchronization path outside the reviewed platform scope.
