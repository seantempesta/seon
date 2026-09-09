"""Run real edit hooks; report convergence separately from terminal refusals.

Run from the repository root: python3 test/seon/dev/hook_convergence_probe.py
The five-file probe touches only this lane's paths, within two seconds.
"""
import collections
import concurrent.futures
import json
import os
import subprocess
import time
import sys
from pathlib import Path

PATHS = [
    "src/seon/cluster.clj",
    "src/seon/cluster/source.clj",
    "test/seon/cluster/source_test.clj",
    "test/seon/cluster/boot_test.clj",
    "test/seon/dev/edit_feedback_test.clj",
]


def edit(path):
    started = time.monotonic()
    os.utime(path, None)
    try:
        result = subprocess.run(
            ["bin/seon-hook"],
            input=json.dumps({"hook_event_name": "PostToolUse", "tool_name": "Edit",
                              "tool_input": {"file_path": path}}),
            text=True, capture_output=True, timeout=400,
        )
    except subprocess.TimeoutExpired as failure:
        return {"path": path, "seconds": time.monotonic() - started,
                "converged": False, "feedback": "Probe deadline expired",
                "stdout": str(failure.stdout), "stderr": str(failure.stderr)}
    try:
        envelope = json.loads(result.stdout)
        feedback = envelope.get("hookSpecificOutput", {}).get("additionalContext", "")
    except json.JSONDecodeError:
        feedback = "Hook returned no readable JSON result: " + result.stdout
    return {"path": path, "seconds": time.monotonic() - started,
            "converged": "converged:" in feedback, "feedback": feedback,
            "exit": result.returncode, "stderr": result.stderr}


def census():
    lines = Path("logs/hook-debug.log").read_text().splitlines()
    batches = [line for line in lines if " | SOURCE_BATCH | " in line][-50:]
    modes = {}
    for line in lines:
        if " | SOURCE_PROGRESS | publication=" not in line:
            continue
        publication = line.split(" | publication=", 1)[1].split(" | ", 1)[0]
        for mode in ("incremental scalar publication",
                     "incremental manifest reconciliation", "complete publication:"):
            if mode in line:
                modes[publication] = mode
    rows = []
    for line in batches:
        publication = line.split(" | publication=", 1)[1].split(" | ", 1)[0]
        rows.append({"publication": publication,
                     "converged": " | converged:" in line,
                     "mode": modes.get(publication, "unrecorded")})
    return {"requested": 50, "available": len(rows), "publications": rows,
            "modes": dict(collections.Counter(row["mode"] for row in rows)),
            "converged": sum(row["converged"] for row in rows)}


if __name__ == "__main__":
    if "--census" in sys.argv:
        print(json.dumps(census()), flush=True)
        sys.exit(0)
    if "--five-only" not in sys.argv:
        print(json.dumps({"one": edit(PATHS[1])}), flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        started = time.monotonic()
        requests = []
        for path in PATHS:
            requests.append(pool.submit(edit, path))
            time.sleep(0.3)
        launch_span = time.monotonic() - started
        results = [request.result() for request in requests]
    print(json.dumps({"launch_span_seconds": launch_span, "five": results}), flush=True)
