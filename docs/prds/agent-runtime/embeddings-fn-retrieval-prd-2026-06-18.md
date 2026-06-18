---
type: prd
status: draft
tags: [prd, agent, database, schema, cljs]
---

# Embedding-based function retrieval (Proximum on the wire-server)

Implementation PRD. Phase 1 (datahike sync to upstream + Java 22) is DONE and
live — this is Phase 2. Read the two research docs before coding:
[[research/embeddings-fn-retrieval-2026-06-18]] (design + alternatives) and
[[research/datahike-fork-sync-assessment-2026-06-18]] (what the sync gave us +
the Proximum facts). "Slow is fast" — verify each phase live before the next.

## TL;DR

Today the agent's `<namespaces>` context section **compact-renders every
non-internal namespace** (fn heads / signatures only) — see
`src/seon/ctx.cljs`. As the corpus grows this is both noisy and lossy: the
agent rarely gets the *full source* of the functions actually relevant to its
current task.

Replace "compact-render everything" with **"full source of the top-k most
semantically relevant `:seon.fn` entities, compact-render the rest."**
Retrieval = embed the query → KNN over per-function source embeddings → pull
full source for the top-k eids.

The vector index is a **Proximum secondary index on the JVM wire-server**,
queried by all read-only agents over the existing wire protocol — one
centralized index maintained by the sole writer, NOT a per-agent index and
NOT pod-side (Proximum is JVM-only, Java 22+). Embeddings come from an
**external API**, cached by source-hash so unchanged fns aren't re-embedded.

## Decisions already locked (this session, 2026-06-18)

- **Index home:** Proximum **secondary index on the wire-server** (the sole
  datahike writer), queried over the wire. Not standalone-pod, not per-agent.
- **Embedding source:** **external embeddings API** (not a local model).
- **Granularity:** **per-function** (`:seon.fn`), not per-namespace.
- **Substrate shipped:** datahike synced to upstream (brings the secondary-index
  framework + `:proximum` integration shim); wire-server on Java 22.
- Proximum is on Clojars (`org.replikativ/proximum {:mvn/version "0.1.25"}`);
  needs `src-secondary` on the classpath + JVM flags
  `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
  (we own the fork → expose `src-secondary` via a deps alias).

## Architecture

```
 pod (CLJS, read-only agents)                 wire-server (JVM, sole writer)
 ─────────────────────────────                ──────────────────────────────
 ctx build for a turn                          :proximum secondary index on
   │  query text (task/prompt)                  :seon.fn/embedding (HNSW, cosine)
   │                                            maintained on every tx
   ▼  wire verb: knn-fn-search                  ▲ embed-on-persist:
   ├──────────────────────────────────────────▶│   :seon.fn tee'd → hash source
   │  ← [{eid distance} …] top-k                │   → (cache miss) external embed
   ▼                                            │   → store :seon.fn/embedding
 pull FULL source for top-k eids (local db)     │
   │                                            └ backfill existing fns on boot
   ▼
 render <namespaces>: top-k full source +
   the rest compact (token-budgeted)
```

- **Schema:** `:seon.fn/embedding` (a `:db.type/tuple` of floats) + a
  `:db.secondary/type :proximum` index over it (declared via schema tx;
  datahike backfills + maintains). Possibly `:db.secondary/only true` so the
  raw vector lives only in the secondary, not the primary indices.
- **Embed-on-persist:** hook the existing `:seon.fn` tee/persist path. Hash the
  source; if the hash is unchanged, skip (cache). Else call the embeddings API,
  store the vector. Embedding happens **on the wire-server** (owns the index +
  the API key); the pod never embeds.
- **Wire verb `knn-fn-search`:** pod sends the query *text* (+ optional
  namespace pre-filter as an entity set); wire-server embeds the query, runs
  KNN (`sec/-slice-ordered`), returns `[{:entity-id :distance} …]`. Pod pulls
  full source for those eids from its local db value.
- **ctx integration (`src/seon/ctx.cljs`):** the `<namespaces>` section becomes
  budget-aware — full source for the top-k retrieved fns, compact render (or
  omit) the rest. Always-full for the agent's own current namespace.

## Open design questions — RESOLVE BEFORE 2a

1. **Embeddings provider — BLOCKER.** `SEON_AI_PROVIDER` is currently
   `anthropic`, but **Anthropic has no embeddings endpoint.** A separate
   embeddings provider is required: e.g. OpenAI `text-embedding-3-small`
   (1536-dim) / `-3-large` (3072), Voyage (`voyage-3`, code-tuned), or Cohere.
   Pick provider + model + `:dim` (the Proximum index `:dim` must match). This
   is the one hard prerequisite for 2a.
2. **What is "the query"?** The current turn's user prompt? The task/turn
   description? The accumulated transcript? Affects relevance — start with the
   user prompt + open-todos, iterate.
3. **Compose with the existing compact render:** full top-k + compact-rest, or
   full top-k + DROP the rest? Token budget governs k and per-fn caps.
4. **Index store + lifecycle:** `:store-config` backend = file under the
   cluster store; backfill all `:seon.fn` on first boot after the index is
   declared.
5. **Write-path latency/cost:** embedding on every fn persist adds an external
   call. Source-hash cache makes it once-per-source-change; consider async /
   batched embedding so a tx isn't blocked on the API.

## Implementation plan (phased — verify live at each gate)

- **2a — embeddings adapter** (`seon.ai`-adjacent): external API call → float
  vector, cached by source hash. Sandbox-verify a real vector comes back and is
  stable for identical source. *Gate: provider chosen (Q1).*
- **2b — wire-server index:** add proximum dep + expose `src-secondary` on the
  `:writer` alias; declare the `:proximum` secondary index over
  `:seon.fn/embedding`; embed-on-persist hook + boot backfill. *Gate: KNN over
  real fn vectors returns sensible neighbors (extend `tmp/datahike-sync`'s
  proximum proof with actual seon fn source).*
- **2c — wire verb:** `knn-fn-search` (server: embed query + KNN; pod: client +
  pull full source). *Gate: pod retrieves correct top-k eids over the wire.*
- **2d — ctx integration:** budget-aware `<namespaces>` render swap. *Gate:
  rendered context shows full source for relevant fns, stays within budget.*
- **2e — the actual hypothesis (A/B):** does full-source-top-k beat
  compact-render-all on real agent tasks? Measure via the **gym lane**. This is
  the point of the whole feature — if it doesn't help, stop here.

## Risks

- **Embeddings provider dependency** — cost, latency, an extra API key + outage
  surface on the write path. Mitigate with source-hash cache + async embed.
- **Relevance quality** hinges on query formulation (Q2) — iterate with the gym.
- **The hypothesis may not hold** — 2e is a real gate, not a formality. A cheap
  brute-force-cosine spike (no Proximum) could validate the hypothesis first;
  the decision this session was to go straight to Proximum, but 2e still gates.

## Threads

- Research: [[research/embeddings-fn-retrieval-2026-06-18]],
  [[research/datahike-fork-sync-assessment-2026-06-18]]
- Substrate proof: `tmp/datahike-sync/` (Proximum KNN on Java 22, external consumer)
- Vendored source: `reference-code/proximum` (`proximum.core`, `src-secondary/.../proximum.clj`)
- Code anchors: `src/seon/ctx.cljs` (`<namespaces>` render), `src/seon/db.cljs`
  (wire forwarding), the `:seon.fn` tee/persist path.
