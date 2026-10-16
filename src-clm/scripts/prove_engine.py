"""Prove a fine-tuned head loads into seon_clm.Engine and hot-reloads over a live path.

    .venv/bin/python scripts/prove_engine.py CHECKPOINT.safetensors DATA.jsonl [N]

Copies the reference head to a scratch path, serves it in an Engine, scores N validation-like queries
(argmax over their tool + negatives), then overwrites the path with CHECKPOINT (atomic replace) and scores
again without restarting the Engine: the head generation bumps and hard accuracy changes.
"""
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "reference-code", "CLM", "src"))
from seon_clm.engine import Engine  # noqa: E402
from seon_clm.train import mlx_finetune as ft  # noqa: E402

ck, data = sys.argv[1], sys.argv[2]
n = int(sys.argv[3]) if len(sys.argv) > 3 else 100
recs = ft.read_jsonl(data)[-n:]                      # tail of the file: not in a small training prefix
live = os.path.join(tempfile.mkdtemp(), "live.safetensors")
shutil.copy(os.path.expanduser("~/.cache/clm/CLM_v0.1-8B.safetensors"), live)
eng = Engine(checkpoint=live)


def hard_acc() -> float:
    hit = 0
    for r in recs:
        cands = [r["action"], *r["negatives"]]
        hit += eng.score(r["state"], cands)["scored"][0]["index"] == 0
    return hit / len(recs)


h = eng.heads["clm-latest"] if "clm-latest" in eng.heads else next(iter(eng.heads.values()))
g0, a0 = h.generation, hard_acc()
shutil.copy(ck, live + ".new")
os.replace(live + ".new", live)
a1 = hard_acc()
print({"model": h.name, "generation_before": g0, "generation_after": h.generation, "hard_acc_before": a0,
       "hard_acc_after": a1, "namespace": h.namespace, "n": len(recs)})
