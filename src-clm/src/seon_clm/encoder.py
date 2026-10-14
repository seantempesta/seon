"""Qwen3-8B last-token encoder on MLX — the in-process replacement for CLM's vLLM
pooling server (reference-code/CLM/src/clm/embedder.py).

vLLM's ``--runner pooling`` for a causal LM returns the final-norm hidden state of
the last token, L2-normalised. This does the same with mlx-lm's ``Qwen3Model``
(``model.model``), whose ``__call__`` ends in ``self.norm(h)``. Texts are
tokenized like ``/v1/embeddings`` with ``truncate_prompt_tokens``: special tokens
on (Qwen3 adds none), the last ``max_tokens`` tokens kept.

Batches are right-padded: attention is causal, so padding after the last real
token cannot change its hidden state. Padded lengths are rounded up to a few
bucket sizes, because every new shape grows MLX's Metal buffer cache.
"""
from __future__ import annotations

import threading

import mlx.core as mx
import numpy as np

DEFAULT_MODEL = "Qwen/Qwen3-8B"
BUCKET = 64                  # padded lengths are multiples of this
BATCH_TOKENS = 16_384        # padded tokens per forward pass
CACHE_LIMIT = 2 * 1024**3    # Metal buffer cache bound (unbounded by default)


def l2(x: np.ndarray) -> np.ndarray:
    return x / (np.linalg.norm(x, axis=-1, keepdims=True) + 1e-12)


def _bucket(n: int) -> int:
    return -(-n // BUCKET) * BUCKET


class Encoder:
    """``embed(texts) -> ([n, hidden] float32 L2-normalised, tokens)``."""

    def __init__(self, model: str = DEFAULT_MODEL, max_tokens: int = 2048,
                 batch_tokens: int = BATCH_TOKENS):
        from mlx_lm import load
        mx.set_cache_limit(CACHE_LIMIT)
        self.name, self.max_tokens, self.batch_tokens = model, max_tokens, batch_tokens
        lm, wrapper = load(model)
        self.body = lm.model                       # Qwen3Model: embeddings -> layers -> final norm
        self.tok = wrapper._tokenizer              # the Hugging Face tokenizer
        self.hidden = self.body.args.hidden_size
        self._lock = threading.Lock()              # one forward pass at a time on the GPU

    def ids(self, text: str) -> list[int]:
        ids = self.tok(text, add_special_tokens=True)["input_ids"] or \
            self.tok(" ", add_special_tokens=False)["input_ids"]
        return ids[-self.max_tokens:]

    def _forward(self, rows: list[list[int]]) -> np.ndarray:
        width = _bucket(max(map(len, rows)))
        x = np.zeros((len(rows), width), dtype=np.int32)
        for i, r in enumerate(rows):
            x[i, :len(r)] = r
        h = self.body(mx.array(x))                                   # [b, width, hidden]
        last = mx.array([len(r) - 1 for r in rows])
        v = mx.take_along_axis(h, last[:, None, None], axis=1)[:, 0, :].astype(mx.float32)
        mx.eval(v)
        return np.array(v)

    def embed(self, texts: list[str]) -> tuple[np.ndarray, int]:
        rows = [self.ids(t) for t in texts]
        order = sorted(range(len(rows)), key=lambda i: len(rows[i]))  # similar lengths share a batch
        out = np.empty((len(rows), self.hidden), dtype=np.float32)
        with self._lock:
            i = 0
            while i < len(order):
                j = i + 1
                while j < len(order) and _bucket(len(rows[order[j]])) * (j - i + 1) <= self.batch_tokens:
                    j += 1
                idx = order[i:j]
                out[idx] = self._forward([rows[k] for k in idx])
                i = j
        return l2(out), sum(map(len, rows))

    def healthy(self) -> bool:
        return True
