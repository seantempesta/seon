---
type: research
status: active
tags: [research, agent]
---

# Benchmark scenario sourcing for verified-trajectory training data

Owner direction 2026-07-12: source the repl-autosuggest scenario/training
corpus from the serious agentic benchmarks vendored in `reference-code/` —
"updating code using tools", terminal work, computer use. The benches carry
TEST ORACLES, so an optimal solution is *verifiable*; a verified session
yields gold (context, forms) pairs (each turn of a passing run = one known-good
pair), and a benchmark's check maps onto `my.plan/::expect`. This file is the
sourcing plan only — nothing is wired.

## TL;DR (owner reads this to pick the first bench)

- **Wire first: `aider-polyglot`, then `terminal-bench`.** Both are the right
  shape (task text + hidden test oracle), both drive through seon's EXISTING
  toolkit (`seon.agent.shell/run` + the `seon.agent.fs` edit surface), and both
  already have adapter precedent in `src-inspect-ai/` (the tb adapter exists;
  aider-polyglot is plain files, the lightest harness in the whole set).
- **The uncomfortable truth up front (risk #1):** every serious agentic bench
  in `reference-code/` edits **Python / Go / Rust / JS repos**, NOT Clojure. So
  a verified trajectory's *forms* are shell commands and file edits, NOT
  `db/transact!` / `schema/register!` / `my.plan` forms. These benches directly
  feed **only two of v0's form kinds** — shell-call forms and edit-protocol
  forms — plus **`my.plan` deltas** (any multi-step task exercises plan
  bookkeeping) and **`my.kb` recording** (findings → `register!`/`transact!`).
  They do **NOT** organically produce the copy-heavy `db/transact!` /
  `schema/register!` data-modeling forms the v0 MODEL contract is built on
  (design §Target granularity). Those still come from the **synthetic/gold**
  pipeline (KT0-inverted recipe). The benches are the source of *terminal +
  edit + planning* trajectories — a real, valuable, but PARTIAL slice of v0.
- **Two distinct uses, don't conflate them:** (a) *held-out eval / signal
  ceiling* — run a frontier agent through the bench with capture ON, the
  bench's own oracle certifies the session, mined turns become held-out
  (profile, forms) eval pairs. This is pure upside and cheap. (b) *training
  data* — only the CERTIFIED-PASS turns become training pairs; unproven turns
  are diagnosed, not trained. Use (a) immediately; gate (b) on KT2.5
  ingredients-coverage per the design.
- **inspect-evals is the integration path** and it is already consumed by
  `src-inspect-ai`: `swe_bench`, `agent_bench`, `gaia`, `osworld`, `cybench`,
  `mind2web`, `swe_lancer`, `tau2`, `bfcl`, `vimgolf_challenges`,
  `theagentcompany`, `mle_bench` are packaged tasks under
  `reference-code/inspect-evals/src/inspect_evals/`. seon's `catalog.py`
  already wires `swe_bench_verified` (A-overlay arm) and `bfcl_ast`. A new bench
  = a new `BenchSpec` row + a solver, INSIDE `src-inspect-ai` — the three-
  surfaces rule holds; no bespoke driver.
- **Pair-yield estimate (order-of-magnitude):** aider-polyglot ≈ 225 tasks ×
  ~4 turns × ~2 forms/turn ≈ **~1,800 pairs** (shell+edit+plan, all Python/Go/
  Rust/etc.); terminal-bench ≈ 241 tasks × ~5 turns × ~2 ≈ **~2,400 pairs**.
  Both are dominated by shell/edit/plan forms, NOT register!/transact!.

## 1. Inventory of vendored agentic benches

All paths under `reference-code/`. "Oracle" = the automatic pass/fail check
(the thing that maps to `::expect`). Harness weight is the runtime seon would
have to stand up.

| Bench | Tasks (vendored) | Task def + oracle location | Harness weight | License |
|---|---|---|---|---|
| **terminal-bench** | **241** (`original-tasks/`); registry `terminal-bench-core` head/0.1.0/0.1.1 | per-task dir: `task.yaml` (instruction), `tests/test_outputs.py` (pytest oracle), `run-tests.sh`, `solution.sh` (**233 have oracle solution scripts**, 8 `solution.yaml`) | **Docker per task** (`Dockerfile`+`docker-compose.yaml`); pytest in tmux; `parser_name: pytest`, all-PASSED resolution | Apache-2.0 |
| **aider-polyglot** | **225 total** (py 34, go 39, java 47, js 49, cpp 26, rust 30) | `exercises/practice/<name>/`: `.docs/instructions.md` (task), `<name>_test.*` (hidden test oracle), stub `<name>.<ext>`, `.meta/example.*` (reference solution) | **Plain files** — no docker; run the language's test runner. LIGHTEST harness in the set | (Exercism-derived; per-exercise, no repo LICENSE file — verify per-track) |
| **swe-bench** (harness) | dataset via HF (Verified = 500) | `swebench/` harness; inspect-evals `swe_bench` scorer runs the repo's test patch | **Docker per instance** (per-repo images, amd64) | MIT |
| **swelancer** | **0 vendored** — README is a pointer only (moved to `openai/preparedness`) | n/a here | n/a | (no LICENSE vendored) |
| **commit0** | 57 core libraries (via `commit0` pip cli; `test_ids/`, `configs/`, `data/`) | build-a-library-from-scratch; each lib has full pytest suites + lint/typecheck | **Docker/isolated per repo**; heavy (build whole libs) | MIT |
| **osworld** | **369** (`evaluation_examples/test_all.json`: chrome 46, calc 47, impress 47, multi_apps 101, os 24, writer 23, gimp 26, vlc 17, vs_code 23, thunderbird 15); `test_small.json` = 39 | per-example JSON: `instruction`, `config` (VM setup), `evaluator` (postconfig + `execute` checks) | **Full desktop VM** (Ubuntu snapshot, `pyautogui`, GUI); heaviest | Apache-2.0 |
| **webarena** | **812** (`config_files/test.raw.json`) | per-task JSON: `intent`, `start_url`, `eval` (`string_match`/`program_html`/`url_match` + `reference_answers`) | **Docker web stack** (shopping/gitlab/reddit/wiki servers) | Apache-2.0 |
| **browsergym** | wraps miniwob / webarena / visualwebarena / assistantbench / weblite | `browsergym/<env>/` per-env task registration; oracle is the wrapped env's | **Playwright browser + wrapped env's backend** | Apache-2.0 |
| **online-mind2web** | **300** tasks / 136 live websites (`data/schema_v2/`) | task JSON + `WebJudge` LLM-based auto-eval (no hard oracle — live web) | **Live internet + browser**; eval is LLM-judge, not a deterministic oracle | MIT |
| **agentbench** | 8 environments (`data/`: os_interaction, dbbench, knowledgegraph, alfworld, mind2web, avalon, 2×lateralthinking) | per-env JSONL/JSON (e.g. `os_interaction/data/`, `dbbench/standard.jsonl`) | Docker per env; **os_interaction** + **dbbench** are shell/SQL (light-ish) | Apache-2.0 |
| **tau2-bench** | **2,546** across domains (airline 50, retail 114, telecom 2285, banking_knowledge 97, mock 10) | `data/tau2/domains/<d>/tasks.json`; oracle = simulated-user tool-call conversation + DB-state assertion | Python domain sim (no docker); tool/API dialog, not code edits | MIT |
| **cybench** | **40 tasks / 40 subtasks** (`task_list.txt`; hackthebox/sekai/hkcert/bugbounty) | per-task metadata + `grade_benchmark.py`; CTF flag-match oracle | Docker (Kali-style tooling) per task | Apache-2.0 |
| **mle-bench** | **84 Kaggle competitions** (`mlebench/competitions/`) | per-competition grader (`grade.py`, Kaggle metric vs leaderboard threshold) | Docker + large datasets (GB); training runs; very heavy | MIT |
| **re-bench** | **7 AI-R&D tasks** (`ai_rd_*`) | per-task `manifest.yaml` + scorer + `official_solution.zip` | Docker + GPU; research-scale; heaviest per-task | MIT |
| **gorilla-bfcl** | thousands (`bfcl_eval/data/BFCL_v4_*.json`: simple/parallel/multiple/multi_turn/live/memory/web_search) | per-category JSON + `possible_answer/`; **AST-match oracle** (single-turn) | **Plain files for AST subset** (no exec/sandbox); light | Apache-2.0 |

Notes that bite: **swelancer is not actually vendored** (README pointer only) —
if we want it, it comes via `inspect_evals.swe_lancer`, not this dir.
**online-mind2web has no deterministic oracle** (WebJudge is an LLM), so it
fails the owner's "verifiable optimal solution" premise — exclude from the
training pipeline. **osworld / webarena / browsergym / mle-bench / re-bench /
cybench** are all real oracles but heavy environments (VM / browser / GPU /
CTF) that seon has no GUI or browser tool surface for today.

## 2. inspect-evals packaging + how src-inspect-ai consumes it

Packaged task modules exist under
`reference-code/inspect-evals/src/inspect_evals/` for:

- **`swe_bench`** — dataset + official scorer (`scorers.py`); **already wired**
  in seon as `swe_bench_verified` (A-overlay arm, `catalog.py:109`,
  `swebench_arm.py`).
- **`agent_bench`** — `agent_bench.py` + `agent_bench_os_dataset.py` +
  `agent_bench_os_scorer.py` (the OS-interaction shell subset; has a Dockerfile
  template).
- **`gaia`** — `gaia.py` + `scorer.py` + `compose.yaml` (web-tool tasks).
- **`osworld`** — `osworld.py` + `dataset.py` + `scorer.py` + `container/`.
- **`cybench`** — `cybench.py` + `challenges/` + `agent_sandbox/`.
- **`mind2web`** / **`mind2web_sc`** — dataset + prompts (web action prediction).
- **`swe_lancer`** — `dataset.py` + `debugging.py` + `prompts.py`.
- **`tau2`** — full `tau2.py` + per-domain dirs (airline/banking/retail/telecom).
- **`bfcl`** — `bfcl.py` + `data.py` + backends; **already wired** as
  `bfcl_ast` (`catalog.py:102`, `bfcl_adapter.py`, pinned to the pure-AST
  python subset).
- **`vimgolf_challenges`** — editing-keystroke task + verifier (interesting: a
  pure-text edit oracle, no repo).
- **`theagentcompany`** — multi-step workplace tasks + `scoring.py`.
- **`mle_bench`** — `mle_bench.py` (wraps the mle-bench grader).

There is **NO packaged `terminal_bench` / `aider_polyglot` inspect module.**
Terminal-bench runs on its OWN harness (or Harbor/TB-2). seon already handles
this: `tb_agent.py` (tb 0.2.18 custom-agent hook) and `tb2_agent.py` (Harbor
`BaseAgent`) inject the seon pod as the agent and let tb run its own oracle.
Aider-polyglot has no harness at all — it is just files + a test runner, which
is precisely why it is the cheapest to drive.

**How src-inspect-ai consumes inspect tasks today** (`catalog.py`,
`bench_common.py`):

- Each bench is ONE `BenchSpec(module, task_fn, kind=..., adapter=...,
  default_task_kwargs=...)` row in `BENCHES`. `case1` benches run through
  `run_bench`, which takes the real `inspect_evals` Task (its dataset + its
  host-side scorer) and **substitutes seon's pod solver** for the task's own
  `generate()` — verified against `inspect-ai` source (`_eval/eval.py:121`,
  the `solver=` override keeps dataset+scorer, swaps the agent).
- Two run modes: **static URL** (drive one long-lived cluster's pod, e.g.
  acme via `SEON_CLUSTER_URL`) or **`per_sample_cluster=True`** (mint one
  ephemeral cluster per sample: `bin/seon cluster create <name> --ephemeral
  --frozen` → drive → destroy; `cluster.py`). Ephemeral clusters run the FROZEN
  bench bundle (`out-bench/client/main.js`, sha-stamped into EvalLog metadata;
  raises `FrozenBundleChanged` if it moves).
- Code targets score through the REAL seon oracles behind a fail-loud
  **oracle-liveness gate** (`oracle_scorers.py`: bb parse/structural + node
  cljs.js eval; a golden good must pass and a golden bad must fail before any
  task constructs).
- Splits/holdout are governed by `freeze.py` + `evals/datasets.lock` (seeded
  dev/milestone/blind-test tiers, one **canary GUID** per test split, CI guard
  `test_canary_guard.py` scans `src/docs/config/…` so held-out data can never
  leak into agent context). **This is exactly the machinery the design's
  "224 mined turns = held-out, never training" rule needs** — reuse it, don't
  reinvent a split scheme.

Integration path for a NEW bench is therefore: add a `BenchSpec` row (for an
inspect-packaged one) OR a thin custom-agent adapter (for tb/aider) + a
generator/exporter that mints (profile, forms) pairs from the captured turns.
No new top-level harness.

## 3. Seon toolkit coverage per bench

What seon agents HAVE today (verified in source):

- **`seon.agent.shell/run`** (`src/seon/agent/shell.cljs:252`) — argv in (never
  a shell string), `{::ok? ::exit ::out ::err ::timed-out?}` out, as data;
  default-deny until host grants `SEON_SHELL`; `py-run`, `run-bg!` too. This is
  a full terminal surface.
- **`seon.agent.fs`** (`fs.cljs`) — `read-file`, `write-file`, `edit-file`,
  `list-dir`, `walk-dir`, `stat`, `file-exists?`, `home-dir`, plus the
  **anchored edit protocol**: `replace!` / `insert!` / `view` with `::file-sha`
  staleness fences (edit-protocol-spec.md A2/A3, DONE 2026-07-06). SWE-bench-
  grade in-place editing. `SEON_FS_ROOT` roots it; the SWE arm already runs it
  workspace-writable against `/testbed`.
- **`seon.agent.web/fetch` + `/search`** (`web.cljs`) — URL→markdown+blob,
  question→ranked rows+grounded answer. Browserless read only (curl/wget
  analog), NOT a GUI browser.
- **`seon.agent.search/grep` + `grep-graph`** (`search.cljs`).
- **`my.blob`** (content-addressed disk tier), **`my.plan`** (plan bookkeeping
  with `::expect`, `::needs`, `reconcile!`), **`my.kb`** (DB-memory manual),
  **`my.data`/`my.ui`/`my.tile`/`my.canvas`** (render surfaces).

Coverage verdict per bench:

| Bench | Drivable through EXISTING toolkit? | Gap |
|---|---|---|
| **aider-polyglot** | **YES, today.** Read stub → edit file (`replace!`/`write-file`) → `shell/run` the language's test runner → read pass/fail. Pure shell+fs. | Non-Python runners (go/cargo/mvn/npm) must be on the container PATH — a container-image concern, not a toolkit gap. |
| **terminal-bench** | **YES, today** (mechanism proven — `tb_agent.py` injects the pod, agent works in the container via shell+fs). | Each task's Docker image must carry the tools the task needs; seon overlay is arm64 (TB-2 images are amd64 — `tb2_agent.py` arch note). |
| **swe-bench** | **YES** — already wired (A-overlay arm). shell+fs against `/testbed`. | arm64/amd64 image pairing (documented). |
| **commit0** | **Yes in principle** (shell+fs, build libs, run pytest) — heavy but no new surface. | Heavy per-repo build; long timeouts. |
| **agentbench os_interaction / dbbench** | **YES** (shell; SQL via shell client). | dbbench needs a DB client in the image. |
| **bfcl_ast** | **YES** — wired; text→tool_call adapter, AST oracle, no exec. | none (pure files). |
| **gaia** | Partial — needs web read (`seon.agent.web` covers browserless fetch/search) but many tasks need files/multimodal. | some tasks need richer tools; CASE-2 tier per catalog. |
| **tau2-bench** | **Small addition** — tool-call dialog against a simulated user + domain API. seon can drive it but it is a *tool-calling* shape, not code edits. | needs a tau2 domain-API adapter (the dialog/user-sim loop), not a new agent surface. |
| **osworld / webarena / browsergym / online-mind2web / mind2web** | **NO — needs a whole new GUI/browser surface.** seon has NO computer-use / Playwright / screenshot tool. `seon.agent.web` is a read, not a controllable browser. | full browser/GUI automation surface — a large new capability, out of scope for sourcing v0 data. |
| **cybench** | **Yes via shell** but needs a CTF tool image; niche. | security tooling in image. |
| **mle-bench / re-bench** | Yes via shell but GB datasets / GPU / hours-long runs. | infeasible for pair-mining volume. |

**Summary:** the shell+fs+plan slice is **already complete** for the terminal /
code-edit benches. No new toolkit surface is needed for aider-polyglot,
terminal-bench, swe-bench, agentbench-os, commit0, bfcl. Everything computer-
use (osworld/webarena/browsergym/mind2web) needs a **new GUI/browser
capability** seon does not have — those are explicitly out of the v0 sourcing
plan.

## 4. Verified-trajectory pipeline sketch (the two tractable benches)

Shared shape (nothing new — reuses capture + oracle machinery that exists):

```
task text  →  seon agent session (frontier-driven, capture ON,
              plan! with ::expect = a restatement of the task's check)
           →  agent works: my.plan deltas + shell/run + fs edits, turn by turn
           →  bench ORACLE runs (pytest / test runner / all-PASSED)
           →  IF PASS: exporter mints (profile-context, ok-forms) pairs from
              every turn of that session, stamped {turn-id, agent, basis-t,
              store, projection-sha, bench, task-id, oracle=pass}
           →  IF FAIL: session diagnosed, NOT trained on
```

The exporter is the **A1 exporter the design already calls for** — the byte-
exact `seon.repl.autocomplete/context` (profile render over the turn's
`rendered-as-of` db) + the turn's `:seon.eval/ok? true` form sources. The
bench oracle is just an ADDITIONAL certification stamp on top of the per-form
eval-ok filter: it certifies the *whole session* reached the goal, so the pairs
are gold-by-construction, not merely syntactically clean.

### 4a. aider-polyglot (wire FIRST — lightest)

- **Runtime home:** an **ephemeral cluster per task** (`per_sample_cluster`,
  frozen bundle) with `SEON_FS_ROOT` pointed at a scratch copy of the exercise
  dir and `SEON_SHELL` granted. No docker image needed beyond the language
  runtimes on PATH — this is the cheapest correct wiring in the whole set.
  Alternatively a single long-lived acme-style cluster if language runtimes are
  installed there once.
- **Task → session:** feed `.docs/instructions.md` as the goal; the hidden
  `<name>_test.*` is copied in as the oracle (agent does not see `.meta/`).
  `plan!` with `::expect "the exercise test file passes"`.
- **Oracle:** `shell/run` the test runner (`pytest` / `go test` / `cargo test`
  / `mvn test` / `npm test`), exit 0 = pass. Deterministic.
- **Wiring:** a `BenchSpec`-adjacent adapter in `src-inspect-ai` (no inspect
  module exists, so a small custom dataset loader that reads the exercise dirs,
  + the existing pod solver, + a shell-exit scorer). Three-surfaces-clean:
  it is a task INSIDE `src-inspect-ai`, not a driver.
- **Yield:** 225 exercises. Solvable ones ≈ frontier pass-rate (aider-polyglot
  frontier scores are ~60-80%), so ~140-180 certified sessions × ~4 turns ×
  ~2 forms/turn ≈ **~1,100-1,400 verified pairs**, dominated by fs-edit + shell
  + plan forms.

### 4b. terminal-bench (wire SECOND — mechanism already exists)

- **Runtime home:** tb owns the per-task Docker container; `tb_agent.py`
  injects the pod via `container.put_archive` and drives `POST /agents/run`.
  Capture ON inside that pod. (arm64/amd64 image pairing is the standing infra
  gate for a live in-container drive — same as the SWE arm.)
- **Task → session:** `task.yaml:instruction` is the goal; `::expect` = "the
  task's pytest suite passes". tb runs `tests/test_outputs.py` unchanged as the
  oracle. **233 tasks ship an oracle `solution.sh`** — those are a second gold
  source: a solution script is itself a known-good terminal trajectory (though
  it is bash, not seon forms, so it seeds the *situation*, and the seon session
  provides the forms).
- **Oracle:** tb's own parser (all-PASSED). Deterministic.
- **Wiring:** the `tb_agent` adapter already exists; add the capture→export
  step (mine the pod's turns after a PASS). Runs on tb's harness, not inspect —
  consistent with the existing dual-arm design.
- **Yield:** 241 tasks × frontier-solvable fraction (~40-60% on tb) ≈ ~100-145
  certified × ~5 turns × ~2 ≈ **~1,000-1,500 verified pairs**, again shell+fs+
  plan dominant.

### 4c. (optional third) bfcl_ast — already wired, different shape

Already a `BenchSpec`. Not a code-edit trajectory — it is single tool-call
prediction. Useful as a **held-out signal-ceiling probe for the "words →
mechanical call" mapping** (KT2b legibility precedent), not a source of
multi-form REPL trajectories. Low priority for training pairs, high value as a
zero-shot diagnostic.

## 5. Ranked recommendation

Score = oracle quality × toolkit coverage × (inverse) harness weight ×
scenario diversity.

| Rank | Bench | Oracle | Toolkit | Harness | Diversity | Verdict |
|---|---|---|---|---|---|---|
| **1** | **aider-polyglot** | hard test oracle | complete today | **plain files (lightest)** | 6 languages, 225 algorithmic tasks | **Wire first.** Cheapest correct path; highest pairs-per-unit-effort. |
| **2** | **terminal-bench** | hard pytest oracle + 233 solution scripts | complete today | docker/task (adapter exists) | system/troubleshooting/coding/data — richest terminal diversity | **Wire second.** Mechanism proven; arch pairing is the only gate. |
| 3 | swe-bench (verified) | official scorer | complete (wired) | docker/instance | real-repo bug fixes | Already wired; harvest its passing sessions for pairs. |
| 4 | agentbench os_interaction / dbbench | hard | complete | docker (light) | shell + SQL | Good breadth top-up after 1&2. |
| 5 | commit0 | full pytest suites | complete | docker (heavy) | build-a-library | Rich but heavy; later. |
| 6 | bfcl_ast | AST-match | complete (wired) | files | tool-call only | Diagnostic, not trajectories. |
| 7 | tau2-bench | DB-state + user-sim | needs domain adapter | python sim | customer-service dialog | Different shape; defer. |
| — | osworld / webarena / browsergym / mind2web / online-mind2web | (mostly) hard, one LLM-judge | **needs new GUI/browser surface** | VM/browser (heaviest) | computer-use | **Out of scope** until seon has a computer-use tool. |
| — | mle-bench / re-bench / cybench | hard | shell only | GPU/GB/CTF (heaviest) | ML / R&D / security | Infeasible for volume. |
| — | swelancer | n/a | — | not vendered | — | Not vendored here; via `inspect_evals.swe_lancer` only. |

**First two to wire:** aider-polyglot, then terminal-bench. Combined
first-cut yield ≈ **~2,000-2,900 verified pairs**.

### Which v0 form kinds each bench actually feeds (be honest)

The v0 MODEL contract (design §Target granularity) is copy-heavy WHOLE-FORM
kinds: **plan forms, `db/transact!` maps, `schema/register!`**. Here is the
honest mapping — this is the load-bearing risk:

- **`my.plan` deltas — YES, richly.** Every multi-step bench task exercises
  `step!`/`needs!`/`active!`/`move!`/`done!` and `reconcile!`. This is the
  flagship v0 target and the benches feed it *directly and abundantly*
  (long-horizon terminal tasks are exactly the plan-domain situations the
  design's dataset quotas demand: incremental asks against non-empty trees,
  ordering-bearing lists, id resolution).
- **Shell-call forms (`seon.agent.shell/run`) — YES, abundantly.** The dominant
  form kind in every terminal/code-edit trajectory. (Note: shell/edit forms are
  the *serving* north star — "every turn, everything" — even though the v0
  MODEL contract narrows to plan/transact/register. So these pairs feed the
  serving surface and the diffusion second-consumer, and are gold for the
  general autocomplete goal.)
- **Edit-protocol forms (`fs/replace!`/`insert!`/`edit-file`) — YES.** Core to
  aider-polyglot and swe-bench-style tasks.
- **`schema/register!` — NO, not organically.** These benches edit Python/Go/
  Rust; they never register Clojure schemas. `register!` pairs come ONLY from
  the synthetic/gold data-modeling pipeline, OR incidentally when a task's
  findings are recorded to `my.kb` (which registers attrs) — a thin,
  opportunistic source, not a primary one.
- **`db/transact!` — ONLY via `my.kb`/memory recording.** When an agent records
  a benchmark finding as a fact (provenance datom), that is a real `transact!`
  form. So transact! pairs appear as a *byproduct* of the memory-discipline the
  agent applies during a task — genuine but sparse, and their SHAPE is kb-
  recording, not the rich domain-modeling transacts the synthetic pipeline
  stages.
- **Query `:where` graphs / `defn` bodies — excluded from v0 anyway** (design):
  benches would produce Python defns, which are irrelevant to seon's defn form
  kind. The `(seon.ai/gen-fn {::intent …})` delegation call, however, IS
  exercisable — "this situation needs a function" is a copy-of-intent form.

### Risks

1. **Language mismatch (the big one, stated above):** benches feed shell+edit+
   plan, NOT the register!/transact! data-modeling core of the v0 MODEL
   contract. The synthetic/gold pipeline remains PRIMARY for those kinds; the
   benches are a source of *terminal + planning* trajectories and a strong
   held-out signal-ceiling / eval corpus. Do not oversell them as covering v0's
   model contract — they cover the serving north star's shell/edit/plan slice.
2. **Capture contamination:** turns carrying a `:suggest` row are suggestion-
   contaminated; the exporter must filter them (design already specifies this).
   During a bench drive with capture ON, if `:suggest` is live, those sessions
   are eval-only, not training — same rule as the 224 mined turns.
3. **Byte-exact regenerability:** bench-mined turns must render through the same
   `seon.repl.autocomplete/context` profile as everything else, over the turn's
   `rendered-as-of`. Ephemeral/task clusters wiped after a run mean the store is
   gone — so either (a) mine + export DURING the run before destroy, or (b)
   preserve the store. `per_sample_cluster` destroys by default; the exporter
   must run inside the sample lifecycle (before `cluster destroy`).
4. **arm64/amd64 image pairing** blocks live in-container drives for tb-2 and
   swe-bench on the arm64 host (documented in `tb2_agent.py`); an amd64 seon
   overlay is an infra/owner build.
5. **Holdout hygiene:** benchmark task text and hidden tests must never leak
   into agent-visible context. `freeze.py` + `datasets.lock` + the canary guard
   already enforce this — REUSE it; register any bench-derived training corpus
   with a canary GUID so the CI guard covers it.
6. **License heterogeneity:** MIT/Apache across the set (see §1), but
   aider-polyglot (Exercism-derived) has no top-level LICENSE in the vendored
   tree — verify per-track licensing before publishing any derived corpus.
   terminal-bench carries a `terminal-bench-canary` GUID in every task file —
   that guard string must itself be treated as held-out.
7. **Frontier pass-rate ceiling:** only PASSED sessions become gold, so the
   yield scales with the driving model's competence on each bench. Weaker
   frontier ⇒ fewer certified sessions ⇒ fewer pairs. This is correct (we only
   want verified trajectories) but means yield is a function of model spend.

## Sources (cite paths)

- Bench inventories: `reference-code/{terminal-bench,aider-polyglot,swe-bench,
  commit0,swelancer,osworld,webarena,browsergym,online-mind2web,agentbench,
  tau2-bench,cybench,mle-bench,re-bench,gorilla-bfcl}/`.
- terminal-bench task shape: `reference-code/terminal-bench/original-tasks/
  broken-python/{task.yaml,run-tests.sh,tests/test_outputs.py}`; 233
  `solution.sh` + 8 `solution.yaml`.
- aider-polyglot shape: `reference-code/aider-polyglot/python/exercises/
  practice/affine-cipher/{.docs/instructions.md,affine_cipher_test.py,
  .meta/example.py}`.
- inspect-evals modules: `reference-code/inspect-evals/src/inspect_evals/`
  (swe_bench, agent_bench, gaia, osworld, cybench, mind2web, swe_lancer, tau2,
  bfcl, vimgolf_challenges, theagentcompany, mle_bench, …).
- seon consumption: `src-inspect-ai/src/seon_inspect/{catalog.py,tb_agent.py,
  tb2_agent.py,swebench_arm.py,bfcl_adapter.py,solver.py,cluster.py,
  oracle_scorers.py,generators.py,freeze.py}`; `src-inspect-ai/tests/
  test_canary_guard.py`.
- seon toolkit: `src/seon/agent/{shell.cljs,fs.cljs,web.cljs,search.cljs}`;
  `src/my/{plan.cljs,kb.cljs,blob.cljs}`; `docs/seon/architecture/toolkit.md`;
  `docs/prds/agent-ctx/edit-protocol-spec.md`.
- design contract: `docs/prds/repl-autosuggest/{design.md,roadmap.md,CLAUDE.md}`.
