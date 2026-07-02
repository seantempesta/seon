"""Worker endpoints for the diffusion inspect tasks — RunPod or offline mock.

Every solver in this bench calls `.call(payload) -> worker-result-dict`. The
endpoint is a task parameter string:

  "mock:<scenario>"  — the canned offline endpoint (worker_mock.py), zero GPU.
  "runpod"           — the real A100 worker (env DIFFGEMMA_EP + RUNPOD_API_KEY),
                       verify_fresh discipline is the RUNBOOK's job (step 0);
                       this transport mirrors the fixed e1 harness verbatim.
"""

from __future__ import annotations

import json
import os
import time
import urllib.request


class RunPodEndpoint:
    """The real worker over the RunPod async-job API (port: e1_kill_gate.py)."""

    def __init__(self, ep: str | None = None, key: str | None = None):
        self.ep = ep or os.environ["DIFFGEMMA_EP"]
        self.key = key or os.environ["RUNPOD_API_KEY"]

    def _api(self, path, method="GET", body=None):
        req = urllib.request.Request(
            f"https://api.runpod.ai/v2/{self.ep}/{path}",
            data=json.dumps(body).encode() if body else None,
            headers={"Authorization": f"Bearer {self.key}",
                     "Content-Type": "application/json"}, method=method)
        return json.load(urllib.request.urlopen(req, timeout=120))

    def call(self, payload, poll=3, maxpoll=200):
        j = self._api("run", "POST", {"input": payload})
        jid = j["id"]
        for _ in range(maxpoll):
            s = self._api(f"status/{jid}")
            st = s.get("status")
            if st == "COMPLETED":
                return s.get("output") or {}
            if st in ("FAILED", "CANCELLED"):
                return {"_failed": json.dumps(s)[:200]}
            time.sleep(poll)
        return {"_timeout": True}


class CallableEndpoint:
    """Wrap a plain callable (the mock) behind the same .call() surface."""

    def __init__(self, fn):
        self.fn = fn

    def call(self, payload, **_):
        return self.fn(payload)


def resolve_endpoint(spec: str):
    """Turn a task-parameter endpoint spec into a .call()-able endpoint."""
    if spec.startswith("mock:"):
        from seon_inspect.worker_mock import make_mock_endpoint
        return CallableEndpoint(make_mock_endpoint(spec.split(":", 1)[1]))
    if spec == "runpod":
        return RunPodEndpoint()
    raise ValueError(f"unknown endpoint spec: {spec!r} (want mock:<scenario> | runpod)")
