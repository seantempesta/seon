"""SWE-bench Seon arm — the A-overlay composition (design §2b/§2d, slice 3+).

Mechanism: the canonical Seon image's `/opt/seon` tree, extracted ONCE at a
pinned image digest into a docker named volume, is mounted READ-ONLY into the
UNMODIFIED official epoch instance image via inspect_evals' `sandbox_config`
seam (`swe_bench.py:69`, applied `:154-155`). The per-sample compose mimics
their `_create_docker_spec` (`:227-250`: instance image, working_dir
/testbed) plus: the overlay mount, a writable anonymous `/seon-data` volume,
a published per-sample pod port, and the boot command (`seon-entrypoint all`
— wire-server → ready-gate → pod, all INSIDE the task container). The
official scorer's `sandbox().exec` path is untouched; our mounts add paths
only under /opt/seon + /seon-data (plus the ONE entrypoint file overlay
below). Pre-slice-4 debt unit (2026-07-05) added three properties:

- **Workspace-rooted writable fs grant:** the bench pod runs with
  `SEON_FS_ROOT=/testbed` and read-only OFF, so the agent's fs verbs edit
  the repo directly (slice 3 inherited the image's immutable
  `/opt/seon`-read-only grant and could only write via shell). Delivered by
  bind-mounting the REPO's `docker/seon-entrypoint` (env-overridable fs
  grant) over the pinned volume's copy — the volume and image digests stay
  untouched; the mounted file's sha256 is stamped into sample metadata.
- **Run bounds (interim, until cluster-level config lands):** turn-limit +
  deadline are transacted onto the ROOT AGENT entity over the in-container
  wire REPL (via `sandbox().exec` + the bundled node — the `apply_ai_config`
  precedent, `cluster.py`) after boot, BEFORE the task posts. `open-run!`
  seeds each run from `:seon.agent.run/default-turn-limit` /
  `:seon.agent.run/default-deadline-ms` (`src/seon/agent/run.cljs:263-270`).
- **Model-API-only egress (the default):** the task container joins an
  `internal: true` network only; a socat relay sits on both that network and
  an egress network, carries the network-alias `api.deepseek.com`, and TCP-
  passthrough-forwards :443 to the REAL endpoint's host-resolved IP (TLS
  untouched — SNI/cert intact, zero pod config) — nothing else is reachable.
  The relay also forwards the published pod port (an internal-only service
  cannot publish ports itself). `open_egress=True` is the recorded escape
  hatch back to unrestricted egress.

Two compose flavours:
  - `overlay_sandbox_config(boot=False)` — NULL-RUN: mounts only, official
    `sleep infinity` + `network_mode: none` kept. Proves the overlay does
    not perturb the oracle (identical verdict vs the vanilla compose).
  - `overlay_sandbox_config(boot=True)` — the agent arm: Seon boots inside;
    egress is model-API-only by default (design §2c's named gap, now built).

The solver (`seon_swebench_solver`) drives the ROOT agent through the
existing door machinery (`solver.pod_run` — no parallel transport): waits
for the in-container pod on the sample's published port, applies the run
bounds, POSTs the §3 goal-stated task contract (problem statement +
workspace + judging rule + explicit done signal — nothing beyond what every
scaffold gets), and records the honest door metadata via
`solver._record_result`. Done = the ROOT's terminal reply; goal met = the
OFFICIAL scorer's verdict on /testbed.
"""

from __future__ import annotations

import hashlib
import socket as pysocket
import textwrap
import time
import urllib.request
from pathlib import Path

from inspect_ai.solver import Generate, TaskState, solver
from inspect_ai.util import SandboxEnvironmentSpec

from seon_inspect import solver as seon_solver
from seon_inspect.cluster import parse_wire_json

REPO_ROOT = Path(__file__).resolve().parents[3]

# The runtime-overlay volume: /opt/seon extracted from the canonical image at
# a pinned digest (slice 3 pin: seon:slice1 sha256:63db32776190…; the volume
# name + source digest are recorded in the run's evidence + ledger notes).
OVERLAY_VOLUME = "seon-runtime-slice3"

# The repo's entrypoint, bind-mounted over the pinned volume's copy so the
# bench compose can grant a workspace-rooted writable fs root via env (the
# volume's baked entrypoint predates env-overridable SEON_FS_ROOT). The
# mounted file's sha256 is stamped into sample metadata per run.
ENTRYPOINT_FILE = REPO_ROOT / "docker" / "seon-entrypoint"

# The ONE host the bench container may reach: the model API. Resolved to an
# IP on the HOST at compose-generation time (the relay must not resolve the
# name itself — on the internal network the name aliases the relay).
MODEL_API_HOST = "api.deepseek.com"

# Per-sample published pod ports: deterministic in [PORT_BASE, PORT_BASE+SPAN).
POD_PORT_BASE = 17900
POD_PORT_SPAN = 100

# Compose files land under the repo's gitignored tmp/ (mirrors inspect_evals'
# own cache-dir compose files; never a PRD dir, never committed).
COMPOSE_DIR = REPO_ROOT / "tmp" / "slice3-compose"

# SWE-bench trajectories dwarf the QA-class defaults (design §7): generous
# wall budget for this slice. The run's OWN bounds (below) cut first and
# surface as `behavior_miss`, honestly attributed.
SWEBENCH_RUN_TIMEOUT_S = 900

# Interim run bounds (until the tooling lane's cluster-level config lands):
# transacted onto the root agent per sample. Turn limit sized 2× the slice-3
# observation (12 turns consumed of the default 20); the deadline default
# tracks the solve timeout so the pod's own bound cuts before the door clock.
DEFAULT_TURN_LIMIT = 40

# In-container pod readiness budget: entrypoint boot measured ~10-25s (fresh
# seed) inside the sympy instance image; 300s covers a cold, contended boot.
POD_READY_TIMEOUT_S = 300


def sample_port(sample_id: str) -> int:
    """Deterministic per-sample published host port for the pod door."""
    h = int(hashlib.sha256(str(sample_id).encode()).hexdigest(), 16)
    return POD_PORT_BASE + (h % POD_PORT_SPAN)


def entrypoint_sha256() -> str:
    """sha256 of the repo entrypoint the bench compose mounts (evidence pin)."""
    return hashlib.sha256(ENTRYPOINT_FILE.read_bytes()).hexdigest()


def resolve_model_api_ip(host: str = MODEL_API_HOST) -> str:
    """Host-resolve the model API to an IP for the relay's forward target.

    The relay cannot resolve the NAME itself: on the internal network the
    name is its own alias (the loop). Per-sample compose generation resolves
    fresh, so a rotated endpoint IP never outlives one sample."""
    return pysocket.gethostbyname(host)


# The agent-arm pod env + mounts, shared by both egress stances.
# SEON_FS_ROOT=/testbed + read-only OFF is the workspace-rooted fs grant
# (the repo entrypoint honors env overrides; the bind-mount below puts it
# over the pinned volume's pre-override copy).
_BENCH_ENV = """\
    environment:
      - SEON_BIND=0.0.0.0
      - SEON_RUNTIME_ROOT=/opt/seon
      - SEON_SHELL=1
      - SEON_FS_ROOT=/testbed
      - SEON_FS_READ_ONLY=0
      - DEEPSEEK_API_KEY
"""


def _bench_volumes(volume: str) -> str:
    """The agent-arm service mounts: overlay RO + entrypoint file + data.

    The repo entrypoint mounts at CONTAINER ROOT (/seon-entrypoint), NOT
    over the volume's copy: a file bind whose mountpoint sits inside a
    read-only mount breaks `docker cp` for the WHOLE container (daemon
    `openat …: read-only file system`, running or stopped; reproduced
    slice-4 smoke 2026-07-05) — which broke the OFFICIAL scorer's
    compose-cp read_file/write_file. The entrypoint takes SEON_HOME from
    env with the /opt/seon default, so its own location is free."""
    return (
        f"    volumes:\n"
        f"      - {volume}:/opt/seon:ro\n"
        f"      - {ENTRYPOINT_FILE}:/seon-entrypoint:ro\n"
        f"      - /seon-data\n"
    )


def _external_volume_block(volume: str) -> str:
    return (
        f"volumes:\n"
        f"  {volume}:\n"
        f"    external: true\n"
    )


def _compose_yaml(image: str, *, boot: bool, volume: str,
                  host_port: int | None, open_egress: bool = False,
                  api_ip: str | None = None) -> str:
    """The per-sample compose text (see the module docstring for the shape)."""
    if not boot:
        # Null-run: byte-for-byte the official compose (sleep infinity,
        # network_mode: none, /testbed) + ONLY the two mounts.
        return (textwrap.dedent(f"""\
            services:
              default:
                image: {image}
                command: sleep infinity
                working_dir: /testbed
                network_mode: none
            """)
            + f"    volumes:\n"
              f"      - {volume}:/opt/seon:ro\n"
              f"      - /seon-data\n"
            + _external_volume_block(volume))
    assert host_port is not None
    head = textwrap.dedent(f"""\
        services:
          default:
            image: {image}
            command: ["/seon-entrypoint", "all"]
            working_dir: /testbed
            mem_limit: 6gb
        """)
    if open_egress:
        # The recorded escape hatch: slice-3 shape (unrestricted egress,
        # port published on the task container itself).
        return (head + _BENCH_ENV
                + f"    ports:\n"
                  f"      - \"127.0.0.1:{host_port}:7890\"\n"
                + _bench_volumes(volume)
                + _external_volume_block(volume))
    # Default: model-API-only egress. The task container is internal-only;
    # the relay carries the API alias + forwards :443 by TCP passthrough to
    # the host-resolved real IP, and forwards the published pod port inward
    # (an internal-only service cannot publish ports itself).
    assert api_ip is not None
    return (head + _BENCH_ENV
            + "    depends_on:\n"
              "      - relay\n"
              "    networks:\n"
              "      - internal\n"
            + _bench_volumes(volume)
            + textwrap.dedent(f"""\
                  relay:
                    image: alpine/socat
                    entrypoint: ["/bin/sh", "-c"]
                    command:
                      - "socat TCP-LISTEN:443,fork,reuseaddr TCP:{api_ip}:443 & exec socat TCP-LISTEN:7890,fork,reuseaddr TCP:default:7890"
                    networks:
                      internal:
                        aliases:
                          - {MODEL_API_HOST}
                      egress: {{}}
                    ports:
                      - "127.0.0.1:{host_port}:7890"
                networks:
                  internal:
                    internal: true
                  egress: {{}}
                """)
            + _external_volume_block(volume))


def overlay_sandbox_config(*, boot: bool, volume: str = OVERLAY_VOLUME,
                           open_egress: bool = False):
    """A `sandbox_config` callable for `inspect_evals.swe_bench` (§2b seam).

    `boot=False` → null-run compose (mounts only, no Seon, no network);
    `boot=True` → the agent arm (entrypoint boots the cluster inside, pod
    port published per sample and stamped into
    `sample.metadata["seon_pod_port"]` for the solver; model-API-only
    egress unless `open_egress=True`, which is stamped into metadata so
    every run records its egress stance)."""

    def create_spec(sandbox_type: str, sample) -> SandboxEnvironmentSpec:
        assert sandbox_type == "docker", (
            f"A-overlay compose is docker-only (got {sandbox_type!r})")
        image = sample.metadata["image_name"]
        host_port = sample_port(str(sample.id)) if boot else None
        api_ip = None
        if boot:
            sample.metadata["seon_pod_port"] = host_port
            sample.metadata["seon_open_egress"] = open_egress
            sample.metadata["seon_entrypoint_sha"] = entrypoint_sha256()
            if not open_egress:
                api_ip = resolve_model_api_ip()
                sample.metadata["seon_model_api_ip"] = api_ip
        COMPOSE_DIR.mkdir(parents=True, exist_ok=True)
        flavour = "seon" if boot else "null"
        config_file = COMPOSE_DIR / f"{sample.id}-{flavour}-compose.yaml"
        config_file.write_text(
            _compose_yaml(image, boot=boot, volume=volume,
                          host_port=host_port, open_egress=open_egress,
                          api_ip=api_ip))
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

    States the environment facts (including the fs capability — the
    every-check-stated law: the agent must be TOLD it can edit files
    directly), every scored check's judging rule, and the explicit done
    signal — and nothing else (no hints, no test names; the FAIL_TO_PASS
    names are withheld exactly as the official bench withholds them)."""
    return (
        f"{problem_statement}\n\n"
        "Your workspace is the repository checked out at /testbed inside "
        "this machine — your shell runs here, and your file verbs are "
        "rooted at /testbed with write access, so you can read and edit "
        "the repository files directly. Fix the issue by editing the "
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


# ---------------------------------------------------------------------------
# Interim run bounds — the apply_ai_config precedent, delivered in-container
# ---------------------------------------------------------------------------

# The bundled node speaks the wire-REPL socket protocol from INSIDE the task
# container (no nc in the instance images; the host cannot reach the REPL —
# it is loopback-only behind the internal network). Mirrors
# cluster.wire_repl_json: send one form, half-close, read all, print.
NODE_WIRE_REPL_JS = (
    'const s=require("net").connect(7891,"127.0.0.1");let b="";'
    's.on("data",d=>b+=d);'
    's.on("close",()=>{process.stdout.write(b);process.exit(0);});'
    's.on("connect",()=>{s.end(process.argv[1]+"\\n");});'
    's.on("error",e=>{console.error(String(e));process.exit(1);});'
    'setTimeout(()=>{process.stdout.write(b);process.exit(2);},30000);'
)

NODE_BIN = "/opt/seon/node/bin/node"

# Datahike schema for the two run-bound attrs, derived via the one bridge
# (`seon.db/malli->datahike-schema` on `:int` → :db.type/long, same mapping
# as :seon.ai/timeout-ms in cluster.py's precedent). Needed because a fresh
# cluster installs schema lazily on the pod-side write path and the wire
# REPL bypasses that installer. Install-if-missing only.
_RUN_BOUND_SCHEMA_EDN = (
    '[{:db/ident :seon.agent.run/default-turn-limit'
    ' :db/valueType :db.type/long :db/cardinality :db.cardinality/one}'
    ' {:db/ident :seon.agent.run/default-deadline-ms'
    ' :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]'
)


def run_bounds_form(turn_limit: int, deadline_ms: int,
                    db_name: str = "default") -> str:
    """The wire-REPL form: run-bound attrs onto the ROOT agent + read-back.

    `open-run!` seeds every run's turn-limit/deadline from these agent-entity
    attrs (`src/seon/agent/run.cljs:263-270`) — so transacting them BEFORE
    the task posts bounds the task's run. Prints the WIRE-JSON sentinel with
    the read-back row (fail-loud verification host-side)."""
    return (
        "(do (require (quote [cheshire.core :as json])"
        " (quote [datahike.api :as d]))"
        " (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn"
        f" {{:seon.server.registry/db-name :{db_name}}}))"
        " installed (set (keys (d/schema @conn)))"
        " schema-tx (into [] (remove (fn [s] (installed (:db/ident s)))) "
        + _RUN_BOUND_SCHEMA_EDN + ")"
        " _ (when (seq schema-tx) (d/transact conn {:tx-data schema-tx}))"
        " _ (d/transact conn {:tx-data"
        " [{:db/id [:seon.agent/id \"root\"]"
        f" :seon.agent.run/default-turn-limit {int(turn_limit)}"
        f" :seon.agent.run/default-deadline-ms {int(deadline_ms)}}}]}})"
        " row (d/q (quote [:find (pull ?e [:seon.agent.run/default-turn-limit"
        " :seon.agent.run/default-deadline-ms]) . :where"
        " [?e :seon.agent/id \"root\"]]) @conn)]"
        " (println (str \"WIRE-JSON<\" (json/generate-string"
        " {\"turn_limit\" (:seon.agent.run/default-turn-limit row)"
        "  \"deadline_ms\" (:seon.agent.run/default-deadline-ms row)})"
        " \">WIRE-JSON\"))))"
    )


async def apply_run_bounds(exec_fn, *, turn_limit: int, deadline_ms: int,
                           retries: int = 12, retry_sleep_s: float = 5.0
                           ) -> dict:
    """Transact the run bounds onto the in-container root agent, verified.

    `exec_fn` is an async `argv -> stdout+stderr text` executor inside the
    task container (the solver passes `sandbox().exec`; probes pass `docker
    compose exec`). Retries cover the window where the pod answers HTTP but
    the root agent is not yet minted (the transact's lookup-ref fails → no
    sentinel → retry). RAISES when the read-back doesn't carry what was
    asked — a run whose bounds didn't take must never be scored as bounded."""
    import anyio

    argv = [NODE_BIN, "-e", NODE_WIRE_REPL_JS,
            run_bounds_form(turn_limit, deadline_ms)]
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            out = await exec_fn(argv)
            row = parse_wire_json(out)
            if (row.get("turn_limit") == turn_limit
                    and row.get("deadline_ms") == deadline_ms):
                return {"turn_limit": turn_limit, "deadline_ms": deadline_ms}
            raise RuntimeError(
                f"run-bounds read-back mismatch: asked "
                f"turn_limit={turn_limit} deadline_ms={deadline_ms}, "
                f"row says {row}")
        except Exception as e:  # no sentinel yet / root not minted / exec err
            last_err = e
            if attempt < retries - 1:
                await anyio.sleep(retry_sleep_s)
    raise RuntimeError(
        f"run bounds not applied after {retries} attempts "
        f"(seon_env_fault — wire REPL unreachable or root agent absent): "
        f"{last_err}")


async def _sandbox_exec(argv: list[str]) -> str:
    """Run argv in the sample's task container via inspect's sandbox."""
    from inspect_ai.util import sandbox

    res = await sandbox().exec(argv, timeout=60)
    return (res.stdout or "") + ("\n" + res.stderr if res.stderr else "")


@solver
def seon_swebench_solver(timeout_s: int = SWEBENCH_RUN_TIMEOUT_S,
                         turn_limit: int = DEFAULT_TURN_LIMIT,
                         deadline_ms: int | None = None):
    """Drive the ROOT agent of the in-container cluster on one instance.

    Per sample: wait for the pod on the sample's stamped published port,
    transact the run bounds onto the root agent (interim mechanism — see
    `apply_run_bounds`; `deadline_ms` defaults to the solve timeout), POST
    the §3 contract to `POST /agents/run` with `agent_id="root"` (the bench
    drives the ROOT agent — design §2a swarm clarification), record the
    honest door metadata (bounds included). The OFFICIAL scorer then judges
    /testbed."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        port = (state.metadata or {}).get("seon_pod_port")
        assert port, ("no seon_pod_port in sample metadata — was the task "
                      "built with overlay_sandbox_config(boot=True)?")
        url = f"http://127.0.0.1:{port}/agents/run"
        timeout_ms = seon_solver._resolve_timeout_ms(state, timeout_s)
        dl_ms = deadline_ms or timeout_ms

        waited = await anyio.to_thread.run_sync(wait_pod_ready, int(port))
        bounds = await apply_run_bounds(_sandbox_exec, turn_limit=turn_limit,
                                        deadline_ms=dl_ms)

        def drive() -> dict:
            return seon_solver.pod_run(task_contract(state.input_text),
                                       timeout_ms, url, agent_id="root")

        result = await anyio.to_thread.run_sync(drive)
        state = seon_solver._record_result(state, result)
        state.metadata["pod_boot_wait_s"] = round(waited, 1)
        state.metadata["pod_run_bounds"] = bounds
        return state

    return solve
