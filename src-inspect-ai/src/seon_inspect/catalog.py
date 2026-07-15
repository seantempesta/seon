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
- `per_sample_cluster=True`: reserved for the operator's pending structured
  lease. It currently fails before mutation; no retired cluster command is
  used as a compatibility path.

CASE-1 only (this module): benches that need `input text -> final answer` with
a host-side scorer and NO inspect sandbox/tool-bridge. Code-execution benches
(HumanEval/MBPP run the model's code in a sandbox) and web-tool benches (GAIA)
are the CASE-2 / mvm tier — deferred, not faked.
"""

from __future__ import annotations

import importlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Sequence

from inspect_ai import Task, eval as inspect_eval
from inspect_ai._util.registry import registry_log_name
from inspect_ai.log import list_eval_logs
from inspect_ai.solver import Generate, Solver, TaskState, solver

from seon_inspect import cluster as cluster_mod
from seon_inspect import config
from seon_inspect import source_admission
from seon_inspect.solver import seon_cluster_solver, seon_pod_solver


def _bfcl_adapt(*args: Any) -> Any:
    from seon_inspect.bfcl_adapter import bfcl_adapt  # deferred: heavy import
    return bfcl_adapt(*args)


def _bfcl_ast_kwargs() -> dict[str, Any]:
    # bfcl's upstream default is EVERY category (incl. exec + multi-turn,
    # which pull a sandbox); pin the pure-AST python subset so bfcl_ast
    # never widens — freeze-time and run-time load identically.
    from seon_inspect.bfcl_adapter import BFCL_AST_CATEGORIES  # deferred
    return {"categories": list(BFCL_AST_CATEGORIES)}


@dataclass(frozen=True)
class BenchSpec:
    """ONE per-bench wiring record — the single registry surface.

    Folds the former CASE1_BENCHES / BENCH_ADAPTERS /
    BENCH_DEFAULT_TASK_KWARGS trio (pre-slice-4 debt, 2026-07-05) plus the
    arm kind into one structure keyed by bench name:

    - `module`/`attr` — the upstream inspect_evals task callable.
    - `kind` — the driver arm: "case1" (text through the pod door —
      `run_bench`) or "swebench" (the A-overlay sandbox composition —
      `tasks/swe_bench_seon.py`; NOT runnable through `run_bench`).
    - `adapter` — bespoke solver-chain hook for benches whose scorer does
      not read the pod's TEXT reply (bfcl's AST scorer reads synthesized
      ToolCalls); None = the default `swap_generate`.
    - `default_task_kwargs` — a thunk of per-bench task kwargs applied
      UNDER caller kwargs (the one place a bench's constrained scope
      lives); deferred so heavy imports stay lazy.
    """

    module: str
    attr: str
    kind: str = "case1"
    adapter: Callable[..., list[Solver]] | None = None
    default_task_kwargs: Callable[[], dict[str, Any]] | None = None

    def task_fn(self) -> Callable[..., Task]:
        """The upstream task callable (imports the bench module lazily)."""
        return getattr(importlib.import_module(self.module), self.attr)


# The assessed bench registry. Adding a bench is one BenchSpec line.
# case1 = `input text -> final answer` with a host-side scorer, driven
# through the pod door; code-exec benches (HumanEval/MBPP) and web-tool
# benches (GAIA) are the CASE-2 / mvm tier — deferred, not faked.
BENCHES: dict[str, BenchSpec] = {
    "gsm8k": BenchSpec("inspect_evals.gsm8k", "gsm8k"),
    "arc_easy": BenchSpec("inspect_evals.arc", "arc_easy"),
    "arc_challenge": BenchSpec("inspect_evals.arc", "arc_challenge"),
    "mmlu_0_shot": BenchSpec("inspect_evals.mmlu", "mmlu_0_shot"),
    "commonsense_qa": BenchSpec("inspect_evals.commonsense_qa",
                                "commonsense_qa"),
    "truthfulqa": BenchSpec("inspect_evals.truthfulqa", "truthfulqa"),
    "gpqa_diamond": BenchSpec("inspect_evals.gpqa", "gpqa_diamond"),
    # BFCL single-turn AST subset — established tool-calling bench, pure
    # host-side AST-match scorer (no exec/sandbox); needs the text->tool_call
    # adapter, and the categories pin (see the thunks above).
    "bfcl_ast": BenchSpec("inspect_evals.bfcl", "bfcl",
                          adapter=_bfcl_adapt,
                          default_task_kwargs=_bfcl_ast_kwargs),
    # The SWE-bench A-overlay arm (slice 3): dataset + sandbox + OFFICIAL
    # scorer from inspect_evals; solver + per-sample compose are ours
    # (seon_inspect.swebench_arm via tasks/swe_bench_seon.py) — never the
    # plain pod door.
    "swe_bench_verified": BenchSpec("inspect_evals.swe_bench", "swe_bench",
                                    kind="swebench"),
}


def case1_benches() -> dict[str, BenchSpec]:
    """The registry entries runnable through the pod door (`run_bench`)."""
    return {n: s for n, s in BENCHES.items() if s.kind == "case1"}


def save_eval_logs(logs: Any, evidence_dir: Path) -> list[str]:
    """Copy a run's inspect `.eval` log files into `<evidence_dir>/inspect-logs/`.

    Evidence-retention fix (2026-07-04): run dirs under `evals/runs/` must
    carry the run's own `.eval` logs. Missing or uncopyable logs now reject
    finalization instead of silently accepting an unreproducible score."""
    manifest = source_admission.finalize_native_logs(
        logs, evidence_dir=evidence_dir)
    return [row["retained_path"] for row in manifest]


def _bench_identity(name: str, spec: BenchSpec) -> dict[str, str]:
    return {
        "name": name,
        "module": spec.module,
        "attribute": spec.attr,
        "kind": spec.kind,
    }


def load_bench_task(
    name: str,
    *,
    _admission: dict[str, Any] | None = None,
    **task_kwargs: Any,
) -> Task:
    """Load a standard inspect_evals Task by catalog name (its own dataset+scorer).

    Pod-door (case1) benches only; a registered bench of another kind names
    its own driver in the error."""
    spec = BENCHES.get(name)
    if spec is None:
        raise KeyError(
            f"{name!r} not in the assessed bench registry {sorted(BENCHES)}; "
            "code-exec (humaneval/mbpp) + web-tool (gaia) benches are the "
            "case-2/mvm tier — not runnable through the pod door."
        )
    if spec.kind != "case1":
        raise KeyError(
            f"{name!r} is a {spec.kind!r} arm, not a pod-door bench — drive "
            "it through its own driver (swebench: tasks/swe_bench_seon.py)."
        )
    if _admission is None:
        source_admission.verify_sources(_bench_identity(name, spec))
    defaults = spec.default_task_kwargs
    merged = {**(defaults() if defaults else {}), **task_kwargs}
    return spec.task_fn()(**merged)


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


def _eval_admitted_task(
    task: Task,
    admission: dict[str, Any],
    *,
    solver_override: Solver | Sequence[Solver] | None = None,
    evidence_dir: Path | None = None,
    before_finalize: Callable[[], None] | None = None,
    **eval_kwargs: Any,
):
    """Execute and finalize one already-admitted Task through Inspect."""
    def listed_logs(log_dir: str | None) -> set[str]:
        infos = list_eval_logs(log_dir) if log_dir is not None else list_eval_logs()
        return {str(info.name) for info in infos}

    md = dict(eval_kwargs.pop("metadata", None) or {})
    md["seon_source_admission"] = admission
    call_kwargs = {
        "model": eval_kwargs.pop("model", "mockllm/model"),
        "metadata": md,
        **eval_kwargs,
    }
    if solver_override is not None:
        call_kwargs["solver"] = solver_override
    log_dir = call_kwargs.get("log_dir")
    listed_before = listed_logs(log_dir)
    try:
        logs = inspect_eval(task, **call_kwargs)
    except BaseException:
        interrupted_logs = sorted(listed_logs(log_dir) - listed_before)
        if interrupted_logs:
            source_admission.finalize_native_logs(
                interrupted_logs,
                evidence_dir=evidence_dir,
                expected_admission=admission,
                require_success=False,
            )
        raise
    if before_finalize is not None:
        before_finalize()
    if not logs:
        terminal_logs = sorted(listed_logs(log_dir) - listed_before)
        if terminal_logs:
            source_admission.finalize_native_logs(
                terminal_logs,
                evidence_dir=evidence_dir,
                expected_admission=admission,
                require_success=False,
            )
            raise source_admission.SourceAdmissionError(
                "evidence finalization: Inspect returned no accepted native "
                "log; retained its published terminal evidence")
    source_admission.finalize_native_logs(
        logs,
        evidence_dir=evidence_dir,
        expected_admission=admission,
    )
    return logs


def run_native_task(
    identity: dict[str, str],
    task_factory: Callable[..., Task],
    *,
    task_kwargs: dict[str, Any] | None = None,
    evidence_dir: Path | None = None,
    target_snapshot: Callable[[], dict[str, Any]] | None = None,
    **eval_kwargs: Any,
):
    """Run one Seon-native Task with mandatory admission and finalization.

    Native tasks own their existing dataset, solver, scorer, and static target.
    This boundary only admits selected sources before task construction,
    enforces the one-pod serial ceiling, stamps the exact run identity, and
    reopens every retained native log. It is the native sibling of
    ``run_bench`` rather than a second evaluation harness.
    """
    admission = source_admission.verify_sources(identity)
    if target_snapshot is None:
        raise ValueError(
            "target_snapshot is required for an admitted native live run")
    target_start = target_snapshot()
    task = task_factory(_admission=admission, **(task_kwargs or {}))
    md = dict(eval_kwargs.pop("metadata", None) or {})
    md["seon_static_target"] = target_start

    def verify_static_target() -> None:
        if target_snapshot() != target_start:
            raise RuntimeError("static target identity changed during native run")

    return _eval_admitted_task(
        task,
        admission,
        evidence_dir=evidence_dir,
        max_samples=config.POD_MAX_SAMPLES,
        metadata=md,
        before_finalize=verify_static_target,
        **eval_kwargs,
    )


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
    spec = BENCHES.get(name)
    if spec is None:
        raise KeyError(f"{name!r} not in the assessed bench registry {sorted(BENCHES)}")
    admission = source_admission.verify_sources(_bench_identity(name, spec))
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
        task = load_bench_task(name, _admission=admission, **(task_kwargs or {}))
    # Bench-specific solver bridge (bfcl needs text->tool_call); default =
    # swap_generate. An explicit `adapt=` argument overrides the registry.
    adapt_fn = adapt or (spec.adapter if spec else None) or swap_generate
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
    logs = _eval_admitted_task(
        task,
        admission,
        solver_override=adapt_fn(task.solver, the_solver),
        evidence_dir=evidence_dir,
        limit=limit,
        epochs=epochs,
        max_samples=max_samples,
        **eval_kwargs,
    )
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
