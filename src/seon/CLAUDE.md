# src/seon — orientation (auto-loaded when editing anything under here)

**Before editing, read the canonical architecture:** `docs/seon/architecture/`
— start with `architecture.md` (thesis + vocabulary + principles), then the
domain doc for your area (`data-model.md`, `agent-runtime.md`, `ui.md`,
`toolkit.md`, `observability.md`). They are TARGET-written (present tense);
the only we-are-here doc is `docs/prds/agent-ctx/roadmap.md` (+
`docs/prds/agent-ctx/context-rebuild.md`, the context-rebuild plan of
record). Conventions: `docs/conventions.md`.

## The ONE-mechanism table — never build a second

The #1 failure mode in this repo is an agent building a parallel version of a
mechanism that already exists. Before you add a registry, a renderer, a
config read, a retry loop, an error shape, or a search path — check this
table. If your task seems to need a second one, the task is wrong or the
existing one needs strengthening IN PLACE.

| Mechanism | The one owner | Never |
|---|---|---|
| DB access | `seon.db` (sole API; pod forwards writes to wire-server) | touch `datahike.api` outside `src/seon/db/` |
| Schema | `seon.schema/register!` (auto-derives datahike schema) | hand-written datahike schema, inline duplicated shapes |
| Context unit | `:seon.agent.ctx/block` + seed-copy + `install!`/`remove!` | a second block set, render-merge, a provider/catalog |
| Rendering | `seon.render` — one guarded walker, ai + html views | a second projection path; renders are NEVER stored |
| Errors | `:seon/error` value (`seon.error`); catch sites record via `seon.error/record!` (fault-tagged datom, `:agent` never escalates) | throwing into the agent loop; a new error shape; console-only swallows |
| State seeding/reset | `seon.state/reconcile!` (provenance-scoped diff) | ad-hoc seed/override/restore code paths |
| Config | ONE manifest `config/system.edn` via `seon.config` (`SEON_CONFIG` picks the file) resolves ONCE at boot into the `:seon.config` DB **singleton** (`resolve-config-singleton`; `:seon.config/system-text` + every dial as datoms); RUNTIME reads the DB via `config/config-view` (accessors keep their names/arities) | env-var reads, per-feature config files, re-reading the manifest at runtime |
| Literal search | `seon.agent.search` (`grep` files, `grep-graph` DB) | a new scan/query helper per caller |
| Semantic search | `seon.embed` — ONE `:seon/embedding` attr + Proximum index (wire-server) | a second index or embedder |
| Token counts | `seon.ai.tokens/estimate` — sizes shown to anyone are TOKENS | printing char counts; a second estimator |
| LLM calls + retry | providers in `seon.ai.*`; `seon.agent.turn/call-llm!` is the sole retry authority | a parallel retry/backoff path |
| Code execution | `seon.eval` (self-host `cljs.js`), the one sandboxed exec service | a second eval path |
| Capability fns | `seon.agent.fs` is the template (gating, envelope, paging) | a tool with its own arg/result conventions |
| Big text at rest | blob store (`my.blob`, three-tier rule) — DB holds projections + refs | large text dumps as datoms; ad-hoc log-file trees |
| Provenance (who/when wrote a datom) | tx-meta auto-stamp via `db/with-agent`/`with-tx-context` — join the datom's tx | `created-by`/`created-at`/`source-turn` attrs on domain entities |
| Model/agent evals | `src-inspect-ai/` (tasks + scorers; ledger `evals/scorecard.jsonl`) | a new drive script, a bespoke per-run harness, a 4th testing surface |

## Lanes — which files are alive

- **`.cljs` = the ACTIVE pod** (Node, HTTP 7890). This is the current focus.
- **`server/*.clj` + `embed.clj`** = the ACTIVE JVM **wire-server** (sole
  datahike writer, UDS + socket REPL 7891, embeddings).
- **Everything else `.clj`** (`core.clj`, `system.clj`, `flow/*`,
  `db/datahike/*`, `web/*.clj`, `ctx.clj`, `render.clj`…) = the **paused JVM
  main-app track**. Don't extend it; don't "fix" pod bugs there.
- `.cljc` (`schema`, `instrument`, `repair`, `error/instrument`) is genuinely
  shared — promote to `.cljc` only when both tracks converge on a shape.

## Vendored source — read it, don't guess

`reference-code/` holds full checked-out source for every load-bearing dep.
Writing Clojure from training memory produces confident, wrong code (mutable/
imperative reflexes, guessed library semantics). Ground first:

- `reference-code/datahike/` — db-as-value, indexes, history/as-of, CAS.
- `reference-code/malli/` — validators, `:catn`/`:=>` fn schemas, registry.
- `reference-code/sci/` — the eval cage semantics.
- `reference-code/clojurescript/` — the self-host compiler (`cljs/js.cljs`)
  and the `await` macro (`:async &env` assert).
- `reference-code/reitit/` — router-as-data. `reference-code/datastar/` — SSE
  morph. `reference-code/transit-cljs/` — the wire codec.
- Per-claim file:line read-map: `docs/seon/architecture/library-grounding.md`.

## Current known weaknesses (fix the root, don't work around)

- The pod is single-threaded: one uncaught throw blanks every agent + the UI
  — hence never-crash/always-surface at every boundary.
- `seon.db/*conn*` is a single dynamic root — CORRECT by construction: one
  pod = one cluster = one conn (isolation is the process boundary).
  Parallelism = more clusters (`bin/seon cluster create`), never a second
  in-pod conn.
- Home-ns data/function aliases (`db/`, `plan/`, `message/`, `schema/`) DO
  resolve in agent-authored `my.*` nses — `seon.eval/augment-ns-source`
  injects the real `(:require …)` into every authored `(ns …)` form at eval
  time (stored verbatim in `:seon.ns/source` + as `:seon.ns/require-edges`;
  survives resume, #73/#56 CLOSED). NOT auto-aliased: the `my.*` toolkit
  (`my.ui/…`, `my.data/…`, `my.tile/…`, `my.kb/…`), the `agent/` alias, and
  the lifecycle refers (`wait`/`complete`/…) — full-qualify those.
- Turn capture is live (`:seon.agent.turn/rendered-as-of` + prompt/reply
  blob refs, `seon.agent.debug/turn`/`turn-diff`) and is the ONE capture
  path — the gated `seon.debug` file tree is deleted; the gym driver reads
  prompts by blob hash (see `observability.md`).
