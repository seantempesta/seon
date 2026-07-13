---
type: component
status: draft
tags: [component, agent, database]
---

# Embedding retrieval

Optional semantic search over selected database entities. This feature is off
by default and is not a runtime-reliability completion gate.

## Current implementation

The JVM database server owns all expensive embedding work:

- `src/seon/embed.clj` defines the optional Proximum HNSW index, the immutable
  trigger-attribute-to-document composition map, transaction augmentation,
  bounded backfill, and KNN search;
- `src/seon/db/server.clj` constructs those functions once in the immutable
  writer runtime; and
- `src/seon/db/writer.clj` exposes KNN through the typed database protocol.

The Node pod does not embed documents. `src/seon/embed.cljs` sends a text query
and optional entity-id scope to the database server, then enriches returned ids
from its local immutable database value. The only current CLJS consumer is the
optional semantic path in `src/seon/diffusion/retrieval.cljs`; general per-turn
context injection described in older PRDs is not active code.

The shipped pipeline indexes `:seon.fn/source`. `:seon.embed/source-hash`
prevents an unchanged composed document from paying for another embedding.
The full vector is secondary-index data; ordinary database facts retain the
content hash and entity relationships.

## Gates

`SEON_EMBED` must be present in both processes. When it is absent, database
initialization installs no embedding index, transaction augmentation is a
pass-through, backfill is a no-op, and the pod does not request semantic search.

There is an unresolved authentication-policy mismatch that must be fixed before
this page becomes operator guidance: the current implementation and preflight
read `GEMINI_API_KEY`, while the shared repository policy specifies Vertex ADC.
Do not enable the feature in a new environment until code and policy use one
mechanism.

## What remains to prove

- choose and implement the one supported authentication path;
- measure relevance against real Inspect AI tasks with a seeded index;
- define a useful distance cutoff and corpus policy;
- measure startup, write, query, and memory cost under load; and
- decide whether semantic results belong in general dynamic context at all.

The mechanism is available for experiments; its product value is not yet
established.

## Source anchors

- `src/seon/embed.clj`
- `src/seon/embed.cljs`
- `src/seon/embed/preflight.clj`
- `src/seon/db/server.clj`
- `src/seon/db/writer.clj`
- `src/seon/diffusion/retrieval.cljs`
- [[../../prds/embeddings/vertex-usage-reference-2026-06-25]]
