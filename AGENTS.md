# AGENTS.md: How We Build Seon

Seon is where agents do real work for their user, each in its own Copy-on-Write OS process on its own Datahike branch, merging proven changes into one program that gets smaller, faster, and more certain as it grows. This file holds the standing rules for every external coding-agent session and every Seon agent alike; the wiki (`docs/`) holds how the system works, and [decisions](docs/decisions.md) holds the owner's rulings. When a rule changes, edit this file in place rather than appending.

## 0. Start Here

**Read before acting, every time:** [principles](docs/principles.md) first and whole, then this file, [docs/README.md](docs/README.md) (the wiki map), and [docs/plan.md](docs/plan.md) (what is built and what is next). Then **work inside `seon_repl` by default for ALL operations**: all agent context is derived declaratively by the datom-native logic rules engine (`seon.rule`, `seon.rule.effects`, `seon.rule.context`, editable `:seon.rule/*` entities in `resources/seon/rules.edn`) walking your agent entity (`[:seon.agent/id aid]`), assigned `:seon.agent/task`, `<task-chain>`, `<historical-tasks>`, schemas, focus namespace, curated/pinned refs, and reverse refs — rendered through Malli-attached Hiccup renderers into Canvas, Workspace, Debug, and `<walk-context>` alike.

**Where the standing system lives:**
- **Active repository & branch:** `/Users/sean/src/seon-next` on `main` (single checkout — zero Git worktrees needed; all external sessions and internal Seon agents work on isolated Datahike branches).
- **Standing daemon:** `http://127.0.0.1:9998/` (Web UI + `/op` EDN API + `/mcp` JSON-RPC 2.0 endpoint, store `runs/store`, binary `target/seon`).
- **Source of truth:** Once Seon is running, Datahike `:main` IS the source of truth. Every `seon_merge` writes touched `src/`, `test/`, `docs/`, `AGENTS.md`, `resources/seon/tasks.edn`, `resources/seon/schemas.edn`, `resources/seon/rules.edn`, `resources/seon/agents/*.edn`, and `resources/seon/config/default.edn` back to disk and commits to local Git automatically (`Datahike :main === Git main`). **Never edit `src/`/`test/`/`docs/` directly on disk or run manual `git commit` while the daemon is up**, or Git `HEAD` will diverge from Datahike's expected commit SHA.
## 1. The Goal & Number-One Metric

**Moving forward means the loop works for real tasks, end to end:**
1. A user's request becomes a `:seon.task/*` entity in Datahike (`resources/seon/tasks.edn`).
2. An agent works the task from normal context in a few turns.
3. Its reach-selected proofs and `seed = 42` Malli generative checks go green, its branch merges to `:main`, and `:main` runs the result immediately.

**The number-one metric is turns from a roughly specified task to proven, merged code** ([decisions](docs/decisions.md#the-number-one-metric-turns-from-a-roughly-specified-task-to-merged-code)). A task that never merges (or halts on error/budget) is a failure, not a low count. Every fix should eliminate an entire **error class** and be recorded in Datahike's `:seon.fix/*` ledger with its regression test and before/after measurement.

## 2. Working Inside the Runtime (External Sessions Are Not Special)

External sessions and Seon agents use the exact same entity-driven runtime primitives ([agent guide](docs/agent.md)):
1. **Context comes from your agent entity's Datahike pull — not ad-hoc text flags.** Every agent (internal Seon worker or external `seon_repl` session) has an entity `[:seon.agent/id aid]` with a pull pattern (`:seon.ctx/pattern` / `seon.ctx/default-agent-pattern`) and an assigned `:seon.agent/task` (defaulting to the most relevant open task for its scope or `:template/discover-and-plan`). Walking that entity automatically renders `<task-chain>`, `<historical-tasks>`, `<task-templates>`, `ns-tile`, `ctx-tile`, pinned refs, and `<ephemeral-tail>`. On Turn 1 (or when `task`, `scope`, or `context: true` is passed), `seon_repl` attaches any supplied `task`/`scope` directly onto your agent entity and renders its walk; call `(user/assign-task! :ns/task)` or `(user/update-task! {:plan ... :summary ...})` to steer your entity's context, `(user/context)` to re-render it, `(user/chat! "...")` to talk to any agent, and `(user/monitor)` to inspect parent/self/children merge-gate progress.
2. **Edit inside a Datahike branch.** Pass a descriptive `:branch` (`:branch/<topic>`) and `:agent` (`:repl/<topic>`) to `seon_repl` (and optional `:task` / `:scope`). Use structural CST editors (`user/edit-fn!`, `user/add-fn!`, `user/set-meta!`, `user/add-require!`, `user/prune-requires!`, `user/move-vars!`, `user/remove-var!`, `user/doc!`, `user/register-schema!`, `seon.code/file-import-tx`). Never spit `.clj` files on disk or build parallel external tooling.
3. **Prove and merge inside.** Run `seon_test` (reach-selected, fail-fast `clojure.test` + 100-sample `seed = 42` Malli generative checks + anti-test-deletion gate), then `seon_merge`.
   - Normal merges hot-load directly into the running server and `ForkParent` with zero restart.
   - Merges touching boot inputs (`seon.main`, `seon.fork`, `deps.edn`, `native/*`) set `:boot-affected? true` and automatically trigger `bin/build seon` -> `--self-test` -> graceful drain -> `--take-over 9998`. Never run manual `bin/build` + `kill`.
4. **Thin wrappers only.** `bin/mcp` (stdio relay to `POST /mcp`) and `bin/seon` (CLI relay to `POST /op`) hold zero logic. Any missing capability belongs in `src/seon/`, not in shell scripts beside it.
## 3. Engineering Discipline

- **Search prior work first.** Before adding any function, schema, or mechanism, search existing namespaces (`user/search`, `user/grep-src`, `user/ns-outline`, `docs/`) and extend or replace the existing mechanism. Delete superseded code in the same merge so the codebase shrinks as it improves.
- **Design to the lower bound.** Work out `n`, big-O, ticks, and bytes before coding. Prefer indexed Datahike reads (`(d/datoms db :avet/:aevt ...)`) over full scans or `d/q` inside loops. Respect the **256 MB** eval allocation cap (`:seon.config/eval-bytes`) and **`< 8,000` token** namespace budget (`:oversized-namespace`).
- **One schema authority.** Every stored attribute is derived from a co-located multi-segment Malli schema (`resources/seon/schemas.edn`) and checked at the write seam (`seon.store/validate-tx-data!`). Pure functions carry `:malli/schema` (predicate symbols like `number?`, `pos-int?`, never `:number`) and `:cost/ticks` metadata so `seed = 42` generative checks prove them automatically.
- **Fix classes, never add caller guards.** Constrain bugs at their root (immutable values, single owner, schema/CST validation, Flow channels) rather than piling checks, retries, or waivers at callers. Only named `^:seon.flow/edge` boundaries touch the outside world or catch external faults.
- **No external project code in Datahike.** Datahike holds Seon's own code, docs, tasks, schemas, and telemetry. External polyglot repositories (e.g., Harbor / Terminal-Bench containers) are edited through `seon.fork.container` (`user/sh!`, `user/fs-read`, `user/fs-write`, `user/fs-patch`).
- **Never rig benchmarks.** Internal benchmark rounds MUST dispatch real `:seon.task/*` entities via `:op :create-task` + `:op :spawn-agent` with rough 1–2 sentence descriptions (zero spoon-fed recipes, schemas, or test paths) ([bench](docs/bench.md)).

## 4. Architecture & Docs Map

- [docs/principles.md](docs/principles.md) — The 7 founding principles and what enforces them.
- [docs/architecture.md](docs/architecture.md) — Static Flow graph, COW `ForkParent`, Malli -> Datahike derivation, cold-boot overlay sync, and Git write-back.
- [docs/agent.md](docs/agent.md) — Complete `user/*` REPL helper reference, `@seon.ctx/ctx`, `result/e<n>`, and `seon_*` MCP tools.
- [docs/render.md](docs/render.md) — 120 fps `datahike.reads` walk, Living Canvas & Chronicle, `#cv-bench-runs`, `ns-tile`, `ctx-tile`, and single-source LLM wire assembly.
- [docs/health.md](docs/health.md) — Pure derived health rules, efficiency advisories, reach testing, and `:seon.fix/*` class-fix ledger.
- [docs/bench.md](docs/bench.md) — Parallel isolated Seon trials (`seon.bench.*`), Harbor Host-Bridge Mode, integrity rules, and measured baselines.
- [docs/plan.md](docs/plan.md) — Shipped phases and active roadmap aligned with `resources/seon/tasks.edn`.
- [docs/decisions.md](docs/decisions.md) — Owner rulings with dates and context.
- [docs/forks.md](docs/forks.md) — Tracked library forks under `reference-code/` (`jolt`, `chez`, `datahike`, `konserve`, `malli`, `persistent-sorted-set`).

## 5. Honesty, Evidence & Hygiene

- **Mark every claim:** `[M]` when directly observed from a tool, REPL eval, or log; `[H]` for hypotheses. Never report a score without checking the actual trial state, health report, and `eval_trace` (watch for proxy budget halts and container integrity violations).
- **Check outcomes, not intent:** After every merge or benchmark round, verify `seon_health` (`:ok? true`, `0` errors, `0` `:oversized-namespace`, `0` `:minified-form`, `0` `:dangling-declare`, `0` `:unused-require`), `seon_test` green, `0` leaked Docker containers (`docker ps -a`), `0` leaked temp dirs/processes, and clean `git status`.
- **Keep facts in their proper home:** Update the owning doc, task (`:seon.task/*`), or `:seon.fix/*` record as soon as something changes; post major milestones to Living Canvas via `(user/record-summary! ...)`.
