"""Database-derived graduation checks for collaboration and recovery.

The checks consume one ordinary snapshot read after the scenario. They do not
score transcript claims. Live execution still enters through ``pod_run``; the
pending owned-target lease must supply restart plus final database read-back.
"""

from __future__ import annotations

import json
from typing import Any
import urllib.request

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer, Target,
                               accuracy, scorer)
from inspect_ai.solver import TaskState


SCENARIOS = ("namespace", "reuse_repair", "child_recovery", "pod_restart")


def _exactly_one(rows: list[dict], **attrs: Any) -> dict | None:
    found = [row for row in rows
             if all(row.get(key) == value for key, value in attrs.items())]
    return found[0] if len(found) == 1 else None


def check_namespace(snapshot: dict) -> dict:
    namespace = snapshot.get("target_namespace")
    resident = _exactly_one(snapshot.get("agents", []),
                            namespace=namespace, terminated=False)
    messages = snapshot.get("messages", [])
    addressed = ([m for m in messages if resident
                  and m.get("to_agent_id") == resident.get("agent_id")])
    checks = {
        "one_resident": resident is not None,
        "stable_agent": (len(addressed) == 2
                         and len({m.get("to_agent_id") for m in addressed}) == 1),
        "explicit_routing": (len(addressed) == 2
                             and all(m.get("from_agent_id") for m in addressed)),
        "starts_in_namespace": bool(resident and
                                    resident.get("first_eval_namespace") == namespace),
    }
    return _result(checks)


def check_reuse_repair(snapshot: dict) -> dict:
    qualified = snapshot.get("qualified_function")
    versions = sorted(
        [row for row in snapshot.get("functions", [])
         if row.get("qualified_name") == qualified],
        key=lambda row: row.get("source_transaction", -1))
    parallel = [row for row in snapshot.get("functions", [])
                if row.get("qualified_name") != qualified
                and qualified and row.get("qualified_name", "").startswith(qualified)]
    consumer = snapshot.get("consumer_eval", {})
    repair = snapshot.get("repair_eval", {})
    checks = {
        "shared_function": len(versions) >= 2,
        "consumer_called_qualified": (consumer.get("ok") is True
                                      and consumer.get("called") == qualified
                                      and consumer.get("defined") is False),
        "repaired_in_place": (repair.get("ok") is True
                              and repair.get("qualified_name") == qualified
                              and len(versions) >= 2
                              and versions[-1].get("source_transaction", -1)
                              > versions[0].get("source_transaction", -1)),
        "no_parallel_function": not parallel,
        "fresh_test_passed": snapshot.get("fresh_test", {}).get("status") == "pass",
    }
    return _result(checks)


def check_child_recovery(snapshot: dict) -> dict:
    failed = snapshot.get("failed_eval", {})
    recovery = snapshot.get("recovery", {})
    children = snapshot.get("children", [])
    checks = {
        "interrupted_eval": (failed.get("status") == "interrupted"
                             and failed.get("turn_status") == "interrupted"),
        "diagnostic_blob": bool(recovery.get("diagnostic_blob_hash")),
        "failed_child_recorded": (recovery.get("failed_child_id")
                                  == failed.get("child_id")),
        "one_replacement": (len(children) == 2
                            and sum(c.get("replacement") is True
                                    for c in children) == 1),
        "crashing_eval_not_replayed": snapshot.get("crashing_eval_count") == 1,
        "later_success": snapshot.get("later_eval", {}).get("status") == "done",
        "sibling_uninterrupted": snapshot.get("sibling", {}).get("status") == "done",
    }
    return _result(checks)


def check_pod_restart(snapshot: dict) -> dict:
    before = snapshot.get("before", {})
    after = snapshot.get("after", {})
    checks = {
        "pod_replaced": (bool(before.get("pod_id"))
                         and bool(after.get("pod_id"))
                         and before.get("pod_id") != after.get("pod_id")),
        "same_database": (before.get("database_id")
                          and before.get("database_id") == after.get("database_id")),
        "same_agent": (before.get("agent_id")
                       and before.get("agent_id") == after.get("agent_id")),
        "later_database_read": (after.get("read_status") == "ok"
                                and after.get("read_value")
                                == snapshot.get("expected_value")),
    }
    return _result(checks)


def _result(checks: dict[str, bool]) -> dict:
    failures = [name for name, passed in checks.items() if not passed]
    return {"ok": not failures, "checks": checks, "failures": failures}


CHECKS = {
    "namespace": check_namespace,
    "reuse_repair": check_reuse_repair,
    "child_recovery": check_child_recovery,
    "pod_restart": check_pod_restart,
}


PHASES = {
    "namespace": (
        "From root, start or reuse the resident for namespace my.taxes and "
        "send it the requested work.",
        "Send a second message to namespace my.taxes; reuse its resident."),
    "reuse_repair": (
        "Have one agent publish my.tax/rate and another call that qualified "
        "function without redefining it.",
        "Have a peer repair my.tax/rate in place and run its test in a fresh child."),
    "child_recovery": (
        "Perform the maintained deterministic execution-child failure probe.",
        "After recovery, perform later work and verify a sibling was uninterrupted."),
    "pod_restart": (
        "Commit the requested database value before the pod restart.",
        "After the pod restart, read the committed value with the same agent."),
}


def run_product_scenario(scenario: str, cluster_url: str, read_database,
                         *, restart_pod=None, agent_id: str = "root",
                         run=None) -> dict:
    """Drive real work through ``pod_run`` and read one final database value.

    Restart ownership and database read-back are injected boundaries because
    the static pod HTTP door neither owns another process nor exposes arbitrary
    cross-agent database pulls. The owned-target lease supplies both live.
    """
    if scenario not in SCENARIOS:
        raise ValueError(f"unknown scenario {scenario!r}")
    if run is None:
        from seon_inspect.solver import pod_run
        run = pod_run
    first = run(PHASES[scenario][0], None, cluster_url, agent_id=agent_id)
    if scenario == "pod_restart":
        if restart_pod is None:
            raise RuntimeError("pod_restart requires the owned-target restart operation")
        cluster_url = restart_pod()
    second = run(PHASES[scenario][1], None, cluster_url, agent_id=agent_id)
    return {"runs": [first, second],
            "database_snapshot": read_database(scenario, agent_id)}


def query_product_evidence(cluster_url: str, query: str,
                           args: list | None = None,
                           *, history: bool = False,
                           opener=urllib.request.urlopen) -> dict:
    """Read one immutable database value through the typed pod endpoint."""
    endpoint = cluster_url.removesuffix("/agents/run") + \
        "/_seon/operator/product-evidence"
    payload = {"seon.db/query": query, "seon.db/args": args or []}
    if history:
        payload["seon.db/history?"] = True
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        endpoint, data=body, headers={"Content-Type": "application/json"})
    with opener(request) as response:
        result = json.loads(response.read().decode())
    if result.get("seon.db/ok?") is not True:
        raise RuntimeError(result.get("seon.db/error", "database read failed"))
    database = result.get("seon.db/db")
    if not isinstance(database, dict) or not isinstance(database.get("t"), int):
        raise RuntimeError("product evidence omitted its immutable database value")
    return {"database_value": database,
            "database_snapshot": result.get("seon.db/result")}


def query_execution_processes(cluster_url: str, *,
                              opener=urllib.request.urlopen) -> list[dict]:
    """Read one demanded parent-host execution-process snapshot."""
    endpoint = cluster_url.removesuffix("/agents/run") + \
        "/_seon/operator/processes"
    with opener(endpoint) as response:
        result = json.loads(response.read().decode())
    processes = result.get("seon.execution.host/processes")
    if not isinstance(processes, list) or not all(
            isinstance(process, dict) for process in processes):
        raise RuntimeError("operator process response omitted its process vector")
    return processes


@scorer(metrics=[accuracy()])
def product_scenario_scorer() -> Scorer:
    async def score(state: TaskState, target: Target) -> Score:
        scenario = state.metadata.get("scenario")
        snapshot = state.metadata.get("database_snapshot")
        if scenario not in CHECKS or not isinstance(snapshot, dict):
            result = {"ok": False, "failures": ["database_snapshot"]}
        else:
            result = CHECKS[scenario](snapshot)
        return Score(value=CORRECT if result["ok"] else INCORRECT,
                     explanation=json.dumps(result), metadata=result)
    return score
