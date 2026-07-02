"""Coding / eval benchmark — axis 3 of the balanced-agent battery.

Mirrors memory_qa_bench (json_dataset -> seon_pod_solver -> host-side scorer ->
pass^k, chunkable) but scores schema-first CODING: each sample asks the agent to
define a small pure fn WITH a correct :malli/schema and call it on a HOLD-OUT
probe input, reporting the probe result. Because the fn must survive the pod's
always-on instrumentation to produce any output, a wrong schema fails the sample
for free — so this axis exercises the eval path + instrumentation + the
schema-first discipline end to end.

DESIGN: docs/prds/agent-fsm/research/balanced-benchmark-battery-2026-07-02.md

SCORING (`code_probe`): host-side. CORRECT iff the reply contains the host-side
probe target (an integer / short string NOT in the examples, so copying an example
can't pass). Records `pod_evals` — a right answer with zero evals is suspicious
(the agent may have computed the probe in prose instead of running its fn); it
still scores on substring (the primary signal) but the explanation flags it.

RUN:
    # smoke:
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve \
      inspect eval coding_eval_bench.py@coding_eval_smoke \
        --model mockllm/model --display plain
    # full pass^k (OWNER-GATED, chunk 4x under the ~1hr bg cap, --max-samples 1):
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve SEON_SOLVE_TIMEOUT_S=300 \
      inspect eval coding_eval_bench.py@coding_eval_bench \
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


def _token_match(target: str, reply: str) -> bool:
    """Whole-token (word-boundary) match — so target '4' does NOT match '24'.

    The coding/tool-use targets are small integers/short words; a raw substring
    would false-positive ('4' inside '24'). A \\b-delimited match keeps the
    unique-token guarantee the memory axis got for free from surnames.
    """
    return re.search(rf"(?<![\w-]){re.escape(target)}(?![\w-])", reply, re.IGNORECASE) is not None

DATASET_PATH = os.path.join(os.path.dirname(__file__), "coding_eval_dataset.jsonl")
PASS_K = int(os.environ.get("SEON_PASS_K", "4"))


def _dataset(limit: int | None = None):
    ds = json_dataset(DATASET_PATH)
    if limit is not None:
        ds = ds[:limit]
    return ds


@scorer(metrics=[accuracy()])
def code_probe() -> Scorer:
    """CORRECT iff the reply carries the hold-out probe result.

    Substring of the host-side probe target (a value NOT among the spec examples,
    so the agent had to actually run its own fn on the probe). `pod_evals` is
    recorded: 0 evals with a correct answer is flagged as suspicious (prose
    computation rather than a real fn call) but does not by itself fail — the
    held-out probe already makes copy-an-example impossible.
    """

    async def score(state: TaskState, target: Target) -> Score:
        md = state.metadata or {}
        reply = state.output.completion or ""
        want = target.text
        has_answer = _token_match(want, reply)
        evals = md.get("pod_evals")
        timed_out = bool(md.get("pod_timed_out"))
        ok = has_answer and not timed_out
        suspicious = ok and (evals == 0)
        return Score(
            value="C" if ok else "I",
            answer=f"has_answer={has_answer} evals={evals} closed_reason={md.get('pod_closed_reason')!r}",
            explanation=(
                ("PASS but SUSPICIOUS: correct with 0 evals — may be prose-computed, "
                 "not a real fn call") if suspicious else
                ("defined + ran the fn on the probe, reported the result" if ok else
                 "FAIL: probe result not in reply (or timed out)")
            ),
        )

    return score


@task
def coding_eval_smoke():
    """SMOKE: 3 samples, 1 epoch — prove the harness runs end-to-end vs /solve."""
    return Task(
        dataset=_dataset(limit=3),
        solver=seon_pod_solver(),
        scorer=code_probe(),
    )


@task
def coding_eval_bench():
    """FULL coding baseline: all 12 samples x pass^k, host-side code_probe.

    OWNER-GATED. Measures schema-first fn authoring + eval + hold-out-probe
    execution under pass^k noise-robustness. Re-runnable as config evolves.
    """
    return Task(
        dataset=_dataset(),
        solver=seon_pod_solver(),
        scorer=code_probe(),
        epochs=Epochs(PASS_K, pass_at(PASS_K)),
    )
