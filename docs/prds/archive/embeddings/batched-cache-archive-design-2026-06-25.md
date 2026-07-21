---
type: prd
status: draft
tags: [prd, agent, database]
---

# Batched Embeddings + Content-Addressed Cache & Durable Vector Archive

Design + LIVE-VERIFIED empirical findings (2026-06-25, branch `feature/agent-fsm`).
Spec only — no production code was changed. Pairs with
[[docs/prds/embeddings/vertex-usage-reference-2026-06-25]] and
[[docs/prds/embeddings/state-and-activation-2026-06-25]]. The build wave consumes
the checklist at the end.

## TL;DR

- **The current `embed.clj` multi-text batching is BROKEN on Vertex
  `gemini-embedding-2`.** LIVE-PROVEN: the java-genai SDK call
  `.embedContent(model, List<String>, cfg)` with >1 text throws
  `IllegalArgumentException: "The embedContent API for this model only supports
  one content at a time."` — it does **not** fan out. `embed-batch!` packs up to
  100 texts per call, so any backfill/tx touching ≥2 changed entities fails.
  This must be restructured to **one text per request + parallel fan-out**.
- **Parallel online is the right batch mechanism for seon.** Measured throughput
  on the live GLOBAL endpoint: **248 req/s at concurrency 150, zero 429s** across
  400 requests. Even a 10k-entity corpus embeds in ~40s. The async Batch
  Prediction job (the "$0.00012 batch tier") is tied to the *predict*-surface
  models (e.g. `gemini-embedding-001`), **not** `gemini-embedding-2`, and is
  job-based (minutes latency, GCS/BQ I/O) — overkill until 100k+ and a model
  switch. Recommendation: keep `gemini-embedding-2` + parallel online; raise
  concurrency from 6 → ~24–48.
- **Embeddings are deterministic** — byte-identical vectors for identical
  content+params (max elementwise diff `0.0`, cosine `1.0`). So a content hash
  folded with model+task is a sound cache key, and a durable vector archive lets
  the HNSW index be rebuilt for free (raising the 10k capacity costs no
  re-embed).
- **Store the canonical 3072-dim vector; dim is a FREE local transform.**
  LIVE-PROVEN Matryoshka: `API-requested-1536` ==
  `renormalize(vec3072[:1536])`, cosine `1.0000000` (max diff `1.8e-7`); same for
  768. So the archive stores the full 3072 once and serves any smaller index dim
  by local truncate+L2-renormalize — no re-embed to change the index dim. The
  cache key therefore folds **model + task + content-hash but NOT output-dim.**

---

## Part A — Batched embeddings (LIVE-VERIFIED)

### A.1 What the Vertex surface actually accepts for `gemini-embedding-2`

All tested live against `projects/<GCP_PROJECT>/.../global/.../gemini-embedding-2`
with the service-account token.

| Request form | Result |
|---|---|
| `:embedContent` `{"content":{"parts":[{"text":…}]}}` | **200** — one vector (3072 default, `outputDimensionality` → 1536/768) |
| `:embedContent` with `"contents":[…]` (plural) | **400** `Unknown name "contents": Cannot find field` |
| `:embedContent` one `content`, multiple `parts` | **200** — but ONE vector (parts are concatenated, not separate) |
| `:batchEmbedContents` | **404** — Developer-API method, not on Vertex |
| `:predict` (`global` or `us-central1`) | **404** — `gemini-embedding-2` has no predict surface |
| SDK `.embedContent(model, List[3], cfg)` | **throws** `IllegalArgumentException` "only supports one content at a time" (no fan-out) |
| SDK `.embedContent(model, List[1], cfg)` | **200** — 1 embedding, dim 1536 |

**Conclusion:** for `gemini-embedding-2` there is exactly ONE online primitive —
single-`content` `:embedContent`. There is **no** server-side multi-text online
batch and **no** SDK client-side fan-out. N texts ⇒ N HTTP requests, period.

### A.2 The legacy `:predict` batch shape (different model)

For contrast, the predict-surface model **does** batch server-side:

| Request | Result |
|---|---|
| `gemini-embedding-001` `:predict` @ `us-central1`, `{"instances":[{"content":…}×3]}` | **200** — 3 predictions, each 1536-dim |

This is the shape the cheaper batch tier rides. It is a **different model**
(text-only, regional `us-central1`, no multimodal, no global residency). Using it
would mean abandoning `gemini-embedding-2` and its multimodal/MRL/global
properties — out of scope for the locked model choice.

### A.3 Measured parallel-online throughput (the recommended path)

Single-`content` `:embedContent`, `outputDimensionality:1536`, distinct texts,
`xargs -P` fan-out against the live GLOBAL endpoint:

| Concurrency | N | Wall | Throughput | non-200 |
|---|---|---|---|---|
| 1 | 10 | 3.71s | 2.7 req/s | 0 |
| 6 (current default) | 30 | 1.81s | 16.6 req/s | 0 |
| 12 | 30 | 0.96s | 31.3 req/s | 0 |
| 24 | 30 | 0.65s | 46.1 req/s | 0 |
| 48 | 120 | 1.44s | 83.0 req/s | 0 |
| 96 | 120 | 0.97s | 124.2 req/s | 0 |
| 150 | 400 | 1.61s | 248.3 req/s | **0 (zero 429)** |

Single-request latency ≈ 370–570ms. Throughput scales near-linearly with client
concurrency up to 150 with **no throttling observed** — the bottleneck is local
concurrency, not the server. At current default `max-embed-concurrency=6` seon
leaves ~10x throughput on the table for bulk ingest.

### A.4 Pricing (per the verified reference, reconciled)

- `gemini-embedding-2` text: **$0.20 / 1M tokens** (= $0.0002 / 1k tok) online.
  No batch discount tier is published for this multimodal model.
- `gemini-embedding-001` text: online $0.00015 / 1k tok, **batch $0.00012 / 1k
  tok** — the batch tier belongs to the predict-surface model, not to
  `gemini-embedding-2`.
- Output (the vector) is not charged.

At seon scale this is negligible: a 2,000-fn corpus at ~500 tok/fn ≈ 1M tokens ≈
**$0.20 for a full re-embed**, and the content-addressed cache (Part B) makes
re-embeds free. **Cost is a non-issue; latency/throughput is the only real
concern, and parallel online handles it.**

### A.5 Batch recommendation

**Use parallel online single-`content` `:embedContent`, fanned out by seon's own
bounded thread pool.** This is what `embed.clj` already *intends* with its
`Executors/newFixedThreadPool` + `embed-call-count`, except the unit of work must
become **one text per request, not a packed 100-text batch.** Concretely:

1. **Fix the per-request unit (REQUIRED — current code is broken on Vertex).**
   `embed-batch!` must call `.embedContent` with a **single-element** list (or the
   `(String model, String text, cfg)` overload). Today it passes the whole batch
   → `IllegalArgumentException` on any batch >1.
2. **Make the thread pool the batcher.** Drop `plan-batches` / `max-batch-texts` /
   `max-batch-tokens` (they encode the dead Developer-API multi-input semantics).
   Replace with: truncate each text to `max-text-tokens`, submit ONE Callable per
   text to a `newFixedThreadPool(min(concurrency, n))`, collect in input order.
   `embed-call-count` then increments **once per text** (it currently claims once
   per batch — that count is already wrong on Vertex).
3. **Raise `max-embed-concurrency` 6 → 24** (optionally 48 for a cold bulk
   backfill). Measured zero-429 headroom to 150; 24–48 is a safe, polite default
   that still gives 46–83 req/s. Keep the existing exponential-backoff retry for
   the rare 429/5xx — necessary insurance even though none were observed.
4. **Do NOT adopt Batch Prediction jobs** for the foreseeable corpus. They (a)
   require a model switch away from `gemini-embedding-2`, (b) are async job-based
   with GCS/BigQuery input+output buffers and minutes-scale latency, (c) only buy
   a ~20% discount on an already-negligible bill. Revisit only if the corpus
   reaches 100k+ entities AND the cache hit-rate is low AND a text-only model
   becomes acceptable.

> Smell flagged for the build wave: `seon.embed/plan-batches`,
> `max-batch-texts`, `max-batch-tokens`, and the `embed-batch!` multi-text call
> are all predicated on the Developer-API `batchEmbedContents` semantics that
> Vertex `gemini-embedding-2` does not have. They are not a perf knob — they are a
> live correctness bug under the new SA/Vertex config. Fix in place (no `-v2`).

---

## Part B — Content-addressed cache + durable vector archive

Vectors are the **source of truth**; the HNSW (Proximum) index is a derived,
rebuildable projection. Today the only "cache" is the per-entity
`:seon.embed/source-hash` string in the primary store, which skips a re-embed
**only when the SAME entity is re-transacted unchanged**. It does nothing for:
cross-entity duplicate content, post-`cluster reset` rebuilds (vectors are gone
with the wiped store), capacity raises, or model/dim experiments. The archive
generalizes it into a global, durable, content-addressed store.

### B.1 The cache key (folded content hash)

```
cache-key = SHA-256( model-id | task-instruction | content-bytes )   ; hex
```

- **`content-bytes`** — uniform across modalities: text gives UTF-8 bytes of the
  composed document; files/image/audio/PDF give the raw file bytes.
  gemini-embedding-2 is multimodal (one unified vector space, one index), so the
  SAME `SHA-256(bytes)` scheme and ONE store cover every modality (see
  [[docs/prds/embeddings/multimodal-design-2026-06-25]]).
- **`output-dim` is NOT in the key.** The archive stores the canonical 3072-dim
  vector and derives any smaller dim locally (LIVE-PROVEN free Matryoshka
  transform, B.2). One cache entry per (model, task, content) serves the 1536
  index, a 768 index, and any future dim, all from the same stored 3072.
- **The fold is load-bearing.** Same content under a different `model-id` or
  `task-instruction` (e.g. the query-side `query-instruction-prefix`) produces a
  **different vector**, so it must be a different key. LIVE-PROVEN determinism
  (max elementwise diff `0.0` across two calls) means: same fold gives a
  byte-identical vector, so the hash is a sound key with no tolerance needed.
- **Caveat:** the dim-free key assumes a Matryoshka model. If a non-MRL model is
  ever added, re-introduce `output-dim` into the fold (or key by canonical-dim).
- **Relation to `:seon.embed/source-hash`.** That attr becomes the *content* leg
  of the fold for the default text path (it already hashes the composed doc).
  Keep it in the primary store as the cheap "did this entity's doc change?"
  guard, but have it store the **full folded cache-key** (so the entity points
  directly at its archive entry). One hash function, used in both places.

### B.2 The value + on-disk layout

The archive is an **append-only, content-addressed object store** under
`data/embeddings/` (a sibling of `data/clusters/`, NOT inside any cluster store —
same isolation reason as the Proximum sibling store: konserve's `:file` `-keys`
must never see it). Survives `bin/seon cluster reset` (which only wipes
`<cluster>/store`).

```
data/embeddings/
  manifest.edn                 ; archive-level metadata: version, dim set, model set, counts
  vectors/
    <ab>/<cd>/<full-hash>.vec  ; one packed little-endian float32 blob per cache-key
                               ;   (sharded by first 2 hex bytes to keep dirs small)
  index.log                    ; append-only EDN-lines metadata journal (the catalog)
```

- **`index.log`** — append-only, one EDN map per line (newline-delimited).
  Append is the ONLY write; never rewrite a line. Each record:

  ```clojure
  {:seon.embed.archive/key        "<folded-sha256-hex>"
   :seon.embed.archive/model      "gemini-embedding-2"
   :seon.embed.archive/dim        3072             ; CANONICAL stored dim (full fidelity)
   :seon.embed.archive/task       :document        ; or :query (instruction fold)
   :seon.embed.archive/modality   :text            ; :text :image :audio :pdf …
   :seon.embed.archive/vec-path   "vectors/ab/cd/<hash>.vec"
   :seon.embed.archive/entity-ids #{12345}         ; back-links, NEVER the content
   :seon.embed.archive/created-at #inst "2026-06-25T00:00:00Z"}
  ```

- **Store the CANONICAL 3072-dim vector** (12,288 bytes packed LE float32), not
  the 1536 the index currently uses. LIVE-PROVEN Matryoshka: any smaller index
  dim is `renormalize(vec3072[:dim])`, cosine `1.0000000` vs an API call at that
  dim (max diff `1.8e-7`). So storing 3072 keeps full fidelity AND serves the
  1536 index (and any future 768/128 index) by a free local transform — no
  re-embed to change the index dim. The API call should request 3072 (the default
  / native dim) so the archive captures maximum information once.
- **`<hash>.vec`** — the raw 3072 floats as packed little-endian `float32`. Just
  the floats; all metadata lives in `index.log`. Store the normalized native
  vector; the per-index downscale (truncate to the index dim + L2-renormalize)
  happens when emitting `:seon/embedding`.
- **NEVER duplicate content.** The archive stores the *vector* and *entity-id
  back-links*, not the source text/file. The source already lives in the primary
  store (`:seon.fn/source`, `:my.kb/body`, a blob ref); the archive links to it by
  entity-id. `entity-ids` is a set because identical content can back many
  entities (the cross-entity dedup win).

### B.3 The flow: lookup-before-embed

```
augment-tx-with-embeddings / backfill! / reindex!:
  for each entity needing (re)embedding:
    doc      = compose-doc(trigger-attr, entity)
    key      = fold-hash(model, task, utf8(doc))   ; NO dim in the key
    hit      = archive-get(key)            ; in-memory key->record map, O(1)
    if hit:
        v3072  = read-vec(hit.vec-path)    ; zero Gemini cost
        archive-link!(key, entity-id)      ; append/extend entity-ids (idempotent)
    else:
        v3072  = embed-text(doc, 3072)     ; ONE Gemini request, NATIVE dim (parallel in bulk)
        archive-put!(key, v3072, {model task modality entity-id})  ; append
    index-vec = renormalize(v3072[:index-dim])      ; free local downscale (1536/768/…)
    emit tx: {:db/id eid :seon/embedding index-vec :seon.embed/source-hash key}
```

- The archive is loaded into an **in-memory `{key → record}` map** at wire-server
  boot (replay `index.log`; `vectors/` read lazily on demand). Lookup is O(1) and
  off the network — strictly cheaper than the current `d/pull` of the stored
  hash, and global rather than per-entity.
- **Cross-entity / post-wipe / rebuild hits cost zero Gemini.** Two fns with
  identical source, a `cluster reset` followed by re-seed, or a capacity bump all
  resolve from the archive.
- **Concurrency:** the wire-server is the sole writer (single process), so
  `index.log` append is serialized by one in-process lock/agent. No cross-process
  contention (matches the existing single-writer invariant).

### B.4 Rebuild (the capacity raise becomes free)

Because the full normalized vectors live in the archive, the Proximum index is a
pure projection of `(entity-id → vector)` and is rebuildable with **no re-embed**:

```
rebuild-index!(conn, capacity):
  delete-index-store!(index-store-config conn)     ; drop the old HNSW mmap
  install!(conn)                                   ; declare fresh at new capacity
  for record in archive (filtered to the index's MODEL; dim derived locally):
      for eid in record.entity-ids that still exist in the primary store:
          d/transact conn [{:db/id eid
                            :seon/embedding (index-vec (read-vec record) index-dim)}]
                          ; truncate+renormalize to index-dim; no Gemini calls
```

So raising `capacity` from 10,000 → 10,000,000 (or any value) is: bump the const,
`rebuild-index!`. Cost = mmap size + transact time, **zero embedding spend**. This
also covers a Proximum store corruption recovery (today
`:seon.embed/index-restore-failed` is fatal data loss because the vectors live
ONLY in the konserve store — with the archive, restore-failed becomes
"rebuild from archive").

### B.5 Integration points (where it touches existing code)

| Existing | Change |
|---|---|
| `embed-texts` / `embed-batch!` | one-text-per-request + parallel (Part A.5); becomes the **miss** path called by the archive layer |
| `augment-tx-with-embeddings` | insert `archive-get` before `embed-texts`; only the misses go to Gemini; `archive-put!`/`archive-link!` after |
| `backfill!` / `drain-backfill!` | same lookup-before-embed; archive hits make backfill near-instant on re-seed |
| `:seon.embed/source-hash` | store the **folded cache-key** (not a bare content hash); stays the per-entity "changed?" guard + archive pointer |
| `install!` restore-failed path | downgrade from fatal to `rebuild-index!` from archive |
| `capacity` const | now freely raisable via `rebuild-index!` |

The archive is a **new namespace** `seon.embed.archive` (the I/O: `fold-hash`,
`archive-get`, `archive-put!`, `archive-link!`, `load-archive!`, `read-vec`) that
`seon.embed` requires — NOT a parallel embed path. `seon.embed` keeps owning the
Gemini call and the index; the archive only intercepts the cache lookup and
persists vectors. (Turtles: one embed mechanism, the archive is a layer in front
of the network call, not a fork of it.)

---

## Implementation checklist (build wave)

**A. Fix batching for Vertex (correctness — do first):**
- [ ] `embed-batch!`: call `.embedContent` with a **single** text (1-element list
      or the `(model, String, cfg)` overload). Remove the multi-text list call.
- [ ] Replace `plan-batches` + `max-batch-texts` + `max-batch-tokens` with a
      one-Callable-per-text submit to `newFixedThreadPool(min(concurrency, n))`;
      collect in input order. Keep `truncate-to-token-cap` (the 8k per-input cap
      is real).
- [ ] `embed-call-count` increments **once per text** (update the docstring; the
      cache-skip test must still assert no increment on an unchanged re-transact).
- [ ] Raise `max-embed-concurrency` 6 → 24 (const); keep backoff/retry.
- [ ] Update `embed-texts` docstring (no more "≤250 texts/request" — that was the
      Developer API).

**B. Content-addressed archive:**
- [ ] New ns `seon.embed.archive` with schemas
      `:seon.embed.archive/{key,model,dim,task,modality,vec-path,entity-ids,created-at}`
      registered via `schema/register!`.
- [ ] `fold-hash(model, task, content-bytes)` — SHA-256 over the joined fold;
      hex out. **No `dim`** (canonical 3072 stored; smaller dims derived locally —
      keying on dim would defeat the Matryoshka reuse).
- [ ] Local downscale helper `index-vec(v3072, index-dim)` =
      `renormalize(v3072[:index-dim])`; emit that as `:seon/embedding`.
- [ ] `data/embeddings/` layout: `manifest.edn`, sharded `vectors/<ab>/<cd>/…`,
      append-only `index.log`; pack/unpack float32 LE blobs.
- [ ] `load-archive!` (replay `index.log` → in-memory `{key→record}`),
      `archive-get`, `archive-put!` (append blob + log line), `archive-link!`
      (extend `entity-ids`).
- [ ] Single-writer append lock (agent or `locking`) — sole-writer invariant.

**C. Wire into the embed path:**
- [ ] `augment-tx-with-embeddings`, `backfill!`, `reindex!`: lookup-before-embed
      via the archive; only misses hit Gemini.
- [ ] `:seon.embed/source-hash` carries the folded cache-key.
- [ ] `rebuild-index!(conn, capacity)`: drop HNSW store, `install!`, re-transact
      vectors from the archive (no Gemini).
- [ ] `install!` restore-failed → attempt `rebuild-index!` from archive before
      throwing.

**D. Tests / proofs:**
- [ ] Cache-hit proof: embed once, re-transact identical content cross-entity →
      `embed-call-count` unchanged, vector byte-identical.
- [ ] Rebuild proof: embed N, `cluster reset`, re-seed → archive hits, KNN returns
      the same hits, `embed-call-count == 0`.
- [ ] Capacity-raise proof: `rebuild-index!` at 10k → 100k with zero Gemini calls.
- [ ] Bulk-throughput sanity: backfill ~200 fns embeds in well under the old
      timeout at concurrency 24 (no `IllegalArgumentException`).

---

## Appendix — live test commands (reproducible)

Auth (throwaway SA config; never touches the live pipeline):

```bash
export CLOUDSDK_CONFIG=<scratch>/sa-batch
gcloud auth activate-service-account --key-file="$HOME/.config/gcloud/<vertex-sa-key>.json"
TOKEN=$(gcloud auth print-access-token)
EC="https://aiplatform.googleapis.com/v1beta1/projects/<GCP_PROJECT>/locations/global/publishers/google/models/gemini-embedding-2:embedContent"
```

- Single: `curl -X POST "$EC" -d '{"content":{"parts":[{"text":"…"}]},"outputDimensionality":1536}'` → 200, 1536-vec.
- Plural rejected: `-d '{"contents":[…]}'` → 400.
- SDK multi-text (proves the bug): `clojure -Sdeps '{:deps {com.google.genai/google-genai {:mvn/version "1.59.0"}}}'`
  calling `.embedContent(model, ArrayList[3], cfg)` → `IllegalArgumentException:
  "only supports one content at a time"`; `ArrayList[1]` → 200.
- Throughput: `seq … | xargs -P <conc> -I {} curl … --data @payload{}.json -w '%{http_code}\n'`.
- Determinism: two identical calls → byte-identical values (max diff 0.0).
