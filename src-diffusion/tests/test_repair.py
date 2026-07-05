"""try_repair logic against a scripted eval session — no node, no model."""

from seon_diffusion.repair import try_repair, undeclared_var, strip_hints, hint_for


class FakeSession:
    """Evals 'ok' iff the code contains none of the known-bad symbols."""

    def __init__(self, bad_to_good):
        self.bad_to_good = bad_to_good
        self.names = list(bad_to_good.values())

    def core_names(self):
        return self.names

    def eval(self, code, budget_ms=None):
        for bad in self.bad_to_good:
            import re
            if re.search(rf"(?<![\w?!*+<>=./-]){re.escape(bad)}(?![\w?!*+<>=./-])", code):
                return {"ok": False,
                        "error": {"kind": "compile",
                                  "message": f"undeclared-var: Use of undeclared Var cljs.user/{bad}"}}
        return {"ok": True, "value": "#'cljs.user/f"}


def test_single_var_repair():
    ses = FakeSession({"even": "even?"})
    src = "(defn f [xs] (filter even xs))"
    fixed, fixes = try_repair(src, "undeclared Var cljs.user/even", ses)
    assert fixed == "(defn f [xs] (filter even? xs))"
    assert fixes == [("even", "even?")]


def test_boundary_no_partial_replace():
    """'even' inside 'even?' or 'seven' must not be touched."""
    ses = FakeSession({"even": "even?"})
    src = "(defn f [xs] (+ seven (count (filter even xs))))"
    fixed, _ = try_repair(src, "undeclared Var cljs.user/even", ses)
    assert "seven" in fixed
    assert "(filter even? xs)" in fixed


def test_two_var_chain():
    ses = FakeSession({"even": "even?", "redcue": "reduce"})
    src = "(defn f [xs] (redcue + (filter even xs)))"
    fixed, fixes = try_repair(src, "undeclared Var cljs.user/redcue", ses)
    assert "reduce" in fixed and "even?" in fixed
    assert len(fixes) == 2


def test_no_candidate_returns_none():
    ses = FakeSession({"frobnicate": "map"})   # too far for cutoff 0.75
    assert try_repair("(frobnicate 1)", "undeclared Var cljs.user/frobnicate", ses) is None


def test_non_undeclared_error_returns_none():
    ses = FakeSession({})
    assert try_repair("(defn f [x])", "fn-arity: wrong args", ses) is None


def test_undeclared_var_extraction():
    assert undeclared_var("undeclared-var: Use of undeclared Var cljs.user/even") == "even"
    assert undeclared_var("something else") is None


def test_hints_roundtrip():
    err = {"error-kind": "def-vs-defn", "source": "(def f [x] x)"}
    h = hint_for(err)
    assert h.startswith("; fix:")
    assert strip_hints(h + "(defn f [x] x)\n") == "(defn f [x] x)\n"
