---
type: reference
status: measured; read-only research lane, no src/test/resources/reference-code edits
created: 2026-09-23
tags: [agent-platform, memory, datahike, caches, sci, jfr, measured]
---

# Datahike memory retention: what keeps default's heap growing (2026-09-23)

Lane `datahike-memory-research` (Opus 5.5). This lane read source only and changed none of it.
Every measurement is a read-only probe on default's JVM. The probes used MCP `eval_clj`, JVM
mode, private session `lane-dh-mem`, and the throwaway namespace `lane-dh-mem.walk`.

**Answer.** Datahike retains the memory, and Seon's caches keep it pinned. Datahike's unit of
memory is the **per-connection node cache**. Every branch connection, one per test member and
per isolated agent, builds its own konserve store and its own `CachedStorage`. That cache is a
strong LRU of up to 1000 index nodes, and at Seon's 4096-way branching those nodes hold
2,048–4,096 Datoms each (about 2.5 M Datoms per full cache). The connection release is correct.
The cache is freed only when **no database value from that connection is reachable**. Three
structures keep such values reachable after release:

- Seon's refusal-recording cache, which keys on the connection object and grows linearly;
- Seon's memoized SCI base contexts, capped at 4;
- a fork regression in Datahike's query-result cache key: the mechanism is proven, but this
  window did not sample it.

21 leaked or live storages × ~2.4 M Datoms ≈ **50 M Datoms**, the first OOM's count exactly.

## JFR verdict (orchestrator dump `tmp/orchestrator/heap-2026-09-23/leak-1550.jfr`)

The dump holds 373 `jdk.OldObjectSample` events over 1,124 s; 155 of them carry a reference
chain to a GC root and 218 do not. Analysed with
`jfr print --json --events jdk.OldObjectSample --stack-depth 200` (1.2 s) and a grouping script.
Noise hops (`Object[]`, persistent-map nodes, `AtomicReference`) are dropped.

**By object type.** 345 samples are `byte[]` (79.6 MB sampled), 19 `Object[]`, 6 Datom.
Allocation stacks show that **all 324 sampled `byte[]`, 10 of the `Object[]` and the Datoms were
allocated during a konserve Fressian node read.** The chain is
`FressianSerializer._deserialize → persistent_sorted_set.fressian read handler →
datahike.index.persistent_set datom handler → String.<init>` on `async-mixed-*` threads. The
`byte[]` are the value strings of Datoms in freshly deserialized index leaves. The `Object[]` are
the leaves' key arrays (`APersistentVector.toArray`, `Leaf.<init>`). The Datoms, the `byte[]` and
the `Object[]` are **one population: deserialized index nodes.**

**By reference chain (the 155 rooted samples).** Every chain runs
`byte[] ← String ← Datom ← Leaf [← Branch$NodeState ← Branch] ← LRUCache ← Atom ←
CachedStorage ← PersistentSortedSet ← DB ← …`, and it continues differently per holder:

| Samples | Sampled MB | Continuation above `DB` | Owner |
|---|---|---|---|
| 55 (+5 via the environment directly) | 11.4 (+1.3) | `… ← Atom ← seon.env.Environment ← Atom ← datahike.connector.Connection ← clojure.core.cache.LRUCache ← Atom ← Var` | **`seon.sci.eval/refusal-recording-cache`**, `src/seon/sci/eval.clj:2569-2598`. It is the only Var-held Seon LRU whose key holds a Connection. The live probe counted 53 released-connection keys. |
| 27 | 7.1 | `… ← Atom ← sci.impl.opts.Ctx ← clojure.lang.Delay ← LRUCache ← Atom ← Var` | **`seon.sci.eval/base-context-cache`**, `eval.clj:2339-2341`, value built at `eval.clj:2442-2444` |
| 37 | 4.5 | `… ← Atom ← datahike.connector.Connection` (thread stack or Var) | Live registered connections: expected |
| 7 | 2.3 | `… ← seon.test$run$run_batch` | A running test batch: live, expected |
| 0 | 0 | through `datahike.query/query-result-cache` | Candidate 3 was not sampled in this window |

**Answer to the orchestrator's question.** The Datoms, the `byte[]` and the `Object[]` are held
by released test-member and isolated-agent branch connections' node caches (`CachedStorage`,
`reference-code/datahike/src/datahike/index/persistent_set.cljc:460-468`). Those caches are kept
alive after `release` by two Seon caches in `src/seon/sci/eval.clj`:

- first, `refusal-recording-cache`, whose key is the Connection object (`eval.clj:2585-2590`).
  The Connection's metadata carries Seon's projection-state, and so its whole world
  (`src/seon/db.clj:244-256`).
- second, `base-context-cache`, whose cached SCI context snapshot holds `:seon.db/db`.

It is not materialized databases or listener sets.

**Smallest fix, in Seon, for the first.** Key `refusal-recording-cache` on
`[program (select-keys (:cache-context db) [:datahike.cache/connection-id :datahike.cache/generation])]`,
or on the commit-fault identity, never on the Connection object. In the same slice,
`store/release-branch!` (`src/seon/cluster/store.clj:573-577`) should
`alter-meta! dissoc :seon.sci.eval/projection-state`. **For the second:** drop `:seon.db/db` from
the value `derive-base-ctx` caches; `copy-base-ctx` already assocs the caller's database.

**Recording limit.** 218 of 373 samples have no root chain. For full chains, re-dump the same
recording with `path-to-gc-roots=true`; do not restart it:
`jcmd 63253 JFR.dump name=leak path-to-gc-roots=true filename=…`. The stacks end in async
konserve threads, so the allocating Seon caller is not in the stack. The reference chain is the
evidence.

## Evidence at a glance

| JVM | when | Datom | Leaf | `CachedStorage` | `Connection` objects | registered connections | `DB` |
|---|---|---|---|---|---|---|---|
| 90963 (first OOM, `histo.txt`) | 09-22 23:29 | 50.5 M | — | — | — | — | — |
| 55322 (`jcmd GC.class_histogram`, live) | 09-23 08:03 | 9.85 M | 6,822 | **21** | **19** | 3 | 137 |
| 60088, 2 min after start | 08:10 | 3.74 M | 1,957 | 5 | 4 | 3–5 | 37 |
| 63253, 2 min after start | 08:13 | 8.26 M | 4,269 | **13** | 13 | 5 | 109 |

Growth on 63253 across other lanes' ordinary test requests (`jcmd GC.class_histogram`, live; each 0.33–0.39 s):

| time | `byte[]` | Datom | Leaf | `CachedStorage` | `Connection` | `DB` | heap used |
|---|---|---|---|---|---|---|---|
| 08:14:47 | 691 MB | 9.07 M | 5,140 | 10 | 10 | 94 | 2.00 GB |
| 08:18:48 | 1,255 MB | 14.96 M | 8,691 | 19 | 16 | 99 | 3.30 GB |
| 08:22:48 | 1,295 MB | 15.12 M | 9,100 | 18 | 14 | 110 | 3.49 GB |
| 08:26:49 | 1,443 MB | 15.86 M | 10,117 | 23 | 39 | 119 | 3.94 GB |

**Rate: about +1 `CachedStorage` a minute, +0.57 M Datoms a minute, +63 MB of `byte[]` a
minute, and +160 MB of live heap a minute.** Only 5–6 connections were registered throughout.
The OOMs are therefore minutes to hours away, depending on test traffic.

The direct probe (`sample2`, 5 ms) at 08:30:29:

```
{:base-context-cache {:storages 4 :retired 4 :retired-nodes 1654}
 :query-cache-keys  {:storages 1 :retired 0}
 :refusal-cache     {:entries 54 :released-connection-keys 53}
 :connections 6}
```

At 08:14 the refusal cache held 4 released-connection keys. At 08:30 it held 53, about one per
test member, and it is bounded only at 256. That is the linear retainer. The base-context cache
is saturated at 4 retired branches, about 4 M Datoms.

Measured on the live cluster connection (63253):

```
{:config {:index-config {:branching-factor 4096 :diff-buf-size 256}
          :store-cache-size 1000 :search-cache-size 10000 :crypto-hash? false :keep-history? true}
 :leaf-len {:min 2048 :max 4096 :avg 2544} :ref-type "SOFT"}
```

The cluster-default storage was at its 1000-node cap, about 2.5 M Datoms. On 55322 the `:db`
connection held 431 nodes and 1,048,419 Datoms in its cache.

Timing correlates with the fork. Fork commit `684d3290`, "Share committed query results by
store and commit across branches", reached Seon at 2026-09-22 19:45 (`2f0c63d9a`). The first
OOM histogram was taken at 23:29 the same night.

## Ranked candidate retainers

### 1. Seon: the refusal-recording cache keys on the Connection object (confirmed; the linear grower, bounded only at 256)

**The structure.** `seon.sci.eval/refusal-recording-cache` is `eval.clj:2569-2571`, threshold
256 (`::program-identity-cache-size`). Its key is `[program target]`, and `target` is
`(:seon.db/connection (::custody ctx))` (`eval.clj:2585-2590`), a Datahike Connection object.

**What it holds.** On 63253 the cache had 4 entries, all keyed on released connections, at
08:14. By 08:30 it had **54 entries, 53 of them released connections**, which grows linearly
with test members. The 39 live `Connection` objects in the 08:26 histogram (against 6
registered) are these keys. Datahike's
`delete-connection!` resets the connection atom to `:released` (`connections.cljc:124-127`) but
leaves its **metadata**. Seon's `db/carry-connection-projection-state!` (`src/seon/db.clj:244-256`)
put the projection-state atom in that metadata. The live walk path to an unregistered storage:
`LRUCache → key vector → Connection → wrapped Atom → meta {:seon.sci.eval/projection-state …}
→ Atom → seon.env.Environment → :seon.agent/context-state Atom → … → datahike.db.DB →
PersistentSortedSet → CachedStorage`. Of the environment's keys, only
`:seon.agent/context-state` reached the retired storage.

**What should release it.** Nothing does before 256 newer keys arrive. That is up to 256
released connections, each carrying its world. **This is the structure that grows with test
traffic.** The base-context cache (2) is capped at 4, and the fork key (3) pinned no retired storage in this window. By growth rate
it ranks first.

**Attribution limit.** 23 storages were live at 08:26. 6 were registered, and 4 retired ones
are pinned directly by the base-context cache. The other ~13 are reachable from somewhere this
lane's walks could not finish or see: walk limits, virtual-thread stacks, the
`seon.flow$graph_executor` threads of two class generations (`reify__94529` and
`reify__423073` both run, so a reload left the old graph's threads alive), and one test body
past its bound (`test.clj:181` watcher). The JFR dump with `path-to-gc-roots=true` names them.

**Smallest fix, in Seon.** Key on data, the connection's `[:datahike.cache/connection-id
:datahike.cache/generation]` from `:cache-context`. Also have `store/release-branch!`
(`src/seon/cluster/store.clj:573-577`) clear the metadata key Seon itself wrote, so no retained
Connection carries a world.

### 2. Seon: the memoized SCI base contexts hold the retired branch's database (confirmed)

**The structure.** `seon.sci.eval/base-context-cache` is `src/seon/sci/eval.clj:2339-2341`, a
core.cache LRU with threshold 4 (`eval.clj:2326`). `derive-base-ctx` (`eval.clj:2423-2446`)
caches a context whose `::kernel/program-snapshot` atom holds **`:seon.db/db database`**
(`eval.clj:2442-2444`), along with `::acquisition` and `:seon.flow/commit-fault!`. The database
is whichever branch first derived that program identity: a test member's or an isolated agent's.

**What it holds.** 63253, 08:14: two of the four cached bases held retired branches
`:agent-43c0ee7732ab` (530 cached nodes) and `:agent-02edb289f4f0` (522 nodes), about 2.6 M
Datoms together. At 08:16 one retired branch held 242 nodes. The earlier whole-Var sweep found
eight `CachedStorage`s reachable from this Var, three of them unregistered (701, 346 and 10
nodes).

**What should release it.** Nothing does. The key is the program identity (`base-ctx-key`,
`eval.clj:2450-2465`), not the branch, so retiring the branch cannot evict the entry. The
value's database is never read as the value. `copy-base-ctx` (`eval.clj:2467-2474`) overwrites
`:seon.db/db` in every copy.

**Smallest fix, in Seon.** The cached base must not carry a database value. In the value stored
by `base-ctx`, keep the identity `acquired-database?` needs (the commit id and program basis)
and let `copy-base-ctx` supply the database, as it already does. Probe it with
`(lane-dh-mem.walk/sample2)` → `:base-context-cache {:retired 0}` after a test run.

### 3. Fork: the query-result cache key holds the caller's args array, database included (confirmed)

**The structure.** `datahike.query/query-result-cache` is `reference-code/datahike/src/datahike/query.cljc:2503-2506`.
It is bounded by `*query-cache-size*` 64 buckets and `*query-cache-weight-limit*` 1,000,000
shallow weight (`query.cljc:2457-2476`). Its cache key is
`[query non-db-args offset limit order-by *disable-planner*]` (`query.cljc:4691-4697`).
`non-db-args` comes from `query-cache-arguments` (`query.cljc:3166-3180`). For the ordinary
single source at position 0 it returns **`(rest args)`**, which is an `ArraySeq` or
`PersistentVector$ChunkedSeq` over the caller's argument array. **Element 0 of that array is the
database value.** The upstream and merge-base code is `(vec (rest args))`
(`git show 85c40aee:src/datahike/query.cljc`, line 4013), which copies. Fork commit `0070d507`
(2026-07-16, "Cache queries by every database source") introduced the uncopied seq.

**What it holds.** The live walk path from a key to a database:
`PersistentVector → Object[] → PersistentVector$ChunkedSeq → Object[] → datahike.db.DB`.
On 60088, 114 of 155 entries had this path; every hit was in `:cache-key`, never in `:result`,
`:dependency-plan` or `:source-contexts`. A `DB` reaches its `:store` → `CachedStorage` → up to
1000 nodes, plus the PSS roots and their soft children.

**What should release it.** Release removes the connection's scope from every bucket and
evicts only the buckets that no scope still admitted owns (`close-query-cache-generation!`,
`query.cljc:2594ff`, reached from `connector.cljc:489-497`). Since `684d3290` a bucket is keyed
`[store-id commit-id]` and shared by every branch at that commit. A test member branched off
commit C shares C's bucket with the request branch and the cluster, so the bucket survives the
member's release. The member's entries survive with it, and so does the member's whole node
cache, until 64 newer commits push the bucket out.

**Evidence against.** On 63253 at 08:13 and 08:16, the key-pinned storages were still
registered (2 and 1), with 0 retired storages pinned. The mechanism is proven. Its share of the
old JVM's 21 storages is not measured.

**Smallest fix, in the fork.** Change `query.cljc:3171` from `(rest args)` to `(vec (rest args))`,
which restores upstream. Regression: run a query on a branch connection, release the
connection, then assert that no cache key reaches that `DB`. Checking `(hidden-args (second k))`
is enough, see the probe below.

### 4. Datahike design: one node cache per connection, sized in nodes, never shared across branches (confirmed; the multiplier)

**The structure.** `-connect-impl*` (`connector.cljc:288-440`) runs `ks/connect-store`, then
`ds/add-cache-and-handlers` (`store.cljc:22-29` → `di/add-konserve-handlers`). The result is a
fresh `CachedStorage` whose LRU threshold is `:store-cache-size` (`persistent_set.cljc:460-468`,
default 1000, `config.cljc:24,124`). Each connection therefore deserializes its own copy of
every node it touches: new Datoms and new value strings, for byte-identical immutable nodes.
Branch connections of one physical store share only write hooks (`connections.cljc`,
`reserve-connection-opening!`). The size is counted in nodes. At branching 4096
(`src/seon/cluster/store.clj:158,189`) one cache is about 2.5 M Datoms, a few hundred MB with
the source strings. That is the 2 KB `byte[]` population.

**Seon's side.** `acquire-context!` with `:seon.agent/isolate? true` (`src/seon/cluster/agent.clj:783-930`)
opens one connection per test member (`src/seon/test.clj:1467-1470`) and one per test request
(`test.clj:1762-1764`). Each member starts cold and reloads nodes from disk. Pairing is correct:
`release-context!` (`agent.clj:749-781`) releases, retires and removes on every path, including
acquisition failure (`agent.clj:926-935`). A body that outlives its bound keeps its branch until
its thread exits (`test.clj:177-209`, by design). The roster matched the connections: 8 roster
branches, 6 connections, 3 agent branches, all open.

**Smallest fix, in the fork.** Share one `CachedStorage` per `physical-store-key`, as
`write-hooks` are shared already. Nodes are immutable per address. Online GC, the only address
reuser, is off and refuses multi-branch stores (`online_gc.cljc:150-175`). That deletes N−1
copies of the cache, and test members start warm. This change is larger than candidates 1–3 and
needs its own design note. Candidates 1–3 are what turn this multiplier into a leak.

### 5. PSS soft children inflate "live" counts (confirmed mechanism; not an OOM cause)

**The structure.** persistent-sorted-set 0.4.137 `Settings` defaults to `RefType.SOFT`. The
bytecode reads `getstatic RefType.SOFT` at `Settings.java` line 108, and `persistent_sorted_set.clj:150-160`
maps `nil` to that default. Loaded `Branch` children are held through `SoftReference`
(`Branch$NodeState.children`). A soft referent survives full GCs for
`SoftRefLRUPolicyMSPerMB` (default 1000 ms) × free MB after its last access, which is about
1.5 hours at 6 GB free. So `GC.class_histogram` counts nodes that the JVM would clear before an
OOM. **Against it as a cause:** soft referents are cleared before `OutOfMemoryError`. The strong
paths above are the OOM. **Probe:** compare `jcmd <pid> GC.class_histogram` with the node counts
the strong walk reaches (`lane-dh-mem.walk/reach`, which skips `Reference`).

### 6. Freed-address tracking grows forever on multi-branch stores (confirmed mechanism, small)

`CachedStorage.markFreed` appends `[address Date]` to `freed-addresses` and to `freed-set` on
every freed node (`persistent_set.cljc:445-450`). Only online GC drains them
(`online_gc.cljc:40-70`), and online GC is disabled by default and skips multi-branch stores
(`online_gc.cljc:150-175`). Measured: `cluster-default` held 40 entries after minutes, and
`writer-cost-2-probe` held 12. Growth is linear in writes over the connection's lifetime,
roughly 100 bytes per entry. **Fix, in the fork:** do not record freed addresses when online GC
is disabled. They are unusable without it; offline GC works by reachability (`gc.cljc:175`).

### 7. Seon projection memo (bounded, measured small)

`seon.db/projection-cache` is 24 entries (`src/seon/db.clj:1257-1270`). Its content keys are
~40 k declaration Datoms each (`declaration-content-key`, `db.clj:1300-1330`). The strong walk
reached 124,638 Datoms, 26 M string chars and **0** `DB` values in 2.9 s. It stays bounded.

### Ruled out (with the evidence)

| Structure | Location | Measured |
|---|---|---|
| Search cache | `db/search.cljc:17-26` `memoize-for` | Reads `(:cache-size (:config db))`. Config spells it `:search-cache-size` (`config.cljc:23`), so the cache is always off. |
| Committed-report sources | `committed_report.cljc:7-11` | 0 sources; structurally bounded at 4096. |
| Single-flight coordinator | `query/single_flight.cljc:17` | 0 flights. |
| gc-guard | `gc_guard.cljc:52,56,62` | In-flight 0; reachability gates 3. |
| Schema caches | `schema_cache.cljc:8-12` | 3 entries; keys are data. |
| Query result payloads | `query.cljc` result-cache values | 892 k weight, 652 k distinct result chars, no `DB` in results, plans or contexts. |
| Konserve locks | `konserve/core.cljc:126-135` | Refcounted; released keys dissoc. |
| Konserve read cache | `konserve/cache.cljc:23` | Not used by Datahike since fork `0e8601d7`. |
| Konserve 1 MB buffer | `filestore.clj:477,591,898` | Allocated per read or write and dropped. It cannot be the 2 KB-average `byte[]` population. |
| Connection registry | `connections.cljc:3` | Entries leave at final release (`connector.cljc:549`); roster equals connections. |
| `commit-as-db` / `branch-as-db` | `versioning.cljc:473-515` | Reuse the connection's store (`extract-store`), so no new cache. |

### Fork commits read for retention effects (merge-base `85c40aee`, 118 fork commits)

These commits change what lives how long:

| Commit | What it changed |
|---|---|
| `0070d507` | The candidate 1 regression. |
| `684d3290` | Shared buckets across branches, which makes candidate 1 outlive release. |
| `0cf39e57` | Lazy inheritance: promoted entries copy the old entry, key included. |
| `f3776b72` | Shared earlier as-of results, the same key path. |
| `092f5b05` / `0b652215` | Cache identity per connection generation; the release fence is correct. |
| `5e82166f` / `f8d0b34b` / `d9765276` | Committed-report source, bounded. |
| `f8192962` | Single-flight, drained. |
| `04213e17` | Secondary-index lifetime, closed at release (`connector.cljc:513`). |
| `0e8601d7` | Removed the unused Konserve wrapper LRU, a deletion. |
| `d6a99f53` | Unified branching and connection lifecycle. |
| `cc2b2bc7` | Adopts retained history at connect. |

No other fork commit adds a process-lifetime structure that holds values.

## Where else to look, beyond Datahike

Each row gives the one probe that confirms or rules out the suspect.

| Suspect | Probe | Status |
|---|---|---|
| prepl `*1/*2/*3/*e` per session | `jcmd <pid> Thread.print` for prepl session threads, then `reach` from the session result. Owner removed the retention in `5aa3989d0`. | The owner ruled it (`343127069`) and it landed. |
| SCI contexts (live and forked) | `(lane-dh-mem.walk/reach (:seon.sci.eval/ctx inst) 4000000)` → `:dbs`, `:storages`. Measured on 60088: 8 `DB`s, 2 storages, 923 leaves. | Live contexts are expected. The retained bases are candidate 2. |
| Agent context-state | `(:seon.agent/context-state (seon.env/of ctx))`: every entry's `:seon.db/db` storage registered? | Clean on 63253 (5 entries, all registered). |
| clj-kondo analysis during publication (the `byte[]` spike) | At the next publication, `jcmd <pid> GC.class_histogram` before and after, then `jcmd <pid> JFR.dump name=leak path-to-gc-roots=true filename=…`. OldObjectSample gives the `byte[]` allocation stack and root path. | Open. Not attributable from the current heap; the spike state was lost to the restarts. |
| core.async / flow buffers | `bin/seon status` → `:seon.oversight/buffers` counts. Every buffer is capacity 1. | Ruled out: capacities 1, counts 0. |
| Malli compiled closures (4.6 M in the first OOM) | `jcmd … GC.class_histogram | grep malli.core` trend across test requests. | Fixed by `c9870081f`. 63253 shows 255 k closures, flat. Recheck in the trend. |
| http-kit / Datastar SSE | `reach` from `seon.render.web` generator Vars; the Var sweep found no `DB` there. | Ruled out by the sweep. |
| Lane probe Vars | The whole-Var sweep found `tmp.writer-cost-2/pconn` holding a `writer-cost-2-probe` branch connection with 6 `DB`s on 55322. That branch is still in the roster (`:writer-cost-2-probe`). | Out of scope (another lane's probe); reported. |

## Measurement method and the probes (reusable)

`/private/tmp/claude-501/-Users-sean-src-seon/c00779a6-e734-4978-8631-d8663d9ca14f/scratchpad/walk.clj`
defines `lane-dh-mem.walk/reach`, `path-to`, `sample` and `sample2` in a throwaway namespace.
Load it with `(load-string (slurp "<path>"))`; it redefines nothing of default's.

`reach` is a strong-reference walk that stops at `Reference`, `Var`, `Class` and `Thread`, so
it cannot see thread stacks or thread-locals.

`sample2` costs about 2 ms. It reports the retired storages pinned by the base-context cache and
by query-cache keys, and the released-connection keys in the refusal cache. It is the
regression probe for candidates 1–3.

## JFR

**Recording:** name `leak`, on default pid **63253**, started 2026-09-23 08:11. It uses
`settings=/private/tmp/claude-501/-Users-sean-src-seon/c00779a6-e734-4978-8631-d8663d9ca14f/scratchpad/leak.jfc`
(the JDK `profile.jfc` with `jdk.OldObjectSample#cutoff=infinity`), with `maxage=12h` and
`maxsize=2g`.

The recordings on 55322 and 60088 died with those JVMs. The first `leak.jfr` is empty
(0 bytes). Dump at the next growth:

```
jcmd 63253 JFR.dump name=leak path-to-gc-roots=true filename=/Users/sean/src/seon/tmp/orchestrator/heap-2026-09-23/leak-63253.jfr
jfr print --events jdk.OldObjectSample /Users/sean/src/seon/tmp/orchestrator/heap-2026-09-23/leak-63253.jfr | less
```

`path-to-gc-roots=true` makes the dump walk the heap for the sampled objects' root paths, a
stop-the-world pause that grows with the live heap. Take it deliberately.

## Timings (probes over one second, each justified)

| Probe | ms | Proportional to | Why not sub-second |
|---|---|---|---|
| `reach` from `seon.db/projection-cache` | 2,933 | 1.23 M reachable objects, reflective field walk | A one-off diagnostic walk; the cache itself is not slow. |
| `reach` over 5 instance keys | 20,252 | ~13 M objects over overlapping roots | **Defect in the probe** (separate seen sets re-walked the shared system). Replaced by the shared-seen sweep. |
| `reach` from the SCI ctx | 3,122 | 2.86 M objects | Diagnostic walk. |
| Whole-Var sweep, first | 18,862 | 8.1 M objects, every Var root once | Diagnostic whole-heap walk. **Over 10 s**: done once, never in the system path; `sample2` (2 ms) replaces it. |
| Whole-Var storage sweep | 10,540 | 15 M objects (limit hit) | Same; done once. |
| Query-cache part walk | 8,151 | 3 M limit hit per part | Diagnostic; replaced by `path-to` (72 ms). |
| Env/meta path search | 10,911 | 1.5 M limit × 14 roots | Diagnostic; done once. |
| `jcmd GC.class_histogram` | 230–360 | Live heap, full GC | Under 1 s. |
| `jcmd Thread.print` on 55322 | 5,580 | Attach refused (JVM replaced) | The JVM was being replaced mid-call. |

The walks over 10 s are the lane's own diagnostic cost: object-graph walks over the live heap.
The system never runs them, so no system issue is filed. Their job is now done by the 2 ms
`sample2` probe.
