"""Offline unit tests — random tiny weights, no checkpoint, no network.

Pin the numerics that a transposition/ordering bug would silently break:
non-interleaved RoPE, ZCRMSNorm, and KV-cached decode == full-buffer decode
(the parity test then proves the whole stack against the JAX reference).
"""

import numpy as np
import pytest

mx = pytest.importorskip("mlx.core")

from seon_needle.model import (  # noqa: E402
    NeedleConfig,
    NeedleModel,
    apply_rope,
    make_causal_mask,
    make_padding_mask,
    rope_freqs,
    zcrms_norm,
)

TINY = NeedleConfig(vocab_size=97, d_model=32, num_heads=4, num_kv_heads=2,
                    num_encoder_layers=2, num_decoder_layers=2)


def tiny_weights(config, seed=0):
    """Random fp16 weights with the exact converted-checkpoint key paths."""
    rng = np.random.default_rng(seed)
    d, hd = config.d_model, config.head_dim
    kv = config.num_kv_heads * hd

    def w(*shape, scale=0.05):
        return rng.normal(0, scale, shape).astype(np.float16)

    def attn(prefix):
        return {
            f"{prefix}/q_proj/kernel": w(d, d),
            f"{prefix}/k_proj/kernel": w(d, kv),
            f"{prefix}/v_proj/kernel": w(d, kv),
            f"{prefix}/out_proj/kernel": w(d, d),
            f"{prefix}/q_norm/scale": w(hd),
            f"{prefix}/k_norm/scale": w(hd),
        }

    flat = {"embedding/embedding": w(config.vocab_size, d, scale=0.02),
            "encoder/final_norm/scale": w(d),
            "decoder/ZCRMSNorm_0/scale": w(d),
            "contrastive_hidden/kernel": w(d, d // 4),
            "contrastive_hidden/bias": w(d // 4),
            "contrastive_proj/kernel": w(d // 4, config.contrastive_dim),
            "log_temp": np.float16(0.0)}
    for i in range(config.num_encoder_layers):
        flat[f"encoder/layers/{i}/attn_gate"] = w()
        flat[f"encoder/layers/{i}/ZCRMSNorm_0/scale"] = w(d)
        flat.update(attn(f"encoder/layers/{i}/self_attn"))
    for i in range(config.num_decoder_layers):
        flat[f"decoder/layers/{i}/self_attn_gate"] = w()
        flat[f"decoder/layers/{i}/cross_attn_gate"] = w()
        flat[f"decoder/layers/{i}/ZCRMSNorm_0/scale"] = w(d)
        flat[f"decoder/layers/{i}/ZCRMSNorm_1/scale"] = w(d)
        flat.update(attn(f"decoder/layers/{i}/self_attn"))
        flat.update(attn(f"decoder/layers/{i}/cross_attn"))
    return {k: mx.array(v) for k, v in flat.items()}


def test_rope_non_interleaved():
    """Needle splits the head dim into HALVES (GPT-NeoX style), never
    interleaved even/odd pairs."""
    head_dim, T = 8, 5
    cos, sin = rope_freqs(head_dim, T, theta=10000.0)
    x = mx.array(np.random.default_rng(1).normal(size=(1, 1, T, head_dim)),
                 dtype=mx.float32)
    got = np.array(apply_rope(x, cos, sin))

    xn = np.array(x)
    freqs = 1.0 / (10000.0 ** (np.arange(0, head_dim, 2) / head_dim))
    ang = np.arange(T)[:, None] * freqs[None, :]
    c, s = np.cos(ang), np.sin(ang)
    x1, x2 = xn[..., :head_dim // 2], xn[..., head_dim // 2:]
    want = np.concatenate([x1 * c - x2 * s, x2 * c + x1 * s], axis=-1)
    np.testing.assert_allclose(got, want, rtol=1e-5)


def test_rope_offset_matches_slice():
    """apply_rope(x, offset=k) == rows k.. of the full-sequence rope."""
    head_dim, T = 8, 6
    cos, sin = rope_freqs(head_dim, T)
    x = mx.array(np.random.default_rng(2).normal(size=(1, 2, T, head_dim)),
                 dtype=mx.float32)
    full = np.array(apply_rope(x, cos, sin))
    tail = np.array(apply_rope(x[:, :, 4:, :], cos, sin, offset=4))
    np.testing.assert_allclose(tail, full[:, :, 4:, :], rtol=1e-5)


def test_zcrms_norm():
    """(1 + scale) * x / rms with rms in float32."""
    rng = np.random.default_rng(3)
    x = rng.normal(size=(2, 4, 16)).astype(np.float32)
    scale = rng.normal(0, 0.1, 16).astype(np.float32)
    got = np.array(zcrms_norm(mx.array(x), mx.array(scale)).astype(mx.float32))
    rms = np.sqrt(np.mean(x ** 2, axis=-1, keepdims=True) + 1e-6)
    want = (1 + scale) * x / rms
    np.testing.assert_allclose(got, want, rtol=2e-2, atol=2e-2)  # bf16 out


def test_cached_decode_matches_full_forward():
    """Greedy per-token KV-cached decode == whole-sequence teacher forcing."""
    model = NeedleModel(TINY, tiny_weights(TINY))
    rng = np.random.default_rng(4)
    src = mx.array(rng.integers(1, TINY.vocab_size, (2, 7)), dtype=mx.int32)
    tgt = mx.array(rng.integers(1, TINY.vocab_size, (2, 5)), dtype=mx.int32)

    src_mask = make_padding_mask(src, TINY.pad_token_id)
    encoder_out, enc_mask = model.encode(src, src_mask=src_mask)

    full = model.decode(tgt, encoder_out,
                        self_mask=make_causal_mask(tgt.shape[1]),
                        cross_mask=enc_mask)

    caches = model.new_caches()
    steps = []
    for pos in range(tgt.shape[1]):
        steps.append(model.decode(tgt[:, pos:pos + 1], encoder_out,
                                  cross_mask=enc_mask, offset=pos,
                                  caches=caches))
    stepped = mx.concatenate(steps, axis=1)
    # bf16 activations round-trip slightly differently per-step vs batched;
    # logits agree to ~1e-3 and the greedy path (argmax) must agree exactly
    np.testing.assert_allclose(np.array(full), np.array(stepped),
                               rtol=1e-2, atol=5e-3)
    assert (np.array(mx.argmax(full, axis=-1)) ==
            np.array(mx.argmax(stepped, axis=-1))).all()


def test_padding_mask_shape():
    src = mx.array([[5, 6, 0, 0]], dtype=mx.int32)
    m = make_padding_mask(src, 0)
    assert m.shape == (1, 1, 1, 4)
    assert np.array(m)[0, 0, 0].tolist() == [True, True, False, False]
