"""terminal-bench adapter — the Seon pod as a custom `BaseAgent` (design §4).

Composition, tb flavour (design §2b Option A, §2d): tb owns the task's
docker-compose lifecycle and its oracle (copy `run-tests.sh` + `tests/` in,
run in tmux, parse, all-PASSED resolution — `harness.py:536-611`). We change
ONLY the agent, via tb's first-class custom-agent hook
(`agent_factory.py:64-79`, `tb run --agent-import-path
seon_inspect.tb_agent:SeonAgent`). `perform_task` receives the task container
directly (`base_agent.py:125`; `session.container` is a docker-py `Container`
— `tmux_session.py:29,34`) and:

1. **Injects the Seon runtime** via `container.put_archive` — the /opt/seon
   tree extracted ONCE from the pinned overlay volume (`OVERLAY_VOLUME`, same
   artifact the SWE-bench arm mounts) into a cached host tarball, plus the
   repo's env-overridable `docker/seon-entrypoint` at `/seon-entrypoint`. No
   compose seam exists to MOUNT it — tb tasks own their compose — so we copy;
   the injection time is measured and returned SEPARATELY from agent time so
   tb's timeout accounting is not silently eaten by our boot (design §4).
2. **Boots the cluster** inside the container: `exec_run` the entrypoint
   detached (wire-server → ready-gate → pod), with the bench env (workspace-
   rooted writable `SEON_FS_ROOT`, `SEON_SHELL`, the model key).
3. **Reaches the pod** with the BUNDLED node (guaranteed in the overlay; no
   dependency on curl/nc in the task image): readiness GET + the door POST run
   via `container.exec_run([NODE_BIN, "-e", ...])` from the harness side (an
   operator channel, not an agent surface). tb publishes no ports for us.
4. **Applies run bounds** onto the ROOT agent via the shared
   `bench_common.apply_run_bounds` (same wire-REPL mechanism as the SWE-bench
   arm — ONE implementation, this arm binds the container-exec channel).
5. **Drives `POST /agents/run`** with a goal-stated contract built from tb's
   instruction (the workspace, that the work is judged by the task's own
   tests, the done signal), then returns an `AgentResult` so THEIR harness
   runs THEIR oracle unchanged.

TB PIN (design §4): the vendored submodule pin (0.2.18, commit 1a6ffa96) ships
241 tasks + a registry whose `terminal-bench-core` versions are head / 0.1.0 /
0.1.1 — there is NO Terminal Bench 2.0 entry (the published 59.1 anchor). For
THIS unit the vendored tasks prove the adapter MECHANISM; comparability to 59.1
still needs the 2.0 task set (bump the submodule or pin the tb package version
carrying the 2.0 registry — an owner/orchestrator shared-tree call, NOT done
here). Until then tb rows are internal-delta / mechanism-proof only.
"""

from __future__ import annotations

import io
import json
import subprocess
import tarfile
import time
from pathlib import Path

from seon_inspect.bench_common import (
    NODE_BIN, apply_run_bounds, deadline_below_door)

# terminal-bench is NOT in the pinned src-inspect-ai/.venv (py3.14) — it runs
# from a SIBLING venv (py3.13, tb + this package editable; see README). The
# import is guarded ONLY so the tb-FREE pure helpers below (contract, env,
# door body, tarball) stay importable in the pinned env for the offline
# regression suite; the class + AgentResult mapping require tb and fail loudly
# if used without it. Complexity note: this guard is the one concession to
# the dual-env split — flagged, not silent.
try:
    from terminal_bench.agents.base_agent import AgentResult, BaseAgent
    from terminal_bench.agents.failure_mode import FailureMode
    _HAVE_TB = True
except ModuleNotFoundError:  # pinned env — pure helpers only
    _HAVE_TB = False
    AgentResult = None  # type: ignore
    FailureMode = None  # type: ignore
    BaseAgent = object  # type: ignore

REPO_ROOT = Path(__file__).resolve().parents[3]

# The runtime-overlay volume: /opt/seon extracted from the canonical image at
# a pinned digest (the SAME artifact the SWE-bench arm mounts; slice-3 pin
# recorded in that arm + the run evidence).
OVERLAY_VOLUME = "seon-runtime-slice3"

# The env-overridable entrypoint (the volume's baked copy predates
# SEON_FS_ROOT overridability — same reason the SWE-bench arm bind-mounts it).
ENTRYPOINT_FILE = REPO_ROOT / "docker" / "seon-entrypoint"

# Cached host tarball of the overlay tree (extracted once; gitignored tmp/).
OVERLAY_TAR = REPO_ROOT / "tmp" / f"{OVERLAY_VOLUME}-opt-seon.tar"

POD_PORT = 7890
POD_URL = f"http://127.0.0.1:{POD_PORT}/agents/run"

# Defaults (tb's max_agent_timeout_sec is a harness-global, NOT in the
# perform_task signature — accept it as an --agent-kwarg; the pod's own bound
# cuts first, with margin, and surfaces as behavior_miss honestly attributed).
DEFAULT_TIMEOUT_MS = 600_000
DEFAULT_TURN_LIMIT = 40
POD_READY_TIMEOUT_S = 300
DEFAULT_WORKSPACE = "/app"

# The bundled node reaches the in-container pod (no curl/nc assumed in the
# task image). Readiness = a 200 on `/`; the driver POSTs the door body.
NODE_READY_JS = (
    'require("http").get("http://127.0.0.1:%d/",'
    'r=>{process.exit(r.statusCode===200?0:1);})'
    '.on("error",()=>process.exit(1));' % POD_PORT
)

NODE_POST_JS = (
    'const http=require("http");const u=new URL(process.argv[1]);'
    'const body=process.argv[2];'
    'const req=http.request({hostname:u.hostname,port:u.port,path:u.pathname,'
    'method:"POST",headers:{"Content-Type":"application/json",'
    '"Content-Length":Buffer.byteLength(body)}},'
    'res=>{let b="";res.on("data",d=>b+=d);'
    'res.on("end",()=>{process.stdout.write(b);'
    'process.exit(res.statusCode===200?0:3);});});'
    'req.on("error",e=>{console.error(String(e));process.exit(1);});'
    'req.write(body);req.end();'
)


# ---------------------------------------------------------------------------
# Pure helpers (no docker) — unit-testable offline
# ---------------------------------------------------------------------------

def build_task_contract(instruction: str, workspace: str) -> str:
    """The goal-stated contract around tb's raw instruction (§4, the law).

    States the workspace (native shell + writable file verbs rooted there),
    that the work is judged by THIS task's own tests, and the explicit done
    signal — and nothing else (no hints, no test names; tb tasks carry canary
    lines and withhold their tests exactly as the oracle expects)."""
    return (
        f"{instruction}\n\n"
        f"Your workspace is {workspace} inside this machine — your shell runs "
        f"here, and your file verbs are rooted at {workspace} with write "
        "access, so you can read, create, and edit files directly. Do the "
        f"work by changing the files under {workspace}.\n\n"
        "How your work is judged: after you finish, this task's own automated "
        "test suite is run against the state you leave in this machine. Only "
        "the final state matters.\n\n"
        "When the goal is met, send the user a short summary of what you did "
        "(reply via message/user) — that reply is the done signal."
    )


def boot_env(workspace: str, *, api_key: str | None,
             shell: bool = True) -> dict[str, str]:
    """The entrypoint env for `exec_run` — workspace-rooted writable fs grant.

    `SEON_FS_ROOT=<workspace>` + read-only OFF is the every-check-stated fs
    capability the contract promises; `SEON_BIND` stays loopback-facing (we
    exec-curl from inside, no port is published to us). The model key is
    threaded so the in-container pod reaches the LLM API."""
    env = {
        "SEON_HOME": "/opt/seon",
        "SEON_RUNTIME_ROOT": "/opt/seon",
        "SEON_DATA": "/seon-data",
        "SEON_BIND": "0.0.0.0",
        "SEON_FS_ROOT": workspace,
        "SEON_FS_READ_ONLY": "0",
        "SEON_SHELL": "1" if shell else "0",
        "SEON_PORT": str(POD_PORT),
    }
    if api_key:
        env["DEEPSEEK_API_KEY"] = api_key
    return env


def door_body(contract: str, timeout_ms: int, agent_id: str = "root") -> str:
    """The `POST /agents/run` JSON body (reuse the ROOT agent — design §2a)."""
    return json.dumps({"input": contract, "timeout_ms": timeout_ms,
                       "agent_id": agent_id})


# Door closed_reasons that mean the run ended WITHOUT a terminal reply
# (design §7 behavior_miss: turn/deadline exhaustion). Mapped onto tb's
# closest failure_mode so their results carry the honest signal.
_BEHAVIOR_MISS_REASONS = {"timeout", "deadline-exceeded", "turn-limit"}


def result_from_door(reply: dict, *, injected_s: float,
                     boot_s: float) -> AgentResult:
    """Map the pod door response onto tb's `AgentResult` contract.

    The door gives no token counts (the pod bills its own model calls), so
    tokens stay 0; `failure_mode` reflects the honest close reason. The
    injection + boot times ride as timestamped markers so tb's own log
    separates our setup from agent time (design §4)."""
    reason = str(reply.get("closed_reason") or "")
    timed_out = bool(reply.get("timed_out"))
    if timed_out or reason in _BEHAVIOR_MISS_REASONS:
        failure = FailureMode.AGENT_TIMEOUT
    else:
        failure = FailureMode.NONE
    return AgentResult(
        total_input_tokens=0,
        total_output_tokens=0,
        failure_mode=failure,
        timestamped_markers=[
            (round(injected_s, 2), "seon: runtime injected (put_archive)"),
            (round(injected_s + boot_s, 2), "seon: cluster ready"),
        ],
    )


# ---------------------------------------------------------------------------
# Runtime injection — extract the overlay tree ONCE to a host tarball
# ---------------------------------------------------------------------------

def ensure_overlay_tarball(volume: str = OVERLAY_VOLUME,
                           dest: Path = OVERLAY_TAR,
                           runner=subprocess.run) -> Path:
    """Extract /opt/seon from the overlay volume to a cached host tarball.

    `docker run --rm -v <vol>:/opt/seon:ro alpine tar -C /opt -cf - seon`
    streamed to `dest` (members rooted `seon/…`, so `put_archive("/opt", …)`
    lands them at /opt/seon). Cached: skipped if `dest` already exists."""
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    with open(dest, "wb") as fh:
        proc = runner(
            ["docker", "run", "--rm", "-v", f"{volume}:/opt/seon:ro",
             "alpine", "tar", "-C", "/opt", "-cf", "-", "seon"],
            stdout=fh, stderr=subprocess.PIPE, check=False)
        if getattr(proc, "returncode", 0) != 0:
            err = getattr(proc, "stderr", b"") or b""
            raise RuntimeError(
                f"overlay extract failed for volume {volume!r}: "
                f"{err.decode(errors='replace') if isinstance(err, bytes) else err}")
    return dest


def entrypoint_tar_bytes(entrypoint: Path = ENTRYPOINT_FILE) -> bytes:
    """An in-memory tar carrying the entrypoint as `seon-entrypoint` mode 0755.

    `put_archive("/", …)` lands it at /seon-entrypoint (env-overridable copy,
    independent of the volume's baked one)."""
    data = entrypoint.read_bytes()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        info = tarfile.TarInfo(name="seon-entrypoint")
        info.size = len(data)
        info.mode = 0o755
        tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Container-exec channel (thin docker wrappers)
# ---------------------------------------------------------------------------

def _exec_text(container, argv, *, environment=None, workdir=None,
               detach=False):
    """Run argv in the container; return decoded stdout (or "" when detached)."""
    res = container.exec_run(argv, environment=environment, workdir=workdir,
                             detach=detach)
    if detach:
        return ""
    out = getattr(res, "output", res)
    if isinstance(out, (bytes, bytearray)):
        out = out.decode(errors="replace")
    return out or ""


def _exec_ok(container, argv, *, environment=None) -> bool:
    """True iff argv exits 0 in the container."""
    res = container.exec_run(argv, environment=environment)
    return getattr(res, "exit_code", 1) == 0


def _wait_pod_ready(container, timeout_s: int = POD_READY_TIMEOUT_S) -> float:
    """Block until the in-container pod answers 200 on `/`. Returns seconds."""
    start = time.monotonic()
    while time.monotonic() - start < timeout_s:
        if _exec_ok(container, [NODE_BIN, "-e", NODE_READY_JS]):
            return time.monotonic() - start
        time.sleep(1)
    raise TimeoutError(
        f"in-container pod not ready within {timeout_s}s (seon_env_fault — "
        "check the task container's /seon-data/logs)")


def _detect_workspace(container, fallback: str = DEFAULT_WORKSPACE) -> str:
    """The task container's working dir (where the agent's fs should root)."""
    try:
        container.reload()
        wd = (container.attrs.get("Config", {}) or {}).get("WorkingDir")
        return wd or fallback
    except Exception:
        return fallback


# ---------------------------------------------------------------------------
# The adapter
# ---------------------------------------------------------------------------

class SeonAgent(BaseAgent):
    """The Seon cluster driven inside a terminal-bench task container.

    Constructor kwargs (via `--agent-kwarg`): `model_name` (recorded only —
    the pod's own config picks the provider/model), `timeout_ms`,
    `turn_limit`, `deepseek_api_key` (else read from the harness env)."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._model_name = kwargs.get("model_name")
        self._timeout_ms = int(kwargs.get("timeout_ms", DEFAULT_TIMEOUT_MS))
        self._turn_limit = int(kwargs.get("turn_limit", DEFAULT_TURN_LIMIT))
        import os
        self._api_key = kwargs.get("deepseek_api_key") or os.environ.get(
            "DEEPSEEK_API_KEY")

    @staticmethod
    def name() -> str:
        return "seon"

    def _inject_runtime(self, container) -> float:
        """put_archive the overlay tree + entrypoint. Returns seconds taken."""
        start = time.monotonic()
        tar_path = ensure_overlay_tarball()
        _exec_text(container, ["mkdir", "-p", "/opt", "/seon-data"])
        with open(tar_path, "rb") as fh:
            container.put_archive("/opt", fh.read())
        container.put_archive("/", entrypoint_tar_bytes())
        return time.monotonic() - start

    def _drive_door(self, container, contract: str) -> dict:
        """POST /agents/run via the bundled node; parse the door JSON."""
        body = door_body(contract, self._timeout_ms)
        out = _exec_text(container, [NODE_BIN, "-e", NODE_POST_JS,
                                     POD_URL, body])
        try:
            return json.loads(out)
        except json.JSONDecodeError as e:
            raise RuntimeError(
                f"door reply was not JSON (pod unreachable / crashed?): "
                f"{out!r:.400}") from e

    def perform_task(self, instruction, session, logging_dir=None):
        import asyncio

        container = session.container
        workspace = _detect_workspace(container)

        injected_s = self._inject_runtime(container)

        env = boot_env(workspace, api_key=self._api_key)
        _exec_text(container, ["bash", "/seon-entrypoint", "all"],
                   environment=env, detach=True)

        boot_s = _wait_pod_ready(container)

        async def _aexec(argv):
            import anyio
            return await anyio.to_thread.run_sync(
                lambda: _exec_text(container, argv))

        # The pod deadline MUST cut before the door timeout (the POST read
        # budget) — same coin-flip hazard as the SWE-bench arm, same shared
        # rule (quality-review finding 1). Never equal to self._timeout_ms.
        asyncio.run(apply_run_bounds(
            _aexec, turn_limit=self._turn_limit,
            deadline_ms=deadline_below_door(self._timeout_ms)))

        contract = build_task_contract(instruction, workspace)
        reply = self._drive_door(container, contract)

        if logging_dir is not None:
            try:
                Path(logging_dir).mkdir(parents=True, exist_ok=True)
                (Path(logging_dir) / "seon-door-reply.json").write_text(
                    json.dumps(reply, indent=2))
                (Path(logging_dir) / "seon-timing.json").write_text(
                    json.dumps({"injected_s": injected_s,
                                "boot_s": boot_s}, indent=2))
            except Exception:
                pass

        return result_from_door(reply, injected_s=injected_s, boot_s=boot_s)
