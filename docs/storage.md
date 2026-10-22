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

Related: [source](source.md) (merges read a branch's history by commit), [DRAGONS](DRAGONS.md).
