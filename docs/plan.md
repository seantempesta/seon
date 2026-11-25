# Seon Rebuild Plan & Active Roadmap

This document tracks what is shipped on `main` and what is next, kept synchronized with open `:seon.task/*` entities in `resources/seon/tasks.edn`.

## Phase 1 — Lean Core, Health, Living Canvas & Atomic Switch-Over (Shipped)

- [x] Static Flow graph + 1-thread COW `ForkParent` + persistent multi-turn agent workers with LRU eviction and lazy `:main` delta sync.
- [x] 100% Malli-derived Datahike schemas (`resources/seon/schemas.edn`, `388` schemas / `664` derived Datahike schema entries `[M]`, write-seam rail, `seed = 42` generative checks).
- [x] Declarative Disk <-> Datahike <-> Git persistence (`seon.code.files`) + mechanical Conventional-Commit merge messages (`seon.git/format-merge-commit-message`) + inside-Seon fork indexing & pushing (`seon.git.forks`, `:op :index-forks`, `./bin/seon push`).
- [x] Native Datahike `d/q` + `d/pull` incremental rule engine (`seon.rule`, `47` editable `:seon.rule/*` entities `[M]`) + 3-signal rank + dual-target Complete-Thought Upgrade Ladder (`seon.render.fit`) driving `:session`, `:context`, `:system`, `:inspect` (plus legacy `:canvas`, `:workspace`, `:debug`), and OpenAI Wire `<walk-context>`.
- [x] Data-driven `:seon.route/*` Reitit SSR/SPA router (`seon.web.router`), Everyday vs Expert UI toggle, sanitized SVG live status cards (`user/set-status!`), JIT Datalog primers (`docs/primers.md`), and unified zero-dep Hiccup->text (`seon.render.text`).
- [x] Pure derived Health engine (`seon.health.*`, cold `report` `246.3 MB < 256 MB` via content-addressed `:seon.code.ns/{forms-hash,unused-requires}` + parse-time form metrics) + LPT `:slow`-tier sharded reach tests (`seon.test`, `seon.test.lpt`) + O(1) `datahike.db/equiv-db` + zero-downtime `--take-over 9998`.

## Phase 2 — Parallel Isolated Benchmarks, Harbor Host-Bridge & `:seon.fix/*` Loop (Shipped)

- [x] Multi-instance CLI isolation, shared streaming quota proxy (`seon.bench.proxy`), live `#cv-bench-runs` monitoring, and strict `:require-merge? true` accounting.
- [x] Official Harbor `0.26.0` Host-Bridge integration (`7/9` genuine TB2.1 passes @ `87–96%` KV cache).
- [x] Compounding Live DeepSeek Waves 1–4 (`Round 7`): **`9/9` consecutive auto-merges across Waves 2–4**, `0` OOMs on Wave 4, `13`-turn double-monolith record, `:large-function` cut `15 -> 5` (`-67%`).

Phase 3 — Active Priorities (Codebase Unification & Recursive Self-Improvement)
- [x] **End-of-Eval Auto-Proofs & Trace Class Fixes (Wave D3 + E0):** Non-blocking `run-fast-auto-proofs!` honoring `^:seon.flow/edge`, `24k` verbatim eval/stdout cap, target-ns `::alias/kw` parsing in `add-fn!`/`set-meta!`, `:ex-data` Malli sub-schema hints, `@(user/db)` recovery hints, +1 wrap-up grace turn.
- [x] **Accretive `:db.type/ref` Domain Graph & Unified Entity Context Kernel:** `:seon.agent/parent-ref`, `:seon.agent.call/{caller,callee,task}-ref`, `seon.ctx/project-entity`, Baseline 0 minimal rule kernel (`resources/seon/rules.edn`), and strict zero-repetition `<context-delta>` (`seon.render.fit`, `seon.render.wire`).
- [x] **Declarative `^:seon.repl/export` REPL Helper Registry (Audit Wave E2-A):** Replaced imperative `install-*-helpers!` closures with `30` directly inspectable `^:seon.repl/export` vars (`seon.fork.helpers`).
- [x] **Self-Assembling Graph Agents, Structured EDN Eval Output, Derived Touches & Forked Review Gates:** Retired `:seon.agent/role`, `:seon.agent/prefix-mode`, scalar `:seon.agent/task`, untyped `:seon.agent/memory`, arbitrary caps, fake `; ` comment eval returns (`seon.model.wire/format-eval-output` EDN maps), and persisted `:seon.agent.eval/touched` / `:touched-refs` LMDB writes (`seon.ctx.touches/derived-agent-touches`); shipped unified graph-driven agent prefixes (`seon.model.messages/render-prefix-agents`), verifiable forked review proofs (`seon.task.gate/evaluate-review-outcome`), and bounded 6-turn auto-merge cutoffs (`seon.task.gate/review-cutoff-ops`).
- [ ] **Context & Printer Convergence (Audit Wave E3):** Complete surface/UI unification on `seon.ctx/project-entity`, make rule pre-skip data-driven via `seon.rule/query-meta`, collapse `seon.render.print.*` files, and purge remaining dead vars.
- [ ] **Graph Density & Turn-0 Predictive Recall (`45% -> 80%+`):** Replace opaque `:map` signatures with named `:seon.*/*` schemas and wire EmbeddingGemma-2 (`seon.vector`) into Turn-0 candidate ranking.
- [ ] **Autonomous Trace-to-Fix Meta-Loop & Multi-File Delegation Benchmarks:** Run scheduled meta-diagnosis over `:learning-metrics` + chronological traces, verify fixes via isolated A/B `seon.bench.cluster` trials, and benchmark hierarchical `:agent/root` -> `:ns/*` cross-namespace features.
- [ ] **Eliminate Remaining `:oversized-namespace` & `:large-function` Warnings** across `src/` so full `(user/health :all)` hits `0` size advisories.
- [x] **Recursive Proof-Driven Refinement, Native Paired A/B Bench (`user/run-ab!`), NASA Coverage & Cockpit `<session-budget>`:** Shipped decoupled Rule A -> Rule B -> Refiner RED-TDD -> Auto-Anchor -> zero-conflict fork-merge (`seon.task.refine`), NASA completeness matrix + 12-scenario corpus (`seon.health.{coverage,corpus}`), `seon.bench.golden/run-ab!` with honest 0-edit rejection & Refiner+Worker roll-ups, `:fork-review` / forked-agent KV prefix anchors, and ephemeral `<session-budget>` gauge (`seon.render.wire`). Measured across 5 paired tasks (`[M]`): Control `4/5 (80%, 86.4% KV)` beat cold 2-agent auto-refinement `2/5 (40%, 74.4% KV)` — keeping `:auto-refine-tasks? false` by default while targeting native `(in-ns 'new-test)` auto-ns seeding and Warm-Continuation Refinement next.
- [x] **Data-Derived Auto-Completion, Turn-0 Recall, Pause-and-Fork Supervisor Lab & Tool Ergonomics (Nov 25, 2026):** Retired `(user/ready!)`/`(user/finish-review!)` via pure Datahike `implicit-ready?`, shipped `seon.bench.lab/run-supervisor-cycle!` (sync & `:sync? false` streaming to `:seon.bench.run/*`) + `user/bench-run`, `O(1)` indexed Turn-0 scoped recall (`seon.ctx.graph`), zero-planner `active-task-rows` & `scope-ns-rows` (`corpus-report` `124.8 MB -> 66.8 MB`), bounded `@user/ctx` (`commit-delta-datoms`), in-place `deftype`/`defrecord` REPL replacement, automatic `:error-samples`/`:failing-tests` persistence, `bin/seon` stdin `eval` & `scope`, and fixed CWD-relative test slurps. Verified `[M]`: `1,019/1,019` green, `3/3 (100%)` unseen `seed=101` random tasks merged cold, and Async Supervisor Cycle 4 (`seed=102`) merged cleanly.
## Phase 4 — Deferred

- [ ] Cross-platform embedded browser parity (`tauri-apps/wry` for Windows `WebView2` / Linux `WebKitGTK` — macOS `WKWebView` already works).
- [ ] End-to-end Harbor TB2.1 re-run (`7/9 -> 9/9`) and inside-Seon `:fork/datahike` edit-build-push E2E validation.
