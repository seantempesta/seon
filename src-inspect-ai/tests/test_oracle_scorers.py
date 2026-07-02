"""Scorer-vs-REAL-oracles: the tier ladder discriminates every fixture class.

These run against the live bb parse server + node eval bundle (no GPU, no pod).
They are the offline proof from the port, hardened as the regression suite.
"""

import pytest

import seon_inspect.oracle_scorers as o
from seon_inspect.worker_mock import TEXT


@pytest.fixture(scope="session", autouse=True)
def liveness():
    o.assert_oracle_live()


def test_golden_good_is_faithful_and_behavioral():
    sc = o.score_code(o._GOLDEN_GOOD, o._GOLDEN_SPEC)
    assert sc["faithful"] is True
    assert sc["behavioral_pass"] is True


def test_inlined_idiom_is_equally_faithful_but_idiom_distinguishes():
    """Owner correction: style preferences report, never gate — correct code in
    the inlined idiom (no register!, no -request/-response) scores faithful;
    the `idiom` dict is what tells the two variants apart."""
    named = o.score_code(o._GOLDEN_GOOD, o._GOLDEN_SPEC)
    inline = o.score_code(o._GOLDEN_GOOD_INLINE, o._GOLDEN_SPEC)
    assert inline["faithful"] is True
    assert inline["behavioral_pass"] is True
    assert named["idiom"]["all"] is True
    assert inline["idiom"]["all"] is False
    assert inline["idiom"]["register"] is False
    assert inline["idiom"]["map_in_out"] is False


def test_idiom_gates_only_when_task_measures_idiom():
    """skill-lift sets idiom_gates=True — there the idiom IS the measurand."""
    gated = dict(o._GOLDEN_SPEC, idiom_gates=True,
                 expects={"register": True, "malli_schema": True,
                          "map_in_out": True, "namespaced_kw": True})
    assert o.score_code(o._GOLDEN_GOOD_INLINE, gated)["faithful"] is False
    assert o.score_code(o._GOLDEN_GOOD, gated)["faithful"] is True


@pytest.mark.parametrize("kind,failing_tier", [
    ("naked_plain", "spec_absent"),     # bare defn — no :malli/schema at all
    ("hallucinated", "structural"),     # clojure.spec instead of the real API
    ("broken", "parses"),               # unbalanced — parse tier
    ("semantic", "eval_ok"),            # undeclared var — compiles? no: eval tier
    ("vacuous", "vacuous_flag"),        # [:map]/:any — present but rejects nothing
    ("transducer", "behavioral_pass"),  # the live false-positive: evals, wrong CALL
])
def test_fixture_class_fails_its_tier(kind, failing_tier):
    sc = o.score_code(TEXT[kind], o._GOLDEN_SPEC)
    assert sc["faithful"] is False, f"{kind} must not be faithful"
    if failing_tier == "parses":
        assert sc["parses"] is False
    elif failing_tier == "structural":
        assert sc["structural"] is False
    elif failing_tier == "spec_absent":
        assert sc["has_malli"] is False
    elif failing_tier == "eval_ok":
        assert sc["eval_ok"] is False
    elif failing_tier == "vacuous_flag":
        assert sc["vacuous"] is True
    elif failing_tier == "behavioral_pass":
        # the discriminating pair: eval tier passes, behavioral catches it
        assert sc["eval_ok"] is True
        assert sc["behavioral_pass"] is False


def test_known_bad_def_vs_defn_fails_eval_tier():
    bad = o.evalsrv().call({"op": "eval", "code": o._GOLDEN_BAD, "budget-ms": 1500})
    assert bad is not None and bad.get("ok") is False
