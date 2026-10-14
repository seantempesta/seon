"""CLM projection heads on MLX — the same MLP as reference-code/CLM/src/clm/heads.py
(``make_head``), loaded from the safetensors file ``convert.py`` writes.

A pair maps [n, 4096] encoder embeddings to L2-normalised [n, proj] vectors; the
score of (state, candidate) is ``scale * cos``. The file is reloaded when its
mtime changes and ``generation`` is bumped, so a fine-tuning loop can overwrite
it under a running server and cached projections stop matching.
"""
from __future__ import annotations

import json
import os
import threading

import mlx.core as mx
import mlx.nn as nn
import numpy as np

ACTIVATIONS = {"gelu": nn.gelu, "relu": nn.relu, "silu": nn.silu}   # nn.gelu is exact erf, like torch


class Head(nn.Module):
    """``hidden -> width -> ... -> proj``, optional LayerNorm / residual hidden blocks."""

    def __init__(self, hidden: int, width: int, depth: int, proj: int, activation: str,
                 layernorm: bool, residual: bool):
        super().__init__()
        self.inp = nn.Linear(hidden, width)
        self.hidden = [nn.Linear(width, width) for _ in range(depth - 2)]
        self.norms = [nn.LayerNorm(width) if layernorm else nn.Identity() for _ in range(depth - 2)]
        self.out = nn.Linear(width, proj)
        self.act, self.residual = ACTIVATIONS[activation], residual

    def __call__(self, x):
        x = self.act(self.inp(x))
        for lin, nrm in zip(self.hidden, self.norms):
            h = self.act(nrm(lin(x)))
            x = x + h if self.residual else h
        return self.out(x)


def config_of(meta: dict) -> dict:
    cfg = meta["cfg"]
    return dict(hidden=cfg.get("hidden_size", 4096), width=cfg["width"], depth=cfg["depth"],
                proj=meta.get("projection_dim") or cfg.get("projection_dim", 512),
                activation=cfg.get("activation", "gelu"), layernorm=cfg.get("layernorm", False),
                residual=cfg.get("residual", False))


class HeadPair:
    def __init__(self, name: str, path: str):
        self.name, self.path = name, path
        self.mtime: float | None = None
        self.generation = 0
        self._lock = threading.Lock()

    def _load(self) -> None:
        from safetensors import safe_open
        with safe_open(self.path, framework="numpy") as f:
            meta = json.loads(f.metadata()["clm"])
            weights = {k: mx.array(f.get_tensor(k)) for k in f.keys()}
        kw = config_of(meta)
        heads = {}
        for side in ("state", "action"):
            h = Head(**kw)
            h.load_weights([(k[len(side) + 1:], v) for k, v in weights.items() if k.startswith(side + ".")])
            h.eval()
            heads[side] = h
        self.heads, self.meta, self.proj_dim = heads, meta, kw["proj"]
        self.scale = float(min(np.exp(meta["logit_scale"]), 100.0))
        self.generation += 1

    def ensure(self) -> "HeadPair":
        with self._lock:
            m = os.path.getmtime(self.path)
            if m != self.mtime:
                self._load()
                self.mtime = m
        return self

    def project(self, side: str, x: np.ndarray) -> np.ndarray:
        """[n, hidden] -> [n, proj] L2-normalised float32."""
        self.ensure()
        z = self.heads[side](mx.array(x))
        z = z / (mx.linalg.norm(z, axis=-1, keepdims=True) + 1e-12)
        mx.eval(z)
        return np.array(z)

    @property
    def namespace(self) -> str:
        return f"{self.name}@{self.generation}"
