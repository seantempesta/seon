"""Seon-pod-as-inspect-solver — Phase-0 bridge (OUTER-SHELL ONLY).

inspect-ai supplies dataset + host-side scorer. Seon's pod agent is the SOLVER,
invoked at its EXISTING boundary (a thin request/response HTTP door that reuses
the gym's start-and-await-read recipe). This module is the inspect side: a
custom @solver that POSTs one sample's input to the pod and returns the agent's
final reply as the completion. The pod's OWN FSM runs every turn — inspect never
caps or manages turns, and NONE of the agent loop / context / eval / FSM is
touched.

Deliberately does NOT use inspect's model-proxy / OpenAI agent_bridge (that path
routes the agent's LLM calls through inspect and reaches into how the agent talks
to the model — the invasive path the owner ruled out). The pod keeps its own LLM
adapter (DeepSeek). Inspect's model is `mockllm/model` — never called, just
satisfies eval()'s model requirement, since our solver bypasses generate().
"""

from __future__ import annotations

import json
import os
import urllib.request

from inspect_ai import Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import includes, model_graded_qa
from inspect_ai.solver import Generate, TaskState, solver

POD_SOLVE_URL = os.environ.get("SEON_SOLVE_URL", "http://127.0.0.1:7890/solve")
# Wall-clock budget the pod is allowed to run its own multi-turn loop to idle.
SOLVE_TIMEOUT_S = int(os.environ.get("SEON_SOLVE_TIMEOUT_S", "300"))


def _pod_solve(prompt: str, timeout_ms: int) -> dict:
    """One request/response call to the pod's thin /solve door.

    Contract (the boundary add, mirroring the gym driver): POST {input} →
    the pod mints/uses a scratch agent, injects the input as a user message
    (the REAL wake path → the REAL FSM), awaits idle, returns the agent's
    final reply text + metadata (turns taken, closed-reason, evals). Inspect
    is purely the caller here. `timeout_ms` is the pod-side wall-clock cap; a
    tiny value forces the honest-timeout path (closed_reason "timeout").
    """
    body = json.dumps({"input": prompt, "timeout_ms": timeout_ms}).encode()
    req = urllib.request.Request(
        POD_SOLVE_URL, data=body, headers={"Content-Type": "application/json"}
    )
    # HTTP read budget always generous — the pod bails on ITS timeout_ms, we wait for that.
    with urllib.request.urlopen(req, timeout=SOLVE_TIMEOUT_S + 30) as resp:
        return json.loads(resp.read().decode())


@solver
def seon_pod_solver():
    """Drive the Seon pod agent as the solver for one sample.

    Sets state.output.completion to the pod agent's final reply. Records the
    pod-side metadata (turns / closed-reason / evals / timed_out) into metadata
    so the log proves the multi-turn loop actually ran AND records honestly.
    A sample may set metadata["timeout_ms"] to force a short pod-side budget
    (the honest-timeout proof); default is SOLVE_TIMEOUT_S.
    """

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        prompt = state.input_text
        timeout_ms = int((state.metadata or {}).get("timeout_ms", SOLVE_TIMEOUT_S * 1000))
        # run the blocking HTTP call off the event loop
        result = await anyio.to_thread.run_sync(_pod_solve, prompt, timeout_ms)

        state.output.completion = result.get("reply", "")
        state.metadata = state.metadata or {}
        state.metadata.update(
            {
                "pod_agent_id": result.get("agent_id"),
                "pod_turns": result.get("turns"),
                "pod_closed_reason": result.get("closed_reason"),
                "pod_evals": result.get("evals"),
                # honest-recording proof: the pod distinguishes a real close
                # from a clock cut-off. timed_out => the answer is NOT valid.
                "pod_timed_out": result.get("timed_out", False),
                "pod_elapsed_ms": result.get("elapsed_ms"),
            }
        )
        return state

    return solve


# --- The smoke task: 3 memory/QA samples the agent answers with its OWN fns ---
# case-1 (no inspect-tool bridging): the agent stores facts to its own KB and
# retrieves them across turns. Scored host-side by `includes` (exact-substring)
# for the crisp facts; the answer key (target) NEVER enters the pod/sandbox.

SMOKE_SAMPLES = [
    Sample(
        input=(
            "As a memory task using your own knowledge-base functions: "
            "1) Remember 'Project Zephyr launched in March 2021.' "
            "2) Remember 'Project Zephyr's lead engineer is Dana Okafor.' "
            "Then retrieve both and answer in one sentence: "
            "Who led the project that launched in March 2021? "
            "Store, then recall, then reply, then complete."
        ),
        target="Okafor",
    ),
    Sample(
        input=(
            "As a memory task using your own knowledge-base functions: "
            "1) Remember 'The Helios reactor produces 42 megawatts.' "
            "2) Remember 'The Helios reactor is located in Reykjavik.' "
            "Then retrieve both and answer in one sentence: "
            "How many megawatts does the reactor in Reykjavik produce? "
            "Store, then recall, then reply, then complete."
        ),
        target="42",
    ),
    Sample(
        input=(
            "As a memory task using your own knowledge-base functions: "
            "1) Remember 'The Orion mission carries 6 crew members.' "
            "2) Remember 'The Orion mission commander is Priya Raman.' "
            "Then retrieve both and answer in one sentence: "
            "Who commands the mission that carries 6 crew members? "
            "Store, then recall, then reply, then complete."
        ),
        target="Raman",
    ),
]


# --- The forced-timeout sample: proves the pod records HONESTLY under the clock ---
# Same shape as the memory samples but with a 2s pod-side budget it can't meet.
# The WIN condition is NOT a correct answer — it's an HONEST non-answer: the pod
# must report closed_reason "timeout" + timed_out true (never a false :completed/
# :waited with a stale/greeting reply). This is the anti-"we're-fucked-if-we-mis-
# record" proof, carried end-to-end through inspect.
TIMEOUT_SAMPLE = Sample(
    input=(
        "As a memory task using your own knowledge-base functions: "
        "1) Remember 'The Aurora satellite orbits at 550 kilometers.' "
        "2) Remember 'The Aurora satellite was built by Nadia Voss.' "
        "Then retrieve both and answer: who built the satellite orbiting at 550 km? "
        "Store, then recall, then reply, then complete."
    ),
    target="Voss",
    metadata={"timeout_ms": 2000},  # forces the honest-timeout path
)


from inspect_ai.scorer import Score, Scorer, Target, accuracy, scorer  # noqa: E402
from inspect_ai.solver import TaskState as _TS  # noqa: E402


@scorer(metrics=[accuracy()])
def timeout_honesty() -> Scorer:
    """Host-side check: a timed-out sample must be RECORDED honestly.

    CORRECT iff the pod reported timed_out=True AND closed_reason contains
    "timeout" AND it did NOT emit a completed/waited reply as if it had
    answered. A false success (timed_out but closed_reason ":completed",
    or a non-empty reply that scores against the target) is INCORRECT —
    that is the benchmark-corrupting failure we are proving is fixed.
    """

    async def score(state: _TS, target: Target) -> Score:
        md = state.metadata or {}
        timed_out = bool(md.get("pod_timed_out"))
        reason = str(md.get("pod_closed_reason") or "")
        honest = timed_out and ("timeout" in reason)
        return Score(
            value="C" if honest else "I",
            answer=f"timed_out={timed_out} closed_reason={reason!r} reply={state.output.completion!r}",
            explanation=(
                "honest timeout recorded" if honest
                else "DISHONEST: a timed-out run must report timed_out+timeout, "
                     "never a completed/waited answer"
            ),
        )

    return score


@task
def seon_memory_smoke():
    """Happy-path: 3 memory-QA samples, host-side `includes` scoring."""
    return Task(
        dataset=MemoryDataset(SMOKE_SAMPLES),
        solver=seon_pod_solver(),
        scorer=includes(),  # host-side, exact substring; answer key stays host-side
    )


@task
def seon_timeout_honesty():
    """Forced-timeout: proves the pod records an honest timeout, not a false pass."""
    return Task(
        dataset=MemoryDataset([TIMEOUT_SAMPLE]),
        solver=seon_pod_solver(),
        scorer=timeout_honesty(),
    )
