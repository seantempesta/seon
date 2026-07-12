"""Offline tests for the terminal-bench adapter (Unit D).

Two layers, matching the dual-env split (tb runs from a SIBLING venv; the
pinned src-inspect-ai/.venv has no terminal_bench):

- **tb-FREE pure helpers** — contract construction, boot-env assembly, the
  door body, the entrypoint tar. Run everywhere, including the pinned suite.
- **tb-COUPLED adapter** — the `AgentResult` mapping + `SeonAgent.perform_task`
  with the docker `Container` MOCKED. Guarded by `skipif(not _HAVE_TB)` so
  these run in the sibling tb venv and skip (not fail) in the pinned env,
  while the pure tests above still run in both.
"""

from __future__ import annotations

import io
import json
import tarfile

import pytest

from seon_inspect import tb_agent as t
from seon_inspect.bench_common import (
    DEADLINE_FRACTION, deadline_below_door, run_bounds_argv, run_bounds_form)


# --------------------------------------------------------------------------
# tb-free pure helpers
# --------------------------------------------------------------------------

def test_contract_states_workspace_judging_and_done_signal():
    c = t.build_task_contract("Make /app/hello.txt say hi.", "/app")
    assert "Make /app/hello.txt say hi." in c      # instruction preserved
    assert "/app" in c                              # workspace stated
    assert "write access" in c                      # fs capability stated
    assert "test suite" in c                        # judging rule stated
    assert "done signal" in c                       # explicit done signal
    # No hints / no test names leak in — only the instruction + the frame.
    assert "FAIL_TO_PASS" not in c


def test_contract_threads_a_nondefault_workspace():
    c = t.build_task_contract("do it", "/workdir/repo")
    assert c.count("/workdir/repo") >= 3
    assert "/app" not in c


def test_boot_env_workspace_rooted_writable_fs_grant():
    e = t.boot_env("/testbed", api_key="sk-abc")
    assert e["SEON_FS_ROOT"] == "/testbed"
    assert e["SEON_FS_READ_ONLY"] == "0"      # writable
    assert e["SEON_SHELL"] == "1"
    assert e["SEON_RUNTIME_ROOT"] == "/opt/seon"
    assert e["SEON_PORT"] == str(t.POD_PORT)
    assert e["DEEPSEEK_API_KEY"] == "sk-abc"


def test_boot_env_omits_key_when_absent_and_can_deny_shell():
    e = t.boot_env("/app", api_key=None, shell=False)
    assert "DEEPSEEK_API_KEY" not in e
    assert e["SEON_SHELL"] == "0"


def test_door_body_reuses_root_agent_and_carries_timeout():
    body = json.loads(t.door_body("goal text", 123456))
    assert body == {"input": "goal text", "timeout_ms": 123456,
                    "agent_id": "root"}


def test_entrypoint_tar_is_executable_single_member():
    data = t.entrypoint_tar_bytes()
    tf = tarfile.open(fileobj=io.BytesIO(data))
    names = tf.getnames()
    assert names == ["seon-entrypoint"]
    m = tf.getmember("seon-entrypoint")
    assert m.mode == 0o755
    assert m.size > 0


def test_shared_run_bounds_argv_uses_bundled_node_and_form():
    argv = run_bounds_argv(40, 600000)
    assert argv[0] == t.NODE_BIN            # same bundled-node channel
    assert argv[1] == "-e"
    # The wire-REPL form transacts BOTH bounds onto the root agent + reads back
    form = run_bounds_form(40, 600000)
    assert ":seon.agent.run/default-turn-limit 40" in form
    assert ":seon.agent.run/default-deadline-ms 600000" in form
    assert "[:seon.agent/id \"root\"]" in form
    assert "WIRE-JSON" in form


def test_node_driver_scripts_target_the_pod_port():
    assert str(t.POD_PORT) in t.NODE_READY_JS
    assert "/agents/run" in t.POD_URL


# --- quality-review finding 1: the pod deadline cuts BEFORE the door ---

def test_deadline_is_strictly_below_the_door_timeout():
    # default derivation (no explicit deadline)
    for door in (300_000, 600_000, 900_000, 1_000):
        dl = deadline_below_door(door)
        assert dl < door                      # the finding-1 invariant
        assert dl == int(door * DEADLINE_FRACTION)


def test_deadline_explicit_below_door_is_honored():
    assert deadline_below_door(600_000, 500_000) == 500_000


def test_deadline_equal_or_above_door_fails_loud():
    # equal → coin-flip; the helper must REFUSE it, not silently accept
    with pytest.raises(AssertionError):
        deadline_below_door(600_000, 600_000)
    with pytest.raises(AssertionError):
        deadline_below_door(600_000, 700_000)


# --------------------------------------------------------------------------
# tb-coupled adapter (mocked docker) — skipped in the pinned env
# --------------------------------------------------------------------------

# skipif (not importorskip) so the pure tests above still run when tb is
# absent; these run only in the sibling tb venv.
_needs_tb = pytest.mark.skipif(
    not t._HAVE_TB, reason="terminal-bench only in the sibling tb venv")


@_needs_tb
def test_result_from_door_maps_clean_reply_to_none_failure():
    from terminal_bench.agents.failure_mode import FailureMode
    r = t.result_from_door(
        {"reply": "done", "closed_reason": "completed", "timed_out": False},
        injected_s=3.0, boot_s=12.0)
    assert r.failure_mode == FailureMode.NONE
    assert r.total_input_tokens == 0 and r.total_output_tokens == 0
    # markers separate injection from boot (design §4 timing honesty)
    assert r.timestamped_markers[0][0] == 3.0
    assert r.timestamped_markers[1][0] == 15.0


@_needs_tb
@pytest.mark.parametrize("reply", [
    {"closed_reason": "turn-limit", "timed_out": False},
    {"closed_reason": "deadline-exceeded", "timed_out": False},
    {"closed_reason": "timeout", "timed_out": True},
])
def test_result_from_door_flags_behavior_miss_as_timeout(reply):
    from terminal_bench.agents.failure_mode import FailureMode
    r = t.result_from_door(reply, injected_s=1.0, boot_s=1.0)
    assert r.failure_mode == FailureMode.AGENT_TIMEOUT


class _FakeExec:
    """Records exec_run calls; scripts the door + readiness + bounds replies."""

    def __init__(self):
        self.calls = []
        self.archives = []
        self.attrs = {"Config": {"WorkingDir": "/app"}}

    def reload(self):
        pass

    def put_archive(self, path, data):
        self.archives.append((path, len(data) if data else 0))
        return True

    def exec_run(self, argv, environment=None, workdir=None, detach=False):
        from collections import namedtuple
        R = namedtuple("R", "exit_code output")
        self.calls.append({"argv": argv, "env": environment,
                           "detach": detach})
        joined = " ".join(argv)
        if detach:                                   # entrypoint boot
            return R(0, b"")
        if "NODE_READY_JS" in joined or t.NODE_READY_JS in argv:
            return R(0, b"")                         # pod ready
        if t.NODE_READY_JS in argv:
            return R(0, b"")
        # run-bounds wire-REPL form → echo back the ASKED bounds (a real pod
        # reads them back; hardcoding would desync from the finding-1 deadline)
        if any("WIRE-JSON" in a for a in argv):
            import re
            form = argv[-1]
            tl = int(re.search(r"default-turn-limit (\d+)", form).group(1))
            dl = int(re.search(r"default-deadline-ms (\d+)", form).group(1))
            return R(0, ('WIRE-JSON<{"turn_limit":%d,"deadline_ms":%d}>'
                         'WIRE-JSON' % (tl, dl)).encode())
        if t.NODE_POST_JS in argv:                   # door POST
            return R(0, json.dumps(
                {"reply": "made the file", "closed_reason": "completed",
                 "turns": 3, "timed_out": False}).encode())
        return R(0, b"")                             # mkdir etc.


@_needs_tb
def test_perform_task_injects_boots_bounds_and_drives(monkeypatch):
    """End-to-end adapter flow with docker mocked: put_archive twice, the
    run-bounds form transacted, the door driven, a clean AgentResult back."""
    from terminal_bench.agents.failure_mode import FailureMode

    # Skip the real overlay extraction (no docker in this test).
    monkeypatch.setattr(t, "ensure_overlay_tarball",
                        lambda *a, **k: t.OVERLAY_TAR)
    monkeypatch.setattr(t, "open", lambda *a, **k: io.BytesIO(b"tar-bytes"),
                        raising=False)
    # put_archive of the overlay reads the tar file — feed bytes directly.
    orig_inject = t.SeonAgent._inject_runtime

    def fake_inject(self, container):
        container.put_archive("/opt", b"overlay-tar")
        container.put_archive("/", t.entrypoint_tar_bytes())
        return 0.1
    monkeypatch.setattr(t.SeonAgent, "_inject_runtime", fake_inject)
    # readiness: first probe succeeds
    monkeypatch.setattr(t, "_wait_pod_ready", lambda c, **k: 5.0)

    fake = _FakeExec()

    class _Session:
        container = fake

    agent = t.SeonAgent(deepseek_api_key="sk-test", turn_limit=40,
                        timeout_ms=600000)
    result = agent.perform_task("Create /app/hello.txt.", _Session())

    assert result.failure_mode == FailureMode.NONE
    # runtime injected (two put_archives) + entrypoint booted detached
    assert len(fake.archives) == 2
    assert any(c["detach"] for c in fake.calls)
    # the door was driven with the ROOT agent
    post = [c for c in fake.calls if t.NODE_POST_JS in c["argv"]]
    assert len(post) == 1
    body = json.loads(post[0]["argv"][-1])
    assert body["agent_id"] == "root"
    assert "/app" in body["input"]
    _ = orig_inject  # referenced to keep the symbol honest
