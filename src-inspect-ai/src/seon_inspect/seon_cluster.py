"""Scratch-cluster lease for the fresh JVM Seon system.

This is the io-prepl sibling of :func:`seon_inspect.cluster.wire_repl_json`.
It deliberately does not use the retired pod, HTTP run endpoint, or branch
lease machinery.  Each lease owns a repository-local operator root, whose
``bin/seon`` initializes and starts one named branch.
"""

from __future__ import annotations

import base64
from datetime import datetime
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
from typing import Any, Callable
import uuid


REPO_ROOT = Path(__file__).resolve().parents[3]
_SCRATCH_PARENT = Path("tmp/graduation-eval")
_ROOT_LINKS = (
    "config", "deps.edn", "reference-code", "resources", "script", "src",
    "test", "bb.edn",
)
_CLUSTER_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")
_PREPL_TOKEN = re.compile(rb"(SEON-PREPL-[A-Fa-f0-9]+)<([A-Za-z0-9+/=]+)>\1")
_EDN_STRING = r'"((?:\\.|[^"\\])*)"'


class ScratchClusterError(RuntimeError):
    """A loud harness failure with machine-readable details."""

    def __init__(self, message: str, **details: Any):
        super().__init__(message)
        self.details = {"seon.cluster.harness/error": message, **details}


def _edn_string(value: str) -> str:
    # Advertisement strings are emitted by Clojure's pr-str.  For the ASCII
    # coordinates used here its escapes are JSON-compatible.
    return json.loads(f'"{value}"')


def parse_advertisement(text: str) -> dict[str, Any]:
    """Parse and validate the fields used from a fresh prepl advertisement."""

    def string_field(key: str, *, required: bool = True) -> str | None:
        match = re.search(re.escape(key) + r"\s+" + _EDN_STRING, text)
        if not match:
            if required:
                raise ScratchClusterError(
                    "The prepl advertisement is missing a string field.",
                    field=key, advertisement=text[:1000])
            return None
        return _edn_string(match.group(1))

    def int_field(key: str) -> int:
        match = re.search(re.escape(key) + r"\s+(-?\d+)", text)
        if not match:
            raise ScratchClusterError(
                "The prepl advertisement is missing an integer field.",
                field=key, advertisement=text[:1000])
        return int(match.group(1))

    start_match = re.search(
        r":seon\.boot/start-instant\s+#inst\s+" + _EDN_STRING, text)
    if not start_match:
        raise ScratchClusterError(
            "The prepl advertisement is missing its process start instant.",
            advertisement=text[:1000])
    result = {
        "cluster_name": string_field(":seon.boot/cluster-name"),
        "pid": int_field(":seon.boot/pid"),
        "prepl_host": string_field(":seon.boot/prepl-host"),
        "prepl_port": int_field(":seon.boot/prepl-port"),
        "start_instant": _edn_string(start_match.group(1)),
    }
    if result["pid"] <= 0 or not 0 < result["prepl_port"] < 65536:
        raise ScratchClusterError(
            "The prepl advertisement carries invalid process coordinates.",
            advertisement=result)
    if result["prepl_host"] not in {"127.0.0.1", "::1", "localhost"}:
        raise ScratchClusterError(
            "A scratch cluster may advertise only a loopback prepl.",
            advertisement=result)
    return result


def _default_process_alive(pid: int, start_instant: str | None) -> bool:
    try:
        os.kill(pid, 0)
    except (OSError, ValueError):
        return False
    if not start_instant:
        return False
    try:
        observed_text = subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "lstart="],
            text=True, stderr=subprocess.DEVNULL, timeout=3).strip()
        observed = datetime.strptime(
            observed_text, "%a %b %d %H:%M:%S %Y").astimezone()
        recorded = datetime.fromisoformat(start_instant.replace("Z", "+00:00"))
        return abs(observed.timestamp() - recorded.timestamp()) < 1
    except (OSError, ValueError, subprocess.SubprocessError):
        # A PID without a verifiable start instant is not the advertised
        # (pid, start-instant) process identity.
        return False


def _bounded_events(raw: bytes, limit: int = 20) -> list[str]:
    lines = raw.decode(errors="replace").splitlines()[-limit:]
    return [line[:2000] for line in lines]


def _prepl_form(form: str, token: str) -> str:
    """Wrap exactly one caller form in the sentinel read-back projection."""
    return (
        "(do (require 'cheshire.core) "
        "(let [value# " + form.strip() + " "
        "json# (cheshire.core/generate-string value#) "
        "bytes# (.getBytes ^String json# "
        "java.nio.charset.StandardCharsets/UTF_8) "
        "payload# (.encodeToString (java.util.Base64/getEncoder) bytes#)] "
        f'(println "{token}<" payload# ">{token}") nil))'
    )


def _prepl_eval(
    advertisement: dict[str, Any],
    form: str,
    *,
    socket_factory: Callable[..., Any],
    timeout_s: float,
    token_factory: Callable[[], str],
) -> Any:
    if not form or not form.strip():
        raise ScratchClusterError("eval_form requires one non-empty form.")
    token = f"SEON-PREPL-{token_factory()}"
    request = _prepl_form(form, token).encode() + b"\n"
    raw = bytearray()
    terminal = False
    exceptional = False
    try:
        with socket_factory(
            (advertisement["prepl_host"], advertisement["prepl_port"]),
            timeout=timeout_s,
        ) as connection:
            connection.settimeout(timeout_s)
            connection.sendall(request)
            connection.shutdown(socket.SHUT_WR)
            while True:
                chunk = connection.recv(65536)
                if not chunk:
                    break
                raw.extend(chunk)
                # io-prepl calls prn once per event.  A terminal event is an
                # array-map emitted with :tag first (Clojure server.clj).
                complete = bytes(raw).rsplit(b"\n", 1)[0]
                for line in complete.splitlines():
                    if line.startswith(b"{:tag :ret"):
                        terminal = True
                        exceptional = b":exception true" in line
                if terminal:
                    break
    except (OSError, TimeoutError) as error:
        raise ScratchClusterError(
            "The cluster prepl transport failed.",
            cause=repr(error), events=_bounded_events(bytes(raw))) from error

    events = _bounded_events(bytes(raw))
    if not terminal:
        raise ScratchClusterError(
            "The cluster prepl closed before a terminal :ret event.",
            events=events)
    if exceptional:
        raise ScratchClusterError(
            "The cluster rejected the prepl form.", events=events)
    matches = list(_PREPL_TOKEN.finditer(bytes(raw)))
    selected = [match for match in matches
                if match.group(1).decode() == token]
    if len(selected) != 1:
        raise ScratchClusterError(
            "The successful prepl reply did not carry exactly one sentinel.",
            sentinel_count=len(selected), events=events)
    try:
        payload = base64.b64decode(selected[0].group(2), validate=True)
        return json.loads(payload)
    except (ValueError, json.JSONDecodeError) as error:
        raise ScratchClusterError(
            "The prepl sentinel payload was not Base64 JSON.",
            cause=repr(error), events=events) from error


def _ensure_beneath(path: Path, parent: Path, what: str) -> None:
    resolved, resolved_parent = path.resolve(), parent.resolve()
    if resolved == resolved_parent or resolved_parent not in resolved.parents:
        raise ScratchClusterError(
            f"Refusing {what} outside its private repository-local root.",
            path=str(resolved), parent=str(resolved_parent))


def _make_runnable_root(root: Path, repo_root: Path) -> None:
    _ensure_beneath(root, repo_root / "tmp", "scratch operator root")
    root.mkdir(parents=True, exist_ok=False)
    for relative in _ROOT_LINKS:
        source = repo_root / relative
        if not source.exists():
            raise ScratchClusterError(
                "The checkout lacks a scratch-operator input.",
                path=str(source))
        (root / relative).symlink_to(source, target_is_directory=source.is_dir())
    bin_dir = root / "bin"
    bin_dir.mkdir()
    (bin_dir / "seon").symlink_to(repo_root / "bin" / "seon")


def _delete_tree_without_following_links(root: Path) -> None:
    """Delete one owned root while treating every symlink as a leaf."""
    if root.is_symlink():
        raise ScratchClusterError(
            "Refusing to recursively delete a symlinked scratch root.",
            path=str(root))
    if not root.exists():
        return
    for entry in os.scandir(root):
        path = Path(entry.path)
        if entry.is_symlink():
            path.unlink()
        elif entry.is_dir(follow_symlinks=False):
            _delete_tree_without_following_links(path)
        else:
            path.unlink()
    root.rmdir()


class ScratchClusterLease:
    """One private operator root and its single named scratch cluster."""

    def __init__(
        self,
        *,
        root: Path,
        name: str,
        runner: Callable[..., subprocess.CompletedProcess[str]],
        socket_factory: Callable[..., Any],
        process_alive: Callable[[int, str | None], bool],
        timeout_s: float,
        clock: Callable[[], float],
        wait: Callable[[float], Any],
        token_factory: Callable[[], str],
    ):
        self.root = root
        self.name = name
        self._runner = runner
        self._socket_factory = socket_factory
        self._process_alive = process_alive
        self._timeout_s = timeout_s
        self._clock = clock
        self._wait = wait
        self._token_factory = token_factory
        self._released = False

    @property
    def advertisement_path(self) -> Path:
        path = self.root / "data" / "clusters" / self.name / "prepl.edn"
        _ensure_beneath(path, self.root / "data" / "clusters",
                        "prepl advertisement")
        return path

    def _operator(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        command = [str(self.root / "bin" / "seon"), *arguments]
        result = self._runner(
            command, cwd=str(self.root), capture_output=True, text=True,
            timeout=self._timeout_s,
        )
        if result.returncode:
            raise ScratchClusterError(
                "The private Seon operator command failed.",
                command=command, cwd=str(self.root),
                returncode=result.returncode,
                stdout=(result.stdout or "")[-4000:],
                stderr=(result.stderr or "")[-4000:])
        return result

    def _advertisement(self) -> dict[str, Any]:
        path = self.advertisement_path
        advertisement = parse_advertisement(path.read_text())
        if advertisement["cluster_name"] != self.name:
            raise ScratchClusterError(
                "The private advertisement names a different cluster.",
                expected=self.name, advertisement=advertisement)
        return advertisement

    def _recover_stale_advertisement(self) -> None:
        path = self.advertisement_path
        if not path.exists():
            return
        advertisement = self._advertisement()
        if self._process_alive(advertisement["pid"],
                               advertisement["start_instant"]):
            raise ScratchClusterError(
                "Refusing to replace a live scratch-cluster advertisement.",
                path=str(path), advertisement=advertisement)
        path.unlink()

    def _wait_ready(self) -> None:
        deadline = self._clock() + self._timeout_s
        last_error: BaseException | None = None
        while self._clock() < deadline:
            try:
                advertisement = self._advertisement()
                if not self._process_alive(advertisement["pid"],
                                           advertisement["start_instant"]):
                    self.advertisement_path.unlink(missing_ok=True)
                    raise ScratchClusterError(
                        "The fresh operator published a stale advertisement.",
                        advertisement=advertisement)
                value = self.eval_form("{:seon.cluster.harness/ready true}")
                if value == {"seon.cluster.harness/ready": True}:
                    return
                last_error = ScratchClusterError(
                    "The readiness form returned the wrong value.", value=value)
            except (OSError, ScratchClusterError) as error:
                last_error = error
            self._wait(0.05)
        raise ScratchClusterError(
            "Timed out waiting for advertisement plus successful prepl eval; "
            "the timeout is a backstop, not the readiness mechanism.",
            timeout_s=self._timeout_s, cause=repr(last_error))

    def eval_form(self, form: str) -> Any:
        """Evaluate one form and return its ordinary JSON-projected value."""
        if self._released:
            raise ScratchClusterError("The scratch-cluster lease is released.")
        advertisement = self._advertisement()
        if not self._process_alive(advertisement["pid"],
                                   advertisement["start_instant"]):
            raise ScratchClusterError(
                "The scratch cluster's advertisement is stale.",
                advertisement=advertisement)
        return _prepl_eval(
            advertisement, form, socket_factory=self._socket_factory,
            timeout_s=self._timeout_s, token_factory=self._token_factory)

    def restart(self) -> None:
        """Stop and restart the same persisted branch, then prove readiness."""
        if self._released:
            raise ScratchClusterError("The scratch-cluster lease is released.")
        self._operator("stop", self.name)
        self._recover_stale_advertisement()
        self._operator("start", self.name)
        self._wait_ready()

    def release(self) -> None:
        """Stop the cluster, then remove only the private operator root."""
        if self._released:
            return
        self._operator("stop", self.name)
        self._released = True
        _delete_tree_without_following_links(self.root)


def start_scratch_cluster(
    prefix: str = "mvpeval",
    *,
    _repo_root: Path = REPO_ROOT,
    _root: Path | None = None,
    _name: str | None = None,
    _runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    _socket_factory: Callable[..., Any] = socket.create_connection,
    _process_alive: Callable[[int, str | None], bool] = _default_process_alive,
    _timeout_s: float = 30.0,
    _clock: Callable[[], float] = time.monotonic,
    _wait: Callable[[float], Any] = threading.Event().wait,
    _token_factory: Callable[[], str] = lambda: uuid.uuid4().hex,
) -> ScratchClusterLease:
    """Create, initialize, start, and prove one isolated scratch cluster."""
    suffix = uuid.uuid4().hex[:10]
    name = _name or f"{prefix}-{suffix}"
    if not _CLUSTER_NAME.fullmatch(name):
        raise ScratchClusterError(
            "Scratch-cluster names use the operator's safe name alphabet.",
            name=name)
    repo_root = Path(_repo_root).resolve()
    root = Path(_root) if _root else (
        repo_root / _SCRATCH_PARENT / f"{name}-{uuid.uuid4().hex[:10]}")
    _ensure_beneath(root, repo_root / "tmp", "scratch operator root")
    _make_runnable_root(root, repo_root)
    lease = ScratchClusterLease(
        root=root, name=name, runner=_runner, socket_factory=_socket_factory,
        process_alive=_process_alive, timeout_s=_timeout_s, clock=_clock,
        wait=_wait, token_factory=_token_factory)
    start_attempted = False
    try:
        lease._recover_stale_advertisement()
        lease._operator("init")
        lease._operator("init", name)
        start_attempted = True
        lease._operator("start", name)
        lease._wait_ready()
        return lease
    except BaseException:
        # Never erase coordinates for a process that may still be alive.
        if not start_attempted:
            _delete_tree_without_following_links(root)
        raise
