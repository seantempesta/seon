"""Batch-embed a JSONL of {"id", "text"} with EmbeddingGemma 2 (text only, MLX), skipping cached items.
  python -I script/embed/embed_batch.py MLX_DIR IN.jsonl OUT.safetensors [--task code|document|clustering|...|none] [--dim 256]
                           [--cache DIR] [--dtype float16|float32] [--budget 16384] [--max-tokens 8192] [--cache-limit-mb 1024]
OUT.safetensors holds "vectors" [n, dim] (L2-normalised) and "digests" uint8 [n, 32]; OUT.ids.jsonl the row ids.
OUT.npy writes vectors only (ids still in OUT.ids.jsonl).
digest = sha256(model \\0 task \\0 dim \\0 text). With --cache DIR every new vector is appended to DIR as one shard
(shard-<time>.safetensors) and every shard already there is read first, so a re-run embeds only new or changed texts."""
import sys, json, time, hashlib, argparse
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
from safetensors.numpy import save_file, load_file
from gemma_text import Encoder, PREFIX, bound_memory, memory

ap = argparse.ArgumentParser(); ap.add_argument("model"); ap.add_argument("inp"); ap.add_argument("out")
ap.add_argument("--task", default="none"); ap.add_argument("--dim", type=int, default=768); ap.add_argument("--cache")
ap.add_argument("--dtype", default="float16"); ap.add_argument("--budget", type=int, default=16384); ap.add_argument("--max-tokens", type=int, default=8192)
ap.add_argument("--cache-limit-mb", type=int, default=1024, help="MLX Metal buffer cache cap; uncapped it grows with every batch shape")
a = ap.parse_args(); task = None if a.task == "none" else a.task; T = {"load": 0, "read": 0, "tokenize": 0, "embed": 0, "write": 0}
bound_memory(a.cache_limit_mb * 1024 * 1024)
t = time.perf_counter(); enc = Encoder.load(a.model, token_budget=a.budget); T["load"] = time.perf_counter() - t
t = time.perf_counter(); rows = [json.loads(l) for l in open(a.inp) if l.strip()]
dig = [hashlib.sha256(f"{enc.name}\0{a.task}\0{a.dim}\0{r['text']}".encode()).digest() for r in rows]
cache = {}
if a.cache:
    Path(a.cache).mkdir(parents=True, exist_ok=True)
    for f in sorted(Path(a.cache).glob("shard-*.safetensors")):
        s = load_file(str(f))
        for d, v in zip(s["digests"], s["vectors"]): cache[d.tobytes()] = v
T["read"] = time.perf_counter() - t
miss = sorted({d: i for i, d in enumerate(dig) if d not in cache}.values())
t = time.perf_counter(); p = PREFIX[task] if task else ""
ids = enc.tokenize([p + rows[i]["text"] for i in miss], a.max_tokens); T["tokenize"] = time.perf_counter() - t
t = time.perf_counter(); V = enc.embed_ids(ids, a.dim).astype(a.dtype) if miss else np.zeros((0, a.dim), a.dtype); T["embed"] = time.perf_counter() - t
t = time.perf_counter()
for i, v in zip(miss, V): cache[dig[i]] = v
if a.cache and miss:
    save_file({"digests": np.frombuffer(b"".join(dig[i] for i in miss), np.uint8).reshape(-1, 32), "vectors": V},
              str(Path(a.cache) / f"shard-{time.time_ns()}.safetensors"))
out = np.stack([cache[d] for d in dig]).astype(a.dtype) if rows else np.zeros((0, a.dim), a.dtype)
if a.out.endswith(".npy"): np.save(a.out, out)
else: save_file({"vectors": out, "digests": np.frombuffer(b"".join(dig), np.uint8).reshape(-1, 32)}, a.out)
with open(Path(a.out).with_suffix(".ids.jsonl"), "w") as w:
    for r, d in zip(rows, dig): w.write(json.dumps({"id": r["id"], "digest": d.hex()}) + "\n")
T["write"] = time.perf_counter() - t
ntok = sum(len(x) for x in ids)
print(json.dumps({"rows": len(rows), "embedded": len(miss), "cached": len(rows) - len(miss), "tokens": ntok,
                  "tok_per_s": round(ntok / T["embed"]) if miss else None, "emb_per_s": round(len(miss) / T["embed"], 1) if miss else None,
                  "seconds": {k: round(v, 3) for k, v in T.items()}, "dim": a.dim, "dtype": a.dtype, "mlx_memory": memory()}))
