"""Offline tests for the Terminal-Bench 2.0 (Harbor) adapter.

Two layers, matching the dual-env split (harbor runs from the SIBLING
tmp/tb2-venv; the pinned src-inspect-ai/.venv has no harbor):

- **harbor-FREE pure helpers** — the door-reply → AgentContext.metadata
  classification, and the confirmation that the injection/contract/door helpers
  are REUSED from `tb_agent` (one mechanism), not re-authored. Run everywhere.
- **harbor-COUPLED adapter** — `SeonAgent.setup`/`run` over a MOCKED async
  `BaseEnvironment`. Guarded by `skipif(not _HAVE_HARBOR)` so they run in the
  sibling tb2 venv and skip (not fail) in the pinned env.
"""

from __future__ import annotations

import json
import shlex

import pytest

from seon_inspect import tb2_agent as t2
from seon_inspect import tb_agent as t


# --------------------------------------------------------------------------
# harbor-free pure helpers
# --------------------------------------------------------------------------

def test_reuses_tb_agent_helpers_not_reauthored():
    # The ONE injection/contract/door mechanism is imported from tb_agent —
    # tb2 must reference the SAME function objects, never a copy.
    assert t2.build_task_contract is t.build_task_contract
    assert t2.boot_env is t.boot_env
    assert t2.door_body is t.door_body
    assert t2.ensure_overlay_tarball is t.ensure_overlay_tarball
    assert t2.NODE_POST_JS is t.NODE_POST_JS


def test_context_metadata_clean_reply_is_none_failure():
    md = t2.context_metadata(
        {"reply": "done", "closed_reason": "completed", "timed_out": False},
        injected_s=3.0, boot_s=12.0)
    assert md["seon_failure_mode"] == "none"
    assert md["seon_injected_s"] == 3.0
    assert md["seon_boot_s"] == 12.0
    assert md["seon_door_reply"]["reply"] == "done"


@pytest.mark.parametrize("reply", [
    {"closed_reason": "turn-limit", "timed_out": False},
    {"closed_reason": "deadline-exceeded", "timed_out": False},
    {"closed_reason": "timeout", "timed_out": True},
])
def test_context_metadata_behavior_miss_is_agent_timeout(reply):
    # Reuses tb 0.2.18's SHARED reason set so both harnesses attribute a
    # turn/deadline exhaustion identically.
    md = t2.context_metadata(reply, injected_s=1.0, boot_s=1.0)
    assert md["seon_failure_mode"] == "agent_timeout"


def test_behavior_miss_reason_set_is_the_shared_one():
    assert t2._BEHAVIOR_MISS_REASONS is t._BEHAVIOR_MISS_REASONS


# --------------------------------------------------------------------------
# harbor-coupled adapter (mocked async environment) — skipped in pinned env
# --------------------------------------------------------------------------

_needs_harbor = pytest.mark.skipif(
    not t2._HAVE_HARBOR, reason="harbor only in the sibling tb2 venv")


class _FakeEnv:
    """Async BaseEnvironment stand-in: scripts pwd/readiness/bounds/door."""

    def __init__(self):
        self.uploads = []
        self.execs = []

    async def upload_file(self, source_path, target_path):
        self.uploads.append((str(source_path), target_path))

    async def exec(self, command, cwd=None, env=None, timeout_sec=None,
                   user=None):
        from harbor.environments.base import ExecResult
        self.execs.append({"command": command, "env": env, "user": user})
        c = command
        if "/agents/run" in c:                       # door POST
            return ExecResult(stdout=json.dumps(
                {"reply": "made the file", "closed_reason": "completed",
                 "turns": 3, "timed_out": False}), stderr="", return_code=0)
        if "WIRE-JSON" in c:                          # run-bounds read-back
            import re
            tl = int(re.search(r"default-turn-limit (\d+)", c).group(1))
            dl = int(re.search(r"default-deadline-ms (\d+)", c).group(1))
            return ExecResult(stdout=(
                'WIRE-JSON<{"turn_limit":%d,"deadline_ms":%d}>WIRE-JSON'
                % (tl, dl)), stderr="", return_code=0)
        if "statusCode===200" in c:                   # pod readiness
            return ExecResult(stdout="", stderr="", return_code=0)
        if command.strip() == "pwd":                  # workspace detect
            return ExecResult(stdout="/app\n", stderr="", return_code=0)
        return ExecResult(stdout="", stderr="", return_code=0)  # boot/untar


@_needs_harbor
def test_setup_injects_overlay_and_entrypoint(tmp_path, monkeypatch):
    monkeypatch.setattr(t2, "ensure_overlay_tarball",
                        lambda *a, **k: tmp_path / "overlay.tar")
    (tmp_path / "overlay.tar").write_bytes(b"tar")
    import anyio
    agent = t2.SeonAgent(logs_dir=tmp_path, deepseek_api_key="sk-x")
    env = _FakeEnv()
    anyio.run(agent.setup, env)
    # overlay tarball + the env-overridable entrypoint both uploaded
    targets = {tgt for _, tgt in env.uploads}
    assert t2.OVERLAY_STAGE in targets
    assert "/seon-entrypoint" in targets
    # extraction + chmod ran as root
    untar = [e for e in env.execs if "tar -C /opt" in e["command"]]
    assert untar and untar[0]["user"] == "root"
    assert agent._injected_s >= 0.0


@_needs_harbor
def test_run_boots_bounds_drives_and_fills_context(tmp_path, monkeypatch):
    from harbor.models.agent.context import AgentContext
    import anyio
    agent = t2.SeonAgent(logs_dir=tmp_path, deepseek_api_key="sk-x",
                         turn_limit=40, timeout_ms=600000)
    env = _FakeEnv()
    ctx = AgentContext()

    async def _go():
        await agent.run("Create /app/hello.txt.", env, ctx)

    anyio.run(_go)

    # the cluster was booted detached with the writable-fs boot env
    boot = [e for e in env.execs if "seon-entrypoint all" in e["command"]]
    assert boot and boot[0]["env"]["SEON_FS_ROOT"] == "/app"
    assert boot[0]["env"]["SEON_FS_READ_ONLY"] == "0"
    # the door was driven with the ROOT agent and the goal-stated contract
    door = [e for e in env.execs if "/agents/run" in e["command"]]
    assert len(door) == 1
    # the run-bounds form was transacted (finding-1 deadline strictly below door)
    bounds = [e for e in env.execs if "WIRE-JSON" in e["command"]]
    assert bounds
    assert "default-deadline-ms 540000" in bounds[0]["command"]  # 0.9 * 600000
    # context carries the honest close reason + our setup/boot split
    assert ctx.metadata["seon_failure_mode"] == "none"
    assert ctx.metadata["seon_closed_reason"] == "completed"
    assert "seon_boot_s" in ctx.metadata and "seon_injected_s" in ctx.metadata


@_needs_harbor
def test_exec_text_shell_joins_argv(tmp_path):
    # Harbor's env.exec takes a shell STRING; our argv-built helpers must be
    # shell-joined so the wire-REPL/door machinery runs unchanged.
    argv = [t2.NODE_BIN, "-e", 'a "b" c', "x y"]
    assert shlex.join(argv).startswith(t2.NODE_BIN)
    assert shlex.split(shlex.join(argv)) == argv
