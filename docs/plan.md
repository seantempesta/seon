# Seon Rebuild Plan & Active Roadmap

This document tracks what is shipped on `main` and what is next, kept synchronized with open `:seon.task/*` entities in `resources/seon/tasks.edn`.

## Phase 1 — Lean Core, Health, Living Canvas & Atomic Switch-Over (Shipped)

- [x] Static Flow graph + 1-thread COW `ForkParent` + persistent multi-turn agent workers with LRU eviction and lazy `:main` delta sync.
- [x] 100% Malli-derived Datahike schemas (`resources/seon/schemas.edn`, 265 schemas / 467 derived attributes, write-seam rail, `seed = 42` generative checks, upfront `:malli/schema` validation in `add-fn!`/`set-meta!`/`health/report`).
- [x] Declarative Disk <-> Datahike <-> Git persistence (`seon.code.files`): `src/`, `test/`, `docs/`, `AGENTS.md`, `resources/seon/{schemas.edn,tasks.edn,rules.edn,agents/*.edn,config/default.edn}` import on fresh install and write back atomically on every green `seon_merge`.
- [x] Datom-native Logic Rules Engine (`seon.rule`, `seon.rule.effects`, `seon.rule.context`, `seon.rule.seed`, `resources/seon/rules.edn`): 43 editable `:seon.rule/*` entities operating over EAV working memory + indexed Datahike reads inside `datahike.reads/with-reads` (`step-rules`), cached boundary effects, GTA-6 modifier tags (`:view/tag`), cycle-safe Graph->Tree projection (`:view/parent`, `derive-bag`), delegation-tree curation & overlap boosting (`:seon.agent/curated`, `:tag/tree-overlap`), A/B `diff-rules`, `d/as-of` turn replay, and Debug Rule Provenance (`#dbg-rule-trace`).
- [x] Pure derived Health engine (`seon.health.*`) + fail-fast reach tests + anti-test-deletion gate + `:boot-affected?` automatic atomic switch-over (`bin/build seon` -> `--self-test` -> drain -> `--take-over 9998`) + cold-boot `src/` & `test/` AOT overlay sync (`seon.main/sync-cold-boot-forms!`).
- [x] Living Canvas & Chronicle (`seon.render.chronicle`, `user/record-summary!`), 120 fps `datahike.reads` incremental walk with cache-transparent `seon.store/lru-get!` read-replay, Clojure-data CSS (`seon.web.styles`), and native macOS `WKWebView` host (`target/seon-browser`).
- [x] Flow-native recursive delegation (`ask!`, `await!`, `call!`, `answer!`, `fail!`, `cancel!`) + true `[:db/retractEntity]` compaction (`user/compact!`) with `d/history` time-travel.
- [x] Modular `< 8k` tok namespaces across the entire codebase (`0` `:oversized-namespace`, `0` `:unused-require`, `0` `:minified-form`, `0` `:dangling-declare`).
- [x] CST-safe `rewrite-clj` editing (`user/edit-fn!`, `user/add-fn!` with auto-format + automatic topological form reordering via `seon.code.cst.ns/topo-order-ns-forms`, `user/prune-requires!`), `ns-tile` `Requires:` + `Test ns requires:` + `Private helpers:`, `schema-tile` `[unique]`/`[indexed]` annotations, and class-level `enrich-eval-error` recovery hints.
## Phase 2 — Parallel Isolated Benchmarks, Harbor Host-Bridge & `:seon.fix/*` Loop (Shipped)

- [x] Multi-instance CLI flags (`--port 0`, `--headless`, `--seed-store`, `--model-base-url`, `--auto-switch-over false` via `clojure.tools.cli`) + shared streaming quota proxy (`seon.bench.proxy`) + isolated cluster runner (`seon.bench.cluster`, `seon.bench.helpers`) with `:require-merge? true` enforcement for `:task` trials.
- [x] Live benchmark monitoring on `http://127.0.0.1:9998/` (`:seon.bench.run/*`, `seon.render.tiles/bench-run-tile`, `user/start-bench-run!`, `#cv-bench-runs`).
- [x] Official Harbor `0.26.0` Host-Bridge integration (`bench/harbor/seon_agent.py` `_ContainerBridgeServer` + `seon.fork.container` `sh!`/`fs-read`/`fs-write`/`fs-patch`, zero external code in Datahike, local-only integrity rule, chronological `eval_trace` — `7/9` genuine TB2.1 passes @ `87–96%` KV cache).
- [x] `:seon.fix/*` Datahike class-fix ledger (`8` earlier overnight fixes archived in `runs/backup-store-20261121-1107`; active class fixes recorded directly on live `:main` in `runs/store`).
- [x] Clean Un-Rigged Baseline Run (`Un-Rigged Real-Task Round 4`, `f8c69c74`, `:require-merge? true`): `3/3` genuine auto-merged & healthy (`89.0%` KV cache; `:task-bench-rollup` 13 evals, `:task-eval-summary` 23 evals, `:task-split-md` 26 evals).
## Phase 3 — Active Priorities (Aligned With Open `:seon.task/*` Backlog)

- [ ] **Surface Explicit Model Error State (`:seon.model/surface-model-error-state`):** Record typed `:seon.agent/last-model-error` on HTTP 429 / upstream model faults in `seon.model/model-proc` instead of silently synthesizing `(user/wait-for-mail!)` into `:idle`, so UI, reconciler, and `wait-for-agent!` distinguish clean completion from model/budget halts immediately.
- [ ] **Reduce `seon.health/report` Memory Allocation (`:seon.health/reduce-report-allocation`):** Trim peak allocation well below the 256 MB eval cap so `(user/health)` composes safely with other queries inside a single REPL eval without hitting OOM.
- [ ] **Automated 3-Stage Proof Cascade (`:seon.test/end-of-eval-auto-proofs`):** Automatically run task `:seon.task/tests` + edited `:malli/schema` generative checks (~4 ms each) at end of eval block (non-blocking, surfaced in ephemeral tail) and add 1 ms COW merge-preview delta testing when `:main` moved since branch base.
- [ ] **Shrink Remaining `:large-function` & Library Consolidation:** Split high-churn large functions (starting with `seon.render.markdown/render-activity-fold` and `seon.render.ns-tile/ns-tile-full`), replace ad-hoc `defonce atom {}` caches with `clojure.core.cache.wrapped/lru-cache-factory`, and expand `:malli/schema` coverage across pure core helpers.
- [ ] **Semantic Embedding Boundary Effect & Root Research-Tree Router:** Wire optional local vector/embedding search into `seon.rule.effects/execute-boundary-effects` (`:ctx.match/embedding`, already derived under `:ctx.match/semantic` in `seon.rule/default-hierarchy`) and benchmark Root -> parallel research specialists (`user/curate!`) -> worker handoff end-to-end.
## Phase 4 — Deferred (Build Only When Benchmarks Show Turn Savings)

- [ ] Cross-platform embedded browser parity (`tauri-apps/wry` for Windows `WebView2` / Linux `WebKitGTK` — macOS `WKWebView` already works).
- [ ] Hybrid semantic vector search (`libseon-embed.dylib` in a post-`ForkParent` worker) once codebase size outgrows `user/grep-src` + call-graph reach.
- [x] Promoted `jolt/rebuild` to `/Users/sean/src/seon-next` `main` and retired all legacy Git worktrees (every external session and Seon agent works on Datahike branches inside the single checkout).
