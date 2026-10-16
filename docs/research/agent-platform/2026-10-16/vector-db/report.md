---
title: "Vectors in Seon's Datahike: Proximum as it is, what it costs, and what must change"
type: research
status: measured (scratch stores only); saved by root from lane vector-db's report (the lane could not write it)
created: 2026-10-16
---

# Vectors in Datahike (Proximum) — 2026-10-16

Lane vector-db (Opus). This is research and a rough plan only. No production file, dependency or fork changed, and
nothing touched default.

**Evidence.** The raw measurements are in [results.edn](results.edn), produced by [vector_bench.clj](vector_bench.clj)
and [vector_semantics.clj](vector_semantics.clj). Each ran in its own JVM (`clojure -M:dev`) on a scratch store, against
Datahike `88d05cf8` and the Proximum fork `9846d3e`. The machine was loaded (load average 7-15), so absolute times are
high; the ratios and byte counts are the evidence. This report folds in lane embed-model's Proximum section
([embedding/report.md](../embedding/report.md), commit ce071b61e) and does not repeat its benchmark.

## Verdict on "sync hashes the full index per commit"

**It is true of our pinned fork. It is not how Proximum is designed: our own fork commit added it.**

`docs/prds/next-turn/synthesis.md` §2 cites no research note for the claim.

**How upstream Proximum works** (`c123579`):
- Konserve is the source of truth.
- Vector chunks (1,000 vectors each) and edge chunks (1,024 nodes each) are written copy-on-write, and only when dirty.
- The mmap is a per-branch cache that `proximum.vectors/open-store*` refills from the chunks.

**What our fork added.** Commit `9846d3e` ("Add crash-safe guarded branch force", 2026-07-15) put
`proximum.vectors/publish-mmap-generation!` into every `sync!`. Each sync now:
- copies the whole mmap to `vectors-generation-<commit>.bin`;
- SHA-256-hashes the whole file three times: `file-evidence`, then `verify-generation!` on the temporary file and again
  on the destination.

The fork's own `docs/PERSISTENCE.md` says sync "is currently O(mmap bytes)".

**The cost follows reserved capacity, not N.** The mmap file is 64 B + capacity × dim × 4. With
`proximum.vectors/DEFAULT-CAPACITY` (10M) at 768 dimensions, that is 30.7 GB written per commit.

**macOS makes it worse.** `test-reflink-support` runs `cp --reflink`, which is GNU syntax. On macOS it fails, so every
generation and every branch is a full byte copy. This matches embed-model's finding.

**Measured on the pinned fork**, at 768 dimensions (the output size of `google/embeddinggemma-2`). A "1-vector commit"
is one `d/transact` of a single vector.

| N | capacity | mmap file | 1-vector commit | mmap bytes written per commit | Proximum konserve bytes per commit | one SHA-256 pass |
|---|---|---|---|---|---|---|
| 1k | 20k | 61 MB | 254-270 ms | 61 MB | 0.16 MB, 9 files | 24 ms |
| 10k | 110k | 338 MB | 693-720 ms | 338 MB | 1.33 MB, 18 files | 134 ms |
| 100k | 110k | 338 MB | 767-815 ms | 338 MB | 4.0 MB, 37 files | 135 ms |
| 1k, 256 dims | 10k | 10 MB | 173-196 ms | 10 MB | 0.15 MB | 4 ms |

**Other costs, from the same runs.**
- **Bulk insert.** A 5,000-vector commit at N=100k took 24.9 s, mostly HNSW inserts on one thread. A 1,000-vector
  commit at N=1k took 1.0 s.
- **A commit that touches no vector.** 39-49 ms without the index and 58-62 ms with it, on the same store (G2).
- **Without `:db.secondary/only` (10k × 768):** every vector is also a datom value in EAVT/AEVT, so `:db.secondary/only`
  is mandatory.
  - A commit that touches no vector writes 2.3 MB of Datahike data, against 38 KB with it.
  - The Datahike store reaches 150 MB, against 1.4 MB with it.
- **`d/branch!`.** It takes 67 ms at 10k and 165 ms at 100k. The new branch's first commit then costs 285 ms at 10k and
  789 ms at 100k, and writes 676 MB of mmap (two capacity-sized files).
- **kNN, k=10** (default M=16 and ef-search):
  - 0.85 ms per query at 10k and 2.4 ms at 100k.
  - Recall@10 on random Gaussian vectors was 0.62 at 10k and 0.16 at 100k. Random high-dimensional vectors are HNSW's
    worst case; recall on real Seon text is unmeasured, and ef-search is the dial to raise.

**Two fixes compared.**
- **Embed-model's fix:** keep the generation file, but use `sync!`'s chunk hash chain as its evidence and verify fully
  only on restore. This removes the hashing. It keeps a capacity-sized copy wherever the filesystem cannot clone (G0).
- **This report's fix:** stop copying the bytes and name them instead. The address map already names every chunk by an
  immutable konserve key, so commit cost no longer depends on the filesystem.

Once the generation copy is gone, a 1-vector commit should cost:
- the rewritten partial vector chunk: at most 3 MB, 1.5 MB on average;
- the dirty layer-0 edge chunks: 1.3 MB at N=10k, up to about 4 MB beyond that (G6);
- the PSS path nodes.

## 1. How Proximum works inside our Datahike

**Declaring.** Require `datahike.index.secondary.proximum`, then declare the attribute and the index:

```clojure
{:db/ident :doc/vec :db/cardinality :db.cardinality/one :db.secondary/only true}
{:db/ident :idx/vectors :db.secondary/type :proximum :db.secondary/attrs [:doc/vec]
 :db.secondary/config {:dim 768 :distance :cosine :capacity 200000
                       :store-config {:backend :file :path ".../vectors" :id #uuid "..."}}
 :db.secondary/status :ready}
```

With `:db.secondary/only` (upstream Datahike `7dd53247`), the primary indices hold `(str (hasch/uuid v))`, a content
digest. The probe saw `"3953c7a0-..."`.

**Insert.** An ordinary transaction. The adapter's `-transact` calls `proximum.core/insert`, using the Datahike entity id
as the external id. A retraction calls `proximum.core/delete`, which soft-deletes into the deleted-nodes bitset. A value
that is not a vector is logged and dropped (G3).

**Query.** kNN is reachable only through the index object:

```clojure
(datahike.index.secondary/-slice-ordered (get-in db [:secondary-indices :idx/vectors])
                                         {:vector q :k 10} nil nil :asc 10)
;; => [{:entity-id e :distance d} ...], then join the ids in Datalog
```

No function carries `:datahike/external-engine` metadata for `:proximum`. The planner's `:retrieval` op passes
`{:query :field}`, but the adapter reads `{:vector :k}`. So no Datalog clause can do kNN today (G5).

**Storage.**
- Proximum keeps its own konserve store, separate from Datahike's. It holds:
  - vector chunks `[:vectors :chunk uuid]` and edge chunks;
  - the metadata, external-id and address PSS nodes;
  - one immutable snapshot per commit and a mutable head per branch;
  - `:branches` and `:index/config`.
- Beside it is a `<path>-mmap` directory with `vectors-<branch>.bin` and, in our fork, one
  `vectors-generation-<commit>.bin` per commit.
- Each Datahike commit record stores the index's key map, `{:type :proximum :branch :commit-id :merkle-root :mmap-dir
  :store-config}`, under `:secondary-index-keys`. Its merkle leaves include `:secondary`.

**On commit.**
- `datahike.writing` calls `-sec-flush`, which runs `proximum.protocols/sync!` (a blocking `<!!`; it is safe under
  virtual-thread dispatch) and stores the returned key map in the Datahike commit.
- Proximum writes its snapshot first, so a crash in between leaves Proximum one commit ahead. That is harmless, because
  restore loads the commit id Datahike recorded.

**On `branch!`.**
- `datahike.versioning/branch-secondary-indices` calls `-sec-branch` (`proximum.versioning/branch!`, which copies the
  mmap), then `-sec-flush`. A historical source goes through `branch-from-key-map` instead.
- So every Datahike branch of a store that has a `:proximum` index pays this cost, including Seon's chain, test and merge
  branches (G4).

**On `merge!`.**
- `datahike.versioning/merge!` is a write whose tx-data the caller supplies; the target's HNSW re-inserts the merged
  values through `-transact`. Proximum's own `proximum.versioning/merge!` is never called.
- Reproduced: merging with the vector value works. Main's nearest neighbour to `[0 0 1 0]` becomes the merged entity
  `"c"`.
- Reproduced: merging the source branch's datoms, as Seon's merge does, carries the hash string `"1bd5fe52-..."`. The
  adapter logs `:datahike/non-vector-embedding` and main's nearest stays `"a"`. **The vector is lost silently** (G3).

**On `delete-branch!`.** Reproduced: Datahike's branches become `#{:db}`, but Proximum's `:branches` stays
`#{:main :feature}`, and `vectors-feature.bin` and every generation file remain (G1).

**On GC.** `datahike.gc` marks `#{}` for `:proximum` (`mark-from-key-map`).
- Reproduced: `d/gc-storage` swept 31 Datahike keys and left the Proximum store and all six mmap files untouched.
- `proximum.gc/gc!` exists, but it starts from Proximum's own branch heads, which Datahike never deletes.
- So under Seon's retention, vector storage grows without bound (G1).

## 2. Gaps: owner and smallest change

**G0 — every commit copies and hashes the whole mmap.** Owner: Proximum fork (our `9846d3e`).
- Evidence: 338 MB written per 1-vector commit at capacity 110k.
- Smallest change: a working mmap records the chunk addresses it holds; open, restore and force reload only the chunks
  whose address differs. Delete the generation machinery (`publish-mmap-generation!`, `file-sha256`,
  `verify-generation!`, the generation sweep). See [proximum-sync-without-generation.patch](proximum-sync-without-generation.patch).

**G1 — deleted branches and collection never reach Proximum's store.** Owners: Datahike fork (gc) and Proximum
(`gc-from-roots!`).
- Evidence: generation files and deleted branches leak (probe above).
- Smallest change: Datahike's gc already walks every retained commit record. Group their `:secondary-index-keys` by index
  type and call one `sweep-from-key-maps!` per type; Proximum then marks from those commit snapshots instead of its own
  heads, and `delete-branch!` needs no hook. See [datahike-proximum-adapter.patch](datahike-proximum-adapter.patch) §4.

**G2 — about 18 ms added to every commit that touches no vector.** Owner: the Datahike adapter.
- Evidence: the konserve read inside `generation-addressed-owner?`.
- Smallest change: a clean owner (`current-commit` set; every mutation clears it) re-exports its key map without reading
  (patch §1).

**G3 — a merge drops `:db.secondary/only` values.** Owners: Datahike fork (protocol and adapter) and Seon's merge.
- Evidence: datom readers see hash strings; a datom-carrying merge drops the vector; the adapter logs and drops
  non-vector values.
- Smallest change: an optional `-value [idx eid attr]` (Proximum: `get-vector`), selected by the schema flag, and a
  refusal instead of log-and-drop for a non-vector value (patch §3).

**G4 — every `d/branch!` forks the HNSW.** Owner: the Datahike adapter.
- Evidence: every branch of a store with a `:proximum` index pays an mmap copy plus a sync.
- Smallest change: a lazy fork. A new branch shares the source commit read-only and forks on its first vector write. This
  is sound while each owner's vectors are append-only, so it must fork before any compaction (patch §2).

**G5 — no Datalog kNN clause.** Owners: the Datahike adapter and `execute`.
- Smallest change: a `nearest` engine function with `:datahike/external-engine` metadata and a `:query-spec` builder
  (patch §5).

**G6 — edge chunks are too coarse.** Owner: Proximum fork.
- Evidence: a layer-0 edge chunk holds 1,024 nodes (about 135 KB), and one insert dirties up to M0 of them, so a
  1-vector commit writes 1.3-4 MB.
- Smallest change: a smaller `PersistentEdgeIndex` CHUNK_SIZE (64 nodes gives about 270 KB). Measure first.

**G7 — Proximum's store ignores Seon's store policy.** Owner: Seon's store configuration.
- Evidence: no LZ4, no deferred immutable sync; every key is written with `{:sync? true}`.
- Smallest change: derive its `:store-config` from `seon.cluster.store/datahike-configuration`, with the path suffixed
  `-vectors`. A single shared konserve store is not possible as is, because Proximum's `:branches` and branch-head keys
  collide with Datahike's.

**G8 — capacity defaults to 10M.** Owner: Seon. Smallest change: a `:seon.config.db/vector-capacity` dial sized for a
16 GB machine.

**G9 — the adapter keeps an `!idx` atom.** Owner: Datahike writing. Evidence: the commit path cannot install the synced
value. Smallest change, later: `-sec-flush` returns `[key-map index']`.

**How these interact with Seon's recent store updates.**
- Fused roots, branching factor 128, diff-buf 32 and LZ4 are Datahike's PSS settings; they do not interact with the
  vector index.
- One merge base is unaffected: merge parents are Datahike's, and Proximum's linear parent chain is never consulted.
- Kept branches make G1 and G4 matter more. Until G1 lands, each retained ended branch holds a forked mmap and a Proximum
  branch. With G4, a branch that never wrote a vector holds nothing.

## 3. Seon integration sketch

**Principle.** An embedding is a derivation keyed by content: computed once per (model, dim, text digest) and shared by
every branch and agent that has that text.

**The entity.**

| attribute | meaning |
|---|---|
| `:seon.embedding/key` | a `seon.id` digest of `[model dim text-digest]`, minted by the one writer |
| `:seon.embedding/text-digest` | for a declaration, the digest of its source (it joins `:seon.program/definition-digest`'s text); for a doc or task, the digest of its rendered AI face |
| `:seon.embedding/model`, `:seon.embedding/dim` | which model and dimension produced the vector |
| `:seon.embedding/vector` | declared `:db.secondary/only true`, covered by the `:proximum` index `:seon.embedding/index` |

Subjects are never modified. A subject reaches its embedding by joining on its text digest. A changed definition has a
new digest and so a new embedding; nothing is retracted.

**Who writes.**
- Root writes embeddings on main, when content is merged or written back to main.
- The embedding is declared outside the program partition, so it never merges as program.
- With G4, a branch sees main's index as of its fork at no cost.
- An agent's own few changed definitions are ranked by brute force: vectors computed on demand and cached in
  `seon.cache` under the same content key.

**Query.** `(seon.embedding/nearest db query-vector k)`, read-only. Until G5 it goes through `-slice-ordered`; after G5,
through a Datalog clause:

```clojure
[(datahike.index.secondary.proximum/nearest :seon.embedding/index ?q 20) [[?e ?d]]]
[?e :seon.embedding/text-digest ?t]
```

The query vector comes from the local embed server (`embed_server_mlx.py`, `POST /v1/embed {texts, task, dim}`), cached
by the digest of the query text.

**Cost budget.**
- **Embedding** (embed-model's MLX bf16 numbers): about 10.7 ms per 100-token text and about 860 texts/s batched. The
  ~10k declarations in `src` embed once in about 12 s batched. After that, about 11 ms per changed definition at
  write-back or merge, never per turn.
- **Storage after G0 and G6:**
  - at 256 dimensions (Matryoshka; −1 point on MTEB per embed-model), about 1 KB of vector plus about 256 B of edges per
    entity, so about 13 MB for 10k entities, written once;
  - at 768 dimensions, about 31 MB.
- **Per query:** kNN about 1 ms.
- **Per commit:** a commit that adds an embedding stays near today's 137 KB ordinary commit, plus one dirty vector chunk
  and a few edge chunks.

## 4. Phased rough plan

**P0 — research.** This note.

**P1 — spike on a scratch store.**
- Work: apply G0, G2 and G4 to scratch checkouts of both forks, uncommitted. Rerun `vector_bench.clj` at 1k, 10k and
  100k, at 256 and 768 dimensions. Embed `src` through the MLX server, then measure recall@10 against brute force on real
  text, and latency.
- Proof:
  - a 1-vector commit costs the same at any N and capacity, at most about 2× a commit with no index;
  - the bytes written are no more than the dirty chunks;
  - `branch!` costs the same with or without the index;
  - recall is chosen on real Seon text, and the dimension is chosen (256 vs 768).

**P2 — fork patches in root's restart batch.**
- Work: G0 and G6 in Proximum; G1-G5 in the Datahike adapter, gc and execute. Add new rows to `proximum.branching-test`
  and `secondary_integration_test.clj`, including the guarded-force tests embed-model flagged.
- Proof: these rows are red on the parent and green with the patch:
  - a datom merge keeps the vector;
  - `delete-branch!` followed by `gc-storage` sweeps the Proximum branch;
  - a Datalog `nearest` clause returns neighbours;
  - an unrelated commit reads nothing from Proximum.

**P3 — Seon schema and writer.**
- Work:
  - the `:seon.embedding/*` schema resource;
  - the index, with the capacity dial (G8) and a store config derived from the cluster store (G7);
  - `seon.embedding/embed!`, the one writer, on main;
  - `seon.embedding/nearest`.
- Proof: published and reloaded on a branch of the live store, with deftests showing:
  - embedding twice writes once;
  - `nearest` returns a planted neighbour;
  - a new branch costs no Proximum write.

**P4 — first use.**
- Work: rank context candidates, or classify forms by their nearest labelled neighbour.
- Proof: an offline replay over stored turns, ranking by nearest neighbour against the recency working set (synthesis
  ①(c), 69.6% top-10) at an equal token budget.
- Stop there if nearest-neighbour ranking does not beat recency.

## Unresolved risks

- Recall on real text is unmeasured; the 0.62 and 0.16 figures come from random vectors.
- G4's shared read-only mmap depends on vectors being append-only, and `proximum.compaction` rewrites positions.
- It is open whether embeddings on main should reach a branch at `merge-main!` (by re-pointing the lazy fork).
- The crash safety of guarded force (`proximum.versioning/force-branch!`) currently depends on the generation evidence
  that G0 removes, so the fork's force tests must be rerun against the new evidence.
- 256 dimensions were measured only at 1k vectors; the queued 256-dim run at 100k was killed.
