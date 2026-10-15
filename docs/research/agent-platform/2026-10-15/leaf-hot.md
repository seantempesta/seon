---
title: "leaf-hot: how hot is Datahike's index-leaf decode across Seon's real work?"
type: research
status: measured (lane leaf-hot, Sonnet, 2026-10-15); no source edits
created: 2026-10-15
---

# leaf-hot: is the ~100 KB per leaf read worth chasing?

**Verdict: later, not now.** Leaf decode is real (16.6% of the allocation of everything default's own threads did in the window
on node reads; 28% if every datom-handler sample counts, commit records included) but it is spread over cache misses, not over any one frequent
operation, and allocation is not time. Two other costs are larger and already owned: the commit-record decode in
`seon.cluster.source/merge-base` (44% of non-probe allocation, fixed 80% by the konserve fork bump `647bd2a1`), and the
node cache missing 4.2% of its reads because its working set exceeds 512 MB. Chase lazy leaf decode only if, after the
bump, one frequent operation still spends over 20% of its **time** in it (none measured yet; see "Verdict").

## Method

- **Where.** Default (pid 75650, uptime ~22 min at measurement, so the node cache was still warming), JVM mode,
  read-only: no transaction, no branch created. The one write is the per-request recording that every `eval_clj` makes.
  Default runs the konserve of **before** the bump (`b4b896f`), so every number below is the old per-read cost.
- **Recording.** One JFR recording (`jcmd 75650 JFR.start settings=profile`, `jdk.ObjectAllocationSample#throttle=20000/s`),
  71 s, 46,446 allocation samples, stopped and dumped. Stack depth 64. Operations ran on named threads with
  `System/currentTimeMillis` windows, so each op's samples are those of its thread inside its window. Raw
  `ThreadMXBean` MB, ms and `CachedStorage` `:stats` reads were taken beside it. Samples are weighted bytes; shares
  below are of sampled bytes. Allocation only: the 10 ms `ExecutionSample` stream gave 1 to 17 samples per op, too
  few to rank time.
- **Classes** (by frames in the sample's stack; first match):
  - **datom decode**: `datahike.index.persistent_set$reify__*.read` (the `datahike.datom.Datom` read handler),
    `datahike.datom/datom-from-reader`, `datahike.datom/datom`, and the vector `FressianReader.readObject` builds
    under them;
  - **fressian container**: `incognito.fressian$reify.read` / `org.fressian.FressianReader` /
    `org.replikativ.persistent_sorted_set.fressian$read_handlers$reify.read` outside the datom handler (the
    `into`/`map` lazy seqs, `Long.valueOf` and transient vectors that assemble a node's keys);
  - **lz4 / konserve**: `net.jpountz.lz4.*`, `konserve.*`, `hasch.*`;
  - **Datahike other**: `datahike.*` and `org.replikativ.persistent_sorted_set.*` outside the above (seeks, `Seq`);
  - **Seon / other Clojure**: the rest.
- **Node read vs commit-record read.** A sample under `datahike.index.persistent_set.CachedStorage.restore` is an
  index-node read; one under `konserve.core/get` without it is another konserve get (commit records, commit graph).
- **Leaf vs branch could not be split from a stack**: both go through one datom handler and the persistent-sorted-set
  read handlers (two `reify` ids, `33776` and `33782`; which is which is not named in any frame). Branch reads are about
  1 per 256 leaves on a scan and about 1 per leaf on a descent; the table says "node" where it cannot tell, and the
  konserve-read-fix.md per-read split (leaf 132 KB, 100 KB of it datoms; branch 244 KB) is the only leaf/branch figure.

Probe artifacts: `tmp/leaf-hot/` (gitignored; the numbers are all here).

## Per operation

Thread MB, ms and node reads are one run each (first run, then a repeat); the shares are the JFR classes of that
op's thread in its window. "Node decode" = datom decode + fressian container on node reads.

| Operation | ms | thread MB | node reads | node decode (datom + container) | konserve + lz4 | datahike other | Seon / other |
|---|---:|---:|---:|---:|---:|---:|---:|
| `datahike.api/merge-base` chain vs main, 1st / 2nd | 333 / 110 | 442 / 442 | 0 node reads (about 370 commit-record reads) | 49.6% (commit-record fused roots, not a leaf) | 49.6% | 0.2% | 0% |
| chains page (`render.web/chains-page-result`), 1st / 2nd | 17 / 5 | 12.9 / 5.3 | 9 / 0 | 22.9% / 11.1% | 10.4% / 0% | 44.5% / 55.6% | 17% / 33% |
| `seon.context/current-state`, S5 task, 1st / 2nd | 502 / 221 | 763 / 309 | 0 / 0 (cache hits) | 29.0% / 1.0% (1st: commit records, not nodes) | 29.2% / 0% | 17.0% / 37.7% | 24.7% / 61.3% |
| `seon.program/changed-identities` (change discovery) | 12 | 16 | 0 | 0% | 0% | 88.9% | 11.1% |
| `seon.cluster.source/merge-seeds` (reach, `reverse-closure`) | 213 | 283 | 388 | 16.4% (datom 12.7 + container 3.6) + 0.8% branch | 3.6% | 22.9% | 43.6% + 12.5% jvm |
| `seon.cluster.source/merge-list` (reach-closure walk) | 24 / 17 | 18 / 18 | 0 | 0% | 0% | 2.6% | 97.4% |
| cold `:eavt` scan, 120,000 datoms | 59 (then 5) | 116 (then 26) | 273 (then 0) | 47.8% (datom 39.2 + 8.6) | 12.1% | 7.2% | 31.6% |
| cold `:aevt` scan, 120,000 datoms | 285 | 479 | 1,383 | 78.5% (65.4 + 12.9) | 13.1% | 2.1% | 3.3% |
| cold temporal `:eavt` scan, 120,000 datoms | 156 | 251 | 581 | 66.5% (56.4 + 9.2) + 0.9% branch | 11.3% | 12.0% | 8.0% |
| eval request `(+ 1 3)`, **turn thread** (steady) | 199 | 200 | n/a | 12.0% (datom 24 MB) | 2.6% | 53.4% | 28.8% |
| eval request `(+ 1 2)`, **turn thread** (cache churned by my scans) | 805 | 888 | n/a | 65.7% (493 datom) | 10.8% | 10.6% | 0% |
| same two requests, `db<->flow` thread (the reaction to the write) | | 590 / 1,592 | | commit-record decode (merge-base), 46% / 47% | 31% / 45% | | |

What the rows say:

- **Leaf decode is the cost of a scan, and only of a scan.** When a leaf is read and every datom is wanted
  (`:eavt` 120k: 207 KB/read; `:aevt`: 231 KB/read; temporal: 247 KB/read, values with long strings included), decode is
  48-79% of the op's allocation. Lazy decode saves nothing there: all the datoms are used.
- **Warm operations read no leaves.** `current-state` (2nd), `merge-list`, `changed-identities` and the chains page
  (2nd) show 0 node reads and 0-11% node decode. The node cache serves them.
- **The first `current-state` and both `merge-base` calls are commit-record reads**, which look like 30-50% "fressian"
  but are the fused index roots of ~370 commit records (the konserve-read-fix.md 1.17 MB per record), not leaves.
- **`merge-seeds` is the one frequent read-through that pays for leaves**: 388 node reads, 47 MB of node decode of
  290 MB (16.4%). It is `fn/reverse-closure` plus `:avet` lookups: point reads.

## The recording as a whole (the real mix)

The 71 s window held my probes and everything default was doing for other lanes (flow procs, other agents' evals and
test steps). Excluding my own four probe windows, **33.6 GB** was sampled; excluding every `Clojure Connection` thread
(my eval threads), **15.1 GB** across the system's own threads:

| source (non-probe threads, 15.1 GB) | share |
|---|---:|
| konserve gets that are **not** node reads (commit records, commit graph): konserve + lz4 17.7%, fressian container 17.1%, datom handler 11.4% | 46.2% |
| node reads (`CachedStorage.restore`): datom decode 10.1%, container 6.5%, konserve 5.3%, cache put 1.0%, lz4 0.5% | 23.3% |
| no storage read in the stack (compute: `seon.instrument/arm-var!`, `seon.schema/call-with-projection`, `seon.db/with-declarations`, `seon.cluster.store/processing-measured`) | 30.5% |

By thread:
- **`db<->flow` (6.6 GB, 44%)**: 81.5% commit-record reads. The nearest Seon frame is `seon.cluster.source/merge-base`
  (1,338 of 1,592 MB in one request, outermost frame `seon.cache/lookup`: a cache miss that computes the merge base),
  80% konserve + container. This is not leaf decode; it is what the konserve bump removes (merge-base 424 to 83 MB
  measured in konserve-read-fix.md).
- **`async-mixed` workers (7.3 GB, 48%)**: node reads 37% (datom decode 15.0%, container 10.9%, konserve 8.7%), plus
  19.3% datom handler under a `konserve.core/get` without `restore` in the first 64 frames (commit-as-db records or
  a `restore` frame deeper than 64; not separable here). These are agents' evals and test steps.
- **Agent `turn` threads (1.1 GB)**: node reads 45% (datom decode 24.7%).

Node cache (live): **15,500 nodes, 512,000,000 of 512,000,000 weighted bytes, hits 5,608,863, misses 245,159
(95.8%), evictions 100,508**; `CachedStorage` `:reads 57,487`, `:accessed 3,906,675` for the `:eavt` storage. The cache is
full and evicting. Default had been up ~22 minutes, so some misses are the first fill. My probes churned it too: the
`(+ 1 2)` request that followed my 120k scans allocated 888 MB on the turn thread, 65.7% node decode, against 200 MB
(12%) for the same request after the cache had settled. **That 4.4x is the hot case: a cache miss storm, not a faster
decode.**

## Ranked hot spots

1. **Commit-record decode in `merge-base` (`db<->flow`)**: 44% of the system's non-probe allocation, every
   request. Fixed 80% by `647bd2a1` (konserve) already; the remaining fused-root datom decode in a commit record is
   part of the same Branch-node codec. Gain after the bump: the 5.4 GB of commit-record reads drops to about 1 GB
   on this mix. **Not leaf decode.**
2. **Node-cache working set above the 512 MB budget** (95.8% hit, 100k evictions): every miss costs one read,
   ~130-250 KB of allocation, of which 60-70% is datom/container decode. Fewer node faults (`Branch.remove`
   multiplier, reach reads, per-request cold reads) beat faster decode by an order of magnitude; konserve-read-fix.md
   already ranks it first.
3. **Leaf datom decode on node reads**: 10.1% datom + 6.5% container = **16.6% of non-probe allocation, 90% of it in
   `async-mixed` and `turn` threads** (agents' real work), 24.7% of the turn threads. Ranges: 48-79% in a cold scan,
   16% in `merge-seeds`, 12% in a settled eval request, 0-1% in warm ops.
4. **Seon's own compute** (30.5%): `seon.instrument/arm-var!`, `seon.schema/call-with-projection`, `seon.db/with-declarations`
   dominate; not in scope here, but larger than leaf decode.

## Where decode exceeds 20% of an operation, and how often it runs

| operation | decode share | frequency | note |
|---|---:|---|---|
| any cold full or range scan (`:aevt`, `:temporal`, `:eavt`) | 48-79% | rare (probes, conversions, `collect`) | all datoms are used: lazy decode gains 0 |
| eval request after a cache miss storm | 66% | when another op evicted its nodes | the fix is the cache, not the decode |
| `merge-seeds` (via `current-state`, merge gate) | 16% (+ 3.6% container) | once per `current-state` and once per merge gate | point reads: lazy decode could save most of the 12.7% datom share, about 35 MB of 283 MB, or 12% of this op |
| eval request, settled cache | 12% | every request | about 24 MB of 200 MB |

## Expected gain from lazy decode (only the datoms a read needs) or smaller leaves

Estimate, from these numbers:
- Lazy decode gains only on point reads. The point-read share of node decode is unknown but bounded by the rows
  that are not full scans: `merge-seeds` (12.7%), the settled eval request (12%), `async-mixed` node reads (25.9% of
  7.3 GB). Taking all of the settled-request and `merge-seeds` datom share as saved: **12% of those ops' allocation, about
  35 MB per `current-state` and 24 MB per eval request**, and about **10% of the system's non-probe allocation** at the
  very most. Allocation is not time: konserve-read-fix.md measured merge-base at 424 to 83 MB with the same ~100 ms.
- Smaller leaves (a lower branching factor) shrink the per-read cost of a point read and raise the count of reads and
  the cache's node overhead; it changes the stored form (needs a measured win per the owner's 10-12 ruling) and cannot
  beat fewer reads.
- Versus the alternatives: the konserve bump removes about 5 GB of the 15 GB, fewer node faults removes whole
  reads, lazy decode removes at most a tenth of what is left.

## Verdict

- **Chase now:** nothing from this lane. The commit-record decode (`seon.cluster.source/merge-base` over
  `db<->flow`) is the largest single cost on default and is the konserve bump root already has.
- **Later:** lazy/partial datom decode in the persistent-sorted-set read handler (`datahike.index.persistent-set`
  `datom-read-handler`, `datahike.datom/datom-from-reader`). Re-measure after the bump with the same JFR recording;
  chase it if a frequent op (`current-state`, the merge gate, an eval request at steady state) shows node decode
  above 20% of its **CPU time** (an `ExecutionSample` window of a few seconds per op will show it; this recording
  had too few CPU samples to rank time).
- **Drop:** smaller leaves, and any decode speed-up aimed at scans (all datoms are used).
- **Open, measured here, not mine:** a settled `(+ 1 3)` eval request costs **199 ms and 252 MB** (the first one 805 ms and
  936 MB); the node cache is full at 95.8% hit. Both are findings for the owners of the eval request and the node
  cache budget.

## Verification limits

- One run per op, one JVM that had been up 22 minutes, a node cache that was warming and that my own scans evicted
  from. Allocation only; the CPU stream had 1-17 samples per op.
- Leaf and branch could not be told apart from a stack; "node" figures mix them (leaf-dominant on scans, about even on
  descents).
- Default's konserve is the pre-bump one: every per-read figure carries ~120-150 KB of buffers the bump removes.
- Stack depth 64 truncated some deep stacks: samples without `CachedStorage.restore` in view may still be node reads
  (the 11.4% "datom handler under a non-restore get").
- The merge gate, as `merge-seeds` + `merge-list` on the S5 chain with an empty proof set and `changed-identities`
  from the merge base, not a run of `done-refusals`; the "test step change discovery" was measured as
  `changed-identities` (the step's own change set) because `seon.test/branch-changes` needs a live execution.
  The S5 chain's changed set was 338 identities (the forward merge is not in yet), so these are a larger delta than
  the right base would give.
