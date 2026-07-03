"""Live run wiring for the bespoke tool rows — one ephemeral cluster per sample.

The per-sample choreography the generators' docstring promises ("the runner
materializes metadata['setup'] … and substitutes via render_input before
POSTing"), made real:

  shell_use / file_edit — materialize the seeded workspace under a per-run
      dir, render the task text with the absolute {workspace} path, drive one
      ephemeral cluster's pod through POST /agents/run, then score by
      RE-READING the workspace (`tool_scorers.check_workspace`).
  web_fetch — materialize the fixture docroot, serve it loopback-only
      (`generators.serve_fixtures`), render {fixture_url}, drive, score the
      reply against the generation-time ground truth
      (`tool_scorers.check_answer`).
  long_term_planning — delegate to `planning.pod_planning_driver` (its own
      cluster + mid-sample pod restart) and score with
      `planning.check_planning`.

Every effect is injectable (cluster factory, pod runner) so the wiring is
unit-tested offline with fakes. Outcome classification follows the scorecard
discipline: a pod-reported timeout / a 422 refusal / a boot failure is a
FLAKE-taxonomy class (excluded from capability means), never a model score;
only a completed run scores pass/fail against the oracle.
"""

from __future__ import annotations

import contextlib
import time
import traceback
from pathlib import Path
from typing import Any, Callable

from seon_inspect import scorecard
from seon_inspect.cluster import ephemeral_cluster
from seon_inspect.generators import materialize_setup, render_input, serve_fixtures
from seon_inspect.solver import AgentRunRefused, pod_run
from seon_inspect.tool_scorers import check_answer, check_workspace

WORKSPACE_ROWS = ("shell_use", "file_edit")


def _pod_summary(pod: dict[str, Any]) -> dict[str, Any]:
    return {k: pod.get(k) for k in
            ("agent_id", "turns", "evals", "closed_reason", "timed_out",
             "elapsed_ms")}


def run_tool_sample(
    sample: dict[str, Any],
    row: str,
    *,
    workspaces_root: Path,
    timeout_ms: int,
    epoch: int = 1,
    cluster_factory: Callable[..., Any] = ephemeral_cluster,
    run: Callable[..., dict[str, Any]] = pod_run,
    fixtures: Callable[[Path], Any] = serve_fixtures,
) -> dict[str, Any]:
    """One tool-row sample: materialize → drive an ephemeral cluster → score.

    Returns an execution record (`scorecard.execution`) carrying the outcome
    plus evidence: the oracle result, the pod's honest metadata, elapsed
    seconds, and (on harness failure) the traceback. Never raises — a
    harness-side exception becomes a flake-class outcome so one broken boot
    can't abort a row. The workspace is keyed on (epoch, sample id) so a
    later epoch NEVER sees an earlier epoch's outputs (a shared workspace
    would score epoch 2 pass off epoch 1's work)."""
    sid = sample["id"]
    meta = sample["metadata"]
    ws = workspaces_root / f"e{epoch}" / sid
    started = time.monotonic()

    def record(outcome: str, **extra: Any) -> dict[str, Any]:
        return scorecard.execution(
            sid, epoch, outcome,
            elapsed_s=round(time.monotonic() - started, 1), **extra)

    try:
        materialize_setup(sample, ws)
        with contextlib.ExitStack() as stack:
            if row in WORKSPACE_ROWS:
                text = render_input(sample, workspace=str(ws))
            else:  # web_fetch — the docroot IS the materialized setup
                base_url = stack.enter_context(fixtures(ws))
                text = render_input(sample, fixture_url=base_url)
            cluster = stack.enter_context(cluster_factory())
            pod = run(text, timeout_ms, cluster.url)
    except AgentRunRefused as e:
        return record("agent_run_refused", error=str(e))
    except TimeoutError as e:
        return record("cluster_boot_timeout", error=str(e))
    except Exception as e:  # bin/seon failures, transport errors, …
        return record("harness_error", error=f"{e}",
                      trace=traceback.format_exc(limit=4))

    if pod.get("timed_out"):
        return record("solve_timeout", pod=_pod_summary(pod))
    if str(pod.get("closed_reason", "")) == ":error":
        # The run CRASHED (turn error / halt :error) — the agent never got to
        # answer; a runtime defect class, not a capability miss. First observed
        # 2026-07-03: cljs-watch hot-reloads landing mid-turn in bench pods.
        return record("run_error", pod=_pod_summary(pod))
    if row in WORKSPACE_ROWS:
        score = check_workspace(ws, meta["oracle"])
    else:
        score = check_answer(pod.get("reply", ""), meta["oracle"])
    return record(scorecard.PASS if score["ok"] else scorecard.FAIL,
                  score=score, pod=_pod_summary(pod))


def run_planning_sample_live(
    sample: dict[str, Any],
    *,
    timeout_ms: int | None = None,
    epoch: int = 1,
    driver: Callable[..., dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """One long_term_planning sample via the two-phase restart driver.

    Wraps `planning.pod_planning_driver` (cluster create → phase 1 → pod
    restart → phase 2, same agent → plan snapshot → destroy) and scores with
    the two-part oracle. Same never-raise outcome discipline as
    `run_tool_sample`; both oracle parts land in the record for attribution."""
    from seon_inspect.planning import check_planning, pod_planning_driver

    sid = sample["id"]
    meta = sample["metadata"]
    started = time.monotonic()
    drive = driver or pod_planning_driver

    def record(outcome: str, **extra: Any) -> dict[str, Any]:
        return scorecard.execution(
            sid, epoch, outcome,
            elapsed_s=round(time.monotonic() - started, 1), **extra)

    try:
        res = drive(sample["input"], meta["phase2_input"],
                    timeout_ms=timeout_ms)
    except AgentRunRefused as e:
        return record("agent_run_refused", error=str(e))
    except TimeoutError as e:
        return record("cluster_boot_timeout", error=str(e))
    except Exception as e:
        return record("harness_error", error=f"{e}",
                      trace=traceback.format_exc(limit=4))

    for phase in ("phase1", "phase2"):
        if res.get(phase, {}).get("timed_out"):
            return record("solve_timeout", phase=phase,
                          pod=_pod_summary(res[phase]))
        if str(res.get(phase, {}).get("closed_reason", "")) == ":error":
            # A phase whose run CRASHED (halt :error) voids the sample — the
            # continuity contract was never given a fair phase to run in.
            return record("run_error", phase=phase,
                          pod=_pod_summary(res[phase]))
    verdict = check_planning(res["reply"], res["plan_snapshot"],
                             res["t_interrupt_ms"], meta["oracle"])
    return record(
        scorecard.PASS if verdict["ok"] else scorecard.FAIL,
        score=verdict,
        pod={"agent_id": res.get("agent_id"), "cluster": res.get("cluster"),
             "phase1": _pod_summary(res.get("phase1", {})),
             "phase2": _pod_summary(res.get("phase2", {}))})
