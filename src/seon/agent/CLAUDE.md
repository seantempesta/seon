# src/seon/agent — the engine (loop, run, turn, context, capability fns)

**Read before editing:** `docs/seon/architecture/agent-runtime.md` (loop/run/
turn/FSM/bounds), `data-model.md` (every attr you'll touch), `observability.md`
(what each turn must persist), `toolkit.md` (function conventions). We-are-here:
`docs/prds/agent-ctx/roadmap.md` (+ `context-rebuild.md`, the
context-rebuild plan of record). Skills: `datahike`, `clojurescript`,
`data-oriented-clojure`.

## Systems at play

- **`loop.cljs`** — the FSM as data: a fold of one transition over events
  derived from the run's data each iteration. It pins **ONE frozen db value
  per turn** (§8a) and threads it everywhere; never re-deref `*conn*` mid-turn.
- **`run.cljs`** — a run is the bounded unit a trigger opens: two independent
  bounds (turn-limit + wall-clock deadline), run-id as fencing token, the
  in-tx `:db.fn/cas` work-fence so a superseded run's writes abort at commit.
- **`turn.cljs`** — one turn: `render-prompt` (pure over the frozen db) →
  `call-llm!` (the SOLE LLM retry authority — never add a parallel retry) →
  eval the reply's forms → persist. Turn datoms are projections; big text
  goes to blobs (see observability.md).
- **`ctx.cljs` + `ctx/*`** — the block system: blocks are seed-COPIED into
  the agent's own `:seon.agent/ctx` at creation; `render-context` reads that
  complete set by `:seon.agent.ctx/priority` and stops. Override =
  `install!`/`remove!`, period. No render-merge, no default set, no provider.
  Each `ctx/*` file is one block family (namespaces, transcript, warnings,
  canvas, usage, findings, inventory, relevant).
- **`inspect.cljs`** — `ctx-preview` renders the agent's context through the
  SAME `render-context` path (byte-identical to the real prompt). Extend
  inspection here (`turn`, `turn-diff`), never as a second render path.
- **`fs.cljs`** — THE capability-fn template: allowlist gating via
  `configure!`/grants, errors-as-values result envelope, honest paging.
  Every new tool (shell, python, web, blob) copies this shape exactly —
  same envelope, same gating pattern, no new conventions.
- **`search.cljs`** — the one literal-search door: `grep` (ripgrep, files)
  + `grep-graph` (regex over DB text attrs). Widen its targets; don't write
  a new scanner.
- **`message.cljs` / `todo.cljs` / `schedule.cljs` / `lifecycle.cljs`** —
  wake triggers (inbound message or due schedule via the ONE ticker),
  the todo tree, agent lifecycle. State is DERIVED (`seon.derive/
  derive-state` from open-run/paused-at/terminated-at) — never store a
  status field.

## Invariants that bite

- **Never throw into the loop.** Every failure becomes a `:seon/error` value
  surfaced via warnings — the pod is single-threaded; one uncaught throw
  blanks every agent.
- **Bootstrap is seeded forms**, eval'd quietly (`:core` origin, no wake, no
  turn count) — the agent sees its own startup. Don't add hidden core magic.
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
  turn threads the current-ns over the turn's frozen db
  (`ctx/current-ns`, `run-turn!` → `ask-and-eval!` → `eval-batch!` in
  `turn.cljs`), so an `(in-ns …)` from a PRIOR turn holds across the boundary
  — home-seeding silently defined into `my.agent.*` and broke cross-ns
  resolution.
- **repl-mode `:batch`/`:stream` is a DB datom** (`ctx/repl-mode` off the
  `:seon.config` singleton; manifest-absent default is per-MODEL,
  `config/default-repl-mode`). `:stream` aborts the LLM stream at the first
  complete top-level form and evals ONE form per turn, so the run's work
  bound counts FORMS (`derive/run-form-count`, `run/default-form-limit` 60);
  `:batch` evals the full parse but strips model-typed result claims at the
  reply boundary (`ctx/strip-result-claims`) before persist + eval.

## Vendored grounding

`reference-code/sci/` (the cage), `reference-code/clojurescript/` (self-host
`cljs/js.cljs`, the `await` macro), `reference-code/datahike/` (db-as-value,
CAS, as-of), `reference-code/again/` (the retry-strategy design `seon.retry`
ports to async errors-as-values). Per-claim map:
`docs/seon/architecture/library-grounding.md`.
