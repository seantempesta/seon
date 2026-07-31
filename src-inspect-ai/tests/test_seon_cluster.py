"""Offline discrimination tests for the fresh scratch-cluster lease."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import subprocess

import pytest

from seon_inspect import seon_cluster as sc


def _fake_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "checkout"
    repo.mkdir()
    for directory in ("config", "reference-code", "resources", "script",
                      "src", "test", "bin", ".clj-kondo"):
        (repo / directory).mkdir()
    for file in ("deps.edn", "bb.edn", "bin/seon", ".clj-kondo/config.edn"):
        path = repo / file
        path.write_text("#!/bin/sh\n")
    return repo


def _advertisement(name: str, pid: int = 123) -> str:
    return (
        "{:seon.boot/cluster-name " + json.dumps(name) + " "
        f":seon.boot/pid {pid} "
        ':seon.boot/start-instant #inst "2026-07-31T12:00:00.000-00:00" '
        ':seon.boot/prepl-host "127.0.0.1" '
        ':seon.boot/prepl-port 45555}\n'
    )


class SentinelSocket:
    def __init__(self, value=None, *, exception=False, terminal=True):
        self.value = ({"seon.cluster.harness/ready": True}
                      if value is None else value)
        self.exception = exception
        self.terminal = terminal
        self.sent = b""
        self.returned = False
        self.timeout = None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def settimeout(self, timeout):
        self.timeout = timeout

    def sendall(self, value):
        self.sent += value

    def shutdown(self, _how):
        pass

    def recv(self, _size):
        if self.returned:
            return b""
        self.returned = True
        token = re.search(rb"SEON-PREPL-[A-Fa-f0-9]+", self.sent).group(0)
        payload = base64.b64encode(json.dumps(self.value).encode())
        out = b'{:tag :out, :val "' + token + b"<" + payload + b">" + token + b'\\n"}\n'
        if not self.terminal:
            return out
        if self.exception:
            return out + b'{:tag :ret, :val "boom", :exception true}\n'
        return out + b'{:tag :ret, :val "nil", :ns "user", :ms 1}\n'


class LifecycleRunner:
    def __init__(self, root: Path, name: str, *, pid: int = 123):
        self.root = root
        self.name = name
        self.pid = pid
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        assert Path(argv[0]) == self.root / "bin" / "seon"
        assert Path(kwargs["cwd"]) == self.root
        if argv[1:3] == ["start", self.name]:
            path = self.root / "data" / "clusters" / self.name / "prepl.edn"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(_advertisement(self.name, self.pid))
        elif argv[1:] == ["stop", self.name]:
            (self.root / "data" / "clusters" / self.name /
             "prepl.edn").unlink(missing_ok=True)
        elif argv[1:] == ["status"]:
            path = self.root / "data" / "clusters" / self.name / "prepl.edn"
            if path.exists() and sc.parse_advertisement(
                    path.read_text())["pid"] != self.pid:
                path.unlink()
        return subprocess.CompletedProcess(argv, 0, stdout="ok", stderr="")


def _start(tmp_path: Path, *, socket_value=None):
    repo = _fake_repo(tmp_path)
    root = repo / "tmp" / "lease"
    name = "eval-fixture"
    runner = LifecycleRunner(root, name)
    sockets = []

    def socket_factory(*_args, **_kwargs):
        sock = SentinelSocket(None if not sockets else socket_value)
        sockets.append(sock)
        return sock

    lease = sc.start_scratch_cluster(
        _repo_root=repo, _root=root, _name=name, _runner=runner,
        _socket_factory=socket_factory,
        _token_factory=lambda: "a" * 32,
    )
    return repo, root, runner, sockets, lease


def test_start_builds_private_runnable_root_and_commands():
    # Use pytest's own temp root only as the parent of a fake checkout; the
    # mechanism itself still insists on <checkout>/tmp/...
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as directory:
        repo, root, runner, sockets, lease = _start(Path(directory))
        assert [call[0][1:] for call in runner.calls] == [
            ["init"], ["init", "eval-fixture"],
            ["start", "eval-fixture"],
        ]
        assert root != repo
        assert (root / "bin").is_dir() and not (root / "bin").is_symlink()
        assert (root / "bin" / "seon").is_symlink()
        for relative in sc._ROOT_LINKS:
            assert (root / relative).is_symlink()
        assert sockets[0].timeout == 30.0
        lease.release()


def test_parse_advertisement_validates_liveness_coordinates():
    parsed = sc.parse_advertisement(_advertisement("scratch", 987))
    assert parsed == {
        "cluster_name": "scratch", "pid": 987,
        "start_instant": "2026-07-31T12:00:00.000-00:00",
        "prepl_host": "127.0.0.1", "prepl_port": 45555,
    }
    with pytest.raises(sc.ScratchClusterError, match="loopback"):
        sc.parse_advertisement(
            _advertisement("scratch").replace("127.0.0.1", "10.0.0.4"))
    with pytest.raises(sc.ScratchClusterError, match="invalid process"):
        sc.parse_advertisement(_advertisement("scratch", 0))


def test_eval_form_returns_json_projection_and_requires_terminal_ret(tmp_path):
    repo, root, runner, sockets, lease = _start(
        tmp_path, socket_value={"answer": [1, 2, 3]})
    assert lease.eval_form("(+ 1 2)") == {"answer": [1, 2, 3]}
    request = sockets[-1].sent.decode()
    assert "(+ 1 2)" in request
    assert "Base64/getEncoder" in request
    lease._socket_factory = lambda *_a, **_kw: SentinelSocket(
        {"answer": 3}, terminal=False)
    with pytest.raises(sc.ScratchClusterError, match="terminal :ret") as error:
        lease.eval_form("(+ 1 2)")
    assert error.value.details["events"]
    lease._socket_factory = lambda *_a, **_kw: SentinelSocket(exception=True)
    with pytest.raises(sc.ScratchClusterError, match="rejected"):
        lease.eval_form("(/ 1 0)")
    lease._socket_factory = lambda *_a, **_kw: SentinelSocket()
    lease.release()


def test_prepl_wrapper_emits_the_exact_unspaced_sentinel():
    token = "SEON-PREPL-" + "d" * 32
    form = sc._prepl_form("{:answer 42}", token)
    result = subprocess.run(
        ["bb", "--config", str(sc.REPO_ROOT / "bb.edn"),
         "--deps-root", str(sc.REPO_ROOT), "-e", form],
        cwd=sc.REPO_ROOT, capture_output=True, text=True, check=True,
    )
    match = sc._PREPL_TOKEN.search(result.stdout.encode())
    assert match is not None, result.stdout
    assert json.loads(base64.b64decode(match.group(2))) == {"answer": 42}


def test_restart_is_stop_start_on_same_branch(tmp_path):
    _repo, _root, runner, _sockets, lease = _start(tmp_path)
    before = len(runner.calls)
    lease.restart()
    assert [call[0][1:] for call in runner.calls[before:]] == [
        ["stop", "eval-fixture"], ["start", "eval-fixture"]]
    lease.release()


def test_restart_readiness_failure_still_stops_started_process(tmp_path):
    _repo, _root, runner, _sockets, lease = _start(tmp_path)
    lease._socket_factory = lambda *_a, **_kw: SentinelSocket(terminal=False)
    with pytest.raises(sc.ScratchClusterError, match="terminal :ret"):
        lease.restart()
    lease.release()
    assert [call[0][1:] for call in runner.calls].count(
        ["stop", "eval-fixture"]) == 2


def test_release_never_follows_symlinks(tmp_path):
    repo, root, _runner, _sockets, lease = _start(tmp_path)
    sentinel = repo / "external-sentinel"
    sentinel.mkdir()
    (sentinel / "alive").write_text("yes")
    (root / "do-not-follow").symlink_to(sentinel, target_is_directory=True)
    lease.release()
    assert not root.exists()
    assert (sentinel / "alive").read_text() == "yes"
    lease.release()  # idempotent


def test_stale_advertisement_is_removed_before_private_init(tmp_path):
    repo = _fake_repo(tmp_path)
    root = repo / "tmp" / "stale"
    name = "stale-fixture"
    sc._make_runnable_root(root, repo)
    runner = LifecycleRunner(root, name, pid=456)
    lease = sc.ScratchClusterLease(
        root=root, name=name, runner=runner,
        socket_factory=lambda *_a, **_kw: SentinelSocket(),
        timeout_s=30,
        token_factory=lambda: "b" * 32,
    )
    path = lease.advertisement_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(_advertisement(name, 111))
    lease._recover_stale_advertisement()
    assert not path.exists()
    runner([str(root / "bin" / "seon"), "start", name], cwd=str(root))
    assert lease._advertisement()["pid"] == 456
    lease.release()


def test_refuses_shared_root_and_live_advertisement(tmp_path):
    repo = _fake_repo(tmp_path)
    with pytest.raises(sc.ScratchClusterError, match="outside"):
        sc.start_scratch_cluster(_repo_root=repo, _root=repo,
                                 _name="shared")

    root = repo / "tmp" / "live"
    sc._make_runnable_root(root, repo)
    path = root / "data" / "clusters" / "live" / "prepl.edn"
    path.parent.mkdir(parents=True)
    path.write_text(_advertisement("live", os.getpid()))
    lease = sc.ScratchClusterLease(
        root=root, name="live",
        runner=lambda argv, **_kw: subprocess.CompletedProcess(
            argv, 0, stdout="alive", stderr=""),
        socket_factory=lambda *_a, **_kw: SentinelSocket(),
        timeout_s=1,
        token_factory=lambda: "c" * 32,
    )
    with pytest.raises(sc.ScratchClusterError, match="live"):
        lease._recover_stale_advertisement()
    sc._delete_tree_without_following_links(root)


def test_start_writes_private_sparse_config_and_passes_it_to_operator(tmp_path):
    repo = _fake_repo(tmp_path)
    root = repo / "tmp" / "configured"
    name = "configured-fixture"
    runner = LifecycleRunner(root, name)
    lease = sc.start_scratch_cluster(
        _repo_root=repo, _root=root, _name=name, _runner=runner,
        _socket_factory=lambda *_a, **_kw: SentinelSocket(),
        config_manifest="{:seon.config.ai/model \"local-model\"}",
        _token_factory=lambda: "e" * 32,
    )
    start = runner.calls[2][0]
    assert start[1:3] == ["start", name]
    assert start[3] == "--config"
    config_path = Path(start[4])
    assert config_path.parent == root
    assert config_path.read_text() == \
        "{:seon.config.ai/model \"local-model\"}"
    lease.release()


def test_release_retries_cleanup_without_re_stopping(tmp_path):
    _repo, root, runner, _sockets, lease = _start(tmp_path)
    attempts = 0

    def flaky_delete(path):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise OSError("injected cleanup failure")
        sc._delete_tree_without_following_links(path)

    lease._delete_root = flaky_delete
    with pytest.raises(OSError, match="cleanup failure"):
        lease.release()
    stops_after_failure = sum(
        call[0][1:] == ["stop", "eval-fixture"] for call in runner.calls)
    lease.release()
    assert attempts == 2
    assert not root.exists()
    assert sum(call[0][1:] == ["stop", "eval-fixture"]
               for call in runner.calls) == stops_after_failure
