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

Cost, measured 2026-10-22:
- Appended turns: the same with history on or off (20 turns: 1,598 vs 1,674 KB uncompressed). Datahike's temporal indexes hold only replaced and retracted datoms, and a turn only adds.
- Replacing one cardinality-one value: 13.5 → 20.7 KB per commit (1.5×), because the commit also writes a path in the temporal `:eavt` and `:aevt`. LZ4: 9.4 → 14.5 KB.

## Node compression

When `:seon.config.store/compressor` is `:lz4` (the default), konserve stores every value (index nodes and commits) as an LZ4 frame, and the value's header names its compressor, so a store holding both kinds reads them all. The codec is `konserve.jolt.lz4`, liblz4's frame API over `jolt.ffi`, in the [konserve fork](forks.md). Its library is built from source, never taken from the system: `bin/build lz4` compiles the lz4 that Chez itself vendors (`reference-code/chez/lz4`, 1.10.0 at Chez's pin) into `target/lib/liblz4.dylib`, and `bin/env` exports `JOLT_LZ4_LIB`, as it does for LMDB (`bin/build lmdb`). Proof: `seon.db-test/nodes-compress-through-the-lz4-the-build-made`.

Measured 2026-10-22 on the checkout's own src and docs: ratio 1.41 at 4 KB, 1.65 at 64 KB, 2.10 at 1.1 MB. It compresses at 600–800 MB/s and decompresses at 1.8–1.9 GB/s, the same order as Homebrew's build of the same version. That is well under a commit's ~5 ms fsync. On the proof's workload, LZ4 alone takes per-turn commits from 1,485 to 862 KB (1.7×), which still fails the bound: the values have to leave the leaf.

## Values out of line

A datom value at least `:seon.config.store/value-out-of-line-bytes` large (1,024, the default; `:never` keeps every value inline) is stored once under its own key inside Datahike's store, and the index nodes hold a reference to it. Datahike's `:index-config :value-out-of-line-bytes` does this in the node storage of [our fork](forks.md), `datahike.index.persistent-set/CachedStorage`:
- `store` writes a node whose keys hold such values with a `ValueRef` to each value's content key (hasch's uuid of the value, Datahike's own content address), and writes the value once: nothing is written when the store or the commit's queue already holds it. `:eavt` and `:aevt` share one copy.
- `restore` swaps the values back before the node enters the cache, so a live node never holds a reference.
- GC's mark (`mark`, `mark-unseen`) adds the keys each stored node references, so a value lives while a tree reaches it.

The size is a lower bound of the value's serialized size: a string's characters, a collection's elements. Hashing a 2 KB value and asking the store for it each cost about 15–18 µs on Jolt, and a commit rewrites the tail nodes with every large value in them. So the key of a value known to be stored (read back, or found by the store) is kept in the node cache under its datom, whose hash the datom caches. A value only queued for writing is not kept there, so a commit that fails before the write leaves no reference to a value the store lacks. Stored nodes describe themselves, so a store holding both kinds reads either, whatever the dial says now. Proof: `seon.db-test/a-large-value-lives-once-under-its-own-key`.

Thresholds measured at 20 turns with LZ4: 4,096 B moves nothing (a turn's values are 1.5–2.5 KB) and costs 862 KB per turn; 1,024 B costs 237 KB; 256 B costs 223 KB (it also moves the basis, ~400 B). 1,024 is the default: it takes nearly all the gain, and every value it moves is its own LMDB key.

Related: [source](source.md) (merges read a branch's history by commit), [DRAGONS](DRAGONS.md).
