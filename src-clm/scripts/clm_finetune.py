"""Fine-tune the CLM heads on MLX from a JSONL of (state, action[, negatives, task_id, step_idx]).

    .venv/bin/python scripts/clm_finetune.py --data d.jsonl --cache ~/.cache/clm/emb --out ~/.cache/clm/mine.safetensors
    .venv/bin/python scripts/clm_finetune.py --data d.jsonl --cache ... --curve 0,100,300,1000,3000   # learning curve

Embeddings are cached per text (float16), so only new texts reach the encoder. ``--out`` is written atomically,
so a running ``seon_clm`` server hot-reloads it. ``--init`` defaults to the converted reference head.
"""
import argparse
import json
import os

import numpy as np

from seon_clm.train import mlx_finetune as ft

INIT = os.path.expanduser("~/.cache/clm/CLM_v0.1-8B.safetensors")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True)
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/clm/embeddings"))
    ap.add_argument("--init", default=INIT)
    ap.add_argument("--out")
    ap.add_argument("--encoder", default="Qwen/Qwen3-8B")
    ap.add_argument("--max-tokens", type=int, default=2048)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float)
    ap.add_argument("--patience", type=int, default=5)
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--select", default="top1", choices=["top1", "val_loss"])
    ap.add_argument("--embed-only", action="store_true")
    ap.add_argument("--curve", help="comma list of train sizes; trains on the first N train tasks of a fixed split")
    ap.add_argument("--summary", help="write the training summary JSON here")
    a = ap.parse_args()

    recs = ft.read_jsonl(a.data)
    enc = []

    def encode(texts):
        if not enc:
            from seon_clm.encoder import Encoder
            enc.append(Encoder(a.encoder, a.max_tokens))
        return enc[0].embed(texts)[0]

    store = ft.EmbedStore(os.path.expanduser(a.cache), f"{a.encoder}@{a.max_tokens}")
    data = ft.build(recs, store, encode, encode)
    print(f"[data] {len(data)} states, {len(data.A)} distinct actions, {sum(map(len, data.negs))} negatives", flush=True)
    if a.embed_only:
        return
    train, val = ft.split_tasks(data, a.val_frac, a.seed)
    print(f"[data] train {len(train)} | val {len(val)}", flush=True)
    kw = dict(epochs=a.epochs, batch=a.batch, lr=a.lr, patience=a.patience, select=a.select, seed=a.seed)
    if a.curve:
        rows = []
        for n in map(int, a.curve.split(",")):
            if n == 0:
                heads, _ = ft.load_init(a.init)
                r = {"n_train": 0, "best": ft.evaluate(heads, val)}
            else:
                r = ft.train(train.subset(np.arange(min(n, len(train)))), val, a.init, None, log=lambda *_: None, **kw)
            rows.append(r)
            b = r["best"]
            print(json.dumps({"n_train": r["n_train"], "best_epoch": r.get("best_epoch"), "lr": r.get("lr"), "top1": round(b["top1"], 4),
                              "hard_top1": b["hard_top1"] and round(b["hard_top1"], 4), "mrr": round(b["mrr"], 4),
                              "val_loss": round(b["val_loss"], 4), "pool": b["pool"]}), flush=True)
        if a.summary:
            json.dump(rows, open(a.summary, "w"), indent=1)
        return
    s = ft.train(train, val, a.init, a.out, **kw)
    print(json.dumps({k: v for k, v in s.items() if k != "history"}, indent=1))
    if a.summary:
        json.dump(s, open(a.summary, "w"), indent=1)


if __name__ == "__main__":
    main()
