"""Offline tests for the SWE-bench A-overlay arm (compose + contract + §7)."""

from types import SimpleNamespace

import pytest

from seon_inspect import scorecard
from seon_inspect.swebench_arm import (
    OVERLAY_VOLUME,
    overlay_sandbox_config,
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


def test_boot_compose_boots_and_stamps_the_sample_port(tmp_path, monkeypatch):
    monkeypatch.setattr("seon_inspect.swebench_arm.COMPOSE_DIR", tmp_path)
    s = _sample()
    spec = overlay_sandbox_config(boot=True)("docker", s)
    text = _spec_text(spec)
    port = sample_port(str(s.id))
    assert s.metadata["seon_pod_port"] == port
    assert f'"127.0.0.1:{port}:7890"' in text
    assert 'command: ["/opt/seon/seon-entrypoint", "all"]' in text
    assert "SEON_BIND=0.0.0.0" in text
    assert "- DEEPSEEK_API_KEY" in text          # passthrough, never a value
    # the agent arm has egress (design §2c deviation) — no network_mode: none
    assert "network_mode" not in text
    assert f"- {OVERLAY_VOLUME}:/opt/seon:ro" in text


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
