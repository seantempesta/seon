"""The CLM engine on MLX.

``Engine`` subclasses the reference ``clm.engine.Engine`` and replaces only where
vectors come from: the MLX ``Encoder`` instead of a vLLM server, MLX heads instead
of torch, and an LRU of projected vectors instead of the CUDA arena. ``answer``,
``rank``, ``models`` and ``has`` are the reference methods unchanged, so the
reference ``clm.server`` app and its playground serve this engine as they are.

Two additions for callers that keep their own candidate vectors (Seon keys card
vectors by definition digest and scores in the JVM):

* ``project(side, texts)`` -> L2-normalised vectors in the head's space, and
* ``score(state, candidates)`` -> raw ``scale * cos`` logits, best first — one
  state encoding per call, however many candidates.
"""
from __future__ import annotations

import glob
import os
from collections import OrderedDict
from typing import Any, Callable

import numpy as np
from clm.engine import DEFAULT_MODEL, RAW_MODEL, RAW_SCALE, Engine as Reference, ModelNotFound
from clm.schema import state_text

from .encoder import DEFAULT_MODEL as DEFAULT_ENCODER, Encoder
from .heads import HeadPair

SIDES = ("state", "action")


class VectorCache:
    """LRU of vectors keyed by (namespace, text); a namespace names exact weights."""

    def __init__(self, capacity: int = 200_000):
        self.capacity, self.rows = capacity, OrderedDict()
        self.hits = self.misses = 0

    def get(self, namespace: str, texts: list[str], compute: Callable[[list[str]], np.ndarray]) -> np.ndarray:
        keys = [(namespace, t) for t in texts]
        todo = list(dict.fromkeys(t for t in texts if (namespace, t) not in self.rows))
        self.misses += len(todo)
        self.hits += len(texts) - len(todo)
        if todo:
            for t, v in zip(todo, compute(todo)):
                self.rows[(namespace, t)] = v
        for k in keys:
            self.rows.move_to_end(k)
        out = np.stack([self.rows[k] for k in keys])
        while len(self.rows) > self.capacity:
            self.rows.popitem(last=False)
        return out

    def stats(self) -> dict:
        n = self.hits + self.misses
        return {"rows": len(self.rows), "capacity": self.capacity, "hits": self.hits, "misses": self.misses,
                "hit_rate": self.hits / n if n else None}


class MLXHeadPair(HeadPair):
    """The reference engine calls ``project_states`` / ``project_actions``."""

    def project_states(self, x):
        return self.project("state", x)

    def project_actions(self, x):
        return self.project("action", x)


def head_paths(checkpoint: str | None, checkpoint_dir: str | None, models: dict[str, str] | None) -> dict[str, str]:
    paths = {DEFAULT_MODEL: checkpoint} if checkpoint else {}
    for p in sorted(glob.glob(os.path.join(checkpoint_dir, "*.safetensors"))) if checkpoint_dir else []:
        paths.setdefault(os.path.splitext(os.path.basename(p))[0], p)
    return {**paths, **(models or {})}


class Engine(Reference):
    def __init__(self, encoder: Encoder | None = None, checkpoint: str | None = None,
                 models: dict[str, str] | None = None, checkpoint_dir: str | None = None,
                 cache_rows: int = 200_000, encoder_model: str = DEFAULT_ENCODER, max_tokens: int = 2048):
        self.embedder = encoder or Encoder(encoder_model, max_tokens)
        self.heads = {n: MLXHeadPair(n, p).ensure() for n, p in head_paths(checkpoint, checkpoint_dir, models).items()}
        self.device = "mlx"
        self.arena = VectorCache(cache_rows)

    def _cached(self, namespace: str, dim: int, texts: list[str], tokens: list[int], project=None):
        def compute(missing: list[str]) -> np.ndarray:
            emb, spent = self.embedder.embed(missing)
            tokens.append(spent)
            return project(emb) if project else emb
        return self.arena.get(namespace, texts, compute)

    def _space(self, model: str) -> tuple[str, float, Any]:
        """-> (cache namespace prefix, logit scale, head or None for the raw encoder space)."""
        if model == RAW_MODEL:
            return "raw", RAW_SCALE, None
        if model not in self.heads:
            raise ModelNotFound(f"unknown model {model!r}; available: {[m['name'] for m in self.models()]}")
        h = self.heads[model].ensure()
        return h.namespace, h.scale, h

    def project(self, side: str, texts: list[str], model: str = DEFAULT_MODEL) -> tuple[np.ndarray, float, int]:
        """-> ([n, dim] L2-normalised vectors, logit scale, encoder tokens spent on misses)."""
        if side not in SIDES:
            raise ValueError(f"side must be one of {SIDES}, got {side!r}")
        ns, scale, head = self._space(model)
        tokens: list[int] = []
        z = self._cached(f"{ns}/{side}", 0, texts, tokens, head and (lambda e: head.project(side, e)))
        return z, scale, sum(tokens)

    def score(self, state: Any, candidates: list[str], instructions: str | None = None,
              model: str = DEFAULT_MODEL, top_k: int | None = None) -> dict:
        """Raw logits (``scale * cos``) of every candidate against one state, best first."""
        zs, scale, t1 = self.project("state", [state_text(state, instructions)], model)
        za, _, t2 = self.project("action", candidates, model)
        cos = za @ zs[0]
        order = np.argsort(-cos)[:top_k]
        return {"model": model, "scale": scale, "input_tokens": t1 + t2,
                "scored": [{"index": int(i), "logit": float(scale * cos[i]), "cos": float(cos[i])} for i in order]}
