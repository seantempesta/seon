"""Live co-located oracle integration — needs bb + node + built bundles.

These are the liveness/statefulness proofs (the voided-E1 lesson: a dead
oracle must fail loud). Skipped when the oracles aren't present.
"""

import shutil

import pytest

from seon_diffusion import config

pytestmark = pytest.mark.skipif(
    shutil.which("bb") is None or shutil.which("node") is None,
    reason="bb/node not on PATH")


@pytest.fixture(scope="module")
def oracle():
    from seon_diffusion.oracle import Oracle
    o = Oracle()                    # ctor IS the liveness gate
    yield o
    o.close()


@pytest.fixture()
def session():
    from seon_diffusion.oracle import EvalSession
    s = EvalSession()               # ctor IS the liveness gate
    yield s
    s.close()


def test_refine_partitions(oracle):
    r = oracle.refine("(defn f [x] x)\n(def g [y] y)\n(defn h [z] (")
    kinds = {e["error-kind"] for e in r["renoise_spans"]}
    assert "def-vs-defn" in kinds
    assert "eof" in kinds
    assert len(r["clamps"]) == 1


def test_phase_grammar(oracle):
    r = oracle.refine("(defn f [x] x)", phase="schemas")
    assert any(e["error-kind"] == "phase-violation" for e in r["renoise_spans"])


def test_session_is_stateful(session):
    assert session.eval("(def xx 41)")["ok"]
    assert session.eval("(inc xx)")["value"] == "42"


def test_session_fails_loud_on_def_typo(session):
    r = session.eval("(def f [x] x)")
    assert not r["ok"]
    assert "def" in r["error"]["message"]


def test_config_resolves_repo():
    root = config.repo_root()
    assert (root / "bin" / "oracle-server").exists()
