import copy

import pytest
from inspect_ai import eval as inspect_eval

from seon_inspect.product_scenarios import (CHECKS, SCENARIOS,
                                            run_product_scenario)
from seon_inspect.tasks.product_scenarios import GOOD, bad_snapshot, product_scenario


@pytest.mark.parametrize("scenario", SCENARIOS)
def test_database_snapshot_checks_discriminate(scenario):
    assert CHECKS[scenario](copy.deepcopy(GOOD[scenario]))["ok"]
    result = CHECKS[scenario](bad_snapshot(scenario))
    assert not result["ok"]
    assert result["failures"]


@pytest.mark.parametrize("scenario,outcome,expected", [
    *[(scenario, "good", 1.0) for scenario in SCENARIOS],
    *[(scenario, "bad", 0.0) for scenario in SCENARIOS],
])
def test_native_task_uses_real_scorer(scenario, outcome, expected):
    log = inspect_eval(product_scenario(scenario=scenario, outcome=outcome),
                       model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(score for score in log.results.scores
                 if score.name == "product_scenario_scorer")
    assert score.metrics["accuracy"].value == pytest.approx(expected)


def test_scorer_fails_closed_without_database_snapshot():
    task = product_scenario(scenario="namespace", outcome="good")
    task.solver = []
    log = inspect_eval(task, model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(score for score in log.results.scores
                 if score.name == "product_scenario_scorer")
    assert score.metrics["accuracy"].value == 0.0


def test_live_driver_reuses_root_and_reads_database_after_work():
    calls = []

    def run(prompt, timeout, url, *, agent_id):
        calls.append((prompt, timeout, url, agent_id))
        return {"agent_id": agent_id, "reply": "done"}

    result = run_product_scenario(
        "namespace", "http://pod/agents/run",
        lambda scenario, agent_id: {"scenario": scenario, "agent": agent_id},
        run=run)
    assert [call[3] for call in calls] == ["root", "root"]
    assert result["database_snapshot"] == {
        "scenario": "namespace", "agent": "root"}


def test_restart_driver_changes_url_between_same_agent_runs():
    calls = []

    def run(prompt, timeout, url, *, agent_id):
        calls.append((url, agent_id))
        return {"agent_id": agent_id}

    run_product_scenario(
        "pod_restart", "http://old/agents/run", lambda *_: GOOD["pod_restart"],
        restart_pod=lambda: "http://new/agents/run", run=run)
    assert calls == [("http://old/agents/run", "root"),
                     ("http://new/agents/run", "root")]


def test_restart_driver_refuses_unowned_restart():
    with pytest.raises(RuntimeError, match="owned-target"):
        run_product_scenario(
            "pod_restart", "http://pod/agents/run", lambda *_: {},
            run=lambda *args, **kwargs: {})
