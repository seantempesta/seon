# Collect each blind scorer's final reply (a JSON array) from its transcript into scores/<packet>.json.
# Usage: python3 -I collect.py TASKS_DIR packet=agentid ...
import json, sys, os
tasks = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scores")
for arg in sys.argv[2:]:
    name, aid = arg.split("=")
    path = os.path.join(tasks, aid + ".output")
    if not os.path.exists(path):
        print(name, "missing"); continue
    last = None
    for line in open(path):
        try: m = json.loads(line)
        except Exception: continue
        msg = m.get("message") or {}
        if msg.get("role") == "assistant":
            for c in msg.get("content") or []:
                if isinstance(c, dict) and c.get("type") == "text": last = c["text"]
    if last is None: print(name, "no reply"); continue
    s = last[last.index("["): last.rindex("]") + 1]
    data = json.loads(s)
    json.dump(data, open(os.path.join(out, name + ".json"), "w"), indent=1)
    print(name, len(data))
