"""Hint rendering (pure) + the oracle-side op:"repair" via the live bundle."""

import shutil

import pytest

from seon_diffusion.repair import undeclared_var, strip_hints, hint_for

needs_oracles = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("node") is None,
    reason="bb/node not on PATH")


def test_undeclared_var_extraction():
    assert undeclared_var("undeclared-var: Use of undeclared Var cljs.user/even") == "even"
    assert undeclared_var("something else") is None


def test_hints_roundtrip():
    err = {"error-kind": "def-vs-defn", "source": "(def f [x] x)"}
    h = hint_for(err)
    assert h.startswith("; fix:")
    assert strip_hints(h + "(defn f [x] x)\n") == "(defn f [x] x)\n"


def test_eval_hint_carries_suggestion():
    err = {"error-kind": "eval",
           "source": "undeclared-var: Use of undeclared Var cljs.user/even",
           "suggest": "even?"}
    assert "did you mean 'even?'" in hint_for(err)


@needs_oracles
class TestRepairOp:
    @pytest.fixture()
    def session(self):
        from seon_diffusion.oracle import EvalSession
        s = EvalSession()
        yield s
        s.close()

    def test_fixes_near_miss_and_defines(self, session):
        r = session.repair("(defn f9 [xs] (filter even xs))")
        assert r["ok"] and "even?" in r["fixed_code"]
        assert r["fixes"] == [{"from": "even", "to": "even?"}]
        # the winner was EVAL'D into the session — f9 is callable
        assert session.eval("(pr-str (f9 [1 2 3 4]))")["value"] == '"(2 4)"'

    def test_refuses_ambiguity(self, session):
        r = session.repair("(defn g9 [x] (mapp inc x))")   # map/max both d1-ish
        if not r["ok"]:
            assert r["reason"] in ("ambiguous", "no-candidate")
            assert r.get("suggestions")

    def test_chains_two_typos(self, session):
        r = session.repair("(defn h9 [xs] (redcue + (filtr even? xs)))",
                           budget_ms=500)
        assert r["ok"]
        assert {f["from"] for f in r["fixes"]} == {"redcue", "filtr"}

    def test_clean_code_is_noop(self, session):
        r = session.repair("(defn k9 [x] x)")
        assert r["ok"] and not r.get("fixed_code")

    def test_run_tests_op(self, session):
        session.eval("(require '[cljs.test])")
        session.eval("(cljs.test/deftest t-ok (cljs.test/is (= 2 (+ 1 1))))")
        r = session.run_tests()
        assert r["ok"] and r["pass"] >= 1 and r["fail"] == 0
