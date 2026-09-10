"""Capture a local GET and virtual-thread-aware samples; no server mutation.

Usage: python3 SCRIPT URL PID OUTPUT_DIRECTORY [SAMPLE_INTERVAL_SECONDS]
JDK JSON dumps include parked virtual threads. Keep all dumps until reviewed.
"""
import json
from pathlib import Path
import subprocess
import sys
import time

url, pid, output = sys.argv[1:4]
interval = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
directory = Path(output).resolve()
directory.mkdir(parents=True, exist_ok=True)
request = subprocess.Popen(
    ["curl", "--max-time", "60", "-sS", "-o", str(directory / "page.html"),
     "-w", "%{http_code} %{time_total}", url], stdout=subprocess.PIPE, text=True)
samples = []
try:
    for index in range(6):
        time.sleep(interval)
        if request.poll() is not None:
            break
        path = directory / f"threads-{index}.json"
        subprocess.run(["jcmd", pid, "Thread.dump_to_file", "-format=json", str(path)],
                       check=True, capture_output=True, timeout=10)
        dump = json.loads(path.read_text())
        for container in dump["threadDump"]["threadContainers"]:
            for thread in container["threads"]:
                stack = thread.get("stack", [])
                if any("debug_prompt" in frame for frame in stack):
                    samples.append({"sample": index, "thread": thread})
    result = request.communicate(timeout=65)[0]
    if request.returncode:
        raise RuntimeError(f"GET failed: {request.returncode}")
    evidence = {"url": url, "pid": pid, "http_status_and_seconds": result,
                "request_thread_samples": samples}
    (directory / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(result, f"{len(samples)} request-thread samples")
finally:
    if request.poll() is None:
        request.terminate()
        request.wait(timeout=10)
