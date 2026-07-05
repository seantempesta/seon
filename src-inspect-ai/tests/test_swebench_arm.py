"""Offline tests for the SWE-bench A-overlay arm (compose + contract + §7)."""

import asyncio
from types import SimpleNamespace

import pytest

from seon_inspect import scorecard
from seon_inspect.cluster import parse_wire_json
from seon_inspect.swebench_arm import (
    DEFAULT_TURN_LIMIT,
    MODEL_API_HOST,
    OVERLAY_VOLUME,
    apply_run_bounds,
    overlay_sandbox_config,
    run_bounds_form,
    sample_port,
    task_contract,
)


def _sample(sample_id="sympy__sympy-22914"):
    return SimpleNamespace(
        id=sample_id,
        metadata={"image_name":
                  f"ghcr.io/epoch-research/swe-bench.eval.arm64.{sample_id}:latest"})


def _spec_text(spec) -> str:
    from pathlib import Path
    return Path(spec.config).read_text()


def test_null_compose_is_official_plus_mounts_only(tmp_path, monkeypatch):
    monkeypatch.setattr("seon_inspect.swebench_arm.COMPOSE_DIR", tmp_path)
    s = _sample()
    spec = overlay_sandbox_config(boot=False)("docker", s)
    text = _spec_text(spec)
    # the official shape survives untouched…
    assert "command: sleep infinity" in text
    assert "network_mode: none" in text
    assert "working_dir: /testbed" in text
    # …plus ONLY the two mounts (read-only overlay + writable /seon-data)
    assert f"- {OVERLAY_VOLUME}:/opt/seon:ro" in text
    assert "- /seon-data" in text
    assert "external: true" in text
    # a null-run must NOT boot Seon, publish ports, or grant egress/env
    assert "seon-entrypoint" not in text
    assert "ports:" not in text
    assert "DEEPSEEK" not in text
    assert "seon_pod_port" not in s.metadata


def _boot_spec(tmp_path, monkeypatch, s, **kwargs):
    monkeypatch.setattr("seon_inspect.swebench_arm.COMPOSE_DIR", tmp_path)
    monkeypatch.setattr("seon_inspect.swebench_arm.resolve_model_api_ip",
                        lambda host=MODEL_API_HOST: "203.0.113.7")
    return overlay_sandbox_config(boot=True, **kwargs)("docker", s)


def test_boot_compose_boots_and_stamps_the_sample_port(tmp_path, monkeypatch):
    s = _sample()
    spec = _boot_spec(tmp_path, monkeypatch, s)
    text = _spec_text(spec)
    port = sample_port(str(s.id))
    assert s.metadata["seon_pod_port"] == port
    assert f'"127.0.0.1:{port}:7890"' in text
    assert 'command: ["/seon-entrypoint", "all"]' in text
    assert "SEON_BIND=0.0.0.0" in text
    assert "- DEEPSEEK_API_KEY" in text          # passthrough, never a value
    assert "network_mode" not in text
    assert f"- {OVERLAY_VOLUME}:/opt/seon:ro" in text


def test_boot_compose_grants_workspace_rooted_writable_fs(tmp_path, monkeypatch):
    # the P0 debt item: the bench pod's fs verbs are rooted at /testbed with
    # write access, delivered by the env-overridable repo entrypoint mounted
    # over the pinned volume's copy (no image rebuild) — sha recorded
    s = _sample()
    text = _spec_text(_boot_spec(tmp_path, monkeypatch, s))
    assert "- SEON_FS_ROOT=/testbed" in text
    assert "- SEON_FS_READ_ONLY=0" in text
    # root-level mountpoint: a file bind inside the RO volume breaks
    # docker cp for the whole container (slice-4 smoke, 2026-07-05)
    assert "docker/seon-entrypoint:/seon-entrypoint:ro" in text
    assert ":/opt/seon/seon-entrypoint" not in text
    assert len(s.metadata["seon_entrypoint_sha"]) == 64


def test_boot_compose_default_is_model_api_only_egress(tmp_path, monkeypatch):
    # default-deny egress: the task container is internal-only; the relay
    # carries the model-API alias + forwards :443 to the host-resolved IP and
    # publishes the pod port (an internal-only service cannot publish)
    s = _sample()
    text = _spec_text(_boot_spec(tmp_path, monkeypatch, s))
    assert "internal: true" in text
    assert f"- {MODEL_API_HOST}" in text                     # the alias
    assert "TCP:203.0.113.7:443" in text                     # host-resolved IP
    assert "TCP:default:7890" in text                        # pod-port forward
    assert s.metadata["seon_open_egress"] is False
    assert s.metadata["seon_model_api_ip"] == "203.0.113.7"
    # the ports block publishes via the relay, not the task container
    default_body = text.split("relay:")[0]
    assert "ports:" not in default_body


def test_boot_compose_open_egress_escape_hatch(tmp_path, monkeypatch):
    # --open-egress: the recorded slice-3 shape (unrestricted egress, port on
    # the task container itself); the stance is stamped into metadata
    s = _sample()
    text = _spec_text(_boot_spec(tmp_path, monkeypatch, s, open_egress=True))
    assert "internal: true" not in text and "relay" not in text
    assert f'"127.0.0.1:{sample_port(str(s.id))}:7890"' in text
    assert s.metadata["seon_open_egress"] is True
    assert "seon_model_api_ip" not in s.metadata
    # the fs grant + entrypoint overlay apply in BOTH egress stances
    assert "- SEON_FS_ROOT=/testbed" in text
    assert "seon-entrypoint:/seon-entrypoint:ro" in text


def test_sample_port_is_deterministic_and_in_range():
    p1 = sample_port("astropy__astropy-12907")
    assert p1 == sample_port("astropy__astropy-12907")
    assert 17900 <= p1 < 18000
    assert sample_port("a") != sample_port("b") or True  # range, not unique


def test_non_docker_sandbox_refused():
    with pytest.raises(AssertionError):
        overlay_sandbox_config(boot=True)("k8s", _sample())


def test_task_contract_states_checks_without_leaking_the_oracle():
    stmt = "Min(1, 2) prints wrong python code"
    text = task_contract(stmt)
    assert stmt in text
    assert "/testbed" in text                    # workspace stated
    assert "test suite" in text                  # judging rule stated
    assert "done signal" in text                 # explicit done signal
    assert "FAIL_TO_PASS" not in text            # oracle names withheld
    # the every-check-stated law: the fs capability is TOLD, truthfully
    assert "write access" in text
    assert "edit" in text and "file verbs" in text


# --- interim run bounds (the apply_ai_config precedent, in-container) -------

def test_run_bounds_form_transacts_and_reads_back_the_agent_attrs():
    form = run_bounds_form(2, 900000)
    # the EXACT attrs open-run! seeds from (src/seon/agent/run.cljs:263-270)
    assert ":seon.agent.run/default-turn-limit 2" in form
    assert ":seon.agent.run/default-deadline-ms 900000" in form
    assert '[:seon.agent/id "root"]' in form     # onto the ROOT agent
    assert "WIRE-JSON<" in form                  # sentinel'd read-back
    assert ":db.type/long" in form               # install-if-missing schema


def test_apply_run_bounds_verifies_readback_and_retries():
    calls = []

    async def exec_fn(argv):
        calls.append(argv)
        if len(calls) == 1:
            return "Cannot resolve lookup ref"   # root agent not minted yet
        return 'WIRE-JSON<{"turn_limit": 2, "deadline_ms": 900000}>WIRE-JSON'

    out = asyncio.run(apply_run_bounds(
        exec_fn, turn_limit=2, deadline_ms=900000, retry_sleep_s=0))
    assert out == {"turn_limit": 2, "deadline_ms": 900000}
    assert len(calls) == 2                       # retried past the miss


def test_apply_run_bounds_raises_on_mismatch():
    async def exec_fn(argv):
        return 'WIRE-JSON<{"turn_limit": 20, "deadline_ms": 1}>WIRE-JSON'

    with pytest.raises(RuntimeError, match="mismatch|not applied"):
        asyncio.run(apply_run_bounds(
            exec_fn, turn_limit=2, deadline_ms=900000,
            retries=2, retry_sleep_s=0))


def test_parse_wire_json_shared_helper():
    assert parse_wire_json('noise WIRE-JSON<{"a": 1}>WIRE-JSON echo') == {"a": 1}
    with pytest.raises(RuntimeError, match="sentinel"):
        parse_wire_json("no sentinel here")


def test_default_turn_limit_is_forty():
    assert DEFAULT_TURN_LIMIT == 40


# --- §7 behavior_miss (scored FAIL, distinctly attributed, never a flake) ---

def test_behavior_miss_predicate():
    assert scorecard.behavior_miss(":turn-limit", "some reply")
    assert scorecard.behavior_miss(":deadline-exceeded", "reply")
    assert scorecard.behavior_miss(":completed", "")     # empty terminal reply
    assert not scorecard.behavior_miss(":completed", "done: fixed the bug")


def _fake_log(closed_reason, reply, score_value="C"):
    score = SimpleNamespace(value=score_value)
    s = SimpleNamespace(
        id="sympy__sympy-22914", epoch=1, error=None,
        metadata={"pod_closed_reason": closed_reason, "pod_timed_out": False},
        output=SimpleNamespace(completion=reply),
        scores={"swe_bench_scorer": score})
    return SimpleNamespace(samples=[s])


def test_turn_limit_close_is_fail_attributed_behavior_miss():
    execs = scorecard.executions_from_eval_log(_fake_log(":turn-limit", ""))
    assert execs[0]["outcome"] == scorecard.FAIL
    assert execs[0]["attribution"] == scorecard.BEHAVIOR_MISS
    # it is SCORED (counts in the mean), never excluded as a flake
    m = scorecard.compute_metrics(execs)
    assert m["flake_rate"] == 0.0
    assert m["mean"] == 0.0


def test_solve_timeout_stays_an_excluded_flake_not_behavior_miss():
    log = _fake_log(":turn-limit", "")
    log.samples[0].metadata["pod_timed_out"] = True
    execs = scorecard.executions_from_eval_log(log)
    assert execs[0]["outcome"] == "solve_timeout"


def test_concluded_run_scores_through_normally():
    execs = scorecard.executions_from_eval_log(
        _fake_log(":completed", "fixed the printer", "C"))
    assert execs[0]["outcome"] == scorecard.PASS
    assert "attribution" not in execs[0]
