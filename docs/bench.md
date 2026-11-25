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
- Every optimization must be a general improvement to Seon's context rendering, `user/*` helpers, `enrich-eval-error` hints, or runtime behavior — and logged in `:seon.fix/*`.

## 2. External Benchmarks — Harbor Host-Bridge Mode (`bench/harbor/seon_agent.py`, `seon.fork.container`)

External polyglot repositories (Python, C, Git, ELF, SQLite, shell tasks in Terminal-Bench 2.1 / SWE-bench) are NEVER imported into Datahike. Instead, Seon runs natively on macOS `arm64` via **Host-Bridge Mode**:

1. `bench/harbor/seon_agent.py` (`SeonAgent`) starts a local HTTP bridge (`_ContainerBridgeServer`) proxying container shell & file ops (`docker exec`) to a host-side headless `target/seon` instance with `SEON_EXEC_BRIDGE_URL` set.
2. `seon.fork.container/install-container-helpers!` exposes `(user/sh! cmd opts)`, `(user/fs-read path opts)`, `(user/fs-write path content)`, and `(user/fs-patch path old new opts)` inside the agent's Clojure REPL, while `seon.render.entities.work/render-ctx-snapshot` switches to `:mode :container` guidance.
3. **Integrity & Audit:** `:mode :container` enforces strict local-only solving (never fetch external benchmark repos/tests), `seon_agent.py` captures read-only `branch-as-db` telemetry plus chronological `tx`-ordered `eval_trace` with per-eval bytes into `seon-result.json`, and streams live progress to `http://127.0.0.1:9998/` (`SEON_MONITOR_URL`).

## 3. Verified Baselines (`[M]`, `deepseek-flash`, Nov 2026)

- **Official Terminal-Bench 2.1 (Harbor `0.26.0`, Host-Bridge Mode):** **`7 / 9` genuine `1.0` passes (`77.8%`)** across `tb21-file-recovery` and `tb21-code-debug`, `87–96%` KV-cache hit rates, `0` exceptions, `0` integrity issues.
- **Internal Suite A (Before -> After Malli upfront validation + cold-boot overlay):** `2/3` @ `22.7` mean evals (`643.5k` tok) -> **`3/3` auto-merged @ `10.3` mean evals (`197.3k` tok, `-54.6%` evals, `-69.3%` tokens, `88.3%` KV cache)**.
- **Un-Rigged Real-Task Rounds 1 -> 5 (`:seon.task/*`, `deepseek-flash`, `:run-tests? true :merge? true`):**
  - **Round 1–2:** `0/3` genuinely merged (cold-boot split-brain / proxy 429 cutoff).
  - **Round 3 (`8b62ccdf`):** `3/3` merged, `1/3` fully healthy & green (`86.9%` KV cache).
  - **Round 4 (`f8c69c74`):** **`3 / 3` GENUINE PASS** (`20.7` mean evals, `154.0s` wall clock, `89.0%` KV cache).
  - **Round 5 (`914b2369`):** **`3 / 3` GENUINE PASS** (`17.7` mean evals `-14.5%`, `110.9s` wall clock `-28.0%`, `90.65%` KV cache).
- **Round 6 (1/1/1/1 Unified Pipeline — native `:seon.rule/query` rules, live `:eval/touched` + EmbeddingGemma-2 vectors):** **`3 / 3` GENUINE PASS & MERGED (`90.4%` KV cache, `1` prefix hash per session):** minimal-context A/B baseline FAILED at `32/32` turns vs full pipeline **PASSED & MERGED in `13` turns** (`2.46×` fewer turns, `-45.9%` prompt tok).
- **Round 7 (Compounding Autonomous DeepSeek Waves 1–4 on Live `:main` — O(1) `equiv-db`, Flat-AEVT Reach, LPT Sharding, Wave D1–D3 + Wave E0 Trace Fixes + Cold `health/report < 256 MB`):**
  - **Wave 1 (Post-Wave-C, 3 `:large-function` tasks):** `2/3` immediate auto-merge (`shrink-health-metrics` `16` turns / `18` evals / `91.9%` KV; `shrink-merge-autofix` `22` turns / `0` errs / `91.4%` KV; `shrink-bench-cluster` green & merged after Wave D3 `^:seon.flow/edge` auto-proof fix, `96.2%` KV; `0` OOMs).
  - **Wave 2 (Post-Wave-D1/D2/D3 + 249 mined schema examples):** **`3 / 3` AUTO-MERGED (`100%`, `0` OOMs, `1` prefix-hash each, `93.8%` mean KV cache):** `shrink-agents-proc` **`19` turns** (`1.68` evals/turn, `93.7%` KV), `shrink-server-proc` `32` turns (`94.0%` KV), `shrink-move-vars-tx` `33` turns (`93.8%` KV, saved by Wave D3 +1 wrap-up grace turn).
  - **Wave 3 (Post-Wave-E0 trace fixes — Audit Wave E1 multi-ns unification tasks):** **`3 / 3` AUTO-MERGED (`100%`, `1` prefix-hash each, `92.9%` mean KV cache):** `store-cache-keys` (`33` turns, `1.79` evals/turn, `6` files refactored), `health-oom-and-cleanup` (`31` turns), `context-cache-and-chars` (`lean-wire-item` + bounded `walk-prefix-cache`).
  - **Wave 4 (Fresh AOT binary + Cold `health/report` `246.3 MB < 256 MB` + 1-focused-goal tasks):** **`3 / 3` AUTO-MERGED (`100%`, `0` OOMs across all `124` evals, `1` prefix-hash each, `91.5%` mean KV cache):** `shrink-code-monoliths` (**`13` turns** / `17` evals splitting 2 monoliths — new record!), `unify-hiccup-to-text` (`30` turns, created zero-dep `seon.render.text/hiccup->text` + added `9` missing contracts), `shrink-test-proc` (`31` turns, `2.16` parallel evals/turn splitting `328L` monolith).
  - **Cumulative Round 7 Impact:** **`9 / 9` consecutive auto-merges across Waves 2–4 (`100%`)**, sliding-window `:learning-metrics` merge rate **`70% -> 90%+`**, KV cache **`88% -> 92%`**, `:large-function` count cut **`15 -> 5` (`-67%`)**, cold `seon.health/report` **`269.7 MB OOM -> 246.3 MB / 270 ms`**, `/op :status` **`2,607 ms -> 112 ms` (`23.2×`)**, `diff` **`11,900 ms -> 128 ms` (`92.8×`)**.
4. Native Paired A/B Experiments (`user/run-ab!`, `seon.bench.golden`, Nov 2026)
26)

`(user/run-ab! opts)` runs paired isolated A/B experiments natively inside Seon (`seon.bench.golden/build-ab-tasks`, `summarize-ab`, `seon.bench.helpers/trial-audit-summary`), streaming live to `#cv-bench-runs`, persisting `:seon.bench.run/*` + compact `:eval-trace` to Datahike `:main`, enforcing strict 0-edit merge rejection (`:touched-paths` non-empty; no fallback merge on halted `:auto-merge? true` workers), and rolling up multi-agent Refiner + Worker turns/tokens honestly.

- **Round 1 -> Round 2 -> Round 3B Paired A/B (`auto-refine-tasks? false` Control vs `true` Treatment, `deepseek-flash`, `max-tool-turns = 24`, 5 unseen/golden tasks = 10 isolated trials `[M]`):**
  - **Platform fixes validated:** Eliminating `:refine/*` 3-way merge conflicts (`strip-task-ops`), anchoring post-refinement KV prefixes (`:fork-review` in `branch-reset-tx`, forked-agent `anchor-cid` & Turn-1/2 `walk-main` stability), teaching standard Clojure + `user/` verbs, fixing 0-edit false passes, and adding the ~15-token ephemeral `<session-budget>` cockpit gauge raised Control merges from `0/2 (0%)` -> **`4/5 (80%)`** (`86.4%` mean KV hit, `0` OOMs, `0` prefix busts) and shifted hard `:golden/v1` agents from `75%` read-only stalls (Round 3A) to `100%` writing/testing code (Round 3B).
  - **Honest A/B verdict on cold 2-agent auto-refinement (`[M]`):** Control (`4/5 = 80%` merged, `86.4%` KV) decisively beat automatic cold 2-agent Refiner->Worker splitting (`2/5 = 40%` merged, `74.4%` KV, `~2x` prompt tokens; Refiners merged `1/4 = 25%` measured runs; even on `H2` where Refiner succeeded, cold handoff cost `20t / 383k tok / 217s` vs Control `13t / 158k tok / 93s`).
  - **Standing policy & next frontiers:** Keep `:seon.config/auto-refine-tasks?` `false` by default (opt-in when an explicit proof/spec checkpoint is desired). Next two high-leverage targets discovered from persisted `:eval-trace`s: (1) auto-seed `(ns ...)` headers on native `(in-ns 'new-test) (deftest ...)` (tripped `:missing-ns-form` on 3/4 Round-3B runs), and (2) test **Warm-Continuation Refinement** (sharing Refiner's warm KV prefix instead of waking a cold Worker).

- **Round 4A -> Round 4B -> Round 5/5B/5C & Pause-and-Fork Supervisor Lab (Nov 25, 2026, `deepseek-flash`, `[M]`):**
  - **Round 4A (8 tasks, pre-Refiner vs Control):** Control `6/8 (75%)` beat pre-Refiners `2/8 (25%)` at `3.3x` fewer tokens -> retired pre-Refiner arm in favor of the **Pause-and-Fork Supervisor Lab** (`seon.bench.lab/run-supervisor-cycle!`, `:template/tune-context`).
  - **Data-derived automatic completion & Turn-0 recall:** Retired manual `(user/ready!)`/`(user/finish-review!)` via pure Datahike `implicit-ready?` (`seon.reconciler.policy`, `seon.ctx.diff/branch-latest-edit-info`), upgraded Turn-0 scoped recall (`O(k log N)` indexed `:avet` schema + scope vars + 1-hop neighborhood + sibling `-test` ns, lifting `:golden/v1-test-run-renderers` from `212 tok / 2 tiles` to `3,305–7,175 tok / 13 tiles`), bounded `@user/ctx` (`commit-delta-datoms` + `:semantic? false`, cutting deref `70.9 MB -> 32.6 MB`), and moved `:rule.ctx/active-tasks` & `:rule.ctx/scope-namespaces` to zero-planner indexed `seon.ctx.ground` rows (cutting cold 12-scenario `corpus-report` `124.8 MB -> 66.8 MB`).
  - **Round 5 (`c6cf7159`, 3 hard 0/3 R4B retries + 3 unseen `seed=101` random tasks, `86.0%` KV, `0` proxy errors, `0` rogue FS):** Flipped `:golden/v1-test-run-renderers` FAILED -> **MERGED (`23t`, `142.6s`)** and merged `2/3` unseen `seed=101` tasks first try (`:rand/render-seon-error` `16t`, `:rand/audit-seon-web-css` `23t`).
  - **Tool & test root fixes validated in Round 5C (`725e294a`) + Async Supervisor Cycle 4 (`acd6342a`):** Fixing CWD-relative `(slurp ...)` in `alloc_guard_test` & `health_liveness_and_closure_test`, caller `:timeout-ms` precedence in `build-ab-tasks`, 50ms CPU-contention flake in `stress_test`, in-place REPL `deftype`/`defrecord` replacement, `/op :timeout-ms`, `:explore-scope`, `user/bench-run`, and automatic `:error-samples`/`:failing-tests` flipped unseen `:rand/contract-unit-account` to **MERGED (`19t`, `86.4%` KV -> `3/3 = 100%` on unseen `seed=101`!)**, and ran Async Supervisor Cycle 4 (`:rand/contract-build-explained-item`, unseen `seed=102`) cleanly to **MERGED (`14t`, `295.8s`, `:health-ok? true`)**.
