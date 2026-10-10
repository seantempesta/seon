"""Summarize inclusive Seon boot samples; counts are samples, not invocations.

Usage: python3 summarize_jfr.py recording.jfr [recording.jfr ...]
Requires the JDK's jfr command. Allocation weights are sampled estimates;
inclusive owners overlap and must not be added together.
"""
import collections
import json
from pathlib import Path
import subprocess
import sys


def summarize(path):
    events = json.loads(subprocess.check_output([
        "jfr", "print", "--json", "--stack-depth", "256", "--events",
        "jdk.ExecutionSample,jdk.ObjectAllocationSample", path,
    ]))["recording"]["events"]
    samples = collections.Counter()
    allocated = collections.Counter()
    boot_samples = collections.Counter()
    boot_allocated = collections.Counter()
    for event in events:
        value = event["values"]
        frames = (value.get("stackTrace") or {}).get("frames", [])
        owners = set()
        for frame in frames:
            name = frame["method"]["type"]["name"]
            if name.startswith(("seon/cluster", "seon/fn")) and "$" in name:
                owners.add("$".join(name.split("$")[:2]))
        for owner in owners:
            if event["type"] == "jdk.ExecutionSample":
                samples[owner] += 1
            else:
                allocated[owner] += value["weight"]
        if ("seon/cluster/boot$stand_boot_layers_BANG_" in owners
                and "seon/cluster/boot$stand_cluster_runtime_BANG_" not in owners):
            for owner in owners:
                if event["type"] == "jdk.ExecutionSample":
                    boot_samples[owner] += 1
                else:
                    boot_allocated[owner] += value["weight"]
    return {
        "recording_name": Path(path).name,
        "execution_sample_events": sum(e["type"] == "jdk.ExecutionSample" for e in events),
        "inclusive_execution_samples": dict(samples.most_common()),
        "inclusive_sampled_allocation_bytes": dict(allocated.most_common()),
        "before_runtime_inclusive_execution_samples": dict(boot_samples.most_common()),
        "before_runtime_inclusive_sampled_allocation_bytes": dict(boot_allocated.most_common()),
    }


if __name__ == "__main__":
    print(json.dumps([summarize(path) for path in sys.argv[1:]], indent=2))
