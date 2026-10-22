# Storage

One durable store holds every branch as Datahike index trees in konserve, one content-addressed node per key, on LMDB. A commit costs the nodes it rewrites, so what it writes depends on how values are laid out, whether history is kept, and how nodes are encoded. The store is opened in `seon.db` (`seon.db/open!`, ```seon.db/store-cfg```). Its failure points are in [DRAGONS](DRAGONS.md), and its dials (`:seon.config.store/*`) in [config](config.md).

## The proof: what a commit costs

`seon.db-test/a-commit-costs-what-it-writes` says a turn committed by itself costs what the same turn costs in one big commit, plus one root-to-leaf path per index (32 KB at most). Its turns are realistic: each reply is real prose (the checkout's `docs/`), and each evaluated value is real source (its `src/`). A repeated letter compresses about 3× better than real turn text, which once made LZ4 look like the fix. Sample data must look like production data, or the numbers lie. Twenty turns, LMDB, branching factor 64:

| layout | one commit | per turn | bound | verdict |
|---|---|---|---|---|
| values inline, no compression | 185 KB | 1,674 KB | 840 KB | 2.0× over |
| values inline, LZ4 | 106 KB | 967 KB | 761 KB | 1.3× over |

Inline values are the cause. Every commit rewrites the tail leaf of `:eavt` and `:aevt`, which holds each value inline, so a turn's text is written again by every later commit until its leaf splits.

## History

A store keeps history when `:seon.config.store/keep-history?` is true, the default (v1's ruling 23, and Datahike's own default). With history, `d/history`, `as-of` and `since` work, a retraction stays a datom, and a transaction keeps its tx-meta. `:db/txInstant` is part of that tx-meta: Datahike applies tx-meta only when history is on, and drops it silently when it is off. The flag is fixed when a store is made (Datahike's `store-fixed-record-keys`). So ```seon.db/made-cfg``` adds it only when a store is created (`seon.db/open!`, and the test store), and ```seon.db/store-cfg``` never names it. A connect adopts what the store recorded, so a store made either way still opens: the standing root's store was made without history, and it keeps that layout. Proofs: `seon.db-test/the-store-keeps-history`, `seon.db-test/a-kept-store-keeps-the-history-it-was-made-with`.

Cost, measured 2026-10-22:
- Appended turns: the same with history on or off (20 turns: 1,598 vs 1,674 KB uncompressed). Datahike's temporal indexes hold only replaced and retracted datoms, and a turn only adds.
- Replacing one cardinality-one value: 13.5 → 20.7 KB per commit (1.5×), because the commit also writes a path in the temporal `:eavt` and `:aevt`. LZ4: 9.4 → 14.5 KB.

## Node compression

When `:seon.config.store/compressor` is `:lz4` (the default), konserve stores every value (index nodes and commits) as an LZ4 frame, and the value's header names its compressor, so a store holding both kinds reads them all. The codec is `konserve.jolt.lz4`, liblz4's frame API over `jolt.ffi`, in the [konserve fork](forks.md). Its library is built from source, never taken from the system: `bin/build lz4` compiles the lz4 that Chez itself vendors (`reference-code/chez/lz4`, 1.10.0 at Chez's pin) into `target/lib/liblz4.dylib`, and `bin/env` exports `JOLT_LZ4_LIB`, as it does for LMDB (`bin/build lmdb`). Proof: `seon.db-test/nodes-compress-through-the-lz4-the-build-made`.

Measured 2026-10-22 on the checkout's own src and docs: ratio 1.41 at 4 KB, 1.65 at 64 KB, 2.10 at 1.1 MB. It compresses at 600–800 MB/s and decompresses at 1.8–1.9 GB/s, the same order as Homebrew's build of the same version. That is well under a commit's ~5 ms fsync. On the proof's workload LZ4 alone takes per-turn commits from 1,674 to 967 KB (1.7×), which still fails the bound: the values must leave the leaf.

Related: [source](source.md) (merges read a branch's history by commit), [DRAGONS](DRAGONS.md).
