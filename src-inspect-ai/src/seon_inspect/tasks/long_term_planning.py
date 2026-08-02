"""Offline proof for the long-horizon planning scorer.

The task uses explicit goldens for plan continuity and plan-preload arms.
Live execution will enter through the current provider boundary rather than
the deleted pod restart and writer-port read-back path.
"""

from __future__ import annotations

from typing import Any

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.scorer import pass_at
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.generators import generate_rows
from seon_inspect.planning import planning_scorer

# ---------------------------------------------------------------------------
# Frozen golden pairs for the offline discrimination proof. Constructed to
# exercise EVERY oracle part (answer + trajectory + decompose-first +
# close-adjacency); verified to score good→CORRECT / bad→INCORRECT. The
# interruption boundary is t=1000ms: a "resumed" step is one created before it
# and completed after it.
# ---------------------------------------------------------------------------

_MOCK_ORACLE: dict[str, Any] = {
    "final": {"kind": "integer", "answer": "42"},
    "resume": {"min_pre_steps": 2},
}

_GOOD: dict[str, Any] = {
    "reply": "All steps are done. The total is 42.",
    "t_interrupt_ms": 1000,
    # root goal + 3 children, all planned pre-restart; two children COMPLETED
    # after the restart (resumption), none left open, no new post-restart root.
    "plan_snapshot": [
        {"id": "r", "parent_id": None, "title": "Expense tracker",
         "status": "done", "created_at_ms": 100, "completed_at_ms": 1100,
         "from_message": False},
        {"id": "s1", "parent_id": "r", "title": "Design shape",
         "status": "done", "created_at_ms": 110, "completed_at_ms": 300,
         "from_message": False},
        {"id": "s2", "parent_id": "r", "title": "Store seeds",
         "status": "done", "created_at_ms": 120, "completed_at_ms": 1050,
         "from_message": False},
        {"id": "s3", "parent_id": "r", "title": "Report totals",
         "status": "done", "created_at_ms": 130, "completed_at_ms": 1100,
         "from_message": False},
    ],
    # author BEFORE work; closes land as work lands (no batch dump).
    "eval_rows": [
        {"source": "(my.plan/plan! {:my.plan/title \"Expense tracker\"})",
         "ok": True},
        {"source": "(schema/register! :my.exp/amount-cents :int)", "ok": True},
        {"source": "(my.plan/done! s1)", "ok": True},
        {"source": "(db/transact! [{:my.exp/amount-cents 450}])", "ok": True},
        {"source": "(my.plan/done! s2)", "ok": True},
        {"source": "(db/query '[:find (sum ?a) . :where "
                   "[?e :my.exp/amount-cents ?a]])", "ok": True},
        {"source": "(my.plan/done! s3)", "ok": True},
    ],
}

_BAD: dict[str, Any] = {
    # fails answer (no integer), trajectory (1 pre step, a NEW post-restart
    # root, nothing resumed, an open pre step), decompose-first (no author),
    # and close-adjacency (a 3-run batch dump) — robustly INCORRECT.
    "reply": "I have designed a plan for the expenses.",
    "t_interrupt_ms": 1000,
    "plan_snapshot": [
        {"id": "r", "parent_id": None, "title": "Expenses",
         "status": "in-progress", "created_at_ms": 100,
         "completed_at_ms": None, "from_message": False},
        {"id": "r2", "parent_id": None, "title": "Restart plan",
         "status": "in-progress", "created_at_ms": 1200,
         "completed_at_ms": None, "from_message": False},
    ],
    "eval_rows": [
        {"source": "(db/transact! [{:my.exp/amount-cents 450}])", "ok": True},
        {"source": "(my.plan/done! s1)", "ok": True},
        {"source": "(my.plan/done! s2)", "ok": True},
        {"source": "(my.plan/done! s3)", "ok": True},
    ],
}

_GOLDEN = {"good": _GOOD, "bad": _BAD}

_EXPERIMENT_GOLDEN: dict[str, dict[str, Any]] = {
    "pretransacted": {
        "plan_evidence": {
            "observed": True, "observed_at_t": 500,
            "plan_present": True, "first_turn_t": 100,
            "agent_eid": 42, "harness_plan_tx_ids": [90],
            "history_observed": True,
            "run_historical_root_ids": ["p-root"],
            "run_root_creation_count": 0,
            "run_root_creation_tx_ids": [],
            "roots": [{"id": "p-root", "creation_t": 90,
                       "creation_tx_id": 90, "creation_user_eid": 7}],
        },
        "database_outcome": {"answer": "25"},
        "plan_closes": [
            {"step_id": "p1", "expect_verified": True},
            {"step_id": "p2", "expect_verified": True},
            {"step_id": "p3", "expect_verified": True},
        ],
        "report_events": [
            {"kind": "message_user", "content": "island-loop: 25"},
            {"kind": "run_closed"},
        ],
        "address_evidence": {
            "coverage_complete": True,
            "observations": [
                {"address_active": False, "authored_open": True},
                {"address_active": False, "authored_open": False},
            ],
        },
    },
    "model_authored": {
        "plan_evidence": {
            "observed": True, "observed_at_t": 500,
            "plan_present": True, "first_turn_t": 100,
            "agent_eid": 42, "harness_plan_tx_ids": [],
            "history_observed": True,
            "run_historical_root_ids": ["a-root"],
            "run_root_creation_count": 1,
            "run_root_creation_tx_ids": [111],
            "roots": [{"id": "a-root", "creation_t": 110,
                       "creation_tx_id": 111, "creation_user_eid": 42}],
        },
        "database_outcome": {"answer": "25"},
        "plan_closes": [
            {"step_id": "a1", "expect_verified": True},
            {"step_id": "a2", "expect_verified": True},
        ],
        "report_events": [
            {"kind": "message_user", "content": "The result is 25."},
            {"kind": "run_closed"},
        ],
        "address_evidence": {
            "coverage_complete": True,
            "observations": [
                {"address_active": False, "authored_open": True},
            ],
        },
    },
    # Pilot regression: plausible prose reported 26, and the same wrong value
    # reached durable memory because no plan expectation existed to falsify it.
    "no_plan": {
        "plan_evidence": {
            "observed": True, "observed_at_t": 500,
            "plan_present": False, "first_turn_t": 100,
            "agent_eid": 42, "harness_plan_tx_ids": [],
            "history_observed": True, "run_historical_root_ids": [],
            "run_root_creation_count": 0, "run_root_creation_tx_ids": [],
            "roots": [],
        },
        "database_outcome": {"answer": "26"},
        "plan_closes": [],
        "report_events": [
            {"kind": "message_user", "content": "island-loop: 26"},
            {"kind": "run_closed"},
        ],
        "address_evidence": {"coverage_complete": False,
                             "observations": []},
    },
}

_EXPERIMENT_ORACLE: dict[str, Any] = {
    "final": {"kind": "integer", "answer": "25"},
}


@solver
def planning_solver(endpoint: str):
    """Replay an explicit offline planning golden."""

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        if endpoint.startswith("mock:experiment:"):
            arm = endpoint.rsplit(":", 1)[1]
            state.output.completion = "offline experiment fixture"
            state.metadata["plan_experiment"] = {
                "arm": arm, **_EXPERIMENT_GOLDEN[arm]}
            state.metadata["oracle"] = _EXPERIMENT_ORACLE
            return state
        if endpoint.startswith("mock:"):
            golden = _GOLDEN[endpoint.split(":", 1)[1]]  # "good" | "bad"
            state.output.completion = golden["reply"]
            state.metadata["plan_snapshot"] = golden["plan_snapshot"]
            state.metadata["t_interrupt_ms"] = golden["t_interrupt_ms"]
            state.metadata["eval_rows"] = golden["eval_rows"]
            state.metadata["oracle"] = _MOCK_ORACLE
            return state
        raise ValueError(f"unknown endpoint {endpoint!r} "
                         "(mock:good | mock:bad | "
                         "mock:experiment:<arm>)")

    return solve


@task
def long_term_planning(seed: str = "s1", n: int = 1,
                       endpoint: str = "mock:good", epochs: int = 1):
    """Long-horizon planning rows x pass^k epochs (plan → restart → resume)."""
    if endpoint.startswith("mock:experiment:"):
        arm = endpoint.rsplit(":", 1)[1]
        if arm not in _EXPERIMENT_GOLDEN:
            raise ValueError(f"unknown experiment arm {arm!r}")
        samples = [Sample(id=f"mock-experiment-{arm}", input=arm,
                          target="25", metadata={})]
    elif endpoint.startswith("mock:"):
        # The mock arm scores its OWN self-contained golden, not a generated
        # row — one sample is the whole discrimination proof.
        samples = [Sample(id=f"mock-{endpoint.split(':', 1)[1]}",
                          input=_GOOD["reply"], target="42", metadata={})]
    else:
        rows = generate_rows("long_term_planning", seed, n)
        samples = [Sample(id=r["id"], input=r["input"],
                          target=r["target"], metadata=r["metadata"])
                   for r in rows]
    return Task(
        dataset=MemoryDataset(samples),
        solver=planning_solver(endpoint),
        scorer=planning_scorer(),
        epochs=Epochs(epochs, ["mean", pass_at(epochs)]),
    )
