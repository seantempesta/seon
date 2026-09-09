"""Capture virtual-thread stacks during one bounded page GET.

Usage: python3 this-file PID URL OUTPUT_PREFIX
"""
import json
import pathlib
import subprocess
import sys
import time

pid, url, prefix = sys.argv[1:]
request = subprocess.Popen(
    ["curl", "--max-time", "20", "-sS", "-o", "/dev/null", "-w",
     "%{http_code} %{time_total} %{size_download}", url],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
samples = []
for ordinal in range(3):
    time.sleep(0.2)
    path = pathlib.Path(prefix + f"-{ordinal}.json").resolve()
    result = subprocess.run(
        ["jcmd", pid, "Thread.dump_to_file", "-format=json", str(path)],
        capture_output=True, text=True, timeout=10)
    samples.append({"at": time.time(), "dump": str(path),
                    "exit": result.returncode, "output": result.stdout,
                    "error": result.stderr})
out, err = request.communicate(timeout=21)
pathlib.Path(prefix + "-summary.json").write_text(json.dumps(
    {"curl": out, "exit": request.returncode, "error": err, "samples": samples}, indent=2))
