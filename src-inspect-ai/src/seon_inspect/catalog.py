"""Run ESTABLISHED inspect_evals benchmarks against Seon clusters.

Owner directive (2026-07-02): "test useful behaviours with general tests like
with inspect-ai capabilities, not bullshit things we made up." So the standard
path is: take a real `inspect_evals` Task (its dataset + its host-side scorer,
unchanged) and SUBSTITUTE our pod solver for the task's own `generate()`
solver — inspect's `eval(task, solver=...)` overrides the solver while keeping
the task's dataset and scorer (verified against the vendored inspect-ai
source, `_eval/eval.py:121` — `solver` param is an override).

Two run modes:
- static URL (`cluster_url` argument or SEON_CLUSTER_URL): every sample drives
  the SAME long-lived cluster's pod (e.g. acme). Nothing is acme-specific —
  any pod that mounts `POST /agents/run` works.
- `per_sample_cluster=True`: one EPHEMERAL cluster per sample (create → drive
  → destroy via `bin/seon cluster create|destroy`) — true isolation by
  construction, at ~config.CLUSTER_BOOT_BUDGET_S boot cost per sample.

CASE-1 only (this module): benches that need `input text -> final answer` with
a host-side scorer and NO inspect sandbox/tool-bridge. Code-execution benches
(HumanEval/MBPP run the model's code in a sandbox) and web-tool benches (GAIA)
are the CASE-2 / mvm tier — deferred, not faked.
"""

from __future__ import annotations

import importlib
import shutil
from pathlib import Path
from typing import Any, Callable, Sequence

from inspect_ai import Task, eval as inspect_eval
from inspect_ai._util.registry import registry_log_name
from inspect_ai.solver import Generate, Solver, TaskState, solver

from seon_inspect import cluster as cluster_mod
from seon_inspect import config
from seon_inspect.solver import seon_cluster_solver, seon_pod_solver


# The case-1 catalog we have assessed as viable through the pod door (see
# README "Standard benchmarks"). Value = (module, task-attr). Kept as data so
# adding a bench is a one-line edit, not new code.
CASE1_BENCHES: dict[str, tuple[str, str]] = {
    "gsm8k": ("inspect_evals.gsm8k", "gsm8k"),
    "arc_easy": ("inspect_evals.arc", "arc_easy"),
    "arc_challenge": ("inspect_evals.arc", "arc_challenge"),
    "mmlu_0_shot": ("inspect_evals.mmlu", "mmlu_0_shot"),
    "commonsense_qa": ("inspect_evals.commonsense_qa", "commonsense_qa"),
    "truthfulqa": ("inspect_evals.truthfulqa", "truthfulqa"),
    "gpqa_diamond": ("inspect_evals.gpqa", "gpqa_diamond"),
    # BFCL single-turn AST subset — established tool-calling bench, pure
    # host-side AST-match scorer (no exec/sandbox). It needs a text->tool_call
    # ADAPTER (BENCH_ADAPTERS below), not the generic swap_generate, because
    # its scorer harvests structured ToolCalls the pod's text reply must be
    # lifted into. Default categories = the python AST set (see bfcl_adapter).
    "bfcl_ast": ("inspect_evals.bfcl", "bfcl"),
}


# Benches whose scorer does NOT read the pod's TEXT reply directly and so need
# a bespoke solver chain (an `adapt` hook) instead of `swap_generate`. Keyed by
# catalog name; a bench absent here uses `swap_generate` (the default). Data,
# not a code branch — adopting such a bench stays a one-line edit. bfcl's AST
# scorer reads synthesized ToolCalls, so bfcl_ast maps to the text->call
# bridge; nothing else needs one today.
def _bfcl_adapt(*args: Any) -> Any:
    from seon_inspect.bfcl_adapter import bfcl_adapt  # deferred: heavy import
    return bfcl_adapt(*args)


BENCH_ADAPTERS: dict[str, Callable[..., list[Solver]]] = {
    "bfcl_ast": _bfcl_adapt,
}


def save_eval_logs(logs: Any, evidence_dir: Path) -> list[str]:
    """Copy a run's inspect `.eval` log files into `<evidence_dir>/inspect-logs/`.

    Evidence-retention fix (2026-07-04): run dirs under `evals/runs/` must
    ALWAYS carry the run's own .eval logs — the concurrent-pass web_fetch
    root-cause was unrecoverable partly because logs lived only under the
    package's transient logs/ tree. Never raises; returns the copied paths."""
    out_dir = evidence_dir / "inspect-logs"
    copied: list[str] = []
    for log in (logs or []):
        loc = str(getattr(log, "location", "") or "")
        src = Path(loc.removeprefix("file://"))
        try:
            if src.is_file():
                out_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, out_dir / src.name)
                copied.append(str(out_dir / src.name))
        except Exception:
            continue
    return copied


# Per-bench default task kwargs, applied UNDER caller kwargs — the one place a
# bench's constrained scope lives so freeze-time and run-time load identically.
# bfcl's upstream default is EVERY category (incl. exec + multi-turn, which pull
# a sandbox); we pin the pure-AST python subset so bfcl_ast never widens.
def _bfcl_ast_categories() -> list[str]:
    from seon_inspect.bfcl_adapter import BFCL_AST_CATEGORIES  # deferred import
    return list(BFCL_AST_CATEGORIES)


BENCH_DEFAULT_TASK_KWARGS: dict[str, Callable[[], dict[str, Any]]] = {
    "bfcl_ast": lambda: {"categories": _bfcl_ast_categories()},
}


def load_bench_task(name: str, **task_kwargs: Any) -> Task:
    """Load a standard inspect_evals Task by catalog name (its own dataset+scorer)."""
    if name not in CASE1_BENCHES:
        raise KeyError(
            f"{name!r} not in the assessed case-1 catalog {sorted(CASE1_BENCHES)}; "
            "code-exec (humaneval/mbpp) + web-tool (gaia) benches are the "
            "case-2/mvm tier — not runnable through the pod door."
        )
    mod_name, attr = CASE1_BENCHES[name]
    task_fn: Callable[..., Task] = getattr(importlib.import_module(mod_name), attr)
    defaults = BENCH_DEFAULT_TASK_KWARGS.get(name)
    merged = {**(defaults() if defaults else {}), **task_kwargs}
    return task_fn(**merged)


@solver
def pod_backed(step: Solver, pod_solver: Solver) -> Solver:
    """A bench solver step whose INTERNAL `generate()` call drives the pod.

    Composite bench solvers (`multiple_choice` — arc/mmlu/gpqa) format the
    prompt AND call their `generate` callback themselves, then parse the
    reply into `state.choices` for the `choice()` scorer. They never appear
    as a chain-level "generate" step, so swapping chain steps alone would
    leave their internal call on the mock model and the parsed answer would
    be mock garbage (the answer-format contract's sibling trap, standard
    sweep 2026-07-03). Wrapping substitutes the callback: the step keeps its
    own template + parsing; the pod supplies the completion. Steps that
    never call generate (templates, system messages) pass through unchanged
    in behavior."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        async def pod_generate(state: TaskState, *args, **kwargs) -> TaskState:
            return await pod_solver(state, generate)

        return await step(state, pod_generate)

    return solve


@solver
def pod_fallback(pod_solver: Solver) -> Solver:
    """Run the pod ONLY if no upstream step already drove it.

    Appended when a chain has no chain-level `generate` step: a composite
    step (multiple_choice) already drove the pod through its internal
    callback — running the pod AGAIN would double cost and clobber the
    parsed state. The guard is the pod-run MARKER (`pod_agent_id`, stamped
    by the solver's `_record_result`), NOT completion text: a pod run that
    legitimately returned an EMPTY reply must stay that sample's recorded
    answer — re-running would mint a second agent whose reply bypasses the
    bench step's parsing (observed: arc MEA_2016_5_4 scored I while its
    visible completion read "ANSWER: D" — the D came from an unparsed
    second run)."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        if (state.metadata or {}).get("pod_agent_id") is not None:
            return state
        return await pod_solver(state, generate)

    return solve


def swap_generate(task_solver: Solver | Sequence[Solver],
                  pod_solver: Solver) -> list[Solver]:
    """The task's own solver chain with every `generate()` swapped for the pod.

    A bench's answer-format contract (e.g. gsm8k's "ANSWER: $ANSWER"
    prompt_template) lives in its SOLVER CHAIN, not its dataset — replacing
    the whole chain silently drops the contract and the bench then measures
    prompt-omission, not capability (first dev pass, 2026-07-03: correct
    conversational replies scored INCORRECT because match() never saw a
    templated answer). So: chain-level `generate` steps become the pod
    solver; every OTHER step is kept but `pod_backed` (its internal
    `generate()` callback — `multiple_choice`'s — drives the pod too); and a
    guarded `pod_fallback` is appended when the chain had no chain-level
    generate (it no-ops when a composite step already produced the
    completion)."""
    steps = (list(task_solver) if isinstance(task_solver, Sequence)
             else [task_solver])
    out: list[Solver] = []
    swapped = False
    for step in steps:
        try:
            name = registry_log_name(step)
        except Exception:
            name = ""
        if name == "generate":
            out.append(pod_solver)
            swapped = True
        else:
            out.append(pod_backed(step, pod_solver))
    if not swapped:
        out.append(pod_fallback(pod_solver))
    return out


def run_bench(
    name: str,
    *,
    cluster_url: str | None = None,
    per_sample_cluster: bool = False,
    cluster_parallelism: int | None = None,
    limit: int | None = 5,
    epochs: int = 1,
    run_timeout_s: int | None = None,
    max_samples: int | None = None,
    task_kwargs: dict[str, Any] | None = None,
    task: Task | None = None,
    adapt: Callable[..., list[Solver]] | None = None,
    evidence_dir: "Path | None" = None,
    **eval_kwargs: Any,
):
    """Run a standard bench with a Seon cluster's pod agent as the solver.

    `cluster_url` (or SEON_CLUSTER_URL) selects the long-lived cluster's pod
    door — cluster-agnostic; acme is just one value. `per_sample_cluster=True`
    switches to one ephemeral cluster per sample instead (mutually exclusive
    with `cluster_url`). `cluster_parallelism` (per-sample mode only; default
    `config.BENCH_CLUSTER_PARALLELISM`) is bench-cluster-N: that many
    ephemeral clusters live at once — inspect dispatches that many samples
    concurrently, each minting its OWN cluster (still ONE sample per pod).
    In per-sample mode the frozen bench bundle is pre-built ONCE up front
    (`cluster.ensure_bench_bundle`) — freshness is RUN-level; creates never
    rebuild, so concurrent creates can't race a compile and mid-run src
    saves can't swap code under the run. `limit` keeps baselines cheap;
    `epochs` enables
    pass^k. Timeouts/concurrency default from `seon_inspect.config`
    (calibration-derived) and are overridable here per-run — but `max_samples`
    above `config.POD_MAX_SAMPLES` (1) against a SINGLE cluster corrupts
    samples (one pod = one cluster = one sample at a time); raise it only when
    the URL fronts a pool. The bench's OWN scorer grades the replies
    host-side. Returns inspect's eval logs. `--model` is required by inspect
    but never called (the solver bypasses it) — pass model="mockllm/model".

    `task` lets a caller pass a PREBUILT catalog Task (e.g. `freeze.run_split`,
    which injects canary metadata into the blind tier) instead of constructing
    one from `task_kwargs` — same dataset+scorer either way.

    `evidence_dir` (evidence-retention fix, 2026-07-04 — run dirs must always
    hold the evidence): copies the run's inspect `.eval` logs into
    `<evidence_dir>/inspect-logs/` (even when the frozen-bundle assertion
    raises), and in per-sample mode also preserves each ephemeral cluster's
    blob store under `<evidence_dir>/blobs/e<epoch>/<sample_id>/` before the
    cluster is destroyed — per-execution reply text + transcripts survive.
    """
    if per_sample_cluster and cluster_url:
        raise ValueError(
            "per_sample_cluster=True mints its own clusters — "
            "cluster_url selects a long-lived one; pass one or the other")
    if cluster_parallelism is not None and not per_sample_cluster:
        raise ValueError(
            "cluster_parallelism is bench-cluster-N (per-sample ephemeral "
            "clusters); a static cluster_url pod is serial by construction "
            "(POD_MAX_SAMPLES) — pass per_sample_cluster=True")
    if task is None:
        task = load_bench_task(name, **(task_kwargs or {}))
    # Bench-specific solver bridge (bfcl needs text->tool_call); default =
    # swap_generate. An explicit `adapt=` argument overrides the registry.
    adapt_fn = adapt or BENCH_ADAPTERS.get(name, swap_generate)
    bundle_start = None
    if per_sample_cluster:
        parallelism = cluster_parallelism or config.BENCH_CLUSTER_PARALLELISM
        # Build ONCE up front — freshness is RUN-level: creates only
        # build-if-missing (a per-create staleness rebuild swaps code under
        # the run whenever src/ is saved mid-run), so the run starts on
        # current code HERE and the identity stays pinned for the run.
        cluster_mod.ensure_bench_bundle()
        the_solver = seon_cluster_solver(
            timeout_s=run_timeout_s,
            evidence_root=(evidence_dir / "blobs") if evidence_dir else None)
        # Per-sample clusters run the FROZEN bench bundle (the supervisor's
        # --ephemeral default). Pin its identity NOW and record it in the
        # run's own artifacts (EvalLog metadata) — the end-of-run assertion
        # below makes any mid-run bundle change DETECTED, not scored through.
        bundle_start = cluster_mod.bundle_identity()
        md = dict(eval_kwargs.pop("metadata", None) or {})
        md["seon_bundle"] = bundle_start
        eval_kwargs["metadata"] = md
    else:
        the_solver = seon_pod_solver(cluster_url=cluster_url,
                                     timeout_s=run_timeout_s)
    if max_samples is None:
        # per-sample-cluster mode: inspect's sample concurrency IS the number
        # of live clusters (each concurrent sample mints its own); static-URL
        # mode stays at the per-pod ceiling (1 by construction).
        max_samples = (parallelism if per_sample_cluster
                       else config.POD_MAX_SAMPLES)
    logs = inspect_eval(
        task,
        solver=adapt_fn(task.solver, the_solver),
        model=eval_kwargs.pop("model", "mockllm/model"),
        limit=limit,
        epochs=epochs,
        max_samples=max_samples,
        **eval_kwargs,
    )
    if evidence_dir is not None:
        save_eval_logs(logs, evidence_dir)
    if per_sample_cluster:
        violation = cluster_mod.bundle_violation(bundle_start)
        if violation:
            # Contamination is a HARNESS flake, never a capability number:
            # raise loud, with the logs + both identities attached as
            # evidence (scorecard flake class: frozen_bundle_changed).
            raise cluster_mod.FrozenBundleChanged(
                violation, start=bundle_start,
                end=cluster_mod.bundle_identity(), logs=logs)
    return logs
