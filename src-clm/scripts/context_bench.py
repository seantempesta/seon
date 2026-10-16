"""Encode time / peak memory at 2k..16k tokens, and a needle check on the CLM heads.

    .venv/bin/python scripts/context_bench.py

Needle check: 8 (task, action) pairs. A state is ``task + filler`` where the filler is the same
real Seon source for every task, so only the task text at the START of the state differs. Each
state is scored against all 8 actions; top-1 accuracy per filler length shows whether the
last-token embedding still carries the beginning. ``tail-2048`` is what the default encoder does
(keeps the last 2048 tokens, so the task is cut off once filler > 2048).
"""
from __future__ import annotations

import glob
import json
import os
import time

import mlx.core as mx
import numpy as np

from seon_clm.encoder import Encoder
from seon_clm.heads import HeadPair

ST = os.path.expanduser("~/.cache/clm/CLM_v0.1-8B.safetensors")
SRC = os.path.join(os.path.dirname(__file__), "..", "..", "src")
PAIRS = [
    ("Add a Malli schema for the :seon.task/proofs attribute", "(register-schema! :seon.task/proofs [:vector :symbol])"),
    ("The datahike branch deletion leaves blobs behind; remove the finished agent branch", "(d/delete-branch! conn :agent-7)"),
    ("Render the agent page as HTML with hiccup", "(defn agent-page [db agent] [:div.agent [:h1 (:seon.agent/name agent)]])"),
    ("The SCI interpreter throws on a missing namespace; reload it", "(require 'seon.sci.ctx :reload)"),
    ("Profile the slow cache lookup in seon.cache", "(seon.profile/measure (seon.cache/get kind key))"),
    ("Ask the root agent to review my change", "(my.message/send {:to :root :body \"please review\"})"),
    ("The value printer elides vectors too early", "(defn print-vector [v budget] (take budget v))"),
    ("Write a test for the aero config profile reader", "(deftest read-config-profile-test (is (= :dev (:profile (read-config \"c.edn\")))))"),
]


def main() -> None:
    enc, head = Encoder(max_tokens=40000), HeadPair("clm", ST).ensure()
    files = sorted(glob.glob(os.path.join(SRC, "seon", "**", "*.clj"), recursive=True))
    big = "\n".join(open(f).read() for f in files[:80])
    fill = enc.ids(big)
    print("filler tokens available", len(fill))
    enc.embed(["warm"])
    out = []
    for L in (() if os.environ.get('NEEDLE_ONLY') else (2048, 4096, 8192, 16384, 32768)):
        if L > len(fill):
            break
        text = enc.tok.decode(fill[:L])
        mx.reset_peak_memory()
        t = time.perf_counter()
        enc.embed([text])
        ms = (time.perf_counter() - t) * 1000
        r = dict(tokens=L, ms=round(ms), peak_gb=round(mx.get_peak_memory() / 1e9, 2))
        print(json.dumps(r), flush=True)
        out.append(r)
    za = head.project("action", enc.embed([a for _, a in PAIRS])[0])
    print("needle: top-1 over 8 actions, margin = mean(own - best other) cosine")
    for L in (0, 256, 1024, 2000, 3000, 4096, 8192, 16384):
        if L > len(fill):
            break
        filler = enc.tok.decode(fill[:L]) if L else ""
        for mode, cap in (("full", 40000), ("tail-2048", 2048)):
            enc.max_tokens = cap
            states = [f"{t}\n\n{filler}\n\nWhat is the next action?" for t, _ in PAIRS]
            zs = head.project("state", enc.embed(states)[0])
            cos = zs @ za.T
            top1 = float(np.mean(cos.argmax(1) == np.arange(len(PAIRS))))
            own = cos[np.arange(8), np.arange(8)]
            other = np.where(np.eye(8, dtype=bool), -9, cos).max(1)
            print(json.dumps(dict(filler=L, mode=mode, top1=top1, margin=round(float((own - other).mean()), 3),
                                  own_cos=round(float(own.mean()), 3), state_sim=round(float((zs @ zs.T)[~np.eye(8, dtype=bool)].mean()), 3))), flush=True)




def self_retrieval() -> None:
    """Does the long state's embedding still point at its own short version? (raw encoder and head space)"""
    enc, head = Encoder(max_tokens=40000), HeadPair("clm", ST).ensure()
    files = sorted(glob.glob(os.path.join(SRC, "seon", "**", "*.clj"), recursive=True))
    fill = enc.ids("\n".join(open(f).read() for f in files[:80]))
    short = [f"{t}\n\nWhat is the next action?" for t, _ in PAIRS]
    rs = enc.embed(short)[0]
    hs = head.project("state", rs)
    print("self-retrieval: top-1 of nearest short state (8 candidates), mean own cosine vs mean other cosine")
    for L in (256, 1024, 2000, 4096, 8192, 16384):
        filler = enc.tok.decode(fill[:L])
        for mode, cap in (("full", 40000), ("tail-2048", 2048)):
            enc.max_tokens = cap
            e = enc.embed([f"{t}\n\n{filler}\n\nWhat is the next action?" for t, _ in PAIRS])[0]
            z = head.project("state", e)
            r = dict(filler=L, mode=mode)
            for name, a, b in (("raw", e, rs), ("head", z, hs)):
                c = a @ b.T
                eye = np.eye(8, dtype=bool)
                r[name] = dict(top1=float(np.mean(c.argmax(1) == np.arange(8))), own=round(float(c[eye].mean()), 3),
                               other=round(float(c[~eye].mean()), 3))
            print(json.dumps(r), flush=True)


if __name__ == "__main__":
    main()
    self_retrieval()
