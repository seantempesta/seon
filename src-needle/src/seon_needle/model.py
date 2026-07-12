"""MLX inference port of needle's SimpleAttentionNetwork.

Faithful to reference-code/needle/needle/model/architecture.py — read that
file first; every numerics choice below mirrors a line there:

- ZCRMSNorm: (1 + scale) * x / rms, rms computed in float32, result cast
  back to bfloat16. Scale is checkpoint-initialized near 0.
- Gated residuals: x = residual + sigmoid(gate) * sublayer(x), one scalar
  gate per sublayer.
- GQA: 8 query heads / 4 kv heads; kv repeated to 8 before attention.
- Q/K ZCRMSNorm (qk-norm) applied per head BEFORE repeat and BEFORE RoPE.
- RoPE: NON-interleaved halves (GPT-NeoX style): x1 = first half of the
  head dim, x2 = second half, concat[x1*cos - x2*sin, x2*cos + x1*sin].
  cos/sin are float32, so roped q/k promote to float32 — attention runs
  in float32 wherever RoPE applies (matches jax weak-typing promotion).
- Decoder cross-attention has NO RoPE at all (rope=None in the reference).
- No FFN (no_feedforward=True in the shipped checkpoint).
- Shared embedding, tied output: logits = float32(x) @ embedding.T.
- Weights load as float16 (as stored) and cast to bfloat16, matching
  run.py's load_checkpoint.

Parameter tree: `self.w` holds the flax param tree verbatim (scanned
layers split per layer by convert.py), so checkpoint paths and code paths
are the same names.
"""

import json
import math
from dataclasses import dataclass

import mlx.core as mx
import mlx.nn as nn

from . import config as cfg_paths

DTYPE = mx.bfloat16
EPS = 1e-6


@dataclass
class NeedleConfig:
    vocab_size: int = 8192
    d_model: int = 512
    num_heads: int = 8
    num_kv_heads: int = 4
    num_encoder_layers: int = 12
    num_decoder_layers: int = 8
    max_seq_len: int = 1024
    pad_token_id: int = 0
    rope_theta: float = 10000.0
    contrastive_dim: int = 128

    @property
    def head_dim(self):
        return self.d_model // self.num_heads

    @classmethod
    def from_json(cls, path):
        with open(path) as f:
            raw = json.load(f)
        valid = cls.__dataclass_fields__.keys()
        return cls(**{k: v for k, v in raw.items() if k in valid})


def rope_freqs(head_dim, seq_len, theta=10000.0):
    """cos/sin tables, float32, shape (seq_len, head_dim // 2)."""
    freqs = 1.0 / (theta ** (mx.arange(0, head_dim, 2).astype(mx.float32) / head_dim))
    t = mx.arange(seq_len).astype(mx.float32)
    angles = t[:, None] * freqs[None, :]
    return mx.cos(angles), mx.sin(angles)


def apply_rope(x, cos, sin, offset=0):
    """Non-interleaved (half-split) RoPE. x: (B, H, T, head_dim)."""
    T = x.shape[2]
    c = cos[offset:offset + T][None, None, :, :]
    s = sin[offset:offset + T][None, None, :, :]
    half = x.shape[-1] // 2
    x1 = x[..., :half]
    x2 = x[..., half:]
    # float32 cos/sin promote the result to float32, as in jax.
    return mx.concatenate([x1 * c - x2 * s, x2 * c + x1 * s], axis=-1)


def zcrms_norm(x, scale):
    """Zero-centred RMSNorm: (1 + scale) * x / rms, rms in float32."""
    rms = mx.sqrt(mx.mean(x.astype(mx.float32) ** 2, axis=-1, keepdims=True) + EPS)
    return ((1 + scale) * x / rms).astype(DTYPE)


def make_padding_mask(tokens, pad_token_id):
    """(B, T) -> (B, 1, 1, T) bool."""
    return (tokens != pad_token_id)[:, None, None, :]


def make_causal_mask(seq_len):
    """(1, 1, T, T) bool lower-triangular."""
    i = mx.arange(seq_len)
    return (i[:, None] >= i[None, :])[None, None, :, :]


NEG_INF = float(mx.finfo(mx.float32).min)


def _attention(w, q_in, kv_in, num_heads, num_kv_heads, mask=None,
               rope=None, rope_q=True, q_offset=0, kv_cache=None):
    """One multi-head attention sublayer over the flax param subtree `w`.

    kv_cache: optional dict carrying pre-repeat k/v — either
      {"k": ..., "v": ...} frozen (cross-attn) or a mutable dict the
      new k/v get appended to (incremental self-attn decode).
    """
    B, Tq, D = q_in.shape
    head_dim = D // num_heads

    q = q_in @ w["q_proj"]["kernel"]
    q = q.reshape(B, Tq, num_heads, head_dim).transpose(0, 2, 1, 3)
    q = zcrms_norm(q, w["q_norm"]["scale"])
    if rope is not None and rope_q:
        q = apply_rope(q, *rope, offset=q_offset)

    if kv_in is None:
        # cross-attn with a populated frozen cache
        k, v = kv_cache["k"], kv_cache["v"]
    else:
        Tk = kv_in.shape[1]
        k = kv_in @ w["k_proj"]["kernel"]
        v = kv_in @ w["v_proj"]["kernel"]
        k = k.reshape(B, Tk, num_kv_heads, head_dim).transpose(0, 2, 1, 3)
        v = v.reshape(B, Tk, num_kv_heads, head_dim).transpose(0, 2, 1, 3)
        k = zcrms_norm(k, w["k_norm"]["scale"])
        if rope is not None:
            k = apply_rope(k, *rope, offset=q_offset if kv_cache is not None else 0)
        if kv_cache is not None:
            if "k" in kv_cache:  # append (incremental self-attn)
                k = mx.concatenate([kv_cache["k"], k], axis=2)
                v = mx.concatenate([kv_cache["v"], v], axis=2)
            kv_cache["k"], kv_cache["v"] = k, v

    repeats = num_heads // num_kv_heads
    if repeats > 1:
        k = mx.repeat(k, repeats, axis=1)
        v = mx.repeat(v, repeats, axis=1)

    # jax divides by a strong float32 scalar, promoting bf16 scores to f32
    scale = math.sqrt(head_dim)
    attn = (q @ k.transpose(0, 1, 3, 2)).astype(mx.float32) / scale
    if mask is not None:
        attn = mx.where(mask, attn, NEG_INF)
    attn = mx.softmax(attn, axis=-1)

    out = attn @ v.astype(mx.float32)
    out = out.transpose(0, 2, 1, 3).reshape(B, Tq, D).astype(DTYPE)
    return out @ w["out_proj"]["kernel"]


def _unflatten(flat):
    """'a/b/0/c' keys -> nested dicts; numeric segments become list indices."""
    tree = {}
    for key, arr in flat.items():
        parts = key.split("/")
        node = tree
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = arr
    # convert {"0": .., "1": ..} dicts to lists
    def listify(node):
        if not isinstance(node, dict):
            return node
        if node and all(k.isdigit() for k in node):
            return [listify(node[str(i)]) for i in range(len(node))]
        return {k: listify(v) for k, v in node.items()}
    return listify(tree)


class NeedleModel(nn.Module):
    """SimpleAttentionNetwork inference (+ trainable forward for finetune)."""

    def __init__(self, config: NeedleConfig, weights: dict):
        super().__init__()
        self.config = config
        # bf16 cast mirrors run.py load_checkpoint (pkl stores fp16)
        self.w = _unflatten({k: v.astype(DTYPE) for k, v in weights.items()})
        self._rope_cache = {}

    # -- pieces ------------------------------------------------------------

    def _rope(self, seq_len):
        # one table, grown to a power of two; apply_rope slices what it needs
        n = self._rope_cache.get("n", 0)
        if n < seq_len:
            n = max(512, 1 << (seq_len - 1).bit_length())
            self._rope_cache = {
                "n": n,
                "cos_sin": rope_freqs(self.config.head_dim, n, self.config.rope_theta),
            }
        return self._rope_cache["cos_sin"]

    def _embed(self, tokens):
        emb = self.w["embedding"]["embedding"]
        return emb[tokens] * math.sqrt(self.config.d_model)

    def encode(self, src, src_mask=None):
        """Encoder forward. src: (B, T) int. Returns (encoder_out, src_mask)."""
        c = self.config
        x = self._embed(src)
        rope = self._rope(src.shape[1])
        for lw in self.w["encoder"]["layers"]:
            gate = mx.sigmoid(lw["attn_gate"]).astype(DTYPE)
            h = zcrms_norm(x, lw["ZCRMSNorm_0"]["scale"])
            h = _attention(lw["self_attn"], h, h, c.num_heads, c.num_kv_heads,
                           mask=src_mask, rope=rope)
            x = x + gate * h
        x = zcrms_norm(x, self.w["encoder"]["final_norm"]["scale"])
        return x, src_mask

    def _decoder_x(self, tgt, encoder_out, self_mask=None, cross_mask=None,
                   offset=0, caches=None):
        """Decoder hidden states in float32. caches: per-layer dict list."""
        c = self.config
        x = self._embed(tgt)
        rope = self._rope(offset + tgt.shape[1])
        for i, lw in enumerate(self.w["decoder"]["layers"]):
            self_cache = caches[i]["self"] if caches is not None else None
            cross_cache = caches[i]["cross"] if caches is not None else None

            gate = mx.sigmoid(lw["self_attn_gate"]).astype(DTYPE)
            h = zcrms_norm(x, lw["ZCRMSNorm_0"]["scale"])
            h = _attention(lw["self_attn"], h, h, c.num_heads, c.num_kv_heads,
                           mask=self_mask, rope=rope, q_offset=offset,
                           kv_cache=self_cache)
            x = x + gate * h

            gate = mx.sigmoid(lw["cross_attn_gate"]).astype(DTYPE)
            h = zcrms_norm(x, lw["ZCRMSNorm_1"]["scale"])
            use_cached = cross_cache is not None and "k" in cross_cache
            h = _attention(lw["cross_attn"], h,
                           None if use_cached else encoder_out,
                           c.num_heads, c.num_kv_heads, mask=cross_mask,
                           kv_cache=cross_cache)
            x = x + gate * h

        x = zcrms_norm(x, self.w["decoder"]["ZCRMSNorm_0"]["scale"])
        return x.astype(mx.float32)

    def decode(self, tgt, encoder_out, self_mask=None, cross_mask=None,
               offset=0, caches=None):
        """Decoder forward -> float32 logits (tied to the embedding)."""
        x = self._decoder_x(tgt, encoder_out, self_mask=self_mask,
                            cross_mask=cross_mask, offset=offset, caches=caches)
        emb = self.w["embedding"]["embedding"]
        return x @ emb.T.astype(mx.float32)

    def new_caches(self):
        """Fresh per-decoder-layer kv caches for incremental decode."""
        return [{"self": {}, "cross": {}} for _ in self.w["decoder"]["layers"]]

    # -- whole-sequence forward (teacher forcing; used by finetune) ---------

    def __call__(self, src, tgt, src_mask=None, tgt_mask=None, cross_mask=None):
        encoder_out, enc_mask = self.encode(src, src_mask=src_mask)
        cm = cross_mask if cross_mask is not None else enc_mask
        return self.decode(tgt, encoder_out, self_mask=tgt_mask, cross_mask=cm)

    # -- contrastive head ----------------------------------------------------

    def encode_contrastive(self, tokens):
        """L2-normalized contrastive embedding, (B, contrastive_dim)."""
        src_mask = make_padding_mask(tokens, self.config.pad_token_id)
        encoder_out, enc_mask = self.encode(tokens, src_mask=src_mask)
        mask_2d = enc_mask[:, 0, 0, :].astype(encoder_out.dtype)
        summed = mx.sum(encoder_out * mask_2d[:, :, None], axis=1)
        counts = mx.maximum(mx.sum(mask_2d, axis=1, keepdims=True), 1.0)
        pooled = summed / counts
        ch = self.w["contrastive_hidden"]
        h = nn.relu(pooled @ ch["kernel"] + ch["bias"])
        projected = h @ self.w["contrastive_proj"]["kernel"]
        # safe L2 norm (see the reference's NaN-at-origin note)
        denom = mx.sqrt(mx.sum(projected.astype(mx.float32) ** 2,
                               axis=-1, keepdims=True) + 1e-12)
        return projected / denom.astype(projected.dtype)


def load_model(weights_path=None, config_path=None):
    """Load the converted checkpoint into an MLX NeedleModel."""
    wp = weights_path or cfg_paths.weights_path()
    cp = config_path or cfg_paths.model_config_path()
    if not wp.exists():
        raise FileNotFoundError(
            f"{wp} missing; run `python -m seon_needle.convert` first")
    weights = mx.load(str(wp))
    config = NeedleConfig.from_json(cp)
    return NeedleModel(config, weights)
