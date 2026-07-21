"""Tool-use / data benchmark — axis 4 of the balanced-agent battery.

Mirrors memory_qa_bench (json_dataset -> seon_pod_solver -> host-side token match
-> pass^k, chunkable). Each sample is a concrete data task about DRIVING THE VERBS
(db/transact! + db/query, or the my.kb toolkit) over structured rows: store the
rows, then aggregate them (group-by-sum / max / count / join-sum) and report the
computed result. The scored value exists NOWHERE in the prompt — it is the output
of a query the agent had to express — so this axis is DISTINCT from memory (a fact
read back verbatim): it fails if the agent recalls the rows but can't drive the
verbs to compute over them.

DESIGN: docs/prds/agent-fsm/research/balanced-benchmark-battery-2026-07-02.md

SCORING: host-side `token_includes()` — a word-boundary match of the computed
target (a unique integer / short name) in the reply, so a small-integer target
doesn't false-match inside a larger number. A timeout / empty / no-answer reply
scores MISS — correct, not stale state.

RUN:
    # smoke:
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve \
      inspect eval tool_use_data_bench.py@tool_use_data_smoke \
        --model mockllm/model --display plain
    # full pass^k (OWNER-GATED, chunk 4x under the ~1hr bg cap, --max-samples 1):
    SEON_SOLVE_URL=http://127.0.0.1:7890/solve SEON_SOLVE_TIMEOUT_S=300 \
      inspect eval tool_use_data_bench.py@tool_use_data_bench \
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


@scorer(metrics=[accuracy()])
def token_includes() -> Scorer:
    """Host-side whole-token match of the computed target in the reply.

    Like inspect's `includes()` but word-boundary-delimited, so a small-integer
    target (e.g. '2') does NOT false-match inside a larger number ('22') — the
    aggregation targets are short, and a raw substring would over-credit. A
    timed-out / empty reply misses (no token). The answer key stays host-side.
    """

    async def score(state: TaskState, target: Target) -> Score:
        reply = state.output.completion or ""
        want = target.text
        ok = re.search(rf"(?<![\w-]){re.escape(want)}(?![\w-])", reply, re.IGNORECASE) is not None
        return Score(
            value="C" if ok else "I",
            answer=f"want={want!r} closed_reason={(state.metadata or {}).get('pod_closed_reason')!r}",
            explanation=("computed value present" if ok
                         else "FAIL: computed target token not in reply"),
        )

    return score

DATASET_PATH = os.path.join(os.path.dirname(__file__), "tool_use_data_dataset.jsonl")
PASS_K = int(os.environ.get("SEON_PASS_K", "4"))


def _dataset(limit: int | None = None):
    ds = json_dataset(DATASET_PATH)
    if limit is not None:
        ds = ds[:limit]
    return ds


@task
def tool_use_data_smoke():
    """SMOKE: 3 samples, 1 epoch — prove the harness runs end-to-end vs /solve."""
    return Task(
        dataset=_dataset(limit=3),
        solver=seon_pod_solver(),
        scorer=token_includes(),
    )


@task
def tool_use_data_bench():
    """FULL tool-use/data baseline: all 12 samples x pass^k, host-side `includes`.

    OWNER-GATED. Measures the agent's ability to store structured rows and drive
    its query verbs to aggregate them (group-by-sum / max / count / join), under
    pass^k noise-robustness. Re-runnable as config evolves.
    """
    return Task(
        dataset=_dataset(),
        solver=seon_pod_solver(),
        scorer=token_includes(),
        epochs=Epochs(PASS_K, pass_at(PASS_K)),
    )
