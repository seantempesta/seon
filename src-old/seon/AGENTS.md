# CONDEMNED FILE FENCE
DYING — Group 5/Step 6 deletes `client.cljs`, `config.cljs`, `demo.cljs`, `log.cljs`, and `platform.cljs`; Group 6/Step 7 deletes `derive.cljs` and `route.cljs`.
Do not extend, do not port, do not add requires to these namespaces from new code.
Surviving owners: `seon.dev.program-indexer`, `seon.host`, `seon.config.resolve`, `seon.error`, `seon.derive.state`, and `seon.web.server`.
Deletion plan: `docs/prds/sci-execution-runtime/research/pod-cut-verdict-2026-07-26.md`.

# src/seon — orientation (auto-loaded when editing anything under here)

**Before editing, read the canonical architecture:** `docs/seon/architecture/`
— start with `architecture.md` (thesis + vocabulary + principles), then the
domain doc for your area (`data-model.md`, `agent-runtime.md`, `ui.md`,
`toolkit.md`, `observability.md`). They are TARGET-written (present tense);
the only we-are-here doc on this branch is
`docs/prds/archive/runtime-reliability/roadmap.md`. Conventions:
`docs/conventions.md`.

## The ONE-mechanism table — never build a second

The #1 failure mode in this repo is an agent building a parallel version of a
mechanism that already exists. Before you add a registry, a renderer, a
config read, a retry loop, an error shape, or a search path — check this
table. If your task seems to need a second one, the task is wrong or the
existing one needs strengthening IN PLACE.

| Mechanism | The one owner | Never |
|---|---|---|
| DB access | `seon.db` (sole API; the JVM authority owns indexed reads and writes) | touch `datahike.api` outside `src/seon/db/` |
| Reactive reads | `seon.reactive` owns registration, one active computation, newest pending database value, writer interest replacement, equality suppression, and final-consumer release | a renderer-local transaction listener, dependency parser, recompute queue, or equality cache |
| Schema | `seon.schema/register!` (auto-derives datahike schema) | hand-written datahike schema, inline duplicated shapes |
| Context unit | `:seon.agent.ctx/block` + seed-copy + `install!`/`remove!` | a second block set, render-merge, a provider/catalog |
| Rendering | `seon.render` — one guarded walker, ai + html views | a second projection path; renders are NEVER stored |
| Errors | `:seon/error` value (`seon.error`); catch sites record via `seon.error/record!` (fault-tagged datom, `:agent` never escalates) | throwing into the agent loop; a new error shape; console-only swallows |
| State seeding/reset | `seon.runtime.state/reconcile!` (provenance-scoped diff) | ad-hoc seed/override/restore code paths |
| Config | ONE explicitly selected manifest via `seon.config` resolves into the `:seon.config` DB singleton and its managed route/skill populations. Fresh `bin/seon up` selects `config/system.edn`; later config-free boots preserve DB facts. `bin/seon config apply <path>` is the explicit repair path. Runtime startup acquires the singleton once and installs that ordinary map in the existing async transaction context; pure accessors and centralized request normalization read it there, while an explicit operation option wins. | another config reader/cache/ALS, env-var runtime reads, per-feature config files, re-reading the manifest after apply, threading configuration through leaf call sites |
| Literal search | `seon.agent.search` (`grep` files, `grep-graph` DB) | a new scan/query helper per caller |
| Semantic search | `seon.embed` — ONE `:seon/embedding` attr + Proximum index (database server) | a second index or embedder |
| Token counts | `seon.ai.tokens/estimate` — sizes shown to anyone are TOKENS | printing char counts; a second estimator |
| LLM calls + retry | providers in `seon.ai.*`; `seon.agent.turn/call-llm!` is the sole retry authority | a parallel retry/backoff path |
| Code execution | the cluster JVM runs `seon.agent.driver` through `seon.sci.eval`; `seon.db.host` binds the co-located database functions | a pod eval engine, retained per-agent SCI contexts, execution child, or second guarded door |
| Pod process lifecycle | `seon.client/start-runtime!` + `stop-runtime!`; one retained closed launch capability and serialized phase order web/SSE → ticker/hosts → database session → admission/projection → awaited release | mode env flags, a second launcher, or a local database replica |
| Restore intent | `seon.dev.restore` owns the pure writer-visible immutable plan, digest, and fact-derived next command; script-only `seon.dev.restore-state` owns fsync publication | a mutable phase/status file, ambient launch/config inputs, ancestry-inferred force success, or a writer-private intent shape |
| Capability fns | `seon.agent.fs` is the template (gating, envelope, paging) | a tool with its own arg/result conventions |
| Big text at rest | `my.blob` content-addressed disk tier — DB holds projections + refs | large text dumps as datoms; ad-hoc log-file trees |
| Provenance (who/when wrote a datom) | tx-meta auto-stamp via `db/with-agent`/`with-tx-context` — join the datom's tx | `created-by`/`created-at`/`source-turn` attrs on domain entities |
| Model/agent evals | `src-inspect-ai/` (tasks + scorers; ledger `evals/scorecard.jsonl`) | a new drive script, a bespoke per-run harness, a 4th testing surface |

## Runtime boundaries

- **`.clj` = the cluster JVM and web-render JVM.** The cluster JVM owns agent
  driving, SCI evaluation, and database-bound functions; the web-render JVM
  owns pure HTTP/SSE derivation.
- **`.cljs` = the interim JavaScript pod and disposable Bun package leaves.**
  The pod has no agent eval or authored-symbol IPC path.
- **`db/*.clj` + `embed.clj`** = the active JVM database/heavy-work authority:
  sole Datahike writer, shared indexed reads, selective interests, and embeddings.
- `.cljc` is for genuinely portable schemas and pure mechanics. Promote a file
  only when both active runtime boundaries need the same code.

## Vendored source — read it, don't guess

`reference-code/` holds full checked-out source for every load-bearing dep.
Writing Clojure from training memory produces confident, wrong code (mutable/
imperative reflexes, guessed library semantics). Ground first:

- `reference-code/datahike/` — db-as-value, indexes, history/as-of, CAS.
- `reference-code/malli/` — validators, `:catn`/`:=>` fn schemas, registry.
- `reference-code/sci/` — the eval cage semantics.
- `reference-code/clojurescript/` — the diffusion worker's leaf compiler
  (`cljs/js.cljs`) and the pod's `await` macro (`:async &env` assert).
- `reference-code/reitit/` — router-as-data. `reference-code/datastar/` — SSE
  morph. `reference-code/transit-cljs/` — the wire codec.
- Per-claim file:line read-map: `docs/seon/architecture/library-grounding.md`.

## Current known weaknesses (fix the root, don't work around)

- The pod is single-threaded: agent/user failures become values at their
  boundary. A core publication/readiness fault records once and may fail the
  development process or readiness gate; do not turn it into a standing census.
- `seon.db` owns one persistent authority session per process and caches its
  latest ordinary database value. Async computation owners acquire one value
  and pass it through every related read; no caller retains a Datahike
  connection or reconstructs a local replica.
- Turn capture is live (one `:seon.agent.turn/rendered-tx` ref plus
  prompt/reply blob refs, `seon.agent.debug/turn`/`turn-diff`) and is the ONE capture
  path — the gated `seon.debug` file tree is deleted; Inspect AI and debug
  projections read prompts by blob hash (see `observability.md`).
