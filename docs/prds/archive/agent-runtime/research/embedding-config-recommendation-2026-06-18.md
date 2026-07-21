---
type: research
status: active
tags: [research, agent, database, reference]
---

# Embedding + vector-index config recommendation — Seon fn retrieval (2026-06-18)

> **MODEL UPDATE (later same day, proven live): use `gemini-embedding-2`, not
> `gemini-embedding-001` as written below.** v2 is the current GA model with an
> 8,192-token input cap (vs 001's 2,048 — so the chunking analysis below is
> moot; effectively nothing truncates) but **no `task_type`** — bake the
> retrieval instruction into the query text instead (proven equivalent: 3/3
> correct with clean margins, both standalone and through the datahike
> `:proximum` secondary index). Everything else here (dim 1536, HNSW
> `:M`/`:ef-*`, `:distance :cosine`, `:capacity`, one-shared-index,
> L2-normalize, embed-on-source-hash) is model-agnostic and stands. See
> [[gemini-embeddings-2026-06-18]] for the v2 specifics.

System-specific parameter recommendations for Seon's embedding-based
function-retrieval feature. Every number is justified against OUR system:
a SMALL code corpus (hundreds → low-thousands of `:seon.fn` entities), an
asymmetric NL-query → code-retrieval task, FULL-source injection into a
token-budgeted agent context, and a Proximum HNSW index on the JVM
wire-server.

Grounded in the real vendored source:

- Proximum standalone API + defaults: `reference-code/proximum/src/proximum/hnsw.clj:1158-1234`
  (the `create-index :hnsw` defmethod), `docs/CLOJURE_GUIDE.md` (config
  table L267-274, tuning table L851-865), `docs/PERSISTENCE.md` (storage
  model).
- Datahike Proximum config table: `reference-code/datahike/doc/secondary-indices.md:384-394`
  + the integration shim `src-secondary/datahike/index/secondary/proximum.clj`.
- Gemini SDK / model facts: `docs/prds/agent-runtime/research/gemini-embeddings-2026-06-18.md`.
- Corpus measurements: this session, against `src/seon/*.cljs` (1116 `defn`
  blocks measured).

## TL;DR — the headline numbers

| Knob | Recommendation | Why (for OUR system) |
|------|----------------|----------------------|
| `outputDimensionality` | **1536** | Proven clean margin live; storage trivial at our scale; 3072 buys nothing for hundreds of vectors |
| Proximum `:distance` | **`:cosine`** | Gemini vectors + client-side L2-normalize |
| Proximum `:M` | **16** | Library default; over-provisioned already for our recall-easy scale |
| Proximum `:ef-construction` | **200** | Default; build cost is negligible at hundreds of vectors |
| Proximum `:ef-search` | **100** | Above default 50 — recall is free here, latency irrelevant |
| Proximum `:capacity` | **10000** | ~10-50x headroom over current corpus; reallocation is the only real cost |
| taskType (index) | **`RETRIEVAL_DOCUMENT`** | confirmed live |
| taskType (query) | **`CODE_RETRIEVAL_QUERY`** (fallback `RETRIEVAL_QUERY`) | confirmed live |
| What to embed (fn) | **name + ns + docstring + full source** | one composed document string per fn |
| Index layout | **ONE shared index**, entity-type in metadata + an entity-filter set | small corpus; entity-filter is native |
| Chunking | **per-fn, no chunking** for ~97% of fns; pre-truncate the ~3% over cap | only 36/1116 fns exceed the 2048-token cap |
| `k` | **8** (cap per-fn full source, budget-governed) | fits ctx token budget with margin |
| Re-embed | **on source-change, keyed by SHA-256 of the composed doc string**; batch via `embedContent(model, List<String>, cfg)` | avoid re-embedding unchanged fns |

---

## 1. `outputDimensionality` — recommend **1536**

The model is `gemini-embedding-001` (native ~3072, Matryoshka-reducible to
1536 / 768 via `EmbedContentConfig.outputDimensionality`, reduced dims MUST
be client-side L2-normalized —
`research/gemini-embeddings-2026-06-18.md` "Universal model facts").

**Recommendation: 1536.** Reasoning specific to Seon:

- **It already works with a clean margin.** This session's live spike on 6
  real Clojure fns returned the right fn at #1 at 1536 dims with hit cosine
  distance ~0.42-0.49 vs next ~0.65-0.9. We have empirical evidence that
  1536 separates code semantics well for OUR corpus. Do not regress to an
  untested dim.
- **Corpus is SMALL → recall is trivially easy.** Hundreds of vectors. The
  marginal recall gain of 3072 over 1536 (already small in MRL benchmarks)
  is irrelevant when the whole corpus is brute-forceable. The dim choice
  here is governed by storage/latency economy, NOT max recall — exactly the
  brief's framing.
- **Storage is a non-issue at 1536, but 3072 doubles it for ~nothing.** At
  1536 floats × 4 bytes = 6 KiB/vector. For 1000 fns that's ~6 MiB of raw
  vectors (memory-mapped on the wire-server, `PERSISTENCE.md` VectorStorage
  — `64 + node_id × dim × 4` byte layout). 3072 = ~12 MiB. Both negligible,
  but 3072 doubles the mmap footprint, the distance-compute cost per query
  (`distance-squared-to-node` is O(dim), `PERSISTENCE.md`), and the bytes
  shipped per embed call, with no retrieval payoff at this scale.
- **768 is the downside option** if we ever want to halve storage; at our
  scale there's no reason to, and we'd be re-validating margin we haven't
  measured. Stay at 1536.

**Tradeoff stated:** 1536 trades a theoretically-tiny recall ceiling
(vs 3072) for half the storage/compute and — more importantly — keeps the
exact configuration we already proved live. At hundreds of vectors the
recall ceiling is unreachable anyway.

**The Proximum `:dim` MUST equal this number** (`hnsw.clj:1170` throws if
`:dim` missing; `CLOJURE_GUIDE.md` "Dimension mismatch" throws on insert if
a vector's length ≠ `:dim`). So `:dim 1536`.

## 2. Proximum HNSW params — concrete values + exact key names

**Exact config keys (confirmed from source).** Two layers, and they differ —
read this carefully:

- **Standalone `proximum.core/create-index`** destructures **`:M`
  (uppercase)**, `:ef-construction`, `:ef-search`, `:dim`, `:distance`,
  `:capacity`, `:store-config`, `:mmap-dir`, `:cache-size`, `:crypto-hash?`
  (`hnsw.clj:1158-1169`). Defaults: `M 16`, `distance :euclidean`,
  `capacity 10000000`, `chunk-size 1000`, `cache-size 10000`,
  `crypto-hash? false`. `M0` is auto-derived as `2*M`
  (`hnsw.clj:1173`). `ef-construction`/`ef-search` default to
  `recommended-ef-construction`/`recommended-ef-search` when omitted
  (`hnsw.clj:1175-1176`).
- **Datahike secondary `:db.secondary/config`** table documents **`:m`
  (lowercase)**, `:ef-construction`, `:ef-search`, `:dim`, `:distance`,
  `:capacity`, `:store-config` (`secondary-indices.md:384-394`).

> **CODE SMELL / BUG — flag for the implementer.** The datahike shim does
> `(select-keys config [:dim :distance :store-config :mmap-dir :capacity :m
> :ef-construction :ef-search])` and passes the result straight to
> `prox/create-index`
> (`src-secondary/datahike/index/secondary/proximum.clj:156-159`). But
> `create-index` destructures **`:M`**, not `:m`. So an `:m` supplied in
> `:db.secondary/config` is **silently dropped** and M falls back to the
> default 16. (`:ef-construction`/`:ef-search` keys DO match and pass
> through.) Two consequences: (a) you cannot currently tune M through the
> datahike secondary path; (b) the doc table's `:m` is misleading. This is
> probably a bug in the shim (should remap `:m` → `:M`). Since the PRD's
> locked decision is to use the **standalone `proximum.core`** API anyway
> (`embeddings-fn-retrieval-2026-06-18.md` "Recommendation"), we sidestep
> it — but flag it if we ever switch to the secondary path, and consider a
> one-line fix to the shim.

**Recommended values for a few-hundred → low-thousands corpus:**

```clojure
{:type            :hnsw
 :dim             1536          ; MUST match outputDimensionality (§1)
 :distance        :cosine       ; Gemini + L2-normalize (default is :euclidean — override!)
 :M               16            ; library default; ample
 :ef-construction 200           ; CLOJURE_GUIDE "balanced" preset
 :ef-search       100           ; above default 50 — recall is free at our scale
 :capacity        10000}        ; 10-50x headroom; avoid reallocation
```

What each knob trades off, justified for OUR scale:

- **`:distance :cosine`** — NOT optional to set. The default is
  `:euclidean` (`hnsw.clj:1163`). Gemini embeddings are compared by cosine;
  we L2-normalize reduced-dim vectors, so cosine is correct. (With unit
  vectors, cosine and inner-product rank identically; cosine is the
  documented choice for normalized embeddings, `CLOJURE_GUIDE.md:872-874`.)
- **`:M 16`** — max neighbors per node; higher = better recall + more
  memory, lower = faster build (`CLOJURE_GUIDE.md:851-865`). 16 is the
  default and the documented sweet spot (16-32). At hundreds of vectors HNSW
  is already near-exhaustive; raising M buys recall we don't need and costs
  memory (`(M+1)` int slots/node/layer, `PERSISTENCE.md` chunk layout). Keep
  16.
- **`:ef-construction 200`** — build-time beam width; higher = better graph
  quality, slower inserts (`CLOJURE_GUIDE.md:851-865`). 200 is the
  "balanced" preset. Build cost is irrelevant for a few hundred one-time
  inserts (and backfill is a one-shot boot operation), so 200 is free
  quality. (Could go to 400 for the "recall-critical" preset; unnecessary
  here.)
- **`:ef-search 100`** — query beam width; higher = better recall, slower
  search (`CLOJURE_GUIDE.md:851-865`; read at query time
  `hnsw.clj:720` as `(max k (or (:ef opts) (:ef-search state) 50))`). We
  raise it above the default 50 because at our corpus size a "slow" search
  is still sub-millisecond and recall is the only thing we care about. It's
  also overridable per-query via `(prox/search idx q k {:ef 200})`
  (`CLOJURE_GUIDE.md:395-413`) if a specific query needs more.
- **`:capacity 10000`** — preallocates the mmap/edge arrays
  (`hnsw.clj:1199, 1211-1212`); the ONLY hard failure mode is exceeding it
  ("Index capacity exceeded. Create a new index with larger :capacity",
  `hnsw.clj:634, 675`). Default is 10M which over-allocates memory
  (`CLOJURE_GUIDE.md:884-892`). 10000 gives ~10-50x headroom over the
  current corpus while keeping preallocation modest; bump it (and rebuild)
  only if the corpus genuinely approaches it.

**Persistence config** (`:store-config` + `:mmap-dir`): file backend under
the cluster store. Per `PERSISTENCE.md`, the konserve store is the source of
truth and the mmap dir is the SIMD runtime cache; both are local-filesystem
on the wire-server. Keep the mmap dir on fast storage (it's already local).
Call `prox/sync!` after the backfill batch and after each embed-on-persist
batch (`CLOJURE_GUIDE.md:902-916` — sync in batches, not per-insert).

## 3. taskType pairing — confirmed

- **Index time → `RETRIEVAL_DOCUMENT`.** Confirmed live this session.
- **Query time → `CODE_RETRIEVAL_QUERY`.** Confirmed live this session
  (the asymmetric NL→code path; this is the whole point — the query is
  natural-language, the documents are code).
- **Fallback → `RETRIEVAL_QUERY`** if `CODE_RETRIEVAL_QUERY` is ever
  rejected (it's a free string at the SDK layer — `EmbedContentConfig.java`
  `taskType(String)` — so validity is an API contract, per
  `gemini-embeddings-2026-06-18.md` Q4). We've confirmed it works today;
  keep `RETRIEVAL_QUERY` documented as the degradation path.

Set both via `EmbedContentConfig.builder().taskType("...")`. The doc and the
query MUST use their respective taskTypes — mixing them degrades the
asymmetric retrieval Gemini is trained for.

`outputDimensionality(1536)` goes on BOTH configs (index and query) so the
vectors live in the same reduced space.

## 4. What text to embed per entity type

The unit of retrieval is what we inject, so embed a composed **document
string** that mirrors what a human would read to decide "is this fn
relevant?"

### `:seon.fn` (primary) — **name + ns + docstring + full source**

```
<ns>/<name>
<docstring>
<full source string>
```

Concretely, one string:

```clojure
(str fn-ns "/" fn-name "\n"
     (when docstring (str docstring "\n"))
     fn-source)
```

Rationale for OUR system:

- **Name + ns** give the symbol a semantic anchor (`seon.agent.findings/...`
  carries domain signal that bare source doesn't). The agent's NL query
  ("how do I record a finding") matches the name/ns tokens strongly.
- **Docstring** is the single best NL↔code bridge — it's already
  natural-language describing intent, which is exactly the query's
  modality. Include it when present.
- **Full source** is what we ultimately INJECT (the feature replaces
  compact-render-everything with full-source-of-top-k), and
  `CODE_RETRIEVAL_QUERY`/`RETRIEVAL_DOCUMENT` are trained on code bodies.
  Embedding the same text we inject keeps "what matched" and "what the agent
  sees" aligned. Source-alone is weaker than source+name+docstring for NL
  queries, so include all three.

Do NOT add schema/spec metadata into the embedded text unless measured to
help — it's structured noise relative to the NL query. (The schema is
already discoverable via the graph; retrieval is about relevance, not
completeness.)

### `:seon.ns` (coarse "where things live" signal) — **ns name + ns docstring + member-name list**

If we index namespaces at all, embed a SUMMARY, not the whole ns body:

```
<ns-name>
<ns docstring>
defs: <name1> <name2> <name3> ...
```

Rationale: whole-ns contents would (a) blow the 2048-token cap routinely
(ctx.cljs is 2439 lines) and (b) dilute the vector — a namespace is a bag of
unrelated fns, so its centroid is mushy. A name+docstring+member-list
summary is a clean coarse signal for "which namespace owns this area"
without the cap problem. **Lower priority than fn-level** — start fn-only
(the PRD's locked granularity is per-`:seon.fn`), add ns-summaries only if
the gym shows a "where does X live" gap.

### `my.kb.*` (knowledge base — prose/instructions) — **title + body, chunked if long**

KB rows are already prose, the ideal embedding input. Embed
`title + "\n" + body`. These are the rows most likely to exceed 2048 tokens
(prose docs), so apply the chunking strategy in §6 to KB specifically.

### Query-text construction (what becomes the query)

Per the PRD's open Q2, start with: **the current turn's user prompt +
the open todos / task description**, joined into one query string. NOT the
whole transcript (it dilutes the signal). The query is NL; embed it once per
turn with `CODE_RETRIEVAL_QUERY`. Iterate query composition against the gym
(PRD phase 2e) — this is the single biggest relevance lever and is cheap to
tune.

## 5. One shared index vs per-type indices — recommend **ONE shared index**

Recommendation: **a single Proximum index** holding fn / ns / kb vectors,
with the **entity type carried in Proximum metadata** and filtered retrieval
when you want one type.

Why one index for OUR system:

- **Corpus is tiny.** Three indices triples lifecycle/sync/backfill code and
  mmap files to manage, for hundreds of total vectors. No.
- **Entity-filtering is native and cheap.** Proximum's
  `search-filtered idx query k allowed-ids` takes a SET of external IDs and
  KNN-searches only within it (`api_impl.clj:80-106`,
  `CLOJURE_GUIDE.md:440-450`). Through datahike's secondary layer the same
  falls out via the `EntityBitSet` entity-filter
  (`secondary-indices.md:340-363` — "Composing Indices with Entity
  Bitmaps"). So "fns only" / "kb only" is one filtered call, not a separate
  index.
- **Mixed retrieval is sometimes what we want.** "How do I record a finding"
  might best be answered by the fn AND the KB note about findings — a single
  index returns both ranked together; separate indices force an awkward
  merge.

**How to tag entity type** (two equivalent options, pick by path):

- **Standalone API (recommended path):** store type in the vector's
  metadata — `(prox/insert idx vec eid {:seon.embed/type :fn})` (metadata
  travels with the vector, `CLOJURE_GUIDE.md:620-660`,
  `api_impl.clj:48-51`). To restrict to one type, build the allowed-id set
  from the DB (`(d/q ... :seon.fn)`) and pass it to `search-filtered`. Note
  `CLOJURE_GUIDE.md:690` explicitly advises maintaining the metadata→id
  mapping in Datahike for large-scale filtering — which we already have
  (entity types are first-class in the store), so the filter set is a plain
  Datalog query.
- **Datahike secondary path:** the entity type is already a datom
  (`:seon.fn`/`:seon.ns`/`my.kb.*`), so the entity-filter `EntityBitSet`
  comes straight from a query — no extra metadata needed.

The external ID is the `:seon.fn` (or ns/kb) entity-id in both cases — pass
the eid directly as the Proximum external id (`CLOJURE_GUIDE.md:135-141`
"assoc idx 12345 vector — External ID: Datahike entity"), so a KNN hit maps
straight back to a datahike pull for full source.

## 6. Chunking — **per-fn, no chunking for ~97%; pre-truncate the long tail**

Measured this session across 1116 `defn` blocks in `src/seon/*.cljs`:

| Metric | chars | approx tokens (~3.5 ch/tok) |
|--------|-------|------------------------------|
| median fn | 871 | ~250 |
| p95 fn | ~5341 | ~1500 |
| max fn | 29044 | ~8000 |
| fns > ~7000 chars (> ~2048 tok) | **36 of 1116 (~3.2%)** | over cap |

**Conclusion: per-fn embedding with NO chunking for ~97% of functions.**
Median fn is ~250 tokens — nowhere near the 2048-token cap. The cap bites
only the long tail (~3%, the genuinely huge fns like `ctx-entities` at ~143
lines / ~8181 chars). For those:

- **Simplest, recommended:** pre-truncate to the first ~2000 tokens of the
  composed doc string before embedding (keep name + ns + docstring + as much
  source as fits). The head of a Clojure fn — signature, docstring, the
  primary `let`/dispatch — carries most of the semantic signal; the tail is
  usually a long `cond`/hiccup body. A truncated embedding for a 3% tail is
  fine because we still INJECT the full source on retrieval (the embedding
  is only for *finding* it). **Enforce the cap on OUR side** — the SDK
  `truncated()` flag is Enterprise-only and likely absent on the Developer
  API (`gemini-embeddings-2026-06-18.md` Q6).
- **If a tail fn's retrieval quality matters** (measured gap in the gym),
  split THAT fn into head + body chunks, embed each, index both under the
  same eid with a `:chunk` metadata field, and dedupe to the eid on
  retrieval. Do this only for flagged fns — do not build a general chunker
  for a 3% case.

A 36-fn long tail is "flag the rare long ones," not "chunking is needed."
**KB prose rows** are the more likely chunking candidates (§4) — apply
head-chunking there if KB docs routinely exceed the cap.

## 7. `k` + token budget — recommend **k = 8**

Retrieve **top-8** by default, inject full source for those, compact-render
(or drop) the rest, governed by the ctx token budget.

Reasoning for OUR system:

- Median fn ~250 tokens, p95 ~1500. Eight full-source fns ≈ 2k-8k tokens
  typical, ~12k worst case — comfortably inside a ctx `<namespaces>` budget
  while leaving room for the transcript/prompt/soul sections (per
  `src/seon/ctx.cljs` `assemble-context` budgeting).
- k=8 gives enough relevant context to cover a task that touches a couple of
  fns + their neighbors, without flooding the window. Start at 8; make it
  **budget-driven** — fill full-source top-k until the `<namespaces>` budget
  is hit, then stop (so a turn full of huge fns naturally retrieves fewer).
- Always include the agent's **own current namespace at full source**
  regardless of KNN (PRD 2d) — that's not part of the k budget.
- Use the distance margin as a quality gate: this session saw hits at
  ~0.42-0.49 vs next ~0.65+. Consider `:min-similarity` / a distance cutoff
  (`CLOJURE_GUIDE.md:406-407`) so a query with no good match injects FEWER
  than k rather than k weak ones.

## 8. Re-embedding / caching — source-hash keyed, batched

- **Embed-on-source-change, keyed by a hash of the COMPOSED DOC STRING**
  (name+ns+docstring+source — §4), not the raw source, so a docstring edit
  re-embeds too. Store the hash alongside the vector (`:seon.fn/embed-hash`).
  On persist of a `:seon.fn`: recompute the hash; if unchanged, skip the
  embed call entirely (the dominant case — most txs don't touch most fns).
  This is the cost control: embeddings are an external paid call on the
  write path.
- **Use SHA-256** of the composed string (hex). Cheap, collision-safe for
  this purpose.
- **Batch at index/backfill time.** The SDK's
  `embedContent(model, List<String>, config)` overload sends ONE HTTP
  request for the whole list, embeddings returned in input order
  (`gemini-embeddings-2026-06-18.md` Q2). Backfill all fns on first boot in
  batches (~100 texts/request, back off on error — Q2). Use the single-string
  overload only for query-time and one-off persists.
- **Async / off the tx path.** Embedding on every fn persist adds an external
  call; the source-hash cache makes it once-per-change, but still consider
  embedding asynchronously (queue the eid, embed + `insert` + `sync!` in a
  background batch) so a tx isn't blocked on the API (PRD Q5).
- Insert into Proximum with `insert-batch` / `into` (transient-backed,
  `CLOJURE_GUIDE.md:298-314, 816-847`) for the backfill; single `insert`
  for incremental persists. `sync!` after each batch, not per vector.

---

## Recommended config (copy-paste)

### Gemini `EmbedContentConfig` (Java SDK)

```java
// INDEX time (documents): name + ns + docstring + full source
EmbedContentConfig docCfg = EmbedContentConfig.builder()
    .taskType("RETRIEVAL_DOCUMENT")
    .outputDimensionality(1536)
    .build();

// QUERY time (the turn's NL prompt + open todos)
EmbedContentConfig queryCfg = EmbedContentConfig.builder()
    .taskType("CODE_RETRIEVAL_QUERY")   // fallback: "RETRIEVAL_QUERY"
    .outputDimensionality(1536)
    .build();

// model id (free String arg)
String model = "gemini-embedding-001";
// Reduced-dim vectors (1536 < native 3072) MUST be L2-normalized client-side
// before insert/search.
```

### Proximum index — standalone `proximum.core/create-index` (recommended path)

```clojure
(require '[proximum.core :as prox])

(def fn-index
  (prox/create-index
    {:type            :hnsw
     :dim             1536            ; == outputDimensionality
     :distance        :cosine         ; override the :euclidean default
     :M               16              ; UPPERCASE :M (standalone API)
     :ef-construction 200
     :ef-search       100
     :capacity        10000
     :store-config    {:backend :file
                       :path    "<cluster-store>/proximum/fn-vectors"
                       :id      (random-uuid)}
     :mmap-dir        "<cluster-store>/proximum/mmap"}))

;; index time (one shared index; type in metadata)
(prox/insert fn-index norm-vec fn-eid {:seon.embed/type :fn})
;; backfill: (prox/insert-batch fn-index vecs eids {:metadata metas}) then (prox/sync! ...)

;; query time
(prox/search fn-index norm-query-vec 8 {:ef 100})              ; all types
(prox/search-filtered fn-index norm-query-vec 8 allowed-fn-eids) ; fns only
```

### Proximum via the datahike secondary index (ALTERNATIVE path — note the `:m` bug)

```clojure
(d/transact conn
  [{:db/ident :idx/embeddings
    :db.secondary/type   :proximum
    :db.secondary/attrs  [:seon.embed/vector]
    :db.secondary/config {:dim          1536
                          :distance     :cosine
                          :ef-construction 200
                          :ef-search    100
                          :capacity     10000
                          ;; NOTE: the doc table lists :m, but the shim's select-keys
                          ;; picks :m while create-index destructures :M — :m is DROPPED,
                          ;; M stays 16. (See §2 code-smell.) Use the standalone path,
                          ;; or fix the shim to remap :m -> :M, if you need M != 16.
                          :store-config {:backend :file :path "..." :id (random-uuid)}}}])
```

---

## Things I could NOT determine from the sources (flagged, not guessed)

- **Exact Gemini token/batch ceiling for the `List<String>` overload.** The
  SDK source imposes no explicit cap on Developer-API batch size
  (`gemini-embeddings-2026-06-18.md` Q2); ~100/request is a starting guess to
  confirm live, not a sourced number.
- **Whether `CODE_RETRIEVAL_QUERY` stays valid long-term.** It's a free
  string at the SDK layer; confirmed working this session, but it's an API
  contract, not enforced — keep `RETRIEVAL_QUERY` as the fallback.
- **char→token ratio.** I used ~3.5 chars/token to map the measured char
  distribution onto the 2048-token cap. The "~3% of fns over cap" figure is
  robust to a ±1 ch/tok error (the over-cap fns are 7000-29000 chars, far
  above the boundary), but the exact count near the threshold is approximate.
  Confirm with a real tokenizer if a precise cap-bite count matters.
- **`recommended-ef-construction` / `recommended-ef-search` exact formulas**
  (`hnsw.clj:1175-1176`) — we set both explicitly, so the auto-defaults don't
  apply; their internals weren't read.
