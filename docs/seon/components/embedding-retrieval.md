---
type: component
status: draft
tags: [component, agent, database]
---

# Embedding retrieval

Semantic vector retrieval over indexed string attributes. The most relevant
indexed source is injected into the agent's per-turn context as the
`<relevant-source>` section. The feature is OFF by default behind one env
switch (`SEON_EMBED`); a consumer who does not opt in pays zero cost and gets
byte-identical behavior.

## Status — EXPERIMENTAL, unfinished, not thoroughly tested

This feature is **unfinished** and **off by default**. The plumbing works
end-to-end and the safety contract is solid, but the central question — does
injecting retrieved source actually make agents *better*? — is **unmeasured**.
Treat it as an experiment to opt into, not a proven capability.

**Verified (bounded):**

- The mechanism end-to-end — register → embed-on-write → durable Proximum index
  → `knn-search` → pod `search-pull` → `<relevant-source>` injection — on
  synthetic/small corpora with real Gemini, plus a few live spot-checks (a
  matching query returns the right function; type-scoping works; the index
  restores from konserve on reopen).
- The OFF contract: `SEON_EMBED` unset → zero machinery, zero Gemini calls,
  byte-identical prompts. Full CLJS suite green (565 tests).

**NOT done / NOT validated:**

- **The core hypothesis is unmeasured.** Whether full-source-top-k retrieval
  beats the existing compact `<namespaces>` render on real agent tasks was never
  A/B-tested. The gym scoring harness can't reach the wire-server's index (it
  boots isolated `:memory` conns), so that A/B is blocked pending a way to embed
  a scenario corpus into a test index.
- **Relevance quality is unvalidated** on real tasks. The query is the raw latest
  inbound message (a first cut, untuned); off-corpus queries return mediocre
  neighbours (a distance cutoff is not yet applied).
- **Knowledge base is disabled** — only functions are indexed; the kb is a
  documented example, not a built workflow.
- **Corpus coverage is partial** — the backfill is bounded (64/boot); whole-core
  embedding (cost + Gemini batch-size handling) is not productionized.
- **Latency/cost under load is unmeasured** — each retrieval-on turn adds a wire
  round-trip + a Gemini query-embed (fail-soft, but not load-tested).
- Not exercised in a real multi-agent production run.

## What it is

As the code corpus grows, the `<namespaces>` context section (compact heads of
every namespace) is noisy and lossy — the agent rarely gets the full source of
the functions actually relevant to its task. Embedding retrieval replaces "show
a little of everything" with "show the full source of the top-k most
semantically relevant entities".

For each turn the system embeds a query derived from the agent's latest inbound
request, runs k-nearest-neighbour search over per-entity source embeddings, and
renders the nearest hits (full body, char-capped) in a `<relevant-source>`
section. Retrieval is attribute-anchored: the attribute an entity carries IS its
type (idiomatic Datomic). There is NO `:seon/kind` enum.

## Architecture

Two processes, one switch.

- Wire-server (JVM, sole datahike writer) — `src/seon/embed.clj`:
  - A Proximum HNSW secondary index (`:seon.embed/index`) over the single
    cross-cutting attribute `:seon/embedding` (a 1536-float vector,
    L2-normalized). The index is durable and secondary-only: the full vector
    lives only in Proximum's konserve file store (a sibling of the cluster's
    primary store); the primary AEVT holds a content hash. The index restores
    from konserve on conn reopen (it is not rebuilt from AEVT).
  - `register-embeddable!` makes any entity carrying a chosen TRIGGER attribute
    embeddable, with an optional compose-fn `(fn [entity-map] -> str)` that
    builds the document to embed.
  - Embed-on-write: `augment-tx-with-embeddings` is installed into the
    wire-server's transact path. It scans each tx for entities carrying a
    registered trigger attribute whose composed-document SHA-256 changed, embeds
    them with Gemini (`gemini-embedding-2`, dim 1536, L2-normalized) BEFORE the
    `d/transact`, and appends `:seon/embedding` + `:seon.embed/source-hash`
    assertions. The SHA cache (`:seon.embed/source-hash`, a plain string in the
    primary store) means an unchanged document never pays a Gemini call.
  - The `knn-search` wire function embeds an NL query (with a retrieval-instruction
    prefix — v2 has no `task_type`) and runs KNN, optionally scoped to an eid
    set, returning `[{eid distance} …]`.
  - A bounded backfill (`backfill-cap` = 64 per boot) embeds already-stored
    entities that lack a current embedding, on each cluster-conn open.
- Pod (CLJS, read-only agents) — `src/seon/embed.cljs`:
  - `search` / `search-pull` are the pod's thin client over the `knn-search`
    wire function. The pod never embeds — it sends the query text over the Unix
    socket; the wire-server (which owns the key + index) embeds and runs KNN.
    `search-pull` enriches each hit by pulling the full entity from the pod's
    LOCAL db value (`[*]` wildcard pull by default, kind-agnostic).
  - `seon.agent/prefetch-and-render-prompt!` awaits the KNN prefetch and stashes
    the hits; the synchronous `seon.ctx.relevant/relevant-source-section` reads
    the stash. The section is volatile (kept out of the cacheable stable prefix)
    and self-bounded (top-5, 1500 chars per hit). When no prefetch ran it
    renders blank and the composer drops it.

The write-path and query-side are SEAMS pointing one way: `seon.embed` requires
`seon.server.wire` / `seon.server.registry` (never the reverse), so those
namespaces stay loadable on a plain JVM without the Proximum/Gemini classpath.

## How to enable

The feature is OFF by default. To turn it on, set BOTH env vars before starting
the wire-server AND the pod:

```sh
export SEON_EMBED=1        # the master switch (any value, incl. empty string)
export GEMINI_API_KEY=...  # the embeddings provider key
```

`SEON_EMBED` is the single switch read by both processes
(`seon.embed/embed-feature-enabled?` on the wire-server,
`seon.agent/embed-retrieval-on?` on the pod). With it unset:

- the wire-server's `::embed` on-ensure-db hook does nothing — NO Proximum index
  is ever declared on the store, no `:seon/embedding` attr, no backfill;
- `augment-tx-with-embeddings` returns the tx unchanged (pass-through);
- `backfill!` is a no-op;
- the pod's prefetch never fires, so the assembled prompt is byte-identical to
  the pre-retrieval path.

Nothing is sent to Gemini when the feature is off. `GEMINI_API_KEY` is a second,
orthogonal gate: the feature can be enabled without a key (the index is declared,
writes still commit) and embedding simply no-ops until a key is present — it
never errors a write.

On first boot with the feature enabled, the bounded backfill embeds up to 64
already-stored functions; the rest embed incrementally on their next write or a
later backfill pass. Retrieval quality grows as the corpus embeds.

## What is indexed

By default, exactly ONE kind: functions, via the `:seon.fn/source` trigger
attribute. The composed document is `<sym>\n<doc>\n<source>` (the FQ
`<ns>/<name>` symbol is the semantic anchor).

## How to add a kind

The mechanism is general — any entity carrying a registered trigger attribute is
embedded and searchable. `seon.embed` ships an INACTIVE `my.kb` knowledge-base
EXAMPLE (in a `comment` form) as the template a consumer copies. To make your
own attribute searchable, do two things in your consumer namespace:

1. Register your attribute schema(s):

   ```clojure
   (schema/register! :my.kb/id    [:string {:seon.db/identity true}])
   (schema/register! :my.kb/title :string)
   (schema/register! :my.kb/body  :string)
   ```

2. Point the embedder at the TRIGGER attribute, optionally with a compose-fn
   `(fn [entity-map] -> str)` (defaults to the string value of the trigger
   attribute when omitted):

   ```clojure
   (defn compose-kb-body
     [{:my.kb/keys [title body]}]
     (str (when (seq title) (str title "\n")) body))

   (seon.embed/register-embeddable!
     {:seon.embed/trigger-attr :my.kb/body
      :seon.embed/compose-fn    compose-kb-body})
   ```

After that, any entity carrying `:my.kb/body` is embedded on write and
searchable over the SAME single `:seon/embedding` attribute + single Proximum
index. Scope a search to only that kind by passing a datalog `:where`:

```clojure
(seon.embed/search-pull
  {:seon.embed/query "how do I open a cluster store"
   :seon.embed/where '[[?e :my.kb/body]]})   ; type-scope: only kb rows
```

`search` and `search-pull` accept `:seon.embed/where` (resolved to an eid set on
the pod's local db) or `:seon.embed/eids` (already resolved). A new scope is just
a different `:where` — no schema change.

## Confidentiality

Off by default means no data leaves the process unless explicitly enabled. With
`SEON_EMBED` unset, nothing is sent to Gemini — there is no index, no
embed-on-write, no backfill, and no query embedding. Enabling the feature
(`SEON_EMBED=1` + `GEMINI_API_KEY`) opts in to sending the composed document
text of indexed entities (function source by default) and per-turn query text to
the embeddings provider.

## Anchors

- Wire-server: `src/seon/embed.clj` (`embed-feature-enabled?`,
  `register-embeddable!`, `augment-tx-with-embeddings`, `backfill!`, `install!`,
  `knn-search`, the `::embed` on-ensure-db hook)
- Pod: `src/seon/embed.cljs` (`search`, `search-pull`),
  `src/seon/ctx/relevant.cljs` (the `<relevant-source>` section),
  `src/seon/agent.cljs` (`embed-retrieval-on?`, `prefetch-and-render-prompt!`)
- PRD: `docs/prds/agent-runtime/embeddings-fn-retrieval-prd-2026-06-18.md`
