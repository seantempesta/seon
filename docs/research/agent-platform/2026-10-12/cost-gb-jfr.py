"""Summarize allocation samples from an isolated reach-cost check's JFR JSON."""
import collections
import json
import sys

events = json.load(open(sys.argv[1]))["recording"]["events"]
inclusive = collections.Counter()
stacks = collections.Counter()
for event in events:
    value = event["values"]
    if event["type"] != "jdk.ObjectAllocationSample":
        continue
    if value["eventThread"]["javaName"] != "seon-test-body":
        continue
    frames = (value.get("stackTrace") or {}).get("frames", [])
    names = tuple(frame["method"]["type"]["name"] + "." + frame["method"]["name"] for frame in frames)
    weight = value["weight"]
    for name in set(names):
        inclusive[name] += weight
    stacks[names[:30]] += weight
json.dump({"scope": "allocation samples on seon-test-body; inclusive weights overlap",
           "inclusive": inclusive.most_common(30),
           "stacks": [{"estimated_bytes": weight, "frames": names}
                      for names, weight in stacks.most_common(6)]}, sys.stdout, indent=2)
print()
