"""Offline proof runner — all bench tasks against the canned worker + REAL oracles.

Runs every task x arm/condition through inspect's Python API (no GPU, no pod)
and prints one line per run with the reduced metrics read from the eval LOG
(never scraped from stdout). This is the reproducible wiring proof:

    .venv/bin/python run_offline_proof.py     # (the spike venv with inspect-ai)

Expected shape (the mock encodes the live 06-29 pools + the audit's fixtures):
E1 arm1 mean 1.0 (refine converges) vs arm2/arm3 well below; skill treatment
1.0 vs control 0.0; ladder ON 1.0 vs OFF below. Real numbers come from the GPU
(-T endpoint=runpod) — this proves the HARNESS, not the model.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from math import isclose
from typing import Callable

from inspect_ai import eval as inspect_eval

from seon_inspect.tasks.e1_spec_fn import e1_spec_fn
from seon_inspect.tasks.ladder_lift import ladder_lift
from seon_inspect.tasks.long_term_planning import long_term_planning
from seon_inspect.tasks.milestone_lift import milestone_lift
from seon_inspect.tasks.product_scenarios import product_scenario
from seon_inspect.tasks.skill_lift import skill_lift

@dataclass(frozen=True)
class ExpectedRun:
    name: str
    task: Callable
    scorer: str
    accuracy: float


RUNS = [
    ExpectedRun("E1 arm1 guided_refine", lambda: e1_spec_fn(
        arm="arm1_guided_refine", endpoint="mock:guided_wins"),
        "ladder_scorer", 1.0),
    ExpectedRun("E1 arm2 naked", lambda: e1_spec_fn(
        arm="arm2_naked", endpoint="mock:guided_wins"),
        "ladder_scorer", 0.25),
    ExpectedRun("E1 arm3 naked+oracle", lambda: e1_spec_fn(
        arm="arm3_naked_oracle", endpoint="mock:guided_wins"),
        "ladder_scorer", 0.25),
    ExpectedRun("skill control", lambda: skill_lift(
        condition="control", endpoint="mock:skill_lift"),
        "ladder_scorer", 0.0),
    ExpectedRun("skill treatment", lambda: skill_lift(
        condition="treatment", endpoint="mock:skill_lift"),
        "ladder_scorer", 1.0),
    ExpectedRun("ladder ON", lambda: ladder_lift(
        ladder=True, endpoint="mock:ladder"), "ladder_scorer", 1.0),
    ExpectedRun("ladder OFF", lambda: ladder_lift(
        ladder=False, endpoint="mock:ladder"), "ladder_scorer", 0.25),
    ExpectedRun("milestone ns good", lambda: milestone_lift(
        milestone="namespaces", endpoint="mock:good"),
        "milestone_scorer", 1.0),
    ExpectedRun("milestone ns bad", lambda: milestone_lift(
        milestone="namespaces", endpoint="mock:bad"),
        "milestone_scorer", 0.0),
    ExpectedRun("milestone db good", lambda: milestone_lift(
        milestone="db", endpoint="mock:good"), "milestone_scorer", 1.0),
    ExpectedRun("milestone db bad", lambda: milestone_lift(
        milestone="db", endpoint="mock:bad"), "milestone_scorer", 0.0),
    ExpectedRun("planning good", lambda: long_term_planning(
        endpoint="mock:good"), "planning_scorer", 1.0),
    ExpectedRun("planning bad", lambda: long_term_planning(
        endpoint="mock:bad"), "planning_scorer", 0.0),
    ExpectedRun("planning pretransacted", lambda: long_term_planning(
        endpoint="mock:experiment:pretransacted"), "planning_scorer", 1.0),
    ExpectedRun("planning model-authored", lambda: long_term_planning(
        endpoint="mock:experiment:model_authored"), "planning_scorer", 1.0),
    ExpectedRun("planning no-plan", lambda: long_term_planning(
        endpoint="mock:experiment:no_plan"), "planning_scorer", 0.0),
    *[
        ExpectedRun(f"product {scenario} {outcome}",
                    lambda scenario=scenario, outcome=outcome: product_scenario(
                        scenario=scenario, outcome=outcome),
                    "product_scenario_scorer", expected)
        for scenario in ("namespace", "reuse_repair", "child_recovery",
                         "pod_restart")
        for outcome, expected in (("good", 1.0), ("bad", 0.0))
    ],
]


def _mean_accuracy(log, scorer: str) -> float | None:
    for score in log.results.scores:
        if score.name == scorer and score.reducer == "mean":
            metric = score.metrics.get("accuracy")
            return None if metric is None else metric.value
    return None


def main() -> int:
    rows = []
    failed = []
    for run in RUNS:
        logs = inspect_eval(run.task(), model="mockllm/model", display="none",
                            log_level="warning")
        log = logs[0]
        if log.status != "success":
            rows.append((run.name, f"STATUS={log.status} {log.error}"))
            failed.append(run.name)
            continue
        cells = []
        for sc in log.results.scores:
            reducer = sc.reducer or "-"
            for mname, m in sc.metrics.items():
                cells.append(f"{sc.name}:{reducer}/{mname}={m.value:.3f}")
        actual = _mean_accuracy(log, run.scorer)
        if actual is None or not isclose(actual, run.accuracy, abs_tol=1e-9):
            cells.append(f"EXPECTATION={run.scorer}:mean/accuracy="
                         f"{run.accuracy:.3f} actual={actual}")
            failed.append(run.name)
        rows.append((run.name, "  ".join(cells)))
    print(f"\n{'RUN':28s} METRICS (from the eval logs)")
    for name, cells in rows:
        print(f"{name:28s} {cells}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
