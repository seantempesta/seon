# Seon Rebuild Plan & Active Roadmap

This document tracks what is built, what is next, and every agreed architectural plan so nothing gets lost between sessions.

## Phase 1 — Lean Core, Health, Data-Driven UI & Seamless Switch-Over (Shipped on `jolt/rebuild`)

- [x] Static 8-proc Flow graph + 1-thread COW `ForkParent` (`GC ceiling = 5` / child `4`) + persistent multi-turn agent workers with LRU eviction and lazy `:main` delta sync.
- [x] 100% Malli-derived Datahike schemas (`resources/seon/schemas/*.edn`, 208 schemas, multi-segment naming, write-seam rail, `seed = 42` generative checks).
- [x] Declarative Disk <-> Datahike <-> Git persistence (`src/seon/code/files.clj`): code, docs, schemas, Aero config, tasks (`resources/seon/tasks/*.edn`), and seed/example agent records (`resources/seon/agents/*.edn`). Fresh install imports cleanly; resume at matching Git HEAD reads 0 files; green merge writes back touched paths with 2-parent local Git commits.
- [x] Zero `if root` special-casing: `:agent/root` behavior, prompt, routing table, and Canvas come purely from its Datahike record (`resources/seon/agents/root.edn`) and generic renderers.
- [x] Pure derived Health engine (`seon.health` + `seon.health.efficiency`) + fail-fast reach tests + anti-test-deletion gate + automatic `:boot-affected?` atomic binary switch-over (`bin/build seon` -> `--self-test` -> graceful drain -> `--take-over`).
- [x] Native macOS `WKWebView` Host (`target/seon-browser`) + `:browser` proc for ephemeral end-of-context multimodal viewport feedback.
- [x] Flow-native multi-level recursive delegation (`ask!`, `await!`, `call!`, `answer!`, `fail!`, `cancel!`, cycle rejection, Malli `:result-schema`, failure settlement) + true `d/history` retraction compaction (`user/compact!`).
- [x] Oversized monolith splits completed (`seon.web`, `seon.fork`, `seon.code`, `seon.transactor`, `seon.render.ui` 32.4k -> 4.4k tok across 7 cohesive `seon.render.*` namespaces; all 48 namespaces well under 10k tok).
- [x] Scannable progressive-disclosure `Thinking & actions` folds in Canvas & Workspace (`seon.render.markdown`) + structural `rewrite-clj` REPL tools (`user/edit-fn!`, `user/grep-src`, `user/run-tests!`, `user/unused-requires`, `user/prune-requires!`, unified `user/diff` in `seon.code.edit`).

## Phase 2 — Library Consolidation, Lean Testing & Eval-Block Auto-Proofs (In Progress)

**Principle:** Functions are tools (no separate tool entity); agents define functions, Malli schemas (`user/register-schema!`), and tests in any namespace and they persist, merge, and hot-reload natively. Keep the test suite small, fast (< 10 s), and high-signal by leaning on `:malli/schema` generative laws + static `seon.health` gates instead of hundreds of banal unit tests.

- [x] Wire already-pinned `rewrite-clj` (`seon.code.edit`) for whitespace/tab/comment-insensitive structural editing & AST-accurate require pruning.
- [ ] Prune copied unused `:require`s across split `seon.render.*` namespaces & `seon.web.css`; replace 5 hand-rolled `defonce atom {}` caches with `clojure.core.cache.wrapped/lru-cache-factory`; delete internal pass-through façade stubs.
- [ ] Convert `seon.web.css` from a raw CSS string literal into composable runtime-compiled Clojure-data CSS + mounted Datastar `<style id="seon-css">` hot-patching (zero page refresh on style edits).
- [ ] Wire `jolt-lang/router` + `reitit-core` + `clojure.tools.cli` (and process handles) to simplify `seon.web` routing and `seon.main` CLI flags.
- [ ] **Automated 3-Stage Proof Cascade & Task Completion:**
  1. **End of eval block:** automatically run the agent's task `:seon.task/tests` proofs + edited `:malli/schema` generative checks (~4 ms each) and surface failures directly in context without blocking.
  2. **On `(user/ready!)`:** verify all declared `:seon.task/tests` exist & pass, then run fail-fast uncached reached tests (fast + generative first, short-circuiting before slow E2E).
  3. **Ephemeral `:main` merge-preview check:** if `:main` moved since `(d/merge-base conn :main branch)`, run only delta-reached tests on a 1 ms COW merge-preview branch before committing `:main` and auto-marking `:seon.task/done? true` (0 ms no-op when `:main` hasn't moved).
- [ ] Attach `:malli/schema` contracts to pure core functions across `seon.code.*`, `seon.merge`, `seon.render.data`, `seon.schema`, and `seon.health` so `seed=42` generative checks eliminate banal unit tests.

## Phase 3 — Parallel Isolated Seon Runtimes & Multi-Benchmark Evaluation (Next)

**Goal:** Measure every design, prompt, and agent-authored function strictly by **pass rate and turns-to-merged** across parallel isolated environments — without risking benchmark scoring bugs.

- [ ] **Parallel Isolated Seon Instances:** Add `--port 0`, `--headless`, `--seed-store` (APFS `cp -cR` clone in < 10 ms), `--model-base-url`, `--auto-switch-over false` to `seon.main`; remove global `checkout-root` fallbacks; build a shared local Model Quota & Cost Proxy (`seon.bench.proxy`) so N parallel Seon instances (each running multi-agent swarms) never 429 each other and attribute exact token/dollar costs.
- [ ] **Suite A — Seon-Native A/B Rounds:** Clone Seon's own store + repo into 2×N parallel isolated `target/seon` instances to A/B-test branches/functions on randomized backlog tasks (grading via `seon_test`, `seon_health`, and turns-to-merged).
- [ ] **Suite B — Harbor Official Benchmark Integration (`harbor run -d terminal-bench@2.0` / `swebench-verified`):** Run Seon's compiled native `target/seon` binary inside Harbor's official Docker/cloud benchmark containers via a thin Harbor `BaseInstalledAgent` wrapper — Harbor owns dataset downloads, container isolation, and official untouched verifiers (zero risk of corrupting numbers), while Seon runs its full multi-agent Clojure runtime inside each environment.
- [ ] **Iterative Self-Improvement Loop:** Have specialist agents inspect parallel trajectories via Datahike `d/history`, write/refine reusable Clojure functions & Malli schemas on branches, prove lift via parallel A/B benchmark batches, and surface side-by-side results on Canvas for promotion to `:main`.

## Phase 4 — Deferred (Build Only When Benchmarks Show Turn Savings)

- [ ] Cross-platform embedded browser parity (`tauri-apps/wry` for Windows `WebView2` / Linux `WebKitGTK` — macOS `WKWebView` already works).
- [ ] Hybrid semantic vector search (`libseon-embed.dylib` in a post-`ForkParent` worker) once codebase size outgrows `user/grep-src` + call-graph reach.
- [ ] Branch-local unmerged custom entity-tile renderer delegation & periodic LMDB compaction.
