---
type: design
status: proposed; measured; no source edits
lane: cache-central-design-opus
date: 2026-09-26
base: e405f51be (pid 59671), then a34078273 (pid 70849), file:line at de67fdd1a
tags: [caches, memory, datahike, render, measured]
---

# One central cache: inventory, design, migration (2026-09-26)

The owner asked: "Can we do just one central cache and it'll support all the different caches that are currently
separate?"

**The answer is yes, with one exception: Datahike's own caches.** Today about 20 separate holders keep derived
values:

- 13 process-level `clojure.core.cache` instances;
- per-projection memos;
- a per-branch render atom;
- two `memoize`s;
- five Datahike caches.

They are bounded by entry counts written as code constants. There is no byte cap and no per-kind statistics. Several of
them use explicit invalidation.

The design replaces every Seon-owned holder with one Caffeine `AsyncCache` behind a `seon.cache` owner of about 70
lines, sized in bytes by one configuration dial. Keys name content (a digest, a commit id or an immutable value), so
nothing is ever invalidated. Values are only evicted.

Datahike keeps its own caches. They take their dials from configuration and are reported in the same status. One fork
change stops each branch connection from decoding its own copy of shared index nodes.

The recommendation is **Caffeine, stated plainly**. The reasons are in section 4. `CLAUDE.md` currently says "Every
cache is `clojure.core.cache` (or the dependency's own)", so it must be amended in the same commit that introduces the
owner.

Units: heap is in bytes (MB = 10^6 bytes). Anything an agent reads is given in estimated tokens (characters ÷ 4).

## 0. Broken things found (logged as task EDN)

- **Projection memos build more than once when misses are concurrent.** Task `06528ec90fc7`, in
  `resources/seon/tasks/seon.schema.edn`.
  - Affected sites: `seon.schema/build-projection` (`schema.clj:2344`), `declaration-projection` (`:1326`) and
    `seon.schema.edn/packaged-population` (`edn.clj:385`).
  - Each of them passes an undelayed derivation to `clojure.core.cache.wrapped/lookup-or-miss`.
  - That function makes one delay per *caller*, not one per key (core.cache 1.1.234, `wrapped.clj`).
  - Measured: 8 concurrent misses on one key ran **8 derivations**. Caffeine ran 1.
  - Each projection build costs about 390 ms and 570 MB allocated (perf-profile, 2026-09-26).
- **Every hit swaps the atom.** This is part of the same task.
  - A `lookup-or-miss` hit costs 408–858 ns on one thread and 761–1,808 ns per operation with 8 threads.
  - A `cache/lookup` hit costs 14–23 ns.
  - Sites that avoid the swap by calling `lookup` and then `miss`, such as `call_preparation.clj:472`, never record
    recency. Their LRU therefore evicts in insertion order.
- **`seon.render.value/throwable-safe` (`render/value.clj:424`) is an unbounded `memoize` keyed by `Class`.** Task
  `4e5a30acb8a3`, in `resources/seon/tasks/seon.render.value.edn`.
  - It never evicts, so it pins every Throwable class it has seen, together with that class's loader.
  - The idle heap had 11,867 class loaders. Their cause is not attributed yet.
- **Default was replaced twice during this lane** (pid 59671 → 68654/69010 → 70849, root's HEAD advances). I caused
  neither replacement: my probes were refused with `repl-unavailable` / `session-lost` while the replacement ran.
  - Consequence for the numbers: every live size below comes from a JVM that had been up **2–5 minutes**.
  - Idle-at-hours sizes come from the MAT dump `live-1725` (heap-findings, mem-idle landing) and are labelled
    **MAT**.

Already logged by other lanes and not repeated here:

- `edc8cb02dfc2`, the umbrella task this design elaborates;
- `55974f161f71`, the render cache keeps every debug view;
- `0caf995ddc22`, the arity caches copy the indexes;
- the per-connection node cache, which is in the task-log batch.

## 1. Inventory

### Method

**Live measurements.** JVM-mode MCP, read-only, one bounded probe at a time.

- The walker is `tmp/cache-central-design-opus/cache_central_probe.clj`, a scratch namespace.
- It estimates the bytes reachable from a cache. Shallow sizes assume a 12-byte header, 4-byte compressed references
  and 8-byte alignment.
- It does not descend into shared owners: `Class`, `Var`, `Namespace`, `Keyword`, threads, loaders, Datahike DB
  values and index nodes, connections, and core.async.
- It is capped at 3–4 M objects per call.
- Several caches are walked in sequence with one shared `seen` set. For those, each row is its **marginal** size.
- The result is an upper bound on retained size. It is exact only for plain data.
- SCI contexts reach most of the program through copied Var roots, so for them the MAT *retained* number is the
  honest figure.

**Miss costs.** Taken from `seon.profile` counters on pid 59671, armed, inclusive, since boot.

### 1a. Seon-owned, process level (file:line at `de67fdd1a`)

| # | holder | key | value | proportional to | bound today | live entries (pid 70849; pid 59671) | size | miss cost |
|---|---|---|---|---|---|---|---|---|
| 1 | `seon.db/arity-base-cache` `db.clj:3801` | `[::arity-content [[attr datoms…]…]]` (datom vectors) | edge set, bounds, by-caller, by-callee | call edges (≈101,600) | LRU 4, constant | 4; 4 | **122.6 MB**, of which keys are 72.7 MB. MAT: 9.0 MB | `arity-base` mean 1,182 ms, max 5,257 ms (9 calls) |
| 2 | `seon.db/arity-commit-cache` `db.clj:3805` | commit id | the same base as #1 | — | LRU 16, constant | 1; 2 | +120 B marginal, because it shares #1. MAT: **92.6 MB**, because a base evicted from #1 stays alive here | as #1 |
| 3 | `seon.db/projection-cache` `db.clj:1286` | three names per population: attribute revisions, `[::commit id]`, `[::declaration-content datoms]`, plus `[::builder commit]` | compiled projection | declarations (≈3,514 forms) | LRU 24, `projection-cache-policy` constant | 24; 24 | +36.4 MB marginal. MAT: 12.8 MB | `carried-projection` 55,585 calls, mean 0.35 ms (armed hit path), max 1,822 ms |
| 4 | `seon.db/value-projection-cache` `db.clj:1292` | speculative revision key, or as-of point | projection | — | LRU 4, constant | 4; 4 | +6.0 MB | `value-projection` 3,093 calls, mean 3.1 ms, max 694 ms |
| 5 | `seon.schema/declared-projections` `schema.clj:1303` | `[forms predicates]` | declaration projection | forms | LRU 8, constant | 2; 2 | +9.0 MB | 17 calls, mean 59 ms, max 900 ms |
| 6 | `seon.schema/built-projections` `schema.clj:2304` | `[forms contracts options]` | full projection | forms + contracts (2,321) | LRU 8, constant | 5; 3 | +51.9 MB (for 4) | 56 calls, mean 90 ms, max 1,679 ms (about 390 ms / 570 MB per packaged build) |
| 7 | `seon.schema.edn/packaged-populations` `edn.clj:380` | `[resource-url declaration-stamp]` | EDN population | resource files | LRU 2, constant | 1; 1 | 1.32 MB | 54 calls, mean 2.3 ms |
| 8 | `seon.program/authored-shapes-memo` `program.cljc:279` | declaration stamp | row shapes | forms | LRU 2, constant | 0; 1 | — | one projection build |
| 9 | `seon.sci.eval/program-identity-cache` `sci/eval.clj:3219` | commit id | program basis | commits | LRU 256, `program-cache-policy` constant | 31; **256 (full)** | 92 KB | `program-identity` mean 0.05 ms |
| 10 | `seon.sci.eval/base-context-cache` `sci/eval.clj:3223` | `[program-basis(conn-id, generation, revisions) loaded-commit fault-fn]` | whole base SCI context (≈480 namespaces, 6,510 Vars) | program Vars | LRU 4, constant | 3–4; 4 | reachable > 164 MB (walk capped; mostly shared). **MAT retained 27.4 MB for 4, about 6.9 MB each** | `acquire-program!` 80 calls, mean 313 ms, max 1,511 ms. Branch with one write: 1,610 ms (audit item 5) |
| 11 | `seon.sci.eval/refusal-recording-cache` `sci/eval.clj:3463` | `[program target]` | `true` | programs | LRU 256, constant | 0; 0 | — | one transaction |
| 12 | `seon.test.runner/reach-holders` `runner.clj:865` | `[store reach-content]` | atom holding a reach index | program rows | LRU 16, constant | 0; 1 | MAT: 16.7 MB | test-request path |
| 13 | `seon.test.runner/reach-lineage` `runner.clj:869` | commit or connection | `[holder basis]` | — | LRU 64, constant | 0; 5 | small | — |
| 14 | `seon.render.value/throwable-safe` `render/value.clj:424` | `Class` | boolean | Throwable classes | **unbounded `memoize`** | — | small, but pins loaders | 4 `getMethod` calls |
| 15 | `seon.fn.analyzer/roots-of` `fn/analyzer.clj:227` | checkout path | manifest roots | 1 path | unbounded `memoize` | 1 | small | a process constant. **Keep**: a manifest change needs a new JVM |

### 1b. Seon-owned, scoped to a projection or a context

| # | holder | key | bound | notes |
|---|---|---|---|---|
| 16 | `seon.schema/projection-cache-value` holder `schema.clj:354`, `:366` | `(projection, k)`, 37 call sites (`error`, `db`, `render`, `config`, `cluster`, `walk`, `runner`, `eval`, `instrument`, `program`) | **unbounded** `basic-cache`, lives as long as its projection | Validators, program shapes and descriptors. It is bounded only by the projection's lifetime, and the projection is itself held by #3 to #6 |
| 17 | `seon.call-preparation/snapshot-memo` `call_preparation.clj:400` (inside #16) | revision key, then `[::commit id]`, then `[::snapshot id]` | LRU 24 per projection, `snapshot-cache-policy` constant | `derive-snapshot` 29 calls, mean 14 ms, max 52 ms. Evicts on refusal. Its `lookup`-then-`miss` path never records recency |
| 18 | `seon.test` destructive reach `test.clj:403` (inside #16) | `[program edge-attr revisions]` | LRU 4 per projection | evicts on error |
| 19 | `seon.render/shared-cache` `render.clj:1654` (one atom per branch scope in the context's environment) | `::page-results`, `::page-databases`, `::calls`, `::fragments`, `::invocations`, `::packages`, `::ai-calls`, `::graph-calls`, `[::entity-pull lookup]` | **unbounded map, no eviction** | live: empty (no page viewed since boot). **MAT: 69.8 MB**: `::graph-calls` 36.7 MB, `::invocations` 27.5 MB. Explicit invalidation: `web.clj:2216` `invalidate-runtime-derived-state` |
| 20 | `seon.sci.eval/::result-objects` `sci/eval.clj:3189` | result handle | none; lives as long as its context | **Not a cache.** It holds identity-bearing live results that the agent's `result/eN` handles name. Out of scope, listed so that nobody routes it through the cache |

Per-call `memoize`s and folds are scoped to one call and are not caches. They stay as they are:

- `fn.clj:3405`, `:3561`
- `db.clj:3589`, `:4357`
- `test.clj:1350`
- `runner.clj:1483`
- `schema.clj:2039`

### 1c. Datahike's caches (fork `c6c0f80d`)

| holder | key | bound / dial | live (pid 70849) | size |
|---|---|---|---|---|
| `CachedStorage` node LRU, `persistent_set.cljc:463`, **one per connection** (`:544`) | node address (store-global) | `:store-cache-size` 1,000 **nodes** (a count, not bytes) | default connection: 1,000 of 1,000 (946 `Leaf`, 54 `Branch`) after 2 minutes | ≥ 224 MB (walk capped at 4 M objects). **MAT: 347 + 24 + 1.2 MB across 3 connections**, about 350 KB per leaf |
| `query-result-cache`, `query.cljc:2503` | query + args + source identity | weighted LRU: 64 buckets, 1,000,000 *shallow structural* weight (not bytes); env or `set-query-cache-*!` | 9 (state field) | small |
| `query-cache` / `plan-cache`, `query.cljc:2412`, `:2417` | query form | `datahike.lru` 100, constant | — | small |
| `db.search/db-caches`, `search.cljc:17` | db hash → datom LRU | 5 dbs × `:search-cache-size` 10,000 | 0 | — |
| `schema-cache` meta / write, `schema_cache.cljc:8`, `:12` | schema key; store id | `*schema-meta-cache-size*` | 3; 1 | small |

**Totals at 2–5 minutes of uptime:**

- Seon-owned: about 227 MB reachable (#1 to #7, #9).
- Base contexts: about 27 MB retained (MAT).
- Node cache: at least 224 MB.

At idle after hours (MAT) the listed Seon holders were about 228 MB (#2, #3, #1, #10, #12 and #19) plus 372 MB of node caches.

### 1d. Renders as a cache kind (owner addition)

Today a render is computed once, eagerly, at settlement. `seon.sci.eval/shown-result` (`sci/eval.clj:3955`)
evaluates the declared pair's REPL entries, or the floor printer, and saves the exact text as `:seon.eval/shown` on
the evaluation (`seon.cluster.eval.edn:70`). Page, context and debug views re-render through `seon.render/*`, and #19
memoizes those, unbounded.

**Measured miss cost per render kind** (pid 59671, armed, inclusive):

| render | calls | mean | max |
|---|---|---|---|
| `seon.sci.eval/shown-result` (one evaluation's AI face) | 273 | 4.0 ms | 85 ms |
| `seon.render.value/prepare` (the floor's node tree) | 749 | 3.8 ms | 116 ms |
| `render-ai` (a declared AI render) | 147 | 2.0 ms | 71 ms |
| `seon.render.transcript/history-entries` | 15 | 7.4 ms | 30 ms |
| `seon.render/call-with-walk-context` (a page or context walk) | 42 | **652 ms** | 3,212 ms |

Shown text on default (fresh store, n = 19) averages 556 characters, about 140 tokens. The 90th percentile is about 780
tokens and the maximum about 1,560 tokens.

**Key.** `[:seon.cache/render renderer-digest profile-id value-key]`.

- `renderer-digest` is the selected producer's definition digest. For the floor printer it is the floor's own
  digest. A renderer edit therefore misses by construction.
- `value-key` depends on the value:
  - the immutable value itself, for plain data (Clojure's `hasheq` is cached per collection, and `=` short-circuits
    on identical values);
  - `[commit-id lookup selector]`, for an entity render;
  - the blob digest, for a value settled into the blob tier.
- Live objects (atoms, Java objects, anything identity-bearing) are **not cached**. They render once, and their shown
  text is the record.
- A page is `[registration program-digest commit-id profile]`. This retires `invalidate-runtime-derived-state`,
  because a runtime evaluation that installs code changes the program digest, so the old pages simply miss.

**What a re-render after eviction or a JVM restart needs: the value.**

- **Database-derived values** re-pull at the evaluation's recorded basis. The evaluation already stores
  `:seon.cluster.eval/read-basis-transaction` and `:seon.cluster.eval/read-evidence`.
- **Values settled into blobs** re-read their blob.
- **Plain values computed by the form are not stored anywhere today.** They live only in the context
  (`::result-objects`), so after a restart they are gone. Re-rendering them would mean re-evaluating the source,
  which is not safe for effects.
- **Live objects** vanish.

**What the durable record must keep: `:seon.eval/shown`, the exact text the agent saw.** It is an observation, not a
cache, for three reasons:

1. It is history. `CLAUDE.md` says "Save exact shown text … never clip HTML or historical units again". A renderer
   edit must not rewrite what an agent was shown.
2. Provider prompt caching needs byte-identical prefixes across turns.
3. Context tuning and dogfood review read what the agent actually saw.

**So the owner's "renders need not be persisted" holds for the cache, and not for the observation.** The cache is
never persisted, and the observation stays a fact. One optional later saving: shown text over the blob threshold
could be stored once as a blob, with the fact holding its digest, so that equal texts dedupe. That is a separate cut.

## 2. Design: one cache, `seon.cache`

The prototype is `tmp/cache-central-design-opus/seon_cache_prototype.clj`, about 65 non-blank lines. Its behaviour
was exercised against Caffeine 3.2.4 in `tmp/cache-central-design-opus/bench.clj`.

### Owner and shape

One Caffeine `AsyncCache` per process. It is held in the process roster beside the store (the law "Live host objects …
exactly one roster") and is never a `defonce`.

- **Key**: `[kind key]`. `kind` is a qualified keyword that its owner registers with a weigher, for example
  `:seon.cache/projection`.
- **API**:
  - `(seon.cache/lookup cache kind key derive)` and `(… retain?)`;
  - `(seon.cache/status cache)`.

### Budget

**One byte dial**, `:seon.config.cache/max-bytes`, as a database fact whose declared default is 512 MB (about 5% of
the 10.5 GB heap). There are no per-kind dials. The reasons:

- W-TinyLFU's frequency sketch already protects hot small entries from a burst of large one-hit entries, such as a
  fresh arity base.
- A per-kind floor is a hypothetical restriction until a measured starvation names it.

Datahike's two budgets are separate dials that already exist: `:store-cache-size`, converted to weight in slice 5,
and the query weight limit. Status shows all three.

### Weighing

Each entry is weighed once, when its derivation completes (async weights are 0 while in flight). The weight is a count
the value already has, multiplied by a per-kind constant **calibrated by the committed walker**. It is never a deep
walk at runtime.

| kind | weight at insert | calibration (this lane) |
|---|---|---|
| projection | forms × 3.7 KB | 52 MB / 4 projections / 3,514 forms |
| base context | Vars × 1.06 KB | MAT 6.9 MB / 6,510 Vars |
| arity base | edges × 250 B (value only; the content key goes, see below) | (122.6 − 72.7) MB / 2 distinct / 101.6 k |
| snapshot | members × measured bytes | slice 3 measures it |
| render text | 2 × characters + 64 | UTF-16 string |
| render tree / page | nodes × measured bytes | slice 4 measures it |
| alias (name → digest) | 128 B | key-sized |

A calibration drifts. The honest bound is therefore "estimated bytes". The committed walker re-measures, and status
prints the estimate beside the heap in use.

### Eviction

Caffeine's size-based W-TinyLFU: a frequency sketch plus windowed and segmented LRU. It resists scans, which LRU does
not: one test run's burst of branch bases would otherwise evict default's hot projection.

An evicted value that is still in use stays alive in its user and is collected when the user finishes. "A miss only
costs the normal eval tax" (owner ruling).

### Per-kind statistics

The owner keeps `LongAdder` counters per kind: hits, misses, evictions, retained weight and load time. A removal
listener sees the key and therefore the kind. Caffeine's own `recordStats` is per cache, not per kind, so it is not
used.

`runtime_status` gains one `:seon.cache` block: the dial, the weighted bytes and one line per kind. That is about 12 ×
25 ≈ 300 tokens.

### Content keys, so there are no invalidation calls

Every kind keys on what it reads:

- the program's definition digests (`:seon.program/definition-digest`, which is present on function *and* schema
  rows);
- or a commit id (store-global);
- or the immutable value.

Two-step lookups replace the three- and four-tier memos in #3, #10 and #17:

- **Alias kinds** map a cheap name to the digest. Examples: `:seon.cache/program-digest`, keyed by commit id, and
  keyed by the read attributes' Datahike revisions for a speculative value that carries its basis context.
- **The value kind** is keyed by the digest.

For example, `[:seon.cache/projection declaration-digest]` is one entry, whichever branch, commit or speculative value
named it. Two lookups cost about 2 × 150 ns in the prototype (vector key and future join).

This deletes:

- per-connection scoping: `:datahike.cache/connection-id` and `generation` in keys, which is audit items 5 and 9. A
  new branch at an equal program hits;
- the value tier and its `ValueKey` leak;
- the `[::declaration-content datoms]` and `[::arity-content datoms]` keys. Together those 72.7 MB of datom vectors
  exist only to be compared;
- every `cache/evict` call on refusal. A refusal is returned with `retain?` false and is never stored;
- `invalidate-runtime-derived-state` and the program-identity `evict` (`sci/eval.clj` `watch-program-identity!`).

### Concurrency: derive once per key

`lookup` works in two steps:

1. `getIfPresent`, which is a read with no write on a hit.
2. On a miss, `asMap().putIfAbsent(k, incomplete-future)`. The winner derives **on its own thread, outside any map
   lock**, and completes the future. Losers `join`.

This matters for three reasons:

- Nested lookups are allowed. A projection derivation may look up the packaged population. Caffeine's `get(k, fn)` is
  a `computeIfAbsent`, which forbids updating other mappings from inside the function; that would make one central
  cache deadlock-prone.
- It is proven in the bench: a nested lookup across two kinds works, 8 concurrent misses run 1 derivation, and a
  thrown derivation is removed before it completes, so it is not retained.
- Cycles are impossible because content keys are acyclic: declarations → projection → snapshot → base context.

### What happens to Datahike's caches

**They stay Datahike's.** `CachedStorage` speaks the core.cache wrapped protocol over an atom inside the fork's hot
read path (`restore`, `accessed`). Routing it through our cache would fork that path and change its hit cost for
every read.

Instead, three changes:

1. The dials come from configuration at connection open. `:store-cache-size` and the query weight limit are already
   Datahike config and environment inputs.
2. The fork holds **one node cache per physical store**. Addresses are store-global, and the LRU already evicts reused
   addresses (`persistent_set.cljc:419`). That deletes the per-branch copies: MAT shows 24 MB of extra decoded nodes
   at idle, and up to about 350 MB per test branch under load.
3. The node LRU becomes the fork's existing `datahike.lru/weighted-lru`, weighted by datom count, so its bound is
   bytes-like rather than a node count. 1,000 nodes measured anywhere from 224 to 347 MB.

## 3. The three candidate libraries, measured

Measurements: bare JVM 26.0.1, no Seon, `-Xmx512m`. Three runs; ranges are shown (the machine was loaded).

| | clojure.core.cache (wrapped LRU) | Caffeine 3.2.4 | per-kind core.cache LRUs with dials |
|---|---|---|---|
| hit, one thread | `lookup-or-miss` 408–858 ns (swap for recency); `lookup` 14–23 ns (records no recency) | `get` 15–26 ns; `getIfPresent` 6–28 ns; prototype `lookup` 147–218 ns | as core.cache |
| hit, 8 threads (wall ns per operation) | 761–1,808 ns (CAS retries on one atom) | 27–72 ns | as core.cache, spread over atoms |
| 8 concurrent misses of one key | **8 derivations** | **1** | 8 |
| bound | entry count | bytes (`maximumWeight` + weigher); a 3 × 300 / 512 cap kept 1 | entry count per kind |
| eviction | LRU (or LU, TTL, FIFO, LIRS) | W-TinyLFU (scan-resistant, adaptive) | LRU |
| per-kind statistics | none | per cache; per kind through a removal listener | per atom, hand-made |
| process memory cap | no | yes (estimated weights) | no |
| dependency | present (1.1.234) | new: about 900 KB jar plus jspecify; already in `~/.m2` | present |

core.cache stays right for dependency-internal uses such as Datahike's. It cannot bound Seon's derived values in bytes,
and these entries differ in size by a factor of 10^5: a 92 KB identity table against a 50 MB arity base.

## 4. Why Caffeine, and the `CLAUDE.md` amendment

The law "Every cache is `clojure.core.cache` (or the dependency's own …); bounded with an eviction policy (LRU by
default) whose size is a configuration dial" was written to forbid hand-rolled caches. Caffeine is the highly regarded
library for exactly this, and it does four things core.cache cannot:

1. a **byte budget** (weights);
2. **one derivation per key** under concurrency;
3. **hits about 15–50 times cheaper**, with no global CAS on a hit;
4. **scan-resistant eviction**.

The proposed wording:

> Every Seon cache is a kind in `seon.cache` (Caffeine), keyed by content, weighed at insert, under one byte dial;
> dependencies keep their own caches with configured dials; no `memoize` or atom holds derived state across calls.

## 5. Migration slices

Every slice loads on its own, converts all its callers and retires the old path.

The line counts are **estimates** from spans measured at `de67fdd1a`. Each slice's diff proves its own count. The
prototype puts the owner at about 70 lines.

| slice | what | added | deleted | net |
|---|---|---|---|---|
| S1 | Caffeine in `deps.edn`; `seon.cache` owner, the config dial row and the status block; convert #9, #10 and #11 (`program-cache-policy`, 14 lines; tiers) and #1 and #2, with the arity base keyed by the program digest of the call-arity rows; drop the `[::arity-content datoms]` key and #14 (weakKeys kind or per call). Regression: one class test for compute-once, eviction under the dial and `retain?` false | about 110 | about 60 | +50 |
| S2 | the projection family, #3 to #8: declaration digest as the value key, commit and revision aliases. Delete the value tier, `revision-agreement`, `nearest-base`, `as-of-key`, `declaration-content-key` and most of `carried-projection`'s tiering (the `db.clj:1293–1640` span is about 230 lines), `projection-cache-policy` (16 lines), and the two schema `defonce`s | about 50 | about 170 | −120 |
| S3 | #16 to #18: `projection-cache-value` becomes `(lookup cache kind [projection-digest k] …)`, so the per-projection holder and `with-compiled-cache` retire. Snapshot tiers and `snapshot-cache-policy` go (`call_preparation.clj:380–446`, 61 lines), and so do test reach and #12/#13 | about 30 | about 110 | −80 |
| S4 | #19: render and page kinds; delete `shared-cache` / `branch-scope` (51 lines), `invalidate-runtime-derived-state` (27), the `::graph-calls` retention, and the entity-pull evidence cache keyed by revisions | about 45 | about 120 | −75 |
| S5 (fork) | one node cache per physical store, weighted by datom count; dials from configuration | about 25 (fork) | about 15 (fork) | +10 |
| **total (Seon)** | | **about 235** | **about 460** | **about −225** |

S1 alone is net positive. It lands only together with its conversions, and S2, which is the net-deletion slice,
follows directly. If review wants S1 to be net-negative on its own, fold #3's commit-and-revision tiers into it.

## 6. Three priced options

**A. Configuration dials on the existing core.cache LRUs, plus stored delays (simplest viable).**

- Guarantee: every holder is bounded by a configured entry count; the stampede is fixed; #14 and #19 get bounds.
- Cost: about +45 / −35 lines, half a day.
- Sacrifice:
  - no byte cap: a 50 MB arity base and a 92 KB identity table count the same;
  - hits still cost 0.4–1.8 µs of swaps;
  - per-connection keys and explicit invalidation stay;
  - status stays per-atom and hand-made.

**B. One Caffeine `seon.cache` with one byte dial, content-digest keys and no invalidation; Datahike keeps its caches
with configured dials and one node cache per store (RECOMMENDED).**

- Guarantee:
  - a fixed estimated-byte cap on all of Seon's derived values;
  - one derivation per key;
  - branches share every derivation at an equal program;
  - per-kind hit, miss, eviction and byte statistics in `runtime_status`;
  - no invalidation code.
- Cost: one dependency; about 1.5–2 days with Astra review across S1–S5; about −225 net Seon lines.
- Sacrifice: weights are calibrated estimates rather than exact; a new dependency; the `CLAUDE.md` law changes its
  named library; Datahike's two budgets remain separate dials.

**C. B, plus routing Datahike's node and query caches through the same Caffeine instance, with one budget for all
derived heap.**

- Guarantee: a single byte cap covering index nodes too, which are the largest holder at 224–372 MB.
- Cost: B plus about 80 lines in the fork's hot read path (`CachedStorage` `restore`/`accessed`, `query.cljc`) and a
  permanent divergence from upstream; 3–4 days.
- Sacrifice: fork maintenance on the path every read takes; a changed node-hit cost to prove on every benchmark;
  Datahike can no longer run without Seon's cache.

## 7. Evidence

- **Probes and bench:**
  - `tmp/cache-central-design-opus/cache_central_probe.clj` (walker);
  - `bench.clj`, with output in `bench-out.txt`;
  - `seon_cache_prototype.clj`.
- **Live probes:** JVM-mode MCP read-only sessions `cache-central-design-opus*` on pids 59671 and 70849.
  - The largest single walk took 3.7 s. It was over one second because it traversed 4 M objects. Its cost is
    proportional to the walked graph, and it is a diagnostic rather than an ordinary operation.
- **No branches were created.** Nothing in `src` changed.
- **Bare JVMs:** the bench was run in bare, non-Seon JVMs (`-Xmx512m`, about 7 s each) to measure Caffeine, which is
  not on default's classpath. This is reported because the preamble says "never boot another JVM". No Seon JVM was
  booted.
