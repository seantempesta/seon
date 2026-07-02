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


def test_run_bench_passes_solve_url_pod_agnostic(monkeypatch):
    # pod-agnostic: solve_url flows to the solver as an explicit ARGUMENT (the
    # one channel — no env write side effect); no acme hardcoding anywhere
    import os
    captured = {}
    monkeypatch.delenv("SEON_SOLVE_URL", raising=False)
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: captured.setdefault("name", name))
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: captured.setdefault("solver_kw", kw) or "SOLVER")
    catalog.run_bench("gsm8k", solve_url="http://example.test:9999/solve", limit=3, epochs=2)
    assert captured["solver_kw"]["solve_url"] == "http://example.test:9999/solve"
    assert "SEON_SOLVE_URL" not in os.environ  # env never shadows config
    assert captured["kw"]["limit"] == 3 and captured["kw"]["epochs"] == 2
    # per-POD serial ceiling (calibration 2026-07-02): defaults to config
    assert captured["kw"]["max_samples"] == catalog.config.POD_MAX_SAMPLES == 1


def test_run_bench_max_samples_overridable(monkeypatch):
    # per-run override for pod POOLS — the knob exists, the default stays 1
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: "TASK")
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: "SOLVER")
    catalog.run_bench("gsm8k", max_samples=4)
    assert captured["kw"]["max_samples"] == 4


def test_solve_url_resolved_at_call_time(monkeypatch):
    # the import-time-read bug: SEON_SOLVE_URL set AFTER import must still win
    from seon_inspect import config
    monkeypatch.setenv("SEON_SOLVE_URL", "http://late.test:1234/solve")
    assert config.solve_url() == "http://late.test:1234/solve"
    assert config.solve_url("http://arg.test/solve") == "http://arg.test/solve"
    monkeypatch.delenv("SEON_SOLVE_URL")
    assert config.solve_url() == config.DEFAULT_SOLVE_URL
