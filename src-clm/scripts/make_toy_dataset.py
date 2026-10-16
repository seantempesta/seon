"""Toy state -> action dataset from argilla/Synth-APIGen-v0.1 (needs pyarrow; the project venv has none):

    uv run --no-project --with pyarrow python scripts/make_toy_dataset.py OUT.jsonl [N]

state = the user query; action = "tool_name: description" of the tool the query was written for;
negatives = up to 3 other tools offered in the same row (the "same state's other actions").
task_id = the tool name; one row per tool, so a task-disjoint validation split holds out unseen tools.
"""
import glob
import json
import os
import random
import sys

import pyarrow.parquet as pq

out, n = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 5000
f = glob.glob(os.path.expanduser("~/.cache/huggingface/hub/datasets--argilla--Synth-APIGen-v0.1/snapshots/*/data/*.parquet"))[0]
rows = pq.read_table(f).to_pylist()
random.Random(0).shuffle(rows)
seen, recs = set(), []
for r in rows:
    try:
        tools = [t["function"] for t in json.loads(r["tools"])]
    except Exception:
        continue
    by = {t["name"]: f'{t["name"]}: {t.get("description", "")}' for t in tools}
    if r["func_name"] not in by or len(by) < 2 or r["func_name"] in seen:
        continue
    seen.add(r["func_name"])
    negs = [v for k, v in by.items() if k != r["func_name"]][:3]
    recs.append({"state": r["query"], "action": by[r["func_name"]], "negatives": negs, "task_id": r["func_name"], "step_idx": 0})
    if len(recs) == n:
        break
with open(out, "w") as o:
    for r in recs:
        o.write(json.dumps(r) + "\n")
print(len(recs), "records ->", out)
