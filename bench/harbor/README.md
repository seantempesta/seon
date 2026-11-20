# Seon Benchmark & Harbor Integration

Two clean layers — **zero forked benchmark runners, zero manual dataset parsing**:

## 1. Internal Parallel A/B Trials (in-process Seon)

Use `seon.bench.cluster/run-parallel-trial!` (backed by `seon.bench.proxy` for shared concurrency + token budgets) to spawn N completely isolated headless `target/seon` binaries on ephemeral ports (`--port 0`), each with its own temp LMDB store cloned from `--seed-store`:

```clojure
(require '[seon.bench.cluster :as cluster])

(cluster/run-parallel-trial!
  {:repo-dir (System/getProperty "user.dir")
   :seed-store "runs/store"
   :proxy? true
   :proxy-opts {:max-in-flight 4 :max-tokens 200000}
   :tasks [{:id :trial-a :branch :branch/a :eval-code "(+ 20 22)" :run-tests? true}
           {:id :trial-b :branch :branch/b :eval-code "(* 6 7)" :run-tests? true}]})
```

## 2. Official Harbor Benchmarks (`terminal-bench@2.0`, `swebench-verified`, ...)

`bench/harbor/seon_agent.py` implements Harbor's `BaseAgent` interface AND doubles as a zero-dependency Python stdlib container driver:

```bash
# Point at a Linux-compiled target/seon + target/lib bundle for Docker containers:
export SEON_HARBOR_BUNDLE=/path/to/linux-seon-bundle
export DEEPSEEK_API_KEY=...

harbor run -d terminal-bench@2.0 \
  --agent-import-path bench.harbor.seon_agent:SeonAgent \
  -m deepseek-chat
```

### Local Smoke Test (no Docker or Harbor install required)

```bash
python3 bench/harbor/seon_agent.py --drive \
  --bin ./target/seon --repo . --seed-store ./runs/store --model-stub \
  --instruction "Verify (+ 20 22) in the REPL" \
  --out /tmp/seon-harbor-smoke.json
```
