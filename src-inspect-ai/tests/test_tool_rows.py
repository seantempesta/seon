"""tool_rows.py — the per-sample run wiring, offline with fakes."""

import contextlib
import threading
import time
from dataclasses import dataclass

import pytest

from seon_inspect import cluster as cluster_mod
from seon_inspect.generators import generate_rows
from seon_inspect.solver import AgentRunRefused
from seon_inspect.tool_rows import (run_planning_sample_live, run_tool_row,
                                    run_tool_sample)


@dataclass
class _FakeCluster:
    url: str = "http://127.0.0.1:9/agents/run"


@contextlib.contextmanager
def _fake_cluster_factory(**_kw):
    yield _FakeCluster()


@contextlib.contextmanager
def _fake_fixtures(docroot):
    yield "http://127.0.0.1:9"


def _pod_ok(reply=""):
    return {"agent_id": "a1", "turns": 2, "evals": 1,
            "closed_reason": ":completed", "timed_out": False,
            "elapsed_ms": 1000, "reply": reply}


def test_shell_sample_pass_when_agent_does_the_work(tmp_path):
    # shell_use seed-1 index 1 = _sh_exact_file: pod "does" it via the fake.
    sample = generate_rows("shell_use", 1, 8)[1]
    check = sample["metadata"]["oracle"]["checks"][0]

    def fake_run(text, timeout_ms, url, agent_id=None):
        # the task text must carry the ABSOLUTE workspace path, not a template
        assert str(tmp_path) in text and "{workspace}" not in text
        p = tmp_path / "e1" / sample["id"] / check["path"]
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(check["equals"])
        return _pod_ok()

    rec = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory, run=fake_run)
    assert rec["outcome"] == "pass"
    assert rec["pod"]["turns"] == 2


def test_epochs_get_isolated_workspaces(tmp_path):
    # Epoch 2 must NOT score pass off epoch 1's outputs.
    sample = generate_rows("shell_use", 1, 8)[1]
    check = sample["metadata"]["oracle"]["checks"][0]

    def fake_run_e1(text, timeout_ms, url, agent_id=None):
        p = tmp_path / "e1" / sample["id"] / check["path"]
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(check["equals"])
        return _pod_ok()

    r1 = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                         timeout_ms=1000, epoch=1,
                         cluster_factory=_fake_cluster_factory,
                         run=fake_run_e1)
    r2 = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                         timeout_ms=1000, epoch=2,
                         cluster_factory=_fake_cluster_factory,
                         run=lambda *a, **k: _pod_ok())
    assert r1["outcome"] == "pass" and r1["epoch"] == 1
    assert r2["outcome"] == "fail" and r2["epoch"] == 2


def test_shell_sample_fail_when_workspace_untouched(tmp_path):
    sample = generate_rows("shell_use", 1, 8)[1]
    rec = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory,
                          run=lambda *a, **k: _pod_ok())
    assert rec["outcome"] == "fail"
    assert rec["score"]["failures"]


def test_web_fetch_scored_on_reply(tmp_path):
    sample = generate_rows("web_fetch", 1, 8)[0]
    answer = sample["metadata"]["oracle"]["answer"]

    def fake_run(text, timeout_ms, url, agent_id=None):
        assert "{fixture_url}" not in text
        return _pod_ok(reply=answer)

    rec = run_tool_sample(sample, "web_fetch", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory,
                          run=fake_run, fixtures=_fake_fixtures)
    assert rec["outcome"] == "pass"


def test_pod_timeout_is_a_flake_class_not_a_fail(tmp_path):
    sample = generate_rows("web_fetch", 1, 8)[0]
    timed = dict(_pod_ok(), timed_out=True, closed_reason="timeout")
    rec = run_tool_sample(sample, "web_fetch", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory,
                          run=lambda *a, **k: timed, fixtures=_fake_fixtures)
    assert rec["outcome"] == "solve_timeout"


def test_run_error_close_is_a_defect_class_not_a_fail(tmp_path):
    # A pod :error close (e.g. a hot-reload landing mid-turn) never reaches
    # the answer oracle — the agent never got to act.
    sample = generate_rows("web_fetch", 1, 8)[0]
    errored = dict(_pod_ok(), closed_reason=":error", turns=1, evals=0)
    rec = run_tool_sample(sample, "web_fetch", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory,
                          run=lambda *a, **k: errored, fixtures=_fake_fixtures)
    assert rec["outcome"] == "run_error"


def test_refusal_and_boot_timeout_classified_never_raised(tmp_path):
    sample = generate_rows("shell_use", 1, 8)[0]

    def refused(*a, **k):
        raise AgentRunRefused("unknown agent")

    rec = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory, run=refused)
    assert rec["outcome"] == "agent_run_refused"

    @contextlib.contextmanager
    def boot_fails(**_kw):
        raise TimeoutError("pod not ready within 60s")
        yield

    rec2 = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                           timeout_ms=1000, cluster_factory=boot_fails,
                           run=lambda *a, **k: _pod_ok())
    assert rec2["outcome"] == "cluster_boot_timeout"


def test_harness_error_carries_trace(tmp_path):
    sample = generate_rows("shell_use", 1, 8)[0]

    def boom(*a, **k):
        raise RuntimeError("bin/seon cluster create failed")

    rec = run_tool_sample(sample, "shell_use", workspaces_root=tmp_path,
                          timeout_ms=1000,
                          cluster_factory=_fake_cluster_factory, run=boom)
    assert rec["outcome"] == "harness_error"
    assert "bin/seon" in rec["error"]


# ---------------------------------------------------------------------------
# run_tool_row — bench-cluster-N (bounded concurrent dispatch)
# ---------------------------------------------------------------------------


class _SlotCounter:
    """A cluster factory that counts how many clusters are live at once."""

    def __init__(self, dwell_s=0.05):
        self.lock = threading.Lock()
        self.active = 0
        self.max_active = 0
        self.creations = 0
        self.dwell_s = dwell_s

    @contextlib.contextmanager
    def __call__(self, *a, **k):
        with self.lock:
            self.active += 1
            self.creations += 1
            self.max_active = max(self.max_active, self.active)
        try:
            time.sleep(self.dwell_s)  # hold the slot so overlap is observable
            yield _FakeCluster()
        finally:
            with self.lock:
                self.active -= 1


def test_run_tool_row_dispatch_order_and_epoch_major(tmp_path):
    # records return in dispatch order (epoch-major, then sample order),
    # one cluster creation per (epoch, sample) execution
    samples = generate_rows("web_fetch", 1, 3)
    factory = _SlotCounter(dwell_s=0)
    recs = run_tool_row("web_fetch", samples, workspaces_root=tmp_path,
                        timeout_ms=1000, epochs=2, parallelism=1,
                        cluster_factory=factory,
                        run=lambda *a, **k: _pod_ok(reply="x"),
                        fixtures=_fake_fixtures, prepare=lambda: None)
    assert [(r["epoch"], r["sample_id"]) for r in recs] == \
        [(e, s["id"]) for e in (1, 2) for s in samples]
    assert factory.creations == 6
    assert factory.max_active == 1  # parallelism=1 stays serial


def test_run_tool_row_bounds_live_clusters(tmp_path):
    # parallelism=2 over 6 executions: at LEAST two clusters overlap
    # (the slots are used) and NEVER more than two are live (the bound holds)
    samples = generate_rows("web_fetch", 1, 6)
    factory = _SlotCounter()
    recs = run_tool_row("web_fetch", samples, workspaces_root=tmp_path,
                        timeout_ms=1000, parallelism=2,
                        cluster_factory=factory,
                        run=lambda *a, **k: _pod_ok(reply="x"),
                        fixtures=_fake_fixtures,
                        prepare=lambda: None)
    assert len(recs) == 6
    assert factory.max_active == 2


def test_run_tool_row_one_failure_does_not_kill_siblings(tmp_path):
    # one sample's cluster boot dies -> ITS record is the flake class;
    # every other execution still runs and scores
    samples = generate_rows("web_fetch", 1, 4)
    doomed = samples[1]["id"]
    factory = _SlotCounter(dwell_s=0)
    calls = []

    def run(text, timeout_ms, url, agent_id=None):
        calls.append(text)
        return _pod_ok(reply="x")

    attempts = []

    def flaky_factory(*a, **k):
        # boot fails exactly for the doomed sample's execution (serial
        # dispatch makes attempt #2 deterministic)
        attempts.append(1)
        if len(attempts) == 2:
            @contextlib.contextmanager
            def dead(**_kw):
                raise TimeoutError("pod not ready")
                yield
            return dead()
        return factory(*a, **k)

    recs = run_tool_row("web_fetch", samples, workspaces_root=tmp_path,
                        timeout_ms=1000, parallelism=1,
                        cluster_factory=flaky_factory, run=run,
                        fixtures=_fake_fixtures, prepare=lambda: None)
    outcomes = {r["sample_id"]: r["outcome"] for r in recs}
    assert outcomes[doomed] == "cluster_boot_timeout"
    assert all(o == "fail" or o == "pass"
               for sid, o in outcomes.items() if sid != doomed)
    assert len(recs) == 4


def test_run_tool_row_prepares_bundle_once_at_any_parallelism(tmp_path):
    # freshness is RUN-level: exactly ONE up-front build per row run,
    # never per-create, at serial AND concurrent parallelism (a per-create
    # staleness rebuild would swap code under the run — the 2026-07-03
    # web_fetch void)
    samples = generate_rows("web_fetch", 1, 2)
    for par in (1, 3):
        prepared = []
        run_tool_row("web_fetch", samples, workspaces_root=tmp_path,
                     timeout_ms=1000, parallelism=par,
                     cluster_factory=_SlotCounter(dwell_s=0),
                     run=lambda *a, **k: _pod_ok(reply="x"),
                     fixtures=_fake_fixtures,
                     prepare=lambda: prepared.append(1))
        assert prepared == [1], f"parallelism={par}"


def test_run_tool_row_asserts_frozen_bundle(tmp_path, monkeypatch):
    # a mid-row bundle change raises FrozenBundleChanged with the execution
    # records attached — contamination is detected, never scored through
    monkeypatch.setattr(cluster_mod, "REPO_ROOT", tmp_path)
    b = tmp_path / "out-bench" / "client" / "main.js"
    b.parent.mkdir(parents=True)
    b.write_bytes(b"code")
    (b.parent / "main.js.sha256").write_text("abc\n")
    monkeypatch.setattr(cluster_mod, "BENCH_BUNDLE", b)
    monkeypatch.setattr(cluster_mod, "BENCH_BUNDLE_SHA",
                        b.parent / "main.js.sha256")
    samples = generate_rows("web_fetch", 1, 2)

    def run(text, timeout_ms, url, agent_id=None):
        b.write_bytes(b"rebuilt mid-row")  # a tooling-lane save
        return _pod_ok(reply="x")

    with pytest.raises(cluster_mod.FrozenBundleChanged) as e:
        run_tool_row("web_fetch", samples, workspaces_root=tmp_path,
                     timeout_ms=1000, parallelism=1,
                     cluster_factory=_SlotCounter(dwell_s=0), run=run,
                     fixtures=_fake_fixtures, prepare=lambda: None)
    assert len(e.value.logs) == 2  # the evidence rides the raise


def test_planning_sample_scored_through_two_part_oracle():
    sample = generate_rows("long_term_planning", 1, 3)[0]
    answer = sample["metadata"]["oracle"]["final"]["answer"]
    t = 1_000_000

    def fake_driver(phase1, phase2, timeout_ms=None):
        assert phase1 == sample["input"]
        assert phase2 == sample["metadata"]["phase2_input"]
        return {"reply": answer, "t_interrupt_ms": t,
                "plan_snapshot": [
                    {"id": "s1", "title": "record batch 1", "status": "done",
                     "created_at_ms": t - 100, "completed_at_ms": t - 50,
                     "parent_id": None, "from_message": False},
                    {"id": "s2", "title": "record batch 2", "status": "done",
                     "created_at_ms": t - 100, "completed_at_ms": t + 50,
                     "parent_id": "s1", "from_message": False},
                ],
                "phase1": _pod_ok(), "phase2": _pod_ok(reply=answer),
                "cluster": "plan-x", "agent_id": "a1"}

    rec = run_planning_sample_live(sample, driver=fake_driver)
    assert rec["outcome"] == "pass"
    assert rec["score"]["final"]["ok"] and rec["score"]["trajectory"]["ok"]


def test_planning_phase_error_close_is_a_run_error():
    sample = generate_rows("long_term_planning", 1, 1)[0]

    def fake_driver(phase1, phase2, timeout_ms=None):
        return {"reply": "", "t_interrupt_ms": 0, "plan_snapshot": [],
                "phase1": dict(_pod_ok(), closed_reason=":error"),
                "phase2": _pod_ok(), "cluster": "c", "agent_id": "a"}

    rec = run_planning_sample_live(sample, driver=fake_driver)
    assert rec["outcome"] == "run_error"
    assert rec["phase"] == "phase1"


def test_planning_phase_timeout_is_a_flake():
    sample = generate_rows("long_term_planning", 1, 1)[0]

    def fake_driver(phase1, phase2, timeout_ms=None):
        return {"reply": "", "t_interrupt_ms": 0, "plan_snapshot": [],
                "phase1": dict(_pod_ok(), timed_out=True),
                "phase2": _pod_ok(), "cluster": "c", "agent_id": "a"}

    rec = run_planning_sample_live(sample, driver=fake_driver)
    assert rec["outcome"] == "solve_timeout"
    assert rec["phase"] == "phase1"
