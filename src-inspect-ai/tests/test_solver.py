"""Offline tests for the /agents/run bridge (local HTTP fakes, no pod)."""

import contextlib
import http.server
import json
import threading
from types import SimpleNamespace

import pytest

from seon_inspect.solver import (
    AgentRunRefused,
    PodRunInfrastructureError,
    _resolve_timeout_ms,
    _record_result,
    pod_run,
    require_scorable_pod_state,
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
    coordinate = {"database_id": "db-1", "branch": "db",
                  "commit_id": "commit-1", "t": 536870930}
    turns = [{"turn_id": "turn-1", "prompt": "exact prompt",
              "reply": "raw reply", "rendered_coordinate": coordinate}]
    evals = [{"eval_id": "eval-1", "source": "(+ 1 2)", "ok": True}]
    state = SimpleNamespace(output=SimpleNamespace(completion=""), metadata={})

    _record_result(state, {"reply": "answer",
                           "database_coordinate": coordinate,
                           "turn_evidence": turns,
                           "eval_evidence": evals,
                           "effective_timeout_ms": 1800000,
                           "timeout_source": "database"})

    assert state.output.completion == "answer"
    assert state.metadata["pod_database_coordinate"] == coordinate
    assert state.metadata["pod_turn_evidence"] == turns
    assert state.metadata["pod_eval_evidence"] == evals
    assert state.metadata["pod_effective_timeout_ms"] == 1800000
    assert state.metadata["pod_timeout_source"] == "database"

    incomplete = SimpleNamespace(output=SimpleNamespace(completion=""),
                                 metadata={})
    _record_result(incomplete, {"reply": "legacy"})
    assert "pod_database_coordinate" not in incomplete.metadata
    assert "pod_turn_evidence" not in incomplete.metadata
    assert "pod_eval_evidence" not in incomplete.metadata
    assert "pod_effective_timeout_ms" not in incomplete.metadata
    assert "pod_timeout_source" not in incomplete.metadata


@pytest.mark.parametrize(
    "metadata",
    [
        {"pod_timed_out": True, "pod_closed_reason": "timeout"},
        {"pod_timed_out": False, "pod_closed_reason": ":error"},
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
