"""Cluster lifecycle from the harness — create / restart / destroy + wire REPL.

A cluster is the isolation unit (one shared DB + one Node pod; the wire-server
JVM hosts every cluster's db). The supervisor owns the mechanics — this module
just drives `bin/seon cluster create|destroy` and `bin/seon restart pod-<n>`
as subprocesses, reads the per-cluster port file (`tmp/seon-port-<n>`), and
ready-polls the pod's HTTP door. Per-sample benching = one ephemeral cluster
per sample (`ephemeral_cluster()`); the planning row keeps ITS cluster alive
across a mid-sample pod restart (`restart_pod`, which mints a NEW ephemeral
port — always take the returned Cluster).

The wire-server's loopback socket REPL (port in `tmp/seon-writer-repl-port`)
survives pod restarts and sees every cluster's db through the registry —
`wire_repl_json` is the read-back channel the planning snapshot uses.

Effects are injectable (`runner=subprocess.run`) so the sequencing is
unit-tested offline with fakes; nothing here talks to a pod at import time.
"""

from __future__ import annotations

import contextlib
import http.client
import json
import re
import socket
import subprocess
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterator

from seon_inspect import config

REPO_ROOT = Path(__file__).resolve().parents[3]
SEON_BIN = REPO_ROOT / "bin" / "seon"

# Matches bin/seon's valid_cluster_name (a path segment + a wire db-name).
_NAME_RE = re.compile(r"^[a-zA-Z0-9_-]+$")


@dataclass(frozen=True)
class Cluster:
    """A created cluster's coordinates: name + the pod's bound HTTP port."""
    name: str
    port: int

    @property
    def url(self) -> str:
        """The pod door — POST /agents/run on this cluster's pod."""
        return f"http://127.0.0.1:{self.port}/agents/run"


def bench_cluster_name(prefix: str = "bench") -> str:
    """A fresh, collision-proof cluster name for one bench sample."""
    return f"{prefix}-{uuid.uuid4().hex[:12]}"


def _port_file(name: str) -> Path:
    return REPO_ROOT / "tmp" / f"seon-port-{name}"


def _check_name(name: str) -> str:
    if not _NAME_RE.match(name or ""):
        raise ValueError(f"invalid cluster name: {name!r} (a-zA-Z0-9_- only)")
    return name


def _run_seon(args: list[str], runner: Callable[..., Any],
              timeout_s: int) -> None:
    proc = runner([str(SEON_BIN), *args], cwd=str(REPO_ROOT),
                  capture_output=True, text=True, timeout=timeout_s)
    if proc.returncode != 0:
        raise RuntimeError(
            f"bin/seon {' '.join(args)} failed (exit {proc.returncode}):\n"
            f"{proc.stdout}\n{proc.stderr}")


def _pod_answers(port: int) -> bool:
    try:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
        try:
            conn.request("GET", "/")
            return conn.getresponse().status < 500
        finally:
            conn.close()
    except OSError:
        return False


def wait_pod_ready(name: str, timeout_s: int = config.CLUSTER_BOOT_BUDGET_S,
                   probe: Callable[[int], bool] = _pod_answers,
                   clock: Callable[[], float] = time.monotonic,
                   sleep: Callable[[float], None] = time.sleep) -> int:
    """Poll until `tmp/seon-port-<name>` exists AND its pod answers HTTP.
    Returns the bound port; raises TimeoutError past `timeout_s`."""
    pf = _port_file(name)
    deadline = clock() + timeout_s
    while clock() < deadline:
        if pf.is_file():
            raw = pf.read_text().strip()
            if raw.isdigit() and probe(int(raw)):
                return int(raw)
        sleep(0.5)
    raise TimeoutError(
        f"cluster {name}: pod not ready within {timeout_s}s "
        f"(port file {pf}, exists={pf.is_file()})")


def create_cluster(name: str | None = None, *, ephemeral: bool = True,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """`bin/seon cluster create <name> [--ephemeral]` → a ready Cluster.

    The supervisor ready-gates the wire-server and the pod itself; the ready
    poll here is the harness-side confirmation (and yields the bound port)."""
    name = _check_name(name or bench_cluster_name())
    args = ["cluster", "create", name] + (["--ephemeral"] if ephemeral else [])
    # create ready-gates wire-server (bound 180s) + pod (120s) internally.
    _run_seon(args, runner, timeout_s=330)
    return Cluster(name=name, port=ready(name))


def restart_pod(cluster: Cluster, *,
                runner: Callable[..., Any] = subprocess.run,
                ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """`bin/seon restart pod-<name>` — the planning row's interruption.

    The pod rebinds an EPHEMERAL port on boot, so the stale port file is
    removed first and the returned Cluster carries the NEW port (the old
    Cluster's url is dead — always continue with the return value)."""
    _check_name(cluster.name)
    _port_file(cluster.name).unlink(missing_ok=True)
    _run_seon(["restart", f"pod-{cluster.name}"], runner, timeout_s=180)
    return Cluster(name=cluster.name, port=ready(cluster.name))


def destroy_cluster(name: str, *,
                    runner: Callable[..., Any] = subprocess.run) -> None:
    """`bin/seon cluster destroy <name>` — pod stopped, registry db deleted,
    data/clusters/<name>/ removed (blobs included)."""
    _run_seon(["cluster", "destroy", _check_name(name)], runner, timeout_s=120)


@contextlib.contextmanager
def ephemeral_cluster(name: str | None = None, *,
                      runner: Callable[..., Any] = subprocess.run,
                      ready: Callable[[str], int] = wait_pod_ready
                      ) -> Iterator[Cluster]:
    """create → yield → destroy (destroy always runs — no leaked clusters)."""
    cluster = create_cluster(name, ephemeral=True, runner=runner, ready=ready)
    try:
        yield cluster
    finally:
        destroy_cluster(cluster.name, runner=runner)


# ---------------------------------------------------------------------------
# Wire-server socket REPL — the supervisor-facing read-back channel
# ---------------------------------------------------------------------------

WIRE_REPL_PORT_FILE = REPO_ROOT / "tmp" / "seon-writer-repl-port"


def wire_repl_port() -> int:
    """The wire-server's loopback socket-REPL port (written at its boot)."""
    if not WIRE_REPL_PORT_FILE.is_file():
        raise RuntimeError(
            f"wire-server REPL port file missing: {WIRE_REPL_PORT_FILE} — "
            "is the wire-server running? (bin/seon start wire-server)")
    return int(WIRE_REPL_PORT_FILE.read_text().strip())


def wire_repl_json(form: str, *, port: int | None = None,
                   timeout_s: int = 30) -> Any:
    """Eval ONE Clojure form on the wire-server REPL; parse a sentinel line.

    The form must print exactly one line `WIRE-JSON<{...}>WIRE-JSON` (JSON
    between the sentinels — e.g. via cheshire, on the wire-server classpath).
    Sentinels make the extraction robust against REPL prompts/echoes."""
    p = port or wire_repl_port()
    with socket.create_connection(("127.0.0.1", p), timeout=timeout_s) as s:
        s.settimeout(timeout_s)
        s.sendall(form.strip().encode() + b"\n")
        s.shutdown(socket.SHUT_WR)
        buf = b""
        with contextlib.suppress(TimeoutError, OSError):
            while chunk := s.recv(65536):
                buf += chunk
    m = re.search(r"WIRE-JSON<(.*)>WIRE-JSON", buf.decode(errors="replace"),
                  re.DOTALL)
    if not m:
        raise RuntimeError(
            f"wire REPL reply carried no WIRE-JSON sentinel: {buf!r:.400}")
    return json.loads(m.group(1))
