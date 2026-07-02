---
type: research
status: active
tags: [research, diffusion, agent]
---

# Eval-renoise worker — the buzzsaw round-trip primitive (build note)

> WORKER-SIDE build of the EVAL-RENOISE control loop: denoise a partial canvas,
> hand it to Seon to parse/eval, re-noise just the bad char-spans, resume. Two new
> worker modes (`denoise_to_step`, `resume_renoise`) + two shared primitives
> (`StepCountStopping` / `step_stopping`, `good_clamp_for_renoise`). Grounded against
> transformers v5.11.0 (the deployed checkpoint). py_compile-clean; NOT deployed —
> the owner deploys + drives the single GPU. Where the source doesn't settle a
> runtime semantic, this note says MUST MEASURE with the exact test, not a guess.

## TL;DR

- **The eval-renoise loop lowers onto PUBLIC seams only** — no fork of `generate()`.
  Half 1 (`denoise_to_step`) stops the real N-step schedule at step K via an external
  stopping criterion; half 2 (`resume_renoise`) re-seeds via `decoder_input_ids` +
  `ClampLogitsProcessor` and resumes. This is exactly the "OUTER loop of K-step
  `generate()` calls" the source-grounding doc §3 prescribes.
- **Stopping at step K keeps `max_denoising_steps = N` INTACT.** Shrinking the cap
  compresses the temperature ramp (`:311`) — a different regime, NOT a checkpoint.
  `StepCountStopping` counts its own calls (one per `_denoising_step`) and fires an
  all-True `BoolTensor` at the Kth, so the loop breaks (`:782`) after K natural steps.
- **The renoise dial is `good_clamp_for_renoise`**: char spans the parser/eval flagged
  → `span_to_positions` over the seed's own offset map → CLAMP every GOOD (non-span)
  committed position, leave the BAD span positions free to re-denoise. The good text is
  held by the same `ClampLogitsProcessor` that `clamp_smoke`/`infill` proved.
- **Two honest UNKNOWNS the source cannot settle, each with its exact test** (below):
  (1) does the duck-typed `StepCountStopping` actually fire at step K on 5.11.0 — i.e.
  does the OR-accumulate + break path behave as read; (2) cross-call KV-cache reuse is
  NOT wired (a `Cache` can't ride a JSON payload) so each call re-encodes the prompt.
- **py_compile PASSES** (`python -m py_compile gpu_worker.py diffgemma_common.py`); the
  pure-python halves (`good_clamp_for_renoise`, `span_to_positions`, the `step_stopping`
  patch/restore) are unit-checked off-GPU. The torch-dependent half (`StepCountStopping.__call__`,
  the two generate paths) is UNVERIFIED until the owner runs it.

## What was built (gitignored worker — these files CHANGED, not committed)

`tmp/flash-diffgemma/diffgemma_common.py` (shared, leaf, py_compile-clean off-GPU):

- `StepCountStopping` — a `DiffusionGemmaAdaptiveStopping`-SHAPED criterion (DUCK-TYPED,
  not subclassed: the loop never isinstance-checks it and the heavy
  `from transformers.models.diffusion_gemma...` import would break py_compile off-GPU;
  the real ABC is `:466`). `__call__(argmax_canvas, logits, **kwargs)` increments a call
  counter and returns `torch.full((batch,), count >= K, bool)`; `reset()` zeroes it
  (called per canvas at `:993`). One call == one denoise step, so it fires after exactly
  K steps WITHOUT touching `max_denoising_steps`.
- `step_stopping(model, K)` — context manager that REPLACES
  `model._prepare_diffusion_stopping_criteria` (called once at generate setup, `:1207`)
  with one returning a fresh `StepCountStopping(K)`; restores on exit. REPLACE not
  compose — we want exactly K natural steps, so the builtin
  `StableAndConfidentStoppingCriteria` early-stop is disabled for the window.
- `good_clamp_for_renoise(offset_map, seed_ids, spans)` — pure char/int: returns
  `({good_pos -> committed_token_id}, sorted_bad_positions)`. Empty spans ⇒ clamp
  everything (a no-op round-trip sanity check).

`tmp/flash-diffgemma/gpu_worker.py`:

- Helpers: `_resolve_clamps` (payload `clamps`/`clamp_text` → `{pos:id}`),
  `_run_with_optional_stop` (wraps `_run` in `step_stopping` when K given, returns
  `steps_fired`), `_tpf` (the `tokens_per_forward` reader).
- `mode="denoise_to_step"` — stop at step K, return `partial_text`, `canvas_text`
  (piecewise decode aligned with the map), `argmax_per_position` (the per-position
  argmax = the next seed), `offset_map` (`[[pos,char_start,char_end],…]`),
  `seed_canvas_in`, `denoise_steps_fired`, `worker_sha` (base dict). Optional clamp
  scaffold via `clamps`/`clamp_text`.
- `mode="resume_renoise"` — given `seed_canvas` (prior `argmax_per_position`) +
  `renoise_spans` (`[[s,e],…]` char spans), clamp the GOOD positions, re-noise the BAD,
  resume (optional second checkpoint via `denoise_steps`). Returns `good_held` (the
  clamp invariant), `n_clamped_good`/`n_renoised_bad`, the new canvas + fresh
  `offset_map`. An optional persistent scaffold (`clamps`) always wins over a span.
- `__main__`: `python gpu_worker.py denoise_to_step` and `… resume_renoise` (the latter
  denoises partially, re-noises an early span, resumes — a self-contained round-trip).
- `worker_sha` is preserved (the base `info` dict already carries it on every mode;
  unchanged — but note the worker BYTES changed, so the local fingerprint moves and
  `verify_fresh.py` MUST be re-run after deploy).

## The lowering, grounded (file:line — all `generation_diffusion_gemma.py` v5.11.0)

1. **Per-step criterion call** (`:1059`): `finished_denoising |= diffusion_stopping_criteria(new_argmax_canvas, processed_logits)`. Called once per `_denoising_step` (`:756`→`:997`). Counting calls == counting steps.
2. **Inner-loop break** (`:782`): `if torch.all(finished_denoising): break` — when our criterion returns all-True, the canvas stops denoising and `argmax_canvas` (the partial draft) is appended to `input_ids` (`:786`). So `out.sequences[0][nprompt:]` is the canvas_length partial canvas.
3. **`reset()` per canvas** (`:992-993`): so the K-cap restarts each canvas. `denoise_to_step` is single-canvas (`max_new_tokens = canvas_length` ⇒ `max_new_canvases = 1`, `:638`), so this is hygiene. With >1 canvas it would stop at step K of EACH — out of scope here.
4. **`_prepare_diffusion_stopping_criteria` is the only construction point** (`:1207`), built solely from config and returning `None` unless BOTH `stability_threshold` and `confidence_threshold` are set. Overriding it at the instance is the documented minimal-surface injection (source-grounding §3 / §5 item 4).
5. **Seed canvas** (`:979`): `decoder_input_ids` is popped as the start canvas; `resume_renoise` passes the good-clamped + bad-randomized seed here. `_seed_canvas` randomizes every NON-clamped position — exactly the re-noise of the bad spans.
6. **Clamp survives the schedule** (`:1034`, `:1170-1181`): external `logits_processor` runs FIRST, before the temperature schedule; a near-one-hot divided by t stays near-one-hot, so `accept_canvas` (`:431-442`) always keeps clamped positions and `renoise_canvas` (`:457-463`) never churns them. This is the `ClampLogitsProcessor` mechanism already proven by `clamp_smoke`/`infill`.

## Nuances + risks (honest — some are MUST-MEASURE)

1. **MUST MEASURE — does `StepCountStopping` actually fire at step K on 5.11.0?** The
   read is: the criterion is called every `_denoising_step`, its all-True result
   OR-accumulates into `finished_denoising`, and `torch.all(finished_denoising)` breaks
   the inner loop. That SHOULD stop after exactly K steps. UNVERIFIED on hardware.
   **Test:** `denoise_to_step` with `denoise_steps=8`; assert `denoise_steps_fired == 8`
   AND cross-check `tokens_per_forward ≈ canvas_length / 8` (the metric is non-pad
   tokens ÷ decoder forward passes, `:843-848`). Then `denoise_steps=1` and a large K to
   bracket. If `steps_fired != K`, the count/break interaction differs from the read —
   re-derive before trusting any partial canvas.

2. **MUST MEASURE — is the partial canvas at step K meaningfully "partial"?** At small K
   the canvas is mostly random vocab (few low-entropy positions committed); at K→N it
   converges. The PARSE rung needs enough structure to be informative. **Test:** sweep
   `denoise_steps ∈ {4,8,16,32}` on the `mean` prompt, eyeball `partial_text` +
   `committed_per_step` (the trace) for where the form becomes parse-able. This sets the
   real K the eval-renoise loop should checkpoint at — a tuning number, not a constant.

3. **Schedule RESTART on resume (regime nuance, by design).** `resume_renoise` runs a
   FRESH `generate()`: the bad (renoised) positions go through the full t_max→t_min ramp
   again from scratch, while the clamped good positions are pinned. This is desirable
   (re-explore the bad region under high temp) but it is NOT a continuation of the prior
   run's temperature state — it is a new schedule over the surviving canvas. Documented
   so nobody mistakes it for "resume exactly where step K left off." If a true
   continuation is ever needed, that requires the single-loop fork (source-grounding §3),
   which we deliberately did NOT do.

4. **KV-cache reuse across calls is NOT wired (and can't be over JSON).** The source
   returns/accepts `past_key_values` (`:826`, `:635-636`) so the committed prefix need
   not be re-encoded. But a `Cache` object cannot serialize into a JSON worker payload,
   so each `resume_renoise` RE-ENCODES the prompt. Cost: one extra encoder prefill per
   round-trip. **If/when** the loop runs IN-PROCESS on the co-location image (Seon calls
   the function directly, not over HTTP), feed `out.past_key_values` back in — a free
   continuation lever (source-grounding §5 item 6). For now: re-encode, correctness
   over speed. MUST MEASURE the prefill cost once the round-trip is live.

5. **MUST MEASURE — does the renoised bad span actually change under resume?** A clamped
   good frame co-conditions the bad positions; whether they re-decide to something
   DIFFERENT (and better) is the whole eval-renoise bet, untested closed-loop. **Test
   (E4 in the mode-design doc §7):** produce a deliberately-broken form, take its parser
   `:span`, run `resume_renoise` with that span; assert `good_held == True` (the clamp
   invariant) AND that the bad positions' tokens changed AND the retry clears the gate
   more often than a blind full re-noise.

6. **Char-span → BPE-position is OVERLAP, not exact.** `span_to_positions` selects every
   token whose `[cs,ce)` overlaps the char span, because parser spans and BPE boundaries
   don't align. So a renoise may free one extra boundary token on each edge of the span.
   Acceptable (re-noising a paren next to the bad symbol is harmless), but it means the
   dial is token-granular, not char-exact. Flagged so a "why did an adjacent char move"
   surprise is expected behavior.

7. **`canvas_text` vs `partial_text`.** `offset_map` is built from the PIECEWISE decode
   with `skip_special_tokens=False` (load-bearing — special tokens occupy canvas
   positions and char space). So char spans from Seon's parser must be computed against
   `canvas_text` (the piecewise string), NOT `partial_text` (the clean
   `skip_special_tokens=True` decode shown for readability). Mixing the two desyncs every
   span. The worker returns both and labels them; Seon must map spans on `canvas_text`.

8. **`StableAndConfidentStoppingCriteria` interaction.** `step_stopping` REPLACES the
   builtin, so during a K-step run no confidence/stability early-stop fires — the canvas
   runs exactly K steps even if it "settled" earlier. That is intentional (we want K),
   but means `denoise_to_step`'s K is a HARD count, not "≤K." A plain `generate` (no
   override) still gets the builtin if the config sets both thresholds.

## Owner deploy + test commands (single GPU; owner drives)

The worker bytes changed ⇒ the `worker_sha` fingerprint moved ⇒ a fresh deploy + a
verify is mandatory before trusting any number (the stale-warm-worker trap, CLAUDE.md
"Deployment stability").

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a

# Deploy a FRESH image tag (bump so the warm worker can't serve stale bytes), then verify.
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v2   # NEW tag
.venv/bin/flash deploy
python3 verify_fresh.py            # MUST print FRESH ✓ (worker_sha == local) before measuring

export DIFFGEMMA_EP=<endpoint-id-from-deploy>

# (0) regression: clamp still holds (the primitive both new modes lean on)
python -u client.py '{"mode":"clamp_smoke","trace":"canvas"}'

# (1) denoise_to_step — stop at K, get the partial canvas + offset_map.
#     CHECK: denoise_steps_fired == 8, and tokens_per_forward ≈ canvas_length/8.
python -u client.py '{"mode":"denoise_to_step","prompt":"Write an idiomatic Clojure function `mean` returning the average of a vector. ONLY code.","denoise_steps":8,"trace":"canvas"}'
#     Sweep K to find where partial_text becomes parse-able:
python -u client.py '{"mode":"denoise_to_step","prompt":"...","denoise_steps":4}'
python -u client.py '{"mode":"denoise_to_step","prompt":"...","denoise_steps":32}'

# (2) resume_renoise — feed back argmax_per_position as seed_canvas, re-noise a span.
#     CHECK: good_held == true; the renoised span tokens changed.
#     (use the argmax_per_position array returned by step (1) as seed_canvas)
python -u client.py '{"mode":"resume_renoise","prompt":"...","seed_canvas":[ ...canvas_length ids... ],"renoise_spans":[[0,24]],"denoise_steps":12,"trace":"canvas"}'

# Self-contained round-trip smoke (does (1)->(2) in one __main__ call, ON the worker):
#   python gpu_worker.py denoise_to_step
#   python gpu_worker.py resume_renoise
```

After verifying both modes mechanically, the next rung is the CLOSED loop (E4): a
deliberately-broken form → parser `:span` → `resume_renoise` → re-parse, measuring that
span-targeted renoise beats blind full renoise.

## Entry points (depth)

- [[mode-driven-guided-generation-2026-06-28]] §4 — the guided-gen lowering this worker serves (clamp → infill → per-step gate → span renoise).
- [[transformers-diffusion-source-grounding-2026-06-28]] §3/§5 — the stopping-criterion ABC, the temperature-compression caveat, the outer-loop-of-K pattern.
- `tmp/flash-diffgemma/diffgemma_common.py` — `StepCountStopping`/`step_stopping`, `good_clamp_for_renoise`, `ClampLogitsProcessor`, `build_offset_map`/`span_to_positions`.
- `tmp/flash-diffgemma/gpu_worker.py` — `denoise_to_step`, `resume_renoise` modes.
</content>
</invoke>
