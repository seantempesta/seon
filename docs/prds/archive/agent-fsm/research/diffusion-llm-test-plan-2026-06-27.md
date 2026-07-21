---
type: research
status: draft
tags: [research, agent, flow, web]
---

# Diffusion-LLM live-context: cheap-traction test plan

> Goal: spend the **least** money to learn whether diffusion-as-buzzsaw —
> a strong AR model guides, a diffusion model iterates/refines code against
> fast feedback (eval results, retrieved docs) — has traction, before
> committing real resources. Companion to [[diffusion-llm-live-context-2026-06-27]].

## The thesis under test

A smart AR model (Claude/Opus) sets direction — spec, skeleton, constraints. A
**diffusion** model fills + refines a whole-form **canvas** in parallel, taking
feedback *between denoising steps*: eval errors re-noise the failing region,
retrieved fn-specs inject into context to fix wrong names. AR completion can't see
past the cursor and can't revise committed tokens; diffusion does both natively
(the commit paper proved the shipped model already re-masks ~7.5 positions/gen).
If this works, Seon gets a tight refine loop bounded only by how fast we can eval
or retrieve.

## Two-tier model strategy (cost discipline)

| Tier | Model | Where | Cost | Tests | Why |
|---|---|---|---|---|---|
| **Mechanism** | **BD3-LMs** (vendored `reference-code/bd3lms`, HF `kuleshov-group/bd3-lms`) | local / any small GPU | ~free | T0–T2 harness | Real block-diffusion sampler in `diffusion.py`; tiny (DiT, OWT-trained). Proves the clamp / re-noise / observe-loop harness *works* before we pay. **General text, NOT code** — mechanism only. |
| **Capability** | **DiffusionGemma 26B-A4B-it** (transformers `DiffusionGemmaForBlockDiffusion`, HF gated) | rented GPU | $/hr | T0–T5 | Code-capable, 3.8B active. The real traction signal. |

Build the harness against BD3-LMs for $0; flip the model handle to DiffusionGemma
on the rented box once the loop runs. Same `accept_canvas`-shaped seam both sides.

## Hardware: rent a 5090, use the official NVFP4 checkpoint

> **SUPERSEDED (2026-06-28) — hardware only; the T0–T5 ladder below is still
> current.** This section's "5090 / NVFP4 / A100 NOT needed" guidance is
> outdated. The settled decision is **A100-80GB BF16** (see
> [[../../diffusion-dynamic-context/index]] §GPU + cost and the cost-comparison
> doc): the thesis hinges on the model's UNPERTURBED entropy/commit dynamics, so
> the confound-free baseline MUST be BF16 — quantization (NVFP4) perturbs exactly
> what we measure. (≥100k context also needs Flash Attention, disabled on the
> 5090's Blackwell SM120.) NVFP4/5090 is a *later* speed-sweep arm measured
> against the BF16 baseline, NOT the bring-up path. Do NOT rent a 5090 for first
> light — the validated worker image targets A100-80 BF16.

The model is 25.2B params and it's MoE — **all experts stay resident**, so VRAM
tracks total params, not the 3.8B active. BF16 ≈ 50 GB (needs H100). But there is
an **official 4-bit checkpoint** that settles this:

- **`nvidia/diffusiongemma-26B-A4B-it-NVFP4`** — Gemma-26B-A4B quantized to
  **NVFP4** (4-bit float) with NVIDIA Model Optimizer, **vLLM-ready**, near-lossless.
  **~18 GB**, runs on RTX 4090 (24 GB) / **5090 (32 GB)**, **700+ tok/s on the
  5090**. This is the checkpoint we serve — not the BF16 one.

So:

- **RTX 5090 (Blackwell, 32 GB) — THE card.** Native NVFP4, ~18 GB checkpoint
  leaves ~14 GB for KV/activations at our small contexts, cheapest (~$0.43–0.69/hr).
- **RTX 4090 (24 GB)** — also works (NVFP4 fits 18 GB); a fallback if no 5090.
- **H100/A100-80GB** — only if we ever want the BF16 checkpoint or the >1100 tok/s
  number; NOT needed for any traction probe.

**Verdict: rent one 5090, serve the NVFP4 checkpoint.** Single-digit-dollar spend.
Keep BD3-LMs local for harness dev so the meter only runs on real probes. Full
copy-paste setup: [[diffusion-llm-runpod-runbook-2026-06-27]].

## Harness shape — do NOT integrate into the pod yet

Standalone **Python service on the rented box** (transformers + the hook). Seon
is reached over a **thin HTTP bridge** as two oracles only:

- **eval oracle** — POST a Clojure form → Seon `seon.eval` SCI cage → `:seon/error`
  or value back. (The feedback signal for T3.)
- **retrieval oracle** — POST a partial canvas / symbol → embed (Vertex
  `gemini-embedding-2`) → Proximum/HNSW over `:seon.fn`/`:seon.schema` → top-k
  specs/docstrings back. (The injection for T4.)

Seon already has all three pieces (eval cage, embeddings, program graph). The
bridge is a few handlers, not a runtime change. Pod integration (canvas → a
`block` render, SSE-streamed partial canvases) is **Phase 2**, only after a probe
shows traction. This keeps the live pod stable (standing guidance) and the
experiment disposable.

The control seam, both models: wrap/override the per-step commit callback —
DiffusionGemma's `EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas,
logits, cur_step)` (transformers, no `trust_remote_code`), BD3-LMs' equivalent in
`diffusion.py`'s reverse loop. Override gives us: read partial canvas, clamp
positions, re-noise a span, inject retrieved context into the encoder, log entropy.

## Capability probes — cheapest first, each a go/no-go

Run in order; stop the meter at the first hard NO.

### T0 — Smoke + cost baseline (≈30 min on box)
Load DiffusionGemma FP8 on the 5090, generate one Clojure form from a prompt,
measure tok/s and VRAM. **Pass:** runs, fits VRAM, generates parseable text.
**Learn:** real hourly cost, whether 32 GB holds.

### T1 — Observe the loop (≈1 hr; pure observation, no override)
Replicate the paper's forward hook on `accept_canvas`: log per-step canvas +
per-position entropy while generating Clojure. **Pass:** we can read intermediate
canvases and the commit-entropy signal. **Learn:** does commit-entropy spike on
*code* regions we'd want to look up (paper says yes for math/code, null for
facts)? This validates the T4 trigger for free.

### T2 — Infilling / typeahead — THE cheapest "better-than-AR" signal (≈2 hr)
Clamp a partial form with a hole — `(defn avg [xs] (/ (reduce + xs) <HOLE>))` with
the closing context present — denoise the hole. Compare to an AR model completing
the same prefix *blind to the suffix*. **Metric:** % of holes filled correctly
when the suffix constrains the answer (closing parens, next form, known return
shape). **Pass:** diffusion beats AR on suffix-constrained fills. This is the
single clearest structural win and needs no Seon integration.

### T3 — Eval-feedback re-noise loop — the buzzsaw (≈3 hr)
Generate form → eval oracle → on `:seon/error`, re-noise the implicated span (keep
the rest clamped), inject the error text into the encoder, re-denoise. Iterate ≤N.
**Metric:** pass@1-after-k-refines vs cold-regen pass@k at equal compute, on a
small Clojure task set (10–20 functions). **Pass:** in-place refinement converges
faster / higher than regenerate-from-scratch. This is the core thesis.

### T4 — Retrieval-augmented denoising — docs correct errors (≈3 hr)
When a symbol region commits with high entropy (T1 trigger), call the retrieval
oracle, inject the correct fn signature/docstring into the encoder for the next
steps. **Metric:** reduction in wrong-arity / wrong-name / wrong-import errors vs
T3-without-retrieval. **Pass:** injecting the real spec mid-denoise fixes
name/arity errors the model can't self-detect (paper: commit-entropy is *null* on
factual/name correctness — so external retrieval is exactly the missing signal).

### T5 — Smart-model-guides-buzzsaw — the full vision (≈2 hr)
Opus writes spec + skeleton + constraints; DiffusionGemma fills/refines with T3+T4
feedback. **Metric:** end-to-end task completion + wall-clock vs pure-AR (Opus
alone) and vs diffusion-alone. **Pass:** guided-diffusion matches AR quality at
materially lower latency, OR exceeds AR quality at equal latency.

## Decision gates

- **After T2:** if diffusion shows *no* infilling edge over AR → the structural
  premise is weak; stop, reassess. (Cost so far: ~$3–5.)
- **After T3:** if the re-noise loop doesn't beat cold regen → the buzzsaw doesn't
  cut; the idea is interesting but not yet useful. Stop or pivot to T4-only.
- **After T4/T5:** if either shows a clear win → write a Phase-2 PRD for pod
  integration (canvas as a `block`, SSE partial-canvas streaming, eval/retrieval
  oracles promoted from the bridge into the reactive loop).

## Cost estimate

5090 @ ~$0.60/hr × ~14 hr of probe time (T0–T5, generous) ≈ **$8–12**, plus a
little Vertex embedding spend for T4. Mechanism dev (T0–T2 harness) on BD3-LMs is
free/local. **Single-digit-to-low-double-digit dollars to a go/no-go on the whole
thesis.** This is a buy.

## What Seon provides (already built)

- `seon.eval` SCI cage → the T3 feedback oracle.
- Vertex `gemini-embedding-2` + Proximum/HNSW over the program graph → the T4
  retrieval oracle.
- `:seon/error` as data + the warnings block → the error text injected into the
  encoder is already structured.
- ai render → encoder prompt; the **block** is the natural Phase-2 home for a
  canvas; SSE channel already streams renders → partial-canvas streaming is the
  same mechanism. None of this is needed until a probe passes.

## Open prep items before renting

1. Accept the DiffusionGemma license on HF (gated) so the box can pull weights.
2. Confirm `transformers` ships `diffusion_gemma` at the version we pin (paper:
   5.11.0) — else read the modeling file for the real `accept_canvas` signature.
3. Stand up the two bridge handlers in Seon (eval + retrieval) behind a flag.
4. Pick host: Vast.ai or RunPod 5090 spot; ~$0.40–0.70/hr.
</content>
