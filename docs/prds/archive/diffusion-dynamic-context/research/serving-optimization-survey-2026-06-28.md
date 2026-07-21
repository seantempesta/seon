---
type: research
status: active
tags: [research, agent, web]
---

# DiffusionGemma serving + inference optimization survey

> Can `google/diffusiongemma-26B-A4B-it` be Seon's PRIMARY generation+serving
> model? We run it via raw transformers on a RunPod A100-80 and get **137 tok/s**;
> the paper/marketing claims **~1000 tok/s on an H100**. This survey pins down the
> exact conditions behind every speed claim, whether a serving framework (vLLM
> et al.) can run the block-diffusion decode, and whether the RESEARCH path
> (per-step `accept_canvas`/`LogitsProcessor` control) and the SERVING path can be
> ONE deployment or must diverge.
>
> Companion to [[model-mechanics-grounding-2026-06-28]] (the decode mechanics) and
> [[../index]] (the 4 dynamic-context capabilities + the `accept_canvas` seam).

## TL;DR

- **The 1000 tok/s number is real but conditional: H100, FP8, batch size 1, a
  FULL 256-token canvas, ~48 denoise steps, entropy_bound 0.1 (15-20 tokens
  committed/forward).** It is *per-request generation throughput on a saturated
  canvas* — not a number you can compare to AR tok/s. ([vLLM blog](https://vllm.ai/blog/2026-06-10-diffusion-gemma),
  [HF model card](https://huggingface.co/google/diffusiongemma-26B-A4B-it))
- **Our 137 tok/s on the A100 is explained by three independent gaps, in order of
  impact:** (1) **A100 = Ampere, no native FP8/NVFP4** — the 1000 number is an FP8
  number on Hopper; BF16 on Ampere structurally cannot reach it. (2) We commit
  **only ~4 tokens/forward**; the reference config commits **15-20** — that is a
  ~4x gap from sampler tuning alone (entropy_bound + denoise schedule), and it is
  the part we CAN fix on the A100. (3) raw transformers w/o `flash_attention_2` +
  `torch.compile` leaves more on the table.
- **vLLM genuinely runs the block-diffusion decode loop** (not an AR fallback) via
  a new `ModelState` abstraction + a `DiffusionSampler`. The NVFP4 "vLLM-ready"
  tag is real. **BUT vLLM does NOT expose the per-step logits/`accept_canvas`
  hook** — sampling is sealed inside `DiffusionSampler._compiled_sample_step`. So
  vLLM gives us SPEED, never our research seam.
- **Conclusion: the research path and the serving path MUST diverge.** Run TWO
  endpoints behind ONE Seon provider adapter: a **transformers control worker**
  (our RunPod worker — keeps `accept_canvas`/`LogitsProcessor`, slower) and a
  **vLLM serving endpoint** (fast demo throughput, no control). Same weights, same
  prompt format, two backends. A single deployment cannot do both.
- **Honest caveat:** the whole ecosystem is **18 days old** (released 2026-06-10).
  Several flags below are explicitly marked "tentative and subject to change" by
  their own authors. Treat every number as "confirmed by a third party" or "must
  re-measure on OUR A100" — flagged per item.

---

## 1. How people are optimizing DiffusionGemma inference

### 1a. The reference sampler config that yields ~1000 tok/s (this is the lever we're missing)

From the official **HF model card** and the **gemma/diffusion `_sampler.py`** source,
the throughput-optimal *sampling* config is concrete and small:

| Setting | Reference value | Our symptom |
|---|---|---|
| `max_denoising_steps` | **48** (cap) | unknown — likely too low or too high |
| Temperature schedule | **linear anneal 0.8 → 0.4** (`AnnealingTemperatureShaper`) | — |
| `entropy_bound` | **0.1** → **15-20 tokens committed/forward** | we commit **~4** |
| Adaptive stop | mean canvas entropy **< 0.005** → terminate early | — |
| Canvas length | **256** | 256 (ok) |
| dtype | **FP8** for the 1100 number | we run BF16 |

Sources: [HF model card](https://huggingface.co/google/diffusiongemma-26B-A4B-it)
("15-20 tokens per forward pass… exceeding 1100 tok/s in low batch size settings
(H100, FP8)"; denoise max 48; temp 0.8→0.4; entropy bound 0.1; entropy threshold
0.005), [gemma/diffusion `_sampler.py`](https://github.com/google-deepmind/gemma/tree/main/gemma/diffusion).

**The exact per-step seam (verified from `_sampler.py` source):**

- `SampleFromPredictions.__call__` — the **accept seam**. Computes
  `token_entropy = -Σ p·log p`, sorts positions by entropy, and accepts the
  most-confident positions while
  `accumulated_entropy - sorted_entropy <= self.entropy_bound`. Dataclass field
  `entropy_bound: float = 0.1`. **Lower `entropy_bound` ⇒ FEWER tokens accepted
  per step ⇒ more denoise steps ⇒ lower tok/s.** This is almost certainly why we
  see ~4 tokens/forward: our effective bound is far tighter than 0.1 (or the
  canvas is mostly empty on short replies — see §1c).
- `AnnealingTemperatureShaper.__call__` — the **LogitsProcessor seam**.
  `out_logits = logits / temperature[:, None, None]`, temperature annealing from
  `max_temperature: 0.8` to `min_temperature: 0.4` by noise progression. **This is
  the same per-step logits hook our clamp/infill/eval-renoise experiments use.**
- Denoise loop: `jax.lax.while_loop(cond_fn, body_fn, …)`, `cond` checks
  `carry.step < max_denoising_steps`; each step is one full-canvas forward.
- Commit: `append_tokens_to_cache(tokens=canvas, …)` — commits `canvas_length`
  tokens to the KV cache once the block converges.

> **Actionable for the A100 (research path):** before touching frameworks, raise
> tokens-committed-per-forward from ~4 toward 15-20 by setting `entropy_bound`
> ≈ 0.1 and the temperature anneal 0.8→0.4 with a 48-step cap + 0.005 early-stop.
> This is a sampler change in OUR worker, costs nothing, and is the single biggest
> lever we control. **Must re-measure on our A100** — the 15-20 figure is Google's
> on FP8/H100; commit-count is dtype-independent but verify.

### 1b. attn_implementation, torch.compile, attention backend

- The decode (denoise) pass uses **bidirectional** attention over the canvas;
  encoder/prefill + commit use **causal** attention (two modes, shared weights —
  see [[model-mechanics-grounding-2026-06-28]] and the
  [vLLM blog](https://vllm.ai/blog/2026-06-10-diffusion-gemma)). `flash_attention_2`
  supports both masks and requires fp16/bf16; it is the right
  `attn_implementation` for the transformers path (vs `sdpa`/`eager`). **No
  DiffusionGemma-specific FA2 benchmark exists yet** — this is a general-transformers
  inference win we must measure ourselves. ([HF GPU-inference docs](https://huggingface.co/docs/transformers/v4.42.0/perf_infer_gpu_one))
- Every serving recipe uses **`--attention-backend TRITON_ATTN`** for the vLLM
  path (the DGX-Spark NVFP4 run and the developer-guide recipe both pin it).
  ([vLLM recipe](https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it),
  [ai-muninn](https://ai-muninn.com/en/blog/dgx-spark-diffusiongemma-nvfp4-vllm))
- `torch.compile`: vLLM's `DiffusionSampler` ships a `_compiled_sample_step`
  (already compiled). For our raw-transformers worker, compiling the denoise step
  is a plausible win but **unverified for this model** — measure.

### 1c. Why "diffusion tok/s lies" — the per-canvas cost (critical for an AGENT workload)

The single most important operational finding, from
[ai-muninn](https://ai-muninn.com/en/blog/dgx-spark-diffusiongemma-nvfp4-vllm):
**"the cost is per-canvas, not per-token."** A 256-token canvas costs ~the same
wall-clock whether it ends up holding 8 useful tokens or 256. Measured on a DGX
Spark (NVFP4):

- cold / short reply (84 tok): **16.5 tok/s**
- warm, full canvas: **158.7 tok/s** prose, **143.1 tok/s** code
- 4 concurrent: **257.3 tok/s** aggregate

Same model, same hardware, one minute apart — the spread is **entirely canvas
fill**. For Seon agents that emit lots of short turns ("calling the tool", "got
it, continuing"), naive tok/s will look terrible even when wall-clock is fine.
**Implication:** judge the demo on **wall-clock per turn**, not tok/s, and prefer
prompts that fill the canvas. (Settings used there: `canvas_length 256`,
`max_denoising_steps 48`, `TRITON_ATTN`, gpu-mem 0.70.)

### 1d. The "custom drafter" caveat on the headline number

[gncrypto](https://www.gncrypto.news/news/diffusiongemma-1000-tokens-sec-needs-custom-drafter/)
reports the very top numbers assume a **small fast drafter that proposes token
blocks verified in one forward pass (speculative-decoding-adjacent)**, and that
this drafter is **NOT in common public runtimes** (absent from mlx-lm, LM Studio,
Apple MLX). Treat this as **unconfirmed / marketing-adjacent** — the vLLM and HF
numbers below do NOT mention a drafter, so the 1000-1288 figures appear reachable
on Hopper FP8 WITHOUT it. Flagged so we don't chase a component that may not exist
publicly.

### 1e. Quantization

- **FP8** (`RedHatAI/diffusiongemma-26B-A4B-it-FP8-dynamic`) — the dtype behind the
  1008 (H100) / 1288 (H200) numbers. **Requires Hopper.** ([vLLM blog](https://vllm.ai/blog/2026-06-10-diffusion-gemma))
- **NVFP4** (`nvidia/diffusiongemma-26B-A4B-it-NVFP4`, also `RedHatAI/...-NVFP4`) —
  quantized w/ NVIDIA Model Optimizer, 16→4 bits, vLLM-ready. **NVFP4 needs
  Blackwell (GB10/RTX 50xx/DGX); on an A100 it will not run natively.** ([HF NVFP4 card](https://huggingface.co/nvidia/diffusiongemma-26B-A4B-it-NVFP4),
  [NVIDIA blog](https://developer.nvidia.com/blog/run-diffusiongemma-on-nvidia-for-developer-ready-high-throughput-text-generation/))
- **GGUF** (unsloth + ~18 community variants) — llama.cpp/Ollama/LM Studio,
  consumer ~18 GB VRAM. Serving-only, no per-step control. ([unsloth GGUF](https://huggingface.co/unsloth/diffusiongemma-26B-A4B-it-GGUF),
  [diffusiongemma.org/how-to-run](https://diffusiongemma.org/how-to-run))

> **A100 honesty:** our Ampere A100-80 has **no FP8 and no NVFP4 tensor-core
> path**. The headline 1000 tok/s is structurally out of reach on this GPU. On the
> A100 the realistic ceiling is **BF16 + FA2 + tuned sampler**; expect to claw
> back the ~4→15-20 tokens/forward gap (≈ up toward 300-500 tok/s on a full
> canvas) but NOT the FP8 Hopper number. **Must measure.** To actually hit ~1000+
> we'd need an H100/H200 (FP8) or Blackwell (NVFP4).

---

## 2. Does vLLM (or SGLang/TGI/TRT-LLM) actually serve the block-diffusion decode?

### vLLM — YES, natively, and it runs the real diffusion loop (not AR)

Confirmed from the [vLLM engineering blog](https://vllm.ai/blog/2026-06-10-diffusion-gemma)
(launch-day, 2026-06-10): DiffusionGemma is **"the first dLLM natively supported in
vLLM."** It does NOT shoehorn into the AR path. Mechanism:

- New generic **`ModelState`** abstraction (model-runner v2), with hooks:
  `prepare_inputs()` (embeds canvas + self-conditioning), `prepare_attn()`
  (per-request causal vs bidirectional), `custom_sampler()` (→ `DiffusionSampler`),
  `add_request()`/`remove_request()` (per-request canvas + self-cond state).
- Two modes, shared weights: **encoder** (causal, writes KV) for prefill/commit;
  **decoder** (bidirectional, read-only KV) for the denoise iterations. Requests
  alternate modes within a batch ("dynamic per-sequence causal attention").
- During denoise the sampler reports `num_sampled=0, num_rejected=query_len`, KV
  position does not advance; only a **commit** advances it (256 tokens). Automatic
  prefix caching works because the encoder pass is ordinary causal.

**Throughput (vLLM-confirmed, batch size 1):** H200 **1,288 tok/s**, H100 **1,008
tok/s** (FP8), ≈ **6×** an AR baseline. The vLLM recipe's SPEED-Bench table on a
single H100 conc=1: per-request gen **1,282 tok/s** vs Gemma-4 AR **205 tok/s**
(6.2×); end-to-end request **0.88 s** vs **2.87 s** (3.3×). ([vLLM recipe](https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it))

**Does vLLM expose the per-step seam? NO.** The blog is explicit that sampling is
encapsulated in `DiffusionSampler._compiled_sample_step` (temperature scaling,
Gumbel-max, entropy-bound acceptance, renoise rejection, convergence check). There
is **no user-facing per-denoise-step logits / `accept_canvas` callback.** You can
configure the sampler (entropy_bound, canvas length) via `--hf-overrides` /
`--diffusion-config`, but you cannot inject a Python `LogitsProcessor` per step or
inspect/modify the canvas mid-denoise. **This kills vLLM for our research
experiments** (clamp/infill/eval-renoise all need to read+rewrite the canvas
between steps).

**Exact vLLM launch (BF16, H100/H200):** ([vLLM recipe](https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it))

```
vllm/vllm-openai:gemma   # the dedicated docker image
  --model google/diffusiongemma-26B-A4B-it
  --max-model-len 262144
  --max-num-seqs 4               # diffusion state buffers pre-alloc tensors; higher ⇒ OOM
  --gpu-memory-utilization 0.85  # headroom for denoise activations
  --attention-backend TRITON_ATTN
  --hf-overrides '{"diffusion_sampler":"entropy_bound","diffusion_entropy_bound":0.1}'
  --diffusion-config '{"canvas_length":256}'
  --enable-chunked-prefill
  # + multimodal/tools: --tool-call-parser gemma4 --reasoning-parser gemma4
```

**Exact vLLM launch (NVFP4, Blackwell):** ([HF NVFP4 card](https://huggingface.co/nvidia/diffusiongemma-26B-A4B-it-NVFP4))

```
VLLM_USE_V2_MODEL_RUNNER=1 vllm serve nvidia/diffusiongemma-26B-A4B-IT-NVFP4 \
  --trust-remote-code --max-num-seqs 4 --attention-backend TRITON_ATTN \
  --enable-auto-tool-choice --tool-call-parser gemma4 --reasoning-parser gemma4 \
  --override-generation-config '{"max_new_tokens": null}' \
  --default-chat-template-kwargs '{"enable_thinking":true}'
```

> The NVFP4 card marks its command **"tentative and subject to change until the
> supporting vLLM image is publicly released."** The two key gotchas in EVERY
> recipe: **`--max-num-seqs ≤ 4`** (diffusion state buffers OOM above that) and
> **TTFT ~10× the AR baseline** (must denoise a whole canvas before any output) —
> [nerova](https://nerova.ai/news/google-diffusiongemma-26b-a4b-local-text-generation-june-10-2026).

### SGLang / TGI / TensorRT-LLM — NOT confirmed

No source in this survey shows SGLang, TGI, or TensorRT-LLM running the
DiffusionGemma block-diffusion decode. NVIDIA's own blog markets the **vLLM** path
(+ NVFP4 via Model Optimizer) and **does not mention TensorRT-LLM** for this model.
([NVIDIA blog](https://developer.nvidia.com/blog/run-diffusiongemma-on-nvidia-for-developer-ready-high-throughput-text-generation/))
Treat non-vLLM frameworks as **unsupported until proven** — the dLLM decode needs
the custom ModelState/sampler plumbing vLLM built, which those frameworks have not
(publicly) replicated 18 days post-launch.

---

## 3. The adapter question — one Seon provider adapter, how many backends?

Seon already has `SEON_AI_PROVIDER` adapters (default DeepSeek). DiffusionGemma
needs an adapter, and the shape depends on which path:

- **SERVING / DEMO path:** vLLM serves an **OpenAI-compatible** endpoint
  (`vllm/vllm-openai:gemma`, `/v1/chat/completions`). So the Seon serving adapter
  is **nearly the existing OpenAI-style adapter** pointed at the vLLM URL — minimal
  new code. Works against (a) our own RunPod vLLM container, OR (b) RunPod
  serverless vLLM, OR (c) any hosted vLLM endpoint. No per-step control, full
  speed.
- **RESEARCH path:** the adapter talks to **our RunPod transformers control
  worker** — a custom (non-OpenAI) request/response that passes
  `entropy_bound`, denoise schedule, and (the whole point) an `accept_canvas` /
  `LogitsProcessor` callback for clamp/infill/eval-renoise. This is the worker the
  experiment plans ([[../index]], [[model-mechanics-grounding-2026-06-28]]) already
  target. Slower, full control.

**Can ONE deployment serve both? No.** vLLM's `DiffusionSampler` is sealed (§2) —
you cannot get the per-step seam out of it without forking vLLM's sampler in C++/
Triton, which defeats the "fast, supported" reason to use vLLM at all. The
transformers worker, conversely, will never match vLLM's throughput. They are
genuinely different backends with the same weights and prompt format.

**Recommended adapter shape:** ONE Seon provider adapter (`diffusiongemma`) with a
backend selector:

```
SEON_AI_PROVIDER=diffusiongemma
SEON_DG_BACKEND=vllm        # fast demo  → OpenAI-compatible vLLM URL
SEON_DG_BACKEND=control     # research   → our transformers worker (accept_canvas)
```

Same prompt formatting, tokenizer, and stop logic on both sides; the selector just
routes to a different transport + capability set. The control backend additionally
exposes the per-step experiment knobs the serving backend cannot.

---

## 4. Recommendation

**Run TWO endpoints behind ONE adapter — the research-control path and the serving
path MUST diverge.**

1. **Primary demo / serving model = vLLM endpoint.** It is the only framework that
   actually runs the block-diffusion decode, it is OpenAI-compatible (cheap Seon
   adapter), and it delivers the real speed. **Expected tok/s: NOT the A100.** On
   the A100 (BF16, no FP8) you will be well under the headline; to make
   DiffusionGemma feel "primary-fast" in a demo, serve it on an **H100/H200 (FP8,
   ~1000-1288 tok/s, third-party-confirmed batch=1 full-canvas)** or rent
   **Blackwell for NVFP4**. RunPod serverless vLLM or a dedicated H100 pod is the
   pragmatic deploy. Pin `--max-num-seqs ≤ 4`, expect high TTFT, judge on
   wall-clock-per-turn not tok/s (§1c).
2. **Research / dynamic-context experiments = our transformers control worker on
   the A100.** Keep it. It is the ONLY path that exposes `SampleFromPredictions`
   (accept seam) + `AnnealingTemperatureShaper` (logits seam) for
   clamp/infill/eval-renoise. **First, fix the sampler config** (entropy_bound
   ≈ 0.1, temp 0.8→0.4, 48-step cap, 0.005 early-stop) to lift ~4 → 15-20
   tokens/forward — that ~4× is achievable on the A100 today and is our biggest
   self-serve win. Add `flash_attention_2` + try `torch.compile` on the denoise
   step. Realistic A100 BF16 target after tuning: a few-hundred tok/s on a full
   canvas — enough for experiments, NOT for a snappy demo.
3. **The trade, concretely:** *control and speed are mutually exclusive in current
   tooling.* vLLM = speed, zero per-step control. transformers = full per-step
   control, ~vLLM/6 speed. There is no single config that gives both, because the
   fast path compiles the sampler shut. Seon should own both as separate backends
   of one adapter and pick per use-case (demo → vllm, experiment → control).

### Confirmed vs must-test-on-our-A100

| Claim | Status |
|---|---|
| vLLM runs the real diffusion decode (not AR) + ModelState/DiffusionSampler | **Confirmed** (vLLM blog) |
| vLLM exposes NO per-step logits/accept_canvas hook | **Confirmed** (vLLM blog) |
| H100 1008 / H200 1288 tok/s @ batch1, FP8, full canvas | **Confirmed by vLLM**, FP8/Hopper-only |
| 15-20 tokens/forward at entropy_bound 0.1, 48 steps, temp 0.8→0.4 | **Confirmed config** (HF card + `_sampler.py`); our 4→? **must re-measure** |
| "tok/s lies" — per-canvas not per-token; 16 vs 158 tok/s by fill | **Confirmed** (ai-muninn, DGX Spark) |
| A100 cannot reach 1000 (no FP8/NVFP4) | **High confidence** (Ampere arch), exact A100 BF16 ceiling **must measure** |
| FA2 + torch.compile speedup on transformers path | **General prior, UNMEASURED for this model** |
| "custom drafter" needed for top numbers | **Unconfirmed / marketing-adjacent** — discount |
| SGLang/TGI/TRT-LLM support | **None found** — treat unsupported |
| OpenAI-compatible vLLM ⇒ trivial Seon serving adapter | **Confirmed** (recipe ships `vllm/vllm-openai`) |

---

## Sources

- vLLM engineering blog (decode loop, ModelState, DiffusionSampler, H100/H200 numbers): <https://vllm.ai/blog/2026-06-10-diffusion-gemma>
- vLLM recipe (launch command, flags, SPEED-Bench H100 table): <https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it>
- HF model card google/diffusiongemma-26B-A4B-it (15-20 tok/forward, 48 steps, temp 0.8→0.4, entropy 0.1/0.005, FP8 1100 tok/s): <https://huggingface.co/google/diffusiongemma-26B-A4B-it>
- gemma/diffusion `_sampler.py` (SampleFromPredictions, AnnealingTemperatureShaper, entropy_bound, denoise loop): <https://github.com/google-deepmind/gemma/tree/main/gemma/diffusion>
- HF NVFP4 card + tentative vLLM command (VLLM_USE_V2_MODEL_RUNNER, TRITON_ATTN): <https://huggingface.co/nvidia/diffusiongemma-26B-A4B-it-NVFP4>
- NVIDIA technical blog (NVFP4 via Model Optimizer, 1000/150/2000 tok/s H100/Spark/Station): <https://developer.nvidia.com/blog/run-diffusiongemma-on-nvidia-for-developer-ready-high-throughput-text-generation/>
- ai-muninn "why diffusion tok/s lies" (per-canvas cost, 16 vs 158 tok/s, TRITON_ATTN, 48 steps): <https://ai-muninn.com/en/blog/dgx-spark-diffusiongemma-nvfp4-vllm>
- nerova.ai (real tradeoffs: TTFT 10×, max-num-seqs ≤4, quant fits 18GB): <https://nerova.ai/news/google-diffusiongemma-26b-a4b-local-text-generation-june-10-2026>
- gncrypto (the "custom drafter" caveat — discount): <https://www.gncrypto.news/news/diffusiongemma-1000-tokens-sec-needs-custom-drafter/>
- diffusiongemma.org/how-to-run (transformers `DiffusionGemmaForBlockDiffusion` vs vLLM vs GGUF; which preserve per-step control): <https://diffusiongemma.org/how-to-run>
- unsloth GGUF (local llama.cpp/Ollama path): <https://huggingface.co/unsloth/diffusiongemma-26B-A4B-it-GGUF>
- Google DiffusionGemma announcement: <https://blog.google/innovation-and-ai/technology/developers-tools/diffusion-gemma-faster-text-generation/>
- DiffusionGemma developer guide (points to gemma/diffusion repo + hackable_diffusion): <https://developers.googleblog.com/diffusiongemma-the-developer-guide/>
</content>
</invoke>
