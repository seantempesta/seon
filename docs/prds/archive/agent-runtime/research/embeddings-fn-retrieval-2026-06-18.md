---
type: research
status: active
tags: [research, database, agent, schema]
---

# Embedding-based function retrieval — does Datahike support it?

## TL;DR

- **"libdatahike" is NOT about embeddings.** It is C/C++ native bindings
  (GraalVM Native Image) for *embedding the Datahike database engine* in
  non-JVM applications. The word "embed" there = "embed the DB in your
  app", not vector embeddings. Irrelevant to semantic function retrieval.
- **Datahike DOES support vector search — via a pluggable secondary
  index, not core.** The mechanism is `datahike.index.secondary`
  (framework, vendored here at `src/datahike/index/secondary.cljc`) plus
  **Proximum** (`:proximum` index type — HNSW / KNN, external dep
  `proximum-0.1.25`, impl vendored at
  `src-secondary/datahike/index/secondary/proximum.clj`). Status:
  **Experimental** ("functional but may receive breaking API changes").
- **Hard blocker for our active runtime: Proximum requires Java 22+.**
  The wire-server / reference checkout runs **Java 21** (Temurin
  21.0.5). Proximum will not load until the writer JVM is on 22+.
- **Second blocker: the pod can't run it locally.** The CLJS pod does
  not embed datahike; reads are local lazy db values in JS. Proximum is
  a Java/JVM (HNSW, konserve-backed) index — it lives on the
  **wire-server JVM**. Any KNN query must route to the writer, not run
  pod-side.

## The secondary-index framework (this is the real answer)

Three pluggable secondary index types ride alongside the primary B-tree:

| Type | Library | Capability | Java |
|------|---------|-----------|------|
| `:scriptum` | Scriptum (Lucene) | Full-text search | 11+ |
| `:proximum` | Proximum (HNSW) | Vector similarity / KNN | **22+** |
| `:stratum` | Stratum | Columnar aggregates (SIMD) | 21+ |

Declared by a **schema transaction** (no special API); Datahike
backfills existing data and maintains the index on every tx:

```clojure
(require '[datahike.index.secondary.proximum])

;; the embedding attr (a tuple of floats)
(d/transact conn [{:db/ident :seon.fn/embedding
                   :db/valueType :db.type/tuple
                   :db/cardinality :db.cardinality/one}])

;; the index
(d/transact conn [{:db/ident :idx/fn-vectors
                   :db.secondary/type :proximum
                   :db.secondary/attrs [:seon.fn/embedding]
                   :db.secondary/config {:dim 384  ; e.g. all-MiniLM
                                         :distance :cosine
                                         :store-config {:backend :file ...}}}])

;; query
(def vt (get-in (d/db conn) [:secondary-indices :idx/fn-vectors]))
(sec/-slice-ordered vt {:vector (float-array q) :k 12} nil nil :asc nil)
;; => [{:entity-id 1 :distance 0.0} {:entity-id 3 :distance 0.14} ...]
```

Key properties that fit Seon well:

- **EntityBitSet composition.** All secondary indices return a
  RoaringBitmap of entity IDs. You can pre-filter (e.g. "only fns in
  these namespaces") then KNN within that set, or AND/OR results across
  full-text + vector. Maps cleanly onto "retrieve relevant `:seon.fn`
  entities, then pull full source".
- **Versioned / branch-aware.** Proximum forks CoW with the DB branch;
  state persists in commits, restored on reconnect (no backfill).
  Consistent with our git-like store semantics.
- **konserve-backed** → available to all readers in a distributed setup
  (unlike Scriptum, which is writer-local filesystem).

## Where this leaves us (architecture reality)

- We do NOT generate embeddings ourselves yet. Proximum indexes vectors
  we supply; we still need an **embedding model** to turn fn source →
  float vector (an external call or a local model). That is the real new
  dependency, separate from datahike.
- Even with embeddings, the KNN runs on the **JVM writer** (Java 22+
  required). The pod would issue a "retrieve top-k fn eids for this query
  vector" call over the wire, then pull full source for those eids
  locally. This is a wire-protocol addition to wire-server, not a
  pod-local feature.

## Recommended path (cheapest → fullest)

1. **Validate the retrieval *value* before adopting Proximum.** The fn
   corpus is small (hundreds, not millions). A brute-force cosine over
   in-memory float vectors (compute on the JVM writer or even pod-side
   from stored vectors) gives identical top-k with zero new infra and no
   Java-22 dependency. Prove "full source of the k relevant fns" beats
   "compact render of everything" on real agent tasks first.
2. **If brute force is too slow / corpus grows** → adopt Proximum:
   bump wire-server to Java 22+, add the `proximum` dep, declare the
   secondary index, add a wire verb for KNN that returns eids.
3. **Either way we need an embedding source.** Decide: external
   embedding API vs. a small local model. This is the open design
   question, not the index.

## Proximum deep-dive (submodule added 2026-06-18)

Vendored at `reference-code/proximum` (`replikativ/proximum`, ~6.6k LOC
Clojure + ~4.5k LOC Java). Corrects two things from the first pass:

- **NOT a native library.** "Pure JVM, no native deps." The Java-22+ ask
  is purely JDK *modules*: `--add-modules=jdk.incubator.vector` (SIMD
  Vector API) + `--enable-native-access=ALL-UNNAMED` (Foreign Memory API,
  finalized in Java 22, used for the mmap vector store). Java 21 runs the
  Vector API but the Foreign Memory API is preview there — 22+ is the
  clean target. Just JVM flags + version, no `.so` to build.
- **It is a standalone vector DB, not only a datahike add-on.** Two ways
  to use it, and they're independent:
  1. **Standalone** `proximum.core` — `create-index`, `insert`,
     `insert-batch`, `search`, `search-filtered`, `sync!`, `branch!`,
     `history`, `merge!`. This is the reusable primitive — usable for any
     Seon vector-search need, not just fn retrieval.
  2. **Datahike secondary index** — the `:proximum` type wired through a
     **175-line shim** (`datahike/src-secondary/.../proximum.clj`, already
     in your datahike fork). Transparent maintenance on every tx + query
     composition, but couples retrieval to the experimental
     secondary-index machinery.

### Why the standalone API fits `:seon.fn` retrieval perfectly

```clojure
;; id can be ANY value — pass the :seon.fn entity-id directly as external id
(insert idx (float-array vec) fn-eid {:seon.fn/name "..."})
(insert-batch idx vectors eids {:metadata metas})

;; returns external ids (our eids) + distance, ready to pull full source
(search idx query-vec 12)
;; => [{:id 1043 :distance 0.0} {:id 1102 :distance 0.14} ...]

;; namespace pre-filter falls out — pass a set of eids to consider
(search-filtered idx query-vec 12 #{eid1 eid2 ...})
```

- **Metadata carried with each vector** → the vector knows its eid; no
  side table. Maps onto our entity model directly.
- **Persistence** = konserve (we already run konserve under datahike) +
  a memory-mapped file dir for the raw vectors (`:mmap-dir`). Both
  local-filesystem on the wire-server.
- **Git-like branching / time-travel** is native — consistent with our
  store semantics if we ever want per-branch indices.
- Distance metrics include `:cosine` (what embeddings want).

### Is it a big lift? — Medium. The hard parts are done for us.

HNSW, SIMD, CoW persistence, branching = all in the library. Our work:

| Piece | Effort | Notes |
|-------|--------|-------|
| wire-server JVM → Java 22 + JVM opts + `org.replikativ/proximum` dep | S | flags: `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` |
| **Embedding generation** (fn source → vector via external API) | M | the genuinely new external dependency; cache by source hash so we don't re-embed unchanged fns |
| Index lifecycle on wire-server (create/load/`sync!`) | S–M | one index keyed by `:seon.fn` eid |
| Embed-on-write: when a `:seon.fn` is tee'd/persisted, embed + `insert` | M | hook into existing persistence path |
| Wire verb: `knn-fn-search` (query text/vector → top-k eids+distances) | S | pod issues it, then pulls full source for the eids locally |
| Pod-side ctx integration: swap "compact-render all" → "full source of top-k" | M | the actual feature payoff |

Recommendation: **use the standalone `proximum.core`, not the datahike
secondary index.** It's the reusable primitive (your "could be used
elsewhere" goal), avoids coupling fn-retrieval to experimental query-planner
machinery, and keeps the index a plain artifact on the writer behind one
wire verb. Adopt the secondary-index integration later only if we want
transparent index maintenance inside datahike transactions.

Still worth a **brute-force cosine spike first** (a few hundred fn vectors,
no HNSW) purely to prove "full source of k relevant fns > compact-render
everything" before standing up the index — the embedding-API path and the
ctx integration are shared between both, so that spike isn't throwaway.

## Open questions for the user

- Embedding model: external API (e.g. an embeddings endpoint) or local?
- Are we willing to move the writer JVM to Java 22+ for Proximum, or
  stay on 21 and brute-force KNN over a few hundred fn vectors?
- Retrieval granularity: per-`:seon.fn`, per-`:seon.ns`, or both?

## Sources (verified in this checkout, 2026-06-18)

- `reference-code/datahike/doc/secondary-indices.md` (full secondary
  index guide — read end to end)
- `reference-code/datahike/doc/libdatahike.md` (C/C++ bindings — NOT
  embeddings)
- `reference-code/datahike/README.md` L189 (libdatahike), L224-235
  (Proximum), L249 secondary-index note
- `reference-code/datahike/src/datahike/index/secondary.cljc` (framework,
  vendored)
- `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj`
  (Proximum integration, vendored; dep `proximum-0.1.25`)
- `java -version` on this machine → OpenJDK 21.0.5 (Proximum needs 22+)
