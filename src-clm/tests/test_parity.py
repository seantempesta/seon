"""Parity of the MLX port with the reference implementation.

* heads: MLX heads vs reference ``clm.heads.HeadPair`` (torch) on the same inputs;
* encoder: MLX last-token vectors vs Hugging Face ``Qwen3Model`` (torch, MPS, bf16);
* batching: a text embedded alone equals the same text inside a padded batch;
* end to end: the README's tides example ranks the Moon first.
"""
from __future__ import annotations

import os

import numpy as np
import pytest
from clm.heads import DEFAULT_CKPT_DIR, HF_FILE

PT = os.path.join(DEFAULT_CKPT_DIR, HF_FILE)
ST = os.path.splitext(PT)[0] + ".safetensors"
ENCODER = os.environ.get("CLM_ENCODER", "Qwen/Qwen3-8B")
TEXTS = ["What causes tides on Earth?",
         "The Moon's gravitational pull.",
         "(defn add [a b] (+ a b))\n;; => #'user/add\n(add 1 2)\n;; => 3" * 20,
         "Customer: my invoice was charged twice and nobody answers the phone!"]

needs_head = pytest.mark.skipif(not os.path.exists(PT), reason=f"missing {PT} (clm-download)")


@pytest.fixture(scope="session")
def st_head():
    from seon_clm.convert import convert
    return ST if os.path.exists(ST) else convert(PT, ST)


@pytest.fixture(scope="session")
def encoder():
    from seon_clm.encoder import Encoder
    return Encoder(ENCODER)


@needs_head
def test_heads_match_reference(st_head):
    from clm.heads import HeadPair as Ref
    from seon_clm.heads import HeadPair
    x = np.random.default_rng(0).standard_normal((16, 4096)).astype(np.float32)
    x /= np.linalg.norm(x, axis=1, keepdims=True)
    ref, got = Ref("ref", PT, "cpu").ensure(), HeadPair("mlx", st_head).ensure()
    rs, ra = ref.project(x, x)
    for side, want in (("state", rs), ("action", ra)):   # float32 kernels differ at ~1e-4 per element
        z = got.project(side, x)
        assert np.abs(z - want).max() < 1e-3 and (z * want).sum(1).min() > 0.99999
    assert abs(got.scale - ref.scale) < 1e-4


def test_encoder_matches_transformers(encoder):
    import torch
    from transformers import AutoModel
    ref = AutoModel.from_pretrained(ENCODER, torch_dtype=torch.bfloat16).to("mps").eval()
    got, _ = encoder.embed(TEXTS)
    with torch.no_grad():
        for text, v in zip(TEXTS, got):
            ids = torch.tensor([encoder.ids(text)], device="mps")
            h = ref(input_ids=ids).last_hidden_state[0, -1].float().cpu().numpy()
            cos = float(v @ (h / np.linalg.norm(h)))
            assert cos > 0.995, (text[:40], cos)
    del ref
    torch.mps.empty_cache()


def test_padding_does_not_change_a_vector(encoder):
    alone = np.stack([encoder.embed([t])[0][0] for t in TEXTS])
    batched, _ = encoder.embed(TEXTS)
    assert (alone * batched).sum(1).min() > 0.999


@needs_head
def test_rank_end_to_end(encoder, st_head):
    from seon_clm.engine import Engine
    engine = Engine(encoder, checkpoint=st_head)
    ranked = engine.rank("What causes tides on Earth?",
                         ["The Moon's gravitational pull.", "Photosynthesis in plants.", "Because the Earth is round."])
    assert ranked[0]["candidate"] == "The Moon's gravitational pull."
    scored = engine.score("What causes tides on Earth?", ["Photosynthesis in plants.", "The Moon's gravitational pull."])
    assert scored["scored"][0]["index"] == 1
