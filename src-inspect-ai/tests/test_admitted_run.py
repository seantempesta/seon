"""One real offline Inspect log carries admitted source identity."""

from pathlib import Path

from inspect_ai import Task
from inspect_ai.dataset import Sample
from inspect_ai.log import read_eval_log
from inspect_ai.scorer import match
from inspect_ai.solver import solver

from seon_inspect import catalog


@solver
def _fixed_reply():
    async def solve(state, generate):
        state.output.completion = "accepted"
        return state
    return solve


def test_run_bench_retains_native_log_with_source_identity(monkeypatch, tmp_path):
    identity = {
        "schema_version": 1,
        "sources": {
            "inspect_ai": {"revision": "a" * 40},
            "inspect_evals": {"revision": "b" * 40},
        },
        "providers": {"openai": {"version": "2.45.0"}},
        "artifacts": {
            "python_lock": {"sha256": "c" * 64},
            "datasets_lock": {"sha256": "d" * 64},
        },
    }

    def admit(bench):
        return {**identity, "bench": dict(bench)}

    monkeypatch.setattr(catalog.source_admission, "verify_sources", admit)
    task = Task(
        dataset=[Sample(id="admission-1", input="return accepted",
                        target="accepted")],
        solver=[],
        scorer=match(),
    )
    evidence = tmp_path / "evidence"
    logs = catalog.run_bench(
        "gsm8k",
        task=task,
        adapt=lambda task_solver, pod_solver: [_fixed_reply()],
        evidence_dir=evidence,
        limit=1,
        log_dir=str(tmp_path / "logs"),
        display="none",
    )
    assert len(logs) == 1
    retained = evidence / "inspect-logs" / Path(logs[0].location).name
    assert retained.is_file()
    native = read_eval_log(str(retained))
    admitted = native.eval.metadata["seon_source_admission"]
    assert admitted["bench"]["name"] == "gsm8k"
    assert admitted["sources"]["inspect_ai"]["revision"] == "a" * 40


def test_run_native_task_retains_log_with_exact_admission(monkeypatch, tmp_path):
    identity = {
        "schema_version": 2,
        "bench": {"name": "database_workflow", "kind": "seon-native"},
    }
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources", lambda selected: identity)

    def task_factory(_admission):
        assert _admission == identity
        return Task(
            dataset=[Sample(id="native-1", input="return accepted",
                            target="accepted")],
            solver=[_fixed_reply()],
            scorer=match(),
        )

    evidence = tmp_path / "evidence"
    logs = catalog.run_native_task(
        identity["bench"],
        task_factory,
        evidence_dir=evidence,
        log_dir=str(tmp_path / "logs"),
        display="none",
    )
    retained = evidence / "inspect-logs" / Path(logs[0].location).name
    native = read_eval_log(str(retained))
    assert native.status == "success"
    assert native.eval.metadata["seon_source_admission"] == identity
