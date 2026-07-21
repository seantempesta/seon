"""Planning + resume benchmark — axis 2 of the balanced-agent battery.

Mirrors memory_qa_bench exactly (json_dataset -> seon_pod_solver -> host-side
scorer -> pass^k, chunkable) but scores the OTHER canonical CLAUDE.md shape:
long-term planning that survives interruption. Each sample is a 3-step task the
agent must decompose into durable todos and complete, then report a SYNTHESIS
value (the sum of three stored numbers) that only exists if all steps ran.

DESIGN: docs/prds/agent-fsm/research/balanced-benchmark-battery-2026-07-02.md

SCORING (`plan_completion`): host-side. CORRECT iff the reply contains the
host-side synthesis target AND the pod closed `:completed` (NOT turn-limit /
timeout / no-forms). The `:completed` gate encodes the B2 output-discipline
lesson: an agent that computes the sum but rambles past it, or never closes its
plan, is NOT a balanced planner and fails. (When a read-back door for todo state
exists, upgrade this to also require all plan todos `✓` — see design § Open.)

RESUME VARIANT (SEON_SOLVE_RESUME=1): the samples with metadata.interrupt=True are
re-driven to prove resume-from-open-items. This needs a `/solve` extension to
reuse an existing agent id across two calls (design § Open) — until it lands, the
default single-call durable-plan mode runs (proves decomposition + completion, not
cross-call resume). The resume path is scaffolded but gated OFF by default.

RUN (default single-call mode — runs today once the pod is free):
    # smoke:
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve \
      inspect eval planning_resume_bench.py@planning_resume_smoke \
        --model mockllm/model --display plain
    # full pass^k (OWNER-GATED, chunk 4x under the ~1hr bg cap, --max-samples 1):
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve SEON_SOLVE_TIMEOUT_S=300 \
      inspect eval planning_resume_bench.py@planning_resume_bench \
        --model mockllm/model --display plain --max-samples 1
"""

from __future__ import annotations

import os
import re

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import json_dataset
from inspect_ai.scorer import Score, Scorer, Target, accuracy, pass_at, scorer
from inspect_ai.solver import TaskState

from seon_solver import seon_pod_solver

DATASET_PATH = os.path.join(os.path.dirname(__file__), "planning_resume_dataset.jsonl")
PASS_K = int(os.environ.get("SEON_PASS_K", "4"))


def _dataset(limit: int | None = None):
    ds = json_dataset(DATASET_PATH)
    if limit is not None:
        ds = ds[:limit]
    return ds


@scorer(metrics=[accuracy()])
def plan_completion() -> Scorer:
    """CORRECT iff reply carries the synthesis target AND the pod closed cleanly.

    Two conditions, both host-side:
      1. the synthesis value (sum of the three stored numbers) appears in the
         reply — proves the agent did all steps and retrieved + combined them;
      2. `pod_closed_reason == "completed"` — proves it FINISHED (the B2
         output-discipline gate; a ramble→turn-limit/timeout is a planning fail
         even if the number leaked into some mid-ramble sentence).
    A timed-out drive is INCORRECT regardless of reply text (`pod_timed_out`).
    """

    async def score(state: TaskState, target: Target) -> Score:
        md = state.metadata or {}
        reply = state.output.completion or ""
        want = target.text
        # whole-token match so a synthesis sum '81' doesn't false-match inside '810'
        has_answer = re.search(rf"(?<![\w-]){re.escape(want)}(?![\w-])",
                               reply, re.IGNORECASE) is not None
        closed = str(md.get("pod_closed_reason") or "")
        timed_out = bool(md.get("pod_timed_out"))
        completed = ("completed" in closed) and not timed_out
        ok = has_answer and completed
        return Score(
            value="C" if ok else "I",
            answer=f"has_answer={has_answer} closed_reason={closed!r} turns={md.get('pod_turns')}",
            explanation=(
                "planned, completed all steps, reported synthesis, and closed"
                if ok else
                f"FAIL: has_answer={has_answer}, completed={completed} "
                f"(reply must carry the synthesis value AND the run must close :completed)"
            ),
        )

    return score


@task
def planning_resume_smoke():
    """SMOKE: 3 samples, 1 epoch — prove the harness runs end-to-end vs /solve."""
    return Task(
        dataset=_dataset(limit=3),
        solver=seon_pod_solver(),
        scorer=plan_completion(),
    )


@task
def planning_resume_bench():
    """FULL planning baseline: all 10 samples x pass^k, host-side plan_completion.

    OWNER-GATED. Measures durable-decomposition + all-steps-completion + clean
    close of the agent's own planning under pass^k noise-robustness. Re-runnable
    as config evolves (SHA-keyed trend), same as the memory baseline.
    """
    return Task(
        dataset=_dataset(),
        solver=seon_pod_solver(),
        scorer=plan_completion(),
        epochs=Epochs(PASS_K, pass_at(PASS_K)),
    )
