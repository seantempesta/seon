"""Offline tests for the cluster lifecycle helper (subprocess/socket fakes)."""

import json
import subprocess
import threading
import socket

import pytest

from seon_inspect import cluster as cl


class FakeRunner:
    """Records bin/seon invocations; returns a configurable exit code."""

    def __init__(self, returncode=0):
        self.calls = []
        self.returncode = returncode

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        return subprocess.CompletedProcess(argv, self.returncode,
                                           stdout="", stderr="boom")


def test_create_cluster_refuses_retired_operator_command():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable, match="structured per-sample lease"):
        cl.create_cluster("bench-abc", runner=runner, ready=lambda name: 40123)
    assert runner.calls == []


def test_create_cluster_non_ephemeral_still_requires_lease():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable):
        cl.create_cluster("plan-1", ephemeral=False, runner=runner,
                          ready=lambda name: 1)
    assert runner.calls == []


def test_create_cluster_names_missing_operator_fields():
    with pytest.raises(cl.ClusterLeaseUnavailable) as error:
        cl.create_cluster("bench-x", runner=FakeRunner(), ready=lambda name: 1)
    message = str(error.value)
    assert "artifact flavor/digest" in message
    assert "dynamic web/CLJ/CLJS endpoints" in message
    assert "ownership-fenced restart/release" in message


def test_cluster_name_validated():
    # the name becomes a path segment + a wire db-name — reject junk before
    # it reaches a shell (mirrors bin/seon valid_cluster_name; an EMPTY/None
    # name means "mint a fresh bench name", so it is not in this list)
    for bad in ("a/b", "..", "x y", "a;rm"):
        with pytest.raises(ValueError):
            cl.create_cluster(bad, runner=FakeRunner(), ready=lambda n: 1)


def test_bench_cluster_names_are_fresh():
    names = {cl.bench_cluster_name() for _ in range(50)}
    assert len(names) == 50
    assert all(cl._NAME_RE.match(n) for n in names)


def test_restart_refuses_retired_per_pod_command():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable, match="restart"):
        cl.restart_pod(cl.Cluster("plan-z", 40001), runner=runner,
                       ready=lambda name: 40777)
    assert runner.calls == []


def test_create_cluster_frozen_flags_do_not_restore_removed_flavors():
    runner = FakeRunner()
    for frozen in (None, True, False):
        with pytest.raises(cl.ClusterLeaseUnavailable):
            cl.create_cluster("bench-f0", frozen=frozen, runner=runner,
                              ready=lambda n: 1)
    assert runner.calls == []


def test_cluster_coordinates_keep_explicit_dynamic_url():
    cluster = cl.Cluster(name="owned", port=40123)
    assert cluster.url == "http://127.0.0.1:40123/agents/run"


def test_fork_cluster_refuses_retired_operator_command():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable, match="fork"):
        cl.fork_cluster("source-a", 12345, "counterfactual-a",
                        runner=runner, ready=lambda name: 40123)
    assert runner.calls == []


def test_fork_cluster_rejects_invalid_basis_or_names():
    runner = FakeRunner()
    for basis in (-1, True, "123"):
        with pytest.raises(ValueError, match="basis_t"):
            cl.fork_cluster("source-a", basis, runner=runner,
                            ready=lambda name: 1)
    with pytest.raises(ValueError):
        cl.fork_cluster("bad/name", 1, runner=runner, ready=lambda name: 1)


def test_ephemeral_cluster_fails_before_yield_or_subprocess():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable):
        with cl.ephemeral_cluster("bench-f2", frozen=False, runner=runner,
                                  ready=lambda n: 1):
            pytest.fail("unleased cluster must never be yielded")
    assert runner.calls == []


def test_ephemeral_fork_fails_before_yield_or_subprocess():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable):
        with cl.ephemeral_fork("source-a", 12345, "counterfactual-a",
                               runner=runner, ready=lambda name: 40123):
            pytest.fail("unleased fork must never be yielded")
    assert runner.calls == []


def test_ensure_bench_bundle_refuses_removed_artifact_flavor():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable, match="artifact"):
        cl.ensure_bench_bundle(runner=runner)
    assert runner.calls == []


def test_destroy_cluster_refuses_unfenced_release():
    runner = FakeRunner()
    with pytest.raises(cl.ClusterLeaseUnavailable, match="release"):
        cl.destroy_cluster("bench-abc", runner=runner)
    assert runner.calls == []


def test_create_cluster_frozen_override_flags_are_never_shelled():
    for frozen in (True, False):
        runner = FakeRunner()
        with pytest.raises(cl.ClusterLeaseUnavailable):
            cl.create_cluster("bench-f1", frozen=frozen, runner=runner,
                              ready=lambda n: 1)
        assert runner.calls == []


def test_absent_legacy_bundle_is_not_an_artifact_identity(tmp_path, monkeypatch):
    monkeypatch.setattr(cl, "BENCH_BUNDLE", tmp_path / "missing.js")
    monkeypatch.setattr(cl, "BENCH_BUNDLE_SHA", tmp_path / "missing.sha256")
    assert cl.bundle_identity() is None
    assert cl.bundle_violation(None) is None


def test_wire_repl_json_sentinel_roundtrip():
    # a fake socket REPL: reads one form, replies with prompt noise + the
    # sentinel line — wire_repl_json must extract exactly the JSON payload
    rows = [{"id": "s1", "status": "done"}]
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def serve():
        conn, _ = srv.accept()
        conn.recv(65536)
        conn.sendall(("user=> WIRE-JSON<" + json.dumps(rows)
                      + ">WIRE-JSON\nuser=> ").encode())
        conn.close()

    t = threading.Thread(target=serve, daemon=True)
    t.start()
    try:
        assert cl.wire_repl_json("(fake)", port=port, timeout_s=5) == rows
    finally:
        srv.close()


def test_wire_repl_json_no_sentinel_is_loud():
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def serve():
        conn, _ = srv.accept()
        conn.recv(65536)
        conn.sendall(b"Syntax error compiling at (REPL:1:1)\n")
        conn.close()

    threading.Thread(target=serve, daemon=True).start()
    try:
        with pytest.raises(RuntimeError, match="no WIRE-JSON sentinel"):
            cl.wire_repl_json("(broken", port=port, timeout_s=5)
    finally:
        srv.close()


# ---------------------------------------------------------------------------
# Per-cluster AI config hook (the thinking-arm lever, 2026-07-04)
# ---------------------------------------------------------------------------


def test_ai_config_can_be_applied_only_to_an_explicit_owned_connection():
    seen = {}

    def fake_repl(form):
        seen["form"] = form
        return {"thinking": "true", "timeout_ms": 300000}

    row = cl.apply_ai_config(
        "bench-armd", {"thinking": "true", "timeout_ms": 300000},
        repl=fake_repl)
    assert row == {"thinking": "true", "timeout_ms": 300000}
    assert ":bench-armd" in seen["form"]
    assert ':seon.ai/thinking "true"' in seen["form"]
    assert ":seon.ai/timeout-ms 300000" in seen["form"]


def test_ai_config_readback_mismatch_is_loud():
    with pytest.raises(RuntimeError) as e:
        cl.apply_ai_config("bench-x", {"thinking": "true"},
                           repl=lambda form: {"thinking": None})
    assert "read-back mismatch" in str(e.value)


def test_ai_config_rejects_unknown_keys():
    with pytest.raises(ValueError):
        cl.apply_ai_config("bench-x", {"reasoning": "max"},
                           repl=lambda form: {})
