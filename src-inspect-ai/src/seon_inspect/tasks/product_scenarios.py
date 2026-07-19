"""Native offline discrimination for Seon's integrated product scenarios."""

from __future__ import annotations

from copy import deepcopy

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect.product_scenarios import SCENARIOS, product_scenario_scorer


GOOD = {
    "namespace": {
        "target_namespace": "my.taxes",
        "agents": [{"agent_id": "a-tax", "namespace": "my.taxes",
                    "terminated": False, "first_eval_namespace": "my.taxes"}],
        "messages": [{"from_agent_id": "root", "to_agent_id": "a-tax"},
                     {"from_agent_id": "root", "to_agent_id": "a-tax"}],
    },
    "reuse_repair": {
        "qualified_function": "my.tax/rate",
        "functions": [
            {"qualified_name": "my.tax/rate", "source_transaction": 10},
            {"qualified_name": "my.tax/rate", "source_transaction": 20}],
        "consumer_eval": {"ok": True, "called": "my.tax/rate",
                          "defined": False},
        "repair_eval": {"ok": True, "qualified_name": "my.tax/rate"},
        "fresh_test": {"status": "pass"},
    },
    "child_recovery": {
        "failed_eval": {"status": "interrupted", "turn_status": "interrupted",
                        "child_id": "child-1"},
        "recovery": {"failed_child_id": "child-1",
                     "diagnostic_blob_hash": "sha256:abc"},
        "children": [{"child_id": "child-1", "replacement": False},
                     {"child_id": "child-2", "replacement": True}],
        "crashing_eval_count": 1,
        "later_eval": {"status": "done"},
        "sibling": {"status": "done"},
    },
    "pod_restart": {
        "before": {"pod_id": "pod-1", "database_id": "db-1",
                   "agent_id": "root"},
        "after": {"pod_id": "pod-2", "database_id": "db-1",
                  "agent_id": "root", "read_status": "ok", "read_value": 42},
        "expected_value": 42,
    },
}


def bad_snapshot(scenario: str) -> dict:
    snapshot = deepcopy(GOOD[scenario])
    if scenario == "namespace":
        snapshot["agents"].append(deepcopy(snapshot["agents"][0]))
    elif scenario == "reuse_repair":
        snapshot["functions"].append(
            {"qualified_name": "my.tax/rate-v2", "source_transaction": 21})
    elif scenario == "child_recovery":
        snapshot["crashing_eval_count"] = 2
    else:
        snapshot["after"]["pod_id"] = snapshot["before"]["pod_id"]
    return snapshot


@solver
def frozen_solver(scenario: str, outcome: str):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        state.metadata["database_snapshot"] = deepcopy(
            GOOD[scenario] if outcome == "good" else bad_snapshot(scenario))
        state.output.completion = outcome
        return state
    return solve


@task
def product_scenario(scenario: str = "namespace", outcome: str = "good"):
    if scenario not in SCENARIOS:
        raise ValueError(f"unknown scenario {scenario!r}")
    if outcome not in {"good", "bad"}:
        raise ValueError("outcome must be 'good' or 'bad'")
    sample = Sample(id=scenario, input=f"Prove {scenario}", target="correct",
                    metadata={"scenario": scenario})
    return Task(dataset=MemoryDataset([sample]),
                solver=frozen_solver(scenario, outcome),
                scorer=product_scenario_scorer(),
                epochs=Epochs(1, ["mean"]))
