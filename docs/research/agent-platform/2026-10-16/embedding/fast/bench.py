"""Throughput/latency bench of gemma_text.Encoder.
  python -I bench.py MLX_DIR [--no-compile] [--fp32] [--lens 64,128,...] [--batches 1,8,32,...] [--budget 16384]
Synthetic inputs: token slices of the concatenated Seon src (real code tokens), each a distinct slice."""
import sys, time, resource, argparse, numpy as np
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import mlx.core as mx
from mlx.utils import tree_map
ap = argparse.ArgumentParser(); ap.add_argument("path"); ap.add_argument("--no-compile", action="store_true"); ap.add_argument("--fp32", action="store_true")
ap.add_argument("--lens", default="64,128,256,512,1024,2048,4096,8192"); ap.add_argument("--batches", default="1,4,16,32,64,128"); ap.add_argument("--budget", type=int, default=1 << 30)
ap.add_argument("--src", default="/Users/sean/src/seon/src"); a = ap.parse_args()
t0 = time.perf_counter()
from gemma_text import Encoder
enc = Encoder.load(a.path, compiled=not a.no_compile, token_budget=a.budget, max_batch=4096)
if a.fp32: enc.model.update(tree_map(lambda x: x.astype(mx.float32) if x.dtype == mx.bfloat16 else x, enc.model.parameters())); mx.eval(enc.model.parameters())
print(f"{Path(a.path).name}{' fp32' if a.fp32 else ''}{' nocompile' if a.no_compile else ''}: load {time.perf_counter()-t0:.2f}s, weights {mx.get_active_memory()/1e6:.0f} MB")
text = "".join(p.read_text(errors="replace") for p in sorted(Path(a.src).rglob("*.clj"))[:40])
pool = enc.tok.encode(text).ids[1:-1]; off = [0]
def items(n, L):
    out = []
    for _ in range(n):
        s = off[0] % (len(pool) - L); off[0] += 997; out.append([2] + pool[s:s + L - 2] + [1])
    return out
mx.reset_peak_memory()
for L in map(int, a.lens.split(",")):
    row = []
    for B in map(int, a.batches.split(",")):
        if B * L > 262144: continue
        xs = items(B, L); enc.embed_ids(xs)  # warm (compile this shape)
        reps = max(1, min(10, int(2e5 // (B * L)))); t = time.perf_counter()
        for _ in range(reps): enc.embed_ids(items(B, L))
        dt = (time.perf_counter() - t) / reps
        row.append(f"B{B}: {dt*1000/B:.2f} ms/item {B/dt:.0f} emb/s {B*L/dt/1000:.0f}k tok/s")
    print(f"L={L}: " + " | ".join(row), flush=True)
print(f"peak mlx {mx.get_peak_memory()/1e9:.2f} GB, peak RSS {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1e9:.2f} GB")
