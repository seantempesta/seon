"""Extension-finetune scaffold: RoPE interpolation + packing + 2048 smoke.

B2's extended-context training rides three pieces proven here:

- `enc_rope_scale` (model.py): position-interpolation RoPE on the ENCODER
  path — positions divided by the scale so a scale*1024 input lands in
  the trained [0, 1024) rotary range (Chen et al. 2023). Config-only:
  1.0 (the default) is byte-parity with the stock model; the decoder
  never scales (self-attn stays inside the trained 512, cross-attn has
  no rope).
- `pack_batches`: pad-minimizing length-bucketed batching toward the
  target length under a per-batch token budget. (Concat-packing of
  multiple examples into ONE encoder sequence does not apply to the
  enc-dec pair shape without block-diagonal masks — a B2 option, not
  scaffolded here.)
- `smoke`: overfit ~10 REAL v2 rows whose assemblies EXCEED the trained
  1024 (i.e. genuinely extended inputs) at max_enc_len 2048 — loss must
  drop and greedy decode must reproduce the memorized targets through
  the interpolated rope path. Single process; peak memory + tok/s
  reported (mx.get_peak_memory).

This is scaffold + smoke, NOT the B2 train (no data mixing, no eval
split, no checkpointing, no constrained decoding).

Run (from src-needle/):
  .venv/bin/python -m seon_needle.extend
Results land under data/extfit/ (gitignored; the research file quotes
them). Sizes/speeds in TOKENS, always.
"""

import json
import time

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim

from . import config
from .generate import build_encoder_input, generate_batch
from .model import load_model
from .overfit import _cast_tree, loss_fn
from .tokenizer import EOS_ID, PAD_ID, TOOL_CALL_ID, load_tokenizer

REPO_ROOT = config.repo_root()
OUT_DIR = config.package_root() / "data" / "extfit"
DEFAULT_ROWS = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v2.jsonl"


def row_tools_target(row, mode):
    """(tools slot string, target string) for a v2 row.

    mode "clojure": compact cards + the Clojure next-form target.
    mode "json":    needle-native tools slot + compact json_target
                    (the JSON-NATIVE arm, owner 2026-07-12)."""
    if mode == "json":
        return (json.dumps(row["json_tools"], separators=(",", ":")),
                json.dumps(row["json_target"], separators=(",", ":")))
    return "\n".join(row["cards"]), row["target"]


def build_pair(tokenizer, row, max_enc_len, mode="clojure", max_dec=512):
    """One (enc, dec_in, dec_tgt) id-list triple from a v2 row.

    Encoder assembly is run.py's layout at the EXTENDED length:
    [context..., <tools>, tools slot...] truncated to max_enc_len.
    Decoder is the teacher-forcing shape shared with overfit.py."""
    tools, target = row_tools_target(row, mode)
    enc = build_encoder_input(tokenizer, row["context"], tools,
                              max_enc_len=max_enc_len)
    a = tokenizer.encode(target)[:max_dec - 3]
    return enc, [EOS_ID, TOOL_CALL_ID] + a, [TOOL_CALL_ID] + a + [EOS_ID]


def pack_batches(enc_lengths, token_budget):
    """Length-bucketed packing: batches of indices, longest-first, where
    batch_size * padded_length <= token_budget. Minimizes pad waste while
    capping the attention footprint (the T^2 term dominates memory)."""
    order = sorted(range(len(enc_lengths)), key=lambda i: -enc_lengths[i])
    batches, current = [], []
    for i in order:
        padded = max(enc_lengths[i],
                     max((enc_lengths[j] for j in current), default=0))
        if current and (len(current) + 1) * padded > token_budget:
            batches.append(current)
            current = []
        current.append(i)
    if current:
        batches.append(current)
    return batches


def _pad(lists):
    n = max(len(x) for x in lists)
    out = mx.full((len(lists), n), PAD_ID, dtype=mx.int32)
    for i, x in enumerate(lists):
        out[i, :len(x)] = mx.array(x, dtype=mx.int32)
    return out


def select_extended_rows(tokenizer, rows, max_enc_len, n_pairs, mode="clojure",
                         max_tgt=256):
    """The n_pairs LONGEST rows with 1024 < assembly <= max_enc_len and a
    short target — every selected input genuinely exceeds the trained
    envelope, so memorization exercises the interpolated positions."""
    scored = []
    for r in rows:
        if mode == "json" and r["json_target"] is None:
            continue
        tools, target = row_tools_target(r, mode)
        total = (len(tokenizer.encode(r["context"])) + 1
                 + len(tokenizer.encode(tools)))
        if 1024 < total <= max_enc_len and len(tokenizer.encode(target)) <= max_tgt:
            scored.append((total, r))
    scored.sort(key=lambda t: -t[0])
    return [r for _, r in scored[:n_pairs]]


def smoke(rows_path=None, max_enc_len=2048, n_pairs=10, epochs=30, lr=3e-4,
          token_budget=None, mode="clojure", verbose=True):
    """Overfit n_pairs extended-length v2 rows; prove the extension path.

    Returns {"losses": per-epoch means, "exact": n, "total": n_pairs,
    "peak_gb", "train_tok_s", "target_tok_s", ...}."""
    rows_path = rows_path or str(DEFAULT_ROWS)
    token_budget = token_budget or max_enc_len  # default: B=1 at full length
    rope_scale = max_enc_len / 1024.0

    tokenizer = load_tokenizer()
    rows = [json.loads(l) for l in open(rows_path) if l.strip()]
    picked = select_extended_rows(tokenizer, rows, max_enc_len, n_pairs, mode)
    assert len(picked) == n_pairs, f"only {len(picked)} extended rows available"

    pairs = [build_pair(tokenizer, r, max_enc_len, mode) for r in picked]
    enc_lengths = [len(e) for e, _, _ in pairs]
    batches = pack_batches(enc_lengths, token_budget)
    if verbose:
        print(f"{n_pairs} rows, enc {min(enc_lengths)}-{max(enc_lengths)} tok "
              f"(all > 1024), rope_scale {rope_scale}, "
              f"{len(batches)} batches/epoch (budget {token_budget} enc tok)")

    model = load_model()
    model.config.enc_rope_scale = rope_scale
    model.w = _cast_tree(model.w, mx.float32)  # f32 master weights (overfit.py)

    tensors = [(_pad([pairs[i][0] for i in b]),
                _pad([pairs[i][1] for i in b]),
                _pad([pairs[i][2] for i in b])) for b in batches]
    real_tokens_per_epoch = sum(len(x) for p in pairs for x in (p[0], p[2]))

    opt = optim.AdamW(learning_rate=lr)
    step_fn = nn.value_and_grad(model, loss_fn)

    mx.reset_peak_memory()
    losses = []
    t0 = time.perf_counter()
    for epoch in range(epochs):
        epoch_losses = []
        for enc, dec_in, dec_tgt in tensors:
            loss, grads = step_fn(model, enc, dec_in, dec_tgt)
            opt.update(model, grads)
            mx.eval(model.parameters(), opt.state, loss)
            epoch_losses.append(float(loss))
        losses.append(sum(epoch_losses) / len(epoch_losses))
        if verbose and (epoch % 5 == 0 or epoch == epochs - 1):
            print(f"  epoch {epoch:>3}  loss {losses[-1]:.4f}")
    train_s = time.perf_counter() - t0
    peak_gb = mx.get_peak_memory() / 1e9
    train_tok = epochs * real_tokens_per_epoch
    tgt_tok = epochs * sum(len(p[2]) for p in pairs)

    # memorization check through the SAME interpolated path, chunked to
    # keep the inference attention footprint small
    exact = 0
    for i in range(0, n_pairs, 2):
        chunk = picked[i:i + 2]
        slots = [row_tools_target(r, mode) for r in chunk]
        result = generate_batch(model, tokenizer,
                                [r["context"] for r in chunk],
                                [tools for tools, _ in slots],
                                max_gen_len=300, max_enc_len=max_enc_len)
        for j, (_, target) in enumerate(slots):
            want = [TOOL_CALL_ID] + tokenizer.encode(target)
            ok = result["tokens"][j] == want
            exact += ok
            if verbose:
                print(f"  {'=' if ok else '!'} {result['texts'][j][:80]!r}")

    out = {
        "rows_path": rows_path,
        "mode": mode,
        "max_enc_len": max_enc_len,
        "rope_scale": rope_scale,
        "n_pairs": n_pairs,
        "enc_lengths": sorted(enc_lengths),
        "epochs": epochs,
        "lr": lr,
        "losses": [round(x, 4) for x in losses],
        "exact": exact,
        "total": n_pairs,
        "train_s": round(train_s, 1),
        "train_tok_s": round(train_tok / train_s),
        "target_tok_s": round(tgt_tok / train_s),
        "peak_gb": round(peak_gb, 2),
    }
    if verbose:
        print(f"memorized {exact}/{n_pairs} exactly "
              f"(loss {losses[0]:.3f} -> {losses[-1]:.3f}) | "
              f"{train_s:.1f}s train, {out['train_tok_s']} tok/s "
              f"(enc+target), peak {peak_gb:.2f} GB")
    return out


def main():
    results = {}
    for mode in ("clojure", "json"):
        print(f"== smoke, {mode} arm ==")
        results[mode] = smoke(mode=mode)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "extend_smoke.json"
    out.write_text(json.dumps(results, indent=1))
    print("wrote", out)


if __name__ == "__main__":
    main()
