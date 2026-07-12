"""Cluster lifecycle from the harness — create / restart / destroy + wire REPL.

A cluster is the isolation unit (one shared DB + one Node pod; the wire-server
JVM hosts every cluster's db). The supervisor owns the mechanics — this module
just drives `bin/seon cluster create|destroy` and `bin/seon restart pod-<n>`
as subprocesses, reads the per-cluster port file (`tmp/seon-port-<n>`), and
ready-polls the pod's HTTP door. Per-sample benching = one ephemeral cluster
per sample (`ephemeral_cluster()`); the planning row keeps ITS cluster alive
across a mid-sample pod restart (`restart_pod`, which mints a NEW ephemeral
port — always take the returned Cluster).

The wire-server's loopback socket REPL (port in the per-supervisor file —
`$SEON_WRITER_REPL_PORT_FILE`, default `tmp/seon-writer-repl-port-default`)
survives pod restarts and sees every cluster's db through the registry —
`wire_repl_json` is the read-back channel the planning snapshot uses.

Effects are injectable (`runner=subprocess.run`) so the sequencing is
unit-tested offline with fakes; nothing here talks to a pod at import time.
"""

from __future__ import annotations

import contextlib
import os
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

# ---------------------------------------------------------------------------
# Per-cluster LLM config (the thinking-arm lever) — config-DATA, never env
# ---------------------------------------------------------------------------
# The ONE uniform lever for an arm that changes the model config (e.g. armD
# thinking): after `create_cluster`'s ready gate, transact the cluster's
# GLOBAL `:seon.ai/id "config"` row over the wire-server REPL. The pod reads
# the row PER CALL (seon.ai/current — the agent-override → config-row →
# shipped-defaults chain), the row survives pod restarts (the planning row's
# interruption), and boot re-seeding never retracts it (seon.ai/sync-tx-data
# is seed-once). Chosen over a solver-level setup eval because EVERY cluster
# creation (run_bench per-sample, tool_rows, planning driver) already funnels
# through `create_cluster` — one hook, zero plumbing, and no LLM-driven
# bootstrap turn polluting the sample's cluster db.
#
# Set `AI_CONFIG` from the run script for the whole run; None (default) = the
# hook is inert and clusters keep the pod's shipped defaults.
AI_CONFIG: dict[str, Any] | None = None

# Datahike schema for the config-row attrs, DERIVED (not authored) via the one
# bridge `seon.db/malli->datahike-schema` on the live pod, 2026-07-04:
#   (seon.db/malli->datahike-schema
#     [:seon.ai/id :seon.ai/thinking :seon.ai/timeout-ms])
# Needed because a fresh cluster installs schema lazily on first WRITE and the
# wire REPL bypasses the pod-side installer. Install-if-missing only; if the
# source schemas ever drift, the per-run model_config verification fails loud
# (a sample whose model_config disagrees with AI_CONFIG is a harness defect,
# never scored). COMPLEXITY ARTIFACT (flagged, accepted): this duplicates the
# bridge's OUTPUT for three stable attrs because no non-LLM pod-side write
# door exists for a bench cluster — subsume it if such a door lands.
_AI_ATTR_SCHEMA_EDN = (
    '[{:db/ident :seon.ai/id :db/valueType :db.type/string'
    ' :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}'
    ' {:db/ident :seon.ai/thinking :db/valueType :db.type/string'
    ' :db/cardinality :db.cardinality/one}'
    ' {:db/ident :seon.ai/timeout-ms :db/valueType :db.type/long'
    ' :db/cardinality :db.cardinality/one}]'
)

# %s slots: db-name keyword, config-row map EDN (merged over :seon.ai/id).
_AI_CONFIG_FORM = (
    "(do (require (quote [cheshire.core :as json]) (quote [datahike.api :as d]))"
    " (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn"
    " {:seon.server.registry/db-name :%s}))"
    " installed (set (keys (d/schema @conn)))"
    " schema-tx (into [] (remove (fn [s] (installed (:db/ident s)))) " + _AI_ATTR_SCHEMA_EDN + ")"
    " _ (when (seq schema-tx) (d/transact conn {:tx-data schema-tx}))"
    " _ (d/transact conn {:tx-data [(merge {:seon.ai/id \"config\"} %s)]})"
    " row (d/q (quote [:find (pull ?e [*]) . :where [?e :seon.ai/id \"config\"]]) @conn)]"
    " (println (str \"WIRE-JSON<\" (json/generate-string"
    " {\"thinking\" (:seon.ai/thinking row)"
    "  \"timeout_ms\" (:seon.ai/timeout-ms row)}) \">WIRE-JSON\"))))"
)

_AI_CONFIG_KEYS = {"thinking": ":seon.ai/thinking",
                   "timeout_ms": ":seon.ai/timeout-ms"}


def _ai_config_edn(cfg: dict[str, Any]) -> str:
    """The config dict as an EDN map literal ({\"thinking\": \"true\", …})."""
    parts = []
    for key, attr in _AI_CONFIG_KEYS.items():
        if key not in cfg:
            continue
        v = cfg[key]
        parts.append(f"{attr} {json.dumps(v) if isinstance(v, str) else v}")
    unknown = set(cfg) - set(_AI_CONFIG_KEYS)
    if unknown:
        raise ValueError(f"unsupported AI_CONFIG keys: {sorted(unknown)} "
                         f"(supported: {sorted(_AI_CONFIG_KEYS)})")
    return "{" + " ".join(parts) + "}"


def apply_ai_config(name: str, cfg: dict[str, Any],
                    repl: Callable[..., Any] | None = None) -> dict[str, Any]:
    """Transact `cfg` onto cluster `name`'s global `:seon.ai` config row.

    Runs after the cluster's ready gate; returns the read-back row fields
    ({"thinking": …, "timeout_ms": …}) and RAISES when the read-back doesn't
    carry what was asked (fail-loud: a cluster that didn't take the config
    must never silently run the arm's samples)."""
    call = repl or wire_repl_json
    out = call(_AI_CONFIG_FORM % (_check_name(name), _ai_config_edn(cfg)))
    if "thinking" in cfg and out.get("thinking") != cfg["thinking"]:
        raise RuntimeError(
            f"cluster {name}: AI config read-back mismatch — asked "
            f"thinking={cfg['thinking']!r}, row says {out.get('thinking')!r}")
    return out


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
              timeout_s: int,
              extra_env: dict[str, str] | None = None) -> None:
    # extra_env is passed ONLY when set, so injected fake runners in the
    # offline tests (which don't accept an env kwarg) stay compatible.
    kwargs: dict[str, Any] = {}
    if extra_env:
        import os
        kwargs["env"] = {**os.environ, **extra_env}
    proc = runner([str(SEON_BIN), *args], cwd=str(REPO_ROOT),
                  capture_output=True, text=True, timeout=timeout_s,
                  **kwargs)
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
                   extra_env: dict[str, str] | None = None,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """`bin/seon cluster create <name> [--ephemeral] [--frozen|--watched]`.

    `frozen=None` (default) leaves the bundle choice to the supervisor's own
    default — ephemeral ⇒ frozen bench bundle (unwatched; no mid-sample
    hot-patch), durable ⇒ watched. Pass True/False only to override
    (`--frozen`/`--watched`). `extra_env` adds host-owned env grants to the
    create's environment — the spawned pod inherits them (e.g.
    `{"SEON_SHELL": "0"}` to deny a capability for one bench cluster; web
    reachability is now a config policy, not env). The supervisor ready-gates the
    wire-server and the pod itself; the ready poll here is the harness-side
    confirmation (and yields the bound port)."""
    name = _check_name(name or bench_cluster_name())
    args = ["cluster", "create", name] + (["--ephemeral"] if ephemeral else [])
    if frozen is True:
        args.append("--frozen")
    elif frozen is False:
        args.append("--watched")
    # create ready-gates wire-server (180s) + pod (120s) internally, plus a
    # possible frozen-bundle compile (staleness-guarded, ~30s warm).
    _run_seon(args, runner, timeout_s=330, extra_env=extra_env)
    cluster = Cluster(name=name, port=ready(name))
    if AI_CONFIG:
        # Post-create hook (see AI_CONFIG above): the arm's model config as
        # config-data on the cluster's own db — applied AFTER the pod's boot
        # seed so seed-once can't race it, BEFORE any sample drives the pod.
        apply_ai_config(name, AI_CONFIG)
    return cluster


def fork_cluster(source: str, basis_t: int, name: str | None = None, *,
                 runner: Callable[..., Any] = subprocess.run,
                 ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """Fork `source` at `basis_t` into a disposable, writable cluster.

    This is the counterfactual entry point: the new cluster carries the source
    database and blob store as they were at the supplied transaction id, while
    its pod and subsequent writes are isolated. Callers must pin the runtime
    artifact, manifest, model configuration, and replay stimulus separately;
    a database fork intentionally restores only durable world state.

    `name` is fresh by default so independent Inspect samples cannot collide.
    The supervisor starts and ready-gates the fork pod; the returned cluster
    always uses the fork's newly assigned HTTP port.
    """
    source = _check_name(source)
    if isinstance(basis_t, bool) or not isinstance(basis_t, int) or basis_t < 0:
        raise ValueError(f"basis_t must be a non-negative transaction id, got {basis_t!r}")
    target = _check_name(name or f"fork-{source}-{basis_t}-{uuid.uuid4().hex[:8]}")
    _run_seon(["cluster", "fork", source, str(basis_t), target], runner,
              timeout_s=330)
    return Cluster(name=target, port=ready(target))


def restart_pod(cluster: Cluster, *,
                extra_env: dict[str, str] | None = None,
                runner: Callable[..., Any] = subprocess.run,
                ready: Callable[[str], int] = wait_pod_ready) -> Cluster:
    """`bin/seon restart pod-<name>` — the planning row's interruption.

    `extra_env` MUST carry the same host-owned env the create used (e.g.
    `SEON_CONFIG` for a minimal-context cluster) — a bare restart re-exports
    the default manifest and the boot reconcile re-seeds the config
    singleton from it, silently swapping the cluster's whole context
    (the documented minimal.edn gotcha; bit rung 2, 2026-07-10).

    The pod rebinds an EPHEMERAL port on boot, so the stale port file is
    removed first and the returned Cluster carries the NEW port (the old
    Cluster's url is dead — always continue with the return value).

    Since 2026-07-04 the SUPERVISOR ready-gates `restart pod-<n>` internally
    (parity with `create` — its `wait_ready`/`ready_check` blocks up to the
    pod's 120s bound until the port file is written AND the pod answers HTTP),
    so `_run_seon` here now returns only once the pod is ready. That closes
    the restart-vs-create asymmetry that let a tail-latency reboot blow the
    tight 60s CLUSTER_BOOT_BUDGET_S
    (docs/prds/agent-ctx/research/cluster-boot-timeout-2026-07-04.md). The
    180s subprocess timeout accommodates that internal wait; a failed reboot
    surfaces as a non-zero exit → RuntimeError, not a silent early return.

    `ready(...)` below is KEPT as a cheap backstop, NOT a duplicated wait:
    the supervisor already gated, so this poll returns on its first tick —
    its job now is to read the bound port for the returned Cluster (and to
    cover any sliver between the supervisor's curl-ready and our http.client
    probe). Defense-in-depth, near-instant in the common path."""
    _check_name(cluster.name)
    _port_file(cluster.name).unlink(missing_ok=True)
    _run_seon(["restart", f"pod-{cluster.name}"], runner, timeout_s=180,
              extra_env=extra_env)
    return Cluster(name=cluster.name, port=ready(cluster.name))


def destroy_cluster(name: str, *,
                    runner: Callable[..., Any] = subprocess.run) -> None:
    """`bin/seon cluster destroy <name>` — pod stopped, registry db deleted,
    data/clusters/<name>/ removed (blobs included)."""
    _run_seon(["cluster", "destroy", _check_name(name)], runner, timeout_s=120)


class StubLLMBooted(RuntimeError):
    """The cluster's pod booted on the STUB LLM (a provider with no key).

    The 2026-07-10 spark trap: a configured provider whose key env is unset
    boots a stub that returns canned text, so a drive "runs" and SCORES
    GARBAGE. `seon.client` emits `SEON-STUB-LLM` at boot for exactly this
    case; `assert_llm_live` refuses to drive such a cluster (raised, never a
    silent score — the same fail-loud rule as the oracle-liveness gate)."""


_POD_LOG = lambda name: REPO_ROOT / "logs" / f"pod-{name}.log"  # noqa: E731
_STUB_MARKER = "SEON-STUB-LLM"


def assert_llm_live(name: str) -> None:
    """Refuse to drive cluster `name` if its pod booted on the STUB LLM.

    Ported from `min-drive.sh`'s boot guard: scan `logs/pod-<name>.log` for
    the `SEON-STUB-LLM` marker `seon.client` prints when a configured provider
    has no key. A missing log is treated as live (the marker is opt-in and
    absent on a healthy boot); a present marker raises `StubLLMBooted`."""
    log = _POD_LOG(_check_name(name))
    if log.is_file() and _STUB_MARKER in log.read_text(errors="replace"):
        raise StubLLMBooted(
            f"cluster {name}: pod booted on the STUB LLM (saw {_STUB_MARKER} "
            f"in {log.relative_to(REPO_ROOT)}) — export the provider key and "
            "recreate the cluster; a keyless drive scores garbage")


@contextlib.contextmanager
def ephemeral_cluster(name: str | None = None, *,
                      frozen: bool | None = None,
                      extra_env: dict[str, str] | None = None,
                      runner: Callable[..., Any] = subprocess.run,
                      ready: Callable[[str], int] = wait_pod_ready
                      ) -> Iterator[Cluster]:
    """create → yield → destroy (destroy always runs — no leaked clusters).

    Ephemeral ⇒ the supervisor's frozen-bundle default applies (see
    `create_cluster`); `frozen=False` opts a dev inner loop back into the
    watched bundle. `extra_env` rides through to the create (host-owned
    grants for this cluster's pod)."""
    cluster = create_cluster(name, ephemeral=True, frozen=frozen,
                             extra_env=extra_env, runner=runner, ready=ready)
    try:
        yield cluster
    finally:
        destroy_cluster(cluster.name, runner=runner)


@contextlib.contextmanager
def ephemeral_fork(source: str, basis_t: int, name: str | None = None, *,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] = wait_pod_ready
                   ) -> Iterator[Cluster]:
    """fork → yield → destroy a counterfactual cluster.

    The source snapshot remains untouched. The fork's pod is ready before the
    body runs, and cleanup runs on both normal and exceptional exits.
    """
    cluster = fork_cluster(source, basis_t, name, runner=runner, ready=ready)
    try:
        yield cluster
    finally:
        destroy_cluster(cluster.name, runner=runner)


# ---------------------------------------------------------------------------
# Wire-server socket REPL — the supervisor-facing read-back channel
# ---------------------------------------------------------------------------

# Per-supervisor port file (seon registry C48): the harness benches through
# the DEFAULT supervisor's wire-server, whose file is
# tmp/seon-writer-repl-port-default; $SEON_WRITER_REPL_PORT_FILE overrides
# (bin/seon exports it). The old shared tmp/seon-writer-repl-port is dead —
# a second supervisor (bin/acme) clobbered it.
def _wire_repl_port_file() -> Path:
    p = Path(os.environ.get("SEON_WRITER_REPL_PORT_FILE",
                            "tmp/seon-writer-repl-port-default"))
    return p if p.is_absolute() else REPO_ROOT / p


WIRE_REPL_PORT_FILE = _wire_repl_port_file()


def wire_repl_port() -> int:
    """The wire-server's loopback socket-REPL port (written at its boot)."""
    if not WIRE_REPL_PORT_FILE.is_file():
        raise RuntimeError(
            f"wire-server REPL port file missing: {WIRE_REPL_PORT_FILE} — "
            "is the wire-server running? (bin/seon start wire-server)")
    return int(WIRE_REPL_PORT_FILE.read_text().strip())


def parse_wire_json(text: str) -> Any:
    """Extract + parse the `WIRE-JSON<{...}>WIRE-JSON` sentinel from REPL text.

    The one wire-REPL reply parser — shared by the host loopback channel
    (`wire_repl_json`) and the in-container exec channel (the SWE-bench arm's
    run-bounds delivery, `swebench_arm.apply_run_bounds`). Sentinels make the
    extraction robust against REPL prompts/echoes; raises when absent."""
    m = re.search(r"WIRE-JSON<(.*)>WIRE-JSON", text, re.DOTALL)
    if not m:
        raise RuntimeError(
            f"wire REPL reply carried no WIRE-JSON sentinel: {text!r:.400}")
    return json.loads(m.group(1))


def wire_repl_json(form: str, *, port: int | None = None,
                   timeout_s: int = 30) -> Any:
    """Eval ONE Clojure form on the wire-server REPL; parse a sentinel line.

    The form must print exactly one line `WIRE-JSON<{...}>WIRE-JSON` (JSON
    between the sentinels — e.g. via cheshire, on the wire-server classpath)."""
    p = port or wire_repl_port()
    with socket.create_connection(("127.0.0.1", p), timeout=timeout_s) as s:
        s.settimeout(timeout_s)
        s.sendall(form.strip().encode() + b"\n")
        s.shutdown(socket.SHUT_WR)
        buf = b""
        with contextlib.suppress(TimeoutError, OSError):
            while chunk := s.recv(65536):
                buf += chunk
    return parse_wire_json(buf.decode(errors="replace"))
