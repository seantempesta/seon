"""Capture virtual-thread stacks during a GET or a signalled context call.

GET: python3 this-file PID URL OUTPUT_PREFIX
Context: python3 this-file PID - OUTPUT_PREFIX --ready-file PATH --samples 16
Start the latter first, then create PATH immediately before the MCP context call.
"""
import argparse
import json
import pathlib
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("pid")
parser.add_argument("url")
parser.add_argument("prefix")
parser.add_argument("--ready-file")
parser.add_argument("--samples", type=int, default=3)
args = parser.parse_args()
if args.ready_file:
    deadline = time.monotonic() + 60
    while not pathlib.Path(args.ready_file).exists():
        if time.monotonic() >= deadline:
            raise TimeoutError("Context probe never announced readiness")
        time.sleep(0.05)
request = None if args.url == "-" else subprocess.Popen(
    ["curl", "--max-time", "20", "-sS", "-o", "/dev/null", "-w",
     "%{http_code} %{time_total} %{size_download}", args.url],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
samples = []
for ordinal in range(args.samples):
    time.sleep(0.2)
    path = pathlib.Path(args.prefix + f"-{ordinal}.json").resolve()
    result = subprocess.run(
        ["jcmd", args.pid, "Thread.dump_to_file", "-format=json", str(path)],
        capture_output=True, text=True, timeout=10)
    samples.append({"at": time.time(), "dump": str(path),
                    "exit": result.returncode, "output": result.stdout,
                    "error": result.stderr})
out, err = request.communicate(timeout=21) if request else (None, None)
pathlib.Path(args.prefix + "-summary.json").write_text(json.dumps(
    {"curl": out, "exit": request.returncode if request else None,
     "error": err, "samples": samples}, indent=2))
