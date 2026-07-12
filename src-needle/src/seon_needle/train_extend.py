"""B2 extension finetune — full-param 26M at 2048 (position-interpolated).

The real train that the `seon_needle.extend` smoke scaffolded: rope scale
2.0 on the encoder (positions land in the trained [0,1024) range),
f32 master weights (B1: bf16 masters silently stop learning), AdamW with
warmup+cosine, the reference's token-class loss weighting
(finetune.py values: name 2.0 / value 4.0 / key 1.5, weighted CE
normalized by token COUNT + 1e-4 z-loss — train.py _text_loss_fn), and
pad-minimizing length-bucketed batching under an encoder-token budget
(extend.pack_batches — the T^2 attention term is the memory constraint;
single process, peak target < 8 GB).

Mix schedule (documented, phase-keyed): the home stratum (packed-long
Synth-APIGen + irrelevance) is the anti-forgetting majority EARLY; the
seon long-menu stratum ramps in:

  phase 1: home 100% + seon 25%   (1 epoch)
  phase 2: home 100% + seon 100%  (2 epochs)

("+ N%" = that fraction of the stratum sampled per epoch, seeded.)

Checkpoints land under checkpoints/extended-2048/ (gitignored):
weights f32 safetensors + config.json carrying enc_rope_scale/max_seq_len
so `load_model` serves them unchanged (it bf16-casts at load, the same
cast the stock fp16 checkpoint gets). Best = lowest val loss.

Run (from src-needle/):
  .venv/bin/python -m seon_needle.train_extend               # full train
  .venv/bin/python -m seon_needle.train_extend --limit 200   # smoke
  .venv/bin/python -m seon_needle.train_extend --probe-4096  # memory probe
Curves land in checkpoints/extended-2048/train_log.json. Sizes/speeds in
TOKENS, always.
"""

import argparse
import json
import math
import random
import time
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
import numpy as np

from . import config
from .data_extend import token_classes_for_answer
from .extend import pack_batches
from .generate import build_encoder_input
from .model import load_model, make_causal_mask, make_padding_mask
from .overfit import _cast_tree
from .tokenizer import EOS_ID, PAD_ID, TOOL_CALL_ID, load_tokenizer

PKG_ROOT = config.package_root()
DATA_DIR = PKG_ROOT / "data" / "extend"
CKPT_DIR = PKG_ROOT / "checkpoints" / "extended-2048"

MAX_ENC = 2048
MAX_DEC = 512
ROPE_SCALE = 2.0
# reference finetune.py post-train weights
LOSS_WEIGHTS = mx.array([1.0, 2.0, 4.0, 1.5], dtype=mx.float32)

PHASES = ({"name": "phase1", "epochs": 1, "home": 1.0, "seon": 0.25},
          {"name": "phase2", "epochs": 2, "home": 1.0, "seon": 1.0})


# ---------------------------------------------------------------------------
# Pairs
# ---------------------------------------------------------------------------

def build_pair(tokenizer, row, sp):
    """(enc ids, dec_in ids, dec_tgt ids, tgt classes) for one data row."""
    enc = build_encoder_input(tokenizer, row["query"], row["tools"],
                              max_enc_len=MAX_ENC)
    a = tokenizer.encode(row["answers"])[:MAX_DEC - 3]
    classes = token_classes_for_answer(row["answers"], a, sp)
    dec_in = [EOS_ID, TOOL_CALL_ID] + a
    dec_tgt = [TOOL_CALL_ID] + a + [EOS_ID]
    # class labels align to dec_tgt: [<tool_call>=base, answer classes, EOS=base]
    cls = np.zeros(len(dec_tgt), dtype=np.int8)
    cls[1:1 + len(classes)] = classes
    return enc, dec_in, dec_tgt, cls


def load_rows(name, limit=0):
    rows = [json.loads(l) for l in (DATA_DIR / name).read_text().splitlines()]
    return rows[:limit] if limit else rows


def _pad(lists, pad_value=PAD_ID, quantum=128):
    """Pad to the batch max ROUNDED UP to `quantum`. Shape quantization is
    load-bearing: with fully dynamic (B, T) shapes MLX's Metal buffer
    cache allocates a new buffer size per distinct shape and never
    reuses — measured 103 GB of cached buffers by step ~400 of the first
    training launch, which drove the MACHINE into
    vm-compressor-space-shortage and got the trainer jetsam-SIGKILLed.
    Rounding T to 128 (and capping the cache, see train()) bounds the
    shape vocabulary."""
    n = max(len(x) for x in lists)
    n = ((n + quantum - 1) // quantum) * quantum
    out = np.full((len(lists), n), pad_value, dtype=np.int32)
    for i, x in enumerate(lists):
        out[i, :len(x)] = x
    return mx.array(out)


def make_batches(pairs, token_budget, rng=None):
    """pack_batches over enc lengths -> list of padded tensor tuples."""
    enc_lengths = [len(p[0]) for p in pairs]
    batches = pack_batches(enc_lengths, token_budget)
    if rng is not None:
        rng.shuffle(batches)
    out = []
    for b in batches:
        out.append((_pad([pairs[i][0] for i in b]),
                    _pad([pairs[i][1] for i in b]),
                    _pad([pairs[i][2] for i in b]),
                    _pad([pairs[i][3] for i in b], pad_value=0)))
    return out


# ---------------------------------------------------------------------------
# Loss (train.py _text_loss_fn semantics)
# ---------------------------------------------------------------------------

def loss_fn(model, enc, dec_in, dec_tgt, tgt_cls):
    src_mask = make_padding_mask(enc, PAD_ID)
    tgt_mask = mx.logical_and(make_causal_mask(dec_in.shape[1]),
                              make_padding_mask(dec_in, PAD_ID))
    logits = model(enc, dec_in, src_mask=src_mask, tgt_mask=tgt_mask)
    ce = nn.losses.cross_entropy(logits, dec_tgt, reduction="none")
    pad_mask = (dec_tgt != PAD_ID).astype(mx.float32)
    weights = LOSS_WEIGHTS[tgt_cls] * pad_mask
    num_tokens = mx.maximum(pad_mask.sum(), 1.0)
    ce_loss = (ce * weights).sum() / num_tokens
    z_loss = 1e-4 * mx.mean(mx.logsumexp(logits, axis=-1) ** 2)
    return ce_loss + z_loss


def val_loss(model, batches):
    """Unweighted CE over val batches (comparable across weight configs)."""
    total, count = 0.0, 0.0
    for enc, dec_in, dec_tgt, _cls in batches:
        src_mask = make_padding_mask(enc, PAD_ID)
        tgt_mask = mx.logical_and(make_causal_mask(dec_in.shape[1]),
                                  make_padding_mask(dec_in, PAD_ID))
        logits = model(enc, dec_in, src_mask=src_mask, tgt_mask=tgt_mask)
        ce = nn.losses.cross_entropy(logits, dec_tgt, reduction="none")
        mask = (dec_tgt != PAD_ID).astype(mx.float32)
        total += float((ce * mask).sum())
        count += float(mask.sum())
        mx.clear_cache()
    return total / max(count, 1.0)


# ---------------------------------------------------------------------------
# Checkpointing
# ---------------------------------------------------------------------------

def _flatten(tree, prefix=""):
    flat = {}
    if isinstance(tree, dict):
        for k, v in tree.items():
            flat.update(_flatten(v, f"{prefix}{k}/"))
    elif isinstance(tree, list):
        for i, v in enumerate(tree):
            flat.update(_flatten(v, f"{prefix}{i}/"))
    else:
        flat[prefix[:-1]] = tree
    return flat


def save_checkpoint(model, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    mx.save_safetensors(str(path), _flatten(model.w))
    cfg = json.loads(config.model_config_path().read_text())
    cfg["enc_rope_scale"] = ROPE_SCALE
    cfg["max_seq_len"] = MAX_ENC
    (path.parent / "config.json").write_text(json.dumps(cfg, indent=1))


def load_extended(ckpt_dir=CKPT_DIR, name="best.safetensors"):
    """Serve-side loader: the extended checkpoint through load_model."""
    return load_model(weights_path=ckpt_dir / name,
                      config_path=ckpt_dir / "config.json")


# ---------------------------------------------------------------------------
# Train
# ---------------------------------------------------------------------------

def lr_schedule(step, total_steps, peak, warmup_frac=0.03, floor_frac=0.1):
    warmup = max(1, int(total_steps * warmup_frac))
    if step < warmup:
        return peak * (step + 1) / warmup
    t = (step - warmup) / max(1, total_steps - warmup)
    return peak * (floor_frac + (1 - floor_frac) * 0.5 * (1 + math.cos(math.pi * t)))


def train(limit=0, lr=1e-4, token_budget=6144, seed=11, val_every=400,
          log_path=None, resume=False):
    tokenizer = load_tokenizer()
    sp = tokenizer.sp

    train_rows = load_rows("train.jsonl", limit)
    val_rows = load_rows("val.jsonl", max(1, limit // 10) if limit else 0)
    home_rows = [r for r in train_rows if r["src"].startswith("home")]
    seon_rows = [r for r in train_rows if r["src"].startswith("seon")]
    print(f"rows: home {len(home_rows)}, seon {len(seon_rows)}, "
          f"val {len(val_rows)}", flush=True)

    print("tokenizing…", flush=True)
    t0 = time.perf_counter()
    home_pairs = [build_pair(tokenizer, r, sp) for r in home_rows]
    seon_pairs = [build_pair(tokenizer, r, sp) for r in seon_rows]
    val_batches = make_batches([build_pair(tokenizer, r, sp) for r in val_rows],
                               token_budget)
    print(f"  {time.perf_counter() - t0:.0f}s", flush=True)

    # resume: the batch plan is DETERMINISTIC (one seeded rng consumed in a
    # fixed order), so a killed run restarts by re-deriving the plan and
    # skipping the first `start_step` batches. Optimizer moments are NOT
    # persisted — AdamW re-inits with a 100-step lr re-ramp (bias
    # correction rebuilds moments quickly at 26M; the compromise is
    # documented in the research file if a resume actually happens).
    start_step, resumed = 0, False
    latest_meta = CKPT_DIR / "latest.meta.json"
    if resume and (CKPT_DIR / "latest.safetensors").exists() and latest_meta.exists():
        meta = json.loads(latest_meta.read_text())
        start_step = meta["step"]
        resumed = True
        print(f"RESUMING from step {start_step}", flush=True)

    if resumed:
        model = load_model(weights_path=CKPT_DIR / "latest.safetensors",
                           config_path=CKPT_DIR / "config.json")
    else:
        model = load_model()
    model.config.enc_rope_scale = ROPE_SCALE
    model.w = _cast_tree(model.w, mx.float32)

    # Bound the Metal buffer cache (see _pad's note): freed buffers held
    # for reuse are NOT in get_peak_memory and grew to 103 GB unbounded.
    mx.set_cache_limit(2 * 1024 ** 3)

    opt = optim.AdamW(learning_rate=lr, weight_decay=0.01)
    step_fn = nn.value_and_grad(model, loss_fn)

    # total step estimate for the cosine schedule
    rng = random.Random(seed)
    total_steps = 0
    for ph in PHASES:
        n = int(len(home_pairs) * ph["home"]) + int(len(seon_pairs) * ph["seon"])
        est_tokens = (sum(len(p[0]) for p in (home_pairs + seon_pairs)[:n])
                      if n else 0)
        total_steps += ph["epochs"] * max(1, est_tokens // token_budget)
    print(f"~{total_steps} steps planned, token budget {token_budget}", flush=True)

    log = {"phases": [], "val": [], "config": {
        "lr": lr, "token_budget": token_budget, "rope_scale": ROPE_SCALE,
        "max_enc": MAX_ENC, "loss_weights": [1.0, 2.0, 4.0, 1.5],
        "weight_decay": 0.01, "seed": seed,
        "phases": [dict(p) for p in PHASES]}}
    log_path = log_path or (CKPT_DIR / "train_log.json")
    log_path.parent.mkdir(parents=True, exist_ok=True)

    mx.reset_peak_memory()
    step, best_val = 0, float("inf")
    if resumed and log_path and Path(log_path or (CKPT_DIR / "train_log.json")).exists():
        try:
            prev = json.loads(Path(log_path or (CKPT_DIR / "train_log.json")).read_text())
            best_val = min([v["val_loss"] for v in prev.get("val", [])
                            if v["val_loss"] == v["val_loss"]] or [float("inf")])
            log["val"] = prev.get("val", [])
            log["phases"] = prev.get("phases", [])
        except (ValueError, KeyError):
            pass
    t_start = time.perf_counter()
    tokens_seen = 0

    def save_latest():
        save_checkpoint(model, CKPT_DIR / "latest.safetensors")
        latest_meta.write_text(json.dumps({"step": step}))

    def checkpoint_val(tag):
        nonlocal best_val
        vl = val_loss(model, val_batches) if val_batches else float("nan")
        log["val"].append({"step": step, "tag": tag, "val_loss": round(vl, 4),
                           "elapsed_s": round(time.perf_counter() - t_start, 1)})
        print(f"  step {step:>5} [{tag}] val {vl:.4f} "
              f"(peak {mx.get_peak_memory() / 1e9:.2f} GB)", flush=True)
        if vl < best_val:
            best_val = vl
            save_checkpoint(model, CKPT_DIR / "best.safetensors")
        save_latest()
        log_path.write_text(json.dumps(log, indent=1))

    if not resumed:
        checkpoint_val("pre")  # baseline val loss of the stock model @scale2
    for ph in PHASES:
        for epoch in range(ph["epochs"]):
            # rng consumption below is IDENTICAL across runs (resume skips
            # execution, never derivation)
            pairs = list(home_pairs[:int(len(home_pairs) * ph["home"])])
            n_seon = int(len(seon_pairs) * ph["seon"])
            idx = rng.sample(range(len(seon_pairs)), n_seon)
            pairs += [seon_pairs[i] for i in idx]
            batches = make_batches(pairs, token_budget, rng)
            if step + len(batches) <= start_step:  # whole epoch already done
                step += len(batches)
                continue
            epoch_losses = []
            t_ep = time.perf_counter()
            ep_tokens = 0
            for enc, dec_in, dec_tgt, cls in batches:
                if step < start_step:
                    step += 1
                    continue
                lr_t = lr_schedule(step, total_steps, lr)
                if resumed:  # 100-step re-ramp while AdamW moments rebuild
                    lr_t = min(lr_t, lr_t * (step - start_step + 1) / 100)
                opt.learning_rate = lr_t
                loss, grads = step_fn(model, enc, dec_in, dec_tgt, cls)
                opt.update(model, grads)
                mx.eval(model.parameters(), opt.state, loss)
                epoch_losses.append(float(loss))
                step += 1
                ep_tokens += int(enc.size + dec_tgt.size)
                if step % 50 == 0:
                    mean50 = sum(epoch_losses[-50:]) / len(epoch_losses[-50:])
                    el = time.perf_counter() - t_start
                    print(f"  step {step:>5} loss {mean50:.4f} lr {lr_t:.2e} "
                          f"{(tokens_seen + ep_tokens) / el:,.0f} tok/s "
                          f"peak {mx.get_peak_memory() / 1e9:.2f} GB", flush=True)
                if step % 100 == 0:
                    mx.clear_cache()
                if step % val_every == 0:
                    checkpoint_val(f"{ph['name']}-e{epoch}")
                elif step % 200 == 0:
                    save_latest()
            tokens_seen += ep_tokens
            if not epoch_losses:
                continue
            ep = {"phase": ph["name"], "epoch": epoch, "steps": len(epoch_losses),
                  "loss_mean": round(sum(epoch_losses) / len(epoch_losses), 4),
                  "loss_first50": round(sum(epoch_losses[:50]) / min(50, len(epoch_losses)), 4),
                  "loss_last50": round(sum(epoch_losses[-50:]) / min(50, len(epoch_losses)), 4),
                  "epoch_s": round(time.perf_counter() - t_ep, 1),
                  "tok_s": round(ep_tokens / (time.perf_counter() - t_ep))}
            log["phases"].append(ep)
            print(f"== {ph['name']} epoch {epoch}: {ep}", flush=True)
            checkpoint_val(f"{ph['name']}-e{epoch}-end")

    save_checkpoint(model, CKPT_DIR / "final.safetensors")
    log["peak_gb"] = round(mx.get_peak_memory() / 1e9, 2)
    log["total_s"] = round(time.perf_counter() - t_start, 1)
    log["best_val"] = round(best_val, 4)
    log_path.write_text(json.dumps(log, indent=1))
    print(f"done: best val {best_val:.4f}, peak {log['peak_gb']} GB, "
          f"{log['total_s']}s", flush=True)


# ---------------------------------------------------------------------------
# 4096 memory probe
# ---------------------------------------------------------------------------

def probe_4096(token_budget=4096):
    """One forward+backward at enc len 4096 (rope scale 4) — the number the
    2048-vs-4096 decision needs. Reports peak GB; OOM is caught."""
    tokenizer = load_tokenizer()
    model = load_model()
    model.config.enc_rope_scale = 4.0
    model.w = _cast_tree(model.w, mx.float32)
    opt = optim.AdamW(learning_rate=1e-4)
    step_fn = nn.value_and_grad(model, loss_fn)

    B = max(1, token_budget // 4096)
    enc = mx.random.randint(10, 8000, (B, 4096), dtype=mx.int32)
    dec = mx.random.randint(10, 8000, (B, 128), dtype=mx.int32)
    cls = mx.zeros((B, 128), dtype=mx.int32)
    mx.reset_peak_memory()
    t0 = time.perf_counter()
    try:
        loss, grads = step_fn(model, enc, dec, dec, cls)
        opt.update(model, grads)
        mx.eval(model.parameters(), opt.state, loss)
        dt = time.perf_counter() - t0
        peak = mx.get_peak_memory() / 1e9
        print(f"4096 probe OK: B={B}, one step {dt:.1f}s, "
              f"{B * (4096 + 128) / dt:,.0f} tok/s, peak {peak:.2f} GB")
        return {"ok": True, "B": B, "step_s": round(dt, 2),
                "peak_gb": round(peak, 2)}
    except Exception as e:  # MemoryError / metal OOM
        print(f"4096 probe FAILED: {type(e).__name__}: {e}")
        return {"ok": False, "error": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--token-budget", type=int, default=6144)
    ap.add_argument("--val-every", type=int, default=400)
    ap.add_argument("--probe-4096", action="store_true")
    ap.add_argument("--resume", action="store_true",
                    help="continue from checkpoints/extended-2048/latest.*")
    args = ap.parse_args()
    if args.probe_4096:
        probe_4096()
    else:
        train(limit=args.limit, lr=args.lr, token_budget=args.token_budget,
              val_every=args.val_every, resume=args.resume)


if __name__ == "__main__":
    main()
