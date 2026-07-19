"""Offline tests for the /agents/run bridge (local HTTP fakes, no pod)."""

import contextlib
import http.server
import json
import threading
from types import SimpleNamespace

import pytest
from inspect_ai import Task, eval as inspect_eval
from inspect_ai.dataset import Sample
from inspect_ai.scorer import match

from seon_inspect import solver as solver_module

from seon_inspect.solver import (
    AgentRunRefused,
    PodRunInfrastructureError,
    _resolve_timeout_ms,
    _record_result,
    pod_run,
    require_scorable_pod_state,
    seon_diagnostic_pod_solver,
    seon_pod_solver,
    timeout_honesty,
)


@contextlib.contextmanager
def _fake_door(status: int, body: dict, capture: dict):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            n = int(self.headers.get("Content-Length", 0))
            capture["path"] = self.path
            capture["payload"] = json.loads(self.rfile.read(n))
            payload = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *a):
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        yield f"http://127.0.0.1:{srv.server_port}/agents/run"
    finally:
        srv.shutdown()


def test_pod_run_posts_input_and_returns_metadata():
    capture = {}
    body = {"agent_id": "a1", "reply": "42", "turns": 2, "evals": 3,
            "closed_reason": "completed", "elapsed_ms": 1500}
    with _fake_door(200, body, capture) as url:
        result = pod_run("what is 6*7?", 30000, url)
    assert result == body
    assert capture["path"] == "/agents/run"
    assert capture["payload"] == {"input": "what is 6*7?", "timeout_ms": 30000}


def test_pod_run_passes_agent_id_for_reuse():
    # the planning row's continuity handle: agent_id rides the request body
    capture = {}
    with _fake_door(200, {"agent_id": "a1", "reply": "ok"}, capture) as url:
        pod_run("phase 2", 30000, url, agent_id="a1")
    assert capture["payload"]["agent_id"] == "a1"


def test_pod_run_preserves_absent_timeout_for_database_policy():
    capture = {}
    with _fake_door(200, {"agent_id": "a1", "reply": "ok"}, capture) as url:
        pod_run("database-owned bound", None, url)
    assert capture["payload"] == {"input": "database-owned bound"}


def test_solver_timeout_precedence_preserves_absence():
    assert _resolve_timeout_ms(SimpleNamespace(metadata={}), None) is None
    assert _resolve_timeout_ms(SimpleNamespace(metadata={}), 12) == 12000
    assert _resolve_timeout_ms(
        SimpleNamespace(metadata={"timeout_ms": 3456}), 12) == 3456


def test_pod_run_422_raises_distinct_refusal():
    # unknown agent_id / failed mint = harness wiring to fix, NEVER a model
    # score — a distinct error class, carrying the pod's error message
    capture = {}
    with _fake_door(422, {"error": "unknown agent_id: nope"}, capture) as url:
        with pytest.raises(AgentRunRefused, match="unknown agent_id: nope"):
            pod_run("hi", 30000, url, agent_id="nope")


def test_record_result_preserves_database_and_turn_evidence():
    database = {"db_name": "db-1", "commit_id": "commit-1",
                "t": 536870930}
    turns = [{"turn_id": "turn-1", "prompt": "exact prompt",
              "reply": "raw reply"}]
    evals = [{"eval_id": "eval-1", "source": "(+ 1 2)", "ok": True,
              "operation_evidence": {
                  "status": "inline", "blob_hash": "abc",
                  "operations": [{"position": 0, "result": {
                      "kind": "scalar", "value": 3}}]}}]
    transport = _model_transport_evidence()
    state = SimpleNamespace(output=SimpleNamespace(completion=""), metadata={})

    _record_result(state, {"reply": "answer",
                           "database": database,
                           "turn_evidence": turns,
                           "model_transport_evidence": transport,
                           "eval_evidence": evals,
                           "effective_timeout_ms": 1800000,
                           "timeout_source": "database"})

    assert state.output.completion == "answer"
    assert state.metadata["pod_database_value"] == database
    assert state.metadata["pod_turn_evidence"] == turns
    assert state.metadata["pod_model_transport_evidence"] == transport
    assert state.metadata["pod_eval_evidence"] == evals
    assert state.metadata["pod_effective_timeout_ms"] == 1800000
    assert state.metadata["pod_timeout_source"] == "database"

    incomplete = SimpleNamespace(output=SimpleNamespace(completion=""),
                                 metadata={})
    _record_result(incomplete, {"reply": "legacy"})
    assert "pod_database_value" not in incomplete.metadata
    assert "pod_turn_evidence" not in incomplete.metadata
    assert "pod_model_transport_evidence" not in incomplete.metadata
    assert "pod_eval_evidence" not in incomplete.metadata
    assert "pod_effective_timeout_ms" not in incomplete.metadata
    assert "pod_timeout_source" not in incomplete.metadata


def _model_transport_evidence():
    model = "/cache/models--owner--model/snapshots/" + "a" * 40
    attempt = {
        "turn_id": "turn-1", "ordinal": 0,
        "historical_config_valid": True,
        "provider": "deepseek", "adapter": "openai-compat",
        "requested_model": model, "temperature": 0.0,
        "max_tokens": 512,
        "endpoint": "http://127.0.0.1:8080/v1/chat/completions",
        "adapter_timeout_ms": 30000, "outer_timeout_ms": 45000,
        "stream": False, "credential_class": "environment",
        "response_model": model, "system_fingerprint": "mlx-fixture",
        "outcome": "success"}
    return {"status": "inline", "transport_drift": False,
            "turns": [{"turn_id": "turn-1", "attempts": [attempt]}]}


def _model_server_identity(mechanism="huggingface-snapshot"):
    revision = "a" * 40
    model = f"/cache/models--owner--model/snapshots/{revision}"
    identity = {
        "schema_version": 1,
        "implementation": "mlx-lm",
        "endpoint": "http://127.0.0.1:8080/v1/chat/completions",
        "process": {"pid": 123, "start_instant": "instant",
                    "argv_sha256": "b" * 64},
        "runtime": {"module_sha256": "c" * 64,
                    "packages": {"mlx-lm": "0.31.3", "mlx": "0.32.0"}},
        "artifact": ({
            "mechanism": "externally-mutable",
            "request_model": model,
        } if mechanism == "externally-mutable" else {
            "mechanism": mechanism,
            "request_model": model,
            "revision": revision,
            "manifest_sha256": "d" * 64,
            "size_bytes": 123,
            "quantization": "4bit",
            "config_sha256": "e" * 64,
        }),
        "response": ({} if mechanism == "externally-mutable" else {
            "model": model, "system_fingerprint": "mlx-fixture"}),
    }
    if mechanism == "externally-mutable":
        identity.pop("process")
        identity.pop("runtime")
        identity["endpoint"] = "https://api.example.test/v1/messages"
    return identity


def _admitted_state(evidence=None):
    return SimpleNamespace(metadata={
        "seon_source_admission": {"revision": "admitted"},
        "seon_model_server_identity": _model_server_identity(),
        "pod_timed_out": False, "pod_closed_reason": ":completed",
        "pod_database_value": {"db_name": "db-1", "commit_id": "final",
                               "t": 30},
        "pod_turn_evidence": [{"turn_id": "turn-1"}],
        "pod_model_transport_evidence": (
            evidence if evidence is not None
            else _model_transport_evidence())})


def test_admitted_model_transport_evidence_is_scorable():
    state = _admitted_state()
    assert require_scorable_pod_state(state) is state


@pytest.mark.parametrize(
    "mutate",
    [
        lambda metadata: metadata.pop("seon_model_server_identity"),
        lambda metadata: metadata["seon_model_server_identity"].update(
            endpoint="http://127.0.0.1:9999/v1/chat/completions"),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(requested_model="/other/snapshot"),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(response_model="same-name-wrong-artifact"),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(system_fingerprint="other-runtime"),
    ],
)
def test_admitted_model_server_join_fails_closed(mutate):
    import copy

    state = _admitted_state()
    state.metadata = copy.deepcopy(state.metadata)
    mutate(state.metadata)
    with pytest.raises(
        PodRunInfrastructureError,
        match="model server|model response|model attempt",
    ):
        require_scorable_pod_state(state)


def test_externally_mutable_model_identity_is_not_formally_scorable():
    state = _admitted_state()
    state.metadata["seon_model_server_identity"] = _model_server_identity(
        "externally-mutable")
    with pytest.raises(PodRunInfrastructureError, match="externally mutable"):
        require_scorable_pod_state(state)


def _add_retry(metadata, *, drift=False):
    import copy

    attempts = metadata["pod_model_transport_evidence"]["turns"][0][
        "attempts"]
    attempts[0]["outcome"] = "provider-error"
    retry = copy.deepcopy(attempts[0])
    retry.update(ordinal=1, outcome="success")
    if drift:
        retry["max_tokens"] = 1024
    attempts.append(retry)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda metadata: metadata.pop("pod_model_transport_evidence"),
        lambda metadata: metadata["pod_model_transport_evidence"].update(
            status="malformed"),
        lambda metadata: metadata["pod_model_transport_evidence"].update(
            transport_drift=True),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(historical_config_valid=False),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].pop("requested_model"),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(ordinal=1),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(outcome="provider-error"),
        lambda metadata: metadata["pod_model_transport_evidence"]["turns"][0][
            "attempts"][0].update(evidence_error="identity invalid"),
        lambda metadata: _add_retry(metadata, drift=True),
    ],
)
def test_admitted_model_transport_evidence_fails_closed(mutate):
    import copy

    state = _admitted_state()
    state.metadata = copy.deepcopy(state.metadata)
    mutate(state.metadata)
    with pytest.raises(PodRunInfrastructureError,
                       match="model transport|OpenAI-compatible"):
        require_scorable_pod_state(state)


def test_admitted_model_transport_retry_with_same_config_is_scorable():
    state = _admitted_state()
    _add_retry(state.metadata)
    assert require_scorable_pod_state(state) is state


@pytest.mark.parametrize(
    "metadata",
    [
        {"pod_timed_out": True, "pod_closed_reason": "timeout"},
        {"pod_timed_out": False, "pod_closed_reason": ":error"},
        {"pod_timed_out": False, "pod_closed_reason": ":quiesced"},
    ],
)
def test_unscorable_pod_close_is_infrastructure(metadata):
    state = SimpleNamespace(metadata=metadata)
    with pytest.raises(PodRunInfrastructureError,
                       match="model capability was not scored"):
        require_scorable_pod_state(state)


def test_completed_pod_state_is_scorable():
    state = SimpleNamespace(
        metadata={"pod_timed_out": False, "pod_closed_reason": ":completed"})
    assert require_scorable_pod_state(state) is state


@pytest.mark.parametrize(
    "terminal",
    [
        {"timed_out": True, "closed_reason": "timeout"},
        {"timed_out": False, "closed_reason": ":error"},
        {"timed_out": False, "closed_reason": ":quiesced"},
    ],
)
def test_static_capability_solver_records_then_errors_without_score(
    monkeypatch, tmp_path, terminal
):
    monkeypatch.setattr(
        solver_module,
        "pod_run",
        lambda *_args, **_kwargs: {
            "agent_id": "agent-1",
            "reply": "accepted",
            **terminal,
        },
    )
    task = Task(
        dataset=[Sample(id="terminal", input="return accepted",
                        target="accepted")],
        solver=seon_pod_solver(cluster_url="http://pod.test/agents/run"),
        scorer=match(),
    )
    log = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path),
        fail_on_error=True,
    )[0]

    assert log.status == "error"
    sample = log.samples[0]
    assert sample.error is not None
    assert not sample.scores
    assert sample.metadata["pod_agent_id"] == "agent-1"
    assert sample.metadata["pod_closed_reason"] == terminal["closed_reason"]
    assert sample.metadata["pod_timed_out"] is terminal["timed_out"]


def test_static_capability_solver_completed_control_reaches_scorer(
    monkeypatch, tmp_path
):
    monkeypatch.setattr(
        solver_module,
        "pod_run",
        lambda *_args, **_kwargs: {
            "agent_id": "agent-1",
            "reply": "accepted",
            "timed_out": False,
            "closed_reason": ":completed",
        },
    )
    task = Task(
        dataset=[Sample(id="completed", input="return accepted",
                        target="accepted")],
        solver=seon_pod_solver(cluster_url="http://pod.test/agents/run"),
        scorer=match(),
    )
    log = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path),
    )[0]

    assert log.status == "success"
    assert log.samples[0].scores["match"].value == "C"


@pytest.mark.parametrize("selected_agent", [None, "root"])
def test_static_capability_solver_forwards_exact_optional_agent_id(
    monkeypatch, tmp_path, selected_agent
):
    calls = []

    def fake_pod_run(*args):
        calls.append(args)
        return {
            "agent_id": selected_agent or "fresh-agent",
            "reply": "accepted",
            "timed_out": False,
            "closed_reason": ":completed",
        }

    monkeypatch.setattr(solver_module, "pod_run", fake_pod_run)
    task = Task(
        dataset=[Sample(id="routed", input="return accepted",
                        target="accepted")],
        solver=seon_pod_solver(
            cluster_url="http://pod.test/agents/run",
            agent_id=selected_agent,
        ),
        scorer=match(),
    )
    log = inspect_eval(
        task, model="mockllm/model", display="none", log_dir=str(tmp_path)
    )[0]

    assert calls == [(
        "return accepted", None, "http://pod.test/agents/run", selected_agent
    )]
    assert log.samples[0].metadata["pod_agent_id"] == (
        selected_agent or "fresh-agent"
    )


def test_timeout_diagnostic_explicitly_uses_raw_solver(monkeypatch, tmp_path):
    monkeypatch.setattr(
        solver_module,
        "pod_run",
        lambda *_args, **_kwargs: {
            "agent_id": "agent-1",
            "reply": "",
            "timed_out": True,
            "closed_reason": "timeout",
        },
    )
    task = Task(
        dataset=[Sample(id="diagnostic", input="wait", target="")],
        solver=seon_diagnostic_pod_solver(
            cluster_url="http://pod.test/agents/run"),
        scorer=timeout_honesty(),
    )
    log = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(tmp_path),
    )[0]

    assert log.status == "success"
    assert log.samples[0].scores["timeout_honesty"].value == "C"
