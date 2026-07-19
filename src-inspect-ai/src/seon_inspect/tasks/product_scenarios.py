"""Native offline discrimination for Seon's integrated product scenarios."""

from __future__ import annotations

from copy import deepcopy

from inspect_ai import Epochs, Task, task
from inspect_ai.dataset import MemoryDataset, Sample
from inspect_ai.solver import Generate, TaskState, solver

from seon_inspect import source_admission
from seon_inspect.product_scenarios import (PHASES, SCENARIOS,
                                            product_scenario_scorer,
                                            read_child_recovery_evidence,
                                            read_namespace_evidence,
                                            read_reuse_repair_evidence)


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
        "namespace_functions": ["my.tax/rate"],
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
        snapshot["namespace_functions"].append("my.tax/rate-v2")
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
def live_product_solver(scenario: str, timeout_s: int = 300):
    async def solve(state: TaskState, generate: Generate) -> TaskState:
        import anyio

        def drive() -> dict:
            from seon_inspect.cluster import (acquire_branch_lease,
                                              bench_cluster_name,
                                              release_branch_lease)
            from seon_inspect.solver import pod_run

            branch_prefix = f"inspect-{scenario.replace('_', '-')}"
            lease = acquire_branch_lease(bench_cluster_name(branch_prefix))
            product_failure = None
            try:
                if scenario == "namespace":
                    target_namespace = f"my.inspect.n{lease.name.rsplit('-', 1)[-1]}"
                    prompts = (
                        f"From root, send namespace {target_namespace} a task "
                        "to evaluate (+ 1 1) and report the result.",
                        f"Send a second message to namespace {target_namespace} "
                        "asking it to evaluate (+ 20 22). Reuse its resident.")
                elif scenario == "reuse_repair":
                    suffix = lease.name.rsplit('-', 1)[-1]
                    target_namespace = f"my.inspect.repair.n{suffix}"
                    consumer_namespace = f"my.inspect.consumer.n{suffix}"
                    test_namespace = f"{target_namespace}-test"
                    function_name = "rate"
                    test_name = "validates-rate"
                    initial_source = "(defn rate [amount] (* amount 2))"
                    consumer_source = f"({target_namespace}/rate 21)"
                    repair_source = "(defn rate [amount] (* amount 3))"
                    test_ns_source = (
                        f"(ns {test_namespace} "
                        f"(:require [cljs.test :refer [deftest is]] "
                        f"[{target_namespace}]))")
                    test_source = (
                        "(deftest validates-rate "
                        f"(is (= 63 ({target_namespace}/rate 21))))")
                    run_test_source = (
                        "(seon.test.runner/run! "
                        f"{{:seon.test.runner/vars '[{test_namespace}/{test_name}] "
                        ":seon.test.runner/record? true})")
                    producer_task = (
                        f"Evaluate exactly `{initial_source}`, then "
                        "`(complete \"published\")`.")
                    consumer_task = (
                        f"Evaluate exactly `{consumer_source}` without defining "
                        "that function, then `(complete \"reused\")`.")
                    repair_task = (
                        f"Evaluate exactly `{repair_source}`, then exactly "
                        f"`{test_ns_source}`, then exactly `{test_source}`, then "
                        f"exactly `{run_test_source}`, then "
                        "`(complete \"repaired and tested\")`. Do not create a "
                        "suffixed or replacement function name.")
                    prompts = (
                        "Use one `agent/delegate!` form at a time and read each "
                        "child's real completion before continuing. First delegate "
                        f"namespace `{target_namespace}` this task: {producer_task} "
                        "Then delegate "
                        f"namespace `{consumer_namespace}` this separate task: "
                        f"{consumer_task} After both reports arrive, "
                        "`(complete \"producer and consumer finished\")`.",
                        "Use one `agent/delegate!` form to delegate a fresh peer in "
                        f"namespace `{target_namespace}` this task: {repair_task} "
                        "After its real completion arrives, "
                        "`(complete \"repair finished\")`.")
                else:
                    target_namespace = None
                    prompts = PHASES[scenario]
                runs = [pod_run(prompt, timeout_s * 1000, lease.cluster_url,
                                agent_id="root")
                        for prompt in prompts]
                if scenario == "namespace":
                    snapshot = read_namespace_evidence(
                        lease.cluster_url, target_namespace)
                elif scenario == "reuse_repair":
                    try:
                        snapshot = read_reuse_repair_evidence(
                            lease.cluster_url, namespace=target_namespace,
                            function_name=function_name,
                            consumer_source=consumer_source,
                            repair_source=repair_source,
                            test_namespace=test_namespace, test_name=test_name)
                    except BaseException as evidence_failure:
                        evidence_failure.add_note(
                            "pod phases: " + repr([
                                {key: run.get(key) for key in
                                 ("agent_id", "turns", "evals", "timed_out",
                                  "closed_reason", "reply")}
                                for run in runs]))
                        raise
                else:
                    snapshot = read_child_recovery_evidence(
                        lease.cluster_url, "root")
                return {"runs": runs,
                        "database_snapshot": snapshot}
            except BaseException as exception:
                product_failure = exception
                raise
            finally:
                try:
                    release_branch_lease(lease)
                except BaseException as cleanup_failure:
                    if product_failure is None:
                        raise
                    product_failure.add_note(
                        f"branch release also failed: {cleanup_failure}")

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
    if live and scenario not in {"namespace", "reuse_repair", "child_recovery"}:
        raise ValueError(
            "live product rows are namespace, reuse_repair, and child_recovery")
    admission = (_admission or source_admission.verify_sources(_identity(scenario))
                 if live else None)
    sample = Sample(id=scenario, input=f"Prove {scenario}", target="correct",
                    metadata={"scenario": scenario,
                              **({"seon_source_admission": admission}
                                 if admission else {})})
    return Task(dataset=MemoryDataset([sample]),
                solver=(live_product_solver(scenario, timeout_s)
                        if live else frozen_solver(scenario, outcome)),
                scorer=product_scenario_scorer(),
                epochs=Epochs(1, ["mean"]),
                metadata=({"seon_source_admission": admission}
                          if admission else None))
