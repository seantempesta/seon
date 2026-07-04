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
import shutil
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable

from seon_inspect import config, scorecard
from seon_inspect import cluster as cluster_mod
from seon_inspect.cluster import ephemeral_cluster
from seon_inspect.generators import materialize_setup, render_input, serve_fixtures
from seon_inspect.solver import AgentRunRefused, pod_run
from seon_inspect.tool_scorers import check_answer, check_workspace

WORKSPACE_ROWS = ("shell_use", "file_edit")

# The web_fetch row's fixture server binds loopback-only, so its cluster is
# created with the host-owned SEON_WEB_ALLOW_PRIVATE grant — the pod's SSRF
# guard otherwise refuses 127.0.0.1 (root-caused 2026-07-04: every
# wrong-value web_fetch reply was fabrication AFTER that refusal; passes
# had routed around via shell curl / js/fetch). Scoped to the per-sample
# ephemeral cluster; never set on durable clusters.
WEB_FIXTURE_ENV = {"SEON_WEB_ALLOW_PRIVATE": "1"}


def _pod_summary(pod: dict[str, Any]) -> dict[str, Any]:
    return {k: pod.get(k) for k in
            ("agent_id", "turns", "evals", "closed_reason", "timed_out",
             "elapsed_ms", "model_config")}


def preserve_cluster_evidence(cluster_name: str, dest: Path) -> str | None:
    """Copy an ephemeral cluster's blob store to `dest` BEFORE it is destroyed.

    Evidence-retention fix (2026-07-04): the concurrent-pass web_fetch fails
    were UNRECOVERABLE because per-sample clusters were destroyed with their
    turn-capture blobs (rendered prompts + verbatim LLM replies) inside.
    Copies `data/clusters/<name>/blobs/` → `dest/blobs/`; returns the copied
    path (str) or None when the cluster has no blob dir. Never raises — a
    failed copy must not turn a scored sample into a harness_error."""
    src = cluster_mod.REPO_ROOT / "data" / "clusters" / cluster_name / "blobs"
    try:
        if not src.is_dir():
            return None
        out = dest / "blobs"
        shutil.copytree(src, out, dirs_exist_ok=True)
        return str(out)
    except Exception:
        return None


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
    evidence_root: Path | None = None,
) -> dict[str, Any]:
    """One tool-row sample: materialize → drive an ephemeral cluster → score.

    Returns an execution record (`scorecard.execution`) carrying the outcome
    plus evidence: the oracle result, the pod's honest metadata, the FULL
    reply text, elapsed seconds, and (on harness failure) the traceback.
    `evidence_root` (evidence-retention fix, 2026-07-04) additionally copies
    the ephemeral cluster's blob store (rendered prompts + verbatim replies)
    to `evidence_root/e<epoch>/<sid>/blobs` BEFORE destroy, so a wrong-value
    reply is never unrecoverable again. Never raises — a harness-side
    exception becomes a flake-class outcome so one broken boot can't abort a
    row. The workspace is keyed on (epoch, sample id) so a later epoch NEVER
    sees an earlier epoch's outputs (a shared workspace would score epoch 2
    pass off epoch 1's work)."""
    sid = sample["id"]
    meta = sample["metadata"]
    ws = workspaces_root / f"e{epoch}" / sid
    started = time.monotonic()
    evidence: str | None = None

    def record(outcome: str, **extra: Any) -> dict[str, Any]:
        if evidence is not None:
            extra.setdefault("evidence_blobs", evidence)
        return scorecard.execution(
            sid, epoch, outcome,
            elapsed_s=round(time.monotonic() - started, 1), **extra)

    try:
        materialize_setup(sample, ws)
        with contextlib.ExitStack() as stack:
            if row in WORKSPACE_ROWS:
                text = render_input(sample, workspace=str(ws))
                cluster = stack.enter_context(cluster_factory())
            else:  # web_fetch — the docroot IS the materialized setup
                base_url = stack.enter_context(fixtures(ws))
                text = render_input(sample, fixture_url=base_url)
                cluster = stack.enter_context(
                    cluster_factory(extra_env=WEB_FIXTURE_ENV))
            pod = run(text, timeout_ms, cluster.url)
            if evidence_root is not None:
                evidence = preserve_cluster_evidence(
                    cluster.name, evidence_root / f"e{epoch}" / sid)
    except AgentRunRefused as e:
        return record("agent_run_refused", error=str(e))
    except TimeoutError as e:
        return record("cluster_boot_timeout", error=str(e))
    except Exception as e:  # bin/seon failures, transport errors, …
        return record("harness_error", error=f"{e}",
                      trace=traceback.format_exc(limit=4))

    if pod.get("timed_out"):
        return record("solve_timeout", pod=_pod_summary(pod),
                      reply=pod.get("reply", ""))
    if str(pod.get("closed_reason", "")) == ":error":
        # The run CRASHED (turn error / halt :error) — the agent never got to
        # answer; a runtime defect class, not a capability miss. First observed
        # 2026-07-03: cljs-watch hot-reloads landing mid-turn in bench pods.
        return record("run_error", pod=_pod_summary(pod),
                      reply=pod.get("reply", ""))
    if row in WORKSPACE_ROWS:
        score = check_workspace(ws, meta["oracle"])
    else:
        score = check_answer(pod.get("reply", ""), meta["oracle"])
    return record(scorecard.PASS if score["ok"] else scorecard.FAIL,
                  score=score, pod=_pod_summary(pod),
                  reply=pod.get("reply", ""))


def run_tool_row(
    row: str,
    samples: list[dict[str, Any]],
    *,
    workspaces_root: Path,
    timeout_ms: int,
    epochs: int = 1,
    parallelism: int | None = None,
    cluster_factory: Callable[..., Any] = ephemeral_cluster,
    run: Callable[..., dict[str, Any]] = pod_run,
    fixtures: Callable[[Path], Any] = serve_fixtures,
    prepare: Callable[..., Any] | None = None,
    evidence_root: Path | None = None,
) -> list[dict[str, Any]]:
    """One whole tool row — bench-cluster-N: N ephemeral clusters at once.

    Dispatches every (epoch, sample) execution through `run_tool_sample`
    over a bounded thread pool (`parallelism`, default
    `config.BENCH_CLUSTER_PARALLELISM`): at most N clusters live at once,
    the next execution starts as a slot frees. Each execution is
    structurally isolated (own cluster) AND fault-isolated (`run_tool_sample`
    never raises — one broken boot becomes that execution's flake-class
    record, siblings keep running). Records return in dispatch order
    (epoch-major, then sample order), independent of completion order.

    Frozen-bundle discipline (same as `catalog.run_bench`): the bench
    bundle is pre-built ONCE up front (`prepare`, default
    `cluster.ensure_bench_bundle`) at EVERY parallelism — freshness is a
    RUN-level concern; creates only build-if-missing (a per-create
    staleness rebuild swaps code under the run whenever src/ is saved
    mid-run — observed voiding a web_fetch row 2026-07-03). The bundle
    identity is pinned at start and asserted unchanged at the end — a
    violation raises `cluster.FrozenBundleChanged` with the execution
    records attached as `logs` (flake class `frozen_bundle_changed`;
    publish no capability number)."""
    n = parallelism or config.BENCH_CLUSTER_PARALLELISM
    if n < 1:
        raise ValueError(f"parallelism must be >= 1, got {n}")
    (prepare or cluster_mod.ensure_bench_bundle)()
    bundle_start = cluster_mod.bundle_identity()
    jobs = [(epoch, sample) for epoch in range(1, epochs + 1)
            for sample in samples]

    def one(job: tuple[int, dict[str, Any]]) -> dict[str, Any]:
        epoch, sample = job
        return run_tool_sample(sample, row, workspaces_root=workspaces_root,
                               timeout_ms=timeout_ms, epoch=epoch,
                               cluster_factory=cluster_factory, run=run,
                               fixtures=fixtures, evidence_root=evidence_root)

    with ThreadPoolExecutor(max_workers=n) as pool:
        executions = list(pool.map(one, jobs))
    violation = cluster_mod.bundle_violation(bundle_start)
    if violation:
        raise cluster_mod.FrozenBundleChanged(
            violation, start=bundle_start, end=cluster_mod.bundle_identity(),
            logs=executions)
    return executions


def run_planning_sample_live(
    sample: dict[str, Any],
    *,
    timeout_ms: int | None = None,
    epoch: int = 1,
    driver: Callable[..., dict[str, Any]] | None = None,
    evidence_root: Path | None = None,
) -> dict[str, Any]:
    """One long_term_planning sample via the two-phase restart driver.

    Wraps `planning.pod_planning_driver` (cluster create → phase 1 → pod
    restart → phase 2, same agent → plan snapshot → destroy) and scores with
    the two-part oracle. `evidence_root` retains the cluster's blob store
    before destroy (the tool-row evidence rule). Same never-raise outcome
    discipline as `run_tool_sample`; both oracle parts land in the record
    for attribution."""
    from seon_inspect.planning import check_planning, pod_planning_driver

    sid = sample["id"]
    meta = sample["metadata"]
    started = time.monotonic()
    drive = driver or pod_planning_driver
    if evidence_root is not None and driver is None:
        # Bind the retention path onto the REAL driver only — injected test
        # fakes keep their (phase1, phase2, timeout_ms) signature.
        def drive(p1, p2, timeout_ms=None):  # noqa: F811
            return pod_planning_driver(
                p1, p2, timeout_ms=timeout_ms,
                evidence_root=evidence_root / f"e{epoch}" / sid)

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
