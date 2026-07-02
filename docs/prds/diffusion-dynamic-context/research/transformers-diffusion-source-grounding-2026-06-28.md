---
type: research
status: active
tags: [research, diffusion, agent]
---

# DiffusionGemma generation — source grounding against transformers v5.11.0

Ground truth: the vendored submodule `reference-code/transformers/`, pinned to
tag `v5.11.0` (HEAD `e7b5b964e6`), the EXACT version the RunPod worker runs
(`transformers==5.11.0`). Every claim below cites
`reference-code/transformers/.../<file>:<line>`. Where the source is silent it
says "source does not specify" rather than inferring.

## TL;DR

- **The streamer seam is real and our `TraceStreamer` protocol is CORRECT for
  5.11.0.** `generate()` calls `streamer.put_draft(...)` once per denoising step
  (`generation_diffusion_gemma.py:775-779`), reading `_takes_logits` via `getattr`
  and passing EITHER `value=argmax_canvas.cpu()` OR `logits=self_conditioning_logits.cpu()`.
  Our `put(value)` / `put_draft(value=None, logits=None)` / `_takes_logits` / `end()`
  shape matches the contract exactly. **An empty trace is therefore NOT a contract
  bug** — put_draft *is* reached on a real run. The empty-in-0.5s observation means
  the streamer was not attached, or `generate()` raised inside the loop and the
  worker's try/except returned an error payload (so `summary()` never merged), or
  the loop produced 0 recorded steps. Fix = attach the CHEAP trace
  (`_takes_logits=False`, copies only the tiny argmax canvas) and assert
  `len(streamer.steps) == sum(denoise steps)` against `out.tokens_per_forward`
  before paying for the full-logit (entropy) copy.

- **The `logits_processor` apply site is per-denoise-step**
  (`generation_diffusion_gemma.py:1034`, inside `_denoising_step`). Our
  `ClampLogitsProcessor` fires every step, before the built-in temperature schedule.
  The whole between-step-control thesis is supported.

- **"Commit" is EMERGENT, not an explicit lock.** Canvas init and renoise use
  RANDOM vocab tokens (`:392`, `:461`), NOT a mask token. The tokenizer's
  `mask_token_id=4` is **vestigial** for the generation path — the only `masked_*`
  calls in the model are `masked_scatter` for *image-embedding* injection
  (`modeling_diffusion_gemma.py:1094`), unrelated to text diffusion. A position
  "sticks" because `accept_canvas` keeps low-entropy positions and `renoise_canvas`
  only re-randomizes NON-accepted positions (`:437-462`).

- **Checkpoint / short-circuit:** "denoise to step K then stop" is fully supported
  (set `max_denoising_steps=K`; the loop is `reversed(range(1, K+1))`, `:751`).
  Mid-denoise OBSERVATION is supported (streamer, read-only). A CUSTOM mid-denoise
  early-stop is NOT exposed through the public `generate()` API — but
  `DiffusionGemmaAdaptiveStopping` is an ABC (`:466`) and you can override the
  private `_prepare_diffusion_stopping_criteria` (`:1207`) to inject your own
  parse/eval criterion (runs on the default non-compiled path). Inject-and-resume a
  SINGLE loop is not first-class; the supported pattern is an OUTER loop of K-step
  `generate()` calls re-seeded via `decoder_input_ids` + `ClampLogitsProcessor` +
  fed-back `past_key_values`.

- **Newer transformers: NOT worth upgrading for control/features.** The entire
  streamer/logits/sampler/decoder_input_ids/tokens_per_forward seam is byte-identical
  from v5.11.0 through `origin/main` (5.13.0.dev0). `streamers.py` has ZERO diff
  across those versions. What main adds is cosmetic-or-plumbing: `return_dict_in_generate`,
  `disable_compile`, `bos_token_id`, removal of the hardcoded default `eos/pad`,
  and a static-cache/attention-mask refactor that RENAMES internal cache APIs
  (`max_cache_len`→`get_max_length`, `max_batch_size`→`batch_size`). No per-step
  hook, no custom diffusion-stopping injection, no sampler change, no speed change to
  the loop. **Verdict: stay on 5.11.0** (it matches the deployed checkpoint's
  `generation_config.json`, which 5.13 leans on harder).

---

## 1. How `generate()` REALLY works in 5.11.0

File: `reference-code/transformers/src/transformers/models/diffusion_gemma/generation_diffusion_gemma.py`.

### 1.1 Two nested loops

`DiffusionGemmaGenerationMixin.generate` (`:542`) is `@torch.no_grad()`. Outer =
block-autoregressive over canvases; inner = diffusion denoising of one canvas.

- Outer loop: `for _ in range(max_new_canvases)` (`:713`), where
  `max_new_canvases = ceil(max_new_tokens / canvas_length)` (`:638`).
- Per canvas: encoder forward to extend the KV cache (`:727`), then prep the denoise
  loop (`:738`), then the inner loop.
- Inner loop: `for cur_step in reversed(range(1, generation_config.max_denoising_steps + 1))`
  (`:751`) — **N..1, `max_denoising_steps` is the step CAP, `cur_step` counts DOWN**.

### 1.2 The one denoising step (`_denoising_step`, `:997`)

VERBATIM, the heart of it (`:1031-1047`):

```python
raw_logits = decoder_outputs.logits

# 1.c.ii Select new canvas tokens from the output logits.
processed_logits = logits_processor(input_ids, raw_logits, cur_step=cur_step)
probs = torch.softmax(processed_logits, dim=-1, dtype=torch.float32)
...
denoiser_canvas = torch.multinomial(probs.view(-1, vocab_size), num_samples=1)
denoiser_canvas = denoiser_canvas.squeeze(-1).view(batch_size, canvas_length)
new_argmax_canvas = torch.argmax(processed_logits, dim=-1)

# 1.c.iii Apply the sampler acceptance and renoising logic.
accepted_canvas = sampler.accept_canvas(current_canvas, denoiser_canvas, processed_logits, cur_step)
accepted_canvas = accepted_canvas.clone()  # clone needed for compiled sampler
new_current_canvas = sampler.renoise_canvas(accepted_canvas, cur_step)
```

- **`logits_processor` apply site = `:1034`, EVERY step.** This is THE per-step
  control hook. External processors run FIRST (built first in
  `_prepare_logits_processor:1162`, which only APPENDS the temperature schedule after
  any user list, `:1170-1181`). So `ClampLogitsProcessor` runs before
  `LinearTemperatureScheduleLogitsProcessor`. Dividing a near-one-hot by a positive
  temperature stays near-one-hot → clamp survives.
- **Self-conditioning:** the step's processed logits are fed back as
  `self_conditioning_logits` for the next step (`:1062-1063`), passed into the
  decoder (`:1025`).
- **`argmax_canvas` is the draft / the eventual output.** The outer loop appends
  `argmax_canvas` (NOT `current_canvas`) to the sequence: `input_ids = torch.cat([input_ids, argmax_canvas], dim=-1)` (`:786`).

### 1.3 The sampler (accept / renoise) — `EntropyBoundSampler:339`

`accept_canvas` (`:400`) VERBATIM core (`:431-442`):

```python
dist = torch.distributions.Categorical(logits=logits)
token_entropy = dist.entropy()  # (batch_size, canvas_length)
sorted_token_entropy, sorted_indices = torch.sort(token_entropy, dim=-1, descending=False)
cumulative_entropy = torch.cumsum(sorted_token_entropy, dim=-1)
sorted_selection_mask = cumulative_entropy - sorted_token_entropy <= self.entropy_bound
self.accepted_token_mask = torch.scatter(
    input=torch.zeros_like(sorted_selection_mask), dim=-1, index=sorted_indices, src=sorted_selection_mask
)
accepted_canvas = torch.where(self.accepted_token_mask, denoiser_canvas, current_canvas)
```

`renoise_canvas` (`:444`) VERBATIM (`:457-463`):

```python
renoise_mask = ~self.accepted_token_mask
random_canvas = self.initialize_canvas(batch_size, device)
renoised_canvas = torch.where(renoise_mask, random_canvas, accepted_canvas)
```

`initialize_canvas` (`:388`): `torch.randint(low=0, high=self.vocab_size, ...)` —
**random vocab tokens, no mask token.** So: accept the k lowest-entropy positions
(joint-MI bound = `entropy_bound`), keep them, RE-RANDOMIZE the rest. HIGHER
`entropy_bound` → more positions accepted per step → higher `tokens_per_forward`.
This is the source proof that "commit" is emergent low-entropy persistence, not a
lock/mask.

### 1.4 Diffusion stopping (inner) — `StableAndConfidentStoppingCriteria:478`

Updated per step at `:1050-1059`; it is given `new_argmax_canvas` and
`processed_logits` (`:1059`). Stops a batch row when the argmax canvas is unchanged
for `stability_threshold` steps AND mean token entropy `< confidence_threshold`
(`:511-531`). When all rows finished → `break` the inner loop (`:782-783`).
Constructed ONLY from generation_config in `_prepare_diffusion_stopping_criteria`
(`:1207`) — **no public injection point** (see §3).

### 1.5 AR stopping (outer) — `:1185`, applied in `_finalize_canvas:1072`

The `stopping_criteria` argument to `generate()` is the OUTER/per-canvas criteria
(`MaxLengthCriteria`, `EosTokenCriteria`), applied AFTER a whole canvas is denoised
(`:1082`), padding finished rows (`:1090-1100`). This is NOT a mid-denoise hook.

### 1.6 The step metric — `_compute_tokens_per_forward:829`

VERBATIM (`:843-848`):

```python
new_tokens = input_ids[:, initial_input_ids_len:]
if pad_token_id is not None:
    num_valid_tokens = (new_tokens != pad_token_id).sum(dim=-1)
else:
    num_valid_tokens = new_tokens.shape[1]
tokens_per_forward = num_valid_tokens / decoder_forward_passes
```

- `decoder_forward_passes` increments per step per UNFINISHED row:
  `decoder_forward_passes += ~(finished_denoising | finished_sequences)` (`:754`).
- So `out.tokens_per_forward` (the single float ~4.17/6.25 you saw) =
  **non-pad generated tokens ÷ number of decoder forward passes** = avg tokens
  produced per forward. Tests pin exactly this (`test_generation:264-307`).
- **To get the actual DENOISE STEP COUNT:** `generate()` does NOT return
  `decoder_forward_passes` directly (output has only `sequences`,
  `tokens_per_forward`, `past_key_values` — `:243-269`, `:825-827`). Recover it two
  ways: (a) `steps = num_valid_tokens / tokens_per_forward` (you know
  `num_valid_tokens` from `out.sequences`), or (b) **the streamer trace**:
  `len(streamer.steps)` counts `put_draft` calls = total denoise forward passes
  across all canvases. (b) is the authoritative per-step trajectory; (a) is the
  aggregate cross-check.

---

## 2. The streamer verdict — why our trace was empty + the fix

### 2.1 The exact call sites (VERBATIM)

`:688-689` (once, prompt):

```python
if streamer is not None:
    streamer.put(input_ids.cpu())
```

`:773-779` (THE per-step draft hook, inside the `for cur_step` loop):

```python
# If we have a draft-compatible streamer, put out the latest draft. We consider `argmax_canvas`
# to be the draft, as it is often the closest to the final output.
if streamer is not None and hasattr(streamer, "put_draft"):
    streamer_kwargs = {"value": argmax_canvas.cpu()}
    if getattr(streamer, "_takes_logits", False):
        streamer_kwargs = {"logits": self_conditioning_logits.cpu()}
    streamer.put_draft(**streamer_kwargs)
```

`:799-800` (once per finalized canvas):

```python
if streamer is not None:
    streamer.put(input_ids[:, -canvas_length:].cpu())
```

`:819-820` (`streamer.end()`).

### 2.2 Verdict: our protocol is CORRECT

The reference `TextDiffusionStreamer` (`generation/streamers.py:314-407`) defines
exactly: `put(value)`, `put_draft(value, **kwargs)`, `_takes_logits = False`
(`:367`), `end()`. Our `TraceStreamer` (`tmp/flash-diffgemma/diffgemma_common.py:81`)
mirrors it: `put(value)` (`:131`), `put_draft(self, value=None, logits=None)`
(`:136`), `_takes_logits = bool(with_entropy)` (`:119`), `end()` (`:165`). Both
call shapes from `:776` (`value=`) and `:778` (`logits=`) bind to our keyword
defaults. **The contract matches. put_draft IS reached per step on a real run.**

### 2.3 So why was the trace empty in ~0.5s?

It is NOT a contract mismatch (proven above). Source-consistent explanations,
ranked:

1. **The streamer was never attached.** Our `_trace_payload`
   (`gpu_worker.py:135`) returns a streamer ONLY when the payload's `trace` flag is
   truthy ("falsy -> no trace"). No flag → `streamer=None` → the `:775` guard is
   False → `steps == []`. Most likely cause of "empty".
2. **`generate()` raised inside the loop and the worker swallowed it.** The
   `_takes_logits=True` (entropy) path copies the FULL `(1, canvas_len, vocab)`
   logits to CPU EVERY step (`:778`). For a Gemma-scale vocab that is a multi-hundred-MB
   → GB copy per step; it can OOM/raise, and the worker's per-mode try/except returns
   an error dict, so `streamer.summary()` is never merged into the result. A ~0.5s
   wall time is far too short for a real 26B forward × N steps, which is itself the
   tell that the loop body never ran on the GPU.
3. **The loop recorded 0 steps** (e.g. `max_new_tokens`/`max_denoising_steps` so
   small the inner loop broke before any `put_draft`). Unlikely to give a *clean*
   empty list AND fast time together, but possible.

### 2.4 The fix (concrete)

1. **Start with the CHEAP trace: `_takes_logits=False`** (i.e. `with_entropy=False`).
   `generate()` then sends `value=argmax_canvas.cpu()` (`:776`) — a tiny
   `(1, canvas_len)` int tensor — so put_draft fires with negligible cost. This
   isolates "is put_draft called?" from "is the full-logit copy killing the run?".
   Our `TraceStreamer` already records `n_stable` from the argmax path
   (`diffgemma_common.py:151-159`) — the stability trajectory is available WITHOUT
   entropy.
2. **Assert the streamer ran:** check `len(streamer.steps) > 0` AND cross-check it
   against `out.tokens_per_forward` (§1.6). If `steps == []` but
   `tokens_per_forward` is a real number, the streamer simply wasn't attached
   (cause 1) — fix the payload flag. If BOTH are empty/error, generate() raised
   (cause 2) — surface the worker's caught exception.
3. **Only then enable entropy** (`_takes_logits=True`) and expect it to be SLOW by
   design (full-logit CPU copy per step). Consider down-selecting to watched
   positions on the GPU before `.cpu()` in a future custom path, since the reference
   loop hardcodes a full `.cpu()` copy at `:778` (you cannot change that without a
   custom loop — see §3).

---

## 3. Checkpoint / short-circuit feasibility verdict

The owner's design: denoise to step K → inspect partial canvas (parse+eval) → STOP
if valid, else re-noise bad spans + continue.

| Capability | Supported in 5.11.0? | Source |
| --- | --- | --- |
| Denoise to step K then stop, read partial canvas | **YES, but NOT a pure prefix of an N-step run** (see caveat below). `max_denoising_steps=K`; loop is `reversed(range(1, K+1))`; `out.sequences` is the committed `argmax_canvas`. | `:751`, `:786` |
| Observe partial canvas mid-denoise | **YES (read-only).** streamer `put_draft` per step gets `argmax_canvas` (or logits). Return value is discarded — cannot mutate state. | `:773-779` |
| Custom mid-denoise early-stop on parse/eval | **NOT via public API.** Only `StableAndConfidentStoppingCriteria` is wired, built from config. BUT `DiffusionGemmaAdaptiveStopping` is an ABC with abstract `__call__(argmax_canvas, logits)` — override `_prepare_diffusion_stopping_criteria` to return your own subclass. Runs on the default NON-compiled path (DynamicCache is not compileable → `is_compiling=False` → criterion not torch.compiled). | `:466-472`, `:1207-1221`, `:1059`, `:692` |
| Seed a starting canvas | **YES.** `decoder_input_ids` is popped as the start canvas; `self_conditioning_logits` likewise. | `:979-983` |
| Resume across calls (multi-turn) | **YES.** `past_key_values` is returned and accepted back. | `:826`, `:576`, `:635-636` |
| Pause-inspect-mutate-RESUME a SINGLE denoise loop | **NO (not first-class).** The within-loop renoise is emergent (sampler re-randomizes non-accepted positions); there is no user-addressable per-span re-noise inside one loop. | `:444-463`, `:756` |

**CAVEAT — `max_denoising_steps=K` is NOT a checkpoint at step K of an N>K
schedule.** The temperature schedule normalizes by `max_denoising_steps`:
`temperature = t_min + ((t_max - t_min) * (cur_step / self.max_denoising_steps))`
(`LinearTemperatureScheduleLogitsProcessor.__call__:311`). So setting
`max_denoising_steps=K` COMPRESSES the full t_max→t_min ramp into K steps — step 1
of a K=8 run uses a different temperature than step 1 of a K=48 run. Likewise the
`StableAndConfidentStoppingCriteria` may have terminated the inner loop EARLY before
reaching the cap (`:782`), so "K steps" is a cap, not a guarantee. Implication: if you
want the model's *natural* partial state at fraction f of its intended schedule, keep
`max_denoising_steps=N` (the deployed default) and STOP the loop externally at step K
(via the custom-stopping override in the table above) — do NOT shrink
`max_denoising_steps`. Shrinking it is a *different generation regime*, not a peek at
an intermediate state. (Cheap-but-imperfect approximation: scale `t_min`/`t_max` so
the K-step ramp matches the first K/N of the N-step ramp — but the cleaner answer is
the external stop.)

**Recommended path = OUTER loop of K-step `generate()` calls (public API only):**

1. `generate(..., max_denoising_steps=K, decoder_input_ids=seed, logits_processor=[Clamp(good_spans)], past_key_values=pkv)`.
2. Parse/eval `out.sequences[:, -canvas_length:]` (the argmax canvas).
3. If valid → done. If not → build a new `decoder_input_ids` with the good spans
   pinned (via `ClampLogitsProcessor`, which holds them at ~0 entropy so
   `accept_canvas` always keeps them — our clamp's documented mechanism,
   `diffgemma_common.py:43-50`) and the bad spans re-randomized, pass
   `past_key_values=out.past_key_values`, and call `generate()` again.

This reuses ONLY the public seams (`decoder_input_ids`, `logits_processor`,
`past_key_values`, `max_denoising_steps`). If you want a true single-loop checkpoint,
the building blocks (`_prepare_denoiser_inputs:960`, `_denoising_step:997`, the
`EntropyBoundSampler`) are all callable methods — write a custom loop that yields the
partial canvas at step K, runs parse/eval, and either breaks or calls
`sampler.renoise_canvas` on chosen spans. That is the only way to mutate mid-loop, and
it forks the generation code (maintenance cost).

---

## 4. Newer-transformers verdict (with diff evidence)

Tags fetched in the submodule: `v5.11.0`, `v5.12.0`, `v5.12.1`; `origin/main`
= 5.13.0.dev0 (`39f89b91de`). Compared WITHOUT moving the pinned HEAD (`git diff A..B`).

**`generation/streamers.py`: ZERO diff `v5.11.0..origin/main`.** The streamer
contract (incl. `put_draft`, `_takes_logits`) is unchanged. Our seam will not be
"fixed" by upgrading because it is not broken.

**`generation_diffusion_gemma.py` diff `v5.11.0..origin/main`** (74 lines; full diff
in this PR's scratch). Behaviorally relevant changes — and NONE touch the
streamer/logits/sampler/decoder_input_ids/tokens_per_forward seams:

- `+ return_dict_in_generate` (default True): `generate()` can return a bare
  `torch.LongTensor` instead of the output object (`:163`, `:232-233` of the diff).
  Pure convenience.
- `+ disable_compile` flag gating `is_compiling` (`:46`, `:196-198`). Convenience.
- `+ bos_token_id` field.
- **Removed the hardcoded defaults** `eos_token_id=[1,106,50]` and `pad_token_id=0`
  from `_get_default_generation_params` (`:62-68` of the diff); main instead pulls
  the model's own `generation_config.json`
  (`generation_config.update(**self.generation_config.to_dict(), ...)`, `:241`). This
  makes 5.13 depend MORE on the checkpoint's shipped config — a reason to stay on
  5.11.0 unless you also re-verify the checkpoint's config.
- Static-cache + attention-mask refactor that **renames internal cache APIs**
  (`max_cache_len`→`get_max_length`, `max_batch_size`→`batch_size`, `:274-282`) and
  changes the decoder-mask construction. Pure internal churn; also a mild
  incompatibility risk if any of our code reaches into the cache object.

`modeling_diffusion_gemma.py` diff (315 lines) adds `append_to_cache`, new output
classes, and arch plumbing — no generation-control hooks.

**Concrete upgrade verdict: DO NOT upgrade for this work.** Upgrading buys: a bare-tensor
return shortcut, a compile kill-switch, and `bos_token_id` — none needed. It does NOT
add per-step hooks, custom diffusion-stopping injection, a faster loop, or any sampler
change. It carries risk: the cache-API rename and the removal of default eos/pad mean
behavior now hinges on the deployed checkpoint's `generation_config.json`, which would
need re-validation. Staying on 5.11.0 keeps us byte-aligned with the worker and the
checkpoint. Re-evaluate only if 5.13.x ships an actual mid-denoise callback (it has
not as of `origin/main`).

---

## 5. What else we can improve (every item source-cited)

1. **Default to the CHEAP (argmax) trace; gate entropy hard.** The reference loop
   hardcodes a full `(1, canvas_len, vocab)` `.cpu()` copy for `_takes_logits=True`
   (`:778`). On a real run that is the dominant cost and the likely OOM. Use
   `_takes_logits=False` for the step/stability trajectory (our `n_stable` path
   works without logits, `diffgemma_common.py:151-159`); reach for entropy only when
   you specifically need per-position entropy on a SHORT canvas. (Improves §2.3.)

2. **Stop trusting wall-clock; assert against `tokens_per_forward`.** Always read
   `out.tokens_per_forward` and reconcile with `len(streamer.steps)` (§1.6). A "fast +
   empty" result is diagnostic of a not-attached streamer or a swallowed exception,
   not of the model. Surface the worker's caught exception in the mode result.

3. **Use the supported "denoise to K" primitive — but mind the temperature
   normalization.** `max_denoising_steps=K` + reading `out.sequences` gives a
   partial-canvas checkpoint (`:751`, `:786`), and many denoise→inspect→continue
   experiments can be an OUTER loop of K-step `generate()` calls (§3) with NO fork of
   transformers. CAVEAT (see §3): shrinking `max_denoising_steps` COMPRESSES the
   temperature ramp (`:311`), so it is a different regime, not a peek at step K of an
   N-step run. To inspect the natural intermediate state, keep
   `max_denoising_steps=N` and stop externally (item 4).

4. **For a true custom early-stop, override `_prepare_diffusion_stopping_criteria`,
   don't fork `generate()`.** `DiffusionGemmaAdaptiveStopping` is an ABC (`:466`); a
   subclass whose `__call__(argmax_canvas, processed_logits)` decodes the argmax
   canvas, runs the seon parser/eval, and returns a per-row bool plugs into the exact
   per-step update site (`:1059`). Keep the default DynamicCache so `is_compiling`
   stays False (`:692`) and your Python parse/eval doesn't break torch.compile
   (`:1258-1263`). This is the minimal-surface path to "stop when the partial canvas
   already parses".

5. **`decoder_input_ids` + `self_conditioning_logits` are both seedable** (`:979-983`),
   not just `decoder_input_ids`. Warm-starting the self-conditioning logits (e.g.
   from a previous K-step run's last `processed_logits`) is a free continuation lever
   we are not using.

6. **Multi-turn / continuation should pass `past_key_values` back in** (`:826`,
   `:635-636`). For the eval-renoise outer loop, feeding `out.past_key_values` into
   the next `generate()` avoids re-encoding the committed prefix.

7. **Clamp ordering is already correct and worth keeping explicit.** External
   `logits_processor` runs before the temperature schedule (`:1170-1181`), and a
   near-one-hot survives division by `t` — our `ClampLogitsProcessor` docstring
   documents this (`diffgemma_common.py:52-59`). No change needed; just don't reorder.

8. **Batching is free leverage.** The whole loop is batched (`finished_denoising`,
   `decoder_forward_passes`, per-row stopping are all `(batch,)` tensors —
   `:658-659`, `:754`, `:1052-1059`). Driving B prompts (or B re-noise variants of one
   canvas) in one `generate()` amortizes the encoder prefill and the per-step decoder
   forward. `tokens_per_forward` is reported per-row (`test_generation:281`), so the
   metric survives batching.

9. **`cache_implementation="static"` enables `torch.compile`** of the encoder/decoder/
   sampler (`:692-696`, `:1235-1265`; model_doc note `:68-69`). It is OFF by default
   (DynamicCache). For throughput experiments it is the documented fast path — but it
   is mutually exclusive with item 4 (a Python stopping criterion) because the
   criterion gets compiled. Choose per experiment: compiled+fast OR custom-stop.

10. **`mask_token_id=4` is vestigial — do not build infill on a mask token.** The
    generation path uses random-token noise, not masking (`:392`, `:461`; only
    `masked_scatter` for image embeds at `modeling:1094`). Infill must be done by
    CLAMPING the kept spans (our `ClampLogitsProcessor`) + random-noising the hole,
    which is exactly what our `infill` mode already does. Confirmed correct approach.

---

## Source index (line numbers are v5.11.0, HEAD `e7b5b964e6`)

- `generate` outer/inner loops: `:542`, `:713`, `:751`, `:786`.
- per-step logits apply: `:1034`. self-conditioning feedback: `:1062`.
- streamer calls: `:688`, `:773-779`, `:799`, `:819`.
- sampler accept/renoise/init: `:400`, `:444`, `:388`.
- diffusion stopping (inner) + its prep: `:478`, `:1207`. AR stopping (outer): `:1185`, `:1072`.
- step metric: `:829`. output fields: `:243-269`.
- seedable canvas / self-cond: `:979-983`. compile path: `:692`, `:1235`.
- our worker: `tmp/flash-diffgemma/gpu_worker.py` (modes), `diffgemma_common.py:35` (Clamp), `:81` (TraceStreamer).
