"""Offline discrimination gate for every MVP graduation predicate."""

from __future__ import annotations

from copy import deepcopy

import pytest
from inspect_ai import Task, eval as inspect_eval
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.mvp_graduation import (
    CHECK_MUTATIONS,
    CHECKS,
    GOOD,
    SCENARIOS,
    TAXONOMY_MUTATIONS,
    bad_snapshot,
    derived_expectations,
    good_snapshot,
    mvp_graduation_scorer,
)


def _value_at(value, path):
    for key in path:
        value = value[key]
    return value


def _restore_path(value, source, path):
    cursor = value
    source_cursor = source
    for key in path[:-1]:
        cursor = cursor[key]
        source_cursor = source_cursor[key]
    cursor[path[-1]] = deepcopy(source_cursor[path[-1]])


@pytest.mark.parametrize("scenario", SCENARIOS)
def test_every_golden_snapshot_passes_all_checks(scenario):
    result = CHECKS[scenario](deepcopy(GOOD[scenario]))
    assert result["ok"], result
    assert result["failures"] == []
    assert result["failure_taxonomy"] == []
    assert all(result["checks"].values())


@pytest.mark.parametrize("scenario", SCENARIOS)
def test_every_named_check_has_exactly_one_bad_mutation(scenario):
    good_result = CHECKS[scenario](GOOD[scenario])
    assert set(CHECK_MUTATIONS[scenario]) == set(good_result["checks"])

    for name, (path, _replacement) in CHECK_MUTATIONS[scenario].items():
        bad = bad_snapshot(scenario, name)
        result = CHECKS[scenario](bad)
        assert not result["ok"], (scenario, name, result)
        assert result["checks"][name] is False, (scenario, name, result)

        # Restoring the one declared path recreates the exact golden snapshot;
        # the helper cannot hide a second mutation elsewhere in the fixture.
        assert _value_at(bad, path) != _value_at(GOOD[scenario], path)
        _restore_path(bad, GOOD[scenario], path)
        assert bad == GOOD[scenario]


@pytest.mark.parametrize(
    "scenario,taxonomy",
    [(scenario, taxonomy)
     for scenario, mutations in TAXONOMY_MUTATIONS.items()
     for taxonomy in mutations],
)
def test_every_named_failure_taxonomy_is_observed_and_scores_incorrect(
        scenario, taxonomy):
    path, _replacement = TAXONOMY_MUTATIONS[scenario][taxonomy]
    bad = bad_snapshot(scenario, taxonomy, taxonomy=True)
    result = CHECKS[scenario](bad)
    assert not result["ok"], (scenario, taxonomy, result)
    assert taxonomy in result["failure_taxonomy"], result
    assert _value_at(bad, path) != _value_at(GOOD[scenario], path)
    _restore_path(bad, GOOD[scenario], path)
    assert bad == GOOD[scenario]


def test_expectations_are_derived_from_each_snapshot_fixture():
    first = good_snapshot("A", "nonce-first")
    second = good_snapshot("A", "nonce-second")
    assert derived_expectations("A", first) == {
        "amount_cents": first["fixture_expenses"][0]["amount_cents"],
        "vendor": first["fixture_expenses"][0]["vendor"],
    }
    assert derived_expectations("A", first) != derived_expectations("A", second)

    b = good_snapshot("B", "nonce-derived")
    expected_keys = derived_expectations("B", b)["schema_keys"]
    assert f":my.archive/retention-days-nonce-derived" in expected_keys
    assert ":my.hidden/key" not in expected_keys

    c = good_snapshot("C", "nonce-derived")
    assert derived_expectations("C", c)["total_cents"] == sum(
        row["amount_cents"] for row in c["fixture_expenses"])

    d = good_snapshot("D", "nonce-derived")
    expected = derived_expectations("D", d)
    assert expected["max_vendor_total_cents"] == max(
        expected["vendor_totals_cents"].values())


def test_scorers_follow_changed_fixture_values_not_golden_constants():
    a = good_snapshot("A", "nonce-variable")
    expense = a["fixture_expenses"][0]
    expense["amount_cents"] = 4321
    expense["vendor"] = "Changed-nonce-variable"
    rendered = "$43.21 Changed-nonce-variable"
    a["behavior_call"]["result"] = rendered
    a["post_walk"][0]["rendered"] = rendered
    assert CHECKS["A"](a)["ok"]

    b = good_snapshot("B", "nonce-variable")
    b["toolkit_functions"][0]["spec"] = (
        "[:=> [:cat :my.changed/key-nonce-variable] :seon.error/value]")
    b["settled_reply"] = ":my.changed/key-nonce-variable :seon.error/value"
    assert CHECKS["B"](b)["ok"]

    c = good_snapshot("C", "nonce-variable")
    c["fixture_expenses"][0]["amount_cents"] = 100
    c["fixture_expenses"][1]["amount_cents"] = 250
    c["messages"][0]["content"] = "$3.50"
    c["settled_reply"] = "$3.50 was sent."
    assert CHECKS["C"](c)["ok"]


@solver
def _snapshot_solver(snapshot):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        if snapshot is not None:
            state.metadata["database_snapshot"] = deepcopy(snapshot)
        state.output.completion = "offline"
        return state
    return solve


def _inspect_score(scenario, snapshot):
    task = Task(
        dataset=MemoryDataset([
            Sample(input="offline", metadata={"scenario": scenario})]),
        solver=_snapshot_solver(snapshot),
        scorer=mvp_graduation_scorer(),
    )
    log = inspect_eval(task, model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    aggregate = next(score for score in log.results.scores
                     if score.name == "mvp_graduation_scorer")
    sample_score = log.samples[0].scores["mvp_graduation_scorer"]
    return aggregate.metrics["accuracy"].value, sample_score


def test_inspect_scorer_exercises_good_and_bad_rails():
    accuracy, score = _inspect_score("B-control", GOOD["B-control"])
    assert accuracy == pytest.approx(1.0)
    assert score.metadata["ok"] is True

    bad = bad_snapshot("B-control", "no_schema_keys_named")
    accuracy, score = _inspect_score("B-control", bad)
    assert accuracy == pytest.approx(0.0)
    assert score.metadata["failures"] == ["no_schema_keys_named"]
    assert score.metadata["failure_taxonomy"] == ["hallucinated_walk"]


def test_inspect_scorer_fails_closed_without_snapshot():
    accuracy, score = _inspect_score("A", None)
    assert accuracy == pytest.approx(0.0)
    assert score.metadata == {
        "ok": False,
        "checks": {"database_snapshot": False},
        "failures": ["database_snapshot"],
        "failure_taxonomy": [],
    }
