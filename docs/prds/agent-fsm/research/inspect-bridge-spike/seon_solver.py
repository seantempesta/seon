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


def _pod_solve(prompt: str) -> dict:
    """One request/response call to the pod's thin /solve door.

    Contract (the boundary add, mirroring the gym driver): POST {input} →
    the pod mints/uses a scratch agent, injects the input as a user message
    (the REAL wake path → the REAL FSM), awaits idle, returns the agent's
    final reply text + metadata (turns taken, closed-reason, evals). Inspect
    is purely the caller here.
    """
    body = json.dumps({"input": prompt, "timeout_ms": SOLVE_TIMEOUT_S * 1000}).encode()
    req = urllib.request.Request(
        POD_SOLVE_URL, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=SOLVE_TIMEOUT_S + 30) as resp:
        return json.loads(resp.read().decode())


@solver
def seon_pod_solver():
    """Drive the Seon pod agent as the solver for one sample.

    Sets state.output.completion to the pod agent's final reply. Records the
    pod-side metadata (turns / closed-reason / evals) into metadata so the log
    proves the multi-turn loop actually ran.
    """

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        prompt = state.input_text
        # run the blocking HTTP call off the event loop
        result = await anyio.to_thread.run_sync(_pod_solve, prompt)

        state.output.completion = result.get("reply", "")
        state.metadata = state.metadata or {}
        state.metadata.update(
            {
                "pod_agent_id": result.get("agent_id"),
                "pod_turns": result.get("turns"),
                "pod_closed_reason": result.get("closed_reason"),
                "pod_evals": result.get("evals"),
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


@task
def seon_memory_smoke():
    return Task(
        dataset=MemoryDataset(SMOKE_SAMPLES),
        solver=seon_pod_solver(),
        scorer=includes(),  # host-side, exact substring; answer key stays host-side
    )
