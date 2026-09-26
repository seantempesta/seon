---
type: issue
status: closed (2026-09-26, Datahike fork fa0cc1a7; evidence below)
severity: high
created: 2026-09-24
tags: [issue, datahike, persistent-sorted-set, read-path, memory]
---

# A committed database holds its fused index roots only softly

## Closed 2026-09-26 (lane fix-eval-schema-reds-opus)

The smallest fix below landed in the Datahike fork as `fa0cc1a7` ("Pin fused index roots
strongly in the flushed live db value"): `db->stored`'s fusion block pins each flushed set's
root with `-seed-root!` (`reference-code/datahike/src/datahike/writing.cljc:153-172`). The
fork carries its regression, `test/datahike/test/fusion_root_pin_test.clj`. At HEAD (fork
`f3a3cb59`, classpath `:local/root`), on a scratch file store with Seon's configuration
(`tmp/fix-durability/probe.clj` `soft-root`), a committed live value's six fused roots are
held strongly (not `java.lang.ref.Reference`) and none is written as its own object. With any
Reference cleared and the indexes read through a fresh storage (an empty node cache), all six
read the same counts as before (eavt 2,007, avet 2,001, temporal-eavt 2,006).


## Observation

`Node not found in storage.` (`datahike/index/persistent_set.cljc:436-439`) was logged on
default at 2026-09-24T18:07:30Z, 21:14:14Z and 21:19:42Z (×5; the 21:19 read panicked
`seon.cluster.agent/turn-step` on `running-fixture`). The addresses were `6ab56616-49ae…`,
`6ab59219-bbd0…` and `6ab593d5-3eb4…`. Each squuid was minted 25 s to 4 min before its read
failed. None exists in the store now.

**On-disk data is intact.** Every one of the 23 roster heads has all six fused roots'
children present (19,556 `k/exists?` checks, 685 ms, all roots level 1). Because level-1
roots have leaf children, that check is complete.
Probe: `tmp/diagnose-node-not-found/head-check.clj`.

## Cause (confirmed for 18:07:30, reproduced on a copy of default's live value)

With `:fuse-index-roots? true`, a root is never written as a separate object. The root is
inlined in the commit record, and `commit!` drops it from the pending writes
(`datahike/writing.cljc` `fused-root-addresses`, lines ~384-409 and 493-495).
- The live set still carries that root's address in `_address`. Its `_root` is a
  `java.lang.ref.SoftReference` (store `:ref-type` is SOFT).
- Default's live `(:eavt @conn)` answered `_address 6ab59769-1165…`, `k/exists?` false, and
  `_root` held as a `SoftReference`.
- `PersistentSortedSet.root()` reads `_root` through `Settings.readReference`. If the
  reference is empty, it falls back to `IStorage.restore(_address)`
  (javap, persistent-sorted-set 0.4.137).
- `CachedStorage.restore` checks its LRU (`:store-cache-size` 1000), then konserve
  (`persistent_set.cljc:432-444`).
- Heap pressure clears the soft root. Once the LRU also evicts the root, every read of that
  database value fails for good, even though the data is on disk.

Evidence:
- `6ab56616-49ae…` is the fused `:avet-key` root of committed commit `6ab56616-12f0…`,
  which is in the lineage of `cluster-web-test` and three agent branches
  (`tmp/diagnose-node-not-found/root-match-all.clj`).
- It failed at 18:07:30Z, one minute before pid 70372 died of heap exhaustion
  (`seon.log:89843`), which is when soft references are cleared.
- Reproduction (`tmp/diagnose-node-not-found/soft-root-clear.clj`, read-only, on a
  `with-storage` copy): take default's live `:avet` set, set `_root` to an empty
  `SoftReference`, and bind a fresh `create-storage` (empty LRU). The read raised
  `Node not found in storage.` with `:address 6ab597f5-8268…`, the live head's own avet root.
  Using the live storage's LRU, the same read succeeded.

A database loaded from a record does not have this problem: `stored->db` pins the inlined
root strongly with `-seed-root!` (`persistent_set.cljc:232-236`). The hole is only in the
flushed, in-memory value that `commit!` and `force-branch!` hand back: the connection's
`@conn`, the writer's chained `db-after`, retained eval contexts, and test fixtures.

`6ab59219…` and `6ab593d5…` match no fused root in the bounded first-parent chains of the 23
roster branches. They are most likely roots of test branches that have since been retired.
Confirming that needs a scan of retired head and commit keys, which is heavy; root should
run it in a quiet window.

## Ruled out

- The collector did not delete them. No collection was logged, `seon.maintenance/collect!`
  is scheduled only on Sundays at 03:00 UTC (`schedule.clj:58-62`), and the tests collect
  their own directories.
- A swallowed `Error` did not cause them either
  (`published-head-references-never-written-index-nodes.md`):
  - the superv.async fork is pinned (`deps.edn:40-43`, `20d317e19`);
  - there is no writer shutdown or Error near 21:14 or 21:19;
  - 18:07:30 comes before the first heap OOM, and `-XX:+ExitOnOutOfMemoryError` terminates
    on that first one.
- It is not use-after-release. `release-materialized-db` releases secondary-index resources
  and deletes no konserve keys (`versioning.cljc:494-501`).

## Smallest fix (owner: Datahike fork, `reference-code/datahike`)

In `db->stored`'s fusion block (`writing.cljc:142-160`), after reading `-root-node` from each
flushed set, pin it with `(di/-seed-root! eavt' root)`, and do the same for the other five
sets. That is about 6 lines. It covers `commit!` and `force-branch!`, because both flush
through `db->stored`. A live database value then keeps a strong reference to its six roots,
which is the meaning of holding a value.

Wanted regression (in the fork's tests):
1. Use a fused-root file store and commit.
2. On `@conn`'s index set, clear `_root` to an empty `SoftReference`.
3. Evict the LRU (or bind a fresh storage).
4. Read. The read answers the datoms.
