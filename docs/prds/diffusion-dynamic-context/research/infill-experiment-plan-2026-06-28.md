---
type: research
status: draft
tags: [research, agent, web]
---

# Infill experiment plan — Capability #1 (typeahead via infilling)

> Ready-to-run plan + worker stub so that the MOMENT DiffusionGemma deploys on
> the A100 we run the first dynamic-context experiment without scrambling.
> Capability #1 is the cheapest "clearly better than AR" win and needs **no Seon
> integration** — it is a canvas-construction trick, not a sampler fork.
> Companion to [[index]], [[../../agent-fsm/research/diffusion-llm-live-context-2026-06-27]]
> (the `accept_canvas` mechanism) and the T2 rung of
> [[../../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]].

## TL;DR

- **The win:** clamp the human's typed tokens (a prefix and/or suffix) as fixed
  canvas positions and let the diffusion decoder denoise only the HOLES between
  them. Bidirectional attention conditions the fill on tokens **after** the
  cursor (the suffix) — the structural advantage AR completion fundamentally
  lacks (Copilot et al. see prefix only).
- **The mechanism is grounded** (bd3lms source + the Transformer Lab paper +
  the model card): the canvas starts all-MASK; each step commits low-entropy
  tokens and re-masks the rest; the per-step commit is
  `EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
  cur_step)`. Infilling = the inverse of bd3lms's
  `xt = torch.where(move_indices, mask_index, x)`: never overwrite the typed
  positions with mask; only the holes denoise.
- **The honest gap:** the model card's public call is
  `model.generate(**inputs, max_new_tokens=N)`, which seeds an all-MASK canvas.
  HOW to seed a *partial* canvas (prefix + holes + suffix) and HOW the clamp is
  enforced are **not determinable from docs alone**. Four unknowns (U1–U4 below)
  must be confirmed against the real model on first deploy. The worker ships a
  `mode="introspect"` that resolves all four in ONE A100 call.
- **Worker stub:** `tmp/flash-diffgemma/gpu_worker_infill.py` — separate from the
  proven `gpu_worker.py` generate path so nothing working is disturbed. Modes:
  `env` (health), `introspect` (the first-deploy oracle), `infill`
  (`{prefix, suffix, max_hole_tokens}` → infilled span + per-step diagnostics).

## The mechanism (grounded, not guessed)

From `reference-code/bd3lms/diffusion.py` (the block-diffusion testbed) +
arXiv:2606.14620 (Transformer Lab, the `accept_canvas` reverse-engineering) +
the DiffusionGemma model card:

1. **Encoder prefill (once).** An AR pass over the prompt → KV cache. This is
   where Seon's *ai render* lands. The prompt is cross-attended every step, never
   regenerated.
2. **Decoder canvas (256 tokens, bidirectional).** Starts all-MASK. Each of ≤48
   denoise steps: the sampler commits the lowest-entropy tokens under the
   entropy bound (0.1), **re-noises the rest** (overwrites them with the mask id),
   and repeats; adaptive-stop at mean entropy < 0.005. Commits are NOT frozen —
   the paper measured ~7.5 re-mask events/gen — so in-place revision is native.
3. **The masking primitive** (bd3lms, ~L519):
   `xt = torch.where(move_indices, self.mask_index, x)` — a position is "noised"
   by overwriting it with `mask_index`. **Infilling is the inverse:** the typed
   positions are in `x` and must be excluded from `move_indices` forever (clamp),
   while the hole positions are free to denoise.
4. **Why it beats AR:** the holes attend bidirectionally to the suffix. For
   `(defn mean [xs] (/ ⟨HOLE⟩ (count xs)))` the model SEES `(count xs)))` while
   filling the hole, so it knows the divisor is already written and the hole is
   the dividend `(reduce + xs)`. A left-to-right model emitting after `(/ ` is
   blind to the suffix and can double-count or mis-close.

### The control seam

`EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
cur_step)`, logits `[1, 256, 262144]`, open `transformers` 5.11.0, model class
`DiffusionGemmaForBlockDiffusion`, model_type `diffusion_gemma`, **no
`trust_remote_code`**. The paper wrapped it as an observational forward hook; we
**override** it to: (a) force the clamped (typed) positions back to their token
ids every step so they can never re-mask, and (b) log which hole positions
committed at which step and their per-position entropy (free T1-style
observability, reused later as the capability-3 retrieval trigger).

## The four unknowns — MUST CONFIRM ON FIRST DEPLOY

These cannot be resolved from docs; the model card only shows the all-MASK
`generate()` call. `mode="introspect"` in the stub reflects over the live model
and returns answers to all four in one call. **Do not fabricate a `generate()`
infill call before introspect resolves U1–U3** — the stub intentionally does NOT
issue a guessed generate.

- **U1 — canvas seeding.** How does `generate()` accept a non-empty canvas
  (`[prefix_ids … MASK*H … suffix_ids]`)? Priority order of candidate seams:
  (a) a `generate()` kwarg (`decoder_input_ids` / `canvas` / `canvas_ids` /
  `infill_mask` / `prefix_ids`+`suffix_ids`) — introspect reads the signature;
  (b) tokenizer FIM sentinels (`<|fim_prefix|>`/`<|fim_suffix|>`/`<|fim_middle|>`
  or a `<mask>` the chat template understands) — introspect reads the
  special-token map; (c) no public seam → seed the canvas inside the
  `accept_canvas` override on `cur_step==0`.
- **U2 — sampler attach.** What sampler instance does `generate()` actually use,
  and how is it reachable (model attribute? built per-call from
  `generation_config`? a `custom_sampler()`/`get_sampler()` hook)? We must
  monkeypatch the instance generate() uses, not a stray class.
- **U3 — accept_canvas contract.** Does it RETURN the accepted canvas or mutate
  `current_canvas` in place? Which arg is the running canvas vs the post-denoise
  proposal? (Determines whether the clamp is `canvas[clamp]=ids` before return or
  a `torch.where` over the return value.) introspect dumps the source.
- **U4 — mask id + layout.** `tokenizer.mask_token_id` (or a reserved id), and
  the exact 256-canvas/block layout so `prefix + holes + suffix ≤ 256` fits one
  block (else reject or go multi-block).

## Worker-mode design (`gpu_worker_infill.py`)

Standalone `@Endpoint` `diffgemma-infill` (same A100 / NetworkVolume / env as
`gpu_worker.py`). Modes:

| mode | input | output | purpose |
|---|---|---|---|
| `env` | — | import/health info | cheap liveness |
| `introspect` | — | U1–U4 answers (generate params, sampler attrs, `accept_canvas` source + sig, mask id, canvas cfg, FIM specials) | **the first-deploy oracle** |
| `infill` | `{prefix, suffix, max_hole_tokens}` | `{text, steps:[{step, committed_holes, hole_entropy}], planned_layout}` | the experiment |

**`infill` flow** (once U1–U3 are confirmed and the override is enabled):
tokenize prefix/suffix → lay out the canvas `[pre_ids … MASK*max_hole …
suf_ids]`, holes in the middle → install the `accept_canvas` override that clamps
the typed positions every step and records hole commits/entropy → `generate()` →
decode the hole span. Per-step diagnostics give the same commit-entropy signal
the paper used (and the capability-3 trigger for free).

The stub's `infill` mode currently builds the layout + override and reports
`infill_status: STUB` WITHOUT issuing a guessed generate — the override is wired
to the real sampler only after introspect resolves U2/U3. This is deliberate:
ship the plumbing, not a fabricated result.

## Test cases (encoded in the stub as `INFILL_CASES`)

Each is a suffix-constrained Clojure fill where bidirectional attention should
beat AR. `expect_contains` is the win condition; `ar_handicap` is what a
left-to-right model is missing.

1. **mean-reduce** (the canonical case)
   - prefix `(defn mean [xs] (/ ` · suffix ` (count xs)))` · hole ≤16
   - expect `(reduce + xs)`
   - **AR handicap:** AR sees `(/ ` and must guess the dividend BLIND to
     `(count xs)))`. It can't know the divisor is already written, so it may
     emit `(apply + xs) (count xs)` (double count) or mis-close the form.
2. **map-inc**
   - prefix `(defn inc-all [xs] (map ` · suffix ` xs))` · hole ≤8
   - expect `inc`
   - **AR handicap:** blind to trailing `xs))`, AR may emit `(fn [x] (inc x)) xs`
     duplicating the seq arg.
3. **let-binding**
   - prefix `(defn area [r] (let [pi 3.14159] (* pi ` · suffix `)))` · hole ≤8
   - expect `r r`
   - **AR handicap:** suffix `)))` tells diffusion exactly one form closes the
     `(* …)`; AR must infer arity blind.
4. **cond-default**
   - prefix `(defn sign [n] (cond (pos? n) 1 (neg? n) -1 ` · suffix `))` · hole ≤6
   - expect `:else 0`
   - **AR handicap:** the closing `))` implies one final clause; AR could keep
     emitting clauses.

**AR baseline for contrast.** Run the same prefixes through the AR sibling
`gemma-4-26B-A4B-it` (or any AR coder) as plain left-to-right completion with the
suffix WITHHELD — that is the real AR handicap (an editor's AR completion never
sees what's after the cursor). Score: did the fill, concatenated with the suffix,
(a) parse (Seon's `seon.repl.internal/parse-forms`, instant, no model call — see
[[parser-as-generation-oracle-2026-06-28]]) and (b) eval to the right value in
Seon's SCI cage? The parser is the free syntactic oracle; eval is the semantic
one. Metric: % of holes filled correctly when the suffix constrains the answer,
diffusion vs suffix-blind AR.

## Run order on first deploy

1. `python gpu_worker_infill.py introspect` → read U1–U4 off the live A100.
2. Wire the `accept_canvas` override / canvas-seed to whichever seam U1–U3
   revealed (edit the marked CONFIRM-ON-DEPLOY spots in the stub).
3. `python gpu_worker_infill.py infill` → case 1 (mean-reduce). Confirm the hole
   denoises to `(reduce + xs)` and inspect `steps[]` (which positions committed
   when, entropy).
4. Run cases 2–4; run the suffix-blind AR baseline; tabulate the win rate.
5. Decision gate (T2): if diffusion shows no infilling edge over suffix-blind AR,
   the structural premise is weak — stop and reassess (per the test-plan gate).

## Honesty / limits

- The `generate()` infill API is **uncertain** — U1/U2/U3 are confirm-on-deploy,
  not invented. The stub reflects rather than guesses, and withholds the generate
  call until the seam is known.
- Cases are small, single-form, suffix-constrained — chosen because that is
  exactly where bidirectional attention is unambiguously better. They are a clean
  proxy for editor typeahead, not a general coding benchmark.
- This plan is to EXECUTE fast once the GPU is live, not a result. No DiffusionGemma
  output has been produced yet (blocked on the custom torch image — see [[index]]).
