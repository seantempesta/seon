---
type: research
status: active
tags: [research, diffusion, agent]
---

# PyTorch/transformers vs vLLM for DiffusionGemma — the decision roadmap

> The owner pressed: how long do we stay on raw transformers, and what concretely
> moves us to vLLM? This is the decision logic, not a survey restatement. It
> RE-VERIFIES the prior "vLLM seals the sampler" finding against the current
> (2026-06-28) vLLM docs/blog/recipe, pins the A100-vs-Hopper economics with a
> NEW number, and gives the engine × use-case matrix + the switch triggers.
>
> Grounds in: [[serving-optimization-survey-2026-06-28]] (the prior two-endpoint
> finding), [[transformers-diffusion-source-grounding-2026-06-28]] (the per-step
> seam `:1034`, the compile/stop tension), and live web evidence (cited inline).

## TL;DR

- **vLLM's sampler is STILL sealed for our buzzsaw, re-verified today — and the
  reason is now sharper than "no hook": it's a SHAPE mismatch, not just a missing
  callback.** vLLM DOES have a first-class custom-logits-processor API (register via
  `--logits-processors` / FQCN / entry-point; `apply()` runs "in every engine
  step"). But (a) the DiffusionGemma blog's own description of `DiffusionSampler`
  shows the entropy-bound accept/renoise happening INSIDE one `@torch.compile`d
  `_compiled_sample_step`, and does NOT route user logits processors through it; and
  (b) the public LP interface is `apply(logits: torch.Tensor) # (num_requests) ×
  (vocab_size)` — **there is no canvas/position axis.** Our clamp/infill need
  per-POSITION control across the 256-token canvas (`(req, canvas_len, vocab)`).
  Even if `apply()` *were* called per denoise step, it cannot see or address canvas
  positions. **The seam vLLM exposes is the wrong shape for the buzzsaw. Confirmed
  sealed.**

- **The A100 economics are now DECISIVE against switching for speed: vLLM's win is
  FP8-on-Hopper, NOT BF16.** The vLLM recipe's own SPEED-Bench lists the **BF16**
  single-GPU number at **375 tok/s (1.9× AR)** — the 1,008 (H100) / 1,288 (H200)
  headlines are the **FP8** rows. Our compiled-transformers path on the A100
  measured **~450 tok/s** (tonight). **So on BF16, vLLM is NOT faster than our
  compiled transformers — it is in the same band (≈375 vs ≈450), and it's on a
  faster GPU.** vLLM buys nothing on the A100. Its speed delta is entirely the FP8
  tensor cores the A100 (Ampere) does not have. **Switching engines does not unlock
  speed; switching DTYPE+GPU (FP8+Hopper) does — and that's orthogonal to the
  engine for the control path.**

- **Recommendation — STAY on raw transformers for the entire research/thesis
  phase, indefinitely while per-step control is the product.** The buzzsaw IS the
  per-step `logits_processor` seam (`generation_diffusion_gemma.py:1034`) +
  `accept_canvas`/renoise + the custom adaptive-stop ABC. That control is the
  thesis; vLLM cannot host it without forking a compiled Triton sampler. The ONE
  thing to chase on the A100 is **compiled-transformers-WITH-compatible-control**
  (the genuine best-of-both): `cache_implementation="static"` + a
  **compile-compatible** stop (the model's BUILT-IN entropy/stability criterion,
  NOT a Python parse/eval criterion). That keeps full clamp/renoise control AND
  recovers most of the compile speedup. The Python-parse early-stop forfeits
  `torch.compile` (≈4× slower) — so it is a deliberate per-experiment toggle, not
  the serving default.

- **vLLM enters ONLY as a SEPARATE serving endpoint, and only when ALL THREE
  triggers fire at once:** (1) the buzzsaw thesis has cleared its kill-gate on
  transformers (control proven worth serving); (2) we need many-user / batched
  serving throughput that the control worker can't give; AND (3) we have
  Hopper-FP8 (or Blackwell-NVFP4) hardware so vLLM's FP8 win actually exists.
  Until all three hold, adding vLLM is pure cost (a second deploy, a second adapter
  backend, no control, and on the A100 not even faster). The two-endpoint split
  from the prior survey stands — but the serving endpoint is a FUTURE, hardware-
  gated thing, not a now thing.

---

## 1. Re-verification: is vLLM's per-step seam still sealed? (YES — and it's a shape problem)

The prior survey ([[serving-optimization-survey-2026-06-28]] §2) concluded vLLM
seals sampling inside `DiffusionSampler._compiled_sample_step` with no per-step
`accept_canvas`/`LogitsProcessor` hook. Re-checking the CURRENT vLLM docs surfaced
a wrinkle worth pressing: vLLM ships a real **custom logits-processor** feature.
Does that crack the seal? **No — three independent reasons, all from today's
sources.**

### 1a. The DiffusionSampler is one compiled op; the accept/renoise is INSIDE it

vLLM blog (re-fetched 2026-06-28), verbatim:

> "The per-step work is a single `@torch.compile`d function, `_compiled_sample_step`,
> vectorized over all in-flight decode requests… **Denoise**: temperature-scale the
> logits, draw a candidate token at each canvas position with the Gumbel-max trick
> (`argmax(logits/T + gumbel_noise)`), accept the most confident positions up to the
> entropy bound, and renoise the rest to random tokens. The step also records the
> argmax canvas and checks for convergence…"
> — <https://vllm.ai/blog/2026-06-10-diffusion-gemma>

The temperature scale, the Gumbel-max draw, the entropy-bound accept, AND the
renoise are all inside this compiled function. The blog's description of the sampler
contains **no mention of user logits processors being invoked during denoising**
(re-fetched, explicit null). `DiffusionSampler` "takes the place of vLLM's usual
(Sampler, RejectionSampler) pair" — i.e. it REPLACES the standard sampler that would
normally run the LP pipeline.

### 1b. The custom-LP interface has the WRONG SHAPE — no canvas axis

vLLM custom-logits-processor docs, verbatim:

> "vLLM will invoke `update_state()` and `apply()` against that logits processor in
> every engine step."
>
> ```python
> def apply(self, logits: torch.Tensor) -> torch.Tensor:
>     # Consumes (num_requests) × (vocab_size) tensor
>     # Returns transformed logits tensor
> ```
> — <https://docs.vllm.ai/en/latest/features/custom_logitsprocs/>

This is THE structural barrier. The interface is `(num_requests, vocab_size)` — one
row per request, the AR shape. The buzzsaw operates on a **canvas**: it must clamp
specific POSITIONS (`(req, canvas_len, vocab)`) to pin good spans and re-randomize a
hole. There is no position dimension in `apply()`. Even granting the most optimistic
reading — that the per-engine-step LP hook fires once per denoise step — a custom LP
**cannot address canvas positions**, so it cannot express clamp, infill, or
eval-renoise. The seam exists for AR sampling; it is the wrong shape for diffusion
control.

### 1c. The recipe exposes ONLY entropy_bound / canvas_length — no `--logits-processors` for control

vLLM recipe (re-fetched), verbatim on what's configurable:

> entropy-based sampler (`diffusion_sampler: entropy_bound`, `entropy_bound: 0.1`),
> set via `--hf-overrides`. Launch flags shown: `--max-num-seqs 4`,
> `--gpu-memory-utilization 0.85`, `--hf-overrides
> '{"diffusion_sampler":"entropy_bound","diffusion_entropy_bound":0.1}'`. **No
> `--logits-processors` flag appears in any DiffusionGemma launch command.**
> — <https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it>

You can tune the sampler's BEHAVIOR (entropy_bound, canvas_length) but cannot inject
Python that reads/rewrites the canvas between denoise steps.

### 1d. Coupling tax: even forking the sampler is hostile

A current vLLM bug confirms how tightly `_compiled_sample_step` is wired:
DiffusionGemma "**crashes under tensor-parallel (TP>1) and pipeline-parallel (PP>1)
— multi-GPU is unusable**" due to incompatibility with vocabulary-parallel sharded
weights inside `_compiled_sample_step`
(<https://github.com/vllm-project/vllm/issues/45719>). Forking this compiled,
vocab-parallel-fragile function to inject canvas-position control would be a deep,
brittle C++/Triton-adjacent change that re-breaks on every vLLM bump — defeating the
entire "use vLLM because it's supported and fast" rationale.

**Verdict (1): vLLM is sealed for the buzzsaw. Not for lack of a callback — the one
callback it has is the wrong shape (no canvas axis) and the sampler that would host
it is a single compiled op that doesn't route user LPs. Re-confirmed 2026-06-28.**

## 2. What does vLLM actually buy on the A100? (Nothing — the win is FP8/Hopper)

This is the number that changes the decision. The prior survey said "A100 can't
reach 1000 (no FP8/NVFP4)" but left the A100 BF16 ceiling as "must measure." The
vLLM recipe's own SPEED-Bench gives us the BF16 reference point on Hopper:

| Config | Engine | GPU | dtype | tok/s | Source |
|---|---|---|---|---|---|
| Headline | vLLM | H200 | **FP8** | **1,288** (6×) | vLLM blog |
| Headline | vLLM | H100 | **FP8** | **1,008** (5×) | vLLM blog |
| **BF16 row** | vLLM | H100/H200 | **BF16** | **375 (1.9×)** | vLLM recipe |
| Our path | **transformers, compiled** | **A100** | **BF16** | **~450** (measured tonight) | this session |
| Our path | transformers, custom Python stop | A100 | BF16 | **~110** (≈4× slower, compile forfeited) | this session |

The decisive read: **on BF16, vLLM is 375 tok/s on a faster GPU; our compiled
transformers is ~450 tok/s on the A100.** vLLM does NOT beat our compiled path in
the same dtype — it's in the same band. The entire 1,008/1,288 advantage is the
**FP8 tensor cores on Hopper**, which the A100 (Ampere) physically lacks. NVFP4
(`nvidia/diffusiongemma-26B-A4B-it-NVFP4`) is the Blackwell analog — also not an
Ampere path (GB10 notes: <https://github.com/miter37/diffusiongemma-vllm-gb10-notes>).

> **The lever is dtype+GPU, not engine.** Moving transformers→vLLM on the SAME A100
> BF16 yields ~no speedup (375 vs 450). Moving BF16→FP8 yields ~2.7× — but requires
> Hopper, and is available on the transformers path too (FP8 weights load in
> transformers; the per-step control seam survives). So "switch to vLLM for speed"
> is a category error on the A100: there is no speed there to switch for.

## 3. The engine × use-case decision matrix

| Use-case | Engine | Why | Hardware |
|---|---|---|---|
| **Buzzsaw research** (clamp / infill / eval-renoise / per-step control) | **transformers** | ONLY path with the `:1034` logits seam + `accept_canvas` + custom-stop ABC | A100 BF16 (have it) |
| **Knob sweeps / gym scorecards** (entropy_bound, denoise schedule, canvas-length) | **transformers** | needs the trace streamer + per-row metrics; control & observation | A100 BF16 |
| **A100 "fast as we can get" demo, control still on** | **transformers, compiled** (`static` cache + built-in compatible stop) | recovers most compile speedup WITHOUT losing clamp/renoise | A100 BF16 |
| **Many-user / batched serving, control NOT needed** | **vLLM** (separate endpoint, OpenAI-compatible) | the only framework running the real diffusion decode at FP8 scale | **Hopper FP8** (don't have) |
| **Snappy "primary model" demo (~1000 tok/s)** | **vLLM** | FP8 path, batch≤4 | **Hopper FP8 / Blackwell NVFP4** |

Two engines, ONE Seon provider adapter (`:diffusiongemma`) with a backend selector
(`SEON_DG_BACKEND=control|vllm`), exactly as [[serving-optimization-survey-2026-06-28]]
§3 designed. Nothing in that adapter shape changes; this doc only sharpens WHEN the
`vllm` backend becomes worth standing up.

## 4. The switch triggers (concrete, falsifiable)

**Stay on transformers until ALL of these are TRUE simultaneously. Each is a
checkable gate, not a vibe:**

1. **Thesis cleared.** The buzzsaw kill-gate has PASSED on transformers — per-step
   control (eval-renoise live) demonstrably beats the no-control baseline on the gym
   scorecard. (If control never proves out, we never need the serving split at all —
   we'd serve a plain AR model.) → gated by [[roadmap]] P1.
2. **Serving scale is the actual bottleneck.** There is a real workload with
   concurrency the single control worker can't satisfy (multi-user, or batched
   throughput where wall-clock-per-turn on the control path is the demo's limiting
   factor). Until a human is waiting on throughput, this is false.
3. **Hopper-FP8 (or Blackwell-NVFP4) hardware is in hand.** vLLM's win is FP8; on
   our A100 it doesn't exist. No Hopper ⇒ no reason ⇒ trigger false.

**When all three fire:** stand up vLLM as a SECOND endpoint behind the existing
adapter (`SEON_DG_BACKEND=vllm`, OpenAI-compatible, ~no new adapter code per §3).
The control worker stays — it does not get replaced; serving and research are
permanently different backends because the sampler seal is permanent (§1).

**Reverse trigger (when to NOT bother with vLLM even later):** if Seon's
DiffusionGemma usage stays research/single-user/agent-loop (one turn at a time,
control always on), vLLM never pays for itself — the control worker IS the product
and FP8 weights on a compiled transformers path cover any speed need. vLLM is a
serving-scale tool; absent serving scale, it's dead weight.

## 5. The PyTorch timeline + the best-of-both lever

**How long on raw transformers? Indefinitely, as long as per-step control is core
to the thesis.** This is not a "we'll migrate eventually" holding pattern — the
control seam is the differentiated product, and it lives ONLY in transformers
(`generation_diffusion_gemma.py:1034`, the `DiffusionGemmaAdaptiveStopping` ABC at
`:466`/`:1207`, the seedable `decoder_input_ids`/self-cond at `:979`). There is no
version of vLLM on the public roadmap that exposes canvas-position control, and the
compiled, vocab-parallel-fragile sampler (§1d) makes a fork a losing trade.

**Could buzzsaw control ever live in a vLLM custom sampler (fork the sampler)?**
Technically yes — subclass/replace `DiffusionSampler`, add a canvas-position clamp
inside a custom `_compiled_sample_step`. Cost/benefit: **strongly negative.** You'd
maintain a compiled Triton-adjacent sampler against a sampler that already crashes on
TP>1 (§1d), re-break on every vLLM release, and lose the "supported/fast" reason to
be on vLLM in the first place — all to recover speed you can ALSO get by putting FP8
weights on the transformers path. Only revisit if (a) transformers' generation loop
becomes a proven throughput wall AND (b) vLLM ships a stable canvas-aware sampler
extension API. Neither is true; don't fork.

**The real best-of-both = compiled-transformers-WITH-compatible-control (chase
this, on the A100, now-ish — it's the untested speed lever).** From the source
grounding ([[transformers-diffusion-source-grounding-2026-06-28]] §5 items 9 + 4):

- `cache_implementation="static"` enables `torch.compile` of the
  encoder/decoder/sampler (`:692-696`, `:1235-1265`). This is the compiled fast path
  — the ~450 tok/s regime, and the thing that closes most of the gap to vLLM-BF16.
- **It is compatible with the built-in `StableAndConfidentStoppingCriteria`** (the
  model's native entropy/stability adaptive-stop, compile-safe) AND with our
  `ClampLogitsProcessor` and `accept_canvas`/renoise — all of which are tensor ops,
  not Python control flow. So **clamp + infill + early-stop-on-confidence all survive
  torch.compile.** That is genuine control + most of the speed.
- The ONLY control that forfeits compile is a **Python parse/eval stopping
  criterion** (the custom `DiffusionGemmaAdaptiveStopping` subclass that decodes the
  canvas and runs the seon parser) — `is_compiling=False` on DynamicCache, ≈4×
  slower (the ~110 tok/s row). That is a deliberate per-experiment toggle: turn it on
  for the eval-renoise experiments that NEED mid-denoise parse, leave it off (compiled
  + built-in confidence stop) for everything else, including any "feel fast" demo on
  the A100.

> **Net:** the A100 speed story is NOT "switch to vLLM." It's "turn on
> `static`-cache `torch.compile` with the built-in confidence stop, tune
> entropy_bound→0.1 (the ~4→15-20 tokens/forward lever from the prior survey), and
> reserve the Python-parse stop for the experiments that require it." That gets us
> compiled-transformers speed WITH full clamp/renoise control — the best of both,
> on hardware we already have, with zero new infra.

## 6. Alternatives (one line each)

- **SGLang / TGI / TensorRT-LLM:** no public DiffusionGemma block-diffusion support
  found (prior survey §2; re-confirmed — NVIDIA markets only the vLLM+NVFP4 path).
  The dLLM decode needs vLLM's bespoke ModelState/DiffusionSampler plumbing those
  frameworks haven't replicated 18 days post-launch. **Treat as unsupported.**
- **GGUF (llama.cpp/Ollama/LM Studio):** serving-only, consumer VRAM, **zero
  per-step control** — irrelevant to the buzzsaw, possible future for a local
  no-control demo only.
- **FP8 weights ON the transformers path:** the under-appreciated option — FP8
  checkpoints (`RedHatAI/...-FP8-dynamic`) load in transformers; on a Hopper box this
  gives the FP8 speedup WHILE KEEPING the `:1034` control seam. This, not vLLM, is
  the way to get both FP8 speed and control if/when we have Hopper. Measure when
  hardware allows.

## 7. Bottom line (the owner's decision, stated plainly)

- **Stay on PyTorch/transformers for the whole research+thesis phase, and as the
  permanent CONTROL backend.** It is the only home for the buzzsaw seam; there is no
  migration deadline because there is no migration — the control path and the serving
  path are permanently separate backends of one adapter.
- **Do NOT switch to vLLM for speed on the A100. There is no speed there to switch
  for** (vLLM BF16 375 ≈ our compiled 450; the FP8 win is Hopper-only and orthogonal
  to the engine).
- **The A100 speed lever is compiled-transformers-with-compatible-control**
  (`static` cache + `torch.compile` + built-in confidence stop + entropy_bound 0.1),
  reserving the compile-forfeiting Python parse-stop for the experiments that need
  it. Best of both, zero new infra.
- **vLLM gets stood up as a second serving endpoint ONLY when thesis-cleared AND
  serving-scale-bound AND Hopper-FP8-in-hand — all three at once.** Until then it is
  cost without benefit.

---

## Sources (verbatim quotes preserved inline above)

- vLLM blog — DiffusionSampler `_compiled_sample_step`, FP8 1,288/1,008 numbers, no per-step LP in denoise: <https://vllm.ai/blog/2026-06-10-diffusion-gemma>
- vLLM custom logits processors — `apply(logits) # (num_requests)×(vocab_size)`, "every engine step", no canvas axis, no diffusion mention: <https://docs.vllm.ai/en/latest/features/custom_logitsprocs/>
- vLLM recipe (DiffusionGemma) — **BF16 375 tok/s (1.9×)** row, entropy_bound/canvas_length only, no `--logits-processors`: <https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it>
- vLLM issue #45719 — DiffusionGemma TP>1/PP>1 crash inside `_compiled_sample_step` (coupling/fork-hostility evidence): <https://github.com/vllm-project/vllm/issues/45719>
- GB10 NVFP4 notes (Blackwell-only quant path): <https://github.com/miter37/diffusiongemma-vllm-gb10-notes>
- HF model card (entropy_bound 0.1, 15-20 tok/forward, FP8 Hopper headline): <https://huggingface.co/google/diffusiongemma-26B-A4B-it>
- Transformers source grounding (the `:1034` seam, compile/stop tension, static-cache fast path): [[transformers-diffusion-source-grounding-2026-06-28]]
- Prior serving survey (two-endpoint split, ModelState/DiffusionSampler, A100 no-FP8): [[serving-optimization-survey-2026-06-28]]
</content>
</invoke>
