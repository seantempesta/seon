"""DiffusionGemma model in MLX, loading the 8-bit mlx-community checkpoint.

Reference: reference-code/transformers/src/transformers/models/diffusion_gemma/
modeling_diffusion_gemma.py (encoder/decoder share all layer weights; the MLX
checkpoint stores them ONCE under model.decoder.*, plus per-tree layer_scalar
buffers and the vision tower, which we skip — text only).

Semantics mirrored exactly:
- RMSNorm: fp32 x * (mean(x^2)+eps)^-0.5 [* weight], cast back.
- Attention: q/k/v per-head RMSNorm (v scale-less), RoPE on q/k only,
  scale=1.0 (the q_norm replaces 1/sqrt(d)); sliding layers head_dim=256
  kv_heads=num_key_value_heads; global layers head_dim=512
  kv_heads=num_global_key_value_heads and V is derived from k_proj output
  (no v_proj), v_norm'd, NO rope.
- RoPE: sliding = default theta 1e4 over 256 dims; full = "proportional":
  rope_angles = 0.25*512//2 = 64 real freqs (theta 1e6, exponent /512),
  remaining 192 freq slots ZERO (identity / NoPE dims).
- Layer: attn block, then PARALLEL dense-MLP + MoE feed-forward branches
  (each with its own pre/post norms), summed, post-norm'd, residual,
  * layer_scalar.
- Router: scale-less RMSNorm -> *scale * hidden^-0.5 -> proj -> fp32 softmax
  -> top-k(8) -> renormalize -> * per_expert_scale[idx].
- Embeddings scaled by bf16(sqrt(hidden)); lm_head tied to embeddings;
  final logits fp32 tanh-softcapped at 30.
"""

import json
import glob
import math
from dataclasses import dataclass, field

import mlx.core as mx

GROUP_SIZE = 64
BITS = 8


@dataclass
class DGConfig:
    num_hidden_layers: int = 30
    num_attention_heads: int = 16
    num_key_value_heads: int = 8
    num_global_key_value_heads: int = 8
    head_dim: int = 256
    global_head_dim: int = 512
    hidden_size: int = 2816
    intermediate_size: int = 2112
    moe_intermediate_size: int = 704
    num_experts: int = 128
    top_k_experts: int = 8
    vocab_size: int = 262144
    rms_norm_eps: float = 1e-6
    sliding_window: int = 1024
    final_logit_softcapping: float = 30.0
    canvas_length: int = 256
    layer_types: list = field(default_factory=list)
    rope_parameters: dict = field(default_factory=dict)
    eos_token_id: int = 1
    pad_token_id: int = 0

    @classmethod
    def from_snapshot(cls, path):
        with open(f"{path}/config.json") as f:
            raw = json.load(f)
        t = raw["text_config"]
        return cls(
            num_hidden_layers=t["num_hidden_layers"],
            num_attention_heads=t["num_attention_heads"],
            num_key_value_heads=t["num_key_value_heads"],
            num_global_key_value_heads=t["num_global_key_value_heads"],
            head_dim=t["head_dim"],
            global_head_dim=t["global_head_dim"],
            hidden_size=t["hidden_size"],
            intermediate_size=t["intermediate_size"],
            moe_intermediate_size=t["moe_intermediate_size"],
            num_experts=t["num_experts"],
            top_k_experts=t["top_k_experts"],
            vocab_size=t["vocab_size"],
            rms_norm_eps=t["rms_norm_eps"],
            sliding_window=t["sliding_window"],
            final_logit_softcapping=t["final_logit_softcapping"],
            canvas_length=raw["canvas_length"],
            layer_types=t["layer_types"],
            rope_parameters=t["rope_parameters"],
            pad_token_id=t.get("pad_token_id", 0),
        )


_ONES = {}


def rms_norm(x, weight, eps):
    """fp32 RMS norm matching DiffusionGemmaRMSNorm (weight is a plain scale).

    Uses the fused mx.fast.rms_norm kernel (fp32 accumulation internally,
    weight multiplied before the downcast — same numerics as the reference).
    Scale-less norms pass a cached ones vector.
    """
    if weight is None:
        key = (x.shape[-1], x.dtype)
        if key not in _ONES:
            _ONES[key] = mx.ones(x.shape[-1], dtype=x.dtype)
        weight = _ONES[key]
    return mx.fast.rms_norm(x, weight.astype(x.dtype), eps)


def qlinear(x, w):
    """x @ W.T for an 8-bit affine-quantized weight dict {weight,scales,biases}."""
    return mx.quantized_matmul(
        x, w["weight"], w["scales"], w["biases"],
        transpose=True, group_size=GROUP_SIZE, bits=BITS,
    )


def gelu_tanh(x):
    """gelu_pytorch_tanh."""
    xf = x.astype(mx.float32)
    out = 0.5 * xf * (1.0 + mx.tanh(0.7978845608028654 * (xf + 0.044715 * xf ** 3)))
    return out.astype(x.dtype)


def _rotate_half(x):
    d = x.shape[-1] // 2
    return mx.concatenate([-x[..., d:], x[..., :d]], axis=-1)


def _apply_rope(x, cos, sin):
    """x: [B, L, H, D]; cos/sin: [L, D] (broadcast over B, H)."""
    cos = cos[None, :, None, :]
    sin = sin[None, :, None, :]
    return (x * cos) + (_rotate_half(x) * sin)


class Rope:
    """Precomputes per-layer-type inv_freq; emits (cos, sin) for position ids."""

    def __init__(self, cfg: DGConfig):
        self.inv_freq = {}
        # sliding: default rope over head_dim
        p = cfg.rope_parameters["sliding_attention"]
        dim = cfg.head_dim
        self.inv_freq["sliding_attention"] = 1.0 / (
            p["rope_theta"] ** (mx.arange(0, dim, 2, dtype=mx.float32) / dim)
        )
        # full: proportional rope over global_head_dim with partial factor
        p = cfg.rope_parameters["full_attention"]
        dim = cfg.global_head_dim
        rope_angles = int(p.get("partial_rotary_factor", 1.0) * dim // 2)
        rotated = 1.0 / (
            p["rope_theta"] ** (mx.arange(0, 2 * rope_angles, 2, dtype=mx.float32) / dim)
        )
        nope = dim // 2 - rope_angles
        self.inv_freq["full_attention"] = (
            mx.concatenate([rotated, mx.zeros(nope, dtype=mx.float32)])
            if nope > 0 else rotated
        )

    def __call__(self, position_ids, layer_type, dtype):
        """position_ids: [L] int -> cos, sin [L, head_dim] in `dtype`."""
        freqs = position_ids.astype(mx.float32)[:, None] * self.inv_freq[layer_type][None, :]
        emb = mx.concatenate([freqs, freqs], axis=-1)
        return mx.cos(emb).astype(dtype), mx.sin(emb).astype(dtype)


class Attention:
    """One attention layer; weights shared between encoder and decoder passes."""

    def __init__(self, cfg: DGConfig, layer_idx: int, weights: dict):
        self.layer_type = cfg.layer_types[layer_idx]
        self.is_sliding = self.layer_type == "sliding_attention"
        self.head_dim = cfg.head_dim if self.is_sliding else cfg.global_head_dim
        self.n_heads = cfg.num_attention_heads
        self.n_kv = cfg.num_key_value_heads if self.is_sliding else cfg.num_global_key_value_heads
        self.sliding_window = cfg.sliding_window if self.is_sliding else None
        self.eps = cfg.rms_norm_eps
        self.w = weights  # q_proj/k_proj/(v_proj)/o_proj quant dicts + q_norm/k_norm weights

    def _qkv(self, x, cos, sin):
        B, L, _ = x.shape
        q = qlinear(x, self.w["q_proj"]).reshape(B, L, self.n_heads, self.head_dim)
        q = rms_norm(q, self.w["q_norm"], self.eps)
        q = _apply_rope(q, cos, sin)

        k = qlinear(x, self.w["k_proj"]).reshape(B, L, self.n_kv, self.head_dim)
        # V: from v_proj on sliding layers; from PRE-norm k on global layers
        if "v_proj" in self.w:
            v = qlinear(x, self.w["v_proj"]).reshape(B, L, self.n_kv, self.head_dim)
        else:
            v = k
        k = rms_norm(k, self.w["k_norm"], self.eps)
        k = _apply_rope(k, cos, sin)
        v = rms_norm(v, None, self.eps)  # v_norm is scale-less

        # -> [B, H, L, D]
        return (q.transpose(0, 2, 1, 3), k.transpose(0, 2, 1, 3), v.transpose(0, 2, 1, 3))

    def encode(self, x, cos, sin, cache, mask):
        """Causal (sliding-windowed) self-attention; appends K/V to `cache`."""
        q, k, v = self._qkv(x, cos, sin)
        if cache["k"] is not None:
            k = mx.concatenate([cache["k"], k], axis=2)
            v = mx.concatenate([cache["v"], v], axis=2)
        cache["k"], cache["v"] = k, v
        out = mx.fast.scaled_dot_product_attention(q, k, v, scale=1.0, mask=mask)
        B, _, L, _ = out.shape
        out = out.transpose(0, 2, 1, 3).reshape(B, L, -1)
        return qlinear(out, self.w["o_proj"])

    def decode(self, x, cos, sin, cache):
        """Bidirectional canvas attention over [encoder-cache slice + canvas].

        Read-only w.r.t. the cache. Sliding layers see the last
        (sliding_window - 1) cached tokens (mirrors the non-compiled branch of
        create_diffusion_decoder_attention_mask); full layers see everything.
        """
        q, k, v = self._qkv(x, cos, sin)
        ck, cv = cache["k"], cache["v"]
        if ck is not None:
            if self.is_sliding and ck.shape[2] >= self.sliding_window:
                start = ck.shape[2] - self.sliding_window + 1
                ck, cv = ck[:, :, start:], cv[:, :, start:]
            k = mx.concatenate([ck, k], axis=2)
            v = mx.concatenate([cv, v], axis=2)
        out = mx.fast.scaled_dot_product_attention(q, k, v, scale=1.0, mask=None)
        B, _, L, _ = out.shape
        out = out.transpose(0, 2, 1, 3).reshape(B, L, -1)
        return qlinear(out, self.w["o_proj"])


class Layer:
    """One transformer layer: attention + parallel dense-MLP / MoE branches."""

    def __init__(self, cfg: DGConfig, layer_idx: int, weights: dict):
        self.cfg = cfg
        self.eps = cfg.rms_norm_eps
        self.w = weights
        self.attn = Attention(cfg, layer_idx, weights["self_attn"])

    def _ff(self, h):
        """The dual feed-forward: dense MLP branch + MoE branch, summed."""
        w = self.w
        residual = h
        # dense branch
        x = rms_norm(h, w["pre_feedforward_layernorm"], self.eps)
        dense = qlinear(gelu_tanh(qlinear(x, w["mlp.gate_proj"])) * qlinear(x, w["mlp.up_proj"]),
                        w["mlp.down_proj"])
        h1 = rms_norm(dense, w["post_feedforward_layernorm_1"], self.eps)

        # MoE branch — routing sees the RAW residual; experts see it normed
        B, L, D = residual.shape
        flat = residual.reshape(B * L, D)
        routed_in = rms_norm(flat, w["pre_feedforward_layernorm_2"], self.eps)
        top_w, top_i = self._route(flat)
        moe = self._experts(routed_in, top_i, top_w).reshape(B, L, D)
        h2 = rms_norm(moe, w["post_feedforward_layernorm_2"], self.eps)

        out = rms_norm(h1 + h2, w["post_feedforward_layernorm"], self.eps)
        return residual + out

    def _route(self, flat):
        w = self.w
        x = rms_norm(flat, None, self.eps)  # router norm is scale-less
        x = x * w["router.scale"].astype(x.dtype) * (self.cfg.hidden_size ** -0.5)
        scores = qlinear(x, w["router.proj"])
        probs = mx.softmax(scores.astype(mx.float32), axis=-1)
        k = self.cfg.top_k_experts
        top_i = mx.argpartition(-probs, kth=k - 1, axis=-1)[..., :k]
        top_w = mx.take_along_axis(probs, top_i, axis=-1)
        top_w = top_w / mx.sum(top_w, axis=-1, keepdims=True)
        top_w = top_w * w["router.per_expert_scale"].astype(mx.float32)[top_i]
        return top_w, top_i

    def _experts(self, x, top_i, top_w):
        """Gather-quantized MoE: x [T, D], top_i/top_w [T, K] -> [T, D].

        Token-expert pairs are sorted by expert id so gather_qmm (with
        sorted_indices=True) streams each expert's weights once instead of
        random-accessing them per pair — the difference between ~23 ms and
        ~2 ms per layer.
        """
        w = self.w
        T, K = top_i.shape
        flat_i = top_i.reshape(-1)
        order = mx.argsort(flat_i)
        inv = mx.argsort(order)
        xe = x[order // K][:, None, None, :]        # [N, 1, 1, D] in expert order
        sorted_e = flat_i[order][:, None]           # [N, 1] ascending expert ids
        gu = mx.gather_qmm(
            xe, w["experts.gate_up_proj"]["weight"],
            w["experts.gate_up_proj"]["scales"], w["experts.gate_up_proj"]["biases"],
            rhs_indices=sorted_e, transpose=True, group_size=GROUP_SIZE, bits=BITS,
            sorted_indices=True,
        )  # [N, 1, 1, 2*moe_inter]
        gate, up = mx.split(gu, 2, axis=-1)
        act = gelu_tanh(gate) * up
        down = mx.gather_qmm(
            act, w["experts.down_proj"]["weight"],
            w["experts.down_proj"]["scales"], w["experts.down_proj"]["biases"],
            rhs_indices=sorted_e, transpose=True, group_size=GROUP_SIZE, bits=BITS,
            sorted_indices=True,
        )  # [N, 1, 1, D]
        down = down.reshape(T * K, -1)[inv].reshape(T, K, -1)
        return mx.sum(down * top_w.astype(down.dtype)[..., None], axis=1)

    def _block(self, h, attn_out, layer_scalar):
        h = h + rms_norm(attn_out, self.w["post_attention_layernorm"], self.eps)
        h = self._ff(h)
        return h * layer_scalar.astype(h.dtype)

    def encode(self, h, cos, sin, cache, mask):
        x = rms_norm(h, self.w["input_layernorm"], self.eps)
        return self._block(h, self.attn.encode(x, cos, sin, cache, mask),
                           self.w["encoder_layer_scalar"])

    def decode(self, h, cos, sin, cache):
        x = rms_norm(h, self.w["input_layernorm"], self.eps)
        return self._block(h, self.attn.decode(x, cos, sin, cache),
                           self.w["decoder_layer_scalar"])


class DiffusionGemmaMLX:
    """The full model: shared layers, embed/lm_head, self-conditioning."""

    def __init__(self, cfg: DGConfig, weights: dict):
        self.cfg = cfg
        self.w = weights
        self.rope = Rope(cfg)
        self.layers = [Layer(cfg, i, weights["layers"][i]) for i in range(cfg.num_hidden_layers)]
        self.eps = cfg.rms_norm_eps
        self.embed_scale = mx.array(cfg.hidden_size ** 0.5).astype(mx.bfloat16)
        # Dequantized embedding table, cached once — used for the soft-embedding
        # matmul in self-conditioning (probs @ E). ~1.4 GB bf16.
        e = weights["embed_tokens"]
        self.embed_dequant = mx.dequantize(
            e["weight"], e["scales"], e["biases"], group_size=GROUP_SIZE, bits=BITS
        ).astype(mx.bfloat16)
        self.dtype = mx.bfloat16
        self._core = None  # lazily-built mx.compile'd decode core

    # ---- embeddings / head ----

    def embed(self, ids):
        """ids [B, L] -> scaled embeddings [B, L, D]."""
        return self.embed_dequant[ids] * self.embed_scale

    def lm_head(self, h):
        """h [B, L, D] -> fp32 softcapped logits [B, L, V]."""
        e = self.w["embed_tokens"]
        logits = mx.quantized_matmul(
            h, e["weight"], e["scales"], e["biases"],
            transpose=True, group_size=GROUP_SIZE, bits=BITS,
        ).astype(mx.float32)
        cap = self.cfg.final_logit_softcapping
        return mx.tanh(logits / cap) * cap

    # ---- caches / masks ----

    def new_cache(self):
        return [{"k": None, "v": None} for _ in range(self.cfg.num_hidden_layers)]

    def _encoder_mask(self, q_len, past_len, layer_type):
        """Additive causal (optionally sliding-window) mask, or None."""
        if q_len == 1:
            return None
        pos_q = mx.arange(past_len, past_len + q_len)[:, None]
        pos_k = mx.arange(0, past_len + q_len)[None, :]
        allowed = pos_k <= pos_q
        if layer_type == "sliding_attention":
            allowed = allowed & (pos_k > pos_q - self.cfg.sliding_window)
        return mx.where(allowed, mx.array(0.0, dtype=self.dtype),
                        mx.array(-mx.inf, dtype=self.dtype))

    # ---- forward passes ----

    def encode(self, ids, cache, past_len):
        """Causal encode of `ids` (prompt or a committed canvas) into `cache`."""
        B, L = ids.shape
        h = self.embed(ids)
        pos = mx.arange(past_len, past_len + L)
        cs = {lt: self.rope(pos, lt, h.dtype) for lt in ("sliding_attention", "full_attention")}
        masks = {lt: self._encoder_mask(L, past_len, lt)
                 for lt in ("sliding_attention", "full_attention")}
        for i, layer in enumerate(self.layers):
            lt = self.cfg.layer_types[i]
            h = layer.encode(h, *cs[lt], cache[i], masks[lt])
        return rms_norm(h, self.w["final_norm"], self.eps)

    def decode(self, canvas_ids, cache, canvas_start, self_conditioning_logits=None):
        """One denoiser forward: canvas + read-only cache -> fp32 logits.

        The layer stack runs through an mx.compile'd core (recompiles when the
        cache length changes — i.e. once per canvas block; all 48 denoise steps
        within a block reuse the compiled graph). The soft-embedding matmul
        stays outside so the core has a fixed signature.
        """
        B, L = canvas_ids.shape
        if self_conditioning_logits is not None:
            probs = mx.softmax(self_conditioning_logits.astype(mx.float32), axis=-1)
            soft = (probs.astype(self.dtype) @ self.embed_dequant) * self.embed_scale
        else:
            soft = mx.zeros((B, L, self.cfg.hidden_size), dtype=self.dtype)
        pos = mx.arange(canvas_start, canvas_start + L)
        ks = [c["k"] for c in cache]
        vs = [c["v"] for c in cache]
        if self._core is None:
            self._core = mx.compile(self._decode_core)
        return self._core(canvas_ids, soft, pos, ks, vs)

    def _decode_core(self, canvas_ids, soft, pos, ks, vs):
        h = self.embed(canvas_ids)
        sc = self.w["self_conditioning"]
        normed = rms_norm(soft, sc["pre_norm"], self.eps)
        sig = qlinear(gelu_tanh(qlinear(normed, sc["gate_proj"])) * qlinear(normed, sc["up_proj"]),
                      sc["down_proj"])
        h = rms_norm(h + sig, None, self.eps)  # post_norm is scale-less

        cs = {lt: self.rope(pos, lt, h.dtype) for lt in ("sliding_attention", "full_attention")}
        for i, layer in enumerate(self.layers):
            lt = self.cfg.layer_types[i]
            h = layer.decode(h, *cs[lt], {"k": ks[i], "v": vs[i]})
        h = rms_norm(h, self.w["final_norm"], self.eps)
        return self.lm_head(h)


# ---- weight loading ----

def _collect(shards, prefix):
    """Pull {weight,scales,biases} (or a lone array) for a tensor prefix."""
    out = {}
    for suffix in ("weight", "scales", "biases"):
        key = f"{prefix}.{suffix}"
        if key in shards:
            out[suffix] = shards[key]
    if not out:
        raise KeyError(f"no tensors under {prefix}")
    return out if "scales" in out else out["weight"]


def load_model(snapshot_path):
    """Load config + weights from the mlx-community snapshot directory."""
    cfg = DGConfig.from_snapshot(snapshot_path)
    shards = {}
    for f in sorted(glob.glob(f"{snapshot_path}/model-*.safetensors")):
        shards.update(mx.load(f))

    D = "model.decoder"
    E = "model.encoder.language_model"
    layers = []
    for i in range(cfg.num_hidden_layers):
        L = f"{D}.layers.{i}"
        attn = {
            "q_proj": _collect(shards, f"{L}.self_attn.q_proj"),
            "k_proj": _collect(shards, f"{L}.self_attn.k_proj"),
            "o_proj": _collect(shards, f"{L}.self_attn.o_proj"),
            "q_norm": shards[f"{L}.self_attn.q_norm.weight"],
            "k_norm": shards[f"{L}.self_attn.k_norm.weight"],
        }
        if f"{L}.self_attn.v_proj.weight" in shards:
            attn["v_proj"] = _collect(shards, f"{L}.self_attn.v_proj")
        layers.append({
            "self_attn": attn,
            "input_layernorm": shards[f"{L}.input_layernorm.weight"],
            "post_attention_layernorm": shards[f"{L}.post_attention_layernorm.weight"],
            "pre_feedforward_layernorm": shards[f"{L}.pre_feedforward_layernorm.weight"],
            "pre_feedforward_layernorm_2": shards[f"{L}.pre_feedforward_layernorm_2.weight"],
            "post_feedforward_layernorm": shards[f"{L}.post_feedforward_layernorm.weight"],
            "post_feedforward_layernorm_1": shards[f"{L}.post_feedforward_layernorm_1.weight"],
            "post_feedforward_layernorm_2": shards[f"{L}.post_feedforward_layernorm_2.weight"],
            "mlp.gate_proj": _collect(shards, f"{L}.mlp.gate_proj"),
            "mlp.up_proj": _collect(shards, f"{L}.mlp.up_proj"),
            "mlp.down_proj": _collect(shards, f"{L}.mlp.down_proj"),
            "router.proj": _collect(shards, f"{L}.router.proj"),
            "router.scale": shards[f"{L}.router.scale"],
            "router.per_expert_scale": shards[f"{L}.router.per_expert_scale"],
            "experts.gate_up_proj": _collect(shards, f"{L}.experts.gate_up_proj"),
            "experts.down_proj": _collect(shards, f"{L}.experts.down_proj"),
            "decoder_layer_scalar": shards[f"{L}.layer_scalar"],
            "encoder_layer_scalar": shards[f"{E}.layers.{i}.layer_scalar"],
        })

    weights = {
        "layers": layers,
        "embed_tokens": _collect(shards, f"{D}.embed_tokens"),
        "final_norm": shards[f"{D}.norm.weight"],
        "self_conditioning": {
            "pre_norm": shards[f"{D}.self_conditioning.pre_norm.weight"],
            "gate_proj": _collect(shards, f"{D}.self_conditioning.gate_proj"),
            "up_proj": _collect(shards, f"{D}.self_conditioning.up_proj"),
            "down_proj": _collect(shards, f"{D}.self_conditioning.down_proj"),
        },
    }
    return DiffusionGemmaMLX(cfg, weights)
