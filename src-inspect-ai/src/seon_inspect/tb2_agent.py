"""Terminal-Bench 2.0 (Harbor) adapter — the Seon pod as a Harbor `BaseAgent`.

WHY A SECOND ADAPTER (one-mechanism note): Terminal-Bench 2.0 did NOT ship as
a newer `terminal-bench` release. It shipped as a WHOLE NEW HARNESS — **Harbor**
(`pip install harbor`, package `harbor`, dataset `terminal-bench/terminal-bench-2`,
89 tasks). Harbor's agent contract is a different program from tb 0.2.18's:

  tb 0.2.18 (`tb_agent.py`)          Harbor 0.17.1 (this file)
  --------------------------------   ----------------------------------------
  `terminal_bench.agents.base_agent  `harbor.agents.base:BaseAgent` (ABC)
   :BaseAgent`
  sync `perform_task(instruction,    async `setup(env)` + async `run(
   session, logging_dir)`             instruction, env, context)`
  `session.container` (docker-py     `BaseEnvironment` abstraction:
   Container, `put_archive`/          `env.exec(command:str)`,
   `exec_run(argv)`)                  `env.upload_file/upload_dir`
  returns `AgentResult`              populate the passed `AgentContext`
  `tb run --agent-import-path …`     `harbor run -d … --agent-import-path …`

Because the base class, the method surface, the transport abstraction, AND the
result mechanism ALL differ (and the two harnesses are separate optional
packages), a single class cannot cleanly subclass both — so this is a focused
second binding, NOT a parallel implementation. The ONE mechanism that matters —
runtime injection, the goal-stated contract, the door body, the run-bounds
wire-REPL — is NOT duplicated: it is imported verbatim from `tb_agent` (the pure
helpers) and `bench_common` (the shared run-bounds machinery). Only the thin
harness-binding shell (setup/run over `BaseEnvironment`) lives here, because it
genuinely must.

ARCH NOTE (honest, load-bearing): every terminal-bench-2 task pins a prebuilt
`alexgshaw/<task>:20251031` image whose manifest is **amd64-only** (all 89 —
verified via `docker manifest inspect --verbose`). The Seon overlay volume
`seon-runtime-slice3` bundles an **arm64** node (`node/bin/node` e_machine
`0xB7`). So on an arm64 host the TB-2 container runs amd64-emulated and this
arm64 runtime CANNOT boot inside it — the same arch-matching constraint the
SWE-bench arm hit (it needs arm64 instance images). A live Seon-in-container
TB-2 drive on arm64 therefore needs an amd64 Seon overlay (an infra/owner
build), NOT a change to this adapter. This file is the correct, verified
BINDING; the arch pairing is the remaining gate.
"""

from __future__ import annotations

import os
import shlex
import time
from pathlib import Path

from seon_inspect.bench_common import (
    NODE_BIN, apply_run_bounds, deadline_below_door)
# The ONE copy of the injection/contract/door helpers — reused, never re-authored.
from seon_inspect.tb_agent import (
    _BEHAVIOR_MISS_REASONS, DEFAULT_TIMEOUT_MS, DEFAULT_TURN_LIMIT,
    DEFAULT_WORKSPACE, ENTRYPOINT_FILE, NODE_POST_JS, NODE_READY_JS,
    POD_READY_TIMEOUT_S, POD_URL, build_task_contract, boot_env, door_body,
    ensure_overlay_tarball)

# Harbor is NOT in the pinned src-inspect-ai/.venv — it runs from the SIBLING
# tmp/tb2-venv (py3.13). Guard the import ONLY so the pure-helper reuse above
# stays testable in the pinned env; the class needs harbor and fails loudly if
# used without it. Same dual-env concession the tb 0.2.18 arm flags.
try:
    from harbor.agents.base import BaseAgent
    from harbor.environments.base import BaseEnvironment
    from harbor.models.agent.context import AgentContext
    _HAVE_HARBOR = True
except ModuleNotFoundError:  # pinned env — pure helpers only
    _HAVE_HARBOR = False
    BaseAgent = object  # type: ignore
    BaseEnvironment = object  # type: ignore
    AgentContext = object  # type: ignore

# The container path the overlay tarball is staged at before extraction.
OVERLAY_STAGE = "/tmp/seon-overlay.tar"


# ---------------------------------------------------------------------------
# Pure helper (no harbor) — the door timings folded into AgentContext metadata
# ---------------------------------------------------------------------------

def context_metadata(reply: dict, *, injected_s: float, boot_s: float) -> dict:
    """The AgentContext.metadata for a Harbor trial from the pod door reply.

    Harbor reads token/cost off the context, not a return value; the door gives
    no token counts (the pod bills its own model calls), so those stay unset and
    only the honest close reason + our setup timings ride as metadata. The
    behavior-miss classification reuses tb 0.2.18's SHARED reason set
    (`_BEHAVIOR_MISS_REASONS`) so both harnesses attribute a turn/deadline
    exhaustion identically — but emits a harness-neutral string (Harbor has no
    tb `FailureMode` enum)."""
    closed_reason = str(reply.get("closed_reason") or "")
    timed_out = bool(reply.get("timed_out"))
    behavior_miss = timed_out or closed_reason in _BEHAVIOR_MISS_REASONS
    return {
        "seon_closed_reason": closed_reason,
        "seon_timed_out": timed_out,
        "seon_failure_mode": "agent_timeout" if behavior_miss else "none",
        "seon_injected_s": round(injected_s, 2),
        "seon_boot_s": round(boot_s, 2),
        "seon_door_reply": reply,
    }


class SeonAgent(BaseAgent):
    """The Seon cluster driven inside a Terminal-Bench 2.0 (Harbor) task env.

    Kwargs (via `--agent-kwarg`): `timeout_ms`, `turn_limit`,
    `deepseek_api_key` (else read from the agent env / process env). The pod's
    own config picks the provider/model; `model_name` is recorded only."""

    SUPPORTS_ATIF: bool = False
    SUPPORTS_WINDOWS: bool = False

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._timeout_ms = int(kwargs.get("timeout_ms", DEFAULT_TIMEOUT_MS))
        self._turn_limit = int(kwargs.get("turn_limit", DEFAULT_TURN_LIMIT))
        self._api_key = (
            kwargs.get("deepseek_api_key")
            or self.extra_env.get("DEEPSEEK_API_KEY")
            or os.environ.get("DEEPSEEK_API_KEY"))
        self._injected_s = 0.0

    @staticmethod
    def name() -> str:
        return "seon"

    def version(self) -> str:
        return "1.0.0"

    # -- exec channel: Harbor's env.exec takes a shell STRING, our helpers build
    #    argv — join with shell quoting so the ONE wire-REPL/door machinery runs
    #    unchanged over Harbor's transport.

    async def _exec_text(self, environment, argv: list[str], *,
                         env: dict | None = None) -> str:
        res = await environment.exec(command=shlex.join(argv), env=env)
        out = (res.stdout or "") + (res.stderr or "")
        return out

    async def _exec_ok(self, environment, argv: list[str]) -> bool:
        res = await environment.exec(command=shlex.join(argv))
        return res.return_code == 0

    async def _wait_pod_ready(self, environment,
                              timeout_s: int = POD_READY_TIMEOUT_S) -> float:
        import anyio
        start = time.monotonic()
        while time.monotonic() - start < timeout_s:
            if await self._exec_ok(environment, [NODE_BIN, "-e", NODE_READY_JS]):
                return time.monotonic() - start
            await anyio.sleep(1)
        raise TimeoutError(
            f"in-container pod not ready within {timeout_s}s (seon_env_fault — "
            "check /seon-data/logs and /seon-data/boot.log in the container)")

    async def setup(self, environment) -> None:
        """Inject the Seon runtime: stage the overlay tarball + entrypoint.

        The `/opt/seon` tree extracted ONCE from the pinned overlay volume
        (cached host tarball) is uploaded and untarred at /opt; the
        env-overridable `docker/seon-entrypoint` lands at /seon-entrypoint 0755.
        Harbor has no compose seam to MOUNT it — TB-2 tasks own their env — so
        we copy, exactly as the tb 0.2.18 arm does via put_archive."""
        start = time.monotonic()
        tar_path = ensure_overlay_tarball()
        await environment.upload_file(source_path=str(tar_path),
                                      target_path=OVERLAY_STAGE)
        await environment.upload_file(source_path=str(ENTRYPOINT_FILE),
                                      target_path="/seon-entrypoint")
        # Untar the overlay tree at /opt (members rooted `seon/…`) and make the
        # entrypoint executable. root: the injection is an operator channel.
        await environment.exec(
            command="mkdir -p /opt /seon-data && "
                    f"tar -C /opt -xf {shlex.quote(OVERLAY_STAGE)} && "
                    "chmod 0755 /seon-entrypoint",
            user="root")
        self._injected_s = time.monotonic() - start

    async def run(self, instruction, environment, context) -> None:
        """Boot the cluster, apply run bounds, drive the door, fill context.

        Populates `context.metadata` with the door reply, the honest close
        reason, and the injection/boot timings so Harbor's trial record carries
        our setup/agent split (Harbor then runs THE TASK's own verifier
        unchanged)."""
        workspace = await self._detect_workspace(environment)

        # Boot detached: nohup + `&` returns immediately; the pod comes up async.
        env = boot_env(workspace, api_key=self._api_key)
        await environment.exec(
            command="nohup bash /seon-entrypoint all "
                    ">/seon-data/boot.log 2>&1 &",
            env=env, user="root")

        boot_s = await self._wait_pod_ready(environment)

        # The pod deadline MUST cut before the door read budget (shared rule) —
        # never equal to self._timeout_ms.
        async def _aexec(argv):
            return await self._exec_text(environment, argv)

        await apply_run_bounds(
            _aexec, turn_limit=self._turn_limit,
            deadline_ms=deadline_below_door(self._timeout_ms))

        contract = build_task_contract(instruction, workspace)
        reply = await self._drive_door(environment, contract)

        meta = context_metadata(reply, injected_s=self._injected_s,
                                boot_s=boot_s)
        if context is not None and hasattr(context, "metadata"):
            context.metadata = {**(context.metadata or {}), **meta}

        # Best-effort: also drop the door reply + timing into the agent logs dir.
        try:
            logs = Path(self.logs_dir)
            logs.mkdir(parents=True, exist_ok=True)
            import json
            (logs / "seon-door-reply.json").write_text(json.dumps(reply, indent=2))
            (logs / "seon-timing.json").write_text(json.dumps(
                {"injected_s": self._injected_s, "boot_s": boot_s}, indent=2))
        except Exception:
            pass

    async def _detect_workspace(self, environment) -> str:
        """The task env's working dir (where the agent's fs verbs should root)."""
        try:
            res = await environment.exec(command="pwd")
            wd = (res.stdout or "").strip()
            return wd or DEFAULT_WORKSPACE
        except Exception:
            return DEFAULT_WORKSPACE

    async def _drive_door(self, environment, contract: str) -> dict:
        """POST /agents/run via the bundled node; parse the door JSON."""
        import json
        body = door_body(contract, self._timeout_ms)
        out = await self._exec_text(
            environment, [NODE_BIN, "-e", NODE_POST_JS, POD_URL, body])
        try:
            return json.loads(out)
        except json.JSONDecodeError as e:
            raise RuntimeError(
                f"door reply was not JSON (pod unreachable / crashed?): "
                f"{out!r:.400}") from e
