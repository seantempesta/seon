"""Memory / latency / embedding quality of the Qwen3-8B encoder at bf16, q8, q4.

    .venv/bin/python scripts/quant_bench.py --bits 16 --out tmp-quant   # one process per precision
    .venv/bin/python scripts/quant_bench.py --compare tmp-quant          # cosine + rank agreement

Texts: 50 slices of Seon's own Clojure source (states, 40..1500 tokens) and 30 one-line
defn heads (candidates). Peak memory is mx.get_peak_memory() per process.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import time

import mlx.core as mx
import numpy as np

ST = os.path.expanduser("~/.cache/clm/CLM_v0.1-8B.safetensors")
SRC = os.path.join(os.path.dirname(__file__), "..", "..", "src")


def samples():
    files = sorted(glob.glob(os.path.join(SRC, "seon", "**", "*.clj"), recursive=True))
    rng = np.random.default_rng(0)
    states, cands = [], []
    for f in rng.permutation(files)[:60]:
        lines = open(f).read().splitlines()
        if len(lines) < 60:
            continue
        a = int(rng.integers(0, len(lines) - 40))
        n = int(rng.choice([10, 30, 60, 120, 240]))
        states.append("\n".join(lines[a:a + n]))
        if len(cands) < 30:
            heads = [l for l in lines if l.startswith("(defn ")]
            if heads:
                cands.append(heads[int(rng.integers(len(heads)))])
    return states[:50], cands[:30]


def run(bits: int, out: str) -> None:
    from mlx_lm import load
    import mlx.nn as nn
    from seon_clm.encoder import Encoder
    from seon_clm.heads import HeadPair
    mx.reset_peak_memory()
    t = time.perf_counter()
    enc = Encoder.__new__(Encoder)
    import threading
    mx.set_cache_limit(2 * 1024**3)
    lm, w = load("Qwen/Qwen3-8B")
    if bits < 16:
        nn.quantize(lm.model, group_size=64, bits=bits)
        mx.eval(lm.parameters())
    enc.name, enc.max_tokens, enc.batch_tokens = "q", 2048, 16384
    enc.body, enc.tok, enc.hidden, enc._lock = lm.model, w._tokenizer, 4096, threading.Lock()
    load_s = time.perf_counter() - t
    weights_gb = mx.get_active_memory() / 1e9
    states, cands = samples()
    enc.embed([states[0]])
    mx.reset_peak_memory()
    ms = {}
    for n in (128, 512, 2048):
        s = " ".join(["defn"] * n)
        enc.embed([s])
        t = time.perf_counter()
        for _ in range(3):
            enc.embed([s])
        ms[n] = (time.perf_counter() - t) / 3 * 1000
    peak_single_gb = mx.get_peak_memory() / 1e9
    mx.reset_peak_memory()
    t = time.perf_counter()
    es, tok = enc.embed(states)
    ec, _ = enc.embed(cands)
    batch_s = time.perf_counter() - t
    head = HeadPair("clm", ST).ensure()
    scores = head.project("state", es) @ head.project("action", ec).T
    os.makedirs(out, exist_ok=True)
    np.savez(os.path.join(out, f"q{bits}.npz"), es=es, ec=ec, scores=scores)
    r = dict(bits=bits, load_s=round(load_s, 1), weights_gb=round(weights_gb, 2),
             peak_gb_single_encode_2048=round(peak_single_gb, 2),
             peak_gb_batch80=round(mx.get_peak_memory() / 1e9, 2), ms_128=round(ms[128]), ms_512=round(ms[512]),
             ms_2048=round(ms[2048]), batch80_s=round(batch_s, 1), batch_tokens=tok)
    print(json.dumps(r))
    json.dump(r, open(os.path.join(out, f"q{bits}.json"), "w"))


def compare(out: str) -> None:
    ref = np.load(os.path.join(out, "q16.npz"))
    for b in (8, 4):
        p = os.path.join(out, f"q{b}.npz")
        if not os.path.exists(p):
            continue
        q = np.load(p)
        cs = (ref["es"] * q["es"]).sum(-1)
        cc = (ref["ec"] * q["ec"]).sum(-1)
        sr, sq = ref["scores"], q["scores"]
        top1 = float(np.mean(sr.argmax(1) == sq.argmax(1)))
        rk = lambda x: x.argsort(-1).argsort(-1).astype(float)
        rho = float(np.mean([np.corrcoef(a, c)[0, 1] for a, c in zip(rk(sr), rk(sq))]))
        print(json.dumps(dict(bits=b, state_cos_mean=round(float(cs.mean()), 4), state_cos_min=round(float(cs.min()), 4),
                              cand_cos_mean=round(float(cc.mean()), 4), cand_cos_min=round(float(cc.min()), 4),
                              head_top1_agree=top1, head_spearman=round(rho, 4),
                              score_abs_err=round(float(np.abs(sr - sq).mean()), 4))))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--bits", type=int)
    ap.add_argument("--out", default="tmp-quant")
    ap.add_argument("--compare")
    a = ap.parse_args()
    compare(a.compare) if a.compare else run(a.bits, a.out)
