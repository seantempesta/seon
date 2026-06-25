---
type: research
status: active
tags: [research, agent, database]
---

# Embeddings / Semantic Search — State & Activation

Read-only investigation, 2026-06-25, branch `feature/agent-fsm` (live pod) + `feature/embeddings`. No edits, no branch ops.

## TL;DR

- **"Mostly done, just flagged off" — confirmed YES.** Every component is implemented and **already merged into `feature/agent-fsm`**: the pod search client, the JVM foundation/write/query sides, the wire path, the Proximum/HNSW index, AND the predictive per-turn context-assembly path. All gated behind **one env var, `SEON_EMBED`**, read by both the pod and the wire-server.
- **No branch merge to do.** `feature/embeddings` is a strict **ancestor** of `feature/agent-fsm` (0 commits ahead; agent-fsm is 98 ahead and carries a *newer* wire codec). Do NOT merge embeddings in — it could revert the newer Transit-JSON envelope.
- **Proven live now:** `seon.embed/search` from the pod completes a full UDS round-trip to the running JVM wire-server and returns `{:seon.embed/hits []}` — a clean OK envelope, empty only because `SEON_EMBED` is unset so no index was ever declared. No error / no stack trace ⇒ flag-gated, not unimplemented.
- **The predictive context-assembly path the owner wants is already built:** `seon.ctx/retrieval-query` (derive query from latest inbound) → `seon.embed/search-pull` (prefetch in `agent/turn.cljs`) → `seon.embed.stash` (fiber-local ALS) → `seon.ctx.relevant/relevant-source-section` (renders pointer blocks: identity headline + capped body + "pull the full row with `(seon.db/pull …)`"). Turning on `SEON_EMBED` activates it.

## State, component by component

| Component | File | Status |
|---|---|---|
| Pod search client | `src/seon/embed.cljs` (`search` l.137, `search-pull` l.173) | DONE — thin wire client, pod never embeds |
| Pod wire transport | `src/seon/store/internal/wire_node.cljs` (`knn-search` l.186) | DONE / round-trip works live |
| JVM foundation (attr+index) | `src/seon/embed.clj` (`:seon/embedding` `:db.secondary/only` l.180; `:proximum` HNSW cosine dim 1536 cap 10000 l.290; `install!` l.385) | DONE, flag-gated off |
| JVM write side (P2-B) | `embed.clj` (`augment-tx-with-embeddings` l.816; Gemini `gemini-embedding-2` l.658; SHA-256 source-hash cache; `drain-backfill!` l.938) | DONE, flag-gated off; default corpus = `:seon.fn/source` only (`my.kb` an inactive template) |
| JVM query side (P2-C) | `embed.clj` (`query-vec` l.1018; `knn` l.974; `knn-search` l.1048; `wire/handle-op "knn-search"` l.1076) | DONE, flag-gated off |
| Wire path pod↔JVM | both + `wire.clj` | DONE / proven live (`{:seon.embed/hits []}`) |
| Proximum/HNSW on datahike fork | `deps.edn` l.100-102 fork `seantempesta/datahike@7ef2b5de` (newer than the memory note) | READY, not instantiated (gated). Wire-server JVM running with `--add-modules jdk.incubator.vector` (confirmed live) |
| Predictive context assembly | `agent/turn.cljs` (`prefetch-and-render-prompt!` l.206, gated `embed-retrieval-on?` l.198); `ctx.cljs` (`retrieval-query` l.1527); `ctx/relevant.cljs` (`relevant-source-section` l.98, top-k=5, 1500-char cap) | DONE, flag-gated off |
| `feature/embeddings` branch delta | — | NONE — fully-merged ancestor |

### Live evidence
```clojure
;; pod env: {:SEON_EMBED nil, :GEMINI_API_KEY-present? true, :conn-bound? true}
(seon.embed/search {:seon.embed/query "transact a datom into the database" :seon.embed/k 3})
;; => {:seon.embed/hits []}   ; clean OK envelope, empty hits, NO error
```
Wire-server JVM (`ps`): running with SIMD module flags, `GEMINI_API_KEY` set, `SEON_EMBED` absent. Empty-but-successful is the documented OFF behavior (`knn` returns nil when the index isn't in `:secondary-indices`; `knn-search` coerces to `[]`).

## The two gates
- **`SEON_EMBED`** (master switch): wire-server `embed-feature-enabled?` = `(some? (System/getenv "SEON_EMBED"))` (embed.clj l.151); pod `embed-retrieval-on?` = `(some? (.. js/process -env -SEON_EMBED))` (turn.cljs l.204). Unset on both ⇒ OFF path is byte-identical to no-embeddings.
- **`GEMINI_API_KEY`** (can-embed): embed.clj l.608 — already present on the running wire-server.

What remains is **validation/tuning, not implementation**: relevance untuned (query = raw latest inbound, no distance cutoff), whole-core corpus embed not productionized (64/pass backfill cap), KB kind an inactive template, gym A/B blocked (gym uses isolated `:memory` conns — irrelevant to the structural gym baseline).

## Minimal activation (ordered, NO code changes)
**A. Wire-server (JVM) first — owns the key + index:**
1. `SEON_EMBED=1` in the wire-server launch env (`GEMINI_API_KEY` already set; Java 22+ `jdk.incubator.vector` already in place).
2. Restart the wire-server → `ensure-db` `::embed` hook runs `install!` (declares attr + `:proximum` HNSW index, sibling store `…/embedding-index`) then `drain-backfill!` (embeds `:seon.fn/source` entities, one Gemini batch/pass).
3. Verify: `java -jar … --preflight` → exit 0 (throwaway `:memory` install+write+KNN self-test; never touches the durable store; codes 10-14 pinpoint failures).

**B. Pod second — agent-facing prefetch:**
4. `SEON_EMBED=1` in the pod env; restart → `prefetch-and-render-prompt!` derives a query from the latest inbound, KNN-searches over the wire, stashes hits, `relevant-source-section` renders the breadcrumb block.
5. Verify (read-only, live): `(seon.embed/search {:seon.embed/query "<fn-corpus text>" :seon.embed/k 5})` → non-empty hits; then `search-pull`.

## Cross-track risk
- **No merge needed** (`feature/embeddings` ⊆ `feature/agent-fsm`); merging could revert the newer wire codec — stay on agent-fsm.
- **Flag flip, not code change.** OFF path byte-identical; ON only adds the volatile `:relevant-source` section (priority 48, self-bound, NOT charged to the agent budget, kept OUT of the cacheable stable prefix → won't disturb prompt caching).
- **Fork version** already correct (`7ef2b5de`, SIMD loaded) — no bump.
- **Per-turn cost:** one Gemini embed of the query text + one in-process HNSW KNN (cap 10k, cosine) per turn — a single UDS RPC; cheap enough to run every turn. Write-side bounded by the SHA-256 source-hash cache (unchanged fns never re-embed).

## Recommendation (smallest next step, decoupled from the gym)
Flip `SEON_EMBED=1` on the **wire-server**, restart, gate on `--preflight` exit 0 — instantiates the Proximum index + backfills the fn corpus (the expensive stateful half), zero code, zero gym impact. Confirm from the pod with a read-only `seon.embed/search` returning non-empty hits. Then flip it on the **pod** to realize the predictive breadcrumbs (no new code).

**One small refinement for the owner's "breadcrumbs not bulk":** `seon.ctx.relevant` currently renders identity headline **plus** a 1500-char body slice (`source-char-cap`, relevant.cljs l.36). Dropping `source-char-cap` (header-only + the existing "pull the full row" pointer) makes it the lightweight "here's what exists, here's the fn to view it" trail. Hook point: `render-hit`/`block`, relevant.cljs l.59-96. Optional, not required to turn it on.

## Activation DECISION (owner's call)
Flipping `SEON_EMBED` on the wire-server **restarts the live durable cluster writer** and spends Gemini calls backfilling the corpus — a real action on the live default cluster. Safe to validate first via `--preflight` (throwaway `:memory`). Recommend: preflight green → flip wire-server → flip pod, OR rehearse on the `acme` harness first. Owner to authorize the live-cluster restart.

## Critical files
- `src/seon/embed.clj` · `src/seon/embed.cljs` · `src/seon/agent/turn.cljs` · `src/seon/ctx/relevant.cljs` · `src/seon/server/boot.clj` (+ `src/seon/embed/preflight.clj` for `--preflight`)
