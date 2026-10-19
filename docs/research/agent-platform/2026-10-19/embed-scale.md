---
title: "Embeddings at scale: generation, space, search, and declarative embedding"
type: research
status: owner approved P1-P3 on 2026-10-19 (later phases need their own go-ahead); P1-P3 landed
created: 2026-10-19
---

# Embeddings at scale

Lane embed-scale (Opus). The owner asked on 2026-10-19 what limits the kNN, how much it costs to embed a lot of data,
and how to embed lazily or in the background. He then set constraints: embeddings must be cheap to store and cheap
to regenerate on change, without exploding the database. Small data stays in datoms, in frequent small transactions,
and larger data goes to blobs. The index stays out of the database, and the design uses Datahike's own caches.

**Prior work this builds on:**
- [embedding/report.md](../2026-10-16/embedding/report.md) and [embedding/fast/report.md](../2026-10-16/embedding/fast/report.md): the model and the fast MLX path.
- [vector-db/report.md](../2026-10-16/vector-db/report.md) and [vector-db/proximum-api.md](../2026-10-16/vector-db/proximum-api.md): Proximum, its gaps G0-G9, and the G0 fork fix.
- [2026-10-18/embeddings/](../2026-10-18/embeddings/): the probes.
- Commits `0d9f8f847` (`seon.embed`) and `820277488` (the background fill and `my.program/similar`).

**How this was measured:**
- Machine: Apple M5 Max (6 performance and 12 efficiency cores), 128 GB, OpenJDK 26.0.1, eg2-text-bf16.
- Scripts and raw output are in `tmp/embed-scale/`. That directory is gitignored, so every number that matters is copied into this page.
- No SCI request was made, and default was not touched. The embed server on :8701 is the one exception: it died during this lane's load, and `bin/seon start` relaunched it (see Findings).

**Corpora, all real text:**

| corpus | items | mean tokens |
|---|---|---|
| `names` (qualified declaration names) | 7,534 | 19 |
| `decldocs` (`title: sym \| text: arglists + docstring`, today's meaning corpus) | 7,534 | 67 |
| `mix` (shuffled top-level forms of `src`, `test` and `reference-code`, plus Markdown paragraphs of `reference-code`; texts capped at 2,400 characters) | 61,458 | 166 |

The 61,458 `mix` items hold 10.2M tokens.

## 1. Generation cost

**The earlier estimate.** The fast lane reported about 1,000-1,900 embeddings/s, measured on uniform 50- and
100-token texts. At that rate, 10k items would take 5-10 s.

**What 10k items actually cost.** The rate depends on text length and on how the work is batched. The table below
gives warm figures; a cold server is slower (see the next list).

| path | names (19 tok) | decldocs (67 tok) | mix (166 tok) |
|---|---|---|---|
| batch CLI (`embed_batch.py`): whole backlog, sorted into length buckets, 16k-token GPU batches | – | **1,501/s** (100k tok/s) | **442-499/s** (71-83k tok/s) |
| server, one request carrying the whole backlog (fresh server) | – | 1,152/s | 423/s |
| server, requests of 256 texts with 2 clients in flight (today's `:seon.config.embed/batch-size` 256) | 2,042-2,949/s | 180-253/s | 189-352/s |

**Cold shapes.** A newly started server, or one seeing a new mix of lengths, pays for each new batch shape. Names ran
at 387-456/s on the first pass and 2,042-2,949/s on later passes.

**Why the 256-text requests are slow.**
- The server sorts texts into length buckets only within one request.
- Each 256-text request of mixed lengths therefore turns into many small GPU batches.
- Result: 2-6× slower than sending the whole backlog at once, for the same texts.

**Whole-corpus run.** All 61,458 `mix` texts through the batch CLI took 125 s wall: 499 embeddings/s, 82.7k tokens/s.

**Estimate per scale.** These use the measured batch-CLI rates, which the server reaches once it is fed whole
backlogs (Phase 1). The machine draws an estimated 60 W of GPU power while embedding; energy was not measured.

| items | names (~3,000/s) | declaration docs (~1,500/s) | typical code and prose (~480/s, 166 tok) | energy at 60 W, typical |
|---|---|---|---|---|
| 10k | 3 s | 7 s | **21 s (measured 20-24 s)** | 0.4 Wh |
| 100k | 33 s | 67 s | 3.5 min (61k measured in 125 s) | 3.5 Wh |
| 1M | 5.6 min | 11 min | 35 min | 35 Wh |
| 10M | 56 min | 1.9 h | 5.8 h | 0.35 kWh |

**Memory is the real limit on generation (measured).**
- The 61k batch-CLI run reached a resident set of 3.7 GB, but `time -l` reported a **peak memory footprint of 84 GB**.
- That is MLX's Metal buffer cache growing under varying batch shapes. It is the failure recorded in the
  mlx-metal-cache-limit note: unbounded, and invisible to RSS.
- Neither `script/embed/server.py` nor `embed_batch.py` calls `mx.set_cache_limit`.
- The production server (pid 17517) died silently during this lane's load: nothing in its log, and status still
  showed it. On a 16 GB machine either process would be killed long before 10k mixed items.
- Bounding this is Phase 1.

## 2. Space

**Bytes per vector** (vector only; the content key adds 32 B):

| dims | float32 | float16 | int8 (plus a 4 B scale) | binary |
|---|---|---|---|---|
| 768 | 3,072 | 1,536 | 772 | 96 |
| 256 | 1,024 | 512 | 260 | 32 |
| 128 | 512 | 256 | 132 | 16 |

**Totals at 256 dimensions:**

| items | f32 | f16 | int8 |
|---|---|---|---|
| 10k | 10 MB | 5 MB | 2.6 MB |
| 100k | 102 MB | 51 MB | 26 MB |
| 1M | 1.0 GB | 512 MB | 260 MB |
| 10M | 10.2 GB | 5.1 GB | 2.6 GB |

**Recall loss (measured).**
- "Neighbour agreement" is recall@10 against exact f32 at the same dimension. It uses 1,000 corpus items as queries,
  each excluding itself.
- Task hits are hit@1 and hit@8 on the embeddings lanes' two query sets:
  - 14 misspelled or guessed names against `names`, from probe3;
  - 14 intents against `decldocs`: probe3's 5, the test's `pull an entity by its id`, and 8 more whose answers exist
    in the corpus.

| 256 dims | names agreement | decldocs agreement | mix agreement | typos hit@1/@8 (of 14) | intents hit@1/@8 (of 14) |
|---|---|---|---|---|---|
| f32 | 1 | 1 | 1 | 13 / 14 | 6 / 10 |
| f16 | 0.995 | 0.999 | 0.997 | 14 / 14 | 6 / 10 |
| int8, per-vector scale | 0.945 | 0.966 | 0.954 | 14 / 14 | 6 / 10 |
| binary | 0.53 | 0.44 | 0.37 | – | – |
| binary, shortlist 100 reranked in int8 | 0.90 | 0.84 | 0.77 | – | – |
| **int8 shortlist of 20, reranked in f16** | – | – | **0.998** | – | – |

**At other dimensions:**
- 768 dimensions gives the same task hits as 256: typos 14/14, intents 6/10.
- 128 dimensions loses one intent at hit@1 (5/14).

**Truncation changes neighbours more than quantization does.** Truncating from 768 to 256 changes neighbours far more
(agreement with 768 f32 is 0.75-0.86) than int8 does at a fixed dimension. Even so, task hits did not move. **Keep
256 dimensions.**

**Where vectors live.**
- **Store them as int8 + f16 together** at 256 dimensions: 772 B per vector.
- Search scans the int8 copy, then reranks the top 20 in f16. That matches exact f16 recall (0.998) while scanning a
  quarter of the f32 bytes.
- **Vectors live on disk in packed konserve segments** (§4), and are read through an off-heap `MemorySegment` mmap for
  search.
- **Not in the heap:** it is capped at 4 GB, and `seon.cache`'s whole budget is 512 MB.
- **Not in datoms** (§3c).

## 3. Search

### 3a. Today's limits

All of these are in `seon.embed` at `820277488`.

1. **The matrix is one `byte[]` in the heap**, so it is bounded three ways:
   - A Java array holds at most 2³¹ bytes, so at most 2.1M vectors at 256 f32.
   - The heap is capped at 4 GB.
   - `seon.cache` has a 512 MB budget, and an index weighs about 1 KB per text. Past about 400k texts the index no
     longer fits, so every call would derive it again: an estimate, from `seon.embed/index-weight`.
2. **Every call costs O(corpus) before the scan.**
   - `my.program/similar` re-reads the corpus by Datalog (`seon.embed/meaning-texts` or `name-texts`), sorts it, and
     SHA-256-hashes every text into `index-key`.
   - It then builds a text→symbol map of the whole corpus.
   - At 7.5k texts this is the 19 MB a warm call allocated (live run 10).
   - At 1M texts of about 300 B it would hash about 300 MB per query: roughly 0.3 s (an estimate).
3. **One changed text derives the whole index again.** The index is keyed by the content of the whole corpus, so one
   change means a new key and a full re-stack. Each per-text embedding is still a cache hit.
4. **The scan is scalar Clojure on one thread.** It allocates a `[score row]` vector per row and boxes each comparison
   in the `PriorityQueue`.
5. **The corpora are a hand list.** `meaning-texts`, `name-texts` and `corpus-attributes` name the two corpora and
   five attributes, so nothing else can be embedded by declaration.
6. **The durable store uses one konserve file per vector.**
   - `data/embeddings` today holds 42,253 files using 165 MB on disk, for 49 MB of vectors: each 1,160-byte value
     takes a 4 KB block.
   - Measured on a scratch store: 400 MB on disk for 100k vectors (§3c).
7. **The fill listens to main only**, and it sends 256-text requests: the 2-6× loss in §1.

### 3b. Brute force, scalar against SIMD

`tmp/embed-scale/Knn.java`: one query, k=10, 256 dimensions, best of 5-30 runs. The top-k is kept in a primitive heap
with no allocation per row. The vectors are random, so these numbers measure time only; recall is in §2. NEON gives
4 float32 lanes.

| N | f32 scalar, 1 thread | f32 SIMD, 1 thread | **f32 SIMD, all cores** | int8 SIMD, 1 thread, off-heap | **int8 SIMD, all cores, off-heap** | bytes scanned (f32 / int8) |
|---|---|---|---|---|---|---|
| 7.5k | 0.63-0.95 ms | 0.15-0.22 ms | 0.13-0.16 ms | 0.22 ms | 0.17 ms | 7.7 / 1.9 MB |
| 100k | 8.4-14 ms | 1.8-3.3 ms | 0.38-0.52 ms | 2.9 ms | 0.31 ms | 102 / 26 MB |
| 1M | 84 ms | 18.5 ms | **4.2-4.3 ms** | 28.9 ms | **2.7 ms** | 1.0 GB / 256 MB |
| 10M | does not fit the heap | – | – | 285 ms | **22 ms** | – / 2.56 GB |

**Reading the table.**
- The scalar Java loop is the floor for today's Clojure `nearest`, which also allocates per row. At 7.5k that floor
  matches the "about 1 ms" measured in `0d9f8f847`.
- SIMD on all cores gives 20× at 1M.
- The int8 kernel must widen through 128-bit species (`ByteVector.SPECIES_64` → `ShortVector.SPECIES_128` →
  `IntVector.SPECIES_128`). The first version used 256-bit species, which NEON lacks: it fell off the intrinsic path
  and ran 200× slower (6.3 s at 1M).

### 3c. Proximum (HNSW in Datahike) against content-keyed segments: store growth

`tmp/embed-scale/growth.clj` runs on scratch stores with the pinned fork (Datahike at `reference-code/datahike`,
Proximum `9a4edc0`, which already includes G0). Everything is at 256 dimensions, as f32 vectors.
- **A** is today's layout: one konserve key per vector.
- **B** is the proposed layout: one packed segment per fill pass.
- **C** is Proximum: `:db.secondary/only` vector datoms.
- **D** is Datahike holding only a text-digest datom per entity, the most an entity carries under B.

Each initial load is 10 or 100 commits of 1,000 entities. Disk is `du`.

| case | 10k: disk | 10k: time | 100k: disk | 100k: time |
|---|---|---|---|---|
| A per-key, initial | 40.0 MB (10,000 files) | 2.7 s | 400 MB (100,000 files) | 25.8 s |
| A, +100 changed texts | +0.4 MB | 33 ms | +0.4 MB | 31 ms |
| **B packed, initial** | **10.0 MB (1 file)** | 0.9 s | **100 MB (10 files)** | 8.8 s |
| **B, +100 changed texts in one pass** | **+0.10 MB** | 9 ms | **+0.10 MB** | 9 ms |
| B, +100 changed texts in 100 passes | +0.5 MB | 31 ms | +0.5 MB | 30 ms |
| D digest datoms only, initial | 3.1 MB | 2.8 s | 36.0 MB | 28.3 s |
| D, +100 changed texts in 100 commits | +9.7 MB (97 KB per commit, Datahike's own) | 9.3 s | +15.2 MB (152 KB per commit) | 11.4 s |
| D, +100 changed texts in one commit | +1.3 MB | 0.8 s | +2.8 MB | 1.6 s |
| C Proximum, initial | 33.7 MB | 11.7 s | 564 MB | 221 s |
| **C, +100 changed texts in 100 commits** | **+156 MB (1.56 MB per commit)** | 24.3 s | **+429 MB (4.3 MB per commit)** | 38.9 s |
| C, +100 changed texts in one commit | +4.1 MB | 1.8 s | +10.4 MB | 4.8 s |

**Proximum's 1.6 MB per commit, explained from the fork's code.**

A commit that adds one vector rewrites two kinds of copy-on-write chunk.

1. **The partial vector chunk.**
   - `proximum.vectors/sync!` calls `flush-write-buffer-async!`.
   - That reads the existing partial chunk (`chunk-size` 1,000 vectors), appends the new vectors and writes the whole
     chunk under a new address.
   - At 256 f32 a full chunk is 1 MB, so this costs up to 1 MB per commit, about 0.5 MB on average.
2. **The dirty layer-0 edge chunks.**
   - `proximum.internal.PersistentEdgeIndex` stores `CHUNK_SIZE` = 1,024 nodes per chunk, at `slotsPerNode` = M0+1 =
     33 ints, so 135 KB per chunk.
   - One insert links the new node and adds back-links on up to M0 = 32 neighbours, which are spread at random.
   - So it dirties nearly every chunk at 10k (10 chunks, 1.35 MB), and about 30 chunks (4 MB) at 100k and beyond.

The measured 1.56 MB per commit at 10k is the 97 KB Datahike commit, plus about 1.35 MB of edge chunks, plus the
partial vector chunk.

**It is not inherent to HNSW. It is the chunk layout.**
- The bytes an insert really changes are its own vector (1 KB) and 33 adjacency lists of 132 B each (4.4 KB).
- A layout that appends a per-commit vector tail and per-commit edge deltas, compacted later like an LSM tree, would
  write about 6 KB per insert.
- Batching amortizes the cost: one 100-vector commit wrote 4.1 MB, against 156 MB for 100 single-vector commits.
- Smaller edge chunks are no fix: G6 measured 64-node chunks at 0.41 MB per commit, at the cost of slower build,
  query and restore.

**It is fixable, but it does not need fixing.** By the owner's constraint the index stays out of the database. Then
no per-commit index bytes exist, and the Datahike side of a changed text is at most D's small digest datom.

### 3d. IVF on real text

`tmp/embed-scale/ivf.py` runs on 60,958 `mix` vectors at 256 f32. It holds out 500 queries, runs 10 iterations of
spherical k-means, and measures recall@10 against brute force.

| nlist | nprobe | recall@10 | fraction scanned |
|---|---|---|---|
| 64 | 8 | 0.928 | 13% |
| 64 | 16 | 0.977 | 26% |
| 256 | 16 | 0.925 | 6.8% |
| 256 | 32 | 0.964 | 13% |
| 1,024 | 32 | 0.942 | 3.9% |

k-means takes 0.2-0.9 s at this size. IVF buys about 8× fewer bytes scanned at 0.96 recall. Brute-force int8 SIMD
already answers 1M in 2.7 ms, so IVF pays only above about 10M, or where a sub-5 ms answer is needed at 10M.

### 3e. Recommendation per scale

| scale | search | lives in | per query | note |
|---|---|---|---|---|
| ≤100k (today: 7.5k) | int8 SIMD brute force, top 20 reranked in f16 | off-heap mmap of the segments | <0.5 ms | removes the O(corpus) per-call work of §3a |
| 1M | the same, on all cores | off-heap, 772 MB on disk | ~3 ms | outside the 4 GB heap and the cache budget |
| 10M | the same (22 ms), or IVF with nlist ≈ 4k and nprobe 32 over the same segments (estimated 2-4 ms) | off-heap, 7.7 GB on disk | 2-22 ms | IVF centroids are a derived value, rebuilt in the background |
| >10M | IVF-PQ or HNSW built from the segments, out of the database | mmap | – | not needed now |

HNSW (Proximum) is not recommended at any scale we foresee:
- brute force covers 10M in 22 ms;
- HNSW's random back-link writes are what make the per-commit cost (§3c);
- its recall on random vectors was 0.16-0.62 (vector-db report).

## 4. Declarative embedding: an embedding attribute

**The whole design:** a schema property says that an attribute's text is embedded. The vector is a derivation keyed by
the content of that text, stored in packed segments outside Datahike. Search folds a derived index from the attribute's
datoms.

**Declaring.** Put the property on the attribute's schema in the registry, for example
`[:string {:seon.embed/task "document"}]`.
- The registry derives the set of embedded attributes. This replaces the `corpus-attributes` hand list and
  `meaning-texts` / `name-texts`.
- A composed text, such as today's `title: sym | text: arglists + doc`, is declared as
  `{:seon.embed/text-fn my.ns/text-fn :seon.embed/reads #{...}}`: a contracted function of the entity's values of the attribute and its reads, run by the fill.
- `:seon.embed/when` takes `:write` (the default) or `:query` (§5).

**No vector datom.** The entity keeps exactly what it has today, its text.
- The content key is `seon.embed/embedding-key` over model, task, dimension and text, derived wherever the text is read.
- An optional 32 B digest datom is the fallback if hashing every embedded text at boot is measured above one second.
  The estimate is about 0.6 s per 1M texts of 600 B. D's row above prices that datom.
- Datahike reads (the fill reading the attribute's datoms, the query joining ids) go through Datahike's own node cache.
  There is no parallel cache of texts.

**The vector store.** Packed konserve segments sit beside the store in `data/embeddings`.
- One segment is written per fill pass. Its value is the digests of its texts, then their int8 vectors with scales,
  then their f16 vectors. Its key is the segment's digest.
- The set of present digests is derived at boot by reading the segment headers: 32 B per vector, so 32 MB per 1M.
- So a membership test needs no per-vector key. Measured: 100 changed texts in one pass add 0.10 MB in one file,
  against 0.4 MB in 100 files today.
- Segments are immutable. Superseded vectors are reclaimed by compacting segments whose live fraction falls below a
  dial, in the background. This is konserve's own reclamation.
- `seon.cache` keeps only query embeddings, small and hot. The OS page cache is the cache for segments.

**Embedding on write.** The existing Flow `:io` proc (`seon.embed/fill-step`) keeps its listener and its sliding-1 wake.
1. Its wake predicate reads the embedded-attribute set instead of the hand list.
2. Each pass takes the backlog: embedded-attribute datoms whose content key is not in the present set.
3. It sorts the backlog by length and sends it in token-budget requests rather than 256 texts at a time.
4. It writes one segment, then offers the index fold a wake.
- Unchanged text costs zero embed calls and zero writes on any branch, after any restart, because the key is the content.

**The index is derived, never stored per commit.** Per embedded attribute it is
`{:base <off-heap matrix + row→eid> :overlay {eid → key}}`.
- It is folded from each transaction report's datoms of that attribute.
- An assert or retract changes only the overlay, and a background rebuild folds the overlay into a new base when it
  grows past a dial: O(N) copy, about 1 s at 1M.
- A query scans the base (skipping eids the overlay replaced) and then the overlay.
- Nothing is keyed by the whole corpus, so a query costs O(scan) and never O(hash corpus).
- The matrix is a live host object with no data form. It belongs in the process registry, observable in status, as
  CLAUDE.md requires.

**Queries never block.**
- When the base exists, the overlay's not-yet-embedded keys are reported beside the answer as
  `:seon.embed/pending n`, and the answer still ranks everything already embedded.
- `{:seon.embed/pending n}` alone is answered only when nothing of the attribute is embedded yet. That is today's
  shape; the ranked part is an accreted key.

**Per-branch visibility.**
- A branch's index is main's base as of its fork, plus an overlay derived from the branch's own changed datoms of
  embedded attributes. Seon already holds that delta since the merge base.
- A branch never copies the base. Its texts that main lacks are embedded by the same fill on demand, and they land in
  the shared segments.
- Any other branch or agent with the same text then reuses them ("key it by content").

**Merge.** Vectors never merge. They are derived data keyed by content, outside the program partition and outside
Datahike. Merging the text datoms is the whole merge. After merge, main's fold sees the merged datoms and finds their
keys already present, so it makes no embed call.

## 5. Laziness and backlog

**What to embed when:**
- **Eager, `:when :write`:** the small corpora agents rank on most turns: declarations (names and meaning), tasks, and
  doc sections. These are 10k-100k texts: 7-67 s once, then only the changed texts.
- **Lazy, `:when :query`:** large and rarely ranked attributes, such as evaluation outputs, messages, or
  reference-code forms.
  - The first query of such an attribute enqueues its corpus and answers in progress.
  - Embedding is then the same background fill, never inside the query.
  - At 480/s, 1M lazily enqueued texts take about 35 minutes. That is honest only as a background job with a visible
    pending count.

**Backlog policy when generation lags writes:**
- One fill pass at a time (the proc). Each pass takes at most a token-budget dial of the backlog.
- Order: texts a query is waiting on first, then `:write` attributes newest first, then `:query` attributes.
- The backlog is not stored. It is derived each pass as "embedded-attribute datoms whose key is not present", so a
  restart loses nothing and nothing is replayed.
- The pending count is a positive observation in the proc's ping (`::filled`, `::pending`). A backlog older than a dial
  is a health finding, never a silent lag.

## 6. Phases and red proofs

Each phase names its owner and the proof that is red until it lands. Root files them as tasks.

**Phase status (lane embed-p1-p3):**
- P1: the MLX cache cap, worker health and server error replies landed (`script/embed/`, `seon.operator/embed-server-status`).
  Measured on 61,458 `mix` texts: the batch CLI's peak footprint fell from 84.4 GB to 5.1 GB at 536/s (499/s before);
  the server under the load that killed it (256-text requests, 2 clients) peaked at 2.9 GB footprint (sampled every
  2-3 s) at 340/s, and at 3.3 GB and 380/s with 1,500-text requests, a check JVM running beside it.
  The byte-budget requests landed with lane embed-fix: `:seon.config.embed/request-bytes` (768 KiB) replaces
  `:seon.config.embed/batch-size`, and a pass sorts its backlog by length and cuts it at that budget
  (`seon.embed/requests`), once adoption wrote a new dial's decision onto a held config entity
  (`seon.config/adopted-dials-tx`). The ≥400/s rate on the live server is not measured yet.
- P2: packed segments and the conversion of the per-key store landed in `seon.embed`. The live store converted on
  2026-10-19, but inside an agent's call that the 2 GB allocation bound cut while it deleted the per-key files; the
  next open packed the 10,986 left into a second segment (11.6 MB of duplicates, until a P4 compaction). Lane
  embed-fix: a call now answers in progress while the store opens (`seon.embed/store-within`), and a cut conversion
  resumes without packing again, deleting the per-key values only after its synced segment.
- P3: landed (lane embed-p3). `:seon.fn/sym` and `:seon.test/sym` declare `{:seon.embed/task "similarity"}`;
  `:seon.fn/arglists` declares `{:seon.embed/task "none" :seon.embed/text-fn seon.embed/declaration-text
  :seon.embed/reads #{:seon.fn/sym :seon.fn/doc :seon.fn/private?}}`. `seon.embed/embedded-attributes` derives the set
  from the projection as `seon.config/dial-attributes` does; `seon.embed/corpus` groups it by task and maps each text to
  the entities holding it on the asking database value, held by `seon.db/attribute-revision-key` (Datahike's own
  per-attribute change tracking), so a write to other attributes reuses it; `seon.embed/entities-now` answers entity
  ids. `name-texts`, `meaning-texts`, `corpus-attributes`, `corpus-report?` and `similar-now-in` are deleted.
  `:seon.embed/when :query` leaves a task out of the fill when none of its attributes is `:write`.
  The text function's property is `:seon.embed/text-fn`, since `:seon.embed/text` already names the text schema; it
  returns a vector of texts (empty leaves the entity out), since a bare nilable return is refused.
  Measured on default (494ff6a42, read-only JVM probe): the derived corpora hold exactly the old hand lists' texts
  (11,046 names, 2,350 meaning texts), so no text is embedded again; a cold derivation takes 8-16 ms (names) and
  14-25 ms (meaning) against 13 and 28 ms for the hand lists; a warm one is a cache hit. The index key is now the
  hash of the held corpus digest, so each index is stacked once again from the cache and segments after the restart.
  `my.program-query-test/a-warm-similar-by-name-allocates-in-proportion-to-its-answer` (red at 19.9 MB against 5 MB)
  is green in the check JVM. The query's scan is unchanged until P4.
- P4-P6: awaiting the owner.

| phase | change | owner | red proof |
|---|---|---|---|
| P1 | The embed server bounds MLX memory (`mx.set_cache_limit`, a dial sized for 16 GB) and buckets across queued requests. `seon.embed/embed!` batches by a byte budget (`:seon.config.embed/request-bytes`, about 3.2 UTF-8 bytes per token) instead of `:seon.config.embed/batch-size`. | `script/embed/server.py`, `seon.embed/embed!` | `seon.embed-test/a-fill-pass-sends-its-backlog-in-byte-budget-requests` (shortest first, cut at the budget, a longer text alone). Measured: ≥400/s, against 189-352/s today, and peak footprint under the dial. |
| P2 | Packed segments replace the per-key store. The present-digest set is derived from segment headers. | `seon.embed/open-store`, `embed!` | `seon.embed-test/a-fill-pass-writes-one-segment`. `seon.embed-test/unchanged-text-costs-no-request-and-no-write-after-restart` (exists in spirit as probe6; make it a row). |
| P3 | The `:seon.embed/task` attribute property, and the fill reads the derived attribute set. Deletes `corpus-attributes`, `meaning-texts` and `name-texts` as hand lists. | schema registry, `seon.embed/fill-step` | `seon.embed-test/an-attribute-declaring-an-embed-task-is-embedded-without-a-hand-list`, `seon.embed-test/an-entity-only-another-branch-holds-is-not-returned` |
| P4 | Index folded from transaction reports into base plus overlay, off-heap int8 SIMD scan with f16 rerank, partial answers with a pending count. | `seon.embed/index`, `nearest`, `similar-now` | `seon.embed-test/a-query-allocates-in-proportion-to-its-answer-not-the-corpus` (extends `my.program-query-test/a-warm-similar-by-name-allocates-in-proportion-to-its-answer` to 100k synthetic rows). `seon.embed-test/a-changed-text-re-embeds-only-that-text`. A measured row: 1M synthetic vectors in under 10 ms per query. |
| P5 | Branch overlay from the branch's own delta. | `seon.embed`, `seon.ref` read-where | `seon.embed-test/a-branch-ranks-its-own-changed-texts-and-mains-rest` |
| P6 | IVF centroids as a derived value. | – | Deferred until an attribute passes about 5M texts; no task now. |

**Not recommended:** Proximum vector datoms (§3c). The fork bump in `dd0be250c` stays harmless while no attribute
declares a `:proximum` index.

## Findings outside the assignment

1. **The embed server holds unbounded MLX memory and died silently under load.**
   - Measured: the 61k batch-CLI run reached an 84 GB peak footprint.
   - The production server (pid 17517) stopped answering during this lane's mixed-length requests, with no log line.
     `bin/seon status` had reported it `:healthy` from its last observation.
   - Relaunched with `bin/seon start`.
   - The fix is P1's cache limit. A server that stops is today noticed only by a failed request (the
     `:seon.embed/unreachable-error`) and nothing restarts it.
2. **The shipped batch size of 256 is 2-6× slower than whole-backlog requests** (§1). P1 fixes it.
3. **The config comment beside `:seon.config.embed/endpoint` is wrong** ("about 1,000 per second batched"). That holds
   for texts of about 100 tokens. Code forms run at about 450/s and declaration docs at about 1,500/s. Correct it with P1.

## Evidence files (`tmp/embed-scale/`, disposable)

| file | what it holds |
|---|---|
| `corpus.py` | builds the real-text corpus |
| `gen.py` | server throughput |
| `quant.py`, `quality.py` | recall and hit@k |
| `Knn.java` | brute-force kNN timings |
| `ivf.py` | IVF recall |
| `growth.clj`, `growth-100k.edn` | store growth |
| `all.log` | the 61k CLI run, with `time -l` |
