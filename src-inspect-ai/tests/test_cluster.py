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


def test_create_cluster_drives_bin_seon_and_ready(monkeypatch):
    runner = FakeRunner()
    c = cl.create_cluster("bench-abc", runner=runner, ready=lambda name: 40123)
    assert runner.calls == [[str(cl.SEON_BIN), "cluster", "create",
                             "bench-abc", "--ephemeral"]]
    assert c == cl.Cluster(name="bench-abc", port=40123)
    assert c.url == "http://127.0.0.1:40123/agents/run"


def test_create_cluster_non_ephemeral_flag():
    runner = FakeRunner()
    cl.create_cluster("plan-1", ephemeral=False, runner=runner,
                      ready=lambda name: 1)
    assert runner.calls[0][-1] == "plan-1"  # no --ephemeral appended


def test_create_cluster_failure_is_loud():
    with pytest.raises(RuntimeError) as e:
        cl.create_cluster("bench-x", runner=FakeRunner(returncode=1),
                          ready=lambda name: 1)
    assert "cluster create bench-x" in str(e.value)


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


def test_restart_pod_clears_stale_port_and_returns_new(tmp_path, monkeypatch):
    # the pod rebinds an EPHEMERAL port on restart: the stale file must go,
    # and the caller must get a NEW Cluster with the re-read port
    monkeypatch.setattr(cl, "REPO_ROOT", tmp_path)
    pf = tmp_path / "tmp" / "seon-port-plan-z"
    pf.parent.mkdir(parents=True)
    pf.write_text("40001")  # the OLD pod's port
    runner = FakeRunner()
    c2 = cl.restart_pod(cl.Cluster("plan-z", 40001), runner=runner,
                        ready=lambda name: 40777)
    assert not pf.exists()  # stale port file removed BEFORE the restart
    assert runner.calls == [[str(cl.SEON_BIN), "restart", "pod-plan-z"]]
    assert c2.port == 40777 and c2.name == "plan-z"


def test_create_cluster_frozen_default_defers_to_supervisor():
    # frozen=None (default) sends NO bundle flag — the supervisor's own
    # default rules (ephemeral ⇒ frozen, durable ⇒ watched)
    runner = FakeRunner()
    cl.create_cluster("bench-f0", runner=runner, ready=lambda n: 1)
    assert "--frozen" not in runner.calls[0]
    assert "--watched" not in runner.calls[0]


def test_create_cluster_frozen_override_flags():
    for frozen, flag in ((True, "--frozen"), (False, "--watched")):
        runner = FakeRunner()
        cl.create_cluster("bench-f1", frozen=frozen, runner=runner,
                          ready=lambda n: 1)
        assert runner.calls[0][-1] == flag


def test_ephemeral_cluster_passes_frozen_through():
    runner = FakeRunner()
    with cl.ephemeral_cluster("bench-f2", frozen=False, runner=runner,
                              ready=lambda n: 1):
        pass
    assert runner.calls[0][-1] == "--watched"


def _bundle_fixture(tmp_path, monkeypatch):
    monkeypatch.setattr(cl, "REPO_ROOT", tmp_path)
    b = tmp_path / "out-bench" / "client" / "main.js"
    b.parent.mkdir(parents=True)
    b.write_bytes(b"code")
    sha = b.parent / "main.js.sha256"
    sha.write_text("abc123\n")
    monkeypatch.setattr(cl, "BENCH_BUNDLE", b)
    monkeypatch.setattr(cl, "BENCH_BUNDLE_SHA", sha)
    return b, sha


def test_bundle_identity_absent_is_none(tmp_path, monkeypatch):
    monkeypatch.setattr(cl, "REPO_ROOT", tmp_path)
    monkeypatch.setattr(cl, "BENCH_BUNDLE", tmp_path / "out-bench/client/main.js")
    monkeypatch.setattr(cl, "BENCH_BUNDLE_SHA",
                        tmp_path / "out-bench/client/main.js.sha256")
    assert cl.bundle_identity() is None
    # nothing pinned at start ⇒ nothing to assert (watched-only use)
    assert cl.bundle_violation(None) is None


def test_bundle_identity_reads_build_step_sha(tmp_path, monkeypatch):
    _bundle_fixture(tmp_path, monkeypatch)
    ident = cl.bundle_identity()
    assert ident["sha256"] == "abc123"
    assert ident["size"] == 4
    assert ident["path"] == "out-bench/client/main.js"


def test_bundle_violation_detects_mid_run_change(tmp_path, monkeypatch):
    b, sha = _bundle_fixture(tmp_path, monkeypatch)
    start = cl.bundle_identity()
    assert cl.bundle_violation(start) is None  # unchanged ⇒ clean
    b.write_bytes(b"rebuilt")                  # a tooling-lane save mid-run
    sha.write_text("def456\n")
    v = cl.bundle_violation(start)
    assert v is not None and "frozen_bundle_changed" in v


def test_bundle_violation_detects_vanished_bundle(tmp_path, monkeypatch):
    b, _sha = _bundle_fixture(tmp_path, monkeypatch)
    start = cl.bundle_identity()
    b.unlink()
    assert cl.bundle_violation(start) is not None


def test_destroy_cluster_drives_bin_seon():
    runner = FakeRunner()
    cl.destroy_cluster("bench-abc", runner=runner)
    assert runner.calls == [[str(cl.SEON_BIN), "cluster", "destroy", "bench-abc"]]


def test_ephemeral_cluster_always_destroys():
    runner = FakeRunner()
    with pytest.raises(RuntimeError, match="sample blew up"):
        with cl.ephemeral_cluster("bench-boom", runner=runner,
                                  ready=lambda name: 1):
            raise RuntimeError("sample blew up")
    assert runner.calls[-1] == [str(cl.SEON_BIN), "cluster", "destroy",
                                "bench-boom"]


def test_wait_pod_ready_polls_file_then_probe(tmp_path, monkeypatch):
    monkeypatch.setattr(cl, "REPO_ROOT", tmp_path)
    pf = tmp_path / "tmp" / "seon-port-w1"
    pf.parent.mkdir(parents=True)
    ticks = iter(range(0, 100))
    seen = []

    def probe(port):
        seen.append(port)
        return len(seen) >= 2  # first probe refused (booting), then ready

    def sleep(_s):
        if not pf.exists():
            pf.write_text("40555\n")  # the pod writes its port mid-poll

    port = cl.wait_pod_ready("w1", timeout_s=50, probe=probe,
                             clock=lambda: next(ticks), sleep=sleep)
    assert port == 40555 and seen == [40555, 40555]


def test_wait_pod_ready_times_out_loud(tmp_path, monkeypatch):
    monkeypatch.setattr(cl, "REPO_ROOT", tmp_path)
    ticks = iter(range(0, 100))
    with pytest.raises(TimeoutError, match="w2"):
        cl.wait_pod_ready("w2", timeout_s=3, probe=lambda p: False,
                          clock=lambda: next(ticks), sleep=lambda s: None)


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
