# seon-inspect — Seon benchmarks on the inspect-ai standard harness

Core, maintained code (owner directive 2026-07-02): the cluster pod solver and
the diffusion measurement tasks live HERE, not in a PRD dir. The Phase-0 spike
(`docs/prds/agent-fsm/research/inspect-bridge-spike/`) is history; future bench
work goes in this package.

**The shape (Option B — Seon owns the loop):** inspect supplies the dataset +
host-side scorer; a Seon cluster's pod agent is a custom `@solver` behind
`POST /agents/run` (its own FSM runs every turn, inspect never manages one), and
diffusion-worker tasks call the RunPod worker the same way. A CLUSTER is the
isolation unit: static-URL mode drives one long-lived cluster's pod (acme),
`per_sample_cluster=True` mints one ephemeral cluster per sample
(`bin/seon cluster create|destroy`, ~10-25s boot each). Every generation
scores through the REAL Seon oracles — the bb parse/structural/phase server
(`bin/oracle-server`) and the node cljs.js eval bundle
(`out/worker-oracle-eval/main.js`) — behind a fail-loud **oracle-liveness
gate**: a golden known-good must score faithful AND a golden known-bad
(def-vs-defn) must FAIL the eval tier before any task constructs. That gate
exists because a dead eval bundle silently voided a GPU run once
(`docs/prds/diffusion-dynamic-context/research/e1-behavioral-zero-audit-2026-07-02.md`).

## Setup (one command)

```bash
cd src-inspect-ai
uv venv && uv pip install -e ".[test]"     # or: python -m venv .venv && .venv/bin/pip install -e ".[test]"
```

`inspect-ai` installs from the vendored source `reference-code/inspect-ai`
(`[tool.uv.sources]`) — the exact dev build everything here was proven against
(`0.1.dev1+g92dd737b9`; verified on python 3.12 and 3.14). The node eval bundle
must exist: `clj -M:cljs compile worker-oracle-eval` (the liveness gate tells
you loudly when it doesn't). `bb` must be on PATH.

## Layout

```
src/seon_inspect/
  solver.py              pod_run (POST /agents/run) + seon_pod_solver (static
                         URL) + seon_cluster_solver (ephemeral cluster per
                         sample) + timeout_honesty scorer; 422 = AgentRunRefused
  cluster.py             cluster lifecycle (create/restart_pod/destroy via
                         bin/seon, port-file read, ready poll) + wire-server
                         socket-REPL read-back (wire_repl_json)
  oracle_scorers.py      persistent bb+node oracle servers, score_code tier
                         ladder, ladder_scorer, assert_oracle_live
  generators.py          seeded bespoke-row generators (shell_use / web_fetch /
                         file_edit / long_term_planning) + fixture server +
                         setup/render helpers
  tool_scorers.py        outcome oracles for the tool rows (workspace re-read,
                         fixture ground truth; bb/node for code targets)
  planning.py            long_term_planning two-part oracle (final answer +
                         plan-trajectory resumption evidence) + the two-phase
                         restart choreography (pod_planning_driver: cluster
                         create -> phase 1 -> pod restart -> phase 2 with the
                         SAME agent_id -> plan snapshot via the wire REPL)
  worker_endpoints.py    mock:<scenario> | runpod endpoint resolution
  worker_mock.py         canned offline worker (REAL Clojure fixtures)
  offline_proof.py       python -m seon_inspect.offline_proof
  tasks/
    e1_spec_fn.py        E1 three-arm (runbook step 3)
    skill_lift.py        north-star skill A/B
    ladder_lift.py       ladder ON/OFF (runbook step 4)
tests/                   the offline proofs as the pytest regression suite
```

Generated `.jsonl` data artifacts live in `evals/` (the single dataset home,
next to `datasets.lock`) — never under the package or docs/.

## Run matrix

| Target | Command | Needs |
|---|---|---|
| Offline proof (all tasks, canned worker, REAL oracles) | `.venv/bin/python -m seon_inspect.offline_proof` | bb + the node bundle |
| Test suite | `.venv/bin/pytest` | same |
| One task offline | `.venv/bin/inspect eval src/seon_inspect/tasks/e1_spec_fn.py@e1_spec_fn -T arm=arm1_guided_refine -T endpoint=mock:guided_wins --model mockllm/model --display plain` | same |
| Pod-backed (acme, static URL) | `SEON_CLUSTER_URL=http://127.0.0.1:7980/agents/run .venv/bin/inspect eval <task> --model mockllm/model --max-samples 1` | acme pod + DeepSeek key |
| Per-sample ephemeral clusters | `run_bench("gsm8k", per_sample_cluster=True, ...)` | default supervisor stack up (`bin/seon status`) + DeepSeek key |
| Planning row (live, two-phase) | `planning.pod_planning_driver(phase1, phase2)` per sample -> `check_planning` | same + wire-server REPL (`tmp/seon-writer-repl-port`) |
| GPU worker | add `-T endpoint=runpod` + env `DIFFGEMMA_EP`/`RUNPOD_API_KEY`, `--max-samples 1` | deployed worker, `verify_fresh` FIRST (runbook step 0) |

`--model mockllm/model` satisfies eval()'s model requirement and is never
called — the solvers set completions themselves.

## Scoring: report BOTH reducers

Every task runs `Epochs(k, ["mean", pass_at(k)])`. `mean` is the RATE the E1
decision rule compares (Δ arm1−arm3 ≥ 0.10 EARNS); `pass_at_k` (at-least-one)
is the noise-robustness view and ALONE it hides arm differences — offline,
arm2's pass_at_4 is 1.000 while its rate is 0.250. Caveat: per-epoch variation
keys off `TaskState.epoch`; if a future inspect version renames it the solvers
fall back to epoch 1 (`getattr(state, "epoch", 1)`), which collapses mock
pools to one fixture — the tests would catch it (arm means shift).

## Scoring philosophy — correctness gates vs idiom metrics

**Never encode a style preference as a correctness gate** (owner correction,
2026-07-02: "We never REQUIRE -request/-response"). Seon allows BOTH fn idioms
— named `::foo-request`/`::foo-response` map-in/map-out (preferred for API
surfaces) AND named-positional / inlined schemas — and the original ported
scorer hard-gated the preference: correct inlined-map code scored
`faithful=false` (behavioral_pass=true, killed by structural). The split now:

- **Correctness gates** (`ladder_scorer`, decides `faithful`): parses ·
  not-hallucinated · spec PRESENT (either idiom) · eval-clean ·
  behavioral-pass · not vacuous.
- **Idiom metrics** (`idiom_scorer`, runs beside it): register! ·
  `-request`/`-response` naming · namespaced keys — adoption data, never a
  verdict.
- **The one exception**: a task whose measurement IS idiom adoption sets
  `spec["idiom_gates"]=True` — skill-lift does (context teaching the preferred
  idiom is exactly what it measures); nothing else may.

The liveness gate enforces this structurally: BOTH golden idioms (named +
inlined) must score faithful before any task runs, so an answer-shaped gate
cannot sneak back in.

## Offline proof (captured 2026-07-02, from THIS layout, post scorer-fix)

```text
RUN                          METRICS (from the eval logs)
E1 arm1 guided_refine        ladder:mean=1.000 pass_at_4=1.000   idiom:mean=1.000
E1 arm2 naked                ladder:mean=0.250 pass_at_4=1.000   idiom:mean=0.250
E1 arm3 naked+oracle         ladder:mean=0.250 pass_at_4=1.000   idiom:mean=0.250
skill control                ladder:mean=0.000 pass_at_4=0.000
skill treatment              ladder:mean=1.000 pass_at_4=1.000
ladder ON                    ladder:mean=1.000 pass_at_4=1.000
ladder OFF                   ladder:mean=0.250 pass_at_4=1.000
```

The mock encodes the live 06-29 pools + the audit's fixture classes — these
deltas prove the harness DISCRIMINATES; the model's numbers come from the GPU.
pytest: 20 passed (scorer-vs-oracles tier table incl. the transducer
false-positive caught by behavioral-not-eval AND the inlined-idiom golden
scoring faithful with `idiom` distinguishing; liveness-gate loud abort;
task-wiring means). Engineering note: inspect runs epochs CONCURRENTLY — the
oracle line-servers serialize request/response pairs with a lock, and metrics
are read from the eval LOGS, never scraped from stdout.

## Standard benchmarks (`inspect_evals` catalog) — the general path

Owner directive: **test useful behaviours with established benches, not
homemade gates.** So the primary path is running real `inspect_evals` tasks
(their dataset + their host-side scorer, unchanged) with the Seon pod
substituted as the solver — `seon_inspect.catalog.run_bench(name,
cluster_url=…)` (or `per_sample_cluster=True`) uses inspect's
`eval(task, solver=…)` override (verified against
`reference-code/inspect-ai/_eval/eval.py:121`). Bespoke Seon tasks
(`e1_spec_fn`, `skill_lift`, `ladder_lift`) stay only for Seon/Clojure codegen,
where no standard bench measures the same thing — and they are oracle-scored,
never invented-gate-scored.

Case-1 = `input text → final answer`, host-side scorer, **no** inspect
sandbox/tool-bridge. That's what `POST /agents/run` supports today.

| Bench | Capability | Case-1 via the pod door? | Split reported | Notes |
|---|---|---|---|---|
| `gsm8k` | grade-school math reasoning | ✅ **baselined** | test (report-only) | numeric match scorer, host-side |
| `arc_easy` / `arc_challenge` | science MC-QA | ✅ | test | choice scorer |
| `mmlu_0_shot` | broad knowledge MC-QA | ✅ | test | choice scorer |
| `commonsense_qa` | commonsense MC-QA | ✅ | validation | choice scorer |
| `truthfulqa` | truthfulness MC-QA | ✅ | validation | choice scorer |
| `gpqa_diamond` | hard graduate MC-QA | ✅ | test | csv from openaipublic (sha256-pinned in inspect_evals) — no HF token needed |
| `humaneval` / `mbpp` | code generation | ❌ **case-2** | — | scorer EXECUTES model code in a sandbox → mvm tier |
| `gaia`, `assistant_bench`, `tau2`, `theagentcompany`, `swe_bench` | agentic / **long-horizon planning** | ❌ **case-2** | — | need web/tool/repo sandboxes → mvm tier |

**Long-horizon planning (the headline capability):** no `inspect_evals` bench
measures long-horizon planning WITHOUT a tool sandbox — the agentic ones
(`gaia`/`tau2`/`theagentcompany`) are all case-2. So *plan-survives-restart*
(build a durable plan → restart the pod → resume from open plan items across
process death) legitimately stays a **bespoke Seon task** — it exercises the
DB-backed `my.plan` durability that generic benches don't touch. It runs LIVE
via `planning.pod_planning_driver`: its own cluster, phase 1 → `bin/seon
restart pod-<cluster>` → phase 2 reusing the SAME `agent_id`, plan snapshot
read back over the wire-server socket REPL, scored by `check_planning`. Tasks state the GOAL (a durable plan that survives an
interruption), never the API verbs — agents discover `my.plan` from their own
context (no-coaching rule). The case-2 agentic benches become available once the
mvm tool-bridge tier lands.

Run one (cluster-agnostic — `cluster_url` selects ANY pod that mounts
`POST /agents/run`; or let `per_sample_cluster=True` mint ephemeral ones):

```bash
# against the acme harness (a long-lived cluster):
SEON_CLUSTER_URL=http://127.0.0.1:7980/agents/run \
  .venv/bin/python -c "from seon_inspect.catalog import run_bench; \
    run_bench('gsm8k', limit=5, epochs=1, run_timeout_s=240)"
# per-sample isolation (one ephemeral cluster per sample, serial):
#   run_bench('gsm8k', per_sample_cluster=True, limit=5, run_timeout_s=240)
# Nothing is acme-specific.
```

`--model mockllm/model` is forced internally (inspect requires a model arg; the
solver bypasses it — the pod owns every turn). Image/multimodal benches also
need `pillow` (`uv pip install pillow`); the QA/math case-1 set does not.

## Frozen splits — `evals/datasets.lock` (dev / milestone / test)

Every A/B (context trim, skill edit, tool tune) runs against FROZEN samples —
the ledger decides, not taste. `seon_inspect.freeze` implements the eval-design
sampling rule (sort by id → seeded shuffle, per-source seed derived from ONE
global seed, MMLU subject-stratified round-robin → slice dev/milestone/test)
and records everything in `evals/datasets.lock`: upstream pin (HF revision /
csv sha256), seed, dev+milestone id lists, blind-test size + id-digest, corpus
content hash, and one canary GUID per test split / bespoke dataset.

```bash
.venv/bin/python -m seon_inspect.freeze          # lock present → VERIFY: no-op or loud diff (exit 1)
.venv/bin/python -m seon_inspect.freeze --write  # deliberate re-freeze (canaries carried over)
```

Run a frozen tier (sample_id-filtered, limit off):

```python
from seon_inspect.freeze import run_split
run_split("gsm8k", "dev", cluster_url=..., epochs=2)  # per-sample iteration fine
run_split("gsm8k", "milestone", ...)                  # AGGREGATE-only (loader enforces)
run_split("gsm8k", "test", formal_eval=True, ...)     # blind reserve: raises without the flag;
                                                      # canary GUID injected into sample METADATA
```

Tier discipline is structural: milestone splits don't enumerate ids
(iteration raises), the test tier won't load without `formal_eval=True`, and
`tests/test_canary_guard.py` greps `src/ docs/ config/ seon-skills/
src-inspect-ai/ test/` for any lock canary escaping `evals/` — a hit means
answer-shaped context and fails the suite. Bespoke generator rows freeze the
GENERATOR + seeds (dev=1, milestone=2, test=fresh-per-draw): rows with a
generator in `seon_inspect.generators` are `"generated"` in the lock
(dev/milestone jsonl sha256s + the dev artifact at `evals/<row>.dev.jsonl`;
milestone rows regenerate on demand from seed 2, the test tier draws a fresh
seed via `generators.fresh_test_rows`); the rest stay `pending-generator`
until their generators land. Tool-row tasks are GOAL-STATED (outcomes, never
Seon verb names) and every check their oracle makes is stated in the task
text; scoring = `tool_scorers.workspace_scorer` (shell_use / file_edit —
re-reads the per-run workspace; bb parse + node behavioral eval for code
targets) and `tool_scorers.fixture_answer_scorer` (web_fetch — LOCAL fixtures
via `generators.serve_fixtures`, ground truth computed at generation time).

### First baseline (DeepSeek, via acme, 2026-07-02 — pre-repoint `/solve` door)

| Bench | N | accuracy | turns/sample | notes |
|---|---|---|---|---|
| `gsm8k` | 2 | **1.000** | 2 | closed `:completed`, 27-38 s/sample, no timeouts |

This is Seon's first STANDARD-benchmark number — the baseline the diffusion
provider and every context change compares against. Small N (a wire-proof, not
a leaderboard); scale N + add `epochs` for `pass^k` when a real comparison is
needed. Ran against the pre-`my.plan` bundle — gsm8k is planning-independent so
the number stands; planning benches need a rebuilt pod (`bin/acme restart pod`).

## Parity map (the gym retires at parity)

| This task | Replaces | Notes |
|---|---|---|
| `e1_spec_fn` (3 arms) | `tmp/flash-diffgemma/e1_kill_gate.py` scorecard | same prompts / tiers / liveness gate; verdict = compare arm runs' `mean` |
| `skill_lift` | `tmp/flash-diffgemma/skill_lift.py` + `score_ab.py` | ledger rows → `LEDGER` entries |
| `ladder_lift` | `battery.py` refine_loop scoring | exp D's sweep grid stays in `battery.py` (knob sweep, not a benchmark) |
| memory/QA benches | spike dir (already inspect) | migrate into `tasks/` as they're next touched |

`bin/acme gym-diffusion` retires once these tasks produce one real GPU
scorecard each; the gym's agent scenarios are the agent-fsm lane's parity call.

## Live-validation checklist (pending)

- [x] Door smoke with DeepSeek (2026-07-03, ephemeral cluster): 422 refusal
      class + a completed drive (`reply "391"`, 2 turns) + a shell_use dev
      sample end-to-end (workspace + real shell + oracle verdict).
- [ ] GPU E1 re-run (runbook step 3): three `e1_spec_fn` runs,
      `-T endpoint=runpod`, epochs≥4, `--max-samples 1` (~$0.50).
- [ ] GPU ladder-lift (runbook step 4) on the co-location image.
- [ ] A tiny verdict reader over two E1 eval logs (Δ + EARNS/KILL/MARGINAL).
