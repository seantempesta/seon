"""One real offline Inspect log carries admitted source identity."""

from pathlib import Path

import pytest
from inspect_ai import Task
from inspect_ai.dataset import Sample
from inspect_ai.log import read_eval_log, write_eval_log
from inspect_ai.scorer import match
from inspect_ai.solver import solver

from seon_inspect import catalog, scorecard, solver as solver_module, source_admission


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


@pytest.mark.parametrize(
    "terminal",
    [
        {"timed_out": True, "closed_reason": "timeout"},
        {"timed_out": False, "closed_reason": ":error"},
        {"timed_out": False, "closed_reason": ":quiesced"},
    ],
)
def test_admitted_static_bench_publishes_no_capability_score_for_terminal_state(
    monkeypatch, tmp_path, terminal
):
    identity = {
        "schema_version": 2,
        "bench": {"name": "gsm8k", "kind": "case1"},
    }
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources", lambda _selected: identity)
    monkeypatch.setattr(
        solver_module,
        "pod_run",
        lambda *_args, **_kwargs: {
            "agent_id": "agent-1",
            "reply": "accepted",
            **terminal,
        },
    )
    task = Task(
        dataset=[Sample(id="terminal", input="return accepted",
                        target="accepted")],
        solver=[],
        scorer=match(),
    )

    logs = catalog.run_bench(
        "gsm8k",
        task=task,
        evidence_dir=tmp_path / "evidence",
        limit=1,
        log_dir=str(tmp_path / "logs"),
        display="none",
        fail_on_error=False,
    )

    native = read_eval_log(logs[0].location)
    sample = native.samples[0]
    assert native.eval.metadata["seon_source_admission"] == identity
    assert sample.error is not None
    assert not sample.scores
    assert sample.metadata["pod_closed_reason"] == terminal["closed_reason"]


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
        target_snapshot=lambda: {"artifact": "stable"},
        log_dir=str(tmp_path / "logs"),
        display="none",
    )
    retained = evidence / "inspect-logs" / Path(logs[0].location).name
    native = read_eval_log(str(retained))
    assert native.status == "success"
    assert native.eval.metadata["seon_source_admission"] == identity
    assert native.metadata["seon_source_admission_end"] == identity
    assert native.metadata["seon_static_target_end"] == {
        "artifact": "stable"}
    assert native.log_updates[-1].provenance.author == "seon_inspect.catalog"


def test_classified_failure_roundtrips_with_native_evidence(monkeypatch, tmp_path):
    identity = {
        "schema_version": 2,
        "bench": {"name": "database_workflow", "kind": "seon-native"},
    }
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources", lambda selected: identity)

    def task_factory(_admission):
        return Task(
            dataset=[Sample(id="native-failure", input="return accepted",
                            target="different")],
            solver=[_fixed_reply()],
            scorer=match(),
        )

    evidence = tmp_path / "evidence"
    logs = catalog.run_native_task(
        identity["bench"],
        task_factory,
        evidence_dir=evidence,
        target_snapshot=lambda: {"artifact": "stable"},
        log_dir=str(tmp_path / "logs"),
        display="none",
    )
    log = logs[0]
    scorecard.annotate_failure_classification(
        log,
        sample_id="native-failure",
        epoch=1,
        score_name="match",
        classification="model reasoning failure",
        author="seon-operator",
        reason="Reviewed retained prompt, reply, and scorer evidence",
    )
    write_eval_log(log)
    manifest = source_admission.finalize_native_logs(
        logs, evidence_dir=evidence, expected_admission=identity)
    native = read_eval_log(manifest[0]["retained_path"])
    score = native.samples[0].scores["match"]

    assert score.value == "I"
    assert score.metadata["seon_failure_classification"] == \
        "model reasoning failure"
    assert score.history[-1].provenance.author == "seon-operator"
    assert native.eval.metadata["seon_source_admission"] == identity
    assert native.metadata["seon_source_admission_end"] == identity
    assert native.metadata["seon_static_target_end"] == {
        "artifact": "stable"}
