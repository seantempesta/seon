"""SWE-bench Seon arm — the A-overlay composition (design §2b/§2d, slice 3).

Mechanism: the canonical Seon image's `/opt/seon` tree, extracted ONCE at a
pinned image digest into a docker named volume, is mounted READ-ONLY into the
UNMODIFIED official epoch instance image via inspect_evals' `sandbox_config`
seam (`swe_bench.py:69`, applied `:154-155`). The per-sample compose mimics
their `_create_docker_spec` (`:227-250`: instance image, working_dir
/testbed) plus: the overlay mount, a writable anonymous `/seon-data` volume,
a published per-sample pod port, and the boot command (`seon-entrypoint all`
— wire-server → ready-gate → pod, all INSIDE the task container). The
official scorer's `sandbox().exec` path is untouched; our mounts add paths
only under /opt/seon + /seon-data.

Two compose flavours:
  - `overlay_sandbox_config(boot=False)` — NULL-RUN: mounts only, official
    `sleep infinity` + `network_mode: none` kept. Proves the overlay does
    not perturb the oracle (identical verdict vs the vanilla compose).
  - `overlay_sandbox_config(boot=True)` — the agent arm: Seon boots inside;
    the container gets egress (the ONE deviation from their default compose,
    design §2c — the in-container pod must reach the LLM API; interim
    condition until the egress allowlist lands, recorded on every row).

The solver (`seon_swebench_solver`) drives the ROOT agent through the
existing door machinery (`solver.pod_run` — no parallel transport): waits
for the in-container pod on the sample's published port, POSTs the §3
goal-stated task contract (problem statement + workspace + judging rule +
explicit done signal — nothing beyond what every scaffold gets), and records
the honest door metadata via `solver._record_result`. Done = the ROOT's
terminal reply; goal met = the OFFICIAL scorer's verdict on /testbed.
"""

from __future__ import annotations

import hashlib
import textwrap
import time
import urllib.request
from pathlib import Path

from inspect_ai.solver import Generate, TaskState, solver
from inspect_ai.util import SandboxEnvironmentSpec

from seon_inspect import solver as seon_solver

REPO_ROOT = Path(__file__).resolve().parents[3]

# The runtime-overlay volume: /opt/seon extracted from the canonical image at
# a pinned digest (slice 3 pin: seon:slice1 sha256:63db32776190…; the volume
# name + source digest are recorded in the run's evidence + ledger notes).
OVERLAY_VOLUME = "seon-runtime-slice3"

# Per-sample published pod ports: deterministic in [PORT_BASE, PORT_BASE+SPAN).
POD_PORT_BASE = 17900
POD_PORT_SPAN = 100

# Compose files land under the repo's gitignored tmp/ (mirrors inspect_evals'
# own cache-dir compose files; never a PRD dir, never committed).
COMPOSE_DIR = REPO_ROOT / "tmp" / "slice3-compose"

# SWE-bench trajectories dwarf the QA-class defaults (design §7): generous
# wall budget for this slice; the pod's own run bounds (turn-limit 20 /
# deadline 600s — the standing cluster-level-bounds cross-lane ask) still cut
# first and surface as `behavior_miss`, honestly attributed.
SWEBENCH_RUN_TIMEOUT_S = 900

# In-container pod readiness budget: entrypoint boot measured ~10-25s (fresh
# seed) inside the sympy instance image; 300s covers a cold, contended boot.
POD_READY_TIMEOUT_S = 300


def sample_port(sample_id: str) -> int:
    """Deterministic per-sample published host port for the pod door."""
    h = int(hashlib.sha256(str(sample_id).encode()).hexdigest(), 16)
    return POD_PORT_BASE + (h % POD_PORT_SPAN)


def _compose_yaml(image: str, *, boot: bool, volume: str,
                  host_port: int | None) -> str:
    """The per-sample compose text (see the module docstring for the shape)."""
    if boot:
        assert host_port is not None
        service = textwrap.dedent(f"""\
            services:
              default:
                image: {image}
                command: ["/opt/seon/seon-entrypoint", "all"]
                working_dir: /testbed
                mem_limit: 6gb
                ports:
                  - "127.0.0.1:{host_port}:7890"
                environment:
                  - SEON_BIND=0.0.0.0
                  - SEON_RUNTIME_ROOT=/opt/seon
                  - SEON_SHELL=1
                  - DEEPSEEK_API_KEY
            """)
    else:
        # Null-run: byte-for-byte the official compose (sleep infinity,
        # network_mode: none, /testbed) + ONLY the two mounts.
        service = textwrap.dedent(f"""\
            services:
              default:
                image: {image}
                command: sleep infinity
                working_dir: /testbed
                network_mode: none
            """)
    return service + _volumes_block(volume)


def _volumes_block(volume: str) -> str:
    """The service-level mounts + the external named-volume declaration."""
    return (
        f"    volumes:\n"
        f"      - {volume}:/opt/seon:ro\n"
        f"      - /seon-data\n"
        f"volumes:\n"
        f"  {volume}:\n"
        f"    external: true\n"
    )


def overlay_sandbox_config(*, boot: bool, volume: str = OVERLAY_VOLUME):
    """A `sandbox_config` callable for `inspect_evals.swe_bench` (§2b seam).

    `boot=False` → null-run compose (mounts only, no Seon, no network);
    `boot=True` → the agent arm (entrypoint boots the cluster inside, pod
    port published per sample and stamped into
    `sample.metadata["seon_pod_port"]` for the solver)."""

    def create_spec(sandbox_type: str, sample) -> SandboxEnvironmentSpec:
        assert sandbox_type == "docker", (
            f"A-overlay compose is docker-only (got {sandbox_type!r})")
        image = sample.metadata["image_name"]
        host_port = sample_port(str(sample.id)) if boot else None
        if boot:
            sample.metadata["seon_pod_port"] = host_port
        COMPOSE_DIR.mkdir(parents=True, exist_ok=True)
        flavour = "seon" if boot else "null"
        config_file = COMPOSE_DIR / f"{sample.id}-{flavour}-compose.yaml"
        config_file.write_text(
            _compose_yaml(image, boot=boot, volume=volume,
                          host_port=host_port))
        return SandboxEnvironmentSpec(type=sandbox_type,
                                      config=str(config_file))

    return create_spec


@solver
def noop_solver():
    """Submit nothing: the null-run's solver (repo state left UNMODIFIED)."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        state.output.completion = ""
        return state

    return solve


def task_contract(problem_statement: str) -> str:
    """The §3 goal-stated task contract around the OFFICIAL problem statement.

    States the environment facts, every scored check's judging rule, and the
    explicit done signal — and nothing else (no hints, no test names; the
    FAIL_TO_PASS names are withheld exactly as the official bench withholds
    them)."""
    return (
        f"{problem_statement}\n\n"
        "Your workspace is the repository checked out at /testbed inside "
        "this machine — your shell runs here. Fix the issue by editing the "
        "repository files under /testbed.\n\n"
        "How your work is judged: after you finish, this repository's own "
        "test suite is run against the files you leave in /testbed. Only "
        "the final state of the files matters.\n\n"
        "When the goal is met, send the user a short summary of the change "
        "you made (reply via message/user) — that reply is the done signal."
    )


def wait_pod_ready(port: int, timeout_s: int = POD_READY_TIMEOUT_S) -> float:
    """Block until the in-container pod answers HTTP on the published port.

    Returns the seconds waited; raises TimeoutError past `timeout_s` (the
    §7 `seon_env_fault` signal — the cluster never came up inside the task
    container, a flake class, never a model score)."""
    url = f"http://127.0.0.1:{port}/"
    start = time.monotonic()
    while time.monotonic() - start < timeout_s:
        try:
            with urllib.request.urlopen(url, timeout=2) as resp:
                if resp.status == 200:
                    return time.monotonic() - start
        except OSError:
            pass
        time.sleep(1)
    raise TimeoutError(
        f"in-container pod not ready on 127.0.0.1:{port} within {timeout_s}s "
        "(seon_env_fault — check the sandbox container's entrypoint log)")


@solver
def seon_swebench_solver(timeout_s: int = SWEBENCH_RUN_TIMEOUT_S):
    """Drive the ROOT agent of the in-container cluster on one instance.

    Per sample: wait for the pod on the sample's stamped published port,
    POST the §3 contract to `POST /agents/run` with `agent_id="root"` (the
    bench drives the ROOT agent — design §2a swarm clarification), record
    the honest door metadata. The OFFICIAL scorer then judges /testbed."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        port = (state.metadata or {}).get("seon_pod_port")
        assert port, ("no seon_pod_port in sample metadata — was the task "
                      "built with overlay_sandbox_config(boot=True)?")
        url = f"http://127.0.0.1:{port}/agents/run"
        timeout_ms = seon_solver._resolve_timeout_ms(state, timeout_s)

        def drive() -> dict:
            waited = wait_pod_ready(int(port))
            out = seon_solver.pod_run(task_contract(state.input_text),
                                      timeout_ms, url, agent_id="root")
            out["pod_boot_wait_s"] = round(waited, 1)
            return out

        result = await anyio.to_thread.run_sync(drive)
        state = seon_solver._record_result(state, result)
        state.metadata["pod_boot_wait_s"] = result.get("pod_boot_wait_s")
        return state

    return solve
