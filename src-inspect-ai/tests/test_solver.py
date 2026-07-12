"""Offline tests for the /agents/run bridge (local HTTP fakes, no pod)."""

import contextlib
import http.server
import json
import threading

import pytest

from seon_inspect.solver import AgentRunRefused, pod_run


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


def test_pod_run_422_raises_distinct_refusal():
    # unknown agent_id / failed mint = harness wiring to fix, NEVER a model
    # score — a distinct error class, carrying the pod's error message
    capture = {}
    with _fake_door(422, {"error": "unknown agent_id: nope"}, capture) as url:
        with pytest.raises(AgentRunRefused, match="unknown agent_id: nope"):
            pod_run("hi", 30000, url, agent_id="nope")
