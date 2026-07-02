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

from inspect_ai import eval as inspect_eval

from e1_spec_fn_task import e1_spec_fn
from ladder_lift_task import ladder_lift
from skill_lift_task import skill_lift

RUNS = [
    ("E1 arm1 guided_refine", lambda: e1_spec_fn(arm="arm1_guided_refine",
                                                 endpoint="mock:guided_wins")),
    ("E1 arm2 naked", lambda: e1_spec_fn(arm="arm2_naked",
                                         endpoint="mock:guided_wins")),
    ("E1 arm3 naked+oracle", lambda: e1_spec_fn(arm="arm3_naked_oracle",
                                                endpoint="mock:guided_wins")),
    ("skill control", lambda: skill_lift(condition="control",
                                         endpoint="mock:skill_lift")),
    ("skill treatment", lambda: skill_lift(condition="treatment",
                                           endpoint="mock:skill_lift")),
    ("ladder ON", lambda: ladder_lift(ladder=True, endpoint="mock:ladder")),
    ("ladder OFF", lambda: ladder_lift(ladder=False, endpoint="mock:ladder")),
]


def main() -> int:
    rows = []
    for name, mk in RUNS:
        logs = inspect_eval(mk(), model="mockllm/model", display="none",
                            log_level="warning")
        log = logs[0]
        if log.status != "success":
            rows.append((name, f"STATUS={log.status} {log.error}"))
            continue
        cells = []
        for sc in log.results.scores:
            reducer = sc.reducer or "-"
            for mname, m in sc.metrics.items():
                cells.append(f"{reducer}/{mname}={m.value:.3f}")
        rows.append((name, "  ".join(cells)))
    print(f"\n{'RUN':28s} METRICS (from the eval logs)")
    for name, cells in rows:
        print(f"{name:28s} {cells}")
    bad = [n for n, c in rows if "STATUS=" in c]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
