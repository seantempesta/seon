# Storage

One durable store holds every branch as Datahike index trees in konserve, one content-addressed node per key, on LMDB. A commit costs the nodes it rewrites, so what it writes depends on how values are laid out, whether history is kept, and how nodes are encoded. The store is opened in `seon.db` (`seon.db/open!`, ```seon.db/store-cfg```). Its failure points are in [DRAGONS](DRAGONS.md), and its dials (`:seon.config.store/*`) in [config](config.md).

## The proof: what a commit costs

`seon.db-test/a-commit-costs-what-it-writes` says a turn committed by itself costs what the same turn costs in one big commit, plus one root-to-leaf path per index (32 KB at most). Its turns are realistic: each reply is real prose (the checkout's `docs/`), and each evaluated value is real source (its `src/`). A repeated letter compresses about 3× better than real turn text, which once made LZ4 look like the fix. Sample data must look like production data, or the numbers lie. Twenty turns, LMDB, branching factor 64:

| layout | one commit | per turn | bound | verdict |
|---|---|---|---|---|
| layout | one commit | per turn | bound | per turn / bound |
|---|---|---|---|---|
| values inline, no compression | 178 KB | 1,485 KB | 818 KB | 1.82 |
| values inline, LZ4 | 102 KB | 862 KB | 742 KB | 1.16 |
| out of line from 1,024 B, no compression | 111 KB | 376 KB | 751 KB | 0.50 |
| out of line from 1,024 B, LZ4 (the defaults) | 78 KB | 237 KB | 718 KB | 0.33 |

Inline values were the cause. Every commit rewrites the tail leaf of `:eavt` and `:aevt`, which holds each value inline, so a turn's text was written again by every later commit until its leaf split, and the waste grew with the run. With values inline and LZ4, per-turn cost against the bound is 0.83 at 10 turns, 1.16 at 20 and 1.74 at 40. Out of line it stays near flat: 0.29, 0.33 and 0.41. Every layout commits in about 1.7 ms per turn.

## History

A store keeps history when `:seon.config.store/keep-history?` is true, the default (v1's ruling 23, and Datahike's own default). With history, `d/history`, `as-of` and `since` work, a retraction stays a datom, and a transaction keeps its tx-meta. `:db/txInstant` is part of that tx-meta: Datahike applies tx-meta only when history is on, and drops it silently when it is off. The flag is fixed when a store is made (Datahike's `store-fixed-record-keys`). So ```seon.db/made-cfg``` adds it only when a store is created (`seon.db/open!`, and the test store), and ```seon.db/store-cfg``` never names it. A connect adopts what the store recorded, so a store made either way still opens: the standing root's store was made without history, and it keeps that layout. Proofs: `seon.db-test/the-store-keeps-history`, `seon.db-test/a-kept-store-keeps-the-history-it-was-made-with`.

Main's transaction facts reach the fork server's replica of main as well. The writer sends each commit's datoms (```seon.db/report->tx```) together with its transaction entity's facts (```seon.db/report->tx-meta```: its instant and any tx-meta), and the fork server applies the facts as its own transaction's `:tx-meta`. They can't travel as datoms on the transaction, because a transaction's id is its own branch's. Proof: `seon.procs-test/the-fork-server-step`.

Cost, measured 2026-10-22:
- Appended turns: the same with history on or off (20 turns: 1,598 vs 1,674 KB uncompressed). Datahike's temporal indexes hold only replaced and retracted datoms, and a turn only adds.
- Replacing one cardinality-one value: 13.5 → 20.7 KB per commit (1.5×), because the commit also writes a path in the temporal `:eavt` and `:aevt`. LZ4: 9.4 → 14.5 KB.

## Compression

konserve compresses every stored value (index nodes, values out of line, commits) with the codec `:seon.config.store/compressor` names: `:zstd` (the default), `:lz4` or `:none`. Each value's header names its codec, so a store holding several kinds reads them all. The codecs are `konserve.jolt.zstd` (libzstd's simple API, one frame per value at level 3) and `konserve.jolt.lz4` (liblz4's frame API), both over `jolt.ffi` in the [konserve fork](forks.md). Their libraries are built from pinned source, never taken from the system: `bin/build zstd` compiles `reference-code/zstd` (the official repository, a submodule at v1.5.7) and `bin/build lz4` the lz4 Chez itself vendors (`reference-code/chez/lz4`), into `target/lib`. `bin/env` exports `JOLT_ZSTD_LIB` and `JOLT_LZ4_LIB`, as it does for LMDB. Proof: `seon.db-test/stored-values-compress-through-the-codecs-the-build-made`.

Measured 2026-10-22 on data from the standing root's kept runs, each value compressed by itself as the store does:

| data (median size) | LZ4 | zstd-3 | zstd-19 | zstd-3 with a trained dictionary |
|---|---|---|---|---|
| model replies (0.4 KB) | 1.56× | 2.16× | 2.23× | 4.17× |
| eval records (0.5 KB) | 1.49× | 2.05× | 2.13× | 7.29× |
| source forms (0.5 KB) | 1.45× | 2.02× | 2.08× | 2.92× |
| task plans (0.2 KB) | 1.24× | 1.74× | 1.78× | 2.94× |

On 0.9 KB records, zstd-3 compresses at ~260 MB/s and decompresses at ~580 MB/s (LZ4: 1,330 and 7,550 MB/s). A 2 KB value takes 1–3 µs either way, well under a commit's fsync. On the proof's workload with values out of line, zstd writes 194 KB per 20 per-turn commits, against 233 KB with LZ4, at the same ~1.9 ms a commit. A dictionary trained on our own values compresses small ones 2–5× better, and is faster too. It waits until we have a corpus that represents the running system (owner, 2026-10-22); it would live in the store under its content key, named by each frame.

## Values out of line

A datom value at least `:seon.config.store/value-out-of-line-bytes` large (1,024, the default; `:never` keeps every value inline) is stored once under its own key inside Datahike's store, and the index nodes hold a reference to it. Datahike's `:index-config :value-out-of-line-bytes` does this in the node storage of [our fork](forks.md), `datahike.index.persistent-set/CachedStorage`:
- `store` writes a node whose keys hold such values with a `ValueRef` to each value's content key (hasch's uuid of the value, Datahike's own content address), and writes the value once: nothing is written when the store or the commit's queue already holds it. `:eavt` and `:aevt` share one copy.
- `restore` swaps the values back before the node enters the cache, so a live node never holds a reference.
- GC's mark (`mark`, `mark-unseen`) adds the keys each stored node references, so a value lives while a tree reaches it.

The size is a lower bound of the value's serialized size: a string's characters, a collection's elements. Hashing a 2 KB value and asking the store for it each cost about 15–18 µs on Jolt, and a commit rewrites the tail nodes with every large value in them. So the key of a value known to be stored (read back, or found by the store) is kept in the node cache under its datom, whose hash the datom caches. A value only queued for writing is not kept there, so a commit that fails before the write leaves no reference to a value the store lacks. Stored nodes describe themselves, so a store holding both kinds reads either, whatever the dial says now. Proof: `seon.db-test/a-large-value-lives-once-under-its-own-key`.

Thresholds measured at 20 turns with LZ4: 4,096 B moves nothing (a turn's values are 1.5–2.5 KB) and costs 862 KB per turn; 1,024 B costs 237 KB; 256 B costs 223 KB (it also moves the basis, ~400 B). 1,024 is the default: it takes nearly all the gain, and every value it moves is its own LMDB key.

## Plain values

A value that is plain data (a map, vector, set or list of strings, numbers, keywords, symbols, uuids and instants) is stored as data: its attribute is `:db.type/data` in [our Datahike](forks.md), and the store's serializer (jfasl) writes it with no text in between. Seon marks such an attribute `{:seon.db/data true}` on its schema (```seon.schema.datahike/declaration```): a turn's basis, calls and split, a task's evidence, an attempt's failures and errors, the config's maps, merge entries. `datahike.datom/compare-value` gives plain data a total order consistent with `=`, so such values sit in an index like any other. A kept store that holds an attribute as another value type is refused at open with `:seon.db/types-differ`, naming the attributes, because a value type is fixed once installed. Proofs: `seon.db-test/a-kept-store-whose-types-differ-is-refused`, and every test that writes these attributes.

Measured on 5,006 real eval records: jfasl is the same size as EDN text (4.65 vs 4.67 MB). A record reads in 1.3 µs instead of 11 µs and writes in 1.6 µs instead of 14.4 µs, and instants, uuids and keywords come back as themselves. It compresses about 10% less well than EDN text without a dictionary (zstd-3: 1.84× vs 2.05×), 4% less with one.

## Collection

Root collects the store while it is the store's only writer: at quiescence, with no agent live (owner, 2026-10-22, simplest first). The reconciler asks once per quiet spell (```seon.reconcile/quiet```), root refuses while an agent is live (`seon.root/api-proc`, `:collect`), and the writer runs ```seon.db/collect!```:
- When a branch named in main's `:seon.root/collected` record is gone, it runs `datahike.api/gc-storage`. A new main with no record yet only records the branches it sees: every check step boots a new main on one store of hundreds of branches, and a full sweep at its first quiescence held its spawns behind root's writer for seconds (scale 8.2 s to ready, 2026-10-22). That sweeps every key no branch reaches: nodes, and the values out of line that the mark keeps while a tree reaches them.
- When branches were only added, it records them as seen and sweeps nothing. So a branch that exists at one quiescence and is gone at a later one gets reclaimed.
- The record (`{:swept :ms :branches :gone}`) is committed on main through the writer's batch.

Seon's `:sync` writer runs the collection itself, not as a transaction (fork `594815d`). Proofs: `seon.db-test/a-collection-runs-when-a-branch-is-gone`, `seon.db-test/gc-runs-through-the-sync-writer`, `seon.procs-test/a-collection-waits-until-no-agent-is-live`, `seon.reconcile-test/quiescence-is-reported-when-nothing-can-move`.

With history kept and runs kept, garbage comes from deleted branches: check and test mains, which `seon drop` deletes. Measured 2026-10-22: on a copy of the standing store (297 branches, 159,152 keys), a full collection took 11 s and swept 5,148 keys (3%). On a fresh root's store, a deleted branch was reclaimed at the next quiescence (19 keys, 488 ms).

Why only with no agent live: every commit is one LMDB write transaction (nodes, values, commit record and head), and LMDB's writer lock serializes commits from every process. But a commit can reuse a value out of line that the store held when its node was flushed. A collection running at that moment could sweep the value, because no branch reached it. Collecting concurrently would need a mark outside the lock, then a short delta mark and sweep inside one write transaction, plus commits that re-assert reused values in their own transaction. That is the design to build when collection has to run beside live agents.

Related: [source](source.md) (merges read a branch's history by commit), [DRAGONS](DRAGONS.md).
