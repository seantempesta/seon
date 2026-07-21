---
type: research
status: draft
tags: [research, agent]
---

# `my.recall` — best out-of-the-box semantic recall (research)

Design research for the agent toolkit tool **`my.recall`** (toolkit-catalog
§"my.recall — IN: semantic (KNN) retrieval"). Capability: read-only,
nearest-by-meaning retrieval over the agent's store (`my.kb` and any other
indexed entity). DESIGN ONLY — no source changed.

## TL;DR

- **Recommendation: `thin-wrap-existing-seon`.** The embedding model + ANN index
  already exist and are authoritative: `seon.embed` (pod, read-only) →
  `seon.store.internal.wire-node/knn-search` (UDS) → the JVM wire-server, which
  embeds the query (Gemini `gemini-embedding-2`, dim 1536, L2-normalized) and runs
  a **Proximum HNSW cosine** KNN inside datahike. The pod **never embeds and holds
  no index**. `my.recall` is a ~60-line CLJS facade over `seon.embed/search-pull`:
  reshape `:seon.embed/hits` → the `:seon.items/*` envelope, add a `:seon.db/ref`
  per item, add an honest `:my.recall/similarity` (= `1 - distance`, valid because
  the metric is cosine over normalized vectors), and convert the `SEON_EMBED`-off
  wire failure into a legible `ok?`-false fallback. This satisfies the "CLJS/Node,
  no JVM in-process" constraint perfectly: the pod-side code is pure Node; the JVM
  heavy lifting is a pre-existing service, not something we add to the pod.
- **`build-fresh` with an npm vector lib is the rejected alternative — and the
  reason is fatal, not merely redundant: VECTOR-SPACE INCOMPATIBILITY.** The
  authoritative vectors are 1536-dim Gemini embeddings. Any in-Node embedder
  (transformers.js MiniLM = 384-dim, a different model and space) produces vectors
  you **cannot** query the existing index with. So an in-pod index would force
  EITHER re-embedding the whole corpus locally with a different model (a second,
  drifting, incompatible index) OR calling Vertex from the pod (defeats
  "pod never embeds / holds no key"). Plus index drift, model-weight/native-addon
  shipping, and a memory blow-up (the pod's budget is ∝ working set; an HNSW over
  the whole store is not). Compared below for completeness.
- **API shape:** one primary verb `(recall {:my.recall/query "…"})` → the RESULT
  envelope wrapping the ITEMS mixin, each item = the pulled entity **+**
  `:seon.db/ref` (re-addressable) **+** `:my.recall/distance` / `:my.recall/similarity`
  (rankable / threshold-able). Optional in-keys: `:my.recall/k`, `:my.recall/within`
  (datalog `:where` type-scope, passthrough), `:my.recall/min-similarity` (relevance
  cutoff), `:my.recall/pull` (pattern override). One thin sibling `recall-refs`
  (ids only, no pull) mirrors the floor's `search` for the ids-only path.

## What already exists (read first)

| Layer | File | Role |
|---|---|---|
| Pod query client | `src/seon/embed.cljs` | `search` (refs+distance) / `search-pull` (refs+distance+pulled entity). Resolves `:where`→eids on the LOCAL db, ships `{query,k,eids}` over UDS, pulls hits locally. **The pod NEVER embeds.** |
| Wire transport | `src/seon/store/internal/wire_node.cljs` | `knn-search` — UDS RPC; returns decoded hits or a not-ok envelope. |
| JVM foundation + KNN | `src/seon/embed.clj` | Owns the Gemini key, the retrieval-prefix query embed, and the Proximum HNSW index. Metric **`:cosine`**, dim **1536**, capacity 10000 (`embed.clj` ~L19, L125-127). `SEON_EMBED` is the master off-by-default gate; unset ⇒ the `knn-search` op is unavailable and the wire returns not-ok. |
| Knowledge schema | `src/my/kb.cljs`, `src/my/kb/shared.cljs` | The `my.kb.<domain>` provenance shapes the recall items mostly carry. |

`seon.embed/search-pull` already returns exactly what we need, one reshape away:

```clojure
{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d :seon.embed/entity {…}} …]}
;; distance-ascending; default-k = 10; default pull = [*] (kind-agnostic wildcard)
```

The shared threading shapes the wrapper targets (`:seon.items/*`, `:seon.result/ok?`,
`:seon.path/*`) are **specified in the catalog but NOT yet registered** in `src/`
— `my.recall` lands after the backbone unit (build order step 1), same as every
other `my.*` wrapper. The only `my.*` ns that exists today is `my.kb`.

## Options compared

### Option A — `thin-wrap-existing-seon` (RECOMMENDED)

`my.recall` (editable `:toolkit-seed` wrapper) over the protected `seon.embed`
floor. Two real jobs, both pure CLJS:

1. **Reshape** `:seon.embed/hits` → the `:seon.items/*` envelope; per item, lift
   the pulled entity to the top, add `:seon.db/ref` (the eid — the REF backbone, so
   the item threads into `db/pull`/`db/entity`/`my.canvas`), and add
   `:my.recall/distance` + `:my.recall/similarity`.
2. **Gate gracefully** — catch the `SEON_EMBED`-off wire failure (`seon.embed/search`
   throws on the not-ok envelope) and the unbound-`*conn*` case, returning a legible
   `ok?`-false `:seon.error/*` map that points the agent at `(db/store-inventory)` +
   datalog + `my.search/grep`. Never throws (Errors-Are-Values).

Pros: zero new heavy deps; ONE embedding model + ONE index (no drift); honors the
read-only pod / sole-JVM-writer architecture; honest cosine similarity available
for free; ~900-tok budget met (the wrapper is thin because the engine is the floor).
Cons: depends on the wire-server being up + `SEON_EMBED` on — handled as the
`ok?`-false fallback, which is the correct behavior anyway.

### Option B — `build-fresh`: in-pod npm embeddings + npm ANN index (REJECTED)

Embed and index inside the Node process. Surveyed npm options (grounded, 2026):

**Local embedders (in-process):**

- **transformers.js v4** (`@huggingface/transformers`) — WASM/onnxruntime, runs in
  Node unchanged; quantized `all-MiniLM-L6-v2` ≈ 23 MB, **384-dim**. Most popular,
  well-maintained. *Different model and dimensionality than the authoritative
  Gemini 1536-dim space.*
- **onnxruntime-node** — bring-your-own ONNX model, optional CUDA; max control,
  more wiring. Same dimensionality-mismatch problem.
- **fastembed-js** — lighter wrapper, smaller model zoo, less active.

**ANN indexes (in-process):**

- **hnswlib-node** — native C++ addon, the canonical Node HNSW; fast, file-persistable.
  A native addon complicates the pod's pure-Node deploy.
- **LanceDB** (`@lancedb/lancedb`) — embedded vector DB, on-disk, native bindings;
  heavier, owns its own storage (a second store beside datahike).
- **Voy** — pure-WASM HNSW, no native addon, but in-memory only / smaller ecosystem.
- **usearch / faiss-node** — fast natives; same native-addon + separate-store cost.

**Why rejected (the killer is #1, not redundancy):**

1. **Vector-space incompatibility.** The authoritative index holds 1536-dim Gemini
   vectors. A local MiniLM/E5 produces 384/768-dim vectors in a *different* space —
   you cannot query the existing index with them. Going in-pod means re-embedding
   the ENTIRE corpus with a second model into a second, never-reconciled index.
2. **Index drift / staleness.** The wire-server's datahike is the source of truth and
   the sole writer; the pod sees writes only via the tx-feed. An in-pod HNSW would
   have to mirror every commit to stay correct — re-implementing the secondary-index
   maintenance that already lives (correctly) on the JVM.
3. **Double-embedding cost + key exposure.** To stay in the Gemini space you'd call
   Vertex from the pod — exactly what the architecture forbids (pod holds no key,
   never embeds).
4. **Deploy + memory.** Native addons (hnswlib/lancedb/usearch) or a ~23 MB+ model
   in the bundle; an index over the whole store breaks the pod's memory-∝-working-set
   budget.

### Option C — `hybrid` (DEFERRED, not now)

A future *fully-offline* pod (no wire-server) could grow its OWN local index via
transformers.js + Voy/hnswlib — but as an independent space for an
independent store, never mixed with the cluster index. No consumer today; same
posture as `my.blob` ("spec the seam, build with the first real need"). Out of
scope for `my.recall`, which targets the existing cluster store.

**Verdict:** `thin-wrap-existing-seon`. Option B is not "we could but it's
redundant" — it is architecturally wrong here (incompatible spaces, drift, key
exposure). Option C is a different product (offline pod), correctly deferred.

## Recommended agent-facing API (map-in / map-out, errors-as-values)

Namespace `my.recall` (editable `:toolkit-seed` wrapper), floor `seon.embed`.
Aligned to the four backbone shapes so outputs thread into inputs with no rekey.

```clojure
;; --- in-keys ---
(schema/register! :my.recall/query          [:string {:min 1}])
(schema/register! :my.recall/k              :int)          ; default below
(schema/register! :my.recall/within         [:vector :any]); datalog :where clauses
                                                           ;   (binds ?e) — a TYPE-SCOPE,
                                                           ;   passthrough to seon.embed/:where
(schema/register! :my.recall/eids           [:set :int])  ; OR already-resolved refs
(schema/register! :my.recall/min-similarity :double)      ; 0..1 relevance cutoff (optional)
(schema/register! :my.recall/pull           [:vector :any]); pull-pattern override (default [*])

;; --- per-item scoring (lives on the item, alongside the lifted entity) ---
(schema/register! :my.recall/distance   :double)   ; raw cosine distance, ascending (0 = identical)
(schema/register! :my.recall/similarity :double)   ; 1 - distance, 0..1, descending (intuitive)

;; --- request / item / response ---
(schema/register! :my.recall/recall-request
  [:map
   [:my.recall/query          :my.recall/query]
   [:my.recall/k              {:optional true} :my.recall/k]
   [:my.recall/within         {:optional true} :my.recall/within]
   [:my.recall/eids           {:optional true} :my.recall/eids]
   [:my.recall/min-similarity {:optional true} :my.recall/min-similarity]
   [:my.recall/pull           {:optional true} :my.recall/pull]])

;; An item = the pulled entity (lifted to the top → self-describing, ITEMS rule)
;; + :seon.db/ref (REF backbone → re-addressable) + the two scores. The entity's
;; own namespaced keys (e.g. :my.kb.codebase/answer) sit beside these without clash.
(schema/register! :my.recall/item
  [:map
   [:seon.db/ref         :seon.db/ref]
   [:my.recall/distance   :my.recall/distance]
   [:my.recall/similarity :my.recall/similarity]])      ; open map: entity attrs also present

(schema/register! :my.recall/recall-response
  [:or
   [:map [:my.recall/ok?       [:= true]]
         [:seon.items/items     [:vector :my.recall/item]]   ; distance-ascending
         [:seon.items/count     :seon.items/count]
         [:seon.items/truncated? :seon.items/truncated?]]
   [:map [:my.recall/ok?       [:= false]]
         [:seon.error/message   :string]
         [:seon.error/data      :map]]])                      ; {:seon.error/kind :feature-off | :user-input}

(defn ^:async recall
  "Semantic KNN over your store: nearest entities to QUERY by MEANING (not exact
   attr/keyword). Returns the :seon.items/items envelope, distance-ascending; each
   item is the pulled entity + :seon.db/ref (thread into db/pull, my.canvas, transact!)
   + :my.recall/distance (raw, lower = nearer) + :my.recall/similarity (0..1, higher
   = nearer). The NEAREST is not necessarily RELEVANT — read the score; pass
   :my.recall/min-similarity to drop far matches. :my.recall/within scopes by a
   datalog :where (binds ?e). Gated by SEON_EMBED on the wire-server; when OFF a
   legible ok?-false points you at (db/store-inventory) + datalog + my.search/grep —
   never an error. Errors are values.
   (recall {:my.recall/query \"what do I know about the user's sleep?\"})"
  {:malli/schema [:=> [:cat :my.recall/recall-request] :my.recall/recall-response]}
  [m] #_…)

;; Optional thin sibling — ids only, no local pull (mirrors seon.embed/search).
;; For when the agent wants refs to thread into its OWN narrowed pull/transform.
(defn ^:async recall-refs
  "Like recall but returns items of {:seon.db/ref :my.recall/distance
   :my.recall/similarity} WITHOUT pulling each entity — cheaper when you only need
   the addresses." {:malli/schema [:=> [:cat :my.recall/recall-request]
                                       :my.recall/recall-response]} [m] #_…)
```

### How it composes (every arrow total — PATH / REF / ITEMS / RESULT)

```clojure
;; recall → filter by relevance → re-pull narrow → show the human
(->> (recall {:my.recall/query "user sleep" :my.recall/min-similarity 0.6})  ; RESULT+ITEMS
     :seon.items/items                                                        ; [item …]
     (map :seon.db/ref)                                                       ; [eid …]  (REF)
     (map #(db/pull db '[:my.kb.codebase/answer] %)))                         ; threads, no rekey

;; recall → persist a derived note with provenance (items ARE namespaced data)
(->> (recall {:my.recall/query "open trading questions"})
     :seon.items/items
     (filter #(> (:my.recall/similarity %) 0.7))
     (map #(assoc % :my.kb/verified-at (js/Date.)))    ; entity attrs already present
     (hash-map :seon.db/tx-data) db/transact!)

;; recall → one human tile (the item is plain namespaced data; my.canvas takes it)
(canvas/card! {:seon.ui/title "Closest match" :seon.ui/body (-> (recall {…}) :seon.items/items first)})

;; within-scope by kind, threading the same datalog vocabulary db/query speaks:
(recall {:my.recall/query "deadlines" :my.recall/within '[[?e :my.kb.codebase/question _]]})
```

`:my.recall/within` accepts the **same** `:where` clauses the agent already writes
for `db/query`; `:my.recall/eids` threads a prior `(map :seon.db/ref items)` back in
as a scope. The output's `:seon.db/ref` is the REF backbone — addresses straight
into `db/entity`/`db/pull`/`my.canvas`.

## Gotchas

- **KNN always returns k — the nearest is not necessarily relevant.** Cosine KNN
  returns the k closest *however far*. Without a cutoff the agent treats a distant
  row as a fact (false confidence — the inverse of the `s32` "re-research" miss). FIX:
  surface the score prominently AND offer `:my.recall/min-similarity`. Document
  "read the score" in the docstring (done above).
- **Distance vs similarity — pick ONE primary, but here BOTH are honest.** The index
  is **cosine over L2-normalized vectors** (`embed.clj` L125-127, L19), so
  `similarity = 1 - distance ∈ [0,1]` is exact, not a fudge. Surface raw
  `:my.recall/distance` (matches the floor, ascending) AND `:my.recall/similarity`
  (intuitive, descending) — and make the threshold the intuitive one
  (`:min-similarity`). Do NOT invent a similarity if the metric were unknown; here it
  is known, so it is safe.
- **Empty ≠ error.** Empty index, a `:within` scope matching no eids, or genuinely
  nothing near ⇒ `{:my.recall/ok? true :seon.items/items [] :seon.items/count 0}`.
  "I know nothing about that" is a valid answer. Only `SEON_EMBED`-off and bad input
  are `ok?`-false (`:seon.error/kind :feature-off` vs `:user-input` — the agent reads
  the kind to decide "fall back to datalog" vs "fix my query").
- **`SEON_EMBED`-off is the common path in dev.** `seon.embed/search` THROWS on the
  wire not-ok envelope; the wrapper MUST catch and convert to the `:feature-off`
  fallback, not propagate. This is most of the wrapper's non-reshape code.
- **k default + context budget.** The floor's `default-k` is 10; each pulled entity
  (`[*]` wildcard) renders into the agent's token-budgeted context. Recommend the
  wrapper default to a SMALLER k (suggest **5**) OR keep 10 but document narrowing
  via `:my.recall/k` / `:my.recall/pull`. Judgment call — flag for the owner; if you
  keep one number, reuse `seon.embed/default-k` rather than minting a second magic
  constant (drift rule).
- **`[*]` wildcard pull is kind-agnostic (good) but can be heavy.** It returns every
  attr a hit carries. For tight context, pass `:my.recall/pull` to project (e.g. just
  the kb answer + provenance). The default stays `[*]` so a fn-only store doesn't
  throw on a missing `:my.kb/*` attr (the floor already chose `[*]` for this reason —
  `embed.cljs` `default-pull-pattern`).
- **`*conn*` unbound** (no live pod db) ⇒ the floor throws; wrap as an `ok?`-false
  `:user-input`/`:core-bug` rather than leaking the ex-info.
- **Shared shapes not yet registered.** `:seon.items/*`, `:seon.result/ok?` land in
  build-order step 1; `my.recall` (step 10) references them. Don't inline them.

## Sources

- `src/seon/embed.cljs`, `src/seon/embed.clj` (L19, L125-127 metric/dim;
  `SEON_EMBED` gate), `src/seon/store/internal/wire_node.cljs` (`knn-search`),
  `src/my/kb.cljs` — the existing seon backing (read in-repo).
- `docs/prds/agent-fsm/toolkit-catalog.md` — `my.recall` spec + the four backbone
  shapes (PATH/REF/ITEMS/RESULT, map-in/map-out, errors-as-values).
- `docs/prds/embeddings/` + memory `project_embedding_fn_retrieval_2026_06_18` —
  the Proximum-on-wire-server retrieval design.
- Web (2026): hnswlib-node (npm / yoshoku) — native HNSW addon; LanceDB docs —
  embedded vector DB; Zilliz "HNSWlib vs Voyager"; HuggingFace "Transformers.js v4"
  (53% smaller bundles, Node-native, quantized MiniLM ≈ 23 MB / 384-dim);
  PkgPulse "Transformers.js vs ONNX Runtime 2026". Used to confirm the in-Node
  embedder/ANN landscape and the vector-space-incompatibility rejection of Option B.
</content>
