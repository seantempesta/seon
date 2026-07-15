"""Offline tests for the standard-bench catalog adapter (no pod, no network)."""

import pytest

from seon_inspect import catalog


class _FakeTask:
    """Just enough Task surface for run_bench: a solver chain to swap."""
    solver = []


@pytest.fixture(autouse=True)
def _admitted_sources(monkeypatch):
    """Catalog mechanics run under one deterministic admitted source map."""
    def admit(bench):
        return {"schema_version": 2, "bench": dict(bench),
                "sources": {"inspect_ai": {}, "inspect_evals": {}}}

    monkeypatch.setattr(catalog.source_admission, "verify_sources", admit)
    monkeypatch.setattr(
        catalog.source_admission,
        "finalize_native_logs",
        lambda logs, evidence_dir=None, expected_admission=None: [
            {"location": "fake.eval", "sha256": "abc",
             "retained_path": "fake.eval"}],
    )


def test_bench_registry_is_one_benchspec_surface():
    # ONE registry: adding a bench is one BenchSpec line carrying task ref,
    # arm kind, adapter hook, and default task kwargs (the old three-dict
    # trio is deleted, not aliased)
    assert "gsm8k" in catalog.BENCHES
    for name, spec in catalog.BENCHES.items():
        assert isinstance(spec, catalog.BenchSpec), name
        assert spec.module.startswith("inspect_evals."), name
        assert spec.kind in ("case1", "swebench"), name
    assert not hasattr(catalog, "CASE1_BENCHES")
    assert not hasattr(catalog, "BENCH_ADAPTERS")
    assert not hasattr(catalog, "BENCH_DEFAULT_TASK_KWARGS")
    # bfcl carries its adapter + categories pin ON the spec
    bfcl = catalog.BENCHES["bfcl_ast"]
    assert bfcl.adapter is not None and bfcl.default_task_kwargs is not None
    # the swebench arm is registered but is NOT a pod-door bench
    assert catalog.BENCHES["swe_bench_verified"].kind == "swebench"
    assert "swe_bench_verified" not in catalog.case1_benches()


def test_unknown_bench_names_case2_tier():
    # code-exec / web-tool benches are NOT case-1 and must fail loud with guidance
    with pytest.raises(KeyError) as e:
        catalog.load_bench_task("humaneval")
    assert "case-2" in str(e.value)


def test_non_case1_kind_refused_by_pod_door_loader():
    # a registered bench of another arm kind names its own driver loudly
    with pytest.raises(KeyError) as e:
        catalog.load_bench_task("swe_bench_verified")
    assert "swe_bench_seon" in str(e.value)


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
    admitted = captured["kw"]["metadata"]["seon_source_admission"]
    assert admitted["bench"]["name"] == "gsm8k"
    # per-POD serial ceiling (one cluster = one sample): defaults to config
    assert captured["kw"]["max_samples"] == catalog.config.POD_MAX_SAMPLES == 1


def test_run_bench_rejects_source_before_task_construction(monkeypatch):
    constructed = []
    monkeypatch.setattr(
        catalog.source_admission,
        "verify_sources",
        lambda bench: (_ for _ in ()).throw(
            catalog.source_admission.SourceAdmissionError("source mismatch")),
    )
    monkeypatch.setattr(
        catalog, "load_bench_task",
        lambda *args, **kwargs: constructed.append(True) or _FakeTask(),
    )
    with pytest.raises(catalog.source_admission.SourceAdmissionError,
                       match="source mismatch"):
        catalog.run_bench("gsm8k")
    assert constructed == []


def test_run_native_task_admits_before_construction_and_finalizes(monkeypatch):
    captured = {}
    admitted = {"schema_version": 2, "bench": {"name": "native"}}

    def admit(identity):
        captured["identity"] = identity
        return admitted

    def factory(**kwargs):
        captured["factory_kwargs"] = kwargs
        return _FakeTask()

    def inspect(task, **kwargs):
        captured["eval_kwargs"] = kwargs
        return ["log"]

    monkeypatch.setattr(catalog.source_admission, "verify_sources", admit)
    monkeypatch.setattr(catalog, "inspect_eval", inspect)
    catalog.run_native_task(
        {"name": "native"}, factory,
        task_kwargs={"cluster_url": "http://example.test/agents/run"},
        target_snapshot=lambda: {"artifact": "stable"},
    )
    assert captured["identity"] == {"name": "native"}
    assert captured["factory_kwargs"]["_admission"] == admitted
    assert captured["eval_kwargs"]["metadata"][
        "seon_source_admission"] == admitted
    assert captured["eval_kwargs"]["max_samples"] == 1
    assert "solver" not in captured["eval_kwargs"]


def test_run_native_task_rejects_before_construction(monkeypatch):
    constructed = []
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources",
        lambda identity: (_ for _ in ()).throw(
            catalog.source_admission.SourceAdmissionError("source mismatch")))
    with pytest.raises(catalog.source_admission.SourceAdmissionError,
                       match="source mismatch"):
        catalog.run_native_task(
            {"name": "native"},
            lambda **kwargs: constructed.append(kwargs) or _FakeTask(),
            target_snapshot=lambda: {"artifact": "stable"},
        )
    assert constructed == []


def test_run_native_task_rejects_target_drift(monkeypatch):
    monkeypatch.setattr(
        catalog.source_admission, "verify_sources",
        lambda identity: {"bench": identity})
    monkeypatch.setattr(catalog, "inspect_eval", lambda *args, **kwargs: ["log"])
    snapshots = iter([{"artifact": "before"}, {"artifact": "after"}])
    with pytest.raises(RuntimeError, match="identity changed"):
        catalog.run_native_task(
            {"name": "native"},
            lambda **kwargs: _FakeTask(),
            target_snapshot=lambda: next(snapshots),
        )


def test_run_bench_max_samples_overridable(monkeypatch):
    # per-run override for cluster POOLS — the knob exists, the default stays 1
    captured = {}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_pod_solver", lambda **kw: "SOLVER")
    catalog.run_bench("gsm8k", max_samples=4)
    assert captured["kw"]["max_samples"] == 4


def _stub_prebuild(monkeypatch):
    # the default BENCH_CLUSTER_PARALLELISM (2) pre-builds the bundle via
    # bin/seon — offline tests stub it (its own behavior has its own tests)
    from seon_inspect import cluster as cluster_mod
    monkeypatch.setattr(cluster_mod, "ensure_bench_bundle",
                        lambda *a, **k: None)


def test_run_bench_per_sample_cluster_mode(monkeypatch):
    # per_sample_cluster=True selects the ephemeral-cluster solver …
    captured = {}
    _stub_prebuild(monkeypatch)
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval", lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    def fake_cluster_solver(**kw):
        captured["cluster_kw"] = kw
        return "CSOLVER"
    monkeypatch.setattr(catalog, "seon_cluster_solver", fake_cluster_solver)
    catalog.run_bench("gsm8k", per_sample_cluster=True, run_timeout_s=120)
    assert captured["cluster_kw"]["timeout_s"] == 120
    # an empty task chain gets the guarded fallback wrapping the pod solver
    from inspect_ai._util.registry import registry_log_name
    assert len(captured["kw"]["solver"]) == 1
    assert registry_log_name(captured["kw"]["solver"][0]).endswith("pod_fallback")
    # … and is mutually exclusive with a static cluster_url
    with pytest.raises(ValueError):
        catalog.run_bench("gsm8k", per_sample_cluster=True,
                          cluster_url="http://x.test/agents/run")


def test_run_bench_per_sample_records_bundle_identity(monkeypatch):
    # frozen-bundle pinning: the identity at run start lands in the run's
    # own artifacts (EvalLog metadata), and an unchanged bundle returns clean
    from seon_inspect import cluster as cluster_mod
    captured = {}
    _stub_prebuild(monkeypatch)
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
    _stub_prebuild(monkeypatch)
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


def test_run_bench_cluster_parallelism_sets_max_samples(monkeypatch):
    # bench-cluster-N: N concurrent samples = N live ephemeral clusters;
    # above 1 the bundle is pre-built ONCE before dispatch
    from seon_inspect import cluster as cluster_mod
    captured = {"prepared": 0}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval",
                        lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_cluster_solver", lambda **kw: "CSOLVER")
    monkeypatch.setattr(cluster_mod, "bundle_identity", lambda: {"sha256": "a"})

    def fake_prepare(*a, **k):
        captured["prepared"] += 1
        return {"sha256": "a"}

    monkeypatch.setattr(cluster_mod, "ensure_bench_bundle", fake_prepare)
    catalog.run_bench("gsm8k", per_sample_cluster=True, cluster_parallelism=4)
    assert captured["kw"]["max_samples"] == 4
    assert captured["prepared"] == 1  # ONE up-front build


def test_run_bench_serial_still_prebuilds_and_stays_max_samples_one(monkeypatch):
    # parallelism 1 (serial dispatch) STILL pre-builds once — freshness is
    # RUN-level (creates never rebuild; a per-create staleness rebuild swaps
    # code under the run) — and max_samples stays 1
    from seon_inspect import cluster as cluster_mod
    captured = {"prepared": 0}
    monkeypatch.setattr(catalog, "load_bench_task", lambda name, **k: _FakeTask())
    monkeypatch.setattr(catalog, "inspect_eval",
                        lambda *a, **k: captured.setdefault("kw", k) or ["log"])
    monkeypatch.setattr(catalog, "seon_cluster_solver", lambda **kw: "CSOLVER")
    monkeypatch.setattr(cluster_mod, "bundle_identity", lambda: {"sha256": "a"})

    def fake_prepare(*a, **k):
        captured["prepared"] += 1

    monkeypatch.setattr(cluster_mod, "ensure_bench_bundle", fake_prepare)
    monkeypatch.setattr(catalog.config, "BENCH_CLUSTER_PARALLELISM", 1)
    catalog.run_bench("gsm8k", per_sample_cluster=True)
    assert captured["kw"]["max_samples"] == 1
    assert captured["prepared"] == 1


def test_run_bench_parallelism_requires_per_sample_mode():
    # a static-URL pod is serial by construction — N clusters is the only
    # parallelism mechanism; reject the incoherent combination loudly
    with pytest.raises(ValueError, match="per_sample_cluster"):
        catalog.run_bench("gsm8k", cluster_parallelism=2,
                          cluster_url="http://x.test/agents/run")


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
    # swap_generate must keep it (pod_backed) and replace generate() with
    # the pod.
    from inspect_ai.solver import generate, prompt_template

    chain = [prompt_template("Q: {prompt}\nANSWER: $ANSWER"), generate()]
    out = catalog.swap_generate(chain, "POD")
    assert out[-1] == "POD" and len(out) == 2
    from inspect_ai._util.registry import registry_log_name
    assert registry_log_name(out[0]).endswith("pod_backed")


def test_swap_generate_appends_guarded_fallback_when_no_generate():
    from inspect_ai._util.registry import registry_log_name

    out = catalog.swap_generate([], "POD")
    assert len(out) == 1
    assert registry_log_name(out[0]).endswith("pod_fallback")


def test_swap_generate_single_solver_not_sequence():
    from inspect_ai.solver import generate

    assert catalog.swap_generate(generate(), "POD") == ["POD"]


def test_swap_generate_backs_internal_generate_with_pod():
    # multiple_choice-style composite steps format the prompt and call their
    # generate CALLBACK internally — the pod must be that callback, and the
    # trailing fallback must NOT run the pod a second time.
    import asyncio

    from inspect_ai.solver import solver as solver_dec

    pod_calls = []

    @solver_dec
    def composite():
        async def solve(state, generate):
            state.user_prompt.text = "TEMPLATED " + state.user_prompt.text
            return await generate(state)  # the internal call (multiple_choice)
        return solve

    @solver_dec
    def fake_pod():
        async def solve(state, generate):
            pod_calls.append(state.user_prompt.text)
            state.output.completion = "POD REPLY"
            state.metadata = {"pod_agent_id": "a1"}  # _record_result's marker
            return state
        return solve

    class _Msg:
        text = "q"

    class _Out:
        completion = ""

    class _State:
        user_prompt = _Msg()
        output = _Out()
        metadata = None

    chain = catalog.swap_generate([composite()], fake_pod())

    async def run():
        s = _State()
        for step in chain:
            s = await step(s, None)
        return s

    s = asyncio.run(run())
    assert pod_calls == ["TEMPLATED q"]  # the pod saw the step's template, ONCE
    assert s.output.completion == "POD REPLY"


def test_pod_fallback_never_reruns_after_an_empty_pod_reply():
    # a pod run that returned an EMPTY reply is still THE recorded answer —
    # the fallback keys on the pod-run marker, not completion text (an
    # unparsed second run bypasses the bench step's answer parsing)
    import asyncio

    from inspect_ai.solver import solver as solver_dec

    pod_calls = []

    @solver_dec
    def fake_pod():
        async def solve(state, generate):
            pod_calls.append(1)
            state.output.completion = ""  # the agent completed silently
            state.metadata = {"pod_agent_id": "a1"}
            return state
        return solve

    @solver_dec
    def composite():
        async def solve(state, generate):
            return await generate(state)
        return solve

    class _Msg:
        text = "q"

    class _Out:
        completion = ""

    class _State:
        user_prompt = _Msg()
        output = _Out()
        metadata = None

    chain = catalog.swap_generate([composite()], fake_pod())

    async def run():
        s = _State()
        for step in chain:
            s = await step(s, None)
        return s

    asyncio.run(run())
    assert pod_calls == [1]  # exactly one run — no unparsed re-drive


def test_prompt_text_prefers_templated_user_prompt():
    from seon_inspect.solver import _prompt_text

    class _Msg:
        text = "TEMPLATED with ANSWER contract"

    class _State:
        user_prompt = _Msg()
        input_text = "raw"

    assert _prompt_text(_State()) == "TEMPLATED with ANSWER contract"
