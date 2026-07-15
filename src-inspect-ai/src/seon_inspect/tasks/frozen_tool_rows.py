"""Native Inspect tasks for the frozen shell, file, and web outcome rows.

The generator and scorer remain their existing owners. This namespace only
adapts each selected row into Inspect's ordinary Sample -> Solver -> Scorer
path so the verdict and Seon turn evidence land in one native ``.eval``.
"""

from __future__ import annotations

import copy
import shutil
from pathlib import Path

import anyio
from inspect_ai import Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, Solver, TaskState, solver

from seon_inspect.generators import (
    generate_rows,
    materialize_setup,
    render_input,
    serve_fixtures,
)
from seon_inspect import source_admission
from seon_inspect.solver import seon_pod_solver
from seon_inspect.tool_scorers import fixture_answer_scorer, workspace_scorer

REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_WORKSPACES_ROOT = REPO_ROOT / "tmp" / "inspect-tool-rows"
TOOL_ROWS = ("shell_use", "file_edit", "web_fetch")
WORKSPACE_ROWS = ("shell_use", "file_edit")


def _bench_identity(row: str) -> dict[str, str]:
    return {
        "name": f"frozen_tool_rows:{row}",
        "module": "seon_inspect.tasks.frozen_tool_rows",
        "attribute": "frozen_tool_rows",
        "kind": "seon-native",
    }


def parse_positions(positions: str) -> tuple[int, ...]:
    """Parse a comma-separated, ordered set of non-negative draw positions."""
    try:
        parsed = tuple(int(part.strip()) for part in positions.split(",")
                       if part.strip())
    except ValueError as exc:
        raise ValueError(f"invalid positions {positions!r}") from exc
    if not parsed or any(position < 0 for position in parsed):
        raise ValueError("positions must contain non-negative integers")
    if len(set(parsed)) != len(parsed):
        raise ValueError("positions must not contain duplicates")
    return parsed


def selected_rows(row: str, seed: int, positions: str) -> list[dict]:
    """Project exact positions from the existing deterministic generator."""
    if row not in TOOL_ROWS:
        raise ValueError(f"unknown tool row {row!r}; expected one of {TOOL_ROWS}")
    selected = parse_positions(positions)
    generated = generate_rows(row, seed, max(selected) + 1)
    return [generated[position] for position in selected]


def _sample(row: dict, admission: dict) -> Sample:
    """Adapt one generated row without sharing mutable metadata with Inspect."""
    return Sample(
        id=row["id"],
        input=row["input"],
        target=row["target"],
        metadata={**copy.deepcopy(row["metadata"]),
                  "seon_source_admission": copy.deepcopy(admission)},
    )


def _sample_row(state: TaskState) -> dict:
    """Reconstruct the generator helper input from the current Inspect state."""
    return {
        "id": state.sample_id,
        "input": state.input_text,
        "target": str(state.target),
        "metadata": state.metadata,
    }


@solver
def frozen_tool_row_solver(
    row: str,
    cluster_url: str | None = None,
    timeout_s: int | None = None,
    workspaces_root: str | None = None,
    admission: dict | None = None,
) -> Solver:
    """Materialize one row, render its real location, then drive the Seon pod."""
    if row not in TOOL_ROWS:
        raise ValueError(f"unknown tool row {row!r}; expected one of {TOOL_ROWS}")
    if not cluster_url:
        raise ValueError(
            "cluster_url is required; frozen tool rows run serially against "
            "an explicitly owned static cluster"
        )
    root = Path(workspaces_root) if workspaces_root else DEFAULT_WORKSPACES_ROOT
    pod_solver = seon_pod_solver(cluster_url=cluster_url, timeout_s=timeout_s)
    serial = anyio.Semaphore(1)

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        async with serial:
            workspace = root / state.uuid
            shutil.rmtree(workspace, ignore_errors=True)
            workspace.mkdir(parents=True, exist_ok=True)
            state.metadata["workspace"] = str(workspace)
            sample = _sample_row(state)
            materialize_setup(sample, workspace)

            if row in WORKSPACE_ROWS:
                state.user_prompt.text = render_input(
                    sample, workspace=str(workspace.resolve())
                )
                state = await pod_solver(state, generate)
            else:
                with serve_fixtures(workspace) as fixture_url:
                    state.user_prompt.text = render_input(
                        sample, fixture_url=fixture_url
                    )
                    state = await pod_solver(state, generate)

            if admission is not None:
                end = source_admission.verify_sources(_bench_identity(row))
                if end != admission:
                    raise source_admission.SourceAdmissionError(
                        "frozen tool-row source changed during the sample")
            return state

    return solve


async def cleanup_workspace(state: TaskState) -> None:
    """Remove only the invocation-UUID workspace after Inspect scoring."""
    workspace = (state.metadata or {}).get("workspace")
    if workspace:
        shutil.rmtree(Path(workspace), ignore_errors=True)


@task
def frozen_tool_rows(
    row: str = "shell_use",
    seed: int = 1,
    positions: str = "0",
    cluster_url: str | None = None,
    timeout_s: int | None = None,
    workspaces_root: str | None = None,
    _admission: dict | None = None,
) -> Task:
    """Evaluate exact frozen generator positions through native Inspect logs."""
    admission = _admission or source_admission.verify_sources(
        _bench_identity(row))
    rows = selected_rows(row, seed, positions)
    scorer = fixture_answer_scorer() if row == "web_fetch" else workspace_scorer()
    return Task(
        dataset=MemoryDataset([_sample(sample, admission) for sample in rows]),
        solver=frozen_tool_row_solver(
            row=row,
            cluster_url=cluster_url,
            timeout_s=timeout_s,
            workspaces_root=workspaces_root,
            admission=None if _admission is not None else admission,
        ),
        scorer=scorer,
        cleanup=cleanup_workspace,
        metadata={
            "seon_tool_row": row,
            "seon_generator_seed": seed,
            "seon_generator_positions": list(parse_positions(positions)),
            "seon_source_admission": admission,
        },
    )
