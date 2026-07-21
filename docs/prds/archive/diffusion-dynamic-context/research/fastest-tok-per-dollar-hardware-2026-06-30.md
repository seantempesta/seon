---
type: research
status: active
tags: [research, agent, web]
---

# Fastest tok/$ hardware path for DiffusionGemma (the experimentation engine)

> The owner's metric is **tok/$/sec** — throughput per dollar — because the goal is
> cheap + fast experimentation, not a production SLA. This doc ranks every hardware ×
> precision option for running `google/diffusiongemma-26B-A4B-it` (26B-param, **4B
> active** MoE block-diffusion LM, ~50 GB BF16), grounds the FP8-on-MoE viability
> question in transformers source, does the TPU deep-dive (JAX/Flax exists — the
> owner's lead is correct), and ends with a ranked recommendation + the single
> cheapest de-risking experiment.
>
> Companion to [[serving-optimization-survey-2026-06-28]] (the 1000 tok/s decode),
> [[torchao-quant-worker-2026-06-28]] (why INT8 skipped the experts), and
> [[model-mechanics-grounding-2026-06-28]] (the decode loop). No GPU was used to
> produce this; numbers are MEASURED (our runs), CONFIRMED (third-party/source), or
> ESTIMATED (clearly marked).

## TL;DR — the ranking

1. **A100-80 SXM, BF16 (our baseline) — the fast VIABLE path TODAY.** ~130 tok/s
   MEASURED, $1.49/hr RunPod → **~87 tok/$/sec**. After the *free* sampler-tuning win
   (commit 15-20 tok/forward instead of our ~4, per the reference `entropy_bound 0.1`
   config) the SAME A100 should reach **~300-500 tok/s** with ZERO new hardware →
   **~200-335 tok/$/sec ESTIMATED**. This is the highest-leverage move and it costs
   one redeploy, not a port.
2. **H100 SXM, FP8 — the fast VIABLE path if you need raw speed.** 1008 tok/s
   CONFIRMED (vLLM, FP8), $2.89/hr → **~349 tok/$/sec**. FP8 works here because the
   MoE FP8 kernel (`deepgemm_fp8_fp4_experts_forward`) **requires Hopper** — H100 is
   the cheapest GPU that can run the fused experts in FP8. *But* FP8 speed lives in
   vLLM, which has **no per-step control seam** — so H100-FP8 is a SERVING number,
   not the RESEARCH worker (see "The two-endpoint reality").
3. **H200, FP8** — 1288 tok/s CONFIRMED (vLLM), ~$4.39/hr → **~293 tok/$/sec**. Faster
   absolute, slightly worse tok/$ than H100. Same control caveat.
4. **TPU v5e (single chip), JAX-native — the owner's lead, strong but UNPROVEN-on-this-
   model.** A JAX/Flax DiffusionGemma EXISTS (`google-deepmind/gemma/gemma/diffusion`),
   so this is a PORT-LIGHT path, not a from-scratch build. $1.20-1.60/chip-hr. If a
   4B-active MoE lands even ~200-400 tok/s on a v5e-4/8 slice the tok/$ could BEAT
   H100 — but no public DiffusionGemma-on-TPU tok/s number exists yet. Engineering
   cost is real (see deep-dive). **The high-ceiling bet; de-risk before committing.**
5. **L40S, FP8 — NON-VIABLE for this model. Confirmed, not assumed.** 48 GB can't hold
   50 GB BF16, and the only FP8 expert kernel needs **Hopper**; the L40S is **Ada
   (SM89)**. The fused 3D experts have no FP8 path on Ada, and the stock torchao
   quantizer skips them (same mechanism as our measured INT8 failure). Model neither
   fits nor quantizes → **drop it.**
6. **A6000, INT8 — NON-STARTER. Confirmed.** Ampere, 48 GB, no FP8 tensor cores, and
   INT8 on the fused experts **already FAILED** in our torchao test (experts skipped).
   Even if it fit, INT8 grouped-GEMM on this MoE has no kernel. **Drop it.**

**The crux finding (grounds 2, 5, 6):** for THIS model the only quantized path that
touches the *experts* (the 4B that dominate compute) is the **deepgemm FP8/FP4 kernel,
which is Hopper-or-newer only**. On any pre-Hopper card (A100, A6000, L40S/Ada) the
experts can ONLY run BF16. That single fact eliminates L40S and A6000 and explains why
the "cheap 48 GB FP8" idea doesn't work here.

---

## The tok/$/sec ranking table

`tok/$/sec` = (tok/s) ÷ ($/hr) — higher is better (more throughput per dollar). $/hr =
RunPod Secure Cloud unless noted. FIT? = does ~50 GB BF16 (or its quantized size) fit
VRAM. PRECISION-WORKS? = does that precision actually run THIS model's fused-MoE
experts (the load-bearing question).

| Hardware | Arch (SM) | VRAM | Precision | FIT? | PRECISION-WORKS? (experts) | tok/s | $/hr | **tok/$/sec** | Evidence |
|---|---|---|---|---|---|---|---|---|---|
| **A100-80 SXM** | Ampere (80) | 80 GB | BF16 | ✅ | ✅ BF16 grouped_mm | **~130** | $1.49 | **~87** | tok/s MEASURED (our run); $/hr CONFIRMED |
| **A100-80 SXM (tuned)** | Ampere (80) | 80 GB | BF16 | ✅ | ✅ | ~300-500 | $1.49 | **~200-335** | ESTIMATED (4-5× from entropy_bound, §sampler) |
| **A100-80 PCIe** | Ampere (80) | 80 GB | BF16 | ✅ | ✅ | ~110-130 | $1.39 | ~79-94 | ESTIMATED (PCIe ~10-15% slower); $/hr CONFIRMED |
| **H100 SXM** | Hopper (90) | 80 GB | BF16 | ✅ | ✅ | ~250-400 | $2.89 | ~87-138 | tok/s ESTIMATED (Hopper BF16 ~2-3× A100) |
| **H100 SXM** | Hopper (90) | 80 GB | **FP8** | ✅ (~26 GB) | ✅ **deepgemm FP8 (SM90)** | **1008** | $2.89 | **~349** | tok/s CONFIRMED (vLLM); FP8 path source-confirmed |
| **H200** | Hopper (90) | 141 GB | FP8 | ✅ | ✅ deepgemm FP8 (SM90) | **1288** | $4.39 | ~293 | tok/s CONFIRMED (vLLM); $/hr CONFIRMED |
| **TPU v5e (slice)** | TPU v5e | 16 GB/chip (HBM) | BF16 (JAX) | ✅ (sharded) | ✅ (JAX MoE, native) | **~200-400?** | $1.20-1.60/chip | **~125-330?** | tok/s ESTIMATED; JAX impl CONFIRMED to exist |
| **TPU v6e (slice)** | TPU v6e | 32 GB/chip | BF16 (JAX) | ✅ | ✅ | ~400-800? | ~$2.70-3.22/chip | ~125-250? | tok/s ESTIMATED; v6e ~4.7× v5e compute |
| **L40S** | Ada (89) | 48 GB | FP8 | ❌ (no expert FP8 path) | ❌ **no Hopper FP8 kernel on Ada** | — | $0.86 | **N/A** | source-confirmed non-viable |
| **A6000** | Ampere (80) | 48 GB | INT8 | ❌ | ❌ **INT8 experts FAILED (our test)** | — | $0.49 | **N/A** | MEASURED failure |

Cross-provider $/hr sanity (A100-80 on-demand): RunPod SXM **$1.49**, RunPod PCIe
**$1.39**, Lambda **$2.49**. RunPod is the cheapest of the three and already our
deploy surface — keep it. (H100: RunPod $2.89 vs Lambda $2.99; comparable.)

---

## The FP8-on-MoE viability finding (THE crux)

This is the question that makes or breaks every 48 GB option. Answer, grounded in
`reference-code/transformers`:

**The model's experts are fused 3D `nn.Parameter`s, and the ONLY kernel that runs them
in FP8/FP4 is `deepgemm_fp8_fp4_experts_forward`, which requires Hopper (SM90+).**

Evidence chain:

1. **The experts are not `nn.Linear`.** `modeling_diffusion_gemma.py` stores experts as
   fused 3D `nn.Parameter`s (`gate_up_proj`, `down_proj`) consumed by `grouped_mm`
   (`reference-code/transformers/src/transformers/integrations/moe.py:380`
   `grouped_mm_experts_forward`). The MoE GEMM dispatcher is `_grouped_mm`
   (`moe.py:311`).
2. **The stock torchao quantizer only touches `nn.Linear`** (`_QUANTIZABLE =
   [torch.nn.Linear]`, `quantizer_torchao.py:129`, gate at `:147`). So
   `from_pretrained(quantization_config=…)` **skips the fused experts entirely** —
   which is exactly our MEASURED INT8 result: VRAM barely dropped because the 4B of
   experts stayed BF16 ([[torchao-quant-worker-2026-06-28]]).
3. **There IS a real FP8 expert kernel — but it's Hopper-only.** `deepgemm.py` header:
   "`deepgemm_fp8_fp4_experts_forward`: FP8 (or FP4 on SM100+) M-grouped experts
   forward. Requirements: CUDA, **Hopper (SM90+)**, CUDA runtime ≥ 12.3"
   (`reference-code/transformers/src/transformers/integrations/deepgemm.py:19-24`). The
   `sonicmoe` FP8 path likewise hard-requires SM90+ (`sonicmoe.py`: "requires a Hopper
   (SM90) or newer GPU … compute capability {major}.x" raises below 9).
4. **`grouped_mm` itself won't help on non-bf16 under compile.** The dispatcher's own
   gate: "torch.grouped_mm is not supported in torch.compile / inductor with dtypes
   other than bf16" (`moe.py:287-288`, `_can_use_grouped_mm`). So even the eager
   grouped path is a BF16 path; FP8 must go through deepgemm/sonicmoe, both Hopper-only.

**Conclusion:** FP8 for THIS model's experts is a **Hopper feature**, full stop. That
means:

- **L40S (Ada/SM89): the experts cannot run FP8** (no Hopper kernel) and cannot fit
  BF16 (50 GB > 48 GB). The "$0.86/hr FP8 bargain" is a mirage for this architecture.
  The attention/router/shared-MLP could FP8-quantize, but those aren't where the GB or
  the FLOPs are — the model still won't fit. **Non-viable.**
- **A6000 (Ampere/SM80): no FP8 tensor cores at all, and INT8 on the fused experts has
  no kernel** (our test confirms the skip). **Non-viable.**
- **A100 (Ampere/SM80): BF16 only — but it FITS in 80 GB and the BF16 grouped_mm path
  works.** This is why the A100 is the cheapest card that actually runs the whole
  model, control seam intact.
- **H100/H200 (Hopper/SM90): FP8 experts work → the 1000+ tok/s numbers are real.**

The owner's stated constraint ("torchao INT8 FAILED, so any quantized path must be
researched") is now fully resolved: **quantization that touches the experts is
Hopper-gated; on pre-Hopper cards the model is BF16-or-nothing.**

---

## TPU deep-dive (the owner's strong lead — and it holds up)

### (a) Does a JAX/Flax DiffusionGemma exist? — YES.

This is the decisive fact and it changes the cost calculus. Gemma is, at its origin, a
**JAX/Flax library** ("A JAX library to use and fine-tune Gemma," gemma README), and
the diffusion variant ships **inside that same repo**:

`github.com/google-deepmind/gemma/gemma/diffusion/` contains (CONFIRMED file listing):
`_sampler.py`, `_chat_sampler.py`, `_early_stopping.py`, `_models.py`,
`_transformer.py`, `_paths.py`, `example.ipynb`, and a `hackable_diffusion_adapter/`
subdir. There is also the standalone `github.com/google/hackable_diffusion` ("a modular
toolbox written in JAX to experiment and educate around Diffusion modeling").

Crucially, **the EntropyBoundSampler + temperature-annealing seam we already reverse-
engineered for the transformers worker IS the JAX `_sampler.py`** — our own
[[serving-optimization-survey-2026-06-28]] cites `SampleFromPredictions.__call__`
(entropy_bound = 0.1) and `AnnealingTemperatureShaper.__call__` (0.8→0.4) FROM this JAX
file, and the denoise loop is `jax.lax.while_loop(cond_fn, body_fn, …)`. So the control
primitives the whole project depends on are **natively available in JAX** — we would NOT
be porting them from torch; they are the *original*. The torch port is the derivative.

**This flips the port question.** It is NOT "can we port the custom control loop to
torch_xla" (hard). It is "can we drive the JAX `_sampler.py` loop and inject our clamp/
renoise between steps" (the loop is plain `jax.lax.while_loop` + a logits shaper — our
clamp is literally an `AnnealingTemperatureShaper`-shaped function in the same slot).
The seam we need is the same seam DeepMind already exposes.

### (b) torch_xla path (the fallback if we stayed on torch)

If we did NOT use the JAX impl and tried to run the *transformers* worker on TPU via
`torch_xla`: the custom denoise loop is Python control flow (a step loop), the
EntropyBoundSampler is a `LogitsProcessor`, and the MoE is `grouped_mm`. `torch_xla`
can trace static control flow, but (i) `torch.nn.functional.grouped_mm` has no XLA
lowering — it's a CUDA kernel — so the experts would hit the `grouped_mm_fallback`
(a Python loop, slow) or fail to lower; (ii) data-dependent control (early-stop on
entropy threshold) breaks XLA graph capture. **Verdict: torch_xla is the WRONG door.**
The JAX impl exists precisely so we don't need it. Use JAX, not torch_xla.

### (c) TPU $/hr + estimated tok/s

- **v5e**: $1.20-1.60 / chip-hr (GCP on-demand; lower for committed/batch).
- **v6e (Trillium)**: ~$2.70-3.22 / chip-hr; ~4.7× the compute of a v5e chip.
- TPUs **excel at exactly this shape** — a 4B-active MoE with big matmuls and batch-1
  latency-bound decode is the TPU sweet spot, and Gemma was *trained* on TPU so the
  sharding is well-trodden. A v5e-4 or v5e-8 slice holds the 50 GB BF16 model easily
  (16 GB HBM/chip × 4-8). **ESTIMATED 200-400 tok/s** for a tuned BF16 decode on a
  small v5e slice (no public DiffusionGemma-on-TPU number exists — this is an
  order-of-magnitude estimate from the 4B-active size + TPU MoE characteristics, NOT a
  measurement). If it lands, the tok/$ is competitive-to-better than A100 and possibly
  rivals H100-FP8 at a fraction of the per-chip price.

### (d) Engineering cost to port — is the tok/$ win worth it?

**Medium, not large — and lower than first assumed**, because the JAX impl + sampler
already exist. The real work:

1. **Stand up the JAX inference path** on a TPU-VM: install JAX-for-TPU, load the
   `gemma/diffusion` checkpoint, run `example.ipynb`'s generate. (Low — it's a notebook
   away.)
2. **Re-implement our clamp/renoise control as a JAX logits shaper** in the
   `_sampler.py` slot (the `AnnealingTemperatureShaper` seam). Our clamp is a
   per-position logits override — trivially a JAX function; renoise is re-injecting
   noise on the canvas between `while_loop` iterations. **This is the only genuinely
   new code, and it's small** (tens of lines), because the seam is already there.
3. **Re-validate the proven primitives** (clamp holds, infill holds, spec-slot) on the
   JAX path — a re-run of our existing experiments, not new design.
4. **Co-located oracle** travels unchanged (see below).

**Worth it?** As a *de-risking bet, yes — but second, after the free A100 sampler win.*
The A100-tuning win (4-5× for one redeploy) is guaranteed and immediate; the TPU win is
higher-ceiling but unproven. Sequence: bank the A100 win first, then spike the TPU path
on a single v5e slice to get a REAL tok/s number before committing the project to it.

### (e) Co-located oracle on TPU — works.

The oracle is **host-side**: babashka + node servers on the TPU-VM's **host CPU**,
talking to the accelerator process over local IPC. A TPU-VM is a normal Linux VM with a
full host CPU (the TPU is the attached accelerator, exactly like a GPU box). bb + node
run on the host CPU identically; the fast-IPC pattern is accelerator-agnostic. **No
blocker** — same as every GPU option below.

---

## Co-located oracle portability (all platforms)

The co-located oracle (host-CPU bb + node servers, the fast-IPC pattern) is **host-side
and platform-agnostic** — it never touches the accelerator. Confirmed clear on:

- **RunPod (A100/H100/H200)**: already where it runs today. ✅
- **Lambda (A100/H100)**: standard Linux GPU VM, same host CPU. ✅
- **TPU-VM (v5e/v6e)**: full host CPU on the VM, TPU is the attached accelerator. ✅

No platform blocks a host-side CPU server. The only thing that changes across platforms
is the *accelerator* process it talks to; the IPC contract is unchanged.

---

## The two-endpoint reality (don't conflate speed with control)

The FP8 1000+ tok/s numbers are **vLLM** numbers, and vLLM **seals the per-step
sampling** inside `DiffusionSampler._compiled_sample_step` — **no `accept_canvas` /
clamp / renoise hook** ([[serving-optimization-survey-2026-06-28]]). So:

- **The RESEARCH worker** (our clamp/renoise/eval-renoise experiments) needs the
  control seam → **transformers BF16 (A100)** today, or **JAX `_sampler.py` (TPU)** —
  both expose the per-step logits shaper.
- **A SERVING endpoint** (raw demo throughput) can be **vLLM FP8 on H100/H200** — fast,
  no control.

For the owner's actual goal — *cheap + fast experimentation* — the metric that matters
is **tok/$ on the CONTROL path**, because that's where the research happens. On the
control path, **A100-BF16-tuned and TPU-JAX-BF16 are the contenders; H100-FP8's headline
number is a serving number that doesn't carry the control seam.**

---

## Ranked recommendation

**1. NOW (zero new hardware): tune the A100 sampler.** We commit ~4 tok/forward; the
reference config commits 15-20 (`entropy_bound 0.1`, denoise cap 48, temp 0.8→0.4 —
[[serving-optimization-survey-2026-06-28]] §1a). That's a **4-5× tok/s win on the SAME
$1.49/hr A100**, taking us from ~87 to **~200-335 tok/$/sec** with one redeploy and no
porting. This is the highest tok/$ improvement available and it's free. Do it first.

**2. NEXT (de-risk the high ceiling): spike DiffusionGemma on ONE TPU v5e slice via the
JAX impl.** The JAX `_sampler.py` already has our control seam; a v5e is $1.20-1.60/chip-
hr and TPUs are built for 4B-active MoE batch-1 decode. **Get a REAL tok/s number** —
if a v5e-4 slice clears ~300 tok/s the tok/$ likely beats everything. This is the only
path that could structurally out-tok/$ the A100 *while keeping the control seam*.

**3. ONLY IF you need raw serving throughput (not research): vLLM FP8 on H100** (1008
tok/s, ~349 tok/$/sec) as a SECOND endpoint. Not the research engine — the demo engine.

**4. DROP L40S, A6000, and any "cheap 48 GB FP8/INT8" plan for this model** — the fused
experts have no sub-BF16 kernel below Hopper. Don't spend a dollar testing them.

### The single cheapest de-risking experiment

**Spin up ONE TPU v5e-4 slice (~$5-6/hr for the slice, minutes to a number), load the
`google-deepmind/gemma/gemma/diffusion` checkpoint, run `example.ipynb`'s generate, and
read the tok/s.** Cost: a few dollars for a 30-60 min session. It answers the ONE
unknown that gates the highest-ceiling path: *does DiffusionGemma actually run fast on a
cheap TPU slice?* Everything else in this doc is already proven or source-confirmed; the
TPU tok/s is the only ESTIMATE that, if confirmed, reorders the whole ranking. (The even
cheaper, do-it-first move — A100 sampler tuning — isn't an "experiment," it's a known
win to bank before spending on the TPU spike.)

> Honesty markers: A100 130 tok/s and the INT8-experts failure are **MEASURED** (our
> runs). H100/H200 FP8 tok/s, all $/hr, the JAX-impl existence, and the Hopper-only FP8
> expert kernel are **CONFIRMED** (vLLM blog / provider pricing / transformers+gemma
> source). A100-tuned tok/s, H100-BF16 tok/s, and **all TPU tok/s are ESTIMATES** — the
> TPU number is the one worth buying with a $5 spike before betting the project on it.

## Sources

- RunPod pricing — <https://www.runpod.io/pricing> ; <https://gpuperhour.com/providers/runpod>
- Lambda pricing — <https://lambda.ai/pricing> ; <https://www.synpixcloud.com/blog/lambda-labs-gpu-pricing-2026>
- GCP TPU pricing — <https://cloud.google.com/tpu/pricing> ; <https://www.spheron.network/blog/google-tpu-trillium-v6-vs-nvidia-b200-llm-inference/>
- vLLM DiffusionGemma (FP8 1008/1288 tok/s) — <https://github.com/vllm-project/vllm-project.github.io/blob/main/_posts/2026-06-10-diffusion-gemma.md>
- DiffusionGemma model card (1100 tok/s H100 FP8) — <https://huggingface.co/google/diffusiongemma-26B-A4B-it>
- JAX/Flax DiffusionGemma — <https://github.com/google-deepmind/gemma/tree/main/gemma/diffusion> ; <https://github.com/google/hackable_diffusion>
- transformers MoE source (the FP8 crux) — `reference-code/transformers/src/transformers/integrations/moe.py`, `deepgemm.py`, `sonicmoe.py` (vendored)
