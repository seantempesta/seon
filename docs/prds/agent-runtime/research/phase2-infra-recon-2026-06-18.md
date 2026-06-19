---
type: research
status: active
tags: [research, agent, database, schema, cljs]
---

# Phase-2 embeddings — infrastructure recon map (2026-06-18)

> Six-subsystem read-only recon before implementation. File:line anchors are
> the load-bearing payload — this is the brief an implementing agent reads so it
> does not re-derive the terrain. Source of truth for the design remains the PRD
> handoff block ([[embeddings-fn-retrieval-prd-2026-06-18]]).

## TL;DR

The whole substrate is **general over any indexable string**, not fn-specific:
ONE `:seon/embedding` attr + ONE Proximum HNSW secondary index, a uniform
`:seon/kind` keyword driving both an `embed-text` multimethod and type-scoped
filtering (datalog `:where` → eid set → Proximum entity-filter). The committed
foundation (`seon.embed`, commit `7d25126`) is **inert and fn-named** — zero
callers, index mis-named `:seon.embed/fn-index`. The first task (P2-A.5) is a
**datahike-fork** root fix so reopen RESTORES from konserve instead of
rebuilding from AEVT. Everything below it (embed-on-write, knn-search verb, ctx
integration) is net-new.

## P2-A.5 — restore-on-reopen root cause (CONFIRMED, two independent reads)

Two compounding defects:

1. **The shim factory allocates a store for the reopen skeleton.** On connect,
   `restore-secondary-indices` (`reference-code/datahike/src/datahike/writing.cljc:179`)
   builds a skeleton via `(sec/create-index idx-type idx-config nil)` purely to
   dispatch `-sec-restore` on. The `:proximum` factory
   (`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:171-181`)
   is NOT passive — it calls `prox/create-index`, whose `:hnsw` method
   (`reference-code/proximum/src/proximum/hnsw.clj:1158`) runs `create-store-sync`
   (`:1179`) AND eagerly writes `:index/config` (`:1194`) + `:branches` (`:1206`)
   into a fresh store, returning an empty index. The correct restore path right
   below — `-sec-restore` → `pwr/load-commit` (`proximum.clj:111-115`,
   `writing.clj:184` via `connect-store-sync`) — never gets a clean shot.
2. **The seon foundation uses a `:memory` store** (`src/seon/embed.clj:136-142`),
   so the flushed commit never survives a JVM restart. On a fresh JVM,
   `load-commit` connects to a new empty memory store, `k/get` the commit-uuid
   returns nil → throws "Commit not found" (`writing.clj:215-218`).
   `restore-secondary-indices` catches + DROPS the index (`writing.cljc:192-194`),
   leaving the schema entry `:building` with no instance → the connector
   AEVT-backfill fires (`connector.cljc:224-237`). **That is the rebuild-from-AEVT.**

P2-A's workaround: drop `:db.secondary/only` (vectors live in AEVT) +
`install!` force-rebuilds from AEVT every open (`embed.clj:193-229`). Works, but
defeats Proximum's persistence/versioning — the handoff calls it the wrong
direction.

### The fix (three coupled parts)

- **Fork:** make the skeleton creation **connect/open-if-exists** instead of
  create-or-throw — when a key-map/restore will follow, the factory must NOT
  `create-store-sync` or write `:index/config`/`:branches`. Lowest-level seam:
  `hnsw.clj:1158` create-index gets a connect path
  (`connect-store-sync`, `storage.clj:322`) when the store already exists;
  alternatively make `proximum.clj:171-181`'s factory lazy. Primitives exist:
  `connect-store-sync`, `load-commit`, `open-store*` (`vectors.clj:286`).
- **Durable backend:** switch `embed.clj` `index-store-config` from `:memory`
  to a **file/lmdb konserve store** under the cluster store dir, so the
  `-sec-flush` commit survives restart and `load-commit` can connect.
- **Re-enable `:db.secondary/only`** on the `:seon/embedding` registration
  (`embed.clj:97`); CLJ bridge already supports it
  (`db/datahike/schema.clj:162-180`), CLJS sibling
  (`db/internal.cljs:325-333`) needs float-inner parity. Delete the
  AEVT-rebuild workaround. Verify a live reopen RESTORES (instrument
  `load-commit` runs; assert NO AEVT backfill).

Then: commit→push fork (`sync-upstream`) + bump **5 sites** in lockstep —
`deps.edn` `:git/sha` at `:171/:194/:220/:309` (all `@6cf05300`) + the
`reference-code/datahike` submodule.

**New hazard surfaced by recon:** the shim's `-sec-flush` blocks with
`async/<!!` inside `commit!`'s `go-try-` (`proximum.clj:96-104`) — a documented
core.async-pool deadlock risk. The inert foundation never exercised it;
durable-flush-on-every-commit will. Separate from restore; decide whether to
address now or under-load-later.

## Subsystem map

| Subsystem | Key files | The one hook for Phase-2 |
|-----------|-----------|--------------------------|
| Fork shim + framework | `proximum.clj`, `writing.cljc`, `transaction.cljc`, `connector.cljc`, `secondary.cljc` | `proximum.clj:171-181` factory → connect-if-exists |
| Proven spikes | `tmp/embed-spike/src/embed_secondary.clj` (3/3 through datahike), `tmp/datahike-sync/test/harness/proximum_secondary.clj` (entity-filter) | copy the embed + KNN + entity-filter shapes verbatim |
| Foundation (inert) | `src/seon/embed.clj`, `db/datahike/schema.clj`, `db/internal.cljs`, `server/boot.clj`, `deps.edn :writer` | rename `index-ident` `:67`; `install!` `:193` is the only entry point |
| ctx render | `src/seon/ctx.cljs` (`assemble-context:1693`, `core-default-ctx:1480`), `ctx/namespaces.cljs` (`namespaces-section:259`, `elide-defn-body:142`) | `compact-ns-source` per-fn elide `namespaces.cljs:243` → retrieved? full : elide |
| Wire-server | `server/wire.clj` (`handle-op:216` verb multimethod, raw `d/transact:367/411`), `server/registry.clj` (`ensure-db!`, `register-on-ensure-db-hook!`), `eval.cljs` (`build-tee-entities:1349`), `store/internal/wire_node.cljs` (`rpc:61`) | new `(defmethod handle-op "knn-search")`; embed-on-persist before `d/transact:367`; `::embed` on-ensure hook FIRST |
| Research + SDK | `research/embedding-config-recommendation`, `research/gemini-embeddings`, `reference-code/java-genai/.../{Models,EmbedContentConfig,ContentEmbedding}.java` | `(.embedContent (.models client) "gemini-embedding-2" texts cfg)`, 1536, no taskType, L2-normalize |

## Locked embedding call (verified vs real java-genai source)

```clojure
(:import [com.google.genai Client] [com.google.genai.types EmbedContentConfig])
(defonce client (-> (Client/builder) (.apiKey (System/getenv "GEMINI_API_KEY")) (.build)))
;; v2: NO .taskType — retrieval instruction goes in the QUERY text
(def cfg (-> (EmbedContentConfig/builder) (.outputDimensionality (int 1536)) (.build)))
;; batch (one HTTP req, input-order-aligned) | single-string wraps the List overload
(.embedContent (.models client) "gemini-embedding-2" ^java.util.List texts cfg)
;; vector: resp.embeddings().get().get(i).values() -> Optional<List<Float>>; no float[] accessor
;; L2-normalize the 1536 Matryoshka slice client-side (native 3072 is pre-normalized; slices are NOT)
```

## Generalization status ("embed EVERYTHING")

- **What exists:** only `:seon/embedding` (`embed.clj:97`). NO `:seon/kind` attr,
  NO `embed-text` multimethod anywhere in `src/`.
- **What "kb" actually is today:** PURELY teaching text in `system-text`
  (`ctx.cljs:958-972`) telling agents to design `my.kb.<domain>` schemas at
  runtime. There is **no `:seon.kb` entity, no kb section, no kb query**. So
  "embed everything" has fns as the only concrete kind today; kb/ns substrate is
  net-new and needs a decision (define a real entity kind, or embed whatever
  carries `:seon/embedding` regardless of kind).
- **Mechanism that satisfies "any indexable string":** `:seon/kind` keyword on
  each embeddable entity → `embed-text` dispatch builds the composed string →
  store `:seon/embedding`. Adding a kind = one `embed-text` clause + writing the
  datoms. NO schema/index change. Type-scope at query via `:where [[?e :seon/kind
  K]]` → eid set → Proximum entity-filter.

## P2-D hard constraints (ctx integration, for when we get there)

- **Async boundary:** every ctx section fn is synchronous; KNN needs a JVM wire
  round-trip. Either `assemble-context` goes async (blast radius:
  `seon.agent/render-prompt` + inspector) OR search results are pre-fetched
  before the sync render. Single hardest constraint.
- **Stable-prefix cache collapse:** `:namespaces` is the tail of the byte-stable
  cache prefix (`ctx.cljs:1752-1757`) for provider prompt-caching. A
  query-dependent body breaks it — retrieved source must move to the volatile
  half, compact "rest" stays stable above.
- **No core-section budget:** `apply-agent-budget` charges only agent sections
  (`ctx.cljs:1624-1665`); `:namespaces` is unbounded-by-design (relies on
  elision). Full top-k needs its own budget.

## Resolved direction (user, 2026-06-18 PM)

1. **DROP `:seon/kind` entirely — NOT load-bearing.** A type/kind enum is
   redundant with attribute namespaces (idiomatic Datomic: the attributes an
   entity has ARE its type). Replace it with: (a) an **attribute-anchored
   "embeddable" registration** — a consumer declares "attribute X is searchable,
   compose its text via fn F" (trigger = the attribute, which already exists);
   (b) **search scoping by arbitrary datalog `:where`** (e.g. attribute presence
   `[?e :my.kb/body]`) → eid set → Proximum entity-filter. fns and the kb example
   are two registrations; adding a consumer = one more. No kind tag anywhere.
2. **Build a concrete `my.kb` knowledge-base example NOW** — invent a reasonable
   entity, insert real data, test the full insert→embed→index→search path live.
3. **Priorities: rock-solid → fast → dead-easy for agents to search.**
4. **The `-sec-flush` async-deadlock fix is IN-SCOPE for P2-A.5.**
5. **Durable konserve backend:** file backend under the cluster store dir, a
   sibling of the primary LMDB (no lock contention). (orchestrator call)
6. **Query-text composition (P2-D):** turn prompt + open-todos + current-ns —
   tuned in the gym later (derive prompt from `ctx/messages` latest live inbound).

## P2-A.5 fix mechanics (test/ship wrinkle)

- The SHIM (`reference-code/datahike/src-secondary/.../proximum.clj`) is on the
  `:writer` classpath as a LOCAL `:extra-paths` dir → edits take effect on JVM
  restart, no sha bump. The factory's connect-if-exists fix can live ENTIRELY
  here (branch on `(nil? db)` = restore skeleton → no `create-store`; let
  `-sec-restore`→`load-commit` populate). **proximum itself is a Maven dep
  (`0.1.25`) — cannot patch it; use its public API.**
- The FRAMEWORK (`reference-code/datahike/src/{writing,secondary,transaction,
  connector}.cljc`) loads from the `:git/sha` JAR, NOT the local tree — edits
  there do NOT take effect until committed + 4-site sha bump. **To test
  uncommitted framework edits (needed if async-flush touches the protocol), use
  a `:local/root reference-code/datahike` alias** (mirror
  `tmp/embed-spike/deps.edn`'s `:secondary`) so the whole working tree is live.
- Ship: commit datahike submodule → push fork (`sync-upstream`) → bump 4
  `:git/sha` sites + the submodule pointer → re-verify on the real `:writer`.
</content>
</invoke>
