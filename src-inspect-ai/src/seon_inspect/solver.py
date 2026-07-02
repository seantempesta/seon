"""Seon-pod-as-inspect-solver — the canonical /solve bridge (Option B).

Promoted from the Phase-0 spike (docs/prds/agent-fsm/research/inspect-bridge-
spike/seon_solver.py — kept there as history; THIS module is the maintained
home). Mechanism, unchanged and proven: inspect supplies dataset + host-side
scorer; the Seon pod agent is the SOLVER, driven through the productionized
`POST /solve` door in `seon.web.serve`. The pod's OWN FSM runs every turn —
inspect never caps or manages turns. Deliberately NOT the model-proxy /
sandbox_agent_bridge path (that routes the agent's LLM calls through inspect
and replaces Seon's loop — rejected; see the spike doc §1).

The pod records honestly under the clock: `/solve` returns `timed_out` +
`closed_reason "timeout"` on a clock cut-off (never a stale :completed/greeting
reply), and `timeout_honesty()` is the scorer that asserts exactly that.
"""

from __future__ import annotations

import json
import os
import urllib.request

from inspect_ai.scorer import Score, Scorer, Target, accuracy, scorer
from inspect_ai.solver import Generate, TaskState, solver

POD_SOLVE_URL = os.environ.get("SEON_SOLVE_URL", "http://127.0.0.1:7890/solve")
# Wall-clock budget the pod may run its own multi-turn loop to idle.
SOLVE_TIMEOUT_S = int(os.environ.get("SEON_SOLVE_TIMEOUT_S", "300"))


def pod_solve(prompt: str, timeout_ms: int) -> dict:
    """One request/response call to the pod's /solve door.

    POST {input, timeout_ms} → the pod mints an ISOLATED scratch agent (fresh
    :memory conn per request — serve.cljs `solve-once!`), injects the input as
    a real user message, awaits idle, returns the reply + honest metadata
    (turns / evals / closed_reason / timed_out). Serial-only for benchmarks:
    run with `--max-samples 1` (the async wake path reads the root conn).
    """
    body = json.dumps({"input": prompt, "timeout_ms": timeout_ms}).encode()
    req = urllib.request.Request(
        POD_SOLVE_URL, data=body, headers={"Content-Type": "application/json"}
    )
    # HTTP read budget stays generous — the POD bails on ITS timeout_ms.
    with urllib.request.urlopen(req, timeout=SOLVE_TIMEOUT_S + 30) as resp:
        return json.loads(resp.read().decode())


@solver
def seon_pod_solver():
    """Drive the Seon pod agent as the solver for one sample.

    Sets state.output.completion to the pod agent's final reply and records the
    pod-side metadata (turns / closed_reason / evals / timed_out / elapsed) so
    the eval log proves the multi-turn loop ran AND recorded honestly. A sample
    may set metadata["timeout_ms"] to force a short pod-side budget.
    """

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        prompt = state.input_text
        timeout_ms = int((state.metadata or {}).get("timeout_ms",
                                                    SOLVE_TIMEOUT_S * 1000))
        result = await anyio.to_thread.run_sync(pod_solve, prompt, timeout_ms)
        state.output.completion = result.get("reply", "")
        state.metadata = state.metadata or {}
        state.metadata.update({
            "pod_agent_id": result.get("agent_id"),
            "pod_turns": result.get("turns"),
            "pod_closed_reason": result.get("closed_reason"),
            "pod_evals": result.get("evals"),
            "pod_timed_out": result.get("timed_out", False),
            "pod_elapsed_ms": result.get("elapsed_ms"),
        })
        return state

    return solve


@scorer(metrics=[accuracy()])
def timeout_honesty() -> Scorer:
    """A timed-out sample must be RECORDED honestly (anti-mis-recording gate).

    CORRECT iff the pod reported timed_out=True AND closed_reason contains
    "timeout". A false success (a completed/waited close or a stale reply on a
    clock cut-off) is INCORRECT — the benchmark-corrupting failure this exists
    to keep fixed.
    """

    async def score(state: TaskState, target: Target) -> Score:
        md = state.metadata or {}
        timed_out = bool(md.get("pod_timed_out"))
        reason = str(md.get("pod_closed_reason") or "")
        honest = timed_out and ("timeout" in reason)
        return Score(
            value="C" if honest else "I",
            answer=f"timed_out={timed_out} closed_reason={reason!r}",
            explanation=("honest timeout recorded" if honest else
                         "DISHONEST: a timed-out run must report timed_out+timeout"),
        )

    return score
