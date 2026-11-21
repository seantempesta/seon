# Seon Rebuild Plan & Active Roadmap

This document tracks what is shipped on `jolt/rebuild` and what is next, kept synchronized with open `:seon.task/*` entities in `resources/seon/tasks.edn`.

## Phase 1 — Lean Core, Health, Living Canvas & Atomic Switch-Over (Shipped)

- [x] Static Flow graph + 1-thread COW `ForkParent` + persistent multi-turn agent workers with LRU eviction and lazy `:main` delta sync.
- [x] 100% Malli-derived Datahike schemas (`resources/seon/schemas.edn`, 248 schemas, write-seam rail, `seed = 42` generative checks, upfront `:malli/schema` validation in `add-fn!`/`set-meta!`/`health/report`).
- [x] Declarative Disk <-> Datahike <-> Git persistence (`seon.code.files`): `src/`, `test/`, `docs/`, `AGENTS.md`, `resources/seon/{schemas.edn,tasks.edn,agents/*.edn,config/default.edn}` import on fresh install and write back atomically on every green `seon_merge`.
- [x] Pure derived Health engine (`seon.health.*`) + fail-fast reach tests + anti-test-deletion gate + `:boot-affected?` automatic atomic switch-over (`bin/build seon` -> `--self-test` -> drain -> `--take-over 9998`) + cold-boot `src/` & `test/` AOT overlay sync (`seon.main/sync-cold-boot-forms!`).
- [x] Living Canvas & Chronicle (`seon.render.chronicle`, `user/record-summary!`), 120 fps `datahike.reads` incremental walk with cache-transparent `seon.store/lru-get!` read-replay, Clojure-data CSS (`seon.web.styles`), and native macOS `WKWebView` host (`target/seon-browser`).
- [x] Flow-native recursive delegation (`ask!`, `await!`, `call!`, `answer!`, `fail!`, `cancel!`) + true `[:db/retractEntity]` compaction (`user/compact!`) with `d/history` time-travel.
- [x] Modular `< 8k` tok namespaces across the entire codebase (`0` `:oversized-namespace`, `0` `:unused-require`, `0` `:minified-form`, `0` `:dangling-declare`).
- [x] CST-safe `rewrite-clj` editing (`user/edit-fn!`, `user/prune-requires!`, automatic topological form reordering via `seon.code.cst.ns/topo-order-ns-forms`), `ns-tile` `Requires:` aliases + `Private helpers:`, and class-level `enrich-eval-error` recovery hints.

## Phase 2 — Parallel Isolated Benchmarks, Harbor Host-Bridge & `:seon.fix/*` Loop (Shipped)

- [x] Multi-instance CLI flags (`--port 0`, `--headless`, `--seed-store`, `--model-base-url`, `--auto-switch-over false` via `clojure.tools.cli`) + shared streaming quota proxy (`seon.bench.proxy`) + isolated cluster runner (`seon.bench.cluster`, `seon.bench.helpers`) with `:require-merge? true` enforcement for `:task` trials.
- [x] Live benchmark monitoring on `http://127.0.0.1:9998/` (`:seon.bench.run/*`, `seon.render.tiles/bench-run-tile`, `user/start-bench-run!`, `#cv-bench-runs`).
- [x] Official Harbor `0.26.0` Host-Bridge integration (`bench/harbor/seon_agent.py` `_ContainerBridgeServer` + `seon.fork.container` `sh!`/`fs-read`/`fs-write`/`fs-patch`, zero external code in Datahike, local-only integrity rule, chronological `eval_trace` — `7/9` genuine TB2.1 passes @ `87–96%` KV cache).
- [x] `:seon.fix/*` Datahike class-fix ledger (`8` verified overnight class fixes recorded with regression tests and before/after `[M]` metrics).

## Phase 3 — Active Priorities (Aligned With Open `:seon.task/*` Backlog)

- [ ] **Clean Un-Rigged Baseline Run (`500k` proxy cap):** Run the 3 open rough `:seon.task/*` tasks (`:seon.health.metrics/eval-summary-by-agent`, `:seon.render.chronicle/bench-runs-rollup`, `:seon.render.markdown/split-activity-fold`) with `:run-tests? true :merge? true` to measure true end-to-end auto-merge rate and mean turns-to-merged without proxy 429 cutoff.
- [ ] **Surface Explicit Model Error State (`:seon.model/surface-model-error-state`):** Record typed `:seon.agent/last-model-error` on HTTP 429 / upstream model faults in `seon.model/model-proc` instead of silently synthesizing `(user/wait-for-mail!)` into `:idle`, so UI, reconciler, and `wait-for-agent!` distinguish clean completion from model/budget halts immediately.
- [ ] **Reduce `seon.health/report` Memory Allocation (`:seon.health/reduce-report-allocation`):** Trim peak allocation well below the 256 MB eval cap so `(user/health)` composes safely with other queries inside a single REPL eval without hitting OOM.
- [ ] **Automated 3-Stage Proof Cascade (`:seon.test-runner/end-of-eval-auto-proofs`):** Automatically run task `:seon.task/tests` + edited `:malli/schema` generative checks (~4 ms each) at end of eval block (non-blocking, surfaced in ephemeral tail) and add 1 ms COW merge-preview delta testing when `:main` moved since branch base.
- [ ] **Shrink Remaining `:large-function` & Library Consolidation:** Split high-churn large functions (starting with `seon.render.markdown/render-activity-fold` and `seon.render.ns-tile/ns-tile`), replace ad-hoc `defonce atom {}` caches with `clojure.core.cache.wrapped/lru-cache-factory`, and expand `:malli/schema` coverage across pure core helpers.

## Phase 4 — Deferred (Build Only When Benchmarks Show Turn Savings)

- [ ] Cross-platform embedded browser parity (`tauri-apps/wry` for Windows `WebView2` / Linux `WebKitGTK` — macOS `WKWebView` already works).
- [ ] Hybrid semantic vector search (`libseon-embed.dylib` in a post-`ForkParent` worker) once codebase size outgrows `user/grep-src` + call-graph reach.
- [ ] Merge `jolt/rebuild` back onto `/Users/sean/src/seon-next` `main` (when owner directs) and retire temporary worktree tooling (`bin/worktree`).
