# Storage

One durable store holds every branch as Datahike index trees in konserve, one content-addressed node per key, on LMDB. A commit costs the nodes it rewrites, so what it writes depends on how values are laid out, whether history is kept, and how nodes are encoded. The store is opened in `seon.db`: `seon.db/open!` makes and seeds a store that does not exist (```seon.db/store-cfg```, ```seon.db/made-cfg```), makes root's main branch from `:db`'s head when the store lacks it, shares the id page ([ids](#ids)), installs the attributes the store lacks and syncs when any of these wrote. Its failure points are in [failure points](failure-points.md), and its config keys (`:seon.config.store/*`) in [config](config.md).

## Identity

A store is named by the id it records, never by where it lies, so a kept run opens wherever it is copied, moved or archived. ```seon.db/store-cfg``` reads the id from the store's `:db` head (```seon.db/stored-id```, Datahike's own read path, 0.8 ms) and mints `(hasch/uuid path)` only for a store it is about to make (`seon.db/open!` creates that store from the config ```seon.db/made-cfg``` derives); Datahike's connect refuses a config whose id differs from the stored one (`:store-identity-mismatch`). A copy keeps its original's id, as a Datahike replica does, so one process opens one of them. A connection's db names the store where it lies: our Datahike's `datahike.connector/deref-conn` keeps the connection's own config, as connect does, so `(:config @conn)` after a move names the new path (task `seon.db/deref-keeps-the-connections-config`). Proof: `seon.db-test/a-moved-store-opens`.

## The map is address space

LMDB's map (`:seon.config.store/lmdb-map-bytes`, 64 GiB) is address space only: an open or a fork costs the same at 1 or 64 GiB, and the file grows only as it is used. Anything that walks a map page by page pays for its size, under the file's one VM-object lock that every process mapping the store shares: the memory probe once did (`VM_REGION_EXTENDED_INFO` on every region, ~1.5 ms per GiB), and 100 agents took 18 s to be ready instead of 0.65 s. `native/probe.c` now reads each region's O(1) short info and skips file-backed, unshadowed regions, which hold none of the process's own pages (`seon.lib-test/the-memory-probe-costs-what-the-process-holds-not-what-it-maps`). Not this: a smaller map; at 1 GiB the kept store hit Datahike's fatal-commit-error.

## The proof: what a commit costs

`seon.db-test/a-commit-costs-what-it-writes` says a turn committed by itself costs what the same turn costs in one big commit, plus one root-to-leaf path per index (32 KB at most). Its turns are realistic: each reply is real prose (the checkout's `docs/`), and each evaluated value is real source (its `src/`). A repeated letter compresses about 3× better than real turn text, which once made LZ4 look like the fix. Sample data must look like production data, or the numbers lie. Twenty turns, LMDB, branching factor 64:

| layout | one commit | per turn | bound | per turn / bound |
|---|---|---|---|---|
| values inline, no compression | 178 KB | 1,485 KB | 818 KB | 1.82 |
| values inline, LZ4 | 102 KB | 862 KB | 742 KB | 1.16 |
| out of line from 1,024 B, no compression | 111 KB | 376 KB | 751 KB | 0.50 |
| out of line from 1,024 B, LZ4 | 78 KB | 237 KB | 718 KB | 0.33 |

Inline values were the cause. Every commit rewrites the tail leaf of `:eavt` and `:aevt`, which holds each value inline, so a turn's text was written again by every later commit until its leaf split, and the waste grew with the run. With values inline and LZ4, per-turn cost against the bound is 0.83 at 10 turns, 1.16 at 20 and 1.74 at 40. Out of line it stays near flat: 0.29, 0.33 and 0.41. Every layout commits in about 1.7 ms per turn. The default layout (out of line from 1,024 B) with the default codec, zstd, writes less again ([compression](#compression)).

## Entity ids

The entity whose unique attribute holds a value is found by `seon.db/eid`: one seek in that attribute's AVET index, never a Datalog query (a query planned per call cost ~95 µs and 100 KB at 100 tasks). It is the one lookup; every namespace that needs an entity id by value calls it. Reading an entity's facts needs no id first: a pull by lookup ref (`(d/pull db attrs [:seon.root/id :root])`) answers nil when no entity holds the value, so root's facts, a task, a run or an error record are pulled directly.

## Ids

Every entity id and every transaction id is issued once across the whole store: every branch, every process. So a datom names the same entity and the same transaction wherever it travels, onto main by a merge or into a query over several branches. The ids come from one page every process writing the store shares (```seon.db/ids!```, `native/ids.c`): two 64-bit words, the last entity id and the last transaction id issued, mapped from the file `ids` beside `data.mdb` (an anonymous page for a store in memory). A process forked from root (the fork server, an agent, a clone) maps the same page, so it needs nothing at fork. Datahike takes them through its id source (`datahike.ids`, `datahike.writing/set-ids!`):
- a transaction takes its id when it begins, and a branch's t (`:max-tx`) is its last transaction's id: increasing on each branch, with gaps where other branches took ids;
- each fresh entity id is one atomic fetch-add. Measured 2026-10-23: 28-34 ns an id, 200-240 ns with 10 processes contending, 30M ids with none lost or repeated; an LMDB counter key cost 9.6 us alone and ~500 us contended ([notes/global-ids/MEASURED.md](../notes/global-ids/MEASURED.md) §6);
- the page is moved past every branch head's ids when a store opens (O(branches) head reads, once), so an id issued but never committed is a gap, never reused, and a page lost in a crash is rebuilt from the heads;
- an explicit id in tx-data (a carried datom's) moves the page past it.

**Content ids.** An entity with a program identity (`:form/id`, `:var/name`, `:ns/name`, `:method/key`, `:impl/key`, `:schema/key`, `:schema.file/path`: `{:seon.db/content-id true}` in seon.facts.edn, Datahike's `:db/contentId`) takes its id from its identity: `datahike.db.transaction/content-eid`, the hash of `[attribute value]` (hasch's uuid, Datahike's content hash: 17-19 us a call on Jolt, measured 2026-10-23) taken into `[ce0, emax]` = `[2^40, 2^60)`. Every branch that mints one identity mints one entity, so a merge carries its datoms as they are, with no remapping (owner's ruling 2026-10-23; notes/content-eids-2026-10-23.md). The partition, every id a fixnum: counter entity ids below `tx0` (2^29), transaction ids in `[tx0, 2^40)`, content ids above. Datahike mints them before anything resolves (`content-tempids`), so a ref to a tempid made before its entity map takes the content id too, and Seon's minting code is unchanged. An identity names its entity for good: asserting another value of it on that entity, a rename or a hash collision (about 4e-7 at a million identities), is refused `:entity-id/collision`, never merged. `:form/id` itself is now hasch's uuid of the namespace and text (it was Clojure's 32-bit `hash`). Cost, measured 2026-10-23 at 110k datoms: nodes written per commit 0.97x the counter's with 1 new identity, 1.51x with 2 and 4.50x with 10 (commit 3.4 -> 3.9, 3.8 -> 6.7, 8.2 -> 20.2 ms); commits that add no identity write the same. Proofs: `seon.db-test/an-identity-names-its-entity-on-every-branch`, `seon.db-test/content-ids-cost-the-writes-the-model-says`.

**Several roots on one store.** Ids no longer stand in the way: every process maps the same page, so two roots issue distinct ids. Two things still do, so a store has one root:
- **The branch roster.** `d/branch!` appends to it with a read and three writes under a lock of its own process only, so two processes branching at once can drop one branch from `d/branches` (task `seon.db/two-processes-branch-one-store-at-once`).
- **Collection.** A root collects only as the store's only writer ([collection](#collection)), and it cannot see another root's live agents.

A speculation (`d/with`) takes no ids. The fork server's replica of main is each stored commit of main, read from the store (```seon.writer/stored!``` sends the notice), so its t is main's. Proofs: `seon.db-test/each-id-is-issued-once-across-branches`, `seon.db-test/a-reopened-store-issues-past-every-head`, and `bin/check ids` (two agents' processes). This replaced per-agent leases of entity ids, whose blocks held about 5,400 agents per store and whose transaction ids were per branch (two branches forked at one t gave their first transactions one id).

## Merges read what changed

A merge decides what merges from the datoms that differ, never by reading whole programs. ```seon.merge/delta-three-way``` takes the datoms that differ between the merge base and each side (`datahike.api/datoms-diff`, which skips every subtree the two trees share by address), the program keys their entities bear on either side (```seon.merge/touched-keys```: a form's own key and its definitions', a definition's own and its form's, a schema entry's, declared data), and reads each key's content on base, ours and theirs by index seeks (```seon.merge/program-of```). It answers what `seon.merge/three-way` over three whole programs answers: every case of `seon.merge-test` checks both. The writer's merge and `:branch-diff`, an agent's `merge-main!`, a turn's contract gaps and `seon.merge/unmerged` read this way; ```seon.merge/program``` stays for tests and `mg/check`.

What merges is then carried as it is (```seon.merge/merge-tx```, ```seon.merge/carried-tx```): the datoms the branch wrote for the keys that merge, with their own entity ids and transaction ids, its retractions first (so a card-one change writes no extra retraction under the merge's transaction), and the facts of the transactions that made them (their instants, their runtime). Ids are issued once across the store and identities take [content ids](#ids), so on main they name the same entities and transactions: a merge adds what the branch wrote, never a copy, and the same datom is one datom in every branch's history. Platform records (turns, evaluations, runs) bear no program key, so they stay where they were written. The writer's octopus merge and an agent's `merge-main!` both merge this way; a conflict's unmerged entry is still written by the merge. Datahike keeps a carried datom's tx (`transact-add`, `transact-retract-datom`). Proof: `seon.merge-test/a-merge-carries-the-branchs-own-datoms`. This deleted the old apply-tx and its five setters (94 lines), which re-transacted each key's content by identity under the merge's transaction.

Measured 2026-10-23 (notes/global-ids/MEASURED.md §5): on a 115k-datom main, a merge's three program reads took 6.6 s of 6.7 s. `seon.merge-cost-test/a-three-way-costs-the-changes-not-the-program` holds the three-way flat at about 7 ms from 100 to 10,000 forms, with one form changed on each side. That proof keeps a store on disk, because the in-memory test store gives index nodes no addresses, so a diff there shares nothing and reads everything.

## The union

Every datom any branch committed is held once in U, one ordinary Datahike db, and queried with plain `d/q`, `d/pull` and `d/entity` over `(d/history U)` (```seon.union```; root: `(my.root/union sys)`). Ids are issued once and identities take content ids ([ids](#ids)), and merges carry datoms as they are ([merges](#merges-read-what-changed)), so the union is a set union with nothing to resolve: an equal datom is the same datom. What is true now on one branch stays that branch's head; U answers what happened across branches, and each datom's transaction names its branch (`:seon.tx/branch`, in every transaction's tx-meta, ```seon.db/tx-meta```). U holds assertions in its current indexes and retractions in its temporal ones, and its schema lifts every attribute to cardinality many with no uniqueness (```seon.union/lifted-schema```), since constraints belong to a branch.

Root's writer keeps it: built when first asked (`:union`; main's history, then each branch from its merge base), then every main commit and every agent's commit notice adds that branch's datoms since the commit U holds (```seon.union/catch-up```: the datoms that differ, by `datoms-diff`, with the branch's retractions from its history). No poll: U moves when a commit does.

The owner's example, root's message on main and the agent's reply on its branch, is one query (`seon.union-test/conversation`):

```clojure
(d/q '[:find ?body ?reply ?branch :in $ ?agent
       :where [?m :seon.message/to ?agent] [?m :seon.message/id ?id] [?m :seon.message/body ?body]
              [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply] [?tx :seon.tx/branch ?branch]]
     (my.root/union sys) "demo/f#1")
```

Measured 2026-10-23 (REPL, LMDB, main 46,708 datoms, each branch one turn of ~15 datoms; notes/global-ids/bench/um.clj):

| | 10 branches | 100 branches |
|---|---|---|
| build: main's history, then each branch | 991 ms | 1,274 ms (about 3 ms a branch) |
| keeping it current: one branch's commit | 0.93 ms | 1.30 ms |
| one agent's conversation | 0.75 ms | 0.63 ms |
| every conversation | 0.87 ms | 4.2 ms (100 rows) |

U lives in root's writer, in memory: about main's history plus what branches add. Persisting it under one store key, so a booted root does not rebuild it, waits until a measurement asks; the rebuild is about 1 s at 50k datoms. Proofs: `seon.union-test/a-message-and-its-reply-on-two-branches-are-one-query`, `seon.procs-test/the-writer-keeps-the-union-current`.

## History

A store keeps history when `:seon.config.store/keep-history?` is true, the default (v1's ruling 23, and Datahike's own default). With history, `d/history`, `as-of` and `since` work, a retraction stays a datom, and a transaction keeps its tx-meta. ``:db/txInstant`` is part of that tx-meta: Datahike applies tx-meta only when history is on, and drops it silently when it is off. The flag is fixed when a store is made (Datahike's `store-fixed-record-keys`). So ```seon.db/made-cfg``` adds it only when a store is created (`seon.db/open!`, and the test store), and ```seon.db/store-cfg``` never names it. A connect adopts what the store recorded, so a store made either way still opens, and keeps the layout it was made with. Proofs: `seon.db-test/the-store-keeps-history`, `seon.db-test/a-kept-store-keeps-the-history-it-was-made-with`.

Every transaction Seon makes names the runtime that made it and its branch in its tx-meta: `:seon.tx/branch` and `:seon.tx/runtime`, the latter Jolt's own key for compiled units (`jolt.host/runtime-fingerprint`: Jolt's version, its runtime fingerprint over the binary's image and Chez's boot files, its trace tag), from ```seon.db/tx-meta```, at the writer's group commit and merges, an agent's transactions and merge-main, and a store's seed. Only the transactions that install the two tx-meta attributes lack them: ```seon.db/install-missing!``` installs those attributes alone, first, in a new store and in a kept one made before them, then every other attribute the store lacks (or holds without its declared `:db/contentId`) in one transaction that carries tx-meta, and answers how many it added; nothing missing writes nothing. A runtime Jolt cannot name is refused by `tx-meta`'s contract, never recorded as no runtime. Two inline datoms per transaction (the runtime's is about 90 B). Proofs: `seon.procs-test/the-writers-transactions-name-their-runtime`, `seon.procs-test/a-writers-merge-names-its-runtime`, `seon.agent-test/an-agents-transactions-name-their-runtime`, `seon.agent-test/after-merge-main-the-agent-judges-against-the-new-base` (its merge commit), `seon.db-test/a-kept-store-from-before-the-runtime-attribute-opens`.

Main's transaction facts reach the fork server's replica of main as well, since the replica is main's stored commit itself: the writer sends one notice per stored commit (```seon.writer/stored!```: the commit's id and its transactions' ops, in order), and the fork server, having opened the store after fork as agents do (```seon.db/after-fork!```), loads that commit (`d/commit-as-db`) and runs ```seon.program/load-changes!``` over the ops; nothing is replayed ([ids](#ids)). Proofs: `seon.procs-test/the-fork-servers-replica-is-main-at-each-commit`, `seon.procs-test/the-fork-server-step`.

A transaction's `t` is its id, issued once across the store ([ids](#ids)): a branch's `max-tx` is its last transaction's id, increasing on that branch with gaps where other branches took ids, and one `t` names one transaction on every branch, so a merged datom keeps the `t` it was written at ([merges](#merges-read-what-changed)). `t`s are ordered on one branch; across branches compare by commit (`seon.db/branch-base` reads the merge base by commit). v1's merges failed on per-branch ids: 774 ids named different transactions.

Cost:
- Appended turns: the same with history on or off (20 turns: 1,598 vs 1,674 KB uncompressed). Datahike's temporal indexes hold only replaced and retracted datoms, and a turn only adds.
- Replacing one cardinality-one value: 13.5 → 20.7 KB per commit (1.5×), because the commit also writes a path in the temporal `:eavt` and `:aevt`. LZ4: 9.4 → 14.5 KB.

## Compression

konserve compresses every stored value (index nodes, values out of line, commits) with the codec `:seon.config.store/compressor` names: `:zstd` (the default), `:lz4` or `:none`. Each value's header names its codec, so a store holding several kinds reads them all. The codecs are `konserve.jolt.zstd` (libzstd's simple API, one frame per value at level 3) and `konserve.jolt.lz4` (liblz4's frame API), both over `jolt.ffi` in the [konserve fork](forks.md). Their libraries are built from pinned source, never taken from the system: `bin/build zstd` compiles `reference-code/zstd` (the official repository, a submodule at v1.5.7) and `bin/build lz4` the lz4 Chez itself vendors (`reference-code/chez/lz4`), into `target/lib`. `bin/env` exports `JOLT_ZSTD_LIB` and `JOLT_LZ4_LIB`, as it does for LMDB. Proof: `seon.db-test/stored-values-compress-through-the-codecs-the-build-made`.

Measured on data from kept runs, each value compressed by itself as the store does:

| data (median size) | LZ4 | zstd-3 | zstd-19 | zstd-3 with a trained dictionary |
|---|---|---|---|---|
| model replies (0.4 KB) | 1.56× | 2.16× | 2.23× | 4.17× |
| eval records (0.5 KB) | 1.49× | 2.05× | 2.13× | 7.29× |
| source forms (0.5 KB) | 1.45× | 2.02× | 2.08× | 2.92× |
| task plans (0.2 KB) | 1.24× | 1.74× | 1.78× | 2.94× |

On 0.9 KB records, zstd-3 compresses at ~260 MB/s and decompresses at ~580 MB/s (LZ4: 1,330 and 7,550 MB/s). A 2 KB value takes 1–3 µs either way, well under a commit's fsync. On the proof's workload with values out of line, zstd writes 194 KB per 20 per-turn commits, against 233 KB with LZ4, at the same ~1.9 ms a commit. A dictionary trained on our own values compresses small ones 2–5× better, and is faster too. It is not used: it needs a corpus that represents the running system.

## Values out of line

A datom value at least `:seon.config.store/value-out-of-line-bytes` large (1,024, the default; `:never` keeps every value inline) is stored once under its own key inside Datahike's store, and the index nodes hold a reference to it. Datahike's `:index-config :value-out-of-line-bytes` does this in the node storage of [our fork](forks.md), ``datahike.index.persistent-set/CachedStorage``:
- `store` writes a node whose keys hold such values with a `ValueRef` to each value's content key (hasch's uuid of the value, Datahike's own content address), and writes the value once: nothing is written when the store or the commit's queue already holds it. `:eavt` and `:aevt` share one copy.
- `restore` swaps the values back before the node enters the cache, so a live node never holds a reference.
- GC's mark (`mark`, `mark-unseen`) adds the keys each stored node references, so a value lives while a tree reaches it.

The size is a lower bound of the value's serialized size: a string's characters, a collection's elements. Hashing a 2 KB value and asking the store for it each cost about 15–18 µs on Jolt, and a commit rewrites the tail nodes with every large value in them. So the key of a value known to be stored (read back, or found by the store) is kept in the node cache under its datom, whose hash the datom caches. A value only queued for writing is not kept there, so a commit that fails before the write leaves no reference to a value the store lacks. Stored nodes describe themselves, so a store holding both kinds reads either, whatever the config key says now. Proof: `seon.db-test/a-large-value-lives-once-under-its-own-key`.

Thresholds measured at 20 turns with LZ4: 4,096 B moves nothing (a turn's values are 1.5–2.5 KB) and costs 862 KB per turn; 1,024 B costs 237 KB; 256 B costs 223 KB (it also moves the basis, ~400 B). 1,024 is the default: it takes nearly all the gain, and every value it moves is its own LMDB key.

## Plain values

A value that is plain data (a map, vector, set or list of strings, numbers, keywords, symbols, uuids and instants) is stored as data: its attribute is ``:db.type/data`` in [our Datahike](forks.md), and the store's serializer (jfasl) writes it with no text in between. Seon marks such an attribute `{:seon.db/data true}` on its schema (```seon.schema.datahike/declaration``` answers the Datahike attribute map for it, or the `:seon.schema/unstorable` error that says why a schema has none): a turn's basis, calls and split, a task's evidence, a run's failures and errors, the config's maps, merge entries. `datahike.datom/compare-value` gives plain data a total order consistent with `=`, so such values sit in an index like any other. A kept store that holds an attribute as another value type is refused at open with `:seon.db/types-differ`, naming the attributes, because a value type is fixed once installed. Proofs: `seon.db-test/a-kept-store-whose-types-differ-is-refused`, and every test that writes these attributes.

Measured on 5,006 real eval records: jfasl is the same size as EDN text (4.65 vs 4.67 MB). A record reads in 1.3 µs instead of 11 µs and writes in 1.6 µs instead of 14.4 µs, and instants, uuids and keywords come back as themselves. It compresses about 10% less well than EDN text without a dictionary (zstd-3: 1.84× vs 2.05×), 4% less with one.

## Collection

Root collects the store while it is the store's only writer: at quiescence, with no agent live, and only when no other root writes the store. The reconciler asks once per quiet spell (```seon.reconcile/quiet```, which also records the spell on main as `:seon.root/quiescent` and does not await either answer), root refuses while an agent is live (`seon.root/api-proc`, `:collect`), and the writer runs ```seon.db/collect!```:
- When `:seon.config.store/sole-writer?` is false, it sweeps nothing and records nothing (`{:skipped true :shared true}`). The key is false under the `:scripted` profile, which is the profile of bin/test's shards and bin/check's steps, whose roots share one store. Production runs one root per store (task `seon.db/two-processes-branch-one-store-at-once`).
- When a branch named in main's `:seon.root/collected` record is gone, it runs `datahike.api/gc-storage`. A new main with no record yet only records the branches it sees: every check step boots a new main on one store of hundreds of branches, and a full sweep at its first quiescence held its spawns behind root's writer for seconds (`bin/check scale`: 8.2 s to ready). That sweeps every key no branch reaches: nodes, and the values out of line that the mark keeps while a tree reaches them.
- When branches were only added, it records them as seen and sweeps nothing. So a branch that exists at one quiescence and is gone at a later one gets reclaimed.
- The record (`{:swept :ms :branches :gone}`) is committed on main through the writer's batch.

Seon's `:sync` writer runs the collection itself, not as a transaction (fork `594815d`). Proofs: `seon.db-test/a-collection-runs-when-a-branch-is-gone`, `seon.db-test/a-root-that-shares-its-store-never-collects`, `seon.db-test/gc-runs-through-the-sync-writer`, `seon.procs-test/a-collection-waits-until-no-agent-is-live`, `seon.reconcile-test/quiescence-is-reported-when-nothing-can-move`.

History is kept, so garbage comes from deleted branches: check and test mains, which the run that made them deletes (```seon.db/delete-main!```). Measured on a copy of a kept store (297 branches, 159,152 keys), a full collection took 11 s and swept 5,148 keys (3%). On a fresh root's store, a deleted branch was reclaimed at the next quiescence (19 keys, 488 ms).

Why only with no agent live: every commit is one LMDB write transaction (nodes, values, commit record and head), and LMDB's writer lock serializes commits from every process. But a commit can reuse a value out of line that the store held when its node was flushed. A collection running at that moment could sweep the value, because no branch reached it. Collecting concurrently would need a mark outside the lock, then a short delta mark and sweep inside one write transaction, plus commits that re-assert reused values in their own transaction. Collecting beside live agents is not built.

A live agent holds its branch: root refuses a second spawn on a branch a live agent holds, and caps the live agents at `:seon.config.root/max-agents` ([root](root.md#holders-and-refused-spawns)).

Why only as the only writer: Datahike's collection sees the in-flight commits of its own process only (`datahike.gc/gc-storage!`'s safe point). A commit in another process can find a value stored, and then that value is swept before its commit lands. Or its nodes can be written before the cutoff but land after the mark. Neither was reproduced at n = 100 (the store-gc bug note (notes/store-gc-bug-2026-10-23.md in its worktree, not yet on main)). One hole was a real bug, and it is fixed in [our Datahike](forks.md) (`jolt/gc-cache-epoch`). The node cache keeps a value's key under its datom, and that key is trusted only in the collection generation it was found in. `gc-storage!` counts up the store's generation (`datahike.index.interface/collection-key`) after its sweep, so every process asks the store again. Before the fix, an [e a v] reused after a collection (a dropped main's ids are minted again) named a value the sweep had deleted, and its node failed on read with `:value-not-found`. Proof: `datahike.test.gc-value-key-test`.

## Compiled code

The store holds definitions (`:form/src`), never compiled code: a process gets its program compiled by inheriting it (an agent forks the fork server's image, copy-on-write) or by loading forms it lacks: ```seon.program/load-all!``` (every live form a db has that the binary lacks), ```seon.program/load-changes!``` (the forms and removals a merge's resolved tx-data carry, which ```seon.agent/merge-main!``` loads right after its merge commit) and ```seon.program/load-diff!``` (the forms one db has over another, and the removals), and evaluations are records that nothing re-runs. A real form loads in 1.6–5 ms; Jolt's compiled units are 15× their source, hold absolute paths and are valid only under the runtime that compiled them, so they are not stored (task `seon.program/compiled-units-in-the-store` holds the idea). A branch's program at commit C is `d/commit-as-db` at C (0.7–0.9 ms) and `seon.program/load-diff!` to it, from definitions only: code and data definitions restore exactly; host cells (atoms, delays) come back empty and host objects (LMDB, sockets, procs, foreign libraries) are made fresh, as at boot.

An agent's `:base`, against which it chooses the tests that judge its branch, is main at the branch's merge base: the image it forked from, then ```seon.db/branch-base``` after each `seon.agent/merge-main!`. ```seon.program/changed-vars``` compares forms by `:form/id`, which names one entity on every branch ([content ids](#ids)). The writer's `:spawn` names main's commit and t on `:fork`, and a new agent whose branch or image is elsewhere (```seon.agent/misaligned```) reports `:seon.agent/fork-misaligned` with its hello. The fork server keeps a replica of main, loaded from each stored commit of main ([history](#history)), so an agent forks holding main rather than reading it whole from the store in every process. Proofs: `seon.agent-test/after-merge-main-the-agent-judges-against-the-new-base`, `seon.procs-test/a-fork-names-the-commit-its-agent-starts-at`, `seon.procs-test/a-fork-at-another-commit-is-recorded-misaligned`.

Related: [source](source.md) (merges read a branch's history by commit), [failure points](failure-points.md).
