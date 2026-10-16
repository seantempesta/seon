---
title: "Commit cost: live attribution and tuning evidence"
type: research
status: measured
created: 2026-10-16
---

# Commit cost — 2026-10-16

Lane: commit-cost. The measured ordinary eval recording writes **136,974 bytes in 22 files for 20 added
datoms**, whose Fressian encoding is 922 bytes. AEVT is the largest producer. Keeping ended branches retains
these immutable writes; it does not cause their amplification. No production change or dependency patch is
included: the duplicate head write is already fixed, and the remaining tuning experiments do not establish
a durable end-to-end improvement. Default was neither stopped nor restarted nor reset.

## Evidence and scope

[probes.edn](probes.edn) contains exact JVM-mode forms and complete replies for attribution, delta size,
node-size and buffer replay, root encoding, reachability, and collection receipts. All calls explicitly used
agent root, cluster default, mode jvm. Forms read immutable snapshots; their ordinary evaluation recordings
write through the existing entrance. Replay storage is private PSS CachedStorage with pending writes only:
no durable store keys, new branches, loaded definitions, or changed production Vars. Materialized databases
were released in finally. No disposable JVM or filesystem store was created.

Installed default: PID 98702, start 2026-10-15T23:39:29.526Z, committed source archive
8dd4334f89781395b98f8e6fcc8f67b1137be694. Live resource URLs establish Datahike
88d05cf830d5d39eddf8e22be815889da7448bd6, Konserve a2a24792ff410235ea4c211f8434785c6e3a6576,
and persistent-sorted-set 0.4.137. The working Konserve checkout is af35a974, different from both its
gitlink and the loaded dependency; it was not edited. PSS Branch.java was also read at tag 0.4.137, not
inferred from a newer checkout. The Seon configuration is branching-factor 128, diff-buf-size 32,
LZ4 compression, fused roots, and deferred immutable sync.

Initial health was faulted: 31 open faults, 371 errored receipts, 19 agent-error signatures, no missing
readiness layers. This was pre-existing. The probes did not require recovery.

## One ordinary recording, attributed by content

Submitted form:

```clojure
{:answer (+ 1 2)
 :before (get-in @(seon.cluster.boot/connection "default") [:meta :datahike/commit-id])}
```

Parent 6ad16769-86d2-54da-ba6e-82bff18ec3e3; recorded commit
6ad1678c-f986-5e7c-bf6d-6cffdda72745, t 536873925. The parent matched exactly, so unrelated writes
were not attributed to this commit. The user-visible value took 1 ms; the complete request reported
56.0 ms, 898 calls, 4.8 MB allocation. That allocation is the request's observation, not an isolated
measurement of writer-thread serialization.

For each of six indexes, mark the parent's addresses, then mark only unseen addresses in the new record.
Attach storage and seed the stored fused root through the dependency's own methods. Stat the resulting
physical Konserve keys. Root addresses are not separate files when fused; count their bytes in the commit
record once. The schema-meta key is unchanged. There is no blob for this small result.

| Producer | New addresses | Files written | File bytes | Share |
|---|---:|---:|---:|---:|
| AEVT | 10 | 9 | 66,179 | 48.3% |
| Commit record, including six fused roots | — | 1 | 32,001 | 23.4% |
| AVET | 7 | 6 | 19,204 | 14.0% |
| EAVT | 5 | 4 | 19,177 | 14.0% |
| Temporal EAVT / AEVT / AVET | 0 / 0 / 0 | 0 | 0 | 0% |
| Commit graph record | — | 1 | 250 | 0.18% |
| Mutable branch head | — | 1 | 163 | 0.12% |
| Total | 22 | 22 | 136,974 | 100% |

The branch-head file is overwritten; the other 21 files are immutable additions. Its current value was
read after subsequent probes, but remains the same 163-byte compact-pointer shape. File bytes are logical
file lengths, not APFS allocated blocks or device-level write traffic. No whole-directory mtime window was used.
The corrected attribution walk took 114.73 ms with seon.profile/measure; it is observation cost, not commit cost.

Datahike's datoms-diff reports zero removed and 20 added datoms, each under a distinct attribute. Their
complete native Fressian delta encodes to 922 bytes. The changed information costs O(delta bytes); this
copy-on-write index implementation costs the changed paths and buffered edits, approximately
O(distinct touched paths × node payload), plus its commit record. Three current indexes and retained history
are required capabilities, not removable overhead. Even the deliberately generous baseline of three full
922-byte copies is only 2,766 bytes: measured file bytes are **49.5×** that, or **148.6×** one logical delta.
These ratios identify amplification; they are not a claim that a correct B-tree with filesystem metadata can
write exactly 2,766 bytes. Relative to a 10× three-copy allowance, the measured cost is still 4.95× larger.

The initial attribution probe appended .ksv twice and therefore reported zero files. It was corrected against
konserve.impl.defaults/key->store-key, which already includes the suffix, and rerun on the same immutable commit.
One delta probe omitted the required components argument; the corrected four-argument form is saved.

## Ranked owners and experiments

### 1. AEVT node rewrites

The producer is PSS Branch.store through datahike.index.persistent-set and datahike.writing/db->stored.
An eval scatters one datom over many attributes; persistent nodes copy their other contents too.
The smallest existing levers are branching-factor and diff-buf-size, supplied by
seon.cluster.store/datahike-configuration. They are creation settings, adopted from stored configuration on
reopen. Changing the source constant does not retune default's existing nodes.

Replay twelve consecutive real main commits ending at the measured commit, starting from 745,061 AEVT
datoms. Rebuild once per variant, apply each exact removed/added delta, flush through the installed PSS
owner, and encode pending nodes with the installed Fressian/LZ4 codecs. Compare the entire final AEVT value
outside the timed region: all variants equal the actual final snapshot. Measurements cover mutation,
flush, and encoding, excluding rebuild, final equality, filesystem I/O, metadata, and the other five indexes.

| Branching factor / buffer | Base nodes | Nodes encoded over 12 commits | Encoded value bytes | Stage ms | Thread allocation |
|---|---:|---:|---:|---:|---:|
| 128 / 32 | 7,843 | 115 | 625,762 | 18.77 | 31.38 MB |
| 64 / 32 | 15,854 | 149 | 595,806 | 15.24 | 28.64 MB |
| 32 / 32 | 32,395 | 165 | 364,219 | 10.74 | 23.86 MB |
| 128 / 64, second run | 7,843 | 69 | 445,800 | 9.77 | 24.86 MB |
| 128 / 128, second run | 7,843 | 43 | 359,188 | 8.52 | 21.71 MB |

The second run's 128/32 control took 13.33 ms and 31.37 MB; bytes differ slightly because new node UUIDs
change compression. Thus 128/128 reduces this stage's bytes about 43%, node count about 63%, and same-run
wall time about 36%. It is the next tuning candidate ahead of 32/32: smaller nodes quadruple the base node
population and increase files per commit, making 4 KiB block rounding and read cost material.

This is **not a commit-ready default change**. The history is short, the rebuilt trees start with fresh
buffers, and a larger buffer can carry more old edits on every later write. The prior store-writes evidence
already measured larger buffers and stale-slot eviction losing on other workloads. A one-commit fresh-tree
probe was especially misleading: all three sizes wrote only one node. Its initial timings also included
whole-index equality, so those timings are explicitly discarded as mutation-cost evidence. The twelve-commit
probe moves equality outside the measurement. The full probe took 1.391 s (three rebuilds and full comparisons);
the second took 767 ms. The earlier fresh-tree probe took 4.624 s. These are research scans proportional to
745k datoms, not ordinary commit timings. No production patch is justified by them alone.

Next proof: same aged replay across all six indexes, actual durable files and fsync, parent and candidate
in one run, then cold read/query costs and longer buffer aging. Keep histories and branch retention intact.

### 2. Fused root record

datahike.writing/commit! already writes a compact head. Commit e65599de and reader commit 3224583b
are in the installed fork. The task each-commit-serializes-its-root-record-twice describes a retired path;
do not implement it again. Branch-head-pointer cost is now 163 bytes, not another 32 KB record.

Remaining duplication: the three unchanged temporal roots are still encoded in every fused record.
On the exact measured record, compressed value encoding is 31,888 bytes (32,001 with file framing).
Encoding the record without its six roots costs 4,235 bytes; the three changed roots separately cost
5,640 + 8,877 + 3,989 bytes. The counterfactual total is 22,741 bytes, 9,147 fewer encoded bytes,
with three additional objects. The unchanged temporal roots independently encode to 5,574, 7,107,
and 703 bytes; independent sizes are not additive with the original record's compression.

The smallest existing option is unfused roots for a fresh store. It has not been adopted: separate roots
add file/block/I/O cost, and changing an existing fused store requires making previously inlined addresses
durable before readers rely on them. This codec-only counterfactual is not a tested format migration.
No new root-reference format, branch-specific cache, or speculative fork patch was introduced.

### 3. EAVT and AVET, then encoding allocation

Each contributes about 19.2 KB; the same node-size/buffer tradeoff applies. Temporal *node writes* are zero
for this append-only recording, so disabling history would not remove these writes and would lose a capability.
Main and kept branches pay the same writer. Coalescing model-call transactions is a separate turn-owner
change, not a storage fix in this lane.

The replay's 128/32 encoding stage allocates 31.38 MB for 625,762 encoded bytes, about 50×, consistent with
the existing each-commit-serializes-hundreds-of-times-the-bytes-it-writes task. This is mutation plus encoding
allocation, not a new attribution to Fressian alone. Konserve's serializer still constructs its handler lookup
per encode; LZ4's write path still constructs frame output buffers. A handler/buffer reuse patch needs its own
measured durable probe and round-trip evidence. The earlier reverted Fressian optimization is not repeated.

## Collection: retention, elapsed time, and retained roots

Live dials: collect? true, retention? false. The installed schedule is daily 03:00 UTC. At the observation
(2026-10-15 about 23:56 UTC), its latest receipt 93a881a626c2 started at 03:00:00.007 and completed at
03:06:57.604: **417.597 s**, 88,334 objects swept, 427,450,873 bytes reclaimed, verification sweep zero,
complete true. Reported objects-after was 352,670. The new status inventory was 512,868 files: about
160,198 files of subsequent net growth, not evidence that a scheduled run was missed. The next nominal
03:00 run was still in the future. Exact receipt and schedule queries are saved.

Read-only epoch marking through datahike.gc/reachable-in-branch, sharing visited commits and seen nodes
across all 24 captured branches, observed **360,422 distinct index addresses and 3,522 visited commit/head
keys**. It took **44,118.31 ms**, with JVM-wide allocation about **51,311 MB** and CPU 120,698 ms.
The 33,232-byte calling-thread count misses the async worker work; it is not the mark's allocation.
JVM-wide figures can include concurrent work. This is further evidence for
seon.maintenance/a-collection-dry-run-allocates-in-proportion-to-what-it-marks and
seon.maintenance/collection-marks-slowly-and-records-nothing, not a new duplicate task.

The checkpoint branch checkpoint-default-9c08fba0b116f62c8ee59e32 added **108,553 index addresses**
after earlier branches in sorted traversal had already been marked. This is an order-dependent marginal
count, not exclusive ownership. It establishes a large retained contributor outside the earlier main-only
227k-node census. Fused roots count as addresses without separate files; schema, commit, graph, and blob
objects also count as files. Therefore 512,868 minus 360,422 is **not** a garbage count. A candidate inventory
must mark all roots and referenced blobs and enumerate actual logical keys; that slower sweep/dry-run was
not run here. Nothing was deleted.

Konserve's installed sweep! enumerates k/keys once and derives observed keys and candidates from that
enumeration; registry/collect-and-inventory! consumes the returned report. The old
collection-lists-the-store-keys-twice task does not describe that path now. A maintenance verification pass
is a separate pass, not evidence of a duplicate inventory listing inside one sweep.

Plain collection deliberately retains all ancestry of every live branch. The owner disabled retention
pruning so growth can be measured; enabling it is not this lane's fix. The retention NPE is real but is not
the reason the currently selected plain collector retains ancestry.

## Retained-before NPE and subtree counts

The following bounded live reproduction uses the legacy :db branch and releases its materialized value:

```clojure
(let [conn (seon.cluster.boot/connection "default")
      legacy (datahike.api/branch-as-db conn :db)]
  (try
    {:declared? (contains? (:schema legacy) :seon.branch/name)
     :read (seon.db/datoms legacy :avet :seon.branch/name :db)
     :fork (try (seon.cluster.registry/fork-of legacy :db)
                (catch Throwable e {:class (class e) :message (.getMessage e)}))}
    (finally (datahike.api/release-materialized-db legacy))))
```

Observed: declared? false; read returns seon.db/invalid-read true, with the message that :seon.branch/name
is not indexed; fork throws NullPointerException, “Cannot invoke Object.getClass() because x is null”.
fork-of sequences that refusal map and passes its entries to max-key :tx. Entries have no :tx, producing
the NPE. retained-before calls branch-merge-base and fork-of before the try/finally that releases database,
so that failure also bypasses its materialized-db release. This latter release consequence is source-derived,
not separately measured as a resource leak. The existing task is
seon.cluster.registry/retained-before-fails-on-a-branch-whose-schema-lacks-the-fork-name.
The fix must settle the schema/branch-domain contract at the registry owner; filtering away :db or merely
catching the NPE would not settle it. No registry source was changed in this store-only assignment.

The measured commit's root subtree counts are -1 for EAVT, AEVT, and all temporal roots; AVET is 308,513.
PSS Branch.add propagates unknown child counts to the parent when it cannot derive the child-count delta.
Branch.count(storage) *can* recompute and cache a count; subtreeCount() only returns the cached value.
Thus the old task's “nothing recomputes it” is too broad: the planner's precomputed-count path does not
perform that traversal. An unconditional +/-1 patch would need to account for leaf processors that expand
or compact, which the dependency explicitly supports. No count or encoding change was made without proof.

## Verification and handoff

Only this report and its probe evidence are changed; net source/test lines: 0/0. No gitlink, dependency,
schema, configuration, or runtime behavior changes. No claim of a production performance improvement.
All replay final-index comparisons passed. Live full lint: clj-kondo --lint src script test --cache false
--parallel, 6,735 ms, **0 errors**, 1,791 warnings. The commit names
seon.cluster.store-test/configuration-is-the-one-shape as the narrow check; its result belongs to the
lane commit gate, not the replay measurements. The existing log-bound red
seon.cluster.store-test/datahike-logs-through-seon-bounded-and-expected-refusals-below-error is filed as
seon.cluster.store/datahike-error-log-lines-exceed-their-bound; the namespace-wide suite was not run.

Foreign boundary: merge-conflict and web-chains own modified source/schema/render files in the shared
checkout; the Konserve checkout differs from the loaded pin. None was edited, resumed, or messaged.
Candidate verification uses HEAD plus only this lane's paths. There is no restart request. The remaining
work is an aged full-commit tuning proof and the registry-domain fix, not permission to discard kept sessions.
