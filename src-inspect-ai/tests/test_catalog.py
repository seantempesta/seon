"""Offline tests for the standard-bench catalog adapter (no pod, no network)."""

import pytest

from seon_inspect import catalog


def test_case1_catalog_is_data():
    # adding a bench is a data edit; every entry is (module, attr)
    assert "gsm8k" in catalog.CASE1_BENCHES
    for name, spec in catalog.CASE1_BENCHES.items():
        assert isinstance(spec, tuple) and len(spec) == 2, name
        mod, attr = spec
        assert mod.startswith("inspect_evals."), name


def test_unknown_bench_names_case2_tier():
    # code-exec / web-tool benches are NOT case-1 and must fail loud with guidance
    with pytest.raises(KeyError) as e:
        catalog.load_bench_task("humaneval")
    assert "case-2" in str(e.value)


def test_run_bench_sets_solve_url_pod_agnostic(monkeypatch):
    # pod-agnostic: solve_url flows to SEON_SOLVE_URL; no acme hardcoding anywhere
    import os
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: captured.setdefault("name", name))
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda: "SOLVER")
    catalog.run_bench("gsm8k", solve_url="http://example.test:9999/solve", limit=3, epochs=2)
    assert os.environ["SEON_SOLVE_URL"] == "http://example.test:9999/solve"
    assert captured["kw"]["limit"] == 3 and captured["kw"]["epochs"] == 2
    assert captured["kw"]["max_samples"] == 1  # serial for the pod async wake path
