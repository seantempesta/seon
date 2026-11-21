# Seon Benchmark & Harbor Integration

Two clean layers — **zero forked benchmark runners, zero external project code in Datahike**. See [docs/bench.md](../../docs/bench.md) for architecture, no-rigging discipline, and measured baselines.

## 1. Internal Parallel Isolated Trials (`seon.bench.cluster`)

Launch `N` isolated headless `target/seon` instances (`--port 0 --headless --seed-store`) behind one shared `seon.bench.proxy` and stream live progress to `http://127.0.0.1:9998/` (`#cv-bench-runs`):

```clojure
(user/start-bench-run!
 {:title "Un-Rigged Real-Task Run (deepseek-flash)"
  :repo-dir "/Users/sean/src/seon-next/.worktrees/rebuild"
  :bin-path "/Users/sean/src/seon-next/.worktrees/rebuild/target/seon"
  :seed-store "/Users/sean/src/seon-next/.worktrees/rebuild/runs/store"
  :model "deepseek-flash"
  :proxy? true
  :proxy-opts {:max-in-flight 3 :max-tokens 500000}
  :task-timeout-ms 180000
  :tasks [{:id :task-split-md
           :task :seon.render.markdown/split-activity-fold
           :scope 'seon.render.markdown
           :run-tests? true :merge? true}]})
```

## 2. Official Harbor Benchmarks (`Host-Bridge Mode`)

`bench/harbor/seon_agent.py` implements Harbor's `BaseAgent` via `_ContainerBridgeServer`: Seon runs natively on macOS `arm64` while the agent operates inside the official Linux Docker container using `(user/sh!)`, `(user/fs-read)`, `(user/fs-write)`, and `(user/fs-patch)` (`seon.fork.container`). Zero Linux cross-compile required, and zero external repo files enter Datahike.

```bash
export DEEPSEEK_API_KEY=...
export SEON_HARBOR_BIN=/Users/sean/src/seon-next/.worktrees/rebuild/target/seon
export SEON_HARBOR_REPO=/Users/sean/src/seon-next/.worktrees/rebuild
export SEON_HARBOR_SEED_STORE=/Users/sean/src/seon-next/.worktrees/rebuild/runs/store
export SEON_MONITOR_URL=http://127.0.0.1:9998
export SEON_MODEL=deepseek-flash

PYTHONPATH=/Users/sean/src/seon-next/.worktrees/rebuild harbor run \
  --path /tmp/tb21-code-debug \
  --task-name fix-git --task-name git-leak-recovery \
  --agent-import-path bench.harbor.seon_agent:SeonAgent \
  -m deepseek-flash -n 2 -o /tmp/seon-harbor-jobs
```

### Local Smoke Test (no Docker required)

```bash
python3 bench/harbor/seon_agent.py --drive \
  --bin ./target/seon --repo . --seed-store ./runs/store --model-stub \
  --instruction "Verify (+ 20 22) in the REPL" \
  --out /tmp/seon-harbor-smoke.json
```
