"""Balanced-agent benchmark BATTERY — the index of all four capability axes.

This is a doc + registry module, NOT a combined run: chunking is per-axis (the
environment kills bg tasks at ~1hr, so each axis runs as 3-4 chunks of
`4 samples x K4`, `--max-samples 1`, merged by sample id — see the design doc).
Importing this module registers every axis task so `inspect eval battery.py@<task>`
works, and BATTERY documents the recipe in one place.

DESIGN: docs/prds/agent-fsm/research/balanced-benchmark-battery-2026-07-02.md

THE FOUR AXES (each = dataset + host-side scorer + /solve solver + pass^k):
  1. memory        — memory_qa_bench.py@memory_qa_bench           (DONE, pass^4=1.000)
  2. planning+resume — planning_resume_bench.py@planning_resume_bench (scaffolded)
  3. coding/eval   — coding_eval_bench.py@coding_eval_bench       (scaffolded)
  4. tool-use/data — tool_use_data_bench.py@tool_use_data_bench   (scaffolded)

ANTI-CHEAT (held-out judges): general task shapes; host-side answer keys that
never enter the pod; NO answer-shaped or benchmark-aware guidance anywhere; probes
held out of the examples. Datasets test capability, not solutions.

STATUS: FILE-ONLY. Nothing here has been run live — a benchmark on broken
rendering is invalid, so the live baselines wait for the Core render-fix + a free
pod. Each axis has a `_smoke` task (2-3 samples, 1 epoch) to prove wiring first.

RUN RECIPE (per axis, once the pod is free):
    # 1) smoke each axis (wiring proof, cheap):
    for t in memory_qa_smoke planning_resume_smoke coding_eval_smoke tool_use_data_smoke; do
      SEON_SOLVE_URL=http://127.0.0.1:7890/solve \
        inspect eval battery.py@$t --model mockllm/model --display plain
    done
    # 2) full pass^k per axis (OWNER-GATED), chunked 4-per-run under the ~1hr cap:
    #    (mirror scratchpad/run_chunk.sh from the memory baseline: --sample-id x4,
    #     --max-samples 1, one .eval per chunk, merge by id.)
"""

from __future__ import annotations

# Re-export every axis's tasks so they register under battery.py's module.
from coding_eval_bench import coding_eval_bench, coding_eval_smoke  # noqa: F401
from memory_qa_bench import memory_qa_bench, memory_qa_smoke  # noqa: F401
from planning_resume_bench import (  # noqa: F401
    planning_resume_bench,
    planning_resume_smoke,
)
from tool_use_data_bench import tool_use_data_bench, tool_use_data_smoke  # noqa: F401

AXES = {
    "memory": "memory_qa_bench",
    "planning": "planning_resume_bench",
    "coding": "coding_eval_bench",
    "tooluse": "tool_use_data_bench",
}
SMOKES = {
    "memory": "memory_qa_smoke",
    "planning": "planning_resume_smoke",
    "coding": "coding_eval_smoke",
    "tooluse": "tool_use_data_smoke",
}
