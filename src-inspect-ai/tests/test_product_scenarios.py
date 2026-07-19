import copy
import json
from types import SimpleNamespace

import pytest
from inspect_ai import eval as inspect_eval

from seon_inspect.product_scenarios import (CHECKS, SCENARIOS,
                                            CHILD_RECOVERY_SOURCE,
                                            read_child_recovery_evidence,
                                            read_namespace_evidence,
                                            read_reuse_repair_evidence,
                                            query_execution_processes,
                                            query_product_evidence,
                                            run_product_scenario)
from seon_inspect.tasks.product_scenarios import GOOD, bad_snapshot, product_scenario
from seon_inspect.tasks import product_scenarios as product_tasks


@pytest.mark.parametrize("scenario", SCENARIOS)
def test_database_snapshot_checks_discriminate(scenario):
    assert CHECKS[scenario](copy.deepcopy(GOOD[scenario]))["ok"]
    result = CHECKS[scenario](bad_snapshot(scenario))
    assert not result["ok"]
    assert result["failures"]


@pytest.mark.parametrize("scenario,outcome,expected", [
    *[(scenario, "good", 1.0) for scenario in SCENARIOS],
    *[(scenario, "bad", 0.0) for scenario in SCENARIOS],
])
def test_native_task_uses_real_scorer(scenario, outcome, expected):
    log = inspect_eval(product_scenario(scenario=scenario, outcome=outcome),
                       model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(score for score in log.results.scores
                 if score.name == "product_scenario_scorer")
    assert score.metrics["accuracy"].value == pytest.approx(expected)


def test_scorer_fails_closed_without_database_snapshot():
    task = product_scenario(scenario="namespace", outcome="good")
    task.solver = []
    log = inspect_eval(task, model="mockllm/model", display="none",
                       log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(score for score in log.results.scores
                 if score.name == "product_scenario_scorer")
    assert score.metrics["accuracy"].value == 0.0


def test_live_driver_reuses_root_and_reads_database_after_work():
    calls = []

    def run(prompt, timeout, url, *, agent_id):
        calls.append((prompt, timeout, url, agent_id))
        return {"agent_id": agent_id, "reply": "done"}

    result = run_product_scenario(
        "namespace", "http://pod/agents/run",
        lambda scenario, agent_id: {"scenario": scenario, "agent": agent_id},
        run=run)
    assert [call[3] for call in calls] == ["root", "root"]
    assert result["database_snapshot"] == {
        "scenario": "namespace", "agent": "root"}


def test_restart_driver_changes_url_between_same_agent_runs():
    calls = []

    def run(prompt, timeout, url, *, agent_id):
        calls.append((url, agent_id))
        return {"agent_id": agent_id}

    run_product_scenario(
        "pod_restart", "http://old/agents/run", lambda *_: GOOD["pod_restart"],
        restart_pod=lambda: "http://new/agents/run", run=run)
    assert calls == [("http://old/agents/run", "root"),
                     ("http://new/agents/run", "root")]


def test_restart_driver_refuses_unowned_restart():
    with pytest.raises(RuntimeError, match="owned-target"):
        run_product_scenario(
            "pod_restart", "http://pod/agents/run", lambda *_: {},
            run=lambda *args, **kwargs: {})


def test_typed_product_evidence_retains_database_value():
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def read(self):
            return (b'{"seon.db/ok?":true,"seon.db/db":'
                    b'{"db_name":"proof","t":42,"commit_id":"c"},'
                    b'"seon.db/result":[["root"]]}')

    requests = []

    def opener(request):
        requests.append(request)
        return Response()

    result = query_product_evidence(
        "http://127.0.0.1:41000/agents/run",
        "[:find ?id :where [?e :seon.agent/id ?id]]", opener=opener)
    assert requests[0].full_url.endswith("/_seon/operator/product-evidence")
    assert result["database_value"]["t"] == 42
    assert result["database_snapshot"] == [["root"]]


def test_typed_product_evidence_requests_datahike_history():
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def read(self):
            return (b'{"seon.db/ok?":true,"seon.db/db":'
                    b'{"db_name":"proof","t":42,"history":true},'
                    b'"seon.db/result":[]}')

    requests = []

    result = query_product_evidence(
        "http://127.0.0.1:41000/agents/run",
        "[:find ?tx :where [_ :seon.fn/source _ ?tx]]",
        history=True, opener=lambda request: requests.append(request) or Response())
    payload = json.loads(requests[0].data.decode())
    assert payload["seon.db/history?"] is True
    assert result["database_value"]["history"] is True


def test_execution_process_reader_uses_the_parent_host_endpoint():
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def read(self):
            return (b'{"seon.execution.host/processes":['
                    b'{"seon.execution.host/pid":41001}]}')

    urls = []
    processes = query_execution_processes(
        "http://127.0.0.1:41000/agents/run",
        opener=lambda url: urls.append(url) or Response())
    assert urls == ["http://127.0.0.1:41000/_seon/operator/processes"]
    assert processes == [{"seon.execution.host/pid": 41001}]


def test_child_recovery_evidence_joins_database_and_parent_host():
    calls = []

    def product_reader(url, query, args, *, history):
        calls.append((url, args, history))
        return {
            "database_value": {"t": 42, "history": True},
            "database_snapshot": [["eval-1", "turn-1", "recovery-1",
                                   41001, "a" * 64, "blob-hash",
                                   "eval-2", "sibling-eval"]],
        }

    processes = [{"seon.execution/agent-id": "root",
                  "seon.execution.host/pid": 41002,
                  "seon.execution.host/artifact-digest": "a" * 64,
                  "seon.execution.host/ready?": True}]
    snapshot = read_child_recovery_evidence(
        "http://pod/agents/run", "root", product_reader=product_reader,
        process_reader=lambda _url: processes)
    assert calls == [("http://pod/agents/run",
                      ["root", CHILD_RECOVERY_SOURCE], True)]
    assert snapshot["recovery"]["pid"] == 41001
    assert snapshot["processes"] == processes
    assert CHECKS["child_recovery"](snapshot)["ok"]


def test_namespace_evidence_derives_first_eval_and_two_root_messages():
    rows = [
        ["tax-agent", ":my.inspect.ntest", "message-2", "eval-2",
         ":my.inspect.ntest", 22],
        ["tax-agent", ":my.inspect.ntest", "message-1", "eval-1",
         ":my.inspect.ntest", 20],
        ["tax-agent", ":my.inspect.ntest", "message-2", "eval-1",
         ":my.inspect.ntest", 20],
    ]
    snapshot = read_namespace_evidence(
        "http://pod/agents/run", "my.inspect.ntest",
        product_reader=lambda *_: {"database_value": {"t": 42},
                                   "database_snapshot": rows})
    assert snapshot["agents"][0]["first_eval_namespace"] == "my.inspect.ntest"
    assert len(snapshot["messages"]) == 2
    assert CHECKS["namespace"](snapshot)["ok"]


def test_reuse_repair_evidence_uses_history_and_peer_authors():
    calls = []
    rows = [
        ["(defn rate [amount] (* amount 2))", 10, "producer",
         "consumer-eval", 15, "consumer", "repair-eval", 20, "repair",
         "my.inspect.repair.ntest-test/rate-test", "2026-07-19T12:00:00Z",
         "my.inspect.repair.ntest/rate"],
        ["(defn rate [amount] (* amount 3))", 20, "repair",
         "consumer-eval", 15, "consumer", "repair-eval", 20, "repair",
         "my.inspect.repair.ntest-test/rate-test",
         "2026-07-19T12:00:00Z",
         "my.inspect.repair.ntest/rate"],
    ]

    def reader(url, query, args, *, history):
        calls.append((url, args, history))
        return {"database_value": {"t": 42, "history": True},
                "database_snapshot": rows}

    snapshot = read_reuse_repair_evidence(
        "http://pod/agents/run", namespace="my.inspect.repair.ntest",
        function_name="rate",
        consumer_source="(my.inspect.repair.ntest/rate 21)",
        repair_source="(defn rate [amount] (* amount 3))",
        test_namespace="my.inspect.repair.ntest-test",
        test_name="rate-test", product_reader=reader)
    assert calls[0][2] is True
    assert [row["source_transaction"] for row in snapshot["functions"]] == [10, 20]
    assert snapshot["consumer_eval"]["author_agent_id"] == "consumer"
    assert snapshot["repair_eval"]["author_agent_id"] == "repair"
    assert CHECKS["reuse_repair"](snapshot)["ok"]


def test_native_live_recovery_task_owns_and_releases_its_branch(monkeypatch):
    from seon_inspect import cluster, solver as pod_solver

    events = []
    lease = SimpleNamespace(name="inspect-recovery-proof",
                            cluster_url="http://pod/agents/run")
    monkeypatch.setattr(cluster, "acquire_branch_lease",
                        lambda name: events.append(("open", name)) or lease)
    monkeypatch.setattr(cluster, "release_branch_lease",
                        lambda value: events.append(("close", value.name)))
    monkeypatch.setattr(
        pod_solver, "pod_run",
        lambda prompt, timeout, url, *, agent_id:
        events.append(("run", prompt, timeout, url, agent_id))
        or {"reply": "done", "agent_id": agent_id})
    monkeypatch.setattr(product_tasks, "read_child_recovery_evidence",
                        lambda url, agent_id: copy.deepcopy(GOOD["child_recovery"]))
    monkeypatch.setattr(pod_solver, "require_scorable_pod_state",
                        lambda state: state)

    log = inspect_eval(
        product_scenario(scenario="child_recovery", live=True,
                         _admission={"tree_sha256": "a" * 64}),
        model="mockllm/model", display="none", log_level="warning")[0]
    assert log.status == "success", log.error
    score = next(score for score in log.results.scores
                 if score.name == "product_scenario_scorer")
    assert score.metrics["accuracy"].value == 1.0
    assert events[0][0] == "open"
    assert [event[0] for event in events].count("run") == 2
    assert events[-1] == ("close", "inspect-recovery-proof")


def test_native_live_namespace_task_uses_one_resident_snapshot(monkeypatch):
    from seon_inspect import cluster, solver as pod_solver

    lease = SimpleNamespace(name="inspect-namespace-abcdef",
                            cluster_url="http://pod/agents/run")
    monkeypatch.setattr(cluster, "acquire_branch_lease", lambda _name: lease)
    monkeypatch.setattr(cluster, "release_branch_lease", lambda _lease: None)
    monkeypatch.setattr(
        pod_solver, "pod_run",
        lambda prompt, timeout, url, *, agent_id:
        {"reply": "done", "agent_id": agent_id})
    monkeypatch.setattr(pod_solver, "require_scorable_pod_state",
                        lambda state: state)
    observed = []
    monkeypatch.setattr(
        product_tasks, "read_namespace_evidence",
        lambda url, namespace:
        observed.append((url, namespace)) or copy.deepcopy(GOOD["namespace"]))

    log = inspect_eval(
        product_scenario(scenario="namespace", live=True,
                         _admission={"tree_sha256": "a" * 64}),
        model="mockllm/model", display="none", log_level="warning")[0]
    assert log.status == "success", log.error
    assert observed == [("http://pod/agents/run", "my.inspect.nabcdef")]


def test_native_live_reuse_repair_task_reads_history(monkeypatch):
    from seon_inspect import cluster, solver as pod_solver

    lease = SimpleNamespace(name="inspect-reuse_repair-abcdef",
                            cluster_url="http://pod/agents/run")
    monkeypatch.setattr(cluster, "acquire_branch_lease", lambda _name: lease)
    monkeypatch.setattr(cluster, "release_branch_lease", lambda _lease: None)
    monkeypatch.setattr(
        pod_solver, "pod_run",
        lambda prompt, timeout, url, *, agent_id:
        {"reply": "done", "agent_id": agent_id})
    monkeypatch.setattr(pod_solver, "require_scorable_pod_state",
                        lambda state: state)
    observed = []
    monkeypatch.setattr(
        product_tasks, "read_reuse_repair_evidence",
        lambda url, **kwargs:
        observed.append((url, kwargs)) or copy.deepcopy(GOOD["reuse_repair"]))

    log = inspect_eval(
        product_scenario(scenario="reuse_repair", live=True,
                         _admission={"tree_sha256": "a" * 64}),
        model="mockllm/model", display="none", log_level="warning")[0]
    assert log.status == "success", log.error
    assert observed[0][0] == "http://pod/agents/run"
    assert observed[0][1]["namespace"] == "my.inspect.repair.nabcdef"


def test_live_product_failure_is_not_masked_by_release_failure(monkeypatch):
    from seon_inspect import cluster, solver as pod_solver

    lease = SimpleNamespace(name="inspect-namespace-failure",
                            cluster_url="http://pod/agents/run")
    monkeypatch.setattr(cluster, "acquire_branch_lease", lambda _name: lease)
    monkeypatch.setattr(
        cluster, "release_branch_lease",
        lambda _lease: (_ for _ in ()).throw(RuntimeError("release failed")))
    monkeypatch.setattr(
        pod_solver, "pod_run",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("product failed")))

    log = inspect_eval(
        product_scenario(scenario="namespace", live=True,
                         _admission={"tree_sha256": "a" * 64}),
        model="mockllm/model", display="none", log_level="warning")[0]
    assert log.status == "error"
    assert "product failed" in str(log.error)
    assert "release failed" in str(log.error)
