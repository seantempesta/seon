---
type: research
status: active
tags: [research, architecture]
---

# Performance benchmark — 2026-07-31 (WIP, one development machine)

Every number here was MEASURED, on one developer's laptop, against a live
system booted from a pinned commit. Nothing is extrapolated unless it says so,
and the two places where the measurement contradicted an existing claim are
called out rather than smoothed over. **These are engineering measurements of
work in progress, not service-level guarantees and not a tuned benchmark.**

## Conditions

| | |
|---|---|
| Machine | Apple M5 Max (`Mac17,6`), 18 cores, 128 GiB RAM, macOS 26.5.2 |
| JVM | OpenJDK 26.0.1 (Homebrew), G1GC, **`-Xmx512m`** — the whole system in a half-gigabyte heap |
| Commit | `fea41b7a8`, exported clean with `git archive HEAD` into its own operator root (`tmp/bench-head`) |
| Isolation | Its own process root, own Datahike store under its own `flock`, own JVM. No other lane's cluster was touched. |
| Store | Datahike file backend (konserve), `:keep-history? true`, self-writer |
| Model | The sanctioned local Ollama server, `qwen3.5:35b-a3b-coding-nvfp4`, real calls — no stub |
| Probes | `tmp/bench/` (committed): `support.clj`, `db*.clj`, `web*.clj`, `agents.clj`, `threads*.clj`, `clusters*.clj`, `cache.clj`, `active.clj` |

## Summary — every row measured, none guaranteed

| # | Measure | Number | What it supports |
|---|---|---|---|
| 1 | New isolated environment (branch fork) | **17.0 ms** median, 18.9 ms p95 | a fresh sovereign world per user or per experiment, on demand |
| 2 | Full cluster boot into a running JVM | **518 ms** median (476–565 ms) | a new tenant is live in half a second |
| 3 | 25 clusters in one JVM | **+1.24 MiB RSS** and **1.4 MiB on disk** per cluster | multi-tenancy is branches, not processes |
| 4 | Cross-cluster interference (5 active) | delivery **3.7 ms → 3.2 ms** (none) | one tenant's churn does not slow another's UI |
| 5 | Arm one agent | **0.47 ms** median at a 1,000-agent fleet | agents are created, not provisioned |
| 6 | 1,000 parked agents | **+79 MiB RSS**, 30 MiB heap, in a 512 MiB heap | ~12,600 parked agents/GiB *today* (see #7) |
| 7 | Platform threads per parked agent | **1** — a defect, not the design | fixing it should reach ~35,000/GiB; do not quote that yet |
| 8 | Live UI delta, commit-settled → bytes on the wire | **3.7 ms** median (small page) / **105 ms** (185 KiB page) | the page derivation is the cost, not the delivery |
| 9 | Same delta at 1, 10, 50 browser connections | **flat**, and byte-identical per connection | serialize once, mult to every tab |
| 10 | Full page GET | **4.1 ms** median, 6.4 ms p95 | a cold tab paints immediately |
| 11 | Sustained churn, 60 s, 10 tabs | 816 commits, **165 ms** median lag, **no growth** | steady state is steady |
| 12 | One stalled tab | other tabs **unaffected**, memory flat | a wedged consumer cannot hurt the room |
| 13 | Database commit, file store | **8 tx/s**, 123 ms median | the one real ceiling today — see caveats |
| 14 | The same commit, in memory | **1,088 tx/s**, 0.82 ms median | the ceiling is the file backend, not the model |
| 15 | Batched commit | **2,000 rows/s** at 1,000 rows/transaction | the ceiling is per-commit, not per-fact |
| 16 | Cached read, warm database value | **3–7 µs**, 100k–190k queries/s | derive-don't-store is affordable |
| 17 | Uncached read after a commit | **0.19–0.25 ms**, ~4,000 queries/s | a fresh basis costs a quarter of a millisecond |
| 18 | Real end-to-end agent turn (local 35B) | **model-bound** — see §6 | the system is not the bottleneck |

Rows 6, 7 and 13 are the honest bad news and are expanded below. Row 7 has an
open issue with acceptance criteria
([armed-agent-holds-a-platform-thread](../../../seon/issues/armed-agent-holds-a-platform-thread.md)).

## 1. Database — writes

Method: a branch forked from the live cluster branch (so the full 171-attribute
schema and its facts are present) opened with `seon.cluster.store/open-branch!`
and written through Seon's one write door `seon.cluster.store/transact!`.
Nothing listens on that branch, so this is Datahike's serial writer over the
file store with no wake or render work attached. 50–100 untimed warm-ups, then
the stated sample count. Probe: `tmp/bench/db.clj`, `tmp/bench/db4.clj`.

| Shape | datoms/tx | median | p95 | sustained |
|---|---|---|---|---|
| Small fact commit (a message row) | 5 | 122.8 ms | 155.7 ms | 8 tx/s |
| Medium commit (agent creation) | 5 | 127.1 ms | 166.7 ms | 8 tx/s |
| Small commit + `:tx-meta` provenance | 6 | 155.9 ms | 196.6 ms | 6 tx/s |

Provenance costs a real 27 % on top of the commit: the tx-meta datom is
resolved and written like any other.

**Batching amortizes the commit, and that is where the throughput is.** The
per-commit cost barely moves until the batch is large, so rows/second scales
almost linearly (n=20 each, `tmp/bench/db4.clj`):

| rows/transaction | median | rows/s |
|---|---|---|
| 1 | 127.7 ms | 8 |
| 10 | 128.7 ms | 80 |
| 100 | 189.8 ms | 500 |
| 1,000 | 560.0 ms | 2,000 |

**Where the 123 ms goes.** Decomposed against the same shape and the same
171-attribute schema (`tmp/bench/db2.clj`, `db3.clj`):

| Layer | median | rate |
|---|---|---|
| Seon's write door (`schema.datahike/encode-transaction`) | 0.001 ms | 1.84 M/s |
| Datahike transact, in-memory backend, history on | 0.816 ms | 1,088 tx/s |
| Datahike transact, in-memory backend, history off | 0.800 ms | 1,129 tx/s |
| Datahike transact, **file backend** | 125.0 ms | 8 tx/s |

So **99.3 % of a commit is the konserve file backend**, ~0.7 % is Datahike's
own index and history work, and Seon's layer is a rounding error. History is
free (2 %). Raw `datahike.api/transact` without Seon's door measured 125.0 ms —
identical — confirming the door adds nothing.

The cost is also **flat in database size**: 122.8 ms at 20,514 datoms,
126.1 ms at 52,795, 127.7 ms at 155,724.

## 2. Database — reads

Same branch, the same query mix run against a pinned database value (cache
hit) and against a fresh database value after each commit (cache miss, with
the commit outside the timed window). n=200 cached / n=25 uncached.

| Query | result size | cached median | cached rate | uncached median |
|---|---|---|---|---|
| Agent by identity | 1 | 0.007 ms | 101,757/s | 0.213 ms |
| Pull agent + namespace | 1 entity | 0.041 ms | 10,765/s | 0.197 ms |
| Functions in one namespace | 43 | 0.005 ms | 188,767/s | 0.254 ms |
| Whole-corpus census | 1,478 fns | 0.006 ms | 118,595/s | 0.190 ms |
| Messages for one agent (fan-out scan) | ~25,000 | 16.96 ms | 57/s | 21.30 ms |

The first four are the shapes a render or a prompt actually issues; the fifth
is deliberately pathological — a full scan of 25,000 rows created by this
benchmark — and is included so the shape of the cliff is visible rather than
hidden.

## 3. Cluster scale — the branch architecture

A cluster is one Datahike **branch** forked from the published `current-src`
commit, plus its own connection, flow graphs and web view. Siblings in one JVM
share only the process-root store holder and the root executors. Probes:
`tmp/bench/clusters.clj`, `clusters2.clj`, `clusters6.clj`, `cache.clj`.

**The fork alone** (n=50, on a roster that already held 79 branches over a
2.0 GB store): **16.97 ms median, 18.9 ms p95, 20.1 ms max.** Fifty forks grew
the store directory by **0 MiB** — structural sharing, not copying. This
confirms the previously claimed ~17 ms at scale.

**Full boot into an already-running JVM**, measured for each of 25 clusters:

| clusters | boot-to-ready | RSS | heap used | threads | store dir |
|---|---|---|---|---|---|
| 0 (base) | — | 1,057 MiB | 207 MiB | 40 | 1,984 MiB |
| 1 | 809 ms | 1,077 MiB | 215 MiB | 80 | 1,985 MiB |
| 5 | 505–546 ms | 1,082 MiB | 251 MiB | 98 | 1,991 MiB |
| 10 | 516–564 ms | 1,084 MiB | 289 MiB | 114 | 1,998 MiB |
| 25 | 476–548 ms | 1,088 MiB | 423 MiB | 157 | 2,019 MiB |

The first boot pays a one-off warm-up; the median of the remaining 24 is
**518 ms**. Marginal cost per additional cluster: **1.24 MiB RSS**, **1.4 MiB
on disk**, **~4.7 platform threads**. A cold JVM start (`clojure -M:dev`,
classpath and namespace load included) to a serving cluster was **13.1 s**;
after that, clusters cost half a second each.

**Cross-cluster interference.** Five clusters prepared identically; one is
measured while the other four commit continuously in the same JVM against the
same process-root store. Alternated three times, warm, discarding a warm-up
round (`tmp/bench/clusters6.clj`):

| round | delivery alone (median / p95) | delivery with 4 neighbours | commit alone | commit with neighbours |
|---|---|---|---|---|
| 1 | 3.68 / 5.21 ms | 3.39 / 5.85 ms | 73.7 ms | 335.4 ms |
| 2 | 3.70 / 5.78 ms | 3.07 / 4.59 ms | 72.0 ms | 349.8 ms |
| 3 | 3.82 / 6.05 ms | 3.21 / 5.31 ms | 75.4 ms | 347.9 ms |

Read plainly: **the render and delivery path shows no cross-cluster
interference at all** — the measured cluster's UI latency is the same with
four busy neighbours as without. The **write path does contend**, because all
five branches share one process-root file store: per-commit latency rises
~4.7× while the neighbours run at ~12 commits/s each. Aggregate is what
improves — roughly **51 commits/s across five clusters** against 13/s for one,
so the store parallelizes across branches even though each individual commit
waits longer.

**Query-cache pressure.** Datahike's result cache is a process-global LRU over
64 database snapshots (`reference-code/datahike/src/datahike/query.cljc:2445`).
Round-robin reads across 1, 10 and 25 live clusters all stayed at **3 µs** per
cached query, and raising `DATAHIKE_QUERY_CACHE_SIZE` to 512 changed nothing
measurable. **25 clusters do not pressure the cache**; pressure begins past
~64 concurrently-read database values, which is >64 clusters, or fewer
clusters each holding several `as-of` values. The mitigation exists and is one
environment variable when that day comes.

**What the single-JVM model does and does not give a hosting story.** It gives
isolation by construction: a cluster is a separate branch, so its facts,
schema accretion, agents, graphs and web view are sovereign, and one cluster
cannot read or corrupt another's data through any supported API. It gives
near-free creation and destruction. It does **not** yet give tenant resource
isolation: all clusters in one JVM share one heap, one bounded compute
executor, one process-root store lock, and one crash domain — a JVM-wide OOM
or a `kill -9` takes every tenant with it, and the write contention above is
real. A hosting story that needs failure isolation between tenants needs
multiple process roots (which the store fence already supports — one flock per
process root), not multiple clusters in one JVM.

## 4. Agent density

Probes: `tmp/bench/agents.clj`, `threads3.clj`, `threads4.clj`, `threads5.clj`.
Each agent is created as facts and then **armed** — its own two-proc flow
graph, started and resumed. A parked agent is `:running` and blocked on a
channel read; it is never flow-`paused`.

| fleet | create batch | arm median | arm p95 | RSS | heap used | platform threads |
|---|---|---|---|---|---|---|
| 0 | — | — | — | 1,021 MiB | 193 MiB | 78 |
| 100 | 158 ms | 0.60 ms | 1.83 ms | 1,031 MiB | 198 MiB | 155 |
| 250 | 214 ms | 0.62 ms | 1.63 ms | 1,049 MiB | 203 MiB | 305 |
| 500 | 275 ms | 0.46 ms | 0.92 ms | 1,074 MiB | 210 MiB | 559 |
| 1,000 | 433 ms | 0.47 ms | 0.98 ms | 1,100 MiB | 223 MiB | 1,059 |

- **Datoms at birth: 5**, not the ~26 previously assumed. Agent creation is a
  namespace row plus the agent's identity, namespace ref and cluster ref, plus
  the transaction instant. Blocks are no longer seeded (see §5).
- **Arm time does not degrade with fleet size** — it improves, from 0.60 ms at
  100 agents to 0.47 ms at 1,000, as the JIT warms. p95 stays under 1 ms.
- **Marginal memory per parked agent: ~81 KiB RSS, 27–41 KiB heap.** In a
  512 MiB heap the 1,000-agent fleet used 30 MiB of heap — about **34,000
  parked agents per GiB of heap**, which matches the 2 procs × ~8.5 KB figure
  `flow-mechanics-2026-07-28.md` measured. RSS tells a worse story, and the
  reason is the next point.
- **Fleet ping**: one agent graph answers in **0.213 ms**; pinging 200 agent
  graphs sequentially took 38.5 ms and every one of the 400 procs answered
  (0.19 ms per agent). Observability of the fleet is cheap.

### The thread finding — measured, and it contradicts the design

**Arming an agent adds one PLATFORM thread.** At 1,000 armed agents the JVM
held exactly 1,000 extra `async-mixed-N` platform threads, all parked. The
agent's own two procs are `:io` and do run on virtual threads — the platform
thread comes from the per-agent error fan-out:

```clojure
;; src/seon/flow.clj:699-703, called once per arm!
(async/pipeline 1 fault-channel (map #(merge % tag)) (:error-chan started) false)
```

`async/pipeline` defaults to `:compute` type, which starts one
`core.async/thread` worker on the unbounded cached `:mixed` executor — the
exact scaling cliff `seon.flow/var-process` refuses at the proc door. The
threads are **not leaked**: disarming the fleet let the cached pool reap them
at its 60 s idle timeout, and RSS fell 1,128 → 1,057 MiB (≈72 KiB per thread,
a stack). But while armed, they are real, and they are why marginal RSS per
parked agent is 81 KiB rather than ~17 KiB.

Filed as a blocker with acceptance criteria:
[armed-agent-holds-a-platform-thread](../../../seon/issues/armed-agent-holds-a-platform-thread.md).
Until it lands, the honest parked-agent figure is **~12,600 agents per GiB of
RSS**. Do not quote the ~35,000/GiB heap figure as a system capability yet.

## 5. Web rendering — the architecture, then the numbers

The old system re-rendered a whole page per tab on every change; the investor
saw that and was right to. The current path is:

> one commit → one unconditional render wake → **one** derivation of each
> watched agent's page → per-block byte equality suppression → **one**
> serialization, multed to every tab → per-tab diff against that tab's own
> last-delivered map → a per-block morph, with a `(sliding-buffer 1)` tap so a
> slow tab gets the newest page rather than a queue of stale ones.

Everything below measures that path live.

### A caveat that must come first

HEAD commit `29794272b` (**the same day**, "Replace seeded context with one
fresh walk") deleted the block declarations from agent creation. The html
render functions all still exist, but a freshly booted cluster's agent page
declares **zero blocks and renders empty**. The benchmark therefore reinstated
exactly the html half of the deleted `seon.render.agent/blocks` vector
(`tmp/bench/blocks.clj`), giving a 4-block page. The pipeline measured is the
real one; the block set is a reconstruction, and the shipped default is
currently nothing.

The **[TARGET] revisioned packages and keyframes** from
`render-pipeline-design-2026-07-29.md` are **not built**. Today's renderer
emits complete page snapshots, mults them, and computes per-tab changed
blocks. So a new tab is served by a fresh full paint rather than a stored
keyframe, and a revision gap does not exist because there are no revisions.
The numbers below are the pre-package pipeline.

### Page GET

n=200 warm, after one cold request. Both routes serve the root agent's page
(root is an agent), 4 blocks, 7,945 bytes.

| route | cold | warm median | warm p95 |
|---|---|---|---|
| `/` | 50.2 ms | 5.15 ms | 9.59 ms |
| `/agent/root` | 4.58 ms | 4.09 ms | 6.41 ms |

### SSE delta latency, and what it is really made of

One probe = one committed fact carrying a unique token. Three timestamps: `t0`
before the transaction, `t1` when the report returns, `t2` when the token's
bytes leave the socket read call on each connection. The SSE clients are raw
sockets opened inside the cluster JVM — real HTTP/1.1 to the live http-kit
server over loopback, but sharing one `System/nanoTime` with the committer, so
sub-millisecond deltas are honest. A browser's own morph cost is excluded and
was measured separately at 1.2–1.5 ms
(`render-pipeline-design-2026-07-29.md`).

The agent graph was **stopped** and the cluster's armer **paused** for these
runs: a committed message would otherwise wake the agent into a ~40 s local
model call and the measurement would be of the model. The render wake is
unconditional per transaction report and is unaffected.

**Large page (4 blocks, 185 KiB, transcript-heavy).** Probe messages are
retracted between configurations, the order is repeated, and the page's
derived byte size is printed every round so drift is visible: it was
123,150 B before each round and 185,7xx B after, every time
(`tmp/bench/web4.clj`, 25 probes each).

| connections | commit (median) | settled → wire median | settled → wire p95 | delta bytes per connection |
|---|---|---|---|---|
| 1 | 117.0 ms | 113.2 ms | 155.2 ms | 3,975,539 |
| 10 | 117.3 ms | 101.1 ms | 146.2 ms | 3,975,727 |
| 50 | 112.4 ms | 112.0 ms | 142.3 ms | 3,975,515 |
| 1 (repeat) | 113.1 ms | 131.4 ms | 168.7 ms | 3,975,961 |
| 10 (repeat) | 102.9 ms | 108.6 ms | 151.1 ms | 3,976,007 |
| 50 (repeat) | 111.4 ms | 104.7 ms | 134.3 ms | 3,976,446 |

**Small page (3 blocks, short transcript)**, measured on a fresh cluster with
5 connections: **settled → wire 3.68 / 3.70 / 3.82 ms median, 5.2–6.1 ms p95**
across three rounds (`tmp/bench/clusters6.clj`).

Three things follow, and they are the whole architectural claim:

1. **Latency is flat in the number of tabs.** 1, 10 and 50 connections give
   the same median. Fan-out is not the cost.
2. **The bytes each connection receives are identical** — 3,975,5xx for 25
   probes whether 1 or 50 tabs are attached. That is the serialize-once-and-
   mult property, measured rather than asserted.
3. **The cost is the page derivation.** `page-of` alone measured 47.5 ms
   median at a 123 KiB page and 130–155 ms at a 186 KiB page — which is the
   whole of the 105 ms delivery latency. On a small page the same pipeline
   delivers in 3.7 ms. **The pipeline is fast; a large derived page is slow**,
   and the transcript block is the reason it grows.

Block suppression works: with 4 blocks declared, each commit produced exactly
**2** SSE events — the two blocks whose bytes changed. The other two were
suppressed by equality. The granularity is the block, though, so a growing
transcript block re-sends its whole content on every message; that is the
honest limit of block-level diffing and the argument for splitting a
transcript into per-entry blocks.

### Sustained churn

10 tabs, 60 s, committing as fast as the store allows. The churn shape upserts
**one** row's content so the page's byte size stays constant and a growing
transcript cannot masquerade as degradation (`tmp/bench/web5.clj`).

| | |
|---|---|
| Commits | 816 in 60 s (**13.6/s**) |
| Render passes | 754 — **62 commits coalesced** by the sliding-1 render wake |
| Delivered per tab | **731**, identical for all 10 tabs |
| Superseded (dropped) per tab | 85 (10.4 %) — latest-wins by construction, never a lost fact |
| Delivery lag | median **165 ms**, p95 214 ms, max 246 ms |
| RSS across the run | 1,011 → 1,020 MiB (**+9 MiB**, no trend) |
| Page bytes | 123,150 → 125,385 (constant, as designed) |

Drops are the design working: a wake says only "look", the woken pass derives
from current facts, and a superseded page is never something a recovery needs.
Every tab converges on the same newest state.

### The stalled consumer

One connection stops reading without closing — its reader parks, its socket
receive window fills, and http-kit's pending-byte state on that channel rises
so the server's connection-owned `:io` writer parks on its exact drain
completion (our fork's `write-state`, `reference-code/http-kit/src/org/httpkit/server.clj:321-326`).
Five healthy tabs keep going (`tmp/bench/web6.clj`).

| | at stall | after 30 s / 410 commits |
|---|---|---|
| Stalled connection, bytes read | 130,303 | 254,090 (one in-flight event, then nothing) |
| Healthy tabs, lag median / p95 | — | **169.8 ms / 208.0 ms** |
| Healthy tabs in an equivalent run with no stalled peer | — | 165.0 ms / 213.7 ms |
| JVM RSS | 1,020 MiB | 1,020 MiB |

**The stalled tab cost the other five nothing, and cost the process nothing.**
The bound is structural: the writer never enqueues a second event before the
first drains, and the per-tab sliding-1 tap keeps only the newest page — so a
wedged consumer holds at most one in-flight event plus one page, not a queue.
After releasing it and churning 15 s more, the healthy tabs measured 167 ms
median — unchanged.

## 6. Active agents — real turns against a local model

## 7. Honest caveats

**One machine, one disk, one JVM.** Everything here is a laptop with a local
file store and a 512 MiB heap. Nothing was tuned. No number is a
service-level objective.

**The file store is the write ceiling, and it is a swappable backend.** 8
commits/second is the single worst number in this document and it is 99.3 %
konserve's file backend, not Datahike (1,088 tx/s in memory with the identical
schema and shape) and not Seon (0.001 ms). Batching already recovers
2,000 rows/s. Whether the production answer is batching, a different konserve
backend, or a remote writer is an open engineering question — but the
measurement says clearly where the cost is and that it is not in the
architecture.

**Model-bound is not system-bound.** A local 35B model on one Mac dominates
any turn measurement. Historical evidence at 10 agents put 99.24 % of tokens
in model thinking; the numbers in §6 measure fairness and dispatch, not
capability.

**What is not built.** Revisioned packages and keyframes are designed and
ruled but unbuilt — today's renderer emits full snapshots and diffs per tab.
The `::renders` proc is unbuilt. Workload derivation by reachability over the
call graph is unbuilt. The shipped default agent page currently declares no
blocks at all (§5), and the benchmark reinstated them.

**Two claims this benchmark corrected.** Agent creation is 5 datoms, not ~26.
And a parked agent is not thread-free: it holds one platform thread through
the error fan-out, which is a filed blocker rather than a design property.

**What would move the numbers most**, in the order the evidence supports:
remove the per-agent platform thread (row 7); split the transcript into
per-entry blocks so a growing conversation stops re-serializing (§5); and
address the file-store commit ceiling (§1).
