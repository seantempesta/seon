"""Block-diffusion generation loop, mirroring DiffusionGemmaGenerationMixin.

Per canvas (block of canvas_length tokens):
  x_T ~ Uniform(vocab); for cur_step = N..1:
    logits   = decoder(current_canvas, cache, self_conditioning_logits)
    logits  /= t_min + (t_max - t_min) * cur_step / N      (linear temp schedule)
    denoiser = sample(logits); argmax = argmax(logits)
    accept the k lowest-entropy positions s.t.
        cumsum(sorted_entropy) - sorted_entropy <= entropy_bound
    renoise the rest with fresh uniform tokens
    stop early when argmax canvas is stable for `stability_threshold` steps
    AND mean token entropy < confidence_threshold
  commit argmax canvas; stop the outer loop on EOS (pad after first EOS);
  otherwise encode the committed canvas into the cache and continue.
"""

import time
from dataclasses import dataclass

import mlx.core as mx


@dataclass
class GenConfig:
    # generation_config.json defaults for the 8-bit checkpoint
    max_new_tokens: int = 256
    max_denoising_steps: int = 48
    entropy_bound: float = 0.1
    t_min: float = 0.4
    t_max: float = 0.8
    stability_threshold: int = 1
    confidence_threshold: float = 0.005
    eos_token_ids: tuple = (1, 106, 50)
    pad_token_id: int = 0
    seed: int | None = None


def _entropy(logits_f32):
    """Per-position categorical entropy (natural log) from raw logits."""
    logp = logits_f32 - mx.logsumexp(logits_f32, axis=-1, keepdims=True)
    return -mx.sum(mx.exp(logp) * logp, axis=-1)  # [B, L]


def _accept(current, denoiser, logits_f32, entropy_bound):
    """Entropy-bound acceptance: take the k lowest-entropy positions such that
    cumsum - max <= bound (max == last element since sorted ascending)."""
    ent = _entropy(logits_f32)  # [B, L]
    order = mx.argsort(ent, axis=-1)
    sorted_ent = mx.take_along_axis(ent, order, axis=-1)
    cum = mx.cumsum(sorted_ent, axis=-1)
    sorted_mask = (cum - sorted_ent) <= entropy_bound
    # scatter sorted mask back to canvas positions
    mask = mx.zeros_like(sorted_mask)
    mask = mx.put_along_axis(mask, order, sorted_mask, axis=-1)
    return mx.where(mask, denoiser, current), mask


def generate(model, tokenizer, prompt_ids, gen: GenConfig | None = None, verbose=False):
    """Generate text; returns dict with sequences, text, tok/s, tokens_per_forward."""
    gen = gen or GenConfig()
    if gen.seed is not None:
        mx.random.seed(gen.seed)
    cfg = model.cfg
    CL = cfg.canvas_length
    B = 1

    ids = mx.array(prompt_ids)[None, :]
    prompt_len = ids.shape[1]
    max_new_canvases = -(-gen.max_new_tokens // CL)
    eos = mx.array(list(gen.eos_token_ids))

    cache = model.new_cache()
    t0 = time.time()
    model.encode(ids, cache, past_len=0)
    mx.eval(cache[0]["k"])
    t_prefill = time.time() - t0

    generated = []
    forwards = 0
    cur_len = prompt_len
    t_gen0 = time.time()

    for _canvas_idx in range(max_new_canvases):
        current = mx.random.randint(0, cfg.vocab_size, (B, CL))
        sc_logits = None
        argmax_canvas = current
        history = None  # stability history of argmax canvases

        for cur_step in range(gen.max_denoising_steps, 0, -1):
            forwards += 1
            logits = model.decode(current, cache, canvas_start=cur_len,
                                  self_conditioning_logits=sc_logits)
            temp = gen.t_min + (gen.t_max - gen.t_min) * (cur_step / gen.max_denoising_steps)
            logits = logits / temp  # fp32 already (lm_head)

            denoiser = mx.random.categorical(logits)  # [B, L]
            argmax_canvas = mx.argmax(logits, axis=-1)

            accepted, _mask = _accept(current, denoiser, logits, gen.entropy_bound)
            renoise = mx.random.randint(0, cfg.vocab_size, (B, CL))
            current = mx.where(_mask, accepted, renoise)

            # stable + confident early stop
            stable = False
            if gen.stability_threshold == 0:
                stable = True
            else:
                if history is not None and bool(mx.all(history == argmax_canvas)):
                    stable = True
                history = argmax_canvas
            confident = float(mx.mean(_entropy(logits))) < gen.confidence_threshold
            mx.eval(current, argmax_canvas)
            sc_logits = logits
            if stable and confident:
                break

        generated.append(argmax_canvas)
        if verbose:
            txt = tokenizer.decode([int(t) for t in argmax_canvas[0]])
            print(f"[canvas {_canvas_idx}] steps used: {gen.max_denoising_steps - cur_step + 1}: {txt[:120]!r}")

        # EOS handling: pad everything after the first EOS in this canvas
        canvas = argmax_canvas
        is_eos = mx.zeros(canvas.shape, dtype=mx.bool_)
        for e in gen.eos_token_ids:
            is_eos = is_eos | (canvas == e)
        finished = bool(mx.any(is_eos))
        if finished:
            cum = mx.cumsum(is_eos.astype(mx.int32), axis=-1)
            pad_mask = (cum > 0) & ~((cum == 1) & is_eos)
            canvas = mx.where(pad_mask, gen.pad_token_id, canvas)
            generated[-1] = canvas
            break

        # commit: encode this canvas into the cache, continue with next block
        model.encode(canvas, cache, past_len=cur_len)
        cur_len += CL

    t_gen = time.time() - t_gen0
    out_ids = mx.concatenate(generated, axis=-1)[0]
    toks = [int(t) for t in out_ids]
    # strip pads for text + counting
    valid = [t for t in toks if t != gen.pad_token_id]
    text = tokenizer.decode(valid)
    return {
        "text": text,
        "token_ids": toks,
        "num_tokens": len(valid),
        "decoder_forwards": forwards,
        "tokens_per_forward": len(valid) / max(forwards, 1),
        "prefill_s": t_prefill,
        "generate_s": t_gen,
        "tok_per_s": len(valid) / t_gen if t_gen > 0 else 0.0,
    }
