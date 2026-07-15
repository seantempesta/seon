"""Native Inspect task proofs for frozen shell, file, and web rows."""

from __future__ import annotations

from pathlib import Path

import anyio
import pytest
from inspect_ai import eval as inspect_eval
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect import catalog
from seon_inspect.generators import generate_rows, rows_jsonl_bytes
from seon_inspect.tasks import frozen_tool_rows as tasks


ADMISSION = {"schema_version": 2, "bench": {"name": "test"}}


def _model_server_identity():
    revision = "c" * 40
    request_model = f"/cache/snapshots/{revision}"
    return {
        "schema_version": 1,
        "implementation": "mlx-lm",
        "endpoint": "http://127.0.0.1:18081/v1/chat/completions",
        "process": {"pid": 123, "start_instant": "instant",
                    "argv_sha256": "a" * 64},
        "runtime": {"module_sha256": "b" * 64,
                    "packages": {"mlx-lm": "0.31.3"}},
        "artifact": {
            "mechanism": "huggingface-snapshot",
            "request_model": request_model,
            "revision": revision,
            "manifest_sha256": "d" * 64,
            "size_bytes": 1,
            "quantization": "4bit",
            "config_sha256": "e" * 64,
        },
        "response": {
            "model": request_model,
            "system_fingerprint": "mlx-fixture",
        },
    }


def _completed_pod_result(reply: str):
    identity = _model_server_identity()
    final = {"database_id": "db-1", "branch": "db",
             "commit_id": "final", "t": 30}
    attempt = {
        "turn_id": "turn-1",
        "ordinal": 0,
        "coordinate": {**final, "commit_id": "attempt", "t": 20},
        "coordinate_valid": True,
        "provider": "deepseek",
        "adapter": "openai-compat",
        "requested_model": identity["artifact"]["request_model"],
        "endpoint": identity["endpoint"],
        "adapter_timeout_ms": 30_000,
        "outer_timeout_ms": 45_000,
        "stream": False,
        "response_model": identity["response"]["model"],
        "system_fingerprint": identity["response"]["system_fingerprint"],
        "outcome": "success",
    }
    return {
        "agent_id": "test-agent",
        "reply": reply,
        "turns": 1,
        "evals": 1,
        "timed_out": False,
        "closed_reason": ":completed",
        "database_coordinate": final,
        "turn_evidence": [{"turn_id": "turn-1"}],
        "model_transport_evidence": {
            "status": "inline",
            "transport_drift": False,
            "turns": [{"turn_id": "turn-1", "attempts": [attempt]}],
        },
    }


def _write_expected_workspace(state: TaskState) -> None:
    root = Path(state.metadata["workspace"])
    for check in state.metadata["oracle"]["checks"]:
        path = root / check["path"]
        if check.get("absent"):
            path.unlink(missing_ok=True)
        elif "equals" in check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(check["equals"])


@solver
def fake_pod(row: str, *, touch_workspace: bool = True,
             infrastructure: str | None = None):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        assert "{workspace}" not in state.user_prompt.text
        assert "{fixture_url}" not in state.user_prompt.text
        if row in tasks.WORKSPACE_ROWS and touch_workspace:
            _write_expected_workspace(state)
        if row == "web_fetch":
            state.output.completion = state.metadata["oracle"]["answer"]
        state.metadata.update({
            "pod_agent_id": "test-agent",
            "pod_turns": 1,
            "pod_evals": 1,
            "pod_timed_out": infrastructure == "timeout",
            "pod_closed_reason": ":error" if infrastructure == "error"
                                 else ":completed",
            "pod_database_coordinate": {"basis_t": 42},
            "pod_turn_evidence": [{"turn_id": "turn-1"}],
        })
        return state

    return solve


def _run(monkeypatch, tmp_path, row, position, *, touch_workspace=True,
         infrastructure=None, fail_on_error=False):
    if infrastructure is None:
        monkeypatch.setattr(
            tasks,
            "seon_pod_solver",
            lambda **_kwargs: fake_pod(
                row,
                touch_workspace=touch_workspace,
            ),
        )
    else:
        closed_reason = {
            "timeout": "timeout",
            "error": ":error",
            "quiesced": ":quiesced",
        }[infrastructure]
        monkeypatch.setattr(
            "seon_inspect.solver.pod_run",
            lambda *_args, **_kwargs: {
                "agent_id": "test-agent",
                "reply": "would otherwise score",
                "timed_out": infrastructure == "timeout",
                "closed_reason": closed_reason,
            },
        )
    task = tasks.frozen_tool_rows(
        row=row,
        seed=1,
        positions=str(position),
        cluster_url="http://127.0.0.1:7994",
        workspaces_root=str(tmp_path / "workspaces"),
        _admission=ADMISSION,
    )
    return inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path / "logs"),
        fail_on_error=fail_on_error,
        max_samples=1,
    )[0]


@pytest.mark.parametrize(
    "row,position",
    [("shell_use", 1), ("file_edit", 0), ("web_fetch", 0)],
)
def test_each_row_scores_through_native_inspect(
    monkeypatch, tmp_path, row, position
):
    log = _run(monkeypatch, tmp_path, row, position)
    assert log.status == "success", log.error
    assert Path(log.location.removeprefix("file://")).is_file()
    assert len(log.samples) == 1
    sample = log.samples[0]
    assert sample.id == generate_rows(row, 1, position + 1)[position]["id"]
    assert sample.metadata["pod_agent_id"] == "test-agent"
    assert sample.metadata["pod_database_coordinate"] == {"basis_t": 42}
    assert sample.metadata["pod_turn_evidence"] == [{"turn_id": "turn-1"}]
    assert sample.metadata["seon_source_admission"] == ADMISSION
    rendered_messages = "\n".join(message.text for message in sample.messages)
    assert "{workspace}" not in rendered_messages
    assert "{fixture_url}" not in rendered_messages
    assert next(iter(sample.scores.values())).value == "C"
    assert not Path(sample.metadata["workspace"]).exists()


@pytest.mark.parametrize(
    "position,expected_id",
    [(8, "file_edit-seed1-008"), (9, "file_edit-seed1-009")],
)
def test_filesystem_candidates_use_native_task_and_workspace_scorer(
    monkeypatch, tmp_path, position, expected_id
):
    log = _run(monkeypatch, tmp_path, "file_edit", position)
    assert log.status == "success", log.error
    sample = log.samples[0]
    assert sample.id == expected_id
    assert sample.metadata["row"] == "file_edit"
    assert next(iter(sample.scores.values())).value == "C"
    assert not Path(sample.metadata["workspace"]).exists()


def test_untouched_workspace_is_a_native_incorrect_score(monkeypatch, tmp_path):
    log = _run(
        monkeypatch, tmp_path, "shell_use", 1, touch_workspace=False
    )
    assert log.status == "success", log.error
    score = next(iter(log.samples[0].scores.values()))
    assert score.value == "I"
    assert score.metadata["failures"]


@pytest.mark.parametrize("infrastructure", ["timeout", "error", "quiesced"])
def test_infrastructure_close_invalidates_instead_of_scoring(
    monkeypatch, tmp_path, infrastructure
):
    log = _run(
        monkeypatch,
        tmp_path,
        "web_fetch",
        0,
        infrastructure=infrastructure,
        fail_on_error=True,
    )
    assert log.status == "error"
    assert len(log.samples) == 1
    assert log.samples[0].error is not None
    assert not log.samples[0].scores


def test_completed_default_solver_reaches_the_unchanged_static_scorer(
    monkeypatch, tmp_path
):
    expected = generate_rows("web_fetch", 1, 1)[0]["metadata"]["oracle"][
        "answer"]
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources", lambda _identity: ADMISSION)
    monkeypatch.setattr(
        "seon_inspect.solver.pod_run",
        lambda *_args, **_kwargs: _completed_pod_result(expected),
    )

    log = catalog.run_native_task(
        tasks._bench_identity("web_fetch"),
        tasks.frozen_tool_rows,
        task_kwargs={
            "row": "web_fetch",
            "seed": 1,
            "positions": "0",
            "cluster_url": "http://127.0.0.1:7994",
            "workspaces_root": str(tmp_path / "workspaces"),
        },
        target_snapshot=lambda: {"artifact": "stable"},
        model_server_snapshot=_model_server_identity,
        evidence_dir=tmp_path / "evidence",
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path / "logs"),
    )[0]

    assert log.status == "success", log.error
    assert next(iter(log.samples[0].scores.values())).value == "C"


def test_selection_is_an_exact_projection_without_generator_drift():
    generated = generate_rows("shell_use", 1, 8)
    selected = tasks.selected_rows("shell_use", 1, "7,0,3")
    assert selected == [generated[7], generated[0], generated[3]]
    assert rows_jsonl_bytes(generate_rows("shell_use", 1, 8)) == \
        rows_jsonl_bytes(generated)


def test_filesystem_comparison_positions_reuse_one_generator_projection():
    generated = generate_rows("file_edit", 1, 10)
    selected = tasks.selected_rows("file_edit", 1, "8,1,9,4")
    assert selected == [
        generated[8],  # F1 experimental candidate
        generated[1],  # F2 existing frozen development row
        generated[9],  # F3 experimental candidate
        generated[4],  # F4 existing frozen development row
    ]


@pytest.mark.parametrize("positions", ["", "-1", "1,1", "wat"])
def test_invalid_positions_fail_loud(positions):
    with pytest.raises(ValueError):
        tasks.selected_rows("shell_use", 1, positions)


def test_static_cluster_url_is_mandatory():
    with pytest.raises(ValueError, match="cluster_url is required"):
        tasks.frozen_tool_rows(
            row="shell_use", positions="0", _admission=ADMISSION)


def test_explicit_static_cluster_url_is_threaded_to_the_pod_solver(
    monkeypatch, tmp_path
):
    captured = {}

    def make_pod(**kwargs):
        captured.update(kwargs)
        return fake_pod("shell_use")

    monkeypatch.setattr(tasks, "seon_pod_solver", make_pod)
    tasks.frozen_tool_rows(
        row="shell_use",
        seed=1,
        positions="0",
        cluster_url="http://127.0.0.1:7994",
        timeout_s=17,
        workspaces_root=str(tmp_path),
        _admission=ADMISSION,
    )
    assert captured == {
        "cluster_url": "http://127.0.0.1:7994",
        "timeout_s": 17,
    }


def test_task_serializes_samples_even_if_inspect_allows_concurrency(
    monkeypatch, tmp_path
):
    active = 0
    maximum = 0

    @solver
    def observing_pod():
        async def solve(state: TaskState, generate: Generate) -> TaskState:
            nonlocal active, maximum
            active += 1
            maximum = max(maximum, active)
            try:
                await anyio.sleep(0.03)
                _write_expected_workspace(state)
                state.metadata.update({
                    "pod_agent_id": "test-agent",
                    "pod_timed_out": False,
                    "pod_closed_reason": ":completed",
                })
                return state
            finally:
                active -= 1

        return solve

    monkeypatch.setattr(tasks, "seon_pod_solver", lambda **_kw: observing_pod())
    task = tasks.frozen_tool_rows(
        row="shell_use",
        seed=1,
        positions="0,1",
        cluster_url="http://127.0.0.1:7994",
        workspaces_root=str(tmp_path / "workspaces"),
        _admission=ADMISSION,
    )
    log = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path / "logs"),
        max_samples=2,
    )[0]
    assert log.status == "success", log.error
    assert maximum == 1


def test_task_admits_source_before_dataset_construction(monkeypatch):
    admitted = {"schema_version": 2, "bench": {"name": "admitted"}}
    seen = []
    monkeypatch.setattr(
        tasks.source_admission, "verify_sources",
        lambda bench: seen.append(bench) or admitted,
    )
    task = tasks.frozen_tool_rows(
        row="shell_use", positions="0",
        cluster_url="http://127.0.0.1:7994",
    )
    assert seen == [tasks._bench_identity("shell_use")]
    assert task.metadata["seon_source_admission"] == admitted
    assert task.dataset[0].metadata["seon_source_admission"] == admitted
