# Write latency vs store size: the 100x floor does not exist

Date: 2026-09-17. Read-only research lane, branch `steward-platform`. Probes on
`default` (pid 95853) through `mcp__seon__eval_clj` jvm mode; scratch stores under
`tmp/write-floor-*` (deleted). Supersedes the floor reading in
[in-process-record-tx-cost-2026-09-17.md](in-process-record-tx-cost-2026-09-17.md).

**Falsified premise.** There is no store-size-dependent commit floor. Re-measured
tonight, an empty-delta commit on `default`'s 12 GB / 107 506-key store costs
**23-38 ms and writes 1-2 konserve keys** — the same as a clean store. The 3.9-8.9 s
numbers were real but were not a floor: they were single commits that flushed a
large accumulated set of dirty index nodes.

## Measurements

| probe | measured |
|---|---|
| empty delta on `default`, first sample tonight | **5 921 ms**, and it wrote **473 files / 39.0 MB** into `data/store` |
| the next empty delta, back to back on the same connection | **26 ms, 1 new key** |
| four more back to back | 25 / 35 / 24 / 23 ms; 1, 2, 1, 1 new keys |
| after a 2 s gap | 38 ms, 15 new keys |
| after a 15 s gap | 24 ms, 1 new key |
| clean file store, identical config, 800 207 datoms, 2 955 keys | **18.9 ms, 2 new keys, 0.13 MB** |
| clean file store curve, 4 008 / 20 012 / 100 032 / 400 107 / 800 207 datoms | 32.3 / 17.0 / 17.2 / 29.8 / 18.9 ms; **2 files every time** |
| `default` config | branching-factor **4096**, `diff-buf-size` 256, `fuse-index-roots? true`, keep-history, 6 indexes (eavt/aevt/avet + temporal), 814 453 datoms, 3 branches |
| directory-fsync cost, 50-blob commit shape, at 1 053 / 10 053 / 50 053 entries | 429 / 554 / 436 ms — **flat**, 8.6-11.1 ms per blob, no scaling with entry count |

The clean-store curve is flat in BOTH latency and files written, so datahike's commit
is O(delta), not O(store). The APFS probe (`tmp/write-floor-dirsync/DirSync.java`,
deleted) falsifies the directory hypothesis: konserve fsyncs the base directory once
per blob (`reference-code/konserve/src/konserve/filestore.clj:144-150`,
`sync-base` at `:65-75`) into a single flat directory of 107k files, but that fsync
does not get more expensive as the directory grows.

## Cause

What costs seconds is **the number of index nodes one commit flushes**, and that is
set by the writes that preceded it, not by the store. `commit!` writes every pending
kv then flips the branch head (`reference-code/datahike/src/datahike/writing.cljc:484-559`,
`get-and-clear-pending-kvs!` at `:493`), under the GC guard
(`reference-code/datahike/src/datahike/gc_guard.cljc:1-44`). At **branching-factor
4096** one changed datom rewrites a whole ~300 KB leaf, and `default` carries six
indexes, so a batch that touches many leaves turns into tens of MB: the 5 921 ms
sample wrote 473 keys / 39 MB, including six blobs of 1.84 MB. At the measured
8.6-11.1 ms per fsynced blob, 473 blobs is ~4.5 s — the whole of the observed latency.

So the unnecessary work is not in the commit path. It is (1) the 4096-wide leaf, which
makes every touched leaf a ~300 KB immutable rewrite, and (2) that nothing collects
those rewrites, so 8 hours of ordinary work grew the store from 107 MB to 12 GB.
Growth is the defect; latency is a symptom of the batch, not of the size.

## Reclamation is wired, but only weekly and only by a timer

`seon.operator/collect!` (`src/seon/operator.clj:897-926`) calls datahike's
`gc-storage` through `seon.cluster.registry/collect!` (`src/seon/cluster/registry.clj:519-546`,
with the branch-roster reachable-extension at `:473-484`). Its only trigger is the
schedule row `root/maintenance/compact-schedule`, cron `0 3 * * 0`
(`src/seon/schedule.clj:66-70`) — once a week, Sunday 03:00 UTC, keyed on nothing
the store observes. Every other maintenance row is likewise a cron expression
(`src/seon/schedule.clj:45-70`). `root/maintenance/footprint` measures the footprint
but does not gate collection.

**Reclaimable now, measured:** I ran `datahike.api/gc-storage` on `default` intending
a dry run and passed `{:dry-run? true}`, which datahike's gc does not accept — its
options are the `:datahike.gc/*` family (`reference-code/datahike/src/datahike/gc.cljc:152-164`),
so **a real collection ran** with a 24 h cutoff. It is safe (it deletes only
unreachable objects, under the guard) but it was outside my read-only mandate and is
recorded here as what happened. It took `data/store` from **107 072 keys / 12 043 MB
to 93 202 keys / 10 301 MB** and was still running when this page was written: at
least **1.7 GB / 13 900 keys reclaimable**, on a store whose live size eight hours ago
was 107 MB. A publication (`bin/seon init --dev default --changed src/seon/id.clj`)
launched alongside it returned after **319 s** and **failed**, so the (a) publication
and test-run byte deltas are NOT measured: the store moved by -28 521 keys / -3 496 MB
over that window, which is the collection, not the publication. Both need a re-measure
on a quiet system.

**Separate defect found, unrelated to storage.** That publication
(`bin/seon init --dev default --changed src/seon/id.clj`) died in the source build with
a bare `java.lang.IndexOutOfBoundsException` (no message) at
`seon.fn/exact-source` (`src/seon/fn.clj:163`, called from `var-row` `src/seon/fn.clj:473`,
`analysis-rows-by-file` `src/seon/fn.clj:940`, `build-artifact` `src/seon/fn.clj:1535`,
reached through `seon.cluster/incremental-source-refresh!` `src/seon/cluster.clj:1885`).
Caveat, unverified: another lane holds uncommitted edits in `src/seon/fn.clj` right now
(`git status`), so this may be a half-edited working tree rather than a defect at HEAD.
Verify against HEAD before filing.

## Three options for the owner (not implemented)

Each keys reclamation on an observable signal, never a timer.

1. **(Recommended) Collect on the footprint signal that already exists.** Keep the
   weekly cron as a backstop, and add a reclamation trigger to
   `root/maintenance/footprint`: when the measured store footprint exceeds its own
   post-reset size by an order of magnitude, or the konserve key count crosses a
   declared ceiling, `collect!` fires. *Guarantee:* the tree never grows past a
   declared multiple of its live size; the signal is the footprint the portfolio
   already samples. *Cost:* one condition on an existing row plus a declared bound;
   collection holds the reachability permit for minutes at 100k keys, so it must be
   bounded and loud. *We give up:* nothing measured; the 03:00 Sunday cron stays as
   the floor.
2. **Declare a per-commit bound and let it fire the collection.** Give `commit!`'s
   seam a declared latency/bytes bound, per §2.3; a commit that exceeds it is a bug
   report naming the batch, and crossing it repeatedly requests reclamation.
   *Guarantee:* the class "an execution surface with no declared bound" dies — a 100x
   degradation can never again be invisible, which is exactly the check nobody had
   here. *Cost:* a new bound on the hottest path in the system, and a bound that is
   too tight turns ordinary large batches into noise. *We give up:* silence on big
   legitimate transactions until the bound is tuned against real batches.
3. **Change the write shape: narrower leaves, or a different backend for the dev
   root.** Drop `branching-factor` from 4096 so a touched leaf is tens of KB rather
   than ~300 KB, and/or point the development root at `reference-code/konserve-lmdb`
   + `reference-code/datahike-lmdb`, both vendored, where a page update does not
   create a new file per node. *Guarantee:* attacks the byte production itself, so
   there is less to collect. *Cost:* a config change that only takes effect on a
   refork, an unmeasured read-performance trade (narrower leaves mean deeper trees),
   and an LMDB move is a second storage path to keep honest. *We give up:* the
   file store's inspectability, and §2.5's one-mechanism rule if LMDB becomes a
   second supported backend rather than a replacement.

## Follow-ups

- Re-measure (a): the store delta of one hook publication and one recorded in-process
  test run, on a quiet system with no collection in flight.
- The `:dry-run?` trap is worth an issue: `gc-storage` silently ignores an unknown
  option and collects. A caller asking for a dry run gets a real sweep.
