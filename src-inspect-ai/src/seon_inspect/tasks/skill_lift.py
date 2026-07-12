"""Skill-lift A/B as an inspect-ai task (the north-star ledger loop).

Control = task alone; treatment = the skill's SKILL.md body + task (verbatim the
proven skill_lift.py recipe). One condition per eval run; the LIFT is the
accuracy delta between the treatment and control runs at the same sha. Scored
by the oracle ladder (structural faithfulness — the ledger's original metric),
pass^k epochs for noise-robustness.

Dataset: seeded with the ledger's `data-modeling` pair (write a spec'd
map-in/map-out fn). Extend by appending (skill x task x expects) entries to
LEDGER — each new skill gets its (task, scorer) pair here, per the north-star.

RUN (offline, canned worker, REAL oracles):
    .venv/bin/inspect eval skill_lift_task.py@skill_lift \
      -T condition=treatment -T endpoint=mock:skill_lift \
      --model mockllm/model --display plain
GPU: -T endpoint=runpod (after verify_fresh — runbook step 0).
"""

from __future__ import annotations

import os
from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import pass_at
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.oracle_scorers import REPO, assert_oracle_live, ladder_scorer
from seon_inspect.worker_endpoints import resolve_endpoint

# idiom_gates=True is DELIBERATE here and only here: this task MEASURES whether
# context (the skill) teaches the PREFERRED named map-in/map-out idiom, so the
# idiom checks are the measurand, not a style gate on correctness (the owner
# correction that made every other task idiom-agnostic — see oracle_scorers).
LEDGER = [
    {
        "skill": "data-modeling",
        "prompt": ("Write `celsius->fahrenheit` as a Seon map-in/map-out fn with a "
                   "registered :malli/schema. It takes ONE map argument like "
                   "{::celsius 20.0} and returns a map like {::fahrenheit 68.0}. "
                   "Reply with ONLY a ```clojure``` block."),
        "spec": {"idiom_gates": True,
                 "expects": {"register": True, "malli_schema": True,
                             "map_in_out": True, "namespaced_kw": True}},
    },
]


def _skill_body(name: str) -> str:
    """The skill's SKILL.md body (empty when absent — the mock ignores prompts)."""
    p = os.path.join(REPO, "seon-skills", name, "SKILL.md")
    if os.path.exists(p):
        with open(p) as f:
            return f.read()
    return ""


def _samples():
    return [Sample(id=e["skill"], input=e["prompt"], target="faithful",
                   metadata={"entry": e, "spec": e["spec"]})
            for e in LEDGER]


@solver
def skill_lift_solver(condition: str, endpoint: str):
    """Generate under control (task alone) or treatment (skill body + task)."""
    ep = resolve_endpoint(endpoint)

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        e = state.metadata["entry"]
        prompt = e["prompt"]
        if condition == "treatment":
            body = _skill_body(e["skill"])
            if body:
                prompt = body + "\n\n---\n\nUsing the Seon conventions above:\n\n" + prompt
        idx = max(0, (getattr(state, "epoch", 1) or 1) - 1)
        r = await anyio.to_thread.run_sync(
            ep.call, {"mode": "generate", "prompt": prompt, "max_new_tokens": 320,
                      "_cond": condition, "_idx": idx})
        state.output.completion = (r.get("text") or r.get("code_buffer_text") or "")
        state.metadata.update({"worker_sha": r.get("worker_sha"),
                               "tok_per_s": r.get("tok_per_s"),
                               "condition": condition})
        return state

    return solve


@task
def skill_lift(condition: str = "control",
               endpoint: str = "mock:skill_lift",
               epochs: int = 4):
    """One skill-lift condition x pass^k epochs, oracle-ladder scored."""
    assert_oracle_live()
    return Task(
        dataset=MemoryDataset(_samples()),
        solver=skill_lift_solver(condition, endpoint),
        scorer=ladder_scorer(),
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
    )
