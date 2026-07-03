"""Offline tests for the standard-bench catalog adapter (no pod, no network)."""

import pytest

from seon_inspect import catalog


class _FakeTask:
    """Just enough Task surface for run_bench: a solver chain to swap."""
    solver = []


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
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
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
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: "SOLVER")
    catalog.run_bench("gsm8k", max_samples=4)
    assert captured["kw"]["max_samples"] == 4


def test_run_bench_per_sample_cluster_mode(monkeypatch):
    # per_sample_cluster=True selects the ephemeral-cluster solver …
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    def fake_cluster_solver(**kw):
        captured["cluster_kw"] = kw
        return "CSOLVER"
    monkeypatch.setattr(catalog, "seon_cluster_solver", fake_cluster_solver)
    catalog.run_bench("gsm8k", per_sample_cluster=True, run_timeout_s=120)
    assert captured["cluster_kw"]["timeout_s"] == 120
    assert captured["kw"]["solver"] == ["CSOLVER"]
    # … and is mutually exclusive with a static cluster_url
    with pytest.raises(ValueError):
        catalog.run_bench("gsm8k", per_sample_cluster=True,
                          cluster_url="http://x.test/agents/run")


def test_run_bench_per_sample_records_bundle_identity(monkeypatch):
    # frozen-bundle pinning: the identity at run start lands in the run's
    # own artifacts (EvalLog metadata), and an unchanged bundle returns clean
    from seon_inspect import cluster as cluster_mod
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())

    def fake_eval(*a, **k):
        captured["kw"] = k
        return ["log"]

    monkeypatch.setattr(catalog, "inspect_eval", fake_eval)
    monkeypatch.setattr(catalog, "seon_cluster_solver", lambda **kw: "CSOLVER")
    monkeypatch.setattr(cluster_mod, "bundle_identity", lambda: {"sha256": "aaa"})
    logs = catalog.run_bench("gsm8k", per_sample_cluster=True)
    assert captured["kw"]["metadata"]["seon_bundle"] == {"sha256": "aaa"}
    assert logs == ["log"]


def test_run_bench_bundle_change_is_loud_with_evidence(monkeypatch):
    # a mid-run bundle change (tooling-lane save → cluster-create rebuild)
    # must raise FrozenBundleChanged carrying the logs + both identities —
    # the run classifies as the harness flake, never a capability number
    from seon_inspect import cluster as cluster_mod
    ident = {"v": {"sha256": "aaa"}}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "seon_cluster_solver", lambda **kw: "CSOLVER")
    monkeypatch.setattr(cluster_mod, "bundle_identity", lambda: ident["v"])

    def fake_eval(*a, **k):
        ident["v"] = {"sha256": "bbb"}  # the bundle changes DURING the run
        return ["log"]

    monkeypatch.setattr(catalog, "inspect_eval", fake_eval)
    with pytest.raises(cluster_mod.FrozenBundleChanged) as e:
        catalog.run_bench("gsm8k", per_sample_cluster=True)
    assert e.value.start == {"sha256": "aaa"}
    assert e.value.end == {"sha256": "bbb"}
    assert e.value.logs == ["log"]
    assert "frozen_bundle_changed" in str(e.value)


def test_cluster_url_resolved_at_call_time(monkeypatch):
    # the import-time-read bug: SEON_CLUSTER_URL set AFTER import must still win
    from seon_inspect import config
    monkeypatch.setenv("SEON_CLUSTER_URL", "http://late.test:1234/agents/run")
    assert config.cluster_url() == "http://late.test:1234/agents/run"
    assert config.cluster_url("http://arg.test/agents/run") == "http://arg.test/agents/run"
    monkeypatch.delenv("SEON_CLUSTER_URL")
    assert config.cluster_url() == config.DEFAULT_CLUSTER_URL


def test_swap_generate_keeps_template_swaps_generate():
    # The bench's answer contract lives in its solver chain (prompt_template);
    # swap_generate must keep it and replace ONLY generate() with the pod.
    from inspect_ai.solver import generate, prompt_template

    chain = [prompt_template("Q: {prompt}\nANSWER: $ANSWER"), generate()]
    out = catalog.swap_generate(chain, "POD")
    assert out[-1] == "POD" and len(out) == 2
    from inspect_ai._util.registry import registry_log_name
    assert registry_log_name(out[0]) == "prompt_template"


def test_swap_generate_appends_pod_when_no_generate():
    assert catalog.swap_generate([], "POD") == ["POD"]


def test_swap_generate_single_solver_not_sequence():
    from inspect_ai.solver import generate

    assert catalog.swap_generate(generate(), "POD") == ["POD"]


def test_prompt_text_prefers_templated_user_prompt():
    from seon_inspect.solver import _prompt_text

    class _Msg:
        text = "TEMPLATED with ANSWER contract"

    class _State:
        user_prompt = _Msg()
        input_text = "raw"

    assert _prompt_text(_State()) == "TEMPLATED with ANSWER contract"
