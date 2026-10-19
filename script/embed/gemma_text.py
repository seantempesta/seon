"""EmbeddingGemma 2 text-only encoder on MLX: the 270M text path, nothing else.

Mirrors mlx_vlm.models.embedding_gemma2 (pinned 3d87e884) `language.TextModel` + the text branch of `Model.__call__`
(embed * sqrt(hidden), bidirectional encoder, sliding window |i-j| <= 512 on 20 of 24 layers, mean pooling, L2 norm),
with three changes for speed, none changing the math:
  * sliding layers on long inputs use banded block attention (each 512-token query block sees 3 key blocks) instead of
    a dense L x L masked attention: O(L*1536) instead of O(L^2);
  * optional mx.compile of the forward pass (compiled=True; one graph per (batch, padded length) shape; measured no
    end-to-end gain on the src corpus and 10-400 ms per new shape, so off by default);
  * masks are built once per batch and omitted when there is no padding.
Depends only on mlx, numpy and tokenizers (no mlx_vlm, torch or transformers at runtime).

Every process that loads it calls bound_memory first: MLX's Metal buffer cache is otherwise unbounded.

API:  enc = Encoder.load(DIR); vecs = enc.embed(texts, task="code", dim=256)  -> float32 [n, dim], L2-normalised.
"""
import json, math, time
from pathlib import Path
import numpy as np
import mlx.core as mx
import mlx.nn as nn

PREFIX = {"search_query": "task: search result | query: ", "query": "task: search result | query: ",
          "document": "title: none | text: ", "classification": "task: classification | query: ",
          "clustering": "task: clustering | query: ", "similarity": "task: sentence similarity | query: ",
          "code": "task: code retrieval | query: ", "qa": "task: question answering | query: ",
          "fact": "task: fact checking | query: "}
import os
FAST_ROPE = os.environ.get("EG2_FAST_ROPE", "1") == "1"  # fused mx.fast.rope (fp32 internally); 0 = reference bf16 cos/sin


class Attention(nn.Module):
    def __init__(self, c, i):
        super().__init__()
        o = c["per_layer_config"].get(f"{i:02d}", {})
        self.head_dim = o.get("head_dim", c["head_dim"]); self.nh = o.get("num_attention_heads", c["num_attention_heads"])
        self.nkv = o.get("num_key_value_heads", c["num_key_value_heads"]); h = c["hidden_size"]
        self.q_proj = nn.Linear(h, self.nh * self.head_dim, bias=False)
        self.k_proj = nn.Linear(h, self.nkv * self.head_dim, bias=False)
        self.v_proj = nn.Linear(h, self.nkv * self.head_dim, bias=False)
        self.o_proj = nn.Linear(self.nh * self.head_dim, h, bias=False)
        self.q_norm = nn.RMSNorm(self.head_dim, eps=c["rms_norm_eps"]); self.k_norm = nn.RMSNorm(self.head_dim, eps=c["rms_norm_eps"])
        self.eps = c["rms_norm_eps"]; self.sliding = c["layer_types"][i] == "sliding_attention"
        self.base = c["rope_parameters"][c["layer_types"][i]]["rope_theta"]
        self._freqs = 1.0 / (self.base ** (mx.arange(0, self.head_dim, 2, dtype=mx.float32) / self.head_dim))

    def rope(self, x):
        if FAST_ROPE: return mx.fast.rope(x, self.head_dim, traditional=False, base=self.base, scale=1.0, offset=0)
        L = x.shape[2]  # reference rounding: fp32 angles, cos/sin cast to activation dtype
        a = mx.arange(L, dtype=mx.float32)[:, None] * self._freqs
        a = mx.concatenate([a, a], axis=-1)
        cos, sin = mx.cos(a).astype(x.dtype), mx.sin(a).astype(x.dtype)
        half = x.shape[-1] // 2
        return x * cos + mx.concatenate([-x[..., half:], x[..., :half]], axis=-1) * sin

    def __call__(self, x, m):
        B, L, _ = x.shape
        q = self.q_norm(self.q_proj(x).reshape(B, L, self.nh, -1)).transpose(0, 2, 1, 3)
        k = self.k_norm(self.k_proj(x).reshape(B, L, self.nkv, -1)).transpose(0, 2, 1, 3)
        v = mx.fast.rms_norm(self.v_proj(x).reshape(B, L, self.nkv, -1), None, self.eps).transpose(0, 2, 1, 3)
        q, k = self.rope(q), self.rope(k)
        if self.sliding and m["band"] is not None:
            out = banded(q, k, v, m)
        else:
            out = mx.fast.scaled_dot_product_attention(q, k, v, scale=1.0, mask=m["slide"] if self.sliding else m["full"])
        return self.o_proj(out.transpose(0, 2, 1, 3).reshape(B, L, -1))


def banded(q, k, v, m):
    """Sliding-window attention by 512-blocks: query block b attends key blocks b-1, b, b+1 (window 512 <= block)."""
    B, H, L, D = q.shape; C = m["block"]; n = L // C; Hk = k.shape[1]
    def blocks(t):  # [B,Hk,L,D] -> [B*n, Hk, 3C, D]
        t = mx.pad(t, [(0, 0), (0, 0), (C, C), (0, 0)]).reshape(B, Hk, n + 2, C, -1)
        t = mx.stack([t[:, :, 0:n], t[:, :, 1:n + 1], t[:, :, 2:n + 2]], axis=3)  # B,Hk,n,3,C,D
        return t.reshape(B, Hk, n, 3 * C, -1).transpose(0, 2, 1, 3, 4).reshape(B * n, Hk, 3 * C, -1)
    qb = q.reshape(B, H, n, C, D).transpose(0, 2, 1, 3, 4).reshape(B * n, H, C, D)
    o = mx.fast.scaled_dot_product_attention(qb, blocks(k), blocks(v), scale=1.0, mask=m["band"])
    return o.reshape(B, n, H, C, D).transpose(0, 2, 1, 3, 4).reshape(B, H, L, D)


class MLP(nn.Module):
    def __init__(self, c):
        super().__init__(); h, f = c["hidden_size"], c["intermediate_size"]
        self.gate_proj = nn.Linear(h, f, bias=False); self.up_proj = nn.Linear(h, f, bias=False); self.down_proj = nn.Linear(f, h, bias=False)
    def __call__(self, x): return self.down_proj(nn.gelu_approx(self.gate_proj(x)) * self.up_proj(x))


class PLE(nn.Module):
    def __init__(self, c):
        super().__init__(); self.n, self.w = c["num_hidden_layers"], c["hidden_size_per_layer_input"]; self.scale = c["hidden_size"] ** -0.5
        self.per_layer_model_projection = nn.Linear(c["hidden_size"], self.n * self.w, bias=False)
        self.per_layer_projection_norm = nn.RMSNorm(self.w, eps=c["rms_norm_eps"])
    def __call__(self, x):
        p = self.per_layer_model_projection(x) * self.scale
        return self.per_layer_projection_norm(p.reshape(*x.shape[:-1], self.n, self.w))


class PLEBlock(nn.Module):
    def __init__(self, c):
        super().__init__(); h, w = c["hidden_size"], c["hidden_size_per_layer_input"]
        self.per_layer_input_gate = nn.Linear(h, w, bias=False); self.per_layer_projection = nn.Linear(w, h, bias=False)
        self.post_per_layer_input_norm = nn.RMSNorm(h, eps=c["rms_norm_eps"])
    def __call__(self, x, p): return x + self.post_per_layer_input_norm(self.per_layer_projection(nn.gelu_approx(self.per_layer_input_gate(x)) * p))


class Layer(nn.Module):
    def __init__(self, c, i):
        super().__init__(); h, e = c["hidden_size"], c["rms_norm_eps"]
        self.self_attn = Attention(c, i); self.mlp = MLP(c); self.ple_block = PLEBlock(c)
        self.input_layernorm = nn.RMSNorm(h, eps=e); self.post_attention_layernorm = nn.RMSNorm(h, eps=e)
        self.pre_feedforward_layernorm = nn.RMSNorm(h, eps=e); self.post_feedforward_layernorm = nn.RMSNorm(h, eps=e)
        self.layer_scalar = mx.ones((1,))
    def __call__(self, x, m, p):
        x = x + self.post_attention_layernorm(self.self_attn(self.input_layernorm(x), m))
        x = x + self.post_feedforward_layernorm(self.mlp(self.pre_feedforward_layernorm(x)))
        return self.ple_block(x, p) * self.layer_scalar


class TextModel(nn.Module):
    def __init__(self, c):
        super().__init__(); self.c = c
        self.embed_tokens = nn.Embedding(c["vocab_size"], c["hidden_size"]); self.ple = PLE(c)
        self.layers = [Layer(c, i) for i in range(c["num_hidden_layers"])]
        self.norm = nn.RMSNorm(c["hidden_size"], eps=c["rms_norm_eps"])
        self.embedding_projection = nn.Linear(c["hidden_size"], c["embedding_dim"], bias=False)

    def __call__(self, ids, mask, band_ok):
        """ids, mask: int32 [B, L]. Returns L2-normalised mean-pooled embeddings, float32 [B, 768]."""
        c = self.c; B, L = ids.shape; W = c["sliding_window"]
        x = self.embed_tokens(ids); x = x * mx.array(c["hidden_size"] ** 0.5, dtype=x.dtype)
        keys = mask.astype(mx.bool_)[:, None, None, :]
        m = {"full": keys, "slide": keys, "band": None, "block": W}
        if L > W + 1:
            if band_ok:  # L % W == 0 and L > 3W: banded blocks
                n = L // W; C = W
                kp = mx.pad(mask.astype(mx.bool_), [(0, 0), (C, C)]).reshape(B, n + 2, C)
                kv = mx.stack([kp[:, 0:n], kp[:, 1:n + 1], kp[:, 2:n + 2]], axis=2).reshape(B * n, 1, 1, 3 * C)
                i = mx.arange(C)[:, None]; j = mx.arange(3 * C)[None, :]
                m["band"] = kv & (mx.abs(i - j + C) <= W)[None, None]
            else:
                pos = mx.arange(L)
                m["slide"] = keys & (mx.abs(pos[:, None] - pos[None, :]) <= W)[None, None]
        p = self.ple(x)
        for i, layer in enumerate(self.layers):
            x = layer(x, m, p[:, :, i])
        h = self.embedding_projection(self.norm(x)).astype(mx.float32)
        f = mask.astype(mx.float32)[..., None]
        e = (h * f).sum(1) / mx.maximum(f.sum(1), 1e-9)
        return e / mx.maximum(mx.linalg.norm(e, axis=-1, keepdims=True), 1e-9)


def bound_memory(cache_bytes):
    """Cap MLX's Metal buffer cache at `cache_bytes`: uncapped it keeps a buffer for every batch shape seen, growing
    without bound and invisible to RSS. Returns the memory figures `memory()` reports."""
    mx.set_cache_limit(int(cache_bytes))
    return memory()


def memory():
    """MLX's active, cached and peak Metal memory, in bytes."""
    return {"active": mx.get_active_memory(), "cache": mx.get_cache_memory(), "peak": mx.get_peak_memory()}


def round_len(n, W=512):
    """Padded length: multiple of 16 up to 512, of 64 up to 1536, then of 512 (banded attention needs L % 512 == 0)."""
    if n <= 512: return max(16, -(-n // 16) * 16)
    if n <= 3 * W: return -(-n // 64) * 64
    return -(-n // W) * W


class Encoder:
    def __init__(self, model, tok, name, compiled=False, token_budget=16384, max_batch=128):
        self.model, self.tok, self.name = model, tok, name
        self.token_budget, self.max_batch = token_budget, max_batch
        self.fwd = mx.compile(model.__call__) if compiled else model.__call__
        self._fwd_band = {}

    @staticmethod
    def load(path, compiled=False, **kw):
        from tokenizers import Tokenizer
        path = Path(path); cfg = json.load(open(path / "config.json")); c = cfg.get("text_config", cfg)
        model = TextModel(c)
        q = cfg.get("quantization")
        if q: nn.quantize(model, group_size=q["group_size"], bits=q["bits"])
        w = {}
        for f in sorted(path.glob("*.safetensors")):
            for k, v in mx.load(str(f)).items():
                k = k.removeprefix("model.")
                if k.startswith("language_model."): w[k.removeprefix("language_model.")] = v
        model.load_weights(list(w.items()), strict=True); model.eval(); mx.eval(model.parameters())
        tok = Tokenizer.from_file(str(path / "tokenizer.json"))
        return Encoder(model, tok, path.name, compiled, **kw)

    def tokenize(self, texts, max_tokens=8192):
        out = []
        for e in self.tok.encode_batch(list(texts)):
            ids = e.ids
            if len(ids) > max_tokens: ids = ids[:max_tokens - 1] + [ids[-1]]  # keep <eos>, as HF truncation does
            out.append(ids)
        return out

    def batches(self, lens):
        """Length-sorted buckets: each batch shares one padded length; size bounded by the token budget."""
        order = sorted(range(len(lens)), key=lambda i: lens[i]); s = 0
        while s < len(order):
            L = round_len(lens[order[s]])
            e = s
            while e < len(order) and round_len(lens[order[e]]) == L and (e - s + 1) * L <= max(self.token_budget, L) and e - s < self.max_batch:
                e += 1
            yield order[s:e], L; s = e

    def forward(self, ids_list, L):
        B = len(ids_list); a = np.zeros((B, L), np.int32); m = np.zeros((B, L), np.int32)
        for r, ids in enumerate(ids_list): a[r, :len(ids)] = ids; m[r, :len(ids)] = 1
        return self.fwd(mx.array(a), mx.array(m), L > 1536 and L % 512 == 0)

    def embed_ids(self, ids, dim=None):
        """Embed token-id lists; one batch is built while the previous one runs on the GPU (two in flight)."""
        n = len(ids); out = np.zeros((n, dim or 768), np.float32); prev = None
        for idx, L in self.batches([len(x) for x in ids]):
            e = self.forward([ids[i] for i in idx], L)
            if dim and dim < e.shape[1]:
                e = e[:, :dim]; e = e / mx.linalg.norm(e, axis=-1, keepdims=True)
            mx.async_eval(e)
            if prev: out[prev[0]] = np.array(prev[1])
            prev = (idx, e)
        if prev: out[prev[0]] = np.array(prev[1])
        return out

    def embed(self, texts, task=None, dim=None, max_tokens=8192):
        p = PREFIX[task] if task else ""
        return self.embed_ids(self.tokenize([p + t for t in texts], max_tokens), dim)
