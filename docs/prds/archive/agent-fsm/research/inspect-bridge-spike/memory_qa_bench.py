"""Memory-QA benchmark (case-1) on the proven inspect-ai <-> Seon pod bridge.

This is Track B1: the first REAL benchmark on the bridge productionized in
`seon.web.serve` (`POST /solve`, commit bfac6f50). It measures the harness's
store->retrieve competency and aligns with the #85 memory-evolution baseline.

WHAT THIS IS
------------
- Dataset: `memory_qa_dataset.jsonl` (16 samples). Each sample gives the agent
  2 real facts + 3 DISTRACTORS to store, then asks a question answerable only by
  retrieving the two real facts across a LATER turn (so it exercises store AND
  discriminating retrieval, not trivial echo). The answer key (`target`) lives
  HOST-SIDE in inspect's process and NEVER enters the pod.
- Solver: `seon_pod_solver` (imported from `seon_solver.py`) — a custom @solver
  that POSTs the input to the pod's /solve door and returns the agent's final
  reply. The pod's OWN multi-turn FSM runs every turn; inspect never manages a
  turn. (Bridge shape: Option B, "Seon owns the loop" — see the spike doc.)
- Scorer: host-side `includes()` (exact-substring against the held-out target).
  The surnames are unique per sample so substring match is unambiguous.
- pass^k: `Epochs(K, pass_at(K))` repeats each sample K times and reduces with
  the Chen-2021 pass^k estimator, so single-run weak-model noise is averaged out
  (a single run is noisy; pass^k is the honest metric — MEMORY: pass^k survey).

ISOLATION (per the spike's model, §5c/§5e)
-------------------------------------------
Per-sample DB isolation is a PROPERTY OF /solve, not of this file: the
productionized handler mints a fresh scratch child per request; DB reads for the
reply are scoped to that child. The parallelism lever (fiber-local *conn* vs
`--max-samples 1`) is a pod-side concern — for a clean serial baseline run with
`--max-samples 1`. This module does not block on it.

RUN
---
    # smoke (2-3 samples, 1 epoch) — prove the harness runs end-to-end:
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve \
      inspect eval memory_qa_bench.py@memory_qa_smoke \
        --model mockllm/model --display plain

    # FULL pass^k baseline (B2 — OWNER-GATED, do NOT run unprompted):
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve SEON_SOLVE_TIMEOUT_S=300 \
      inspect eval memory_qa_bench.py@memory_qa_bench \
        --model mockllm/model --display plain --max-samples 1

`--model mockllm/model` satisfies eval()'s model requirement but is NEVER called
(the solver bypasses generate() and sets the completion directly from the pod).
"""

from __future__ import annotations

import os

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import json_dataset
from inspect_ai.scorer import includes, pass_at

# Reuse the exact solver proven in the Phase-0 smoke (records pod turns / evals /
# closed_reason / timed_out into metadata so the log proves the loop ran honestly).
from seon_solver import seon_pod_solver

DATASET_PATH = os.path.join(os.path.dirname(__file__), "memory_qa_dataset.jsonl")

# pass^k epochs. k=4 by default (survey rec 3-5); overridable for a cheaper/paid run.
PASS_K = int(os.environ.get("SEON_PASS_K", "4"))


def _dataset(limit: int | None = None):
    """Load the memory-QA samples (input + host-side target) from JSONL.

    The JSONL fields are already named `input` / `target` / `id`, so the default
    FieldSpec applies with no mapping. `limit` truncates for the smoke.
    """
    ds = json_dataset(DATASET_PATH)
    if limit is not None:
        ds = ds[:limit]
    return ds


@task
def memory_qa_smoke():
    """SMOKE: 3 samples, 1 epoch — prove the harness runs end-to-end vs /solve.

    Not a measurement — just the wiring proof (dataset loads, solver hits the pod,
    reply scores against the host-side key). Use before the full pass^k baseline.
    """
    return Task(
        dataset=_dataset(limit=3),
        solver=seon_pod_solver(),
        scorer=includes(),
    )


@task
def memory_qa_bench():
    """FULL memory-QA baseline: all 16 samples x pass^k epochs, host-side scoring.

    OWNER-GATED (Track B2). Measures store->retrieve competency of the agent's own
    KB functions under distractors, with pass^k noise-robustness. Re-runnable as
    the config system evolves (SHA-keyed trend).
    """
    return Task(
        dataset=_dataset(),
        solver=seon_pod_solver(),
        scorer=includes(),
        epochs=Epochs(PASS_K, pass_at(PASS_K)),
    )
