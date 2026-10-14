"""Summarize `jfr print --json` output; weights are estimates, not exact counts."""
import collections
import datetime
import json
import sys

events = json.load(open(sys.argv[1]))["recording"]["events"]
start = datetime.datetime.fromisoformat(sys.argv[2])
groups = collections.defaultdict(list)
sites = collections.Counter()
for event in events:
    value = event["values"]
    frames = (value.get("stackTrace") or {}).get("frames", [])
    stack = "\n".join(f["method"]["type"]["name"] for f in frames)
    if "seon/maintenance$konserve_key_set" in stack:
        phase = "verification-key-list"
    elif ("seon/cluster/registry$collect_and_inventory" in stack
          and "konserve/impl/defaults$list_keys" in stack):
        phase = "inventory-key-list"
    elif "konserve/impl/defaults$list_keys" in stack:
        phase = "other-key-list-stack"
    elif "datahike/gc$reachable_in_branch" in stack:
        phase = "native-mark-stack"
    elif "seon/cluster/registry$referenced_blobs" in stack:
        phase = "blob-extension-stack"
    else:
        phase = "unattributed-stack"
    seconds = (datetime.datetime.fromisoformat(value["startTime"]) - start).total_seconds()
    weight = value.get("weight", 0)
    groups[(event["type"], phase)].append((seconds, weight))
    if event["type"] == "jdk.ObjectAllocationSample":
        sites["all"] += weight
        if "net/jpountz/lz4/LZ4FrameInputStream" in stack:
            sites["LZ4FrameInputStream-inclusive"] += weight
        if value["objectClass"]["name"] == "[B":
            sites["byte-arrays"] += weight

print("group, samples, estimated-MB, first-sample-s, last-sample-s")
for (kind, phase), samples in sorted(groups.items()):
    print(kind, phase, len(samples), round(sum(w for _, w in samples) / 1e6, 3),
          round(min(t for t, _ in samples), 3), round(max(t for t, _ in samples), 3))
print("inclusive-allocation-sites-MB", {k: v / 1e6 for k, v in sites.items()})
