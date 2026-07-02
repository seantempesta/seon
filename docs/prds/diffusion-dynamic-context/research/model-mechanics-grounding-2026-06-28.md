---
type: research
status: active
tags: [research, agent, flow]
---

# DiffusionGemma model mechanics — source-grounded (2026-06-28)

## TL;DR

The owner halted experiments because our worker crashed on a wrong assumption
about `generate()`'s return value. Grounding that bug against the **actual
transformers source** (now readable locally — see "Source of truth") surfaced a
**second, far larger wrong assumption that runs through every stub and research
doc**: we modelled DiffusionGemma as an **absorbing-MASK** diffuser (canvas
starts all-`<mask>`, low-entropy tokens "commit" and lock, non-committed
positions hold a MASK sentinel, re-noise = "set back to MASK"). **That is the
bd3lms mechanism, not DiffusionGemma's.** The shipped model has **no mask token
anywhere in its generation/modeling/config code**. Its canvas denoises by
**random re-initialisation** (`EntropyBoundSampler`): non-accepted positions are
overwritten with *uniformly random vocab ids*, never a MASK id. The
`resolve_mask_id` / `build_offset_map`-by-mask-holes machinery in
`diffgemma_common.py` is built on a premise the source contradicts.

The good news: the owner's **slotted-guided-generation** idea is still
achievable — but via a **custom `LogitsProcessor` (a documented, supported
`generate()` seam)**, *not* via a "MASK holes + clamp typed tokens" canvas
construction. Verdict below: **YES, with custom code through supported
extension points; NO native "clamp these positions" kwarg.**

### Source of truth

`transformers==5.12.1` is installed in the project venv and the DiffusionGemma
modeling code is readable at:

```
tmp/flash-diffgemma/.venv/lib/python3.12/site-packages/transformers/models/diffusion_gemma/
  configuration_diffusion_gemma.py   (214 lines)
  generation_diffusion_gemma.py      (1331 lines)  <- the decode loop + sampler + output object
  modeling_diffusion_gemma.py        (1697 lines)
```

All `file:line` citations below are into that directory. **Version caveat:** the
worker pins `transformers==5.11.0`; the readable copy is `5.12.1`. The classes,
`model_type`, output object, and sampler are the public API the model card
targets and are extremely unlikely to differ — but the one introspect probe that
matters (output-object field names, `generate` signature) should re-confirm
against the 5.11.0 the worker actually loads. Treat 5.12.1 as authoritative for
*mechanism*; confirm *exact field/kwarg names* on the live 5.11.0.

---

## 1. How the model ACTUALLY works

### 1a. Block diffusion: AR over blocks, diffusion within a block

`generate()` is an **outer autoregressive loop over canvases (blocks)** with an
**inner diffusion loop per canvas** (`generate` docstring + body,
`generation_diffusion_gemma.py:556-575`, loop `:716-822`).

- `config.canvas_length` **defaults to 256** and is the **block length**, not the
  whole output (`configuration_diffusion_gemma.py:152-153, 196`). The number of
  blocks is `max_new_canvases = ceil(max_new_tokens / canvas_length)`
  (`generation_diffusion_gemma.py:643`). So a 256-token request is **one** canvas;
  asking for more produces **multiple AR blocks**, each denoised internally. The
  research-doc claim "it generates a *canvas*, not a stream … default canvas =
  256" is right for one block but **wrong if it implies the entire generation is a
  single refined 256-canvas** — long outputs chain blocks AR-style.
- The **prompt is encoded once** by an encoder into a **KV cache**; each denoising
  step's decoder cross-attends/prefix-attends that cache rather than
  re-encoding (`:720-741` encoder pass → `past_key_values`; `:1027-1036` decoder
  step consumes `past_key_values`). This matches the "AR encoder cached once,
  cross-attended" claim.
- The decoder attends **bidirectionally within the canvas**
  (`use_bidirectional_attention`, `configuration_diffusion_gemma.py:105, 113-115`)
  — every canvas position attends to every other. **Co-conditioning across canvas
  positions is real** (load-bearing for the slot idea).

### 1b. The canvas is RANDOM-initialised and RANDOM-renoised — there is NO mask token

This is the central correction. `EntropyBoundSampler`
(`generation_diffusion_gemma.py:339-465`):

- **Init** (`initialize_canvas`, `:390-400`): the starting canvas is
  `torch.randint(0, vocab_size, …)` — **uniformly random token ids**, not a MASK
  sentinel.
- **Accept** (`accept_canvas`, `:402-444`): each step computes per-position
  **entropy** of the logits, sorts ascending, and accepts the `k` lowest-entropy
  positions such that `cumulative_entropy - max(entropy_1..k) <= entropy_bound`
  (`:439`). `accepted_canvas = torch.where(accepted_mask, denoiser_canvas,
  current_canvas)` (`:443`) — accepted positions take the new prediction,
  non-accepted keep their current value.
- **Renoise** (`renoise_canvas`, `:446-465`): non-accepted positions are
  overwritten with **fresh uniformly-random ids** (`renoise_mask = ~accepted_mask;
  random_canvas = self.initialize_canvas(...); torch.where(renoise_mask,
  random_canvas, accepted_canvas)`, `:462-464`). **Re-noise = random token, NOT a
  MASK id.**
- **No locking / no permanent commit.** `accepted_token_mask` is **recomputed from
  scratch every step** from the current logits (`:435-442`). A position accepted
  at step *t* carries its value into *t+1* as `current_canvas` and is re-evaluated;
  if its entropy is still low it stays, otherwise it can be **renoised again**.
  This *is* the paper's "≈7.5 re-masks per generation" — it is **emergent from the
  entropy bound, not a separate re-mask mechanism**. There is no "committed and
  frozen" state in the sampler.
- **What controls commit rate:** `entropy_bound` (`EntropyBoundSamplerConfig`,
  `:315-336`; default **0.1**, `:225`). Higher bound → more positions accepted per
  step.

Confirming the absence of a mask token: `grep -ni 'mask_token|absorb|<mask>|
mask_id|mask_index'` over all three diffusion_gemma files returns **zero hits**.
The model's special tokens are `pad=0, eos=1, bos=2` (`configuration:95-97`); there
is no mask id.

**Contrast — where the MASK assumption came from (bd3lms, the ancestor):**
`reference-code/bd3lms/diffusion.py` *does* use an absorbing mask:
`self.mask_index = vocab_size` (or `tokenizer.mask_token_id`) at `:55-58`, and the
noising primitive is literally `xt = torch.where(move_indices, self.mask_index,
x)` (`:497, :519`) with `copy_flag = (x != mask_index)` carry-over (`:592-593`).
bd3lms is the **academic inspiration**; DiffusionGemma's production sampler
**replaced absorbing-mask with entropy-bounded random renoise**. Our docs ported
bd3lms's mechanism onto DiffusionGemma. That port is invalid.

### 1c. The real decode step

`_denoising_step` (`generation_diffusion_gemma.py:1003-1076`), called once per
`cur_step` in `reversed(range(1, max_denoising_steps+1))` (`:757`):

1. decoder forward over `current_canvas` + `past_key_values` (prompt KV) +
   `self_conditioning_logits` → `raw_logits` (`:1029-1037`).
2. apply `logits_processor(input_ids, raw_logits, cur_step=…)` → `processed_logits`
   (`:1040`). **This is the supported injection point.**
3. sample `denoiser_canvas` (multinomial) and `argmax_canvas` (`:1045-1047`).
4. `accepted = sampler.accept_canvas(current_canvas, denoiser_canvas,
   processed_logits, cur_step)` then `new_current = sampler.renoise_canvas(accepted,
   cur_step)` (`:1050-1053`).
5. update `StableAndConfidentStoppingCriteria` (`:1056-1065`) — stops the inner
   loop when the argmax canvas is stable across `stability_threshold` steps **and**
   mean entropy `< confidence_threshold`.
6. `processed_logits` become next step's `self_conditioning_logits` (`:1068-1069`).

The block's **final committed output is `argmax_canvas`** (the argmax of the last
logits), which is appended to `input_ids` (`:753, :792`) — *not* `current_canvas`
(which still has random renoise in non-converged positions).

---

## 2. The real `generate()` API + output object

### Output object (THE crash)

`generate()` returns a **`DiffusionGemmaGenerationOutput(ModelOutput)`**
dataclass (`generation_diffusion_gemma.py:242-269`, returned at `:831-833`):

| field | type | meaning |
|-------|------|---------|
| `sequences` | `LongTensor (batch, seq_len)` | generated ids **incl. the prompt** |
| `tokens_per_forward` | `int`/tensor | diffusion throughput metric |
| `past_key_values` | `Cache` | **pass back into `generate()` to resume multi-turn** |
| `logits` / `scores` / `hidden_states` | `None` | unused, BC stubs |

It is a `ModelOutput`, so it has **no `.shape`** and `out[0]` returns the **first
field (`.sequences`)**, not "batch row 0". That is exactly our bug.

### `generate()` signature & kwargs (`:545-554`, docstring `:577-629`)

```
generate(input_ids=None, past_key_values=None, streamer=None,
         generation_config=None, logits_processor=None,
         stopping_criteria=None, **kwargs)
```

Load-bearing kwargs (all confirmed in source):

- **`decoder_input_ids`** — **seeds the starting canvas** (docstring `:607-608`
  "you can set the starting canvas with `decoder_input_ids`"; consumed at
  `_prepare_denoiser_inputs:985-987` as `current_canvas = model_kwargs.pop(
  "decoder_input_ids", sampler.initialize_canvas(...))`). **Caveat:** it is only
  the *first* step's canvas — accept/renoise then treat it like any random init
  (no clamp). Useful for *warm-starting*, not for *holding fixed*.
- **`self_conditioning_logits`** — seed the self-conditioning signal (`:989`).
- **`past_key_values`** — resume a prior session; the returned cache feeds the next
  call (`:580-582, 640-641`).
- **`logits_processor`** (`LogitsProcessorList`) — applied **first**, before the
  built-in temperature schedule (`:595-599, 1040, 1176-1189`). **This is the clamp
  seam** (see §4).
- **`stopping_criteria`**, **`max_new_tokens`/`max_length`**, and
  generation-config attrs surfaced as kwargs: `max_denoising_steps`,
  `sampler_config` (`EntropyBoundSamplerConfig`), `t_min`/`t_max` (temperature
  schedule), `stability_threshold`, `confidence_threshold`
  (`generation_diffusion_gemma.py:122-132` config fields).

### Correct fix for gpu_worker.py:256, 258

Current (broken):

```python
out = model.generate(**inp, max_new_tokens=payload.get("max_new_tokens", 256))
ncomp = int(out.shape[-1]) - nprompt                      # L256 AttributeError: ModelOutput has no .shape
"text": tkz.decode(out[0][nprompt:], skip_special_tokens=True),  # L258 out[0] is .sequences, slices batch dim
```

Correct:

```python
out = model.generate(**inp, max_new_tokens=payload.get("max_new_tokens", 256))
seqs = out.sequences                                      # LongTensor (batch, seq_len), incl. prompt
ncomp = int(seqs.shape[-1]) - nprompt
"text": tkz.decode(seqs[0][nprompt:], skip_special_tokens=True),
```

(Optionally surface `out.tokens_per_forward` as the diffusion throughput metric.)

---

## 3. The `accept_canvas` / EntropyBoundSampler seam

- **Signature** (`:402-408`): `accept_canvas(self, current_canvas, denoiser_canvas,
  logits, cur_step) -> accepted_canvas`. **Returns** a new canvas tensor; it does
  **not** mutate `current_canvas` in place (it *does* set
  `self.accepted_token_mask` as a side effect, read by `renoise_canvas`). So our
  stubs' open question "RETURN vs mutate" → **RETURN** (U3 answered).
- **Where called** (`:1050`, inside `_denoising_step`). It receives the running
  canvas, the freshly multinomial-sampled `denoiser_canvas`, the
  **`processed_logits`** (post-logits-processor), and `cur_step`.
- **What it can reach:** only canvas tensors + logits. It has **no access to the
  encoder KV cache** and **no position metadata** — a clamp driven from here would
  need a clamp-mask injected via a closure/attribute on a sampler subclass.
- **It is `torch.compile`d** when a compileable cache is used (`:1254-1256`,
  `fullgraph=True`). Monkeypatching/subclassing `accept_canvas` with Python
  control flow (e.g. a `torch.where(clamp_mask, clamp_ids, accepted)`) is fine if
  it stays graph-compatible, but the **cleaner, compile-safe, supported** route is
  a `LogitsProcessor` (§4), which the loop already wires in at `:1040`.
- The sampler instance is **constructed per `generate()` call** by
  `_prepare_sampler` from `generation_config.sampler_config` (`:1229-1239`) — it is
  **not** a persistent `model.sampler` attribute. To override it you must either
  pass a custom `sampler_config`/subclass through generation config or patch after
  `_prepare_sampler` (harder) — again favouring the logits-processor route.

---

## 4. Verdict on the owner's slotted-guided-generation idea

**Idea:** a structured canvas with CLAMPED scaffold slots + retrieval-INJECTED
spec slots + denoising reasoning/answer slots, all co-conditioning via
bidirectional attention; the primitive = "hold chosen positions FIXED across all
steps while others denoise."

**Verdict: YES — achievable, but only through a custom `LogitsProcessor` (a
documented, supported `generate()` seam). There is NO native "clamp these
positions" kwarg, and the MASK-holes construction our docs assumed does not
exist.**

Why it works:

- **Co-conditioning is real and free.** The decoder denoises the whole canvas with
  **bidirectional** attention (`config use_bidirectional_attention`) and
  cross-attends the prompt KV cache. Clamped slots therefore *do* condition the
  denoising slots within the same canvas. ✅
- **Clamping is expressible as a logits constraint.** A `LogitsProcessor` runs at
  `:1040` every step with `(input_ids, raw_logits, cur_step)` and full access to
  the `(batch, canvas_length, vocab)` logits. For each clamped position set its
  logits to a near-one-hot on the clamp token (`-inf` elsewhere). Consequences,
  all from the source:
  - entropy at that position → ~0, so `accept_canvas` (`:435-443`) **always
    accepts it** (lowest entropy, inside the cumulative bound);
  - `renoise_canvas` (`:462-464`) only renoises **non-accepted** positions, so an
    always-accepted position is **never renoised**;
  - multinomial/argmax (`:1045-1047`) pick the clamp token deterministically.
  Net effect: the position is **held fixed across all steps** — exactly the
  requested primitive, achieved through the supported seam without touching model
  internals. ✅ (**This is the single most important correction to the plan: clamp
  via forced logits, not via writing a MASK id into a canvas.**)
- **Seeding initial content** for clamped/injected slots: pass the scaffold/spec
  ids as `decoder_input_ids` (warm-start) **and** keep them pinned with the
  logits-processor (the warm-start alone is NOT enough — without the processor the
  seed is renoised away). ✅
- **Retrieval / spec injection into the prompt:** done by extending the encoder
  side (re-prefill the prompt with the retrieved spec, or extend `past_key_values`
  between blocks) — this is the §"W" encoder/cross-attn question, still genuinely
  open (see §5). The *intra-canvas* clamp is settled; the *cross-attn cache
  extension mid-generate* is not.

What is **NOT** supported / must be dropped:

- ❌ "Canvas starts all-MASK; holes denoise; typed tokens clamped vs MASK." No
  mask token exists. Re-frame every "MASK hole" as "an unconstrained
  (random-init) position" and every "clamp" as "a forced-logits position."
- ❌ `resolve_mask_id` / `build_offset_map` keying on a mask id to detect "holes."
  In this model **every canvas position always holds a real token id** (random or
  committed); there are no mask holes to skip. The offset-map char↔token mapping is
  still useful for span→position work, but it must map **all** positions (decode
  the whole canvas), not "skip MASK holes."

---

## 5. WRONG-ASSUMPTIONS list (file → what's wrong → fix)

1. **`gpu_worker.py:256`** — `int(out.shape[-1])`. `out` is a `ModelOutput` with no
   `.shape`. **Fix:** `int(out.sequences.shape[-1])`. (Source: `:242-269, 831-833`.)
2. **`gpu_worker.py:258`** — `out[0][nprompt:]`. `out[0]` is `.sequences` (the whole
   `(batch, seq)` tensor), so this slices the **batch** dim. **Fix:**
   `out.sequences[0][nprompt:]`.
3. **`diffgemma_common.py` `resolve_mask_id` (whole fn)** — assumes a canvas MASK
   token exists and is load-bearing for "hole detection / clamp / re-mask." **The
   model has no mask token** (grep-confirmed zero hits; sampler uses random
   renoise, `:390-465`). The fn will return `mask_id=None` and the stubs'
   "FAIL LOUD" path fires — but the real conclusion is **the whole mask-hole model
   is wrong**, not that the id is unresolved. **Fix:** delete the mask-hole framing;
   replace "re-mask a position" with "force a position's logits to re-open it"
   (drop the clamp on it) and let the entropy bound re-decide it.
4. **`diffgemma_common.py` `build_offset_map`** — skips positions equal to
   `mask_id` as "holes" (`:128-129`). With no mask token, **no position is ever a
   hole**; passing `mask_id=None` already makes it map all positions, so the
   *output* is incidentally fine, but the documented intent ("MASK positions are
   holes") is wrong. **Fix:** rewrite the docstring/contract — it maps **every**
   canvas position; there are no holes.
5. **`staged/gpu_worker_infill.py:21-52`** — "canvas starts all-MASK; commit
   low-entropy; infill = keep typed tokens, never let MASK overwrite; holes
   denoise." All MASK-based. **Fix:** infill = seed prefix+suffix via
   `decoder_input_ids`, **pin** prefix+suffix with a forced-logits processor, leave
   the middle positions unconstrained (they random-init and denoise). No MASK.
6. **`staged/gpu_worker_infill.py:32-37, 58-64` (U3 "return vs mutate")** —
   open question. **Answered:** `accept_canvas` **returns** the next canvas
   (`:443`); the clamp belongs in a `LogitsProcessor` at `:1040`, not in an
   accept_canvas mutation.
7. **`staged/gpu_worker_renoise.py:14-44`** — "renoise = set positions back to
   MASK, clamp the rest" citing bd3lms `xt = where(move_indices, mask_index, x)`.
   That is **bd3lms, not DiffusionGemma**. **Fix:** to "re-open" a committed span,
   stop forcing its logits (drop it from the clamp set) so the entropy bound
   re-decides it; the *rest* stay clamped via the processor. The bd3lms
   `_ddpm_caching_update` copy_flag logic (`diffusion.py:592-593`) does **not**
   exist in the shipped sampler.
8. **`staged/gpu_worker_renoise.py:45` (correct instinct, wrong reason)** —
   "commits are NOT frozen in DiffusionGemma (~7.5 re-mask)." The *conclusion* is
   right; the *mechanism* is the entropy-bound recompute each step (`:435-442`),
   not a mask operation.
9. **`staged/gpu_worker_retrieval.py:17-26, 60-95`** — "all-MASK 256-canvas" +
   "set renoise_positions back to MASK." Same MASK error. The encoder/cross-attn
   **W1-W3** questions (can the KV cache be extended mid-generate; does the
   cross-attn mask auto-widen) are **still genuinely open** and need live
   introspect — they are *not* contradicted by the source, just unanswered.
10. **`docs/prds/agent-fsm/research/diffusion-llm-live-context-2026-06-27.md:22-50,
    74, 116-137`** — describes the canvas as committing/locking and the typeahead
    trick as "mask = holes, fixed = typed." Re-frame all of it: commit = emergent
    low-entropy persistence (not a lock), clamp = forced logits (not MASK
    construction). The doc's correct calls: `accept_canvas` is the open transformers
    seam (✅ `:402`), entropy bound default 0.1 (✅ `:225`), bidirectional 256
    canvas cross-attending the cached prompt (✅), `DiffusionGemmaForBlockDiffusion`
    / `model_type` (✅). The `logits` shape `[1, 256, 262144]` matches
    `vocab_size=262144` (`configuration:84`) × `canvas_length=256`.

---

## 6. Minimal corrected `introspect` probe list

The source settles almost everything above. What it **cannot** settle (needs a
live probe against the loaded 5.11.0 model) — keep these, drop the mask-id probes:

1. **Output object on 5.11.0** — `type(out).__name__`, `list(out.keys())`,
   `hasattr(out, "sequences")`. Confirms the field names match 5.12.1.
   *(Expected: `DiffusionGemmaGenerationOutput`, `["sequences",
   "tokens_per_forward", "past_key_values", …]`.)*
2. **`generate` signature on 5.11.0** —
   `inspect.signature(model.generate)` and a grep of the bound method's module for
   `decoder_input_ids` / `self_conditioning_logits` handling. Confirms the
   warm-start seam exists in 5.11.0.
3. **`config` block params** — `model.config.canvas_length`,
   `model.config.text_config.vocab_size`,
   `model.config.text_config.use_bidirectional_attention`,
   `model.generation_config` dump (`max_denoising_steps`, `sampler_config`,
   `entropy_bound`, `stability_threshold`, `confidence_threshold`, `t_min/t_max`).
4. **Clamp feasibility smoke test (the one experiment worth running first)** — a
   tiny custom `LogitsProcessor` that forces 3 chosen canvas positions to fixed
   token ids; run `generate(max_new_tokens=256, logits_processor=[clamp])`; assert
   `out.sequences[0][nprompt:][those positions] == the forced ids`. **This is the
   single decisive test for the whole slot architecture** — if it holds, the clamp
   primitive is proven on the real model.
5. **Encoder / cross-attn (W1-W3, still open)** — `hasattr(model, "encoder")` /
   `model.model.encoder`, its forward signature, and whether `past_key_values`
   returned from one `generate()` can be extended and fed to the next so an
   injected spec is cross-attended. (The §4 clamp does **not** depend on this; only
   the retrieval-injection capability does.)
6. **Thinking/reasoning mode — HONEST UNKNOWN, undeterminable from local source.**
   The 50GB checkpoint (incl. `tokenizer_config.json` / chat template / special
   tokens) is **not** downloaded locally, so I cannot confirm whether
   `google/diffusiongemma-26B-A4B-it` has an explicit think token or reasoning
   mode. **Must confirm via introspect:** dump
   `tok.special_tokens_map`, `tok.additional_special_tokens`, and
   `tok.apply_chat_template([{...}], tokenize=False, add_generation_prompt=True)`
   and grep the rendered template for `think`/`reason`/`<thinking>`-style markers.
   Until then, assume **no** dedicated thinking mode; a "thinking canvas" would be
   an *us*-imposed structure (reasoning slots in the canvas), not a model feature.

---

## Appendix — one-line citations

- Output object: `generation_diffusion_gemma.py:242-269` (fields), `:831-833` (return).
- `generate` sig/kwargs: `:545-554`; `decoder_input_ids` seeds canvas `:607-608, 985-987`; `past_key_values` resume `:580-582`.
- EntropyBoundSampler: random init `:390-400`; `accept_canvas` `:402-444` (entropy bound `:439`, where `:443`); `renoise_canvas` random `:446-465` (`:462-464`).
- Decode loop: `_denoising_step:1003-1076`; logits-processor seam `:1040`; sampler called `:1050`; argmax appended `:753, :792`; AR block loop `:716-822`; `max_new_canvases` `:643`.
- Config: `canvas_length=256` `configuration_diffusion_gemma.py:152, 196`; `use_bidirectional_attention` `:105, 113-115`; tokens `pad/eos/bos=0/1/2` `:95-97`; `vocab_size=262144` `:84`.
- Default `entropy_bound=0.1`: `generation_diffusion_gemma.py:225`.
- No mask token: grep `mask_token|absorb|<mask>|mask_id|mask_index` over all 3 files → 0 hits.
- bd3lms ancestor (absorbing mask): `reference-code/bd3lms/diffusion.py:55-58, 497, 519, 592-593`.
