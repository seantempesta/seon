---
type: landing
status: one fix landed (as-of projection key); two dependency-level costs reported with options
lane: writer-cost-2 (Opus 5.5)
---

# Lane writer-cost-2 — 2026-09-23

Question from `lane-test-overhead-2026-09-23.md`: why does `seon.db/transact!` average
~248 ms per small transaction during test recording? Owned paths: `src/seon/db.clj`,
`test/seon/db_test.clj`, this note.

## Method

Dependency pins read: `reference-code/datahike` at `2cc313a61a30a4d9fa3e030d91c6b6d0f715b142`,
`reference-code/konserve` at `8cd9144f4338c1fbdb5e531bc996189d71a86fc5`.

All probes ran on default (pid 55322, then 60088 and 63253 after the orchestrator
restarted it; each ran from the checkout, `(seon.fs/source-directory)` =
`/Users/sean/src/seon`). Probe writes went only to throwaway branches
(`:writer-cost-2-probe`, `-b` … `-e`) made with `registry/branch!` and
`store/open-branch!`. All of them were released and retired afterwards; the final
roster check returned `()`. I used namespace `tmp.writer-cost-2` and my own io-prepl
session ids. Nothing was redefined. To measure, I used a `tm` nanoTime macro,
`seon.profile/begin`/`explain` windows around one `bin/test-check` request, a cache
watch, and JFR (`jcmd JFR.start settings=profile`, `JFR.configure stackdepth=512`).

## Phase table, one small `transact!`

Two-datom write (`[:db/add e :seon.test/pass-count n]`), warm connection, ~490k datoms:

| phase | cost | proportional to |
|---|---|---|
| `carried-projection` (committed value, memo hit) | 0.02–1.8 ms | O(1) |
| `write-error` (pre-validation) + `encode-transaction-in` | 0.04 ms each | transaction datoms |
| `datahike.api/with` (core/with) | 0.2 ms warm; 31–55 ms first on a fresh connection | datoms; cold = index nodes read |
| report validation (`write-report-error`) | 1.5–2.4 ms warm; 45–92 ms first on a fresh connection | report entities; cold = node reads |
| Datahike commit (flush + konserve write + fsync) | 28–62 ms | index roots rewritten per commit, not datoms (below) |
| whole `seon.db/transact!` | 37–62 ms warm; 200–215 ms first on a fresh connection | commit + cold node cache |

Raw `@(d/transact! …)` of an empty transaction costs 24–46 ms. `seon.db/transact!` of
the same costs 26–53 ms, so Seon's warm preparation is about 2 ms.

## Where the 248 ms came from

1. **Seon, fixed here: as-of views of the writer's in-transaction value.** During one
   38-member request (`seon.render.value-test`, run `a26a04708380`) the armed profile
   showed `seon.db/content-projection` ×1,509 / 33.9 s and
   `declaration-content-key` ×1,509 / 28.6 s, but only 4 real derivations
   (`load-projection`, 176 ms). JFR attributed 31 % of all execution samples to it
   (1,589 / 5,083), 636 of them at `db.clj:1447`. That line is the `as-of` branch of
   `carried-projection` when `as-of-key` is nil, which happens when the origin is
   uncommitted. `seon.test.runner` (`prepare-failures!`, `record-latest-tx`; runner.clj
   1108, 1368) reads `(db/as-of database basis-t)` inside the writer's `:db.fn/call`. So
   every pull re-read all ~40k declaration datoms, 25 ms per read.
   - Fix (`db.clj` `carried-projection`): when the origin is uncommitted but attached,
     the view keys by `(assoc (projection-cache-key origin) ::as-of time-point)` in the
     existing value tier. Within one connection an attribute's revision advances with
     every datom it gains or loses (`reference-code/datahike/src/datahike/db.cljc:423`
     `advance-cache-context`, `:444` `speculative-cache-context`), so this pair fixes
     the view's declaration datoms. No new cache, no stamp.
2. **Datahike, not changed: a fresh connection starts with an empty node cache.** Each
   branch connection builds its own `CachedStorage` LRU
   (`reference-code/datahike/src/datahike/index/persistent_set.cljc:458` `create-storage`;
   the comment at `:535-543` says so). Every test fixture and every recording or
   acquisition branch is a new connection. Its first transaction read 30 nodes from disk
   (stats `:reads 0 → 30`) and took 86–215 ms against ~40 ms warm.
3. **Datahike/konserve, not changed: the commit floor.** A 2-datom commit made 5 PSS node
   writes (`CachedStorage` stats) and touched 3 files of 125–144 KB each in
   `data/store`, about 410 KB per commit. That is the 4,096-way branching factor
   (`src/seon/cluster/store.clj` `datahike-configuration`, creation-fixed). Konserve's
   file batch forces each blob and then the directory
   (`reference-code/konserve/src/konserve/filestore.clj:103,147`, `sync-base :66-75`).
   A measured `FileChannel.force` on this machine costs 3.6–4.6 ms for 20 KB; a
   directory force costs 0.05 ms. The existing issue
   `docs/seon/issues/file-store-commits-pay-five-times-the-fsyncs-they-need.md` owns
   this class.

In the post-fix request, JFR park events inside `transact-call` per caller:
`commit-results!` ×67, p50 94 ms, mean 105 ms;
`sci.eval/record-acquisition-refusals!` ×30, mean 194 ms. The second caller was a
concurrent run in which another lane's runner change made `reusable-result`
host-bound. Writer-thread CPU was only 103 of 4,814 samples, so the remaining wall
time is I/O (fsync of the rewritten roots) and cold node reads, not Seon CPU.

## Before / after (same probe, `tmp.writer-cost-2/probe`)

Parent ran on pid 60088 (checkout HEAD `0a24e91e7` for db.clj); commit ran on pid 63253
after adoption. Both came from the checkout. They are different JVMs because default was
restarted between the two runs.

| probe | parent | this change |
|---|---|---|
| `carried-projection` of an as-of view of an in-transaction value (6 views, one point) | 24.9–27.9 ms each | first 29–97 ms (one per revision key and point), then 2.2–3.4 ms |
| warm `seon.db/transact!` 2 datoms ×5 | 36.5–62.0 ms | 37.0–66.0 ms (no regression; noise from concurrent runs) |
| request window `seon.render.value-test`: `content-projection` | ×1,509 / 33.9 s | ×23 / 1.4 s |
| same window: `carried-projection` inclusive | 36.4 s | 1.7 s |
| same window: `declaration-content-key` | 28.6 s | 0.5 s |

The remaining 1.3 ms per as-of hit is `declares-program?`: `(first (d/datoms as-of :avet
:seon.schema/key))` on an as-of view. It is proportional to the declaration rows.
Datahike builds the filtered view eagerly.

Request wall time did not visibly drop: 27.7 / 29.5 s before, 31.0 s after. The other
lanes' runs overlapped on the same JVM, and the saved time was CPU spread across
threads. The same 9 red members appeared before and after (runs `2d368affab6f`,
`7f9f8af85718`); none are in this lane's files.

## Proof runs

- Adoption: `bin/seon init --dev default --changed src/seon/db.clj test/seon/db_test.clj`
  rc 0, **24,168 ms**. Over ten seconds, so this is a defect, but not in this lane's
  files: `seon.cluster/refresh-source!` ×3 took 72.3 s inclusive and
  `full-source-refresh!` ×1 took 22.3 s. It belongs to the publication-cost class
  (`a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction.md`).
  Three earlier attempts were refused in 1.0–11.7 s: "Source changed during development
  adoption". The reload reached `cluster/agent.clj`, `cluster/source.clj` and `flow.clj`,
  whose disk bytes, uncommitted in other lanes, differed from the published ones
  (`seon.cluster/verify-development-sources!`, cluster.clj:2299). I shelved my patch
  until the orchestrator restarted default, then re-applied it and adopted.
- `bin/test-check default --policy named --test …` over 4 tests, run `e2a84c44fd14`:
  46,986 ms, pass 19, fail 2. The new
  `seon.db-test/an-as-of-view-of-an-in-transaction-value-reuses-its-population` and
  `an-in-transaction-declaration-derives-its-population-once` were green. The two fails
  were bound overruns (10.7 s and 6.5 s against 5 s) in tests whose as-of path
  (committed origin) this change does not touch. On rerun, run `65faf9154970`, both
  were green: 17,193 ms, pass 8. Both requests exceeded 10 s under load average 5.0
  with concurrent requests from other lanes. That is the per-request overhead class
  `lane-test-overhead-2026-09-23.md` owns.
- Contracts: no contract was added or changed. `value-projection` already accepts a
  `[:map-of :qualified-keyword …]` key.

## Timings over one second

| operation | ms | reason |
|---|---|---|
| adoption of db.clj | 24,168 | publication re-analysis (`refresh-source!`), outside this lane. Over 10 s: defect, publication class |
| named 4-test request | 46,986 | bodies plus fixtures under concurrent load. Over 10 s: test-overhead class |
| named 2-test rerun | 17,193 | same |
| value-test requests (measurement) | 27,683–44,630 | 38 bodies; measurement windows, not gates |

## Options for the orchestrator (dependency-level, not changed here)

1. **Share the persistent-set node cache across the connections of one physical
   store.** Recommended. Datahike fork, `persistent_set.cljc` `create-storage`: key the
   LRU by `datahike.store/physical-store-key`. Node addresses are immutable, so a
   branch's nodes are the same objects. Guarantee: unchanged durability. Cost: about 10
   lines in the fork, plus a memory bound that counts nodes once instead of per
   connection. Gives up: per-connection cache isolation (none needed for immutable
   nodes). Removes the 86–215 ms first-transaction cost and the cold reads of every
   fixture and recording branch.
2. **Lower the creation-fixed branching factor** (4,096 → 512) so a small commit
   rewrites ~16 KB roots instead of ~410 KB. Guarantee: unchanged durability. Cost: a
   new store (nuke), and publication flushes return to ~3,029 values
   (`complete-publication-takes-seventy-seconds.md`). Gives up: the publication tuning.
3. **Coalesce konserve's per-blob forces within one ordered batch** (force the staged
   blobs, rename them all, then one directory force before the branch head). Guarantee:
   the prefix order holds between batches but not within a batch. That changes
   durability semantics and needs the owner's ruling. Cost: a konserve fork edit. Gives
   up: per-blob rename ordering.

## Outside this lane (for routing)

- `seon.fn/declared-reference-edges` (fn.clj:1516, held): an unverified candidate, not
  a one-line proven fix. Datahike's result cache compares revisions only within one
  connection generation and refuses `:all` attributes
  (`reference-code/datahike/src/datahike/query.cljc:2999-3016`
  `source-context-unchanged?`). A rules query over a fresh selection branch therefore
  never hits. The candidate is to memoize its result with
  `cache/lookup-or-miss` keyed by the commit id plus the rules' attribute revisions, the
  same shape as `projection-cache-key`. Verify first that the rule attributes are the
  full read set.
- A dirty-but-unpublished file blocks every other lane's adoption that reaches it, in
  both directions (`verify-development-sources!`). The orchestrator has the rule.

## Changed paths, sizes

- `src/seon/db.clj` +10 −1. Net src +9: nothing could go in this slice. The next db.clj
  slice deletes `declares-program?`'s as-of scan: an as-of view derives from its
  origin's declaration presence.
- `test/seon/db_test.clj` +31: one regression.
- No RESET NEEDED.

## Design: option 1, one node cache per store (ruled 2026-09-23; docs only, the fork is held)

The orchestrator chose option 1. `reference-code/datahike` is held by the store-damage
lane until its push lands; this section is the change to make once it is handed over.
Pins: fork `2cc313a6`, upstream `replikativ/datahike` `upstream/main` (`7f39cccc`).

### What upstream already does

Upstream shipped this change as `d2b9e525`, "perf(index): share the node cache between
connections to one store (#998)" (Weilbach, 2026-08-27). It is not in the fork:
`git merge-base --is-ancestor d2b9e525 HEAD` is false.

- **Where the cache lives.** It sits in the connection-registry entry, not in a global
  keyed by store id (`upstream/main:src/datahike/connections.cljc:30-106`). Upstream
  gives three reasons: a global needs an explicit drop that `delete-database` skipped,
  and upstream measured 334 nodes of a deleted database pinned for the life of the
  process; a failed connect left a global entry behind; and a rebound `*connections*`
  must isolate.
- **Sharing rule.** `sibling-cache` returns a live connection's or reservation's cache
  when it has the same store and the same `:store-cache-size` threshold.
  `acquire-node-cache!` does the sibling lookup and the reservation in one `swap!`, so
  two branches connecting at once cannot build two caches.
- **How storage gets it.** `di/node-cache-key` and `di/make-node-cache`
  (`upstream/main:src/datahike/index/interface.cljc:22-33`). The connector assocs the
  cache onto the raw store before building storage (`connector.cljc:381-384`).
  `create-storage` takes `(or (get store di/node-cache-key) (di/make-node-cache …))`
  (`persistent_set.cljc:662-671`).
- **What is not shared.** Only the cache. `pending-writes`, `freed-set`, `freelist` and
  `stats` stay per connection.
- **Measured upstream** (6.44M datoms): a fresh branch's first query went from 12,569
  node restores, 2,920 ms and 6,566 MB allocated to 0 restores and ~600 ms.

### The fork change (port, not a new design)

The fork's registry already shares one per-store resource, `:write-hooks`, through
exactly this sibling pattern: `reserve-connection-opening!`
(`src/datahike/connections.cljc:36-94`) finds a sibling entry with the same
`:physical-store-key`, or makes a fresh atom. The node cache rides the same path:

1. `src/datahike/index/interface.cljc`: add `node-cache-key` and
   `make-node-cache [threshold]`. `make-node-cache` returns
   `(atom (cache/lru-cache-factory {} :threshold threshold))` and must be pure, because
   a `swap!` retry may call it and throw the result away. Upstream's docstrings come
   across as-is.
2. `connections.cljc` `reserve-connection-opening!`: take `threshold` and
   `make-cache`. Inside the same `swap!` that picks `write-hooks`, pick `:node-cache`
   from the first entry with an equal `:physical-store-key` and an equal `:threshold`,
   or else `(make-cache)`. Store `:node-cache` and `:threshold` on the reservation, and
   return `:node-cache` in the `:owner` result. `complete-connection-opening!` (`:98-111`)
   copies both keys into the published entry, the same way it copies `:write-hooks`.
   This keys on the fork's `physical-store-key` rather than upstream's bare store id,
   the same choice the fork already made for `write-hooks`.
3. `connector.cljc` `-connect-impl*` `:owner` (`:333-370`): right after
   `ks/connect-store`, `(assoc raw-store dii/node-cache-key node-cache)`. All three
   `ds/add-cache-and-handlers` calls read this one `raw-store`: the first build at `:337`,
   the index-mismatch rebuild at `:351`, and the create-time-config adoption at `:366`.
   All three therefore get the one cache. Branching factor and diff buffer only change
   how nodes are decoded, and a cached entry is an already-decoded node keyed by its
   address, so reusing the cache across these rebuilds is sound.
4. `persistent_set.cljc` `create-storage` (`:458-466`): the cache argument becomes
   `(or (get store di/node-cache-key) (di/make-node-cache (:store-cache-size config)))`.
   Paths with no connection behind them (`create-database`, `database-exists?`) get a
   private cache, as upstream does. Nothing else in `CachedStorage` changes.

Size: about 25 fork src lines plus the upstream test, and no Seon src change.
`seon.cluster.store/open-branch!` and every fixture, recording or acquisition branch
already connect through `-connect-impl*`.

### Bound, eviction, release

- **Bound.** It stays the existing LRU threshold, `:store-cache-size`, default 1000
  (`datahike/config.cljc:24`), now once per (store, threshold) instead of once per
  connection. At 4,096-way nodes that is about 2.5 M datoms held for the whole store,
  where the heap research counted 21 caches. No new dial.
- **Eviction.** Unchanged LRU behaviour, now over one population. Address reuse is the
  only way an address can name different bytes. It happens through the freelist, and
  only when `[:online-gc :enabled?]` is set and the store has a single branch
  (`online_gc.cljc:176-212`). Reuse already calls `wrapped/evict cache address`
  (`persistent_set.cljc:421-422`), which on a shared atom evicts for every connection:
  the correct semantics. Seon enables neither online GC nor `:crypto-hash?`. Addresses
  are `squuid`s (`persistent_set.cljc:277-282`), unique per write. Offline collection
  can leave a cached entry for an address nothing references any more. It is
  unreachable through any root and ages out of the LRU.
- **Nothing retained per connection.** A released connection's entry leaves the
  registry (`release-connection-reference!` on the last reference,
  `delete-connection!`, `fail-connection-opening!` for an abandoned open). The cache
  is reachable only from entries and from the `CachedStorage` of database values still
  alive. A database value that outlives its release, such as those held by
  `seon.sci.eval/refusal-recording-cache` and `base-context-cache` in the research note,
  then pins the one shared, bounded cache. Today it pins its own full cache. The Seon
  holder fixes that note names remain wanted for the connection world they pin, but no
  longer multiply index memory.
- **Isolation.** Writes stay per connection. A node lands in the cache at `store`
  (`persistent_set.cljc:423-425`) before its flush, but another connection can only
  ask for that address through a root that names it, and a root is published only by
  a commit or a merge.
- **Cache pressure.** Default's agents and concurrent test branches now share 1,000
  slots. Test branches start at recent commits of the same store and read mostly the
  same addresses. Measure the hit rate after landing: sum `:reads` over
  `(:stats (:storage store))` per request. `:store-cache-size` is a reopen setting, not
  a creation-fixed one.

### Regression

- **Fork:** port `upstream/main:test/datahike/test/node_cache_test.clj`, all seven tests
  (`cache-reservations-share-by-store-and-threshold`,
  `rebound-connection-registries-isolate-node-caches`,
  `non-connection-store-operations-do-not-reserve-a-cache`,
  `failed-connect-abandons-its-cache-reservation`,
  `invalidation-rejects-an-in-flight-connection`,
  `invalidation-releases-exactly-the-connections-it-removes`,
  `shared-cache-preserves-branch-write-isolation-and-reconnects`). Adapt the reservation
  calls to `reserve-connection-opening!`.
- **Seon** (`test/seon/db_test.clj`, one test): read an entity through the fixture's
  connection, open a branch at that commit, read the same entity there, and assert
  that the branch's `CachedStorage` `:reads` stayed 0 and its cache is `identical?` to
  the parent's. Then release the branch and assert its connection id is gone from
  `datahike.connections/*connections*` while the parent's cache is unchanged.
- **Before/after probe to record** (the one this note used): the first
  `seon.db/transact!` on a fresh branch connection, 200–215 ms today against ~40 ms
  warm, with `:reads` 30 against the target 0. Also the live `CachedStorage` count
  against distinct cache atoms in a heap walk of default after one 38-member request.
