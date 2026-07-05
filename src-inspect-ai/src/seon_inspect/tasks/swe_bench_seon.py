"""SWE-bench composition tasks (slice 3): null-run proofs + the Seon arm.

Thin `@task` wrappers over `inspect_evals.swe_bench` — dataset, sandbox
lifecycle, and the OFFICIAL scorer come whole from their task; we swap only
the solver (`task_with`) and, per arm, the per-sample compose via their
`sandbox_config` seam (see `seon_inspect.swebench_arm`).

Run these from an env that carries inspect_evals + swebench (the vendored
inspect-evals checkout; e.g. tmp/slice2-venv with
PYTHONPATH=src-inspect-ai/src) — the import is lazy so this package's own
offline test env never needs them:

    inspect eval src-inspect-ai/src/seon_inspect/tasks/swe_bench_seon.py@swe_bench_null \
      --model mockllm/model --sample-id sympy__sympy-22914 -T mounted=false
    …@swe_bench_null … -T mounted=true      # overlay mounted, still no Seon
    …@swe_bench_seon --model mockllm/model --sample-id sympy__sympy-22914

`--model mockllm/model` satisfies inspect's model requirement; neither the
noop solver nor the pod solver ever calls it — the Seon arm's REAL model
provenance is runtime-derived from the door's `model_config` (scorecard).
"""

from __future__ import annotations

from inspect_ai import Task, task, task_with


def _swe_bench(**kwargs) -> Task:
    from inspect_evals.swe_bench import swe_bench  # lazy (see module doc)

    return swe_bench(**kwargs)


@task
def swe_bench_null(mounted: bool = False) -> Task:
    """Null-run: no-op solver, repo untouched, OFFICIAL scorer verdict.

    `mounted=False` = the vanilla official compose; `mounted=True` = the
    A-overlay mounts added (still NO Seon boot). Identical verdicts across
    the pair = the overlay provably doesn't perturb the oracle (§8 slice 3
    acceptance (a))."""
    from seon_inspect.swebench_arm import noop_solver, overlay_sandbox_config

    sandbox_config = overlay_sandbox_config(boot=False) if mounted else None
    return task_with(_swe_bench(sandbox_config=sandbox_config),
                     solver=noop_solver())


@task
def swe_bench_seon(timeout_s: int | None = None) -> Task:
    """The Seon arm: cluster booted INSIDE the instance container (A-overlay),
    the ROOT agent driven through POST /agents/run, OFFICIAL scorer unchanged."""
    from seon_inspect.swebench_arm import (
        SWEBENCH_RUN_TIMEOUT_S,
        overlay_sandbox_config,
        seon_swebench_solver,
    )

    return task_with(
        _swe_bench(sandbox_config=overlay_sandbox_config(boot=True)),
        solver=seon_swebench_solver(timeout_s or SWEBENCH_RUN_TIMEOUT_S))
