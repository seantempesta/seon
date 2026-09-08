"""Bounded curl sampling for the live page/adoption verification."""
import argparse
import concurrent.futures
import json
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--seconds", type=float, default=60)
parser.add_argument("--output", required=True)
args = parser.parse_args()


def sample(ordinal):
    started = time.time()
    result = subprocess.run(
        ["curl", "--max-time", "10", "-sS", "-o", "/dev/null", "-w",
         "%{http_code} %{time_total}", args.url], capture_output=True, text=True)
    return {"ordinal": ordinal, "started": started, "result": result.stdout,
            "exit": result.returncode, "error": result.stderr}


with concurrent.futures.ThreadPoolExecutor(max_workers=50) as executor:
    started = time.monotonic()
    pending = []
    while time.monotonic() - started < args.seconds:
        pending.append(executor.submit(sample, len(pending)))
        time.sleep(max(0, started + len(pending) * 0.2 - time.monotonic()))
    results = [item.result() for item in pending]
with open(args.output, "w") as output:
    json.dump(results, output, indent=2)
print(json.dumps({"samples": len(results), "non_200": sum(
    row["exit"] != 0 or not row["result"].startswith("200 ") for row in results),
    "max_seconds": max(float(row["result"].split()[-1]) for row in results)}))
