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


def test_run_bench_passes_cluster_url_agnostic(monkeypatch):
    # cluster-agnostic: cluster_url flows to the solver as an explicit ARGUMENT
    # (the one channel — no env write side effect); no acme hardcoding anywhere
    import os
    captured = {}
    monkeypatch.delenv("SEON_CLUSTER_URL", raising=False)
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: captured.setdefault("name", name))
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: captured.setdefault("solver_kw", kw) or "SOLVER")
    catalog.run_bench("gsm8k", cluster_url="http://example.test:9999/agents/run", limit=3, epochs=2)
    assert captured["solver_kw"]["cluster_url"] == "http://example.test:9999/agents/run"
    assert "SEON_CLUSTER_URL" not in os.environ  # env never shadows config
    assert captured["kw"]["limit"] == 3 and captured["kw"]["epochs"] == 2
    # per-POD serial ceiling (one cluster = one sample): defaults to config
    assert captured["kw"]["max_samples"] == catalog.config.POD_MAX_SAMPLES == 1


def test_run_bench_max_samples_overridable(monkeypatch):
    # per-run override for cluster POOLS — the knob exists, the default stays 1
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: "TASK")
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: "SOLVER")
    catalog.run_bench("gsm8k", max_samples=4)
    assert captured["kw"]["max_samples"] == 4


def test_run_bench_per_sample_cluster_mode(monkeypatch):
    # per_sample_cluster=True selects the ephemeral-cluster solver …
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: "TASK")
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    def fake_cluster_solver(**kw):
        captured["cluster_kw"] = kw
        return "CSOLVER"
    monkeypatch.setattr(catalog, "seon_cluster_solver", fake_cluster_solver)
    catalog.run_bench("gsm8k", per_sample_cluster=True, run_timeout_s=120)
    assert captured["cluster_kw"]["timeout_s"] == 120
    assert captured["kw"]["solver"] == "CSOLVER"
    # … and is mutually exclusive with a static cluster_url
    with pytest.raises(ValueError):
        catalog.run_bench("gsm8k", per_sample_cluster=True,
                          cluster_url="http://x.test/agents/run")


def test_cluster_url_resolved_at_call_time(monkeypatch):
    # the import-time-read bug: SEON_CLUSTER_URL set AFTER import must still win
    from seon_inspect import config
    monkeypatch.setenv("SEON_CLUSTER_URL", "http://late.test:1234/agents/run")
    assert config.cluster_url() == "http://late.test:1234/agents/run"
    assert config.cluster_url("http://arg.test/agents/run") == "http://arg.test/agents/run"
    monkeypatch.delenv("SEON_CLUSTER_URL")
    assert config.cluster_url() == config.DEFAULT_CLUSTER_URL
