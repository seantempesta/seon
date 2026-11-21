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
  :repo-dir "/Users/sean/src/seon-next/.worktrees/rebuild"
  :bin-path "/Users/sean/src/seon-next/.worktrees/rebuild/target/seon"
  :seed-store "/Users/sean/src/seon-next/.worktrees/rebuild/runs/store"
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
- **Un-Rigged Real-Task Rounds 1 -> 2 (`:seon.task/*`):** Cold-boot test split-brain `3/3 failed -> 0/3`; `:task-split-md` dropped `17 -> 11` evals (`0` errors) after `ns-tile` added `Requires:` aliases + `Private helpers:`. Re-run with `500k` proxy cap is the next baseline measurement.
