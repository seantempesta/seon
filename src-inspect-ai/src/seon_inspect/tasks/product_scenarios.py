"""Native offline discrimination for Seon's integrated product scenarios."""

from __future__ import annotations

from copy import deepcopy

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect import source_admission
from seon_inspect.product_scenarios import (PHASES, SCENARIOS,
                                            product_scenario_scorer,
                                            read_child_recovery_evidence)


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
        "failed_eval": {"id": "eval-1", "agent_id": "agent-a",
                        "status": "interrupted",
                        "turn_status": "interrupted"},
        "recovery": {"eval_id": "eval-1", "pid": 41001,
                     "execution_digest": "a" * 64,
                     "diagnostic_blob_hash": "sha256:abc"},
        "processes": [{"seon.execution/agent-id": "agent-a",
                       "seon.execution.host/pid": 41002,
                       "seon.execution.host/artifact-digest": "a" * 64,
                       "seon.execution.host/ready?": True}],
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
        snapshot["processes"][0]["seon.execution.host/artifact-digest"] = "b" * 64
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


@solver
def live_child_recovery_solver(timeout_s: int = 300):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        def drive() -> dict:
            from seon_inspect.cluster import (acquire_branch_lease,
                                              bench_cluster_name,
                                              release_branch_lease)
            from seon_inspect.solver import pod_run

            lease = acquire_branch_lease(bench_cluster_name("inspect-recovery"))
            try:
                runs = [pod_run(prompt, timeout_s * 1000, lease.cluster_url,
                                agent_id="root")
                        for prompt in PHASES["child_recovery"]]
                return {"runs": runs,
                        "database_snapshot": read_child_recovery_evidence(
                            lease.cluster_url, "root")}
            finally:
                release_branch_lease(lease)

        result = await anyio.to_thread.run_sync(drive)
        from seon_inspect.solver import (_record_result,
                                         require_scorable_pod_state)
        for run in result["runs"]:
            require_scorable_pod_state(_record_result(state, run))
        state.metadata["database_snapshot"] = result["database_snapshot"]
        state.metadata["pod_runs"] = result["runs"]
        state.output.completion = result["runs"][-1].get("reply", "")
        return state

    return solve


def _identity(scenario: str) -> dict[str, str]:
    return {"name": f"product_scenario:{scenario}",
            "module": "seon_inspect.tasks.product_scenarios",
            "attribute": "product_scenario",
            "kind": "seon-native-product"}


@task
def product_scenario(scenario: str = "namespace", outcome: str = "good",
                     live: bool = False, timeout_s: int = 300,
                     _admission: dict | None = None):
    if scenario not in SCENARIOS:
        raise ValueError(f"unknown scenario {scenario!r}")
    if outcome not in {"good", "bad"}:
        raise ValueError("outcome must be 'good' or 'bad'")
    if live and scenario != "child_recovery":
        raise ValueError("the first live product row is child_recovery")
    admission = (_admission or source_admission.verify_sources(_identity(scenario))
                 if live else None)
    sample = Sample(id=scenario, input=f"Prove {scenario}", target="correct",
                    metadata={"scenario": scenario,
                              **({"seon_source_admission": admission}
                                 if admission else {})})
    return Task(dataset=MemoryDataset([sample]),
                solver=(live_child_recovery_solver(timeout_s)
                        if live else frozen_solver(scenario, outcome)),
                scorer=product_scenario_scorer(),
                epochs=Epochs(1, ["mean"]),
                metadata=({"seon_source_admission": admission}
                          if admission else None))
