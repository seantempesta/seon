# Store GC bug: a commit points at a value the collector swept (2026-10-23)

**Cause:** Datahike's node cache keeps a value's content key under its datom, and `value-key` treats that cached key as proof the value is stored. A collection that sweeps the value leaves the entry in place. A later commit of an equal datom (same e, a, v) then writes a node with a `ValueRef` to a key that no longer exists, and the next read of that node fails with `{:type :value-not-found}`. The failure is deterministic and needs only one process. Dropping branches is still correct: the collection is reachability-based across every branch, so it never deletes nodes that branches share.

Observed in `seon.agent-test/a-root-with-no-provider-ends-the-attempt-on-its-typed-error` (`/Users/sean/src/seon-jolt/next-test-root/target/prove/tests/shard-3.log:23`): `Value not found in storage.` with key `3d2eb780-…` (a v5 hasch uuid, which is a value's content key, not an index node's address) on the shared `target/runs/test-store`.

## 1. What `seon drop` and `delete-branch!` delete

- `seon drop` is `seon.check/drop-mains` (`src/seon/check.clj:1254`), which calls `seon.db/delete-main!` (`src/seon/db.clj:212`), which calls `d/delete-branch!` for main and every branch under it.
- `datahike.versioning/delete-branch!` (`reference-code/datahike/src/datahike/versioning.cljc:303`) only removes the branch from the roster (`roster-remove-held!`). It deletes no nodes. The docstring says the branch "will still be accessible until the next gc".
- Keys are deleted only by `datahike.gc/gc-storage!` (`gc.cljc:100`). Seon runs it from `seon.db/collect!` (`src/seon/db.clj:259`, call at `:281`) at root's quiescence when a branch named in its `:seon.root/collected` record is gone. Root refuses only while one of **its own** agents is live (`src/seon/root.clj:300`).

## 2. Is GC reachability across all branches?

Yes. `gc-storage!` marks from every branch in the roster (`reachable-in-branch`, `gc.cljc:25`). It walks each branch's whole commit history, because `remove-before` is the epoch and history is kept. It marks every index node and every out-of-line value those nodes reference (`persistent_set.cljc` `mark-unseen`). It then sweeps unmarked keys written before the cutoff (`konserve.gc/sweep!`, `konserve/src/konserve/gc.cljc:9`). Nodes shared between branches are safe.

The hole is on the write side. `gc-storage!` clears only the schema write cache (`gc.cljc:170`, `sc/clear-write-cache`). The node cache, which is shared by every connection to the store in a process (`persistent_set.cljc:668` `create-storage`), keeps its value-key entries:

```clojure
;; persistent_set.cljc:562 (value-key)
(or (di/cache-get cache d)            ; a cached key is trusted: no write, no exists? check
    (let [v (.-v d) vk (uuid v)]
      (cond ...
        (k/exists? store vk {:sync? true}) (di/cache-put! cache d vk)
        ...
```

The cache is keyed by the datom, and datom equality and hash cover e, a and v only (`datom.cljc:100-108`), not tx. So a **new** datom that equals one the cache holds gets the old key back, and the value is never re-written.

In Seon this is the normal pattern, not an edge case:
- A dropped main's entity ids are in no branch head, so the next root mints them again (`docs/storage.md#leases`: "a block's unspent tail is no head, so the new root mints those ids again").
- A test re-transacts the same program forms and task plans.
- The result is the same [e a v] whose value a collection has just swept.

Any root on the shared test store can run that collection, because every other shard's `seon drop` counts as a "gone" branch in each root's record.

## 3. Can a reader see a node vanish?

- **LMDB:** gives MVCC within one read transaction. konserve-lmdb runs one short transaction per get, so a multi-get tree walk is not a snapshot. It only matters for trees nobody reaches any more, such as a db value of a dropped branch.
- **Node and value caches:** nothing invalidates them across a sweep, in the collecting process or in any other. That is the observed bug.
- **Cross-process collection (latent, not reproduced):** `datahike.gc-guard` (the safe point) works within one process only. Its docstring says so: "ALL WRITERS FOR A DATABASE RUN IN ONE JVM". Shards on one store break that model in two ways:
  - **(P2)** process B finds V by `exists?`, then A's mark and sweep delete V before B's commit lands.
  - **(P3)** B's nodes are stamped before A's cutoff but land after A's mark.

  `docs/storage.md#collection` names the P2 window. Seon's guard, "no agent live", only counts this root's agents.

## Reproduction

Worktree `/Users/sean/src/seon-jolt/next-store-gc`, with its own stores under `target/gcrepro/`. Everything ran under `nice -n 15` and has exited.

**(P1) Deterministic, one process: `target/gcrepro/stale.clj`.**
- Steps:
  - a. branch `:x` commits `[E :gc/big V]` (V is 1.2 KB, so it is stored out of line);
  - b. branch `:w` commits the same datom (V is found by `exists?` and its key is cached);
  - c. both branches are deleted and `gc-storage` runs (15 keys swept, V among them);
  - d. branch `:y` commits the same datom.
- The first query on `:y` raises:
  ```
  :error datahike.index.persistent-set [603 20] Value not found in storage.
    data: {:type :value-not-found, :key #uuid "28794a35-…", :store #uuid "36dc2a93-…"}
    datahike.index.persistent-set/in-line (persistent_set.cljc:603)
  ```
- The error is identical in shape to the shard-3 failure.
- **Control: `target/gcrepro/control.clj`.** Step b commits nothing, so no key is cached. Step d finds V missing and writes it, and a fresh process reads `:y` fine (1 datom).
- Run with:
  `. bin/env; nice -n 15 env JOLT_PWD=$PWD target/jolt/jolt -A:test run target/gcrepro/stale.clj $PWD/target/gcrepro/s1`

**(P2/P3) Two processes, `collector.clj` and `writer.clj`, n = 100:**
- Process A, for each i: branch x-i, commit V_i, drop x-i, collect.
- Process B, on its own branch `:y`: waits until x-i exists and has been dropped, then commits V_i.
- Result: all 100 read back from a fresh process (`read.clj`), in two runs. This is a clear negative at n = 100 on a small store, where a mark takes milliseconds. Root asked me to stop there.

## The right fix

1. **Datahike fork (`reference-code/datahike`, branch `jolt/value-key-after-gc`), at the producer, about 15 lines.** A collection has to invalidate what `value-key` trusts, in every process:
   - `gc-storage!` increments a store key, `:datahike/collection` (a generation), in its last write. It also clears this process's value-key entries, together with the node entries for swept addresses.
   - `value-key` stores `[generation vk]` as its cache entry. A commit reads the generation once (one LMDB get, about 1 µs).
   - An entry from an older generation is a miss: the commit falls back to `exists?` and writes the value if it is missing.

   This fixes P1 in this process and in every other one. It keeps the measured saving: the generation is read once per commit, not once per value. Proof: `stale.clj` as a deftest in `seon.db-test`, red now and green after.
2. **Seon (`src/seon/db.clj` `collect!` / `src/seon/root.clj:300`), about 10 lines, plus its task.** Collect only when this root is the store's only writer process, as `docs/storage.md#collection` and `datahike.gc-guard` both require.
   - On the shared test and check stores many roots write at once, so either those roots do not collect (a documented `:seon.config.store/collect?` key, false under the test profile), or the "one root per store" rule is enforced (task `seon.db/two-processes-branch-one-store-at-once`).
   - This closes P2 and P3, which fix 1 alone does not.
3. **Small correction, about 2 lines.** Datahike's `:datahike/gc-without-local-writer` warning fires on every Seon collection, because the writer backend is `:sync`, not `:self`. It is noise that hides the real hazard. Treat `:sync` as local.

## Should dropping reclaim anything?

Yes, within its stated limits:
- AGENTS §4 says history is kept (`keep-history?`), and the collector keeps every commit of every live branch (`remove-before` is the epoch).
- §9 rules out deleting records. `seon.db/delete-main!`'s docstring (owner, 2026-10-22) limits deletion to the check and test mains a run made for itself; real agent runs are never deleted.
- So collecting a dropped test main is allowed. The bug is that the collection is not invisible to writers that still hold cached keys.
