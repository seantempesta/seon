# src/seon/agent — the engine (loop, run, turn, context, capability fns)

**Read before editing:** `docs/seon/architecture/agent-runtime.md` (loop/run/
turn/FSM/bounds), `data-model.md` (every attr you'll touch), `observability.md`
(what each turn must persist), `toolkit.md` (function conventions). We-are-here:
`docs/prds/runtime-reliability/roadmap.md`. Skills: `datahike`, `clojurescript`,
`data-oriented-clojure`.

## Systems at play

- **`loop.cljs`** — the FSM as data: a fold of one transition over events
  derived from the run's data each iteration. It pins **ONE complete database
  value per turn** (§8a) and threads it everywhere; never read a newer database
  value mid-turn.
- **`run.cljs`** — a run is the bounded unit a trigger opens: two independent
  bounds (turn-limit + wall-clock deadline), run-id as fencing token, the
  in-tx `:db.fn/cas` work-fence so a superseded run's writes abort at commit.
- **`turn.cljs`** — one turn: `render-prompt` (one database-value-pinned compiled
  child result) →
  `call-llm!` (the SOLE LLM retry authority — never add a parallel retry) →
  eval the reply's forms → persist. Turn datoms are projections; big text
  goes to blobs (see observability.md).
- **`ctx.cljs` + `ctx/*`** — the block system: blocks are seed-COPIED into
  the agent's own `:seon.agent/ctx` at creation; the compiled prompt owner
  acquires that complete set and pure formatting orders it by
  `:seon.agent.ctx/priority`. Override =
  `install!`/`remove!`, period. No render-merge, no default set, no provider.
  Each `ctx/*` file is one installed block family (namespaces, transcript,
  warnings, canvas, menu, subagents, typeahead-steps).
- **`debug.cljs`** — `ctx-preview` formats the same database-value-pinned compiled
  child result the turn consumes (byte-identical system + context bytes).
  Extend inspection here (`turn`, `turn-diff`), never as a second render path.
- **`fs.cljs`** — THE capability-fn template: allowlist gating via
  `configure!`/grants, errors-as-values result envelope, honest paging.
  Every new tool (shell, python, web, blob) copies this shape exactly —
  same envelope, same gating pattern, no new conventions.
- **`search.cljs`** — the one literal-search door: `grep` (ripgrep, files)
  + `grep-graph` (regex over DB text attrs). Widen its targets; don't write
  a new scanner.
- **`home.cljs` / `runtime.cljs`** — home namespace policy and process-local
  execution resources; keep durable authority in database facts.
- **`message.cljs` / `schedule.cljs` / `lifecycle.cljs`** — wake triggers
  (inbound message or due schedule via the one ticker) and agent lifecycle.
  State is DERIVED (`seon.derive/derive-state` from open-run/paused-at/
  terminated-at)—never store a status field.
- **`shell.cljs` / `web.cljs` / `testrun.cljs`** — capability functions with
  the same schema, grant, envelope, and paging conventions as `fs.cljs`.

## Invariants that bite

- **Never throw into the loop.** Every failure becomes a `:seon/error` value
  surfaced via warnings — the pod is single-threaded; one uncaught throw
  blanks every agent.
- **Bootstrap is the compiled child package plus current database-authored
  namespace sections.** `cljs.js` loads whole namespaces and their requires;
  never replay individual forms or add another bootstrap path.
- **A turn persists one rendered transaction ref**, not its request-scoped
  database-value map. Attempts derive history through their parent turn.
- **Transcript clips must render byte-identical as they age** (age-band
  eviction, not recency-weighting) or the LLM prompt cache is busted every
  turn.
- **Home-ns data/function aliases (`db/`, `plan/`, `message/`, `schema/`) resolve
  in agent-authored `my.*` nses too** — `seon.eval/augment-ns-source` writes
  the real `(:require …)` into every authored `(ns …)` form (no magic; stored
  + resume-safe, #73/#56 CLOSED). The `my.*` toolkit, the `agent/` alias, and
  the lifecycle refers are NOT auto-aliased — full-qualify those.
- **Provenance is on the TX entity** — `transact!` auto-stamps agent/turn/eval
  tx-meta; never add `created-by`/`created-at` domain attrs (datahike skill).
- `^:async`/`await` only inside `^:async` fns (self-host asserts); returned
  Promises auto-await via `seon.eval/maybe-await-value`.
- **Eval batches seed from the agent's DERIVED current-ns, never home.** The
  compiled prompt acquisition returns current-ns with the rendered prompt at
  the same database value; the turn threads it through `run-turn!` →
  `ask-and-eval!` → `eval-batch!`, so an `(in-ns …)` from a PRIOR turn holds across the boundary
  — home-seeding silently defined into `my.agent.*` and broke cross-ns
  resolution.
- **repl-mode `:batch`/`:stream` is a DB datom** returned by the same compiled
  prompt acquisition from the `:seon.config` singleton; manifest-absent default is per-MODEL,
  `config/default-repl-mode`). `:stream` aborts the LLM stream at the first
  complete top-level form and evals ONE form per turn, so the run's work
  bound counts FORMS (`derive/run-form-count`, `run/default-form-limit` 60);
  `:batch` preserves the exact provider reply, parses it once, then attempts
  every complete parsed form and records only real execution results. Runtime-
  like narration remains evidence and never creates an event by resemblance.

## Vendored grounding

`reference-code/sci/` (the cage), `reference-code/clojurescript/` (self-host
`cljs/js.cljs`, the `await` macro), `reference-code/datahike/` (db-as-value,
CAS, as-of), `reference-code/again/` (the retry-strategy design `seon.retry`
ports to async errors-as-values). Per-claim map:
`docs/seon/architecture/library-grounding.md`.
