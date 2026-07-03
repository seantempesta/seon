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

# The FROZEN bench bundle (:bench-client shadow build) frozen clusters exec —
# unwatched, so cljs-watch can never hot-patch a bench pod mid-sample (the
# 2026-07-03 dominant flake class). bin/seon's build step writes the sha file;
# `bundle_identity`/`bundle_violation` pin + assert it per run.
BENCH_BUNDLE = REPO_ROOT / "out-bench" / "client" / "main.js"
BENCH_BUNDLE_SHA = BENCH_BUNDLE.parent / (BENCH_BUNDLE.name + ".sha256")

# Matches bin/seon's valid_cluster_name (a path segment + a wire db-name).
_NAME_RE = re.compile(r"^[a-zA-Z0-9_-]+$")


class FrozenBundleChanged(RuntimeError):
    """The frozen bench bundle changed under a run — the run is contaminated.

    Raised by the end-of-run assertion (`catalog.run_bench` per-sample mode).
    Classify the run's executions as the flake class `frozen_bundle_changed`
    and publish NO capability number from it (the uniform-0 law's sibling:
    contamination is voided, never scored through). Carries both identities
    and, when raised after an eval, the inspect logs — the evidence is never
    lost to the raise."""

    def __init__(self, message: str, *, start: dict[str, Any] | None = None,
                 end: dict[str, Any] | None = None, logs: Any = None) -> None:
        super().__init__(message)
        self.start = start
        self.end = end
        self.logs = logs


def bundle_identity() -> dict[str, Any] | None:
    """The frozen bench bundle's current identity, or None when absent.

    {"sha256": …, "mtime": …, "size": …, "path": …} — the sha256 comes from
    the sha file bin/seon's build step writes (never re-hashed here; a
    mid-recompile hash would race the compiler), mtime/size from the bundle
    itself. None = no frozen bundle on disk (watched-only use — nothing to
    pin, nothing to assert)."""
    if not BENCH_BUNDLE.is_file():
        return None
    st = BENCH_BUNDLE.stat()
    sha = (BENCH_BUNDLE_SHA.read_text().strip()
           if BENCH_BUNDLE_SHA.is_file() else None)
    return {"sha256": sha, "mtime": st.st_mtime, "size": st.st_size,
            "path": str(BENCH_BUNDLE.relative_to(REPO_ROOT))}


def bundle_violation(start: dict[str, Any] | None) -> str | None:
    """None when the bundle is unchanged since `start`; else what changed.

    `start=None` (no bundle existed at run start) asserts nothing. Any
    difference — sha, mtime, size, or the bundle vanishing — is a violation:
    samples before and after it ran DIFFERENT code."""
    if start is None:
        return None
    end = bundle_identity()
    if end == start:
        return None
    return (f"frozen bench bundle changed mid-run (start={start}, end={end}) "
            "— the run is contaminated; classify its executions as "
            "'frozen_bundle_changed' and publish no capability number")


def ensure_bench_bundle(runner: Callable[..., Any] = subprocess.run
                        ) -> dict[str, Any] | None:
    """Build/refresh the frozen bench bundle ONCE, up front — then create.

    `bin/seon bench-bundle` (staleness-guarded, mutexed supervisor-side) so a
    bench-cluster-N run never pays — or races — a compile inside a sample's
    boot window: after this, each concurrent create's own staleness check
    no-ops. Returns the resulting `bundle_identity()` (the pin the end-of-run
    assertion checks against)."""
    _run_seon(["bench-bundle"], runner, timeout_s=330)
    return bundle_identity()


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
                   frozen: bool | None = None,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """`bin/seon cluster create <name> [--ephemeral] [--frozen|--watched]`.

    `frozen=None` (default) leaves the bundle choice to the supervisor's own
    default — ephemeral ⇒ frozen bench bundle (unwatched; no mid-sample
    hot-patch), durable ⇒ watched. Pass True/False only to override
    (`--frozen`/`--watched`). The supervisor ready-gates the wire-server and
    the pod itself; the ready poll here is the harness-side confirmation
    (and yields the bound port)."""
    name = _check_name(name or bench_cluster_name())
    args = ["cluster", "create", name] + (["--ephemeral"] if ephemeral else [])
    if frozen is True:
        args.append("--frozen")
    elif frozen is False:
        args.append("--watched")
    # create ready-gates wire-server (180s) + pod (120s) internally, plus a
    # possible frozen-bundle compile (staleness-guarded, ~30s warm).
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
                      frozen: bool | None = None,
                      runner: Callable[..., Any] = subprocess.run,
                      ready: Callable[[str], int] = wait_pod_ready
                      ) -> Iterator[Cluster]:
    """create → yield → destroy (destroy always runs — no leaked clusters).

    Ephemeral ⇒ the supervisor's frozen-bundle default applies (see
    `create_cluster`); `frozen=False` opts a dev inner loop back into the
    watched bundle."""
    cluster = create_cluster(name, ephemeral=True, frozen=frozen,
                             runner=runner, ready=ready)
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
