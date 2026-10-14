"""Timings for per-turn context selection on this Mac.

    .venv/bin/python scripts/bench.py

* state encode latency at 128..2048 tokens (a new turn's state: one pass, cold);
* card throughput: embedding + projecting many short cards (a one-off per digest);
* scoring: one state vector against 10k / 100k cached card vectors (numpy);
* peak Metal memory.
"""
from __future__ import annotations

import os
import time

import mlx.core as mx
import numpy as np

from seon_clm.encoder import Encoder
from seon_clm.heads import HeadPair

ST = os.path.expanduser("~/.cache/clm/CLM_v0.1-8B.safetensors")
WORDS = ("defn let map reduce schema turn agent evaluation branch datahike malli contract "
         "render walk namespace query pull transact error refusal test deftest fixture").split()


def text(tokens: int, seed: int) -> str:
    rng = np.random.default_rng(seed)
    return " ".join(rng.choice(WORDS, size=tokens))       # ~1 token per word


def ms(f, n: int = 3) -> float:
    best = float("inf")
    for _ in range(n):
        t = time.perf_counter()
        f()
        best = min(best, time.perf_counter() - t)
    return best * 1000


def main() -> None:
    t = time.perf_counter()
    enc, head = Encoder(), HeadPair("clm", ST).ensure()
    print(f"load            {time.perf_counter() - t:6.1f} s")
    enc.embed([text(16, 0)])                               # compile/warm kernels

    for n in (128, 512, 1024, 2048):
        s = text(n, n)
        tokens = len(enc.ids(s))
        print(f"state {tokens:5d} tok  {ms(lambda: head.project('state', enc.embed([s])[0])):7.1f} ms")

    cards = [text(40, 1000 + i) for i in range(512)]
    t = time.perf_counter()
    head.project("action", enc.embed(cards)[0])
    dt = time.perf_counter() - t
    print(f"cards (40 tok)  {len(cards) / dt:7.1f} cards/s  ({dt * 1000 / len(cards):.1f} ms each)")

    s = head.project("state", enc.embed([text(1024, 7)])[0])[0]
    for n in (10_000, 100_000):
        z = np.random.default_rng(0).standard_normal((n, head.proj_dim)).astype(np.float32)
        print(f"score {n:7,d} cards  {ms(lambda: np.argpartition(-(z @ s), 50)[:50], 10):6.2f} ms")

    print(f"peak Metal      {mx.get_peak_memory() / 1024**3:6.1f} GB")


if __name__ == "__main__":
    main()
