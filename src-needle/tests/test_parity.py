"""Parity proof: original JAX needle (CPU) vs the MLX port, greedy-token-exact.

The reference implementation is imported from reference-code/needle via
sys.path — NEVER copied. jax/flax are test-only extras. Both sides get the
byte-identical encoder token lists (built once by our build_encoder_input)
and decode greedily, unconstrained, up to MAX_GEN tokens.

bf16 numerics may cause rare argmax flips; anything below ~95% exact-match
usually means a real transposition/ordering bug, not noise.
"""

import pickle
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
SRC_NEEDLE = HERE.parent
REPO_ROOT = SRC_NEEDLE.parent
REF_NEEDLE = REPO_ROOT / "reference-code" / "needle"

sys.path.insert(0, str(HERE))  # parity_inputs
from parity_inputs import PARITY_INPUTS  # noqa: E402

jax = pytest.importorskip("jax", reason="parity needs the [test] extra (jax/flax)")

from seon_needle import config as cfg  # noqa: E402
from seon_needle.generate import build_encoder_input, generate_batch  # noqa: E402
from seon_needle.model import load_model  # noqa: E402
from seon_needle.tokenizer import EOS_ID, PAD_ID, load_tokenizer  # noqa: E402

MAX_GEN = 128
MIN_EXACT_RATE = 0.95

pytestmark = pytest.mark.skipif(
    not cfg.weights_path().exists() or not REF_NEEDLE.exists(),
    reason="needs converted checkpoint (python -m seon_needle.convert) "
           "and the reference-code/needle submodule",
)


def _ref_architecture():
    """Import the ORIGINAL architecture.py from reference-code, bypassing
    needle/__init__.py (which drags in the heavyweight `datasets` dep)."""
    import importlib.util

    path = REF_NEEDLE / "needle" / "model" / "architecture.py"
    spec = importlib.util.spec_from_file_location("needle_ref_architecture", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _load_reference(arch):
    """Load the ORIGINAL flax model + bf16 params from reference-code."""
    import jax.numpy as jnp

    with open(cfg.pkl_path(), "rb") as f:
        data = pickle.load(f)
    # identical to run.py load_checkpoint (not imported: run.py pulls the
    # heavyweight datasets dependency via needle.dataset.dataset)
    params = jax.tree.map(lambda x: jnp.array(x, dtype=jnp.bfloat16), data["params"])
    config = arch.TransformerConfig(**data["config"])
    return arch.SimpleAttentionNetwork(config), params


def _ref_greedy(arch, model, params, enc_tokens, max_gen_len=MAX_GEN):
    """run.py's unconstrained greedy loop, prefix-length buffer (identical
    logits: causal mask + rope slicing make trailing pad positions inert)."""
    import jax.numpy as jnp

    enc_input = jnp.array([enc_tokens])
    src_mask = arch.make_padding_mask(enc_input, PAD_ID)
    encoder_out, enc_mask = model.apply(
        {"params": params}, enc_input, src_mask=src_mask, method="encode")

    toks = [EOS_ID]
    out = []
    for i in range(max_gen_len - 1):
        dec = jnp.array([toks])
        logits = model.apply(
            {"params": params}, dec, encoder_out,
            self_mask=arch.make_causal_mask(len(toks)), cross_mask=enc_mask,
            method="decode")
        t = int(jnp.argmax(logits[0, i]))
        if t == EOS_ID:
            break
        out.append(t)
        toks.append(t)
    return out


def test_greedy_parity():
    tokenizer = load_tokenizer()
    arch = _ref_architecture()
    ref_model, ref_params = _load_reference(arch)
    mlx_model = load_model()

    queries = [q for q, _ in PARITY_INPUTS]
    tools = [t for _, t in PARITY_INPUTS]
    mlx_out = generate_batch(mlx_model, tokenizer, queries, tools,
                             max_gen_len=MAX_GEN)["tokens"]

    exact = 0
    for i, (q, t) in enumerate(PARITY_INPUTS):
        enc_tokens = build_encoder_input(tokenizer, q, t)
        ref_tokens = _ref_greedy(arch, ref_model, ref_params, enc_tokens)
        if ref_tokens == mlx_out[i]:
            exact += 1
        else:
            div = next((j for j, (a, b) in enumerate(zip(ref_tokens, mlx_out[i]))
                        if a != b), min(len(ref_tokens), len(mlx_out[i])))
            print(f"\nMISMATCH input {i}: {q[:60]!r}")
            print(f"  diverges at token {div}")
            print(f"  ref {len(ref_tokens)} tok: {tokenizer.decode(ref_tokens)!r}")
            print(f"  mlx {len(mlx_out[i])} tok: {tokenizer.decode(mlx_out[i])!r}")

    rate = exact / len(PARITY_INPUTS)
    print(f"\nparity: {exact}/{len(PARITY_INPUTS)} exact ({rate:.0%})")
    assert rate >= MIN_EXACT_RATE, (
        f"greedy parity {rate:.0%} below {MIN_EXACT_RATE:.0%} — "
        "suspect a transposition/ordering bug, not bf16 noise")


def test_contrastive_parity():
    """encode_contrastive mechanism parity.

    FINDING: the shipped needle.pkl contrastive head is EXACTLY zero
    (contrastive_hidden/proj kernels, bias, log_temp all 0.0 — pretrain
    weight decay ate it; architecture.py's safe-L2-norm comment predicts
    this). Both implementations agree on the zeros, but to prove the
    MECHANISM we splice identical random head weights into both sides and
    compare embeddings + retrieval order. B2 must train the head from
    scratch before the retrieval half is usable.
    """
    import jax.numpy as jnp
    import mlx.core as mx
    import numpy as np

    tokenizer = load_tokenizer()
    arch = _ref_architecture()
    ref_model, ref_params = _load_reference(arch)
    mlx_model = load_model()

    # the shipped head really is all-zero — pin the finding
    for key in ("contrastive_hidden", "contrastive_proj"):
        for leaf in ref_params[key].values():
            assert float(jnp.abs(leaf.astype(jnp.float32)).max()) == 0.0

    rng = np.random.default_rng(42)
    hidden_k = rng.normal(0, 0.02, (512, 128)).astype(np.float16)
    hidden_b = rng.normal(0, 0.02, (128,)).astype(np.float16)
    proj_k = rng.normal(0, 0.02, (128, 128)).astype(np.float16)

    ref_params["contrastive_hidden"]["kernel"] = jnp.array(hidden_k, dtype=jnp.bfloat16)
    ref_params["contrastive_hidden"]["bias"] = jnp.array(hidden_b, dtype=jnp.bfloat16)
    ref_params["contrastive_proj"]["kernel"] = jnp.array(proj_k, dtype=jnp.bfloat16)
    mlx_model.w["contrastive_hidden"]["kernel"] = mx.array(hidden_k).astype(mx.bfloat16)
    mlx_model.w["contrastive_hidden"]["bias"] = mx.array(hidden_b).astype(mx.bfloat16)
    mlx_model.w["contrastive_proj"]["kernel"] = mx.array(proj_k).astype(mx.bfloat16)

    texts = [q for q, _ in PARITY_INPUTS[:8]] + [t for _, t in PARITY_INPUTS[:4]]
    token_lists = [tokenizer.encode(t)[:256] for t in texts]
    max_t = max(len(toks) for toks in token_lists)
    batch = np.full((len(texts), max_t), PAD_ID, dtype=np.int32)
    for i, toks in enumerate(token_lists):
        batch[i, :len(toks)] = toks

    ref_emb = np.array(ref_model.apply(
        {"params": ref_params}, jnp.array(batch),
        deterministic=True, method="encode_contrastive"), dtype=np.float32)
    mlx_emb = np.array(mx.array(
        mlx_model.encode_contrastive(mx.array(batch))).astype(mx.float32))

    cos = np.sum(ref_emb * mlx_emb, axis=-1)  # both L2-normalized
    print(f"\ncontrastive cosine(ref, mlx): min {cos.min():.4f} mean {cos.mean():.4f}")
    assert cos.min() > 0.995

    # retrieval order: rank the 4 tool texts against each of the 8 queries
    q, c = mlx_emb[:8], mlx_emb[8:]
    rq, rc = ref_emb[:8], ref_emb[8:]
    mlx_rank = np.argsort(-(q @ c.T), axis=1)
    ref_rank = np.argsort(-(rq @ rc.T), axis=1)
    assert (mlx_rank == ref_rank).all(), "retrieval order diverged"


if __name__ == "__main__":
    test_greedy_parity()
    test_contrastive_parity()
