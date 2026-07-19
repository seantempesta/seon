"""Database-derived graduation checks for collaboration and recovery.

The checks consume one ordinary snapshot read after the scenario. They do not
score transcript claims. Live execution still enters through ``pod_run``; the
pending owned-target lease must supply restart plus final database read-back.
"""

from __future__ import annotations

import json
from typing import Any
import urllib.error
import urllib.request

from inspect_ai.scorer import (CORRECT, INCORRECT, Score, Scorer, Target,
                               accuracy, scorer)
from inspect_ai.solver import TaskState


SCENARIOS = ("namespace", "reuse_repair", "child_recovery", "pod_restart")

CHILD_RECOVERY_SOURCE = "(js/process.exit 17)"
NAMESPACE_QUERY = """[:find ?agent-id ?namespace ?message-id ?eval-id ?eval-ns
                              ?eval-tx
 :where
 [?namespace-entity :seon.ns/name ?namespace]
 [?agent :seon.agent/namespace ?namespace-entity]
 [?agent :seon.agent/id ?agent-id]
 (not [?agent :seon.agent/terminated-at _])
 [?message :seon.agent.message/to ?agent]
 [?message :seon.agent.message/id ?message-id]
 [?message :seon.agent.message/from ?from]
 [?from :seon.agent/id "root"]
 [?eval :seon.eval/agent ?agent]
 [?eval :seon.eval/id ?eval-id]
 [?eval :seon.eval/ns ?eval-ns ?eval-tx]]"""
CHILD_RECOVERY_QUERY = """[:find ?eval-id ?turn-id ?recovery-id ?pid ?digest
                                  ?blob-hash ?later-id ?sibling-id
 :in $ ?agent-id ?source
 :where
 [?agent :seon.agent/id ?agent-id _ true]
 [?failed :seon.eval/agent ?agent _ true]
 [?failed :seon.eval/id ?eval-id _ true]
 [?failed :seon.eval/source ?source _ true]
 [?failed :seon.eval/status :interrupted _ true]
 [?turn :seon.agent.turn/evals ?failed _ true]
 [?turn :seon.agent.turn/id ?turn-id _ true]
 [?turn :seon.agent.turn/status :interrupted _ true]
 [?recovery :seon.runtime.recovery/id ?recovery-id ?recovery-tx true]
 [?recovery :seon.runtime.recovery/eval ?failed ?recovery-tx true]
 [?recovery :seon.runtime.recovery/pid ?pid ?recovery-tx true]
 [?recovery :seon.runtime.recovery/execution-digest ?digest ?recovery-tx true]
 [?recovery :seon.runtime.recovery/diagnostic-blob ?blob ?recovery-tx true]
 [?blob :my.blob/hash ?blob-hash _ true]
 [?later :seon.eval/agent ?agent ?later-tx true]
 [?later :seon.eval/id ?later-id ?later-tx true]
 [?later :seon.eval/status :done ?later-tx true]
 [(> ?later-tx ?recovery-tx)]
 [?sibling-agent :seon.agent/id ?sibling-agent-id _ true]
 [(not= ?sibling-agent ?agent)]
 [?sibling :seon.eval/agent ?sibling-agent _ true]
 [?sibling :seon.eval/id ?sibling-id _ true]
 [?sibling :seon.eval/status :done _ true]
 (not-join [?agent ?source ?failed]
   [?duplicate :seon.eval/agent ?agent _ true]
   [?duplicate :seon.eval/source ?source _ true]
   [(not= ?duplicate ?failed)])]"""
REUSE_REPAIR_QUERY = """[:find ?source ?source-tx ?author-id
                                 ?consumer-eval ?consumer-tx ?consumer-author-id
                                 ?repair-eval ?repair-tx ?repair-author-id
                                 ?test-sym ?passed-at ?namespace-sym
 :in $ ?qualified ?consumer-source ?repair-source ?test-qualified ?namespace
 :where
 [?function :seon.fn/sym ?qualified _ true]
 [?function :seon.fn/source ?source ?source-tx true]
 [?source-tx :seon.db/user ?author]
 [?author :seon.agent/id ?author-id]
 [?consumer :seon.eval/source ?consumer-source ?consumer-tx true]
 [?consumer :seon.eval/id ?consumer-eval _ true]
 [?consumer :seon.eval/ok? true _ true]
 [?consumer-tx :seon.db/user ?consumer-author]
 [?consumer-author :seon.agent/id ?consumer-author-id]
 [?repair :seon.eval/source ?repair-source ?repair-tx true]
 [?repair :seon.eval/id ?repair-eval _ true]
 [?repair :seon.eval/ok? true _ true]
 [?repair-tx :seon.db/user ?repair-author]
 [?repair-author :seon.agent/id ?repair-author-id]
 [?test :seon.test/sym ?test-qualified _ true]
 [?test :seon.test/sym ?test-sym _ true]
 [?test :seon.test/last-passed-at ?passed-at _ true]
 [?namespace-entity :seon.ns/name ?namespace _ true]
 [?namespace-function :seon.fn/ns ?namespace-entity _ true]
 [?namespace-function :seon.fn/sym ?namespace-sym _ true]]"""


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
    test_qualified = snapshot.get("fresh_test", {}).get("qualified_name")
    parallel = [name for name in snapshot.get("namespace_functions", [])
                if name not in {qualified, test_qualified}
                and qualified and name.startswith(qualified)]
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
    processes = snapshot.get("processes", [])
    replacements = [
        process for process in processes
        if process.get("seon.execution/agent-id") == failed.get("agent_id")
        and process.get("seon.execution.host/pid") != recovery.get("pid")]
    checks = {
        "interrupted_eval": (failed.get("status") == "interrupted"
                             and failed.get("turn_status") == "interrupted"),
        "diagnostic_blob": bool(recovery.get("diagnostic_blob_hash")),
        "failed_process_recorded": (recovery.get("eval_id") == failed.get("id")
                                    and isinstance(recovery.get("pid"), int)),
        "one_replacement": (
            len(replacements) == 1
            and replacements[0].get("seon.execution.host/ready?") is True
            and replacements[0].get("seon.execution.host/artifact-digest")
            == recovery.get("execution_digest")),
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
        "From root, send namespace my.inspect.recovery-sibling a small task "
        "that evaluates (+ 1 1) and completes. Then perform the maintained "
        "deterministic execution-child failure probe by evaluating exactly "
        "(js/process.exit 17). Do not evaluate that form a second time.",
        "After the recovery notice, evaluate (+ 20 22), confirm the sibling "
        "completed, and finish without replaying the crashing form."),
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
    try:
        with opener(request) as response:
            result = json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        try:
            result = json.loads(error.read().decode())
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise RuntimeError(
                f"product evidence HTTP {error.code}: {error.reason}") from error
        raise RuntimeError(
            result.get("seon.db/error",
                       f"product evidence HTTP {error.code}")) from error
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


def read_child_recovery_evidence(cluster_url: str, agent_id: str, *,
                                 product_reader=query_product_evidence,
                                 process_reader=query_execution_processes
                                 ) -> dict:
    """Join durable crash recovery with one demanded host snapshot."""
    evidence = product_reader(
        cluster_url, CHILD_RECOVERY_QUERY,
        [agent_id, CHILD_RECOVERY_SOURCE], history=True)
    rows = evidence.get("database_snapshot")
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("child recovery query returned no complete evidence")
    row = rows[0]
    if not isinstance(row, list) or len(row) != 8:
        raise RuntimeError("child recovery query returned an invalid row")
    stable = row[:6]
    if any(candidate[:6] != stable for candidate in rows
           if isinstance(candidate, list) and len(candidate) == 8):
        raise RuntimeError("child recovery query returned several recoveries")
    (eval_id, _turn_id, _recovery_id, pid, digest, blob_hash,
     later_id, sibling_id) = row
    return {
        "database_value": evidence.get("database_value"),
        "failed_eval": {"id": eval_id, "agent_id": agent_id,
                        "status": "interrupted",
                        "turn_status": "interrupted"},
        "recovery": {"eval_id": eval_id, "pid": pid,
                     "execution_digest": digest,
                     "diagnostic_blob_hash": blob_hash},
        "processes": process_reader(cluster_url),
        "crashing_eval_count": 1,
        "later_eval": {"id": later_id, "status": "done"},
        "sibling": {"id": sibling_id, "status": "done"},
    }


def _name(value: Any) -> str:
    return value[1:] if isinstance(value, str) and value.startswith(":") else value


def read_namespace_evidence(cluster_url: str, target_namespace: str, *,
                            product_reader=query_product_evidence) -> dict:
    """Project one namespace resident from current database facts."""
    evidence = product_reader(cluster_url, NAMESPACE_QUERY)
    rows = evidence.get("database_snapshot")
    if not isinstance(rows, list):
        raise RuntimeError("namespace evidence query returned no relation")
    matching = [row for row in rows if isinstance(row, list) and len(row) == 6
                and _name(row[1]) == target_namespace]
    if not matching:
        raise RuntimeError("namespace evidence query returned no target resident")
    agent_ids = {row[0] for row in matching}
    if len(agent_ids) != 1:
        raise RuntimeError("namespace evidence query returned several residents")
    agent_id = next(iter(agent_ids))
    messages = sorted({row[2] for row in matching})
    evals = sorted({(row[5], row[3], _name(row[4])) for row in matching})
    return {
        "database_value": evidence.get("database_value"),
        "target_namespace": target_namespace,
        "agents": [{"agent_id": agent_id, "namespace": target_namespace,
                    "terminated": False,
                    "first_eval_namespace": evals[0][2]}],
        "messages": [{"message_id": message_id,
                      "from_agent_id": "root", "to_agent_id": agent_id}
                     for message_id in messages],
    }


def read_reuse_repair_evidence(cluster_url: str, *, namespace: str,
                               function_name: str, consumer_source: str,
                               repair_source: str, test_namespace: str,
                               test_name: str,
                               product_reader=query_product_evidence) -> dict:
    """Project cross-agent function repair from Datahike history."""
    qualified = f"{namespace}/{function_name}"
    test_qualified = f"{test_namespace}/{test_name}"
    evidence = product_reader(
        cluster_url, REUSE_REPAIR_QUERY,
        [qualified, consumer_source, repair_source, test_qualified, namespace],
        history=True)
    rows = evidence.get("database_snapshot")
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("reuse and repair query returned no complete evidence")
    valid = [row for row in rows if isinstance(row, list) and len(row) == 12]
    if len(valid) != len(rows):
        raise RuntimeError("reuse and repair query returned an invalid row")

    versions = sorted(
        {(row[0], row[1], row[2]) for row in valid}, key=lambda row: row[1])
    consumer = {(row[3], row[4], row[5]) for row in valid}
    repair = {(row[6], row[7], row[8]) for row in valid}
    tests = {(row[9], row[10]) for row in valid}
    namespace_symbols = {_name(row[11]) for row in valid}
    if len(consumer) != 1 or len(repair) != 1 or not tests:
        raise RuntimeError("reuse and repair query returned ambiguous evidence")
    consumer_eval, _consumer_tx, consumer_author = next(iter(consumer))
    repair_eval, _repair_tx, repair_author = next(iter(repair))
    if consumer_author == repair_author:
        raise RuntimeError("reuse and repair were not performed by peer agents")
    return {
        "database_value": evidence.get("database_value"),
        "qualified_function": qualified,
        "functions": [
            {"qualified_name": qualified, "source": source,
             "source_transaction": source_tx, "author_agent_id": author_id}
            for source, source_tx, author_id in versions],
        "namespace_functions": sorted(namespace_symbols),
        "consumer_eval": {"id": consumer_eval, "ok": True,
                          "called": qualified, "defined": False,
                          "author_agent_id": consumer_author},
        "repair_eval": {"id": repair_eval, "ok": True,
                        "qualified_name": qualified,
                        "author_agent_id": repair_author},
        "fresh_test": {"qualified_name": test_qualified, "status": "pass"},
    }


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
