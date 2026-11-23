# Benchmarking, Parallel Trials, and Harbor Host-Bridge Mode

Seon measures every context, helper, and runtime improvement by **pass/merge rate and turns-to-merged** across two tracks: isolated internal Seon trials (`seon.bench.cluster`, `seon.bench.helpers`, `seon.bench.proxy`) and official external container benchmarks (`bench/harbor/seon_agent.py`, `seon.fork.container`).

## 1. Internal Parallel Isolated Trials (`seon.bench.cluster`, `seon.bench.proxy`)

`seon.bench.cluster/run-parallel-trial!` spawns `N` completely isolated headless `target/seon` instances (`--port 0 --headless --seed-store runs/store`), each cloning LMDB via APFS CoW in `< 10 ms`, routing all LLM calls through one shared `seon.bench.proxy` (concurrency cap, streaming token/KV-cache accounting, hard token cap).

- **Live UI Monitoring (`user/start-bench-run!`, `:seon.bench.run/*`):** Launches `start-bench-run-async!` in `< 8 ms` and streams throttled trial snapshots over Datastar SSE to `#cv-bench-runs` above Living Canvas on `http://127.0.0.1:9998/`.
- **Strict Merge Accounting (`:require-merge? true`):** Any trial configured with `:task` defaults to `:require-merge? true`, ensuring a worker halted by proxy HTTP 429 (`Pause on model error` -> `(user/wait-for-mail!)` -> `:idle`) is NEVER scored `:passed`.
- **Cold-Boot Parity:** Seeded instances automatically run `seon.main/sync-cold-boot-forms!` to overlay both `src/` and `test/` forms (+ `:seon.code.form/deps`) modified after `aot-build-ms`, eliminating AOT/store split-brain.

### No-Rigging Discipline (Owner Ruling, 2026-11-20)

Internal benchmark tasks MUST go through the exact normal `:seon.task/*` workflow:
- Create/reference real `:seon.task/*` entities (`:op :create-task` or existing `:main` tasks in `resources/seon/tasks.edn`) with rough 1–2 sentence descriptions and `:scope`.
- Never spoon-feed exact recipes, Malli schemas, Datahike attribute names, or test file paths in benchmark prompts.
- Every optimization must be a general improvement to Seon's context tiles (`ns-tile`, `ctx-tile`), `user/*` helpers, `enrich-eval-error` hints, or runtime behavior — and logged in `:seon.fix/*`.

```clojure
;; Launch an un-rigged 3-task parallel run from :agent/root REPL:
(user/start-bench-run!
 {:title "Un-Rigged Real-Task Run (deepseek-flash)"
  :repo-dir "/Users/sean/src/seon-next"
  :bin-path "/Users/sean/src/seon-next/target/seon"
  :seed-store "/Users/sean/src/seon-next/runs/store"
  :model "deepseek-flash"
  :proxy? true
  :proxy-opts {:max-in-flight 3 :max-tokens 500000}
  :task-timeout-ms 180000
  :tasks [{:id :task-split-md :task :seon.render.markdown/split-activity-fold :scope 'seon.render.markdown :run-tests? true :merge? true}
          {:id :task-eval-summary :task :seon.health.metrics/eval-summary-by-agent :scope 'seon.health.metrics :run-tests? true :merge? true}
          {:id :task-bench-rollup :task :seon.render.chronicle/bench-runs-rollup :scope 'seon.render.chronicle :run-tests? true :merge? true}]})
```

## 2. External Benchmarks — Harbor Host-Bridge Mode (`bench/harbor/seon_agent.py`, `seon.fork.container`)

External polyglot repositories (Python, C, Git, ELF, SQLite, shell tasks in Terminal-Bench 2.1 / SWE-bench) are NEVER imported into Datahike. Instead, Seon runs natively on macOS `arm64` via **Host-Bridge Mode**:

1. `bench/harbor/seon_agent.py` (`SeonAgent`) starts a local HTTP bridge (`_ContainerBridgeServer`) proxying container shell & file ops (`docker exec`) to a host-side headless `target/seon` instance with `SEON_EXEC_BRIDGE_URL` set.
2. `seon.fork.container/install-container-helpers!` exposes `(user/sh! cmd opts)`, `(user/fs-read path opts)`, `(user/fs-write path content)`, and `(user/fs-patch path old new opts)` inside the agent's Clojure REPL, while `seon.render.tiles/ctx-tile` switches to `:mode :container` guidance.
3. **Integrity & Audit:** `:mode :container` enforces strict local-only solving (never fetch external benchmark repos/tests), `seon_agent.py` captures read-only `branch-as-db` telemetry plus chronological `tx`-ordered `eval_trace` with per-eval bytes into `seon-result.json`, and streams live progress to `http://127.0.0.1:9998/` (`SEON_MONITOR_URL`).

## 3. Verified Baselines (`[M]`, `deepseek-flash`, Nov 2026)

- **Official Terminal-Bench 2.1 (Harbor `0.26.0`, Host-Bridge Mode):** **`7 / 9` genuine `1.0` passes (`77.8%`)** across `tb21-file-recovery` and `tb21-code-debug` (`multi-source-data-merger`, `log-summary-date-ranges`, `regex-log`, `db-wal-recovery`, `fix-git`, `git-leak-recovery`, `polyglot-c-py`; plus `fix-log-analyzer` smoke `1.0`), `87–96%` KV-cache hit rates, `0` exceptions, `0` integrity issues across all 4 post-rule code-debug tasks.
- **Internal Suite A (Before -> After Malli upfront validation + cold-boot overlay):** `2/3` @ `22.7` mean evals (`643.5k` tok) -> **`3/3` auto-merged @ `10.3` mean evals (`197.3k` tok, `-54.6%` evals, `-69.3%` tokens, `88.3%` KV cache)**.
- **Un-Rigged Real-Task Rounds 1 -> 5 (`:seon.task/*`, `deepseek-flash`, `:run-tests? true :merge? true`):**
  - **Round 1 (`5760d375` in backup store):** `0/3` passed (cold-boot test split-brain `3/3` failed; `17/15/14` evals).
  - **Round 2 (`37ff85c2` in backup store, `300k` cap, pre-`:require-merge?`):** `0/3` truly merged (`merged? false` on all 3 due to `rejected 3` proxy 429 cutoff; `11/22/14` evals, `86.3%` KV cache).
  - **Round 3 (`8b62ccdf`, 38 starter logic rules, `600k` cap, `:require-merge? true`):** `3/3` merged, `1/3` fully healthy & green (`:task-bench-rollup` 20 evals passed; `:task-eval-summary` 23 evals hit `:minified-form`; `:task-split-md` 14 evals hit `add-fn!` `:used-before-defined`; `86.9%` KV cache).
  - **Round 4 (`f8c69c74`, 43 logic rules + schema-renderer & scope-schema-consumer expansion + `schema-tile` `[unique]`/`[indexed]` + `ns-tile` `Test ns requires:` + `add-fn!` auto-format & topo-reorder, `:require-merge? true`):** **`3 / 3` GENUINE PASS (`100%` `:merged? true`, `100%` `:health-ok? true`, `0` test failures, `89.0%` KV cache `777.2k / 872.9k`):** `:task-bench-rollup` dropped `20 -> 13` evals (`49.9s`, `-35%`), `:task-eval-summary` `23` evals (`76.7s`, `0` health errors), `:task-split-md` `26` evals (`154.0s`, `0` test errors; `20.7` mean evals, `154.0s` wall clock).
  - **Round 5 (`914b2369` on `graph-refactor-v2`, 58 logic rules + data-driven fn discovery + 4-stage recursive rule-driven printer + `entity-index` + unified `:seon.error/*` + `4.6x` health perf + per-tile `step-walk` + singular co-location `seon.rule*`/`seon.agent`/`seon.test`, `:require-merge? true`):** **`3 / 3` GENUINE PASS (`100%` `:merged? true`, `100%` `:health-ok? true`, `0` proxy errors/rejections, `90.65%` KV cache `814.5k / 898.5k`):** `:task-split-md` `26 -> 18` evals (`110.7s`, `-30.8%`), `:task-eval-summary` `23 -> 16` evals (`68.5s`, `-30.4%`), `:task-bench-rollup` `19` evals (`74.0s`) — **`17.7` mean evals (`-14.5%` vs Round 4) and `110.9s` wall clock (`-28.0%`)**.
## Section
# Benchmarking, Parallel Trials, and Harbor Host-Bridge Mode

Seon measures every context, helper, and runtime improvement by **pass/merge rate and turns-to-merged** across two tracks: isolated internal Seon trials (`seon.bench.cluster`, `seon.bench.helpers`, `seon.bench.proxy`) and official external container benchmarks (`bench/harbor/seon_agent.py`, `seon.fork.container`).

## 1. Internal Parallel Isolated Trials (`seon.bench.cluster`, `seon.bench.proxy`)

`seon.bench.cluster/run-parallel-trial!` spawns `N` completely isolated headless `target/seon` instances (`--port 0 --headless --seed-store runs/store`), each cloning LMDB via APFS CoW in `< 10 ms`, routing all LLM calls through one shared `seon.bench.proxy` (concurrency cap, streaming token/KV-cache accounting, hard token cap).

- **Live UI Monitoring (`user/start-bench-run!`, `:seon.bench.run/*`):** Launches `start-bench-run-async!` in `< 8 ms` and streams throttled trial snapshots over Datastar SSE to `#cv-bench-runs` above Living Canvas on `http://127.0.0.1:9998/`.
- **Strict Merge Accounting (`:require-merge? true`):** Any trial configured with `:task` defaults to `:require-merge? true`, ensuring a worker halted by proxy HTTP 429 (`Pause on model error` -> `(user/wait-for-mail!)` -> `:idle`) is NEVER scored `:passed`.
- **Cold-Boot Parity:** Seeded instances automatically run `seon.main/sync-cold-boot-forms!` to overlay both `src/` and `test/` forms (+ `:seon.code.form/deps`) modified after `aot-build-ms`, eliminating AOT/store split-brain.

### No-Rigging Discipline (Owner Ruling, 2026-11-20)

Internal benchmark tasks MUST go through the exact normal `:seon.task/*` workflow:
- Create/reference real `:seon.task/*` entities (`:op :create-task` or existing `:main` tasks in `resources/seon/tasks.edn`) with rough 1–2 sentence descriptions and `:scope`.
- Never spoon-feed exact recipes, Malli schemas, Datahike attribute names, or test file paths in benchmark prompts.
- Every optimization must be a general improvement to Seon's context tiles (`ns-tile`, `ctx-tile`), `user/*` helpers, `enrich-eval-error` hints, or runtime behavior — and logged in `:seon.fix/*`.

```clojure
;; Launch an un-rigged 3-task parallel run from :agent/root REPL:
(user/start-bench-run!
 {:title "Un-Rigged Real-Task Run (deepseek-flash)"
  :repo-dir "/Users/sean/src/seon-next"
  :bin-path "/Users/sean/src/seon-next/target/seon"
  :seed-store "/Users/sean/src/seon-next/runs/store"
  :model "deepseek-flash"
  :proxy? true
  :proxy-opts {:max-in-flight 3 :max-tokens 500000}
  :task-timeout-ms 180000
  :tasks [{:id :task-split-md :task :seon.render.markdown/split-activity-fold :scope 'seon.render.markdown :run-tests? true :merge? true}
          {:id :task-eval-summary :task :seon.health.metrics/eval-summary-by-agent :scope 'seon.health.metrics :run-tests? true :merge? true}
          {:id :task-bench-rollup :task :seon.render.chronicle/bench-runs-rollup :scope 'seon.render.chronicle :run-tests? true :merge? true}]})
```

## 2. External Benchmarks — Harbor Host-Bridge Mode (`bench/harbor/seon_agent.py`, `seon.fork.container`)

External polyglot repositories (Python, C, Git, ELF, SQLite, shell tasks in Terminal-Bench 2.1 / SWE-bench) are NEVER imported into Datahike. Instead, Seon runs natively on macOS `arm64` via **Host-Bridge Mode**:

1. `bench/harbor/seon_agent.py` (`SeonAgent`) starts a local HTTP bridge (`_ContainerBridgeServer`) proxying container shell & file ops (`docker exec`) to a host-side headless `target/seon` instance with `SEON_EXEC_BRIDGE_URL` set.
2. `seon.fork.container/install-container-helpers!` exposes `(user/sh! cmd opts)`, `(user/fs-read path opts)`, `(user/fs-write path content)`, and `(user/fs-patch path old new opts)` inside the agent's Clojure REPL, while `seon.render.entities.work/render-ctx-snapshot` switches to `:mode :container` guidance.
3. **Integrity & Audit:** `:mode :container` enforces strict local-only solving (never fetch external benchmark repos/tests), `seon_agent.py` captures read-only `branch-as-db` telemetry plus chronological `tx`-ordered `eval_trace` with per-eval bytes into `seon-result.json`, and streams live progress to `http://127.0.0.1:9998/` (`SEON_MONITOR_URL`).

## 3. Verified Baselines (`[M]`, `deepseek-flash`, Nov 2026)

- **Official Terminal-Bench 2.1 (Harbor `0.26.0`, Host-Bridge Mode):** **`7 / 9` genuine `1.0` passes (`77.8%`)** across `tb21-file-recovery` and `tb21-code-debug` (`multi-source-data-merger`, `log-summary-date-ranges`, `regex-log`, `db-wal-recovery`, `fix-git`, `git-leak-recovery`, `polyglot-c-py`; plus `fix-log-analyzer` smoke `1.0`), `87–96%` KV-cache hit rates, `0` exceptions, `0` integrity issues across all 4 post-rule code-debug tasks.
- **Internal Suite A (Before -> After Malli upfront validation + cold-boot overlay):** `2/3` @ `22.7` mean evals (`643.5k` tok) -> **`3/3` auto-merged @ `10.3` mean evals (`197.3k` tok, `-54.6%` evals, `-69.3%` tokens, `88.3%` KV cache)**.
- **Un-Rigged Real-Task Rounds 1 -> 5 (`:seon.task/*`, `deepseek-flash`, `:run-tests? true :merge? true`):**
  - **Round 1 (`5760d375` in backup store):** `0/3` passed (cold-boot test split-brain `3/3` failed; `17/15/14` evals).
  - **Round 2 (`37ff85c2` in backup store, `300k` cap, pre-`:require-merge?`):** `0/3` truly merged (`merged? false` on all 3 due to `rejected 3` proxy 429 cutoff; `11/22/14` evals, `86.3%` KV cache).
  - **Round 3 (`8b62ccdf`, 38 starter logic rules, `600k` cap, `:require-merge? true`):** `3/3` merged, `1/3` fully healthy & green (`:task-bench-rollup` 20 evals passed; `:task-eval-summary` 23 evals hit `:minified-form`; `:task-split-md` 14 evals hit `add-fn!` `:used-before-defined`; `86.9%` KV cache).
  - **Round 4 (`f8c69c74`, 43 logic rules + schema-renderer & scope-schema-consumer expansion + `schema-tile` `[unique]`/`[indexed]` + `ns-tile` `Test ns requires:` + `add-fn!` auto-format & topo-reorder, `:require-merge? true`):** **`3 / 3` GENUINE PASS (`100%` `:merged? true`, `100%` `:health-ok? true`, `0` test failures, `89.0%` KV cache `777.2k / 872.9k`):** `:task-bench-rollup` dropped `20 -> 13` evals (`49.9s`, `-35%`), `:task-eval-summary` `23` evals (`76.7s`, `0` health errors), `:task-split-md` `26` evals (`154.0s`, `0` test errors; `20.7` mean evals, `154.0s` wall clock).
  - **Round 5 (`914b2369` on `graph-refactor-v2`, 58 logic rules + data-driven fn discovery + 4-stage recursive rule-driven printer + `entity-index` + unified `:seon.error/*` + `4.6x` health perf + per-tile `step-walk` + singular co-location `seon.rule*`/`seon.agent`/`seon.test`, `:require-merge? true`):** **`3 / 3` GENUINE PASS (`100%` `:merged? true`, `100%` `:health-ok? true`, `0` proxy errors/rejections, `90.65%` KV cache `814.5k / 898.5k`):** `:task-split-md` `26 -> 18` evals (`110.7s`, `-30.8%`), `:task-eval-summary` `23 -> 16` evals (`68.5s`, `-30.4%`), `:task-bench-rollup` `19` evals (`74.0s`) — **`17.7` mean evals (`-14.5%` vs Round 4) and `110.9s` wall clock (`-28.0%`)**.

  - **Round 6 (1/1/1/1 Unified Pipeline — 22 native `:seon.rule/query` rules, `-31.8k` tokens deleted, live `datahike.reads` `:eval/touched` + EmbeddingGemma-2 Metal vectors, live `:main` merges):** **`3 / 3` GENUINE PASS & MERGED (`100%` tests PASS, `0` health errors, `90.4%` KV cache `752.1k / 828.3k`, `1` prefix hash per session):**
    - **Minimal-Context Baseline A/B (`:seon.health.metrics/eval-summary-by-agent`):** minimal context (`:minimal? true`, `2,180` tok T1) **FAILED** at `32/32` turns (`5` errors, `320,750` prompt tok, `121` touched EIDs) vs. **Unified 1/1/1/1 Full Pipeline** (`5,852` tok T1) **PASSED & MERGED in `13` turns** (`-18.8%` vs Round 5, **`2.46×` fewer turns**, **`-45.9%` total prompt tokens** `173,562`, `1` error, `89.7%` KV cache, `64` touched EIDs, `520/520` PASS).
    - **1-Click Entity Spawn (`:op :spawn-on-entity` on `seon.render.markdown/render-activity-fold`):** **PASSED & MERGED in `15` turns** (`19` evals, `88.5%` KV cache, `519/519` PASS, `:large-function` eliminated).
    - **`:seon.render.chronicle/bench-runs-rollup`:** **PASSED & MERGED in `20` turns** (`92.7%` KV cache, `521/521` PASS incl. automatic `102-sample seed=42` generative proof).
    - **Hierarchical `:ns/*` Delegation (`:agent/coord-swarm-e2e` -> `:ns/seon.render` + `:ns/seon.vector`):** both parallel calls `:resolved` in `3` coordinator turns.
