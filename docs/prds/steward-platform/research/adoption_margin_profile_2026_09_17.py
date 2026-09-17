"""Summarize `jfr print --stack-depth 128 --events jdk.ExecutionSample --json`."""
import collections
import json
import sys

with open(sys.argv[1]) as source:
    events = json.load(source)["recording"]["events"]
first_seon = collections.Counter()
encoding_stacks = 0
for event in events:
    stack = event["values"].get("stackTrace") or {}
    names = [f["method"]["type"]["name"] for f in stack.get("frames", [])]
    encoding_stacks += any("schema$canonical_data_string" in n for n in names)
    first = next((n for n in names if n.startswith("seon/")), None)
    if first:
        first_seon[first] += 1
print(json.dumps({"samples": len(events), "encoding_stacks": encoding_stacks,
                  "first_seon_frame": first_seon.most_common(15)}, indent=2))
