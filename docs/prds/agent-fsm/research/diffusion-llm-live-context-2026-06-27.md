---
type: research
status: active
tags: [research, agent, web, flow]
---

# Diffusion LLMs as Seon's live-context interface (DiffusionGemma)

> Investigating Sean's idea: replace the static autoregressive (AR) context
> interface with a **diffusion** LLM, so live data coming out of the model
> mid-generation can drive editor typeahead, live human feedback into
> generation, whole-form refinement, and embedding-based doc lookups that
> correct errors *during* implementation. Server assumed self-hosted (vLLM).

## TL;DR

- **DiffusionGemma is real and shipped** (Google DeepMind, **2026-06-10**,
  Apache 2.0). 26B-A4B MoE (Gemma 4 base), 25.2B total / **3.8B active**, 8/128
  experts, 262K vocab, 256K context, 1024 sliding window. **Natively supported
  in vLLM** as of the same day. ~1100 tok/s/user on a single H100 FP8, low batch
  — feasible on a personal server, which is the whole premise here.
- **It generates a *canvas*, not a stream.** Default canvas = **256 tokens**,
  refined in parallel by ≤48 denoising steps with **bidirectional attention**
  over the canvas; the prompt is an AR **encoder** cached once (cross-attended,
  not regenerated per step). 15–20 tokens committed per forward pass.
- **This is a structural match for Seon's `block`/form vocabulary.** AR is
  left-to-right with an immutable past; diffusion holds a *mutable whole-form
  canvas* it iteratively refines. "Refine entire blocks of forms to execute" is
  not a metaphor here — it's literally what the decoder does.
- **All four of Sean's ideas are viable, in ascending fork-cost:**
  1. *Typeahead via infilling* — near-free; clamp the human's typed tokens,
     denoise the holes. Bidirectional attention sees code **after** the cursor,
     which AR fundamentally cannot. **Strongest near-term win.**
  2. *Whole-form refinement + eval-error feedback* — re-noise only the failing
     region of the canvas, re-denoise in place. Native to diffusion; an in-place
     patch instead of AR's regenerate-from-error-point.
  3. *Retrieval-augmented denoising (live doc lookups)* — when the canvas
     stabilizes on a symbol, embed it, hit Seon's existing Vertex +
     Proximum/HNSW index, inject fn-specs/docs into the encoder cache for the
     next steps. RAG **inside** the generation loop, not before it.
  4. *Live human feedback steering the denoiser* — accept/clamp/re-noise between
     steps. Most powerful, **most fork-heavy** (see fork surface below).
- **The seam is cheaper than the vLLM path implied.** The reverse-engineering
  paper (arXiv:2606.14620, Transformer Lab) shows the commit decision lives in an
  **open `transformers` method**: `EntropyBoundSampler.accept_canvas(current_canvas,
  denoiser_canvas, logits, cur_step)`, `logits` shape `[1, 256, 262144]`, in
  `transformers` 5.11.0, model class `DiffusionGemmaForBlockDiffusion`, model_type
  `diffusion_gemma`, **no `trust_remote_code`**. They wrap it with a forward hook
  (purely observational). **Override it instead and you have ideas 2–4 without
  touching vLLM internals** — clamp positions, inject retrieved context, read
  external feedback, all between accept-calls. vLLM's private `DiffusionSampler`
  is the *production-serving* version of the same seam; prototype on transformers.
- **The catch (honest):** the shipped sampler converges on an **entropy budget**
  and does not *surface* intermediate canvases by default. You get them by hooking
  `accept_canvas` (transformers) or forking `DiffusionSampler` (vLLM). Sean
  controls the server, so in-scope — it's the actual engineering surface, not a flag.

## Architecture (grounded)

Encoder/decoder split:

- **Encoder** = AR pass over the prompt → KV cache, computed once ("prefill").
  Not regenerated per diffusion step. This is where Seon's **ai render** (the
  assembled prompt string) lands today, and where retrieved docs get injected
  (idea 3).
- **Decoder** = bidirectional attention over a **256-token canvas**, cross-
  attending the cached prompt. Every canvas position attends to every other →
  the model sees the *whole form at once*, including what follows the cursor.

Sampling (transformers defaults, from the model card):

- Max denoising steps: **48**
- Temperature schedule: linear **0.8 → 0.4**
- Entropy-bound threshold: **0.1** — at each step the sampler commits the
  lowest-entropy tokens whose mutual-information bound stays under 0.1; the rest
  are **fully re-noised** and revisited.
- Adaptive-stop entropy threshold: **0.005** (mean per-token entropy → converge)
- "block-autoregressive multi-canvas sampling" — blocks chain AR-style, each
  block denoised in parallel internally.

vLLM integration seam (the fork surface):

- A model registers `get_model_state_cls()`; **no scheduler/runner changes**.
- `ModelState` hooks: `prepare_inputs()` (embeds canvas + self-conditioning),
  `prepare_attn()` (causal encoder ↔ bidirectional decoder per request),
  `custom_sampler()` (→ `DiffusionSampler`), `add_request()/remove_request()`
  (per-request diffusion state: canvas, self-conditioning probs, convergence
  history).
- Reuses the **speculative-decoding** infra; `num_sampled = 0` during denoise
  steps, KV cache only advances on commit phases.
- **Not exposed today:** intermediate denoising states, streaming of partial
  canvases, infilling/editing API. All present *internally* as per-request state
  — so a fork can surface them.

## Why this fits Seon specifically

- **`block` = canvas.** The context unit is `:seon.agent.ctx/block` and the
  output unit Seon cares about is a Clojure **form**. A 256-token canvas ≈ one
  form/block. Diffusion's native object is exactly Seon's native object.
- **Code-as-data is the retrieval corpus.** `:seon.fn`/`:seon.schema`/`:seon.ns`
  entities + the Vertex `gemini-embedding-2` + Proximum HNSW index already exist
  (see embeddings PRD, datahike fork secondary indexes). Idea 3 wires the
  *existing* index into the denoise loop.
- **Never-crash / surface-as-data pairs with iterative refinement.** Each denoise
  step can read the **warnings block** (`:seon/error` derived) and inject it as a
  constraint for the next step. Eval → error-as-data → re-noise failing region →
  re-denoise is the reactive loop applied *inside* generation.
- **Render shape barely changes.** ai render → encoder KV cache (cross-attended);
  the canvas is the agent's output form. html render / tiles unaffected.
- **Sizing fits the box.** 3.8B active on one H100 FP8 ≈ the personal/home-server
  target in [[architecture]] ("the user's data stays on their own box").

## Idea-by-idea assessment

### 1. Editor typeahead via infilling — DO THIS FIRST
Clamp the human's already-typed tokens as fixed canvas positions; denoise the
holes. Bidirectional attention conditions the fill on **both** sides of the
cursor — the structural advantage over every AR completion (Copilot et al. can't
see the suffix). Cheap because it needs no sampler fork: it's a prompt/canvas
construction trick (mask = holes, fixed = typed). Risk: vLLM may not expose
canvas-clamping without a small patch to `prepare_inputs()`.

### 2. Whole-form refinement + eval feedback — HIGH VALUE, MODERATE FORK
Generate form → eval in the SCI cage → on `:seon/error`, re-noise only the span
the error implicates, keep the rest clamped, re-denoise. In-place patch vs AR's
"regenerate from here forward." Requires a sampler fork to (a) reset entropy on a
sub-region and (b) accept an externally supplied mask between blocks.

### 3. Retrieval-augmented denoising — HIGH VALUE, MODERATE FORK
When canvas entropy on a symbol region drops (the model has "decided" on a fn
name), embed that partial canvas, query Proximum for the fn-spec/docstring/usage,
and append to the encoder cache before the next steps. Multiple denoise steps =
multiple injection windows; AR gets exactly one (pre-generation) shot. Hook:
between-step callback in `DiffusionSampler`; reuse `seon.db` + the embed path.

### 4. Live human feedback into the denoiser — MOST NOVEL, MOST FORK
Between steps, let the human accept (clamp), reject (force re-noise), or nudge
(bias logits) regions of the live canvas, streamed over Seon's existing SSE
channel. This is the "live data out of the diffusion model drives the UI which
drives the next step" loop Sean described. Needs: partial-canvas streaming out +
a feedback channel into `custom_sampler()`. Biggest payoff, build last.

## What source exists to interface with the model

- **vLLM** — native, day-one. The real integration target. Fork point =
  `ModelState`/`DiffusionSampler` (private but present). Following Seon's
  vendor-as-submodule habit, vendor vLLM's diffusion path into
  `reference-code/` so the sampler is grep-able and forkable. **Recommended;
  not yet done — awaiting go-ahead.**
- **transformers** — `DiffusionGemmaForBlockDiffusion` + `AutoProcessor`,
  `model.generate(..., max_new_tokens=...)`. Good for offline experiments /
  understanding; not the serving path.
- **Hugging Face** — `google/diffusiongemma-26B-A4B-it` (weights, Apache 2.0).
- **NVIDIA** — local-RTX acceleration path (TensorRT-LLM) if running on consumer
  GPU instead of H100.

### transformers usage (verbatim from model card)

```python
from transformers import DiffusionGemmaForBlockDiffusion, AutoProcessor

MODEL_ID = "google/diffusiongemma-26B-A4B-it"

processor = AutoProcessor.from_pretrained(MODEL_ID)
model = DiffusionGemmaForBlockDiffusion.from_pretrained(
    MODEL_ID, dtype="auto", device_map="auto",
)

message = [{"role": "user", "content": "Why is the sky blue?"}]
input_ids = processor.apply_chat_template(
    message, tokenize=True, add_generation_prompt=True,
    return_dict=True, return_tensors="pt",
).to(model.device)

output = model.generate(**input_ids, max_new_tokens=512)
text = processor.decode(output[0], skip_special_tokens=False)
```

## Papers downloaded + read

Saved under the session scratchpad; re-fetch via the arXiv IDs below.

### arXiv:2606.14620 — "Neither Parallel Nor Sequential: How DiffusionGemma Actually Commits Tokens" (Transformer Lab, 2026-06-12) — READ IN FULL

The most useful paper for this project. An **inference-only** study (no
fine-tune): they hook the shipped sampler and record, per accept-call, which
canvas positions commit and at what entropy. Findings that bear directly on
Sean's ideas:

- **The exact hook** (this is the interface answer at the deepest level):
  `EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
  cur_step)`, `logits = [1, 256, 262144]`, decode config = 256-token canvas / ≤48
  denoising steps / `entropy_bound` 0.1, transformers 5.11.0 + torch 2.9.0, one
  H100 80GB. Plain forward hook, no retraining. **This is where every "live"
  idea attaches.**
- **Commits are NOT frozen — positions re-mask.** 4524 un-accept events over 600
  generations (mean 7.5/gen); only 220/600 generations fully monotone. The canvas
  genuinely re-opens already-committed positions → the model *itself* does
  in-place revision. Direct evidence Sean's "refine entire blocks" is native, not
  bolted on.
- **Decode is a partial, granularity-dependent left-to-right bias, NOT clean
  block-autoregression.** Token-level Kendall τ_b ≈ 0.43–0.60 (well below 1.0).
  Genuine sub-block disorder. Commit batches large: **13–26 content tokens per
  accept-call**, only **3.3–17.1 accept-calls per generation** (aggressive early
  stop, far below the 48-step budget; commits fire at entropy 0.002–0.012 nats,
  way under the 0.1 bound).
- **Structured output is order-independent** (JSON R6 τ_b ≈ **−0.044**) — generated
  holistically / anchor-first, not L→R. Clojure forms are structured, so expect
  the same: good for whole-form refinement and infilling-style typeahead.
- **THE killer finding for idea 3 (doc lookups to correct errors):** the model's
  own commit-confidence (negative entropy-at-commit) **predicts correctness on
  math** (GSM8K AUROC **0.749**, CI excludes chance; accuracy falls monotonically
  as commit-entropy rises) **but is NULL on factual recall** (AUROC **0.471**, CI
  includes 0.5). Read across to Seon: the model can self-detect *code/logic-shaped*
  uncertainty (trigger a lookup when a code-region commits hot) but **cannot**
  self-detect wrong *API/symbol names / facts* — exactly the failure an external
  embedding lookup against the program graph must catch. The two ideas are
  complementary, and this paper quantifies the boundary between them.
- Accuracy comparable to the AR sibling `gemma-4-26B-A4B-it` on scorable regimes
  (R1 0.66–0.73 vs 0.70; R4 0.93–0.96 vs 0.967; R6 1.00 vs 1.00). No big quality
  tax for going diffusion.
- **No code released** (§7) — instrumentation available on request — but the hook
  name + signature + config are documented, so reproducible.

### arXiv:2503.09573 — "Block Diffusion (BD3-LMs): Interpolating Between AR and Diffusion LMs" (Arriola et al., Cornell/Cohere/Stanford, ICLR 2025) — abstract + intro read

The architectural foundation DiffusionGemma is built on. Block diffusion = an AR
distribution *over blocks*, each block a discrete denoising diffusion. Buys the
three things vanilla discrete diffusion lacks and Sean needs:
**arbitrary-length generation, KV caching, and parallel token sampling**, while
keeping diffusion's **bidirectional context + controllability**. Code (real, not
on-request): `https://github.com/kuleshov-group/bd3lms`. Worth vendoring if we
prototype block diffusion mechanics before DiffusionGemma weights are wired up.

### Related papers surfaced (next reads, by Sean's idea)

- **Infilling / typeahead (idea 1):** arXiv:2506.13579 "Flexible-length Text
  Infilling for Discrete Diffusion Models" (Zhang et al.) — directly the clamp-
  the-typed-tokens-denoise-the-holes mechanism.
- **Steering the denoiser (ideas 2/4):** arXiv:2602.00250 "TABES: Trajectory-Aware
  Backward-on-Entropy Steering"; arXiv:2511.05664 "KLASS: KL-Guided Fast
  Inference"; arXiv:2512.21336 "Optimizing Decoding Paths by Quantifying
  Uncertainty"; arXiv:2506.00413 "Adaptive Parallel Decoding" (Israel et al.).
- **Order/token joint search (refinement quality):** arXiv:2601.20339 (Shen et
  al.); arXiv:2602.02112 "Unifying Masked Diffusion with Various Generation
  Orders" (Hong et al.).
- **Survey:** arXiv:2506.13759 "Discrete Diffusion in LLMs and Multimodal: A
  Survey" (Yu et al.).

## Open questions / next probes

- Does vLLM expose **canvas clamping / infilling** without a patch? (gates idea 1)
- Exact `DiffusionSampler` between-step callback shape — read the vendored source.
- Latency budget for idea 3: embed + HNSW lookup must fit inside the gap between
  denoise steps (sub-ms HNSW on small sets per CLAUDE.md; embed call is the cost).
- Canvas = 256 tokens. Big forms span multiple blocks ("block-autoregressive
  multi-canvas") — does in-place refinement cross block boundaries?

## Raw external responses (preserved verbatim)

### WebSearch — overview
> DiffusionGemma is Google DeepMind's first open-weight text diffusion language
> model, released June 10, 2026 under Apache 2.0. Based on the 26B A4B MoE Gemma
> 4 architecture … 25.2B total params, ~3.8B active during inference. Generates
> up to 4x faster than AR models of comparable size by refining 256-token blocks
> in parallel. Multimodal (text/image/video in → text out). Encoder processes
> the prompt → KV cache (prefill); prompt not regenerated each step. Decoder
> works on a 256-token canvas with bidirectional attention.

### WebFetch — vLLM blog (interface)
> ModelState hooks: prepare_inputs() (embeds canvas + self-conditioning),
> prepare_attn() (causal encoder ↔ bidirectional decoder per request),
> custom_sampler() (→ DiffusionSampler), add_request()/remove_request().
> Registers via get_model_state_cls(); no scheduler/runner changes. Canvas 256;
> hard denoising-step limit + convergence checks; entropy-bound rule commits
> positions until accumulated entropy exceeds threshold; converge when mean
> per-token entropy < threshold. num_sampled=0 during denoise; only commit
> phases advance KV cache. Reuses speculative-decoding infra. Per-request state
> tracks canvas, self-conditioning probs, convergence history. No streaming /
> infilling / intermediate-state exposure documented.

### WebFetch — HF model card (architecture)
> block-autoregressive multi-canvas sampling; canvas 256; context 256K; sliding
> window 1024. AR encoder caches prompt; decoder bidirectional over canvas,
> cross-attends cached context. Max denoising steps 48; temp 0.8→0.4 linear;
> entropy bound 0.1; adaptive-stop entropy 0.005. Each step selects lowest-
> entropy tokens under the MI bound; non-selected fully re-noised. 25.2B total /
> 3.8B active; 8/128 experts; 262K vocab. >1100 tok/s/user (H100 FP8, low batch);
> 15–20 tokens/forward pass. Visual token budgets 70/140/280/560/1120.

## Sources

- [DeepMind — DiffusionGemma](https://deepmind.google/models/gemma/diffusiongemma/)
- [vLLM blog — DiffusionGemma native support](https://vllm.ai/blog/2026-06-10-diffusion-gemma)
- [HF — google/diffusiongemma-26B-A4B-it](https://huggingface.co/google/diffusiongemma-26B-A4B-it)
- [Google AI — DiffusionGemma model card](https://ai.google.dev/gemma/docs/diffusiongemma/model_card)
- [NVIDIA — local DiffusionGemma acceleration](https://blogs.nvidia.com/blog/rtx-ai-garage-local-gemma-diffusion/)
</content>
</invoke>
