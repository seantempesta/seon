---
type: prd
status: draft
tags: [prd, agent, database, schema, cljs]
---

# Embedding-based function retrieval (Proximum on the wire-server)

> ## HANDOFF — read this first (2026-06-18 end of session)
>
> **Branch:** `feature/embeddings` (tracked on origin). `origin/main` is current
> (`7d25126`) with all completed eval/ctx work + the embedding FOUNDATION.
> Work continues on `feature/embeddings`.
>
> **SCOPE: this embeds EVERYTHING, not just functions.** "fn retrieval" is the
> originating use case; the substrate is general. First-class entity kinds:
> **functions, the knowledge base (`my.kb.*` — high value, standing
> instructions/knowledge), namespaces**, and arbitrary future kinds. Adding a
> kind = (1) write `:seon/embedding` datoms for it, (2) add one `embed-text`
> clause — NO schema/index change. **Build everything general from the start;
> do NOT bake in fn-specificity.** Concretely: name the index
> `:seon.embed/index` (NOT `fn-index` — P2-A's `seon.embed/install!` currently
> says `:seon.embed/fn-index`, RENAME it), the wire verb `knn-search` (NOT
> `knn-fn-search`), and `seon.embed/search` takes a kind-agnostic query. Add a
> uniform **`:seon/kind`** keyword attr on every embeddable entity (e.g.
> `:seon.fn` / `:seon.kb` / `:seon.ns`) — it drives both the `embed-text`
> multimethod dispatch AND clean type-scoped filtering
> (`:where [[?e :seon/kind :seon.kb]]`).
>
> **Locked design (do NOT re-litigate):**
> - ONE shared attr `:seon/embedding` (vector of float, dim **1536**), ONE
>   Proximum secondary index over it on the JVM wire-server, queried over the
>   wire. Type-scoping via datalog `:where` → eid set → Proximum entity-filter
>   (any characteristic, incl. `:seon/kind`). New embeddable kinds need NO
>   schema change — just `:seon/embedding` datoms + an `embed-text` clause.
> - Model: **`gemini-embedding-2`** (current GA, 8192-token, **no `task_type`** —
>   put the retrieval instruction in the query text), **cosine**, L2-normalize.
>   Called from the JVM wire-server via `com.google.genai:google-genai` 1.59.0
>   (the **Java** SDK; embedding co-located with the index). `GEMINI_API_KEY` is
>   in env. Vendored: `reference-code/java-genai`, `reference-code/proximum`.
> - Interface (proposed, confirmed): `seon.embed/search`/`search-pull`
>   (^:async, map-in/out `:seon.embed/{query,k,where,eids}`), `install!`,
>   `ensure-embedding!`/`reindex!`. See the "interface" discussion in the
>   research docs.
> - PROVEN live (3/3, clean margins): `tmp/embed-spike/` — standalone
>   `proximum.core` AND through the datahike `:proximum` secondary index with
>   real Gemini v2 embeddings on real Clojure fn source.
>
> **Foundation DONE (P2-A, committed `7d25126`, INERT — `install!` not yet
> called on boot, no pipeline):** `deps.edn :writer` jvm-opts
> (`--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
> -XX:+UseG1GC -Xmx2g`) + `src-secondary` path + proximum/google-genai deps;
> `seon.db` `:db.secondary/*` bridge (`db/datahike/schema.clj`,
> `db/internal.cljs`); `seon.embed/install!`; `boot.clj` requires the proximum
> type. datahike fork pinned `@6cf05300` (4 sites) — already carries the shim
> coerce(vector→float[]) + `:m`→`:M` patches.
>
> **★ NEXT TASK — the foundation's one real flaw (fix BEFORE the pipeline):**
> The index currently **rebuilds from AEVT on every reopen** instead of
> **restoring from its konserve store** — defeating Proximum's whole
> persistence/versioning point. Root cause: the datahike→Proximum shim
> (`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj`)
> implements `IVersionedSecondaryIndex` (`-sec-persist` via `proximum.sync!`,
> `-sec-restore` via `load-commit`) BUT its `create-index` **eagerly creates a
> fresh konserve store even for the reopen "skeleton"** (datahike calls
> `create-index` with nil db → then `-sec-restore`), so the fresh store collides
> with the one being restored. The P2-A agent worked around it by dropping
> `:db.secondary/only` + rebuilding from AEVT — WRONG direction. Fix it at the
> root in our fork: make open **restore-if-exists** (let `load-commit` populate;
> don't allocate a store for the skeleton), re-enable `:db.secondary/only`
> (vector lives only in Proximum's konserve store), verify a real reopen
> RESTORES (instrument `load-commit` runs + no AEVT backfill), then
> commit→push→bump (datahike fork `sync-upstream`, bump the 4 deps.edn shas).
> Confirm the exact failure mode live first (it's my read of the code + the
> agent's report).
>
> **Then (build general — fns AND KB are first-class, ns next):**
> - **P2-B** write side: `embed-text` multimethod on `:seon/kind` with clauses
>   for `:seon.fn` (ns/name+doc+source) AND `:seon.kb` (title+body) from the
>   start; `ensure-embedding!`/`reindex!`; Gemini via java-genai; source-hash
>   cache; embed BEFORE transact; wire `install!` into boot + backfill BOTH
>   `:seon.fn` and `my.kb.*`.
> - **P2-C** query side: `knn-search` wire verb (kind-agnostic) + pod
>   `seon.embed/search`/`search-pull` with `:where` filtering (scope by
>   `:seon/kind` or any attr).
> - **P2-D** ctx integration — MULTI-CONSUMER, not just `<namespaces>`: retrieved
>   fns feed the fns section, retrieved KB feeds a relevant-knowledge section,
>   etc. Each ctx section that wants relevance calls `seon.embed/search` with its
>   own `:where`. → 2e gym A/B (does retrieval beat the current static render).
>
> Java 22 is selected by `bin/seon` cross-platform (macOS/Linux/WSL).

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

- **Schema:** `:seon/embedding` (a `:db.type/tuple` of floats, cardinality/one)
  + a `:db.secondary/type :proximum` index over it (declared via schema tx;
  datahike backfills + maintains). **DECIDED (P2-A): NOT `:db.secondary/only`** —
  the raw vector lives in the PRIMARY AEVT (durable truth) and the Proximum HNSW
  is a derived cache datahike rebuilds from AEVT. See the 2b FOUNDATION note for
  why `:db.secondary/only` is fatal here.
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
  `:seon/embedding`; embed-on-persist hook + boot backfill. *Gate: KNN over
  real fn vectors returns sensible neighbors (extend `tmp/datahike-sync`'s
  proximum proof with actual seon fn source).*

### 2b FOUNDATION — BUILT (2026-06-18, synthetic-vector KNN proven live)

The substrate for 2b is in place and proven on the live wire-server with
synthetic normalized vectors (the Gemini embed pipeline is the remaining 2b
work — see below). Locked facts:

- **`:writer` alias (`deps.edn`):** added `:jvm-opts ["--add-modules"
  "jdk.incubator.vector" "--enable-native-access=ALL-UNNAMED" "-XX:+UseG1GC"
  "-Xmx2g"]`; appended `reference-code/datahike/src-secondary` to
  `:extra-paths` (stable repo-relative path; the datahike submodule is pinned
  to the same `:git/sha` `6cf05300` — a comment warns the two MUST stay
  aligned); added `org.replikativ/proximum {:mvn/version "0.1.25"}` and
  `com.google.genai/google-genai {:mvn/version "1.59.0"}` to `:extra-deps`.
- **Malli→datahike bridge (BOTH siblings, byte-aligned):**
  1. `system-attr?` (`seon.db.internal` cljs + `seon.db` clj) broadened from
     "namespace == `db`" to "namespace == `db` OR starts-with `db.`" — so the
     validation gate no longer rejects the `:db.secondary/*` family (datahike
     treats the whole `:db.*` cluster as system attrs; see `schema.cljc`
     `::schema-attribute` / `::secondary-index-attribute`).
  2. vector-of-floats tuple bridge branch (`schema->attr-partial` /
     `malli-map->datahike-schema` clj — UPDATED P2-A): a `[:vector :float]`
     (or `:double`), with or without `:db.secondary/only`, emits a SINGLE tuple
     value — `:db/valueType :db.type/tuple`, `:db/cardinality
     :db.cardinality/one` — instead of the cardinality-many a bare `[:vector
     X]` of non-floats gives. Keyed off the FLOAT inner-type (an embedding is
     one tuple, never N scalar datoms), NOT the literal attr keyword.
     `:db.secondary/only` is now ORTHOGONAL: when present it is passed through
     (and still requires a float inner — non-float `:db.secondary/only` throws);
     when absent the tuple persists in the PRIMARY AEVT. `:seon/embedding` is
     registered WITHOUT `:db.secondary/only` (durable + restore-safe). NOTE: the
     `cljs` sibling (`seon.db.internal/malli->datahike-attr`) was left on the
     original `:db.secondary/only`-keyed form — the pod does not register
     `:seon/embedding` (single-segment ns is `:cljs`-gated out), so the
     divergence is harmless; converge it if the pod ever needs a float tuple.
- **`seon.embed` (`src/seon/embed.clj`, JVM/wire-server only):**
  - registers `:seon/embedding` as `[:vector :float]` (NO `:db.secondary/only`
    — see DURABILITY below). **Locked attr name: `:seon/embedding`**
    (single-segment namespace, deliberate — the attr is cross-cutting).
    Registration runs only from this `.clj` ns; the pod's `:cljs`-gated
    `assert-multi-segment-namespace!` WOULD reject `:seon/embedding`, so the pod
    must NOT register it (FLAG for 2c/2d if the pod ever needs it registered).
  - `install!` (idempotent + restore-safe) transacts the `:seon/embedding` attr
    decl (derived via the bridge, not hand-written) + the
    **`:seon.embed/fn-index`** `:proximum` secondary index over it: **dim 1536,
    distance `:cosine`, capacity 10000**. It only (re)instantiates when the
    index is NOT live (`index-live?` checks `:secondary-indices`, not the
    schema), clearing the stale konserve store id (`delete-index-store!`) FIRST
    so `create-index` never collides.
  - **DURABILITY — RESOLVED (P2-A): drop `:db.secondary/only`.** With it, the
    primary indices hold only a content HASH and the full vector lives only in
    the secondary; if the in-memory Proximum store fails to restore (it always
    does — see RESTORE-ON-OPEN), the vector is unrecoverable, and even an AEVT
    backfill would feed hashes, not vectors. So the vector now persists in the
    PRIMARY AEVT (durable truth) and the HNSW is a pure derived cache.
  - **RESTORE-ON-OPEN — RESOLVED + PROVEN.** Proximum `create-index`
    unconditionally `create-store`s its konserve store (throws on a pre-existing
    id). datahike's `restore-secondary-indices` runs the VERSIONED path
    (proximum implements `IVersionedSecondaryIndex`): skeleton `create-store`,
    then `-sec-restore` → `load-commit` connects looking for the flushed commit.
    For a `:memory` store the commit is wiped on `d/release`, so `load-commit`
    throws "Commit not found in storage", restore drops the index (leaking the
    skeleton store). Fix: `install!` is the recovery point — on a reopened conn
    it sees the index is not live, clears the leaked store, re-tx's the index
    def; because the vectors are in AEVT, datahike's `instantiate-secondary`
    marks it `:building` and the writer backfills the HNSW from AEVT
    (`:building` → `:ready`). Backend stays `:memory` (deterministic `:id`); the
    store is throwaway — deleting it loses nothing (vectors are in datoms).
  - **PROVEN LIVE — 5/5 gate** (wire-server socket REPL 7891, file-backed
    throwaway store, `tmp/embed-gate.clj`): (1) `install!` on a fresh
    file-backed conn → `{:installed? true}`, index present; (2) two distinct
    normalized 1536-float vectors transacted as `:seon/embedding` (eids 7, 8);
    (3) KNN via `sec/-slice-ordered` → near-A `[[7 0.0000] [8 1.0000]]`, near-B
    `[[8 0.0000] [7 1.0000]]` (cosine 0 at the match, 1.0 orthogonal); (4)
    `install!` AGAIN → no throw, index still live, KNN still correct; (5) REOPEN
    via `d/connect` on the same store → vectors persisted in AEVT (count 2),
    restore drops the index (expected), `install!` rebuilds from AEVT → INDEX
    PRESENT + KNN correct (`[[7 0.0000] [8 1.0000]]` / `[[8 0.0000] [7
    1.0000]]`).
  - **FLAG for P2-B — `ensure-db!` reopen vs the on-ensure hooks.** Reopen via
    `seon.server.registry/ensure-db!` (the wire-server's real cluster-open path)
    is NOT yet self-healing: the `::reactive`/`::raw-broadcast` on-ensure hook
    (`boot.clj`) transacts the `:seon.subscription/id` schema DURING `ensure-db!`,
    triggering `finalize-secondary-indices` → `create-index` on the store LEFT
    BEHIND by the failed restore (before `install!` can run), re-throwing
    "already exists" and wedging that tx. Proven: if the leaked store is cleared
    before that first post-restore tx, the index self-heals from AEVT
    (`tmp/hook-sim.clj`). Clean P2-B fixes: (a) the proximum secondary shim
    should CONNECT-if-exists rather than always CREATE (root cause; fixes every
    variant), or (b) clear the store + call `embed/install!` from a
    `register-on-ensure-db-hook!` that runs BEFORE the reactive hook's schema tx.
- **Live KNN proof (synthetic, no Gemini):** on the live wire-server (socket
  REPL 7891), `install!` → `{:seon.embed/installed? true}` (genuinely idempotent
  + restore-safe — see the 5/5 gate above); transacted two one-hot 1536-d
  vectors as `:seon/embedding`; KNN via `(sec/-slice-ordered (get-in (d/db conn)
  [:secondary-indices :seon.embed/fn-index]) {:vector qv :k 2} …)` returned the
  correct nearest entity with cosine distance 0 and the orthogonal one at 1.0.
  Note: the wire-server transacts ALL writes as raw `d/transact` through its
  single conn (it does NOT run `seon.db/transact!`), so `install!` and the proof
  transact directly through that conn — the faithful equivalent of the "via
  seon.db/transact!" wording in this PRD.

**Durability + restore-on-open FLAG — RESOLVED (P2-A, proven 5/5 above):**
dropped `:db.secondary/only` (vectors in AEVT) and made `install!` the
restore-on-open recovery point (rebuild HNSW from AEVT). Remaining sub-item is
the `ensure-db!`-hook self-heal (P2-B FLAG above).

**Still needed for 2b (Gemini write pipeline):** the embeddings provider +
model decision (Q1) and the embed-on-persist hook (hash source → cache-miss →
embed → store `:seon/embedding`) + boot backfill (call `embed/install!` on each
cluster conn open; resolve the `ensure-db!`-hook ordering — P2-B).
`google-genai` is already on the `:writer` classpath for the embed call.
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
