"""Task wiring proofs: mock worker + REAL oracles under a real inspect eval().

Asserts the REDUCED `mean` metric per run — the fixtures encode known outcomes
(the harness must DISCRIMINATE; the model's numbers come from the GPU).
`pass_at_k` alone would hide the arm deltas (at-least-one-of-k saturates).
"""

import pytest
from inspect_ai import eval as inspect_eval

from seon_inspect.tasks.e1_spec_fn import e1_spec_fn
from seon_inspect.tasks.ladder_lift import ladder_lift
from seon_inspect.tasks.long_term_planning import long_term_planning
from seon_inspect.tasks.skill_lift import skill_lift


def _mean_accuracy(task_obj):
    log = inspect_eval(task_obj, model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    for sc in log.results.scores:
        if sc.reducer == "mean":
            return sc.metrics["accuracy"].value
    raise AssertionError("no mean reducer in results")


@pytest.mark.parametrize("arm,expected", [
    ("arm1_guided_refine", 1.0),   # refine converges on guided_wins
    ("arm3_naked_oracle", 0.25),   # the naked floor + syntax-only repair
])
def test_e1_arms_discriminate(arm, expected):
    got = _mean_accuracy(e1_spec_fn(arm=arm, endpoint="mock:guided_wins"))
    assert got == pytest.approx(expected)


@pytest.mark.parametrize("condition,expected", [
    ("control", 0.0),     # the hallucination floor
    ("treatment", 1.0),   # skill-in-context
])
def test_skill_lift_discriminates(condition, expected):
    got = _mean_accuracy(skill_lift(condition=condition, endpoint="mock:skill_lift"))
    assert got == pytest.approx(expected)


@pytest.mark.parametrize("ladder,expected", [
    (True, 1.0),    # oracle-terminated refine_loop
    (False, 0.25),  # free-gen floor incl. the transducer false-positive
])
def test_ladder_lift_discriminates(ladder, expected):
    got = _mean_accuracy(ladder_lift(ladder=ladder, endpoint="mock:ladder"))
    assert got == pytest.approx(expected)


@pytest.mark.parametrize("arm,expected", [
    ("pretransacted", 1.0),
    ("model_authored", 1.0),
    ("no_plan", 0.0),
])
def test_planning_preload_experiment_arms_use_real_scorer(arm, expected):
    task = long_term_planning(endpoint=f"mock:experiment:{arm}")
    assert _mean_accuracy(task) == pytest.approx(expected)
