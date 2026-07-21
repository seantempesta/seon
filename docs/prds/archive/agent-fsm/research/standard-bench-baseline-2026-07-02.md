---
type: research
status: active
tags: [research, agent]
---

# Standard benchmarks via /solve — first baseline (2026-07-02)

> Owner directive: "test useful behaviours with general tests like with
> inspect-ai capabilities, not bullshit things we made up." This wires the
> established `inspect_evals` catalog to Seon through the `/solve` solver and
> records the first real standard-benchmark number. Code:
> `src-inspect-ai/src/seon_inspect/catalog.py` (+ README "Standard
> benchmarks"). Pod-agnostic — acme is only the pod we happen to drive.

## TL;DR

- **`inspect_evals` runs against Seon.** A real catalog Task (its dataset + its
  host-side scorer, unchanged) runs with `seon_pod_solver` substituted for the
  task's `generate()` via inspect's `eval(task, solver=…)` override. No bench
  code copied; we depend on the `inspect_evals` package.
- **First baseline: `gsm8k` = 2/2 (accuracy 1.000)** on live DeepSeek acme
  agents through `/solve` — 2 turns / 4-5 evals each, closed `:completed`,
  27-38 s/sample, zero timeouts. Seon's first standard-benchmark number.
- **Case-1 catalog set assessed** (input→answer, host-side scorer, no sandbox):
  gsm8k, arc, mmlu, commonsense_qa, truthfulqa, gpqa — all load and are
  runnable. Code-exec (humaneval/mbpp) and agentic/web (gaia/tau2/
  theagentcompany/swe_bench) are **case-2** (need a tool sandbox) → the mvm tier.
- **Long-horizon planning has NO case-1 catalog bench** — every agentic
  planning bench needs tools. So *plan-survives-restart* stays a bespoke Seon
  task (the DB-backed `my.plan` durability generic benches don't exercise).

## Install reality (grounded)

- `inspect_evals` is vendored (`reference-code/inspect-evals`) AND its
  `pyproject` requires `inspect_ai >= 0.3.221`, but our vendored inspect-ai is a
  tagless shallow checkout → setuptools_scm reports `0.1.dev1+g92dd737b9`, which
  FAILS that constraint. Resolution: `uv pip install --no-deps -e
  ../reference-code/inspect-evals` (the solver only uses inspect_ai's stable
  public task/solver/scorer API, proven), then install the case-1 runtime deps
  (`backoff pandas datasets huggingface_hub jinja2 numpy pydantic pyyaml
  requests tiktoken`). `pillow` only for image benches. Documented in the README.
- Datasets download from HuggingFace at task-load (gsm8k pulled clean). Gated
  datasets (gpqa) need an HF token.

## What each bench measures + the retirement map

| Bench | Measures | Replaces (homemade) |
|---|---|---|
| `gsm8k` | multi-step arithmetic reasoning | — (new capability coverage) |
| `arc`, `mmlu`, `commonsense_qa`, `truthfulqa` | knowledge / MC-QA | the spike `memory_qa` bench's *general* QA half (Seon-memory recall stays bespoke) |
| `gpqa_diamond` | hard reasoning | — |
| `humaneval`/`mbpp` (case-2) | code-gen, sandbox-executed | overlaps `coding_eval` spike bench — adopt at the mvm tier |
| `gaia`/`tau2`/`theagentcompany` (case-2) | agentic long-horizon | the planning capability — but they need tools; see below |

**Stays bespoke (no standard equivalent):** Seon/Clojure codegen (oracle-scored
via bb+node — `e1_spec_fn`, `skill_lift`, `ladder_lift`) and **plan-survives-
restart** (durable `my.plan` across process death). Bespoke tasks are
oracle-scored on correctness, never invented-gate-scored (see the E1 structural
scorer correction — a style preference must never be a gate).

## Long-horizon planning — the headline capability

The owner names long-term planning as the capability to benchmark: a plan that
survives interruption and spans many turns/sessions. Survey of the catalog: the
benches that touch long-horizon agency (`gaia`, `assistant_bench`, `tau2`,
`theagentcompany`, `swe_bench`, `osworld`, `mind2web`) are ALL case-2 — they
need a web/tool/OS/repo sandbox the `/solve` door doesn't provide. None isolates
*planning durability across a restart*, which is the Seon-specific claim (plan
items are DB rows, so a `bin/… restart pod` mid-task should resume from open
items). That is why it stays a bespoke task, shaped like the spike's
`planning_resume`: state the GOAL (a durable plan surviving a restart), never
the API verbs — the agent discovers `my.plan` from its own context. When the
mvm tool-bridge (case-2) lands, `tau2` / `theagentcompany` become the general
long-horizon-agency benches to add.

## Pointers

- `src-inspect-ai/src/seon_inspect/catalog.py` — the adapter (data-driven
  `CASE1_BENCHES`; `run_bench(name, solve_url=…)`).
- `src-inspect-ai/README.md` "Standard benchmarks" — assessment table + run
  commands + the baseline.
- [[research/inspect-seon-bridge-spike-2026-07-01]] — the /solve mechanism.
- `docs/prds/diffusion-dynamic-context/research/deepseek-preflight-drives-2026-07-02.md`
  — the harness-honesty drives (per-sample timeout variance → use `pass^k` +
  headroom, which this baseline inherits).
