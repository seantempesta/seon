"""Bounded curl and declared-read-only MCP observations on the existing default."""
import hashlib
import json
import statistics
import subprocess
import sys
import time


def observe():
    code = '''(let [i (get @@(ns-resolve 'seon.cluster 'running-instances) "default")
                   d (seon.db/db (seon.operator/connection "default"))
                   cache @(seon.render/shared-cache (:seon.sci.eval/ctx i))]
               {:cold-page-kills/basis (seon.db/basis-t d)
                :cold-page-kills/source (seon.db/q '[:find ?s . :where [_ :seon.source/commit-id ?s]] d)
                :cold-page-kills/retained-calls (reduce + (map count (vals (:seon.render.web/calls cache))))})'''
    request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
               "params": {"name": "eval_clj", "arguments": {
                   "root": "/Users/sean/src/seon", "cluster": "default",
                   "mode": "jvm", "read_only": True, "code": code,
                   "session_id": "cold-page-kills-measurement"}}}
    completed = subprocess.run(["bin/mcp-server"], input=json.dumps(request) + "\n",
                               text=True, capture_output=True, timeout=40, check=True)
    reply = json.loads(completed.stdout)
    result = reply["result"]
    if result.get("isError"):
        raise RuntimeError(result)
    data = json.loads(result["content"][0]["text"])
    terminal = next(e for e in data["seon.dev.mcp/events"] if e["tag"] == "ret")
    return terminal["val"]["seon.dev.mcp/value"]


def get(path):
    completed = subprocess.run(
        ["curl", "--max-time", "30", "--fail", "-sS", "-w",
         "\n%{http_code} %{time_total}", "http://127.0.0.1:7994" + path],
        capture_output=True, timeout=35, check=True)
    body, result = completed.stdout.rsplit(b"\n", 1)
    status, seconds = result.decode().split()
    return {"status": int(status), "seconds": float(seconds), "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest()}


path = sys.argv[1] if len(sys.argv) > 1 else "/agent/root"
warm = [get(path) for _ in range(3)]
before = observe()
after = observe()
print(json.dumps({"path": path, "warm": warm, "before": before, "after": after}), flush=True)
started = time.monotonic()
time.sleep(120)
cold = get(path)
elapsed = time.monotonic() - started
repeats = [get(path) for _ in range(3)]
print(json.dumps({"idle_seconds": elapsed - cold["seconds"], "cold": cold,
                  "warm_after": repeats, "observation": observe(),
                  "cold_to_prior_warm_median": cold["seconds"] / statistics.median(x["seconds"] for x in warm),
                  "cold_to_following_warm_median": cold["seconds"] / statistics.median(x["seconds"] for x in repeats)}), flush=True)
