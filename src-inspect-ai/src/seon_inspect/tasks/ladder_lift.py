"""Ladder-lift as an inspect-ai task (runbook step 4 — THE thesis measurement).

Does the validation ladder, steering + terminating the loop mid-denoise, LIFT
behavioral correctness — and at what step cost? Two arms, one per eval run:

  ladder=true    the worker's `refine_loop` (bb op:"refine" renoise source,
                 eval_gate on, behavioral cases as the stop condition — stop on
                 ORACLE-PROOF, not model confidence).
  ladder=false   plain `generate` on the same prompt (free-gen floor).

Scored by `ladder_scorer` (CORRECT iff faithful; behavioral tier decides).
The worker's own metrics (stop_tier / iters / oracle_ms / tok_per_s /
denoise_steps) ride into the eval log via solver metadata, so the step-cost
side of the lift is in the same log as the correctness side.

RUN (offline, canned worker, REAL oracles):
    .venv/bin/inspect eval ladder_lift_task.py@ladder_lift \
      -T ladder=true -T endpoint=mock:ladder \
      --model mockllm/model --display plain
GPU: -T endpoint=runpod (after verify_fresh — runbook step 0).
"""

from __future__ import annotations

import os
from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import pass_at
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.oracle_scorers import assert_oracle_live, ladder_scorer
from seon_inspect.worker_endpoints import resolve_endpoint

TASKS = [
    {
        "name": "celsius->fahrenheit",
        "prompt": ("Write `celsius->fahrenheit` as a Seon map-in/map-out fn with a "
                   "registered :malli/schema. It takes ONE map argument like "
                   "{::celsius 20.0} and returns a map like {::fahrenheit 68.0}. "
                   "Reply with ONLY a ```clojure``` block."),
        "spec": {
            "fn_name": "celsius->fahrenheit",
            # CORRECTNESS expectation only: spec present (either idiom).
            "expects": {"malli_schema": True},
            "cases": [{"in": "{::celsius 0.0}", "key": "::fahrenheit", "expect": 32.0},
                      {"in": "{::celsius 100.0}", "key": "::fahrenheit",
                       "expect": 212.0}],
        },
        # the worker-side behavioral gate (refine_loop payload shape — runbook step 4)
        "behavioral": [{"call": "(celsius->fahrenheit {::celsius 100.0})",
                        "expect": "{::fahrenheit 212.0}"},
                       {"call": "(celsius->fahrenheit {::celsius 0.0})",
                        "expect": "{::fahrenheit 32.0}"}],
    },
]

WORKER_METRICS = ("stop_tier", "validated", "iters", "oracle_ms_mean",
                  "denoise_steps", "tok_per_s", "worker_sha")


def _samples():
    return [Sample(id=t["name"], input=t["prompt"], target="faithful",
                   metadata={"task": t, "spec": t["spec"]})
            for t in TASKS]


@solver
def ladder_solver(ladder: bool, endpoint: str):
    """Drive refine_loop (ladder ON) or plain generate (OFF) per sample/epoch."""
    ep = resolve_endpoint(endpoint)

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        t = state.metadata["task"]
        idx = max(0, (getattr(state, "epoch", 1) or 1) - 1)
        if ladder:
            payload = {"mode": "refine_loop", "prompt": t["prompt"], "max_iters": 6,
                       "eval_gate": True, "behavioral": t["behavioral"], "_idx": idx}
        else:
            payload = {"mode": "generate", "prompt": t["prompt"],
                       "max_new_tokens": 320, "_idx": idx}
        r = await anyio.to_thread.run_sync(ep.call, payload)
        state.output.completion = (r.get("text") or r.get("code_buffer_text") or "")
        state.metadata.update({"ladder": ladder,
                               **{k: r.get(k) for k in WORKER_METRICS}})
        return state

    return solve


@task
def ladder_lift(ladder: bool = True,
                endpoint: str = "mock:ladder",
                epochs: int = 4):
    """One ladder arm x pass^k epochs; worker metrics ride the eval log."""
    assert_oracle_live()
    return Task(
        dataset=MemoryDataset(_samples()),
        solver=ladder_solver(ladder, endpoint),
        scorer=ladder_scorer(),
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
    )
