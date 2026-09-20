"""Summarize the dated canonical walk probe without printing runtime values.

Usage: python3 <this-file> <fast-log> <jfr-allocation-text>
Create the second input with:
  jfr print --events jdk.ObjectAllocationSample --stack-depth 256 <recording>
JFR text uses the machine's local clock; fast logs use UTC. The dated probe
ran in America/Mexico_City. Sample weights are estimates, not the evaluation's
allocated-byte counter. No sample subset is a complete allocation account.
"""
from collections import Counter
from datetime import datetime
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo


log = Path(sys.argv[1]).read_text()
subject = "seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval"
bounds = []
for phase in ("BEGIN", "END"):
    match = re.search(r"bin/test: (\S+) " + phase + " test " + re.escape(subject), log)
    instant = datetime.fromisoformat(match[1].replace("Z", "+00:00"))
    bounds.append(instant.astimezone(ZoneInfo("America/Mexico_City")))

owners = Counter()
compiler_callers = set()
projection_sites = set()
for block in Path(sys.argv[2]).read_text().split("jdk.ObjectAllocationSample {"):
    timestamp = re.search(r"startTime = (\S+) \(([^)]+)\)", block)
    weight = re.search(r"weight = ([0-9.]+) (\w+)", block)
    if not timestamp or not weight or 'eventThread = "main"' not in block:
        continue
    instant = datetime.fromisoformat(timestamp[2] + "T" + timestamp[1]).replace(
        tzinfo=ZoneInfo("America/Mexico_City"))
    if not bounds[0] <= instant < bounds[1]:
        continue
    frames = [line.strip() for line in block.splitlines()
              if "seon." in line and "(" in line]
    if "compile_pull_plan" in block:
        compiler_callers.update(frame for frame in frames
                                if "seon.schema$" in frame)
    if "projection_from_rows" in block:
        projection_sites.update(frame for frame in frames
                                if "seon.sci.eval$cluster_ctx" in frame
                                or "seon.render$walk" in frame)
    if "seon.render$walk" in block:
        owners[frames[0] if frames else "no Seon frame"] += float(weight[1]) * {
            "bytes": 1, "kB": 1024, "MB": 1048576, "GB": 1073741824}[weight[2]]

print("Walk-stack sampled allocation weights (not the evaluation counter):")
for frame, weight in owners.most_common(12):
    print(round(weight), frame, sep="\t")
print("Compiler callers observed during the test:")
print("\n".join(sorted(compiler_callers)))
print("Context/walk frames accompanying projection-from-rows samples:")
print("\n".join(sorted(projection_sites)))
for line in log.splitlines():
    if line.startswith("direct, web, and through-SCI reuse a plan") or line.startswith(
            "through-SCI allocations must stay below"):
        print(line)
