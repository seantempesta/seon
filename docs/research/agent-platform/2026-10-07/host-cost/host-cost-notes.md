---
type: notes
status: active
created: 2026-10-07
tags: [notes, host, reset, datahike, acquisition]
---

# host-cost notes (lane host-cost, 2026-10-07)

Tasks: `seon.cluster.host/hosting-an-agent-costs-seconds-and-a-hundred-megabytes` (persisted in
`resources/seon/tasks/seon.cluster.host.edn`) and `seon.cluster.boot/reset-takes-over-six-seconds` (runtime, default).
Evidence before this lane: `../reset-leak/reset-leak-notes.md`.

All numbers come from a disposable operator root (`bin/seon --root scratchpad/host-cost/root start probe`, HEAD
4c9ccb3f7, pid 95384), driven through its prepl. Allocation is
`ThreadMXBean/getTotalThreadAllocatedBytes` over all threads; heap is used heap after three `System/gc`; armed calls
come from `seon.profile/begin` and `seon.profile/explain`. Probe forms: `scratchpad/host-cost/*.clj`.

## 1. What hosting an agent was made of

Path: `seon.mcp/execution` → `my.agent/create-branch!` → `seon.cluster.host/host!` (`acquire-context!` on the named
branch: `store/open-branch!`, `sci.eval/fork-cluster-ctx`, which calls `sci.eval/acquire!`, then `fork-for-turn`;
then `start!`: two `home-call` transactions and the armer graph) → the first `sci-run` (`arm!` builds the agent
graph, first turn) → later runs.

Steady state at HEAD, per agent (after the first few derivations of the boot's program):

| stage | ms | allocated |
|---|---|---|
| create-branch | 20 | 2 MB |
| host! | 560-940 | 1.23-1.35 GB |
| first run | 105-185 | 200 MB |
| plain run | 80-120 | 90-97 MB |

Profile of `host!`: `seon.sci.eval/acquire!` 491 ms, inside it 19 `seon.db/q` calls, 471 ms. Stack sampling of the
threads that touch konserve during `host!`: 225 of 246 samples sat in
`seon.sci.eval/load-core-namespaces!`'s or-join query, under `datahike.index.persistent_set.CachedStorage.restore`
→ `konserve.core/get` → LZ4 decompress → Fressian decode. JFR allocation samples: 3.8 of 4.3 GB on the reading
thread, 1.4 GB of it in `LZ4FrameInputStream.readHeader`.

Why the reads: a new branch connection builds its own persistent-sorted-set tree over the shared addresses. Its
first whole-program query walks about 3,400 index nodes, which the tree restores through the store's one shared
node cache (`datahike.index.interface/node-cache-key`, an LRU shared by every connection to the physical store).
Seon never set `:store-cache-size`, so the cache held Datahike's default 1,000 nodes, was always full, and each new
branch restored every node from disk again (storage stat `:reads` 106 after `host!`, 3,317-3,384 after its first
run). Each restored node is a fresh object the branch's tree then keeps (soft references), which is the retained
heap: `jmap -histo:live` across 4 hosts grew 217 MB, 1.15 M `Datom`s in 15,135 `Leaf`s (~54 MB per host).

`load-core-namespaces!` itself was redundant on almost every acquisition: it requires the program's namespaces
before `base-ctx`, whose memoized base is keyed by a program identity covering every attribute that query reads
(checked: `:seon.fn.file/relative-root :seon.fn/file :seon.ns/name :seon.schema.admission/source :seon.ns/source
:seon.fn/ns :seon.fn/source` are all acquisition attributes), and the JVM keeps what it loaded. On a cached base
nothing is loaded; on a fresh branch the query was still answered from scratch.

Not the cost: SCI forks (`sci/fork` shares the environment map), `open-branch!` (2 ms), the base-context cache
(3 entries before and after every steady-state host), the agent graph (`arm-graph!` 7-9 ms), projection reuse.

The early outliers after boot (first run 1,460 ms / 3.2 GB; a host 2,119 ms / 3.1 GB) are base-context derivations
for new program identities right after boot; see section 4.

## 2. Fixes

1. `seon.cluster.store/datahike-configuration` sets `:store-cache-size 8192` (Datahike excludes it from the
   stored-config check and the connection acquisition key, so reopening an existing store accepts it). The working
   set of a host plus first run on this store was 3,486-3,805 nodes. EQUIVALENT in results; changes only where a
   node comes from.
2. `seon.sci.eval/derive-base-ctx` calls `load-core-namespaces!` first, and `acquire!` no longer calls it before
   `base-ctx`. The namespaces load exactly when a base is derived (cache miss or a value with no identity).
   EQUIVALENT for `acquire!` under the precondition above (the base key covers the query's attributes; loaded JVM
   namespaces stay loaded). `base-ctx`'s direct callers (tests only) now also load namespaces on a miss, which the
   test JVM has already done.

Measured on the probe root by installing each change in its JVM (cache resized through the shared atom, the two
functions re-evaluated), same probe (`hostonly.clj`: three new agents, host then first run):

| | host! | first run |
|---|---|---|
| HEAD | 590-940 ms, 1.23-1.35 GB | 105-185 ms, 196-244 MB |
| acquisition change only | 98-290 ms, 71-142 MB | 550-820 ms, 1.16-1.18 GB (the same reads move to the first form's `seon.fn/runtime-analysis-batch`) |
| node cache only | 124-242 ms, 139-326 MB | 135-150 ms, 154-166 MB |
| both | 66-80 ms, 61-73 MB | 125-165 ms, 153-157 MB |

Storage reads of a new branch connection (`reads.clj`): node cache 1,000: 106 after host, 3,317-3,384 after the
first run; 8,192 (warm): 0 and 0. Retained heap per idle host (4 hosts, `heap.clj` + histogram diff): HEAD ~54 MB;
both fixes 1-1.5 MB (`:per-host-mb 1`, histogram delta 6.1 MB for 4). Marginal heap of the full node cache itself:
0 MB measured (emptying it freed nothing: every node it holds is also held by a live tree).

So a new agent's first call (host + first run) is now ~200-240 ms, 215-230 MB allocated, against a plain run of
~100 ms and 95 MB: about 120 ms over a plain call (target 300 ms). The plain run's own ~95 MB per `(+ 1 2)` is out of
this slice (section 5).

Regression: `seon.cluster.store-test/a-branch-reads-the-index-nodes-its-store-already-holds` (names the task): a
file store with 30,000 entities (about 2,400 index nodes), two branches each scanned whole; the second restores no
node from storage. Red with `:store-cache-size 1000` (check run 483c9cb7efa1: 2,357 restores), green at 8,192. The
first regression I committed (`seon.cluster.host-test/a-second-hosted-branch-restores-no-index-node-from-storage`,
in d3653e987) was vacuous: it passed at 1,000 too (check run 47ee810a2be8), because a test world's hosting working
set stays under 1,000 nodes; it is deleted in the next slice.

### 2b. The same producer: a join on a freshly materialized commit (task `fb221de3aaf7`, from lane defn-cost)

`d/commit-as-db` (`datahike/versioning.cljc:473`, pinned d9121bbb) builds the commit's tree over the connection's
own store, so it shares the same node cache; nothing is detached. Its first whole-program join walked ~3,000 nodes
the 1,000-node cache had evicted. Measured on the probe (`mat.clj`, `seon.sci.eval/function-rows` of the loaded
commit, fresh materialization each run): cache 1,000: 393-681 ms, 978-995 MB, 2,903-2,995 storage restores;
cache 8,192 warm: 24 ms, 50 MB, 0 restores. No fork change is needed.

A whole base-context derivation (`derive.clj`, `derive-base-ctx` uncached, the loaded program): defn-cost measured
~933 ms / 2.35 GB on default; on the probe with the cache fix 150-207 ms / 289-380 MB; with defn-cost's
`eval-acquisition.patch` applied as well (rows of the loaded commit through `seon.cache` by store and commit, one
`pull-many` for the namespace rows) 106-153 ms / 196-247 MB. The patch holds and is applied in the second slice.
The remaining ~200 MB per derivation is the whole-program rebuild itself (task `4a738d4f7138`, incremental
acquisition): not done here.

Which step of the one loop it serves: eval (the agent's world acquisition and its first form). Nothing new is added:
the dependency's own shared cache is sized for the work, and a query leaves the per-acquisition path. Deletes the
per-acquisition namespace query.

## 3. Reset

`bin/seon reset` → `seon.cluster.boot/request!` `:reset`: `stop!`, `registry/retire-branch!` of the cluster
branch, then `start!` again in the same JVM. Profiled through the probe's prepl (`reset.clj`, 63 hosts alive, HEAD
4c9ccb3f7 plus the slice-1 changes installed in that JVM): total 8,397 ms, 5.98 GB allocated; stop 1,775 ms
(`host/release-children!` of 63 hosts 1,742), unlink 10 ms, start 6,610 ms. Layers (ms since start): store 109,
source/current 1,575, branch 2,579, config 2,880, SCI ctx 5,438, graph 6,607. Armed inclusive time:

| work | ms | what it is proportional to |
|---|---|---|
| `seon.cluster/arm-host-program!` → `instrument/apply!` | 2,230 | every contracted Var (2,584 `arm-var!`, 1,888 ms in `contract-definitions`) although the JVM already armed them all for this commit |
| `seon.fn.analyzer/ensure-dependency-analysis!` | 1,192 | clj-kondo over the checkout's non-program roots, every start |
| `arm!` → `acquire!` → `derive-base-ctx` | 1,145 | the whole program, on cold index nodes (section 2b) |
| `registry/retire-unclaimed!` | 707 | the 63 orphaned host branches (`retire-branch!` x64) |
| `changed-source-paths` | 266 | every source file's digest |

Why everything re-armed: `seon.instrument/current-wrapper?` compares the arming policy, whose
`:seon.call-preparation/snapshot` carries `:seon.call-preparation/checked-through-t`, the basis of the database it
was read at (`seon.call-preparation/snapshot`: "names one database value, not the program"). No wrapper reads it
(`call-preparation/arm-plan` and `preparing` read the rows, validators and refusals), but any later database value
made every wrapper stale. Probe: repeating `arm-host-program!` on the same program 2,095 ms; with the comparison
ignoring that member 79 ms (`arm.clj`, `cw.clj`).

Fix (BEHAVIOR CHANGE only in what is re-armed; EQUIVALENT wrappers): `current-wrapper?` compares the policy without
the snapshot's `checked-through-t`. Regression
`seon.instrument-test/a-later-database-value-of-the-same-program-keeps-every-wrapper` (names the reset task): a
Var armed at one value keeps its identical wrapper when re-armed at a later value of the same program.

Second fix: `seon.fn.analyzer/ensure-dependency-analysis!` linted the non-program roots (`bench`, `script`,
`resources`, top-level files) with clj-kondo on every start, a reset included, to keep script dependencies current
(task `seon.fn.analyzer/local-root-dependency-analysis-goes-stale`). It now writes a marker in the dependency-set
cache directory named by an id of those Clojure files' paths, sizes and modification times (`roots-stamp`) and
lints only when no marker matches. Probe: 871 ms, then 3 ms on the unchanged files. A changed script changes the
id, so it is linted as before. No regression test: whether clj-kondo ran is not observable without redefining it;
the check JVM's own boot runs it.

Stopping many hosts costs ~28 ms each (`release-children!`); not changed.

## 4. Findings

- Plain hosted run of `(+ 1 2)` allocates 90-97 MB and takes 80-120 ms (45 queries, `seon.fn/runtime-analysis-batch`
  20-30 ms, settlement transaction ~30 ms). Over the 10 MB budget.
- Boot-adjacent base-context derivations: the first host/run after boot allocated 3.1-3.2 GB (one derivation per new
  program identity). Existing task `seon.sci.eval` acquisition (health top: `seon.sci.eval/acquire-program!`, task
  4a738d4f7138).

## Design check

The design held, and was smaller than the task's hypothesis: no SCI context copy and no per-branch derivation existed;
the 100 MB was the dependency's node cache sized for a 1,000-node store being asked for 3,500 nodes per branch, plus
one redundant whole-program query per acquisition. Awkward: the cache size is a constant beside the other store
settings rather than a configured dial, because the node cache is fixed by whichever entrant first opens the store
(boot, maintenance and operator paths open it before or without the cluster configuration). A dial would need every
opener to read the configuration first; the constant names its reason in place.
