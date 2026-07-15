"""Inspect's cluster coordinates and writer read-back helpers.

The current operator owns one configured cluster and exposes no per-sample
lease yet. Lease-dependent entry points therefore fail before invoking a
subprocess. This is deliberate: the retired create/destroy/per-pod commands
could mutate the wrong runtime and must not survive as a compatibility path.

The wire-server's loopback socket REPL (port in the per-supervisor file —
`$SEON_WRITER_REPL_PORT_FILE`, default `tmp/seon-writer-repl-port-default`)
survives pod restarts and sees every cluster's db through the registry —
`wire_repl_json` is the read-back channel the planning snapshot uses.

Nothing here talks to a pod at import time.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import socket
import subprocess
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterator, Sequence

REPO_ROOT = Path(__file__).resolve().parents[3]

# Temporary observation-only identity for the retired frozen output. The
# operator now publishes the maintained target artifact in structured status;
# no caller may build this legacy path. Keeping read-only identity lets
# existing offline contamination tests remain meaningful until the lease
# carries the canonical manifest.
BENCH_BUNDLE = REPO_ROOT / "out-bench" / "client" / "main.js"
BENCH_BUNDLE_SHA = BENCH_BUNDLE.parent / (BENCH_BUNDLE.name + ".sha256")

# Matches bin/seon's valid_cluster_name (a path segment + a wire db-name).
_NAME_RE = re.compile(r"^[a-zA-Z0-9_-]+$")

# ---------------------------------------------------------------------------
# Per-cluster LLM config (the thinking-arm lever) — config-DATA, never env
# ---------------------------------------------------------------------------
# The retained config transaction for an arm that changes model data (for
# example armD thinking). A future lease may call it only after ownership and
# writer endpoint selection. The pod reads
# the row PER CALL (seon.ai/current — the agent-override → config-row →
# shipped-defaults chain), the row survives pod restarts (the planning row's
# interruption), and boot re-seeding never retracts it (seon.ai/sync-tx-data
# is seed-once). Chosen over a solver-level setup eval because EVERY cluster
# creation (run_bench per-sample, tool_rows, planning driver) already funnels
# through `create_cluster` — one hook, zero plumbing, and no LLM-driven
# bootstrap turn polluting the sample's cluster db.
#
# `AI_CONFIG` remains data for callers/tests; cluster creation is paused.
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
    " (let [conn (:seon.db.registry/conn (seon.db.registry/lookup-connection"
    " {:seon.db.registry/database-name :%s}))"
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


class ClusterLeaseUnavailable(RuntimeError):
    """A live Inspect operation requires the operator's pending lease seam."""


def _lease_unavailable(operation: str) -> None:
    raise ClusterLeaseUnavailable(
        f"Inspect cannot {operation}: bin/seon currently exposes one configured "
        "target but no structured per-sample lease with cluster identity, "
        "artifact flavor/digest, dynamic web/CLJ/CLJS endpoints, and "
        "ownership-fenced restart/release. The retired cluster commands are "
        "intentionally not invoked.")


def bundle_identity() -> dict[str, Any] | None:
    """Observe the legacy frozen output without building or selecting it."""
    if not BENCH_BUNDLE.is_file():
        return None
    stat = BENCH_BUNDLE.stat()
    sha = (BENCH_BUNDLE_SHA.read_text().strip()
           if BENCH_BUNDLE_SHA.is_file() else None)
    return {"sha256": sha, "mtime": stat.st_mtime, "size": stat.st_size,
            "path": str(BENCH_BUNDLE.relative_to(REPO_ROOT))}


def bundle_violation(start: dict[str, Any] | None) -> str | None:
    """Report when an observed frozen output changed during an offline run."""
    if start is None:
        return None
    end = bundle_identity()
    if end == start:
        return None
    return (f"frozen bench bundle changed mid-run (start={start}, end={end}) "
            "— the run is contaminated; classify its executions as "
            "'frozen_bundle_changed' and publish no capability number")


def static_target_snapshot(
    cluster_url: str,
    status_command: Sequence[str],
    *,
    runner: Callable[..., Any] = subprocess.run,
) -> dict[str, Any]:
    """Read one ready static target identity from the semantic operator.

    The EDN is retained byte-for-byte because it already contains the
    operator-owned artifact digests, process identities, endpoints, database
    path, and target status. Inspect neither reconstructs nor mutates them.
    """
    command = list(status_command)
    if not command:
        raise ValueError("status_command must name the target operator")
    result = runner(
        command,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        lines = (result.stderr or result.stdout or "operator status failed")
        detail = lines.strip().splitlines()[0] if lines.strip() else "operator status failed"
        raise RuntimeError(f"static target status failed: {detail}")
    status_edn = result.stdout.strip()
    target_url = cluster_url.removesuffix("/agents/run").rstrip("/")
    if ":status :seon.dev.target.status/ready" not in status_edn:
        raise RuntimeError("static target status is not ready")
    if f':url "{target_url}"' not in status_edn:
        raise RuntimeError(
            "static target status URL does not match the selected pod door")
    return {
        "cluster_url": cluster_url,
        "status_command": command,
        "status_edn": status_edn,
        "status_sha256": hashlib.sha256(status_edn.encode()).hexdigest(),
    }


def ensure_bench_bundle(runner: Callable[..., Any] = subprocess.run
                        ) -> dict[str, Any] | None:
    """Fail until the operator can lease a pinned artifact flavor."""
    del runner
    _lease_unavailable("prepare a frozen benchmark artifact")


@dataclass(frozen=True)
class Cluster:
    """Explicit coordinates for one already-owned cluster."""
    name: str
    port: int

    @property
    def url(self) -> str:
        """The pod door — POST /agents/run on this cluster's pod."""
        return f"http://127.0.0.1:{self.port}/agents/run"


def bench_cluster_name(prefix: str = "bench") -> str:
    """A fresh, collision-proof cluster name for one bench sample."""
    return f"{prefix}-{uuid.uuid4().hex[:12]}"


def _check_name(name: str) -> str:
    if not _NAME_RE.match(name or ""):
        raise ValueError(f"invalid cluster name: {name!r} (a-zA-Z0-9_- only)")
    return name


def create_cluster(name: str | None = None, *, ephemeral: bool = True,
                   frozen: bool | None = None,
                   extra_env: dict[str, str] | None = None,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] | None = None) -> Cluster:
    """Fail until `bin/seon` exposes a structured owned cluster lease."""
    _check_name(name or bench_cluster_name())
    del ephemeral, frozen, extra_env, runner, ready
    _lease_unavailable("create an Inspect sample cluster")


def fork_cluster(source: str, basis_t: int, name: str | None = None, *,
                 runner: Callable[..., Any] = subprocess.run,
                 ready: Callable[[str], int] | None = None) -> Cluster:
    """Validate a fork request, then fail at the pending lease seam."""
    source = _check_name(source)
    if isinstance(basis_t, bool) or not isinstance(basis_t, int) or basis_t < 0:
        raise ValueError(f"basis_t must be a non-negative transaction id, got {basis_t!r}")
    _check_name(name or f"fork-{source}-{basis_t}-{uuid.uuid4().hex[:8]}")
    del runner, ready
    _lease_unavailable("fork an Inspect sample cluster")


def restart_pod(cluster: Cluster, *,
                extra_env: dict[str, str] | None = None,
                runner: Callable[..., Any] = subprocess.run,
                ready: Callable[[str], int] | None = None) -> Cluster:
    """Fail until a lease can restart only its owned sample processes."""
    _check_name(cluster.name)
    del extra_env, runner, ready
    _lease_unavailable("restart an Inspect sample cluster")


def destroy_cluster(name: str, *,
                    runner: Callable[..., Any] = subprocess.run) -> None:
    """Fail until lease release is idempotent and ownership-fenced."""
    _check_name(name)
    del runner
    _lease_unavailable("release an Inspect sample cluster")


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
                      ready: Callable[[str], int] | None = None
                      ) -> Iterator[Cluster]:
    """Lease one sample cluster once the operator provides that transition."""
    cluster = create_cluster(name, ephemeral=True, frozen=frozen,
                             extra_env=extra_env, runner=runner, ready=ready)
    try:
        yield cluster
    finally:
        destroy_cluster(cluster.name, runner=runner)


@contextlib.contextmanager
def ephemeral_fork(source: str, basis_t: int, name: str | None = None, *,
                   runner: Callable[..., Any] = subprocess.run,
                   ready: Callable[[str], int] | None = None
                   ) -> Iterator[Cluster]:
    """Lease one counterfactual cluster once the operator supports it."""
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
# (`bin/seon` exports it). The old unqualified shared file is dead.
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
            "is the selected cluster running? (bin/seon status)")
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
