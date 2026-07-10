"""op:"cursor" — the cursor-intelligence oracle (typeahead P1), offline.

Real bb oracle over the pipe (the test_oracle_live pattern). Covers the
design-doc contract: slot-kind, locals, ranked+typed candidates,
delimiter repair + balance-delta, and the degenerate inputs. Needs
clj-kondo on PATH (the bb pod route) for the analysis leg.
"""

import shutil

import pytest

pytestmark = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("clj-kondo") is None,
    reason="bb/clj-kondo not on PATH")


@pytest.fixture(scope="module")
def oracle():
    from seon_diffusion.oracle import Oracle
    o = Oracle()                    # ctor IS the liveness gate
    yield o
    o.close()


def test_balanced_draft_untouched(oracle):
    code = "(let [x 1] (inc x))"
    r = oracle.cursor(code, 16)     # cursor on the x inside (inc x)
    assert r["balance-delta"] == 0
    assert r["repaired-text"] == code
    assert r["clean"] is True
    assert r["slot-kind"]["kind"] == "arg"
    assert r["slot-kind"]["head"] == "inc"
    assert r["slot-kind"]["arg-position"] == 1
    assert "x" in r["locals"]


def test_unbalanced_draft_repaired(oracle):
    r = oracle.cursor("(let [alpha 1] (inc alpha", 25)
    assert r["balance-delta"] == 2
    assert r["repaired-text"] == "(let [alpha 1] (inc alpha))"
    assert r["clean"] is True
    assert "alpha" in r["locals"]


def test_map_key_slot(oracle):
    code = '(todo/add! {:my.plan/title "x", :my.p'
    r = oracle.cursor(code, len(code))
    sk = r["slot-kind"]
    assert sk["kind"] == "map-key"
    assert sk["head"] == "todo/add!"
    assert sk["arg-position"] == 1
    # buffer keywords matching the typed :my.p prefix, minus the token itself
    names = [c["name"] for c in r["candidates"]]
    assert ":my.plan/title" in names
    assert ":my.p" not in names
    assert all(c["type"] == "keyword" for c in r["candidates"])
    # odd map: delimiters balance ("})") but the form stays unparseable
    assert r["balance-delta"] == 2
    assert r["repaired-text"].endswith("})")
    assert r["clean"] is False


def test_call_head_slot(oracle):
    r = oracle.cursor("(as", 3)
    assert r["slot-kind"]["kind"] == "head"
    cands = {c["name"]: c["type"] for c in r["candidates"]}
    assert cands.get("assoc") == "core"
    assert all(n.startswith("as") for n in cands)


def test_locals_in_scope_at_cursor(oracle):
    code = ("(defn process [items {:keys [limit] :as opts}]\n"
            "  (let [sorted (sort items)]\n"
            "    (take limit ")
    r = oracle.cursor(code, len(code))
    assert {"sorted", "items", "limit", "opts"} <= set(r["locals"])
    assert r["slot-kind"] == {"kind": "arg", "head": "take", "arg-position": 2}
    # ladder: every local outranks every keyword/core candidate
    types = [c["type"] for c in r["candidates"]]
    assert set(types[: types.index("keyword")]) == {"local"}
    local_names = {c["name"] for c in r["candidates"] if c["type"] == "local"}
    assert {"sorted", "limit"} <= local_names


def test_empty_string(oracle):
    r = oracle.cursor("", 0)
    assert r["slot-kind"]["kind"] == "top-level"
    assert r["balance-delta"] == 0
    assert r["repaired-text"] == ""


def test_cursor_past_end_clamps(oracle):
    r = oracle.cursor("(+ 1 2)", 999)
    assert r["slot-kind"]["kind"] == "top-level"
    assert r["repaired-text"] == "(+ 1 2)"
    assert r["balance-delta"] == 0


def test_bad_request_is_in_band(oracle):
    r = oracle.call({"op": "cursor", "code": "(+ 1 2)", "cursor": "nope"})
    assert r["error"]["kind"] == "bad-request"


def test_template_absent_in_p1(oracle):
    r = oracle.cursor("(assoc m ", 9)
    assert r["template"] is None
