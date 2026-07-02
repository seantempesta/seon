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
> seam `:1034`, the compile/stop tension), live web evidence (cited inline), and
> — NEW — the **vendored vLLM source** `reference-code/vllm` @ `311ad689a`
> (v0.13.0rc1+), the real `vllm/model_executor/models/diffusion_gemma.py` (§1b).
> Every vLLM-internal claim now cites `file:line` in that tree, not just the blog.

## TL;DR

- **vLLM's diffusion sampler is STILL sealed for our buzzsaw — now CONFIRMED FROM
  THE VENDORED SOURCE, not just the blog.** `DiffusionGemma.custom_sampler()`
  RETURNS a `DiffusionSampler` that *replaces* vLLM's standard `(Sampler,
  RejectionSampler)` pair (`diffusion_gemma.py:827,844`). That standard sampler is
  the ONLY thing that runs user logits processors (`vllm/v1/sample/sampler.py:98`
  `apply_logits_processors`, `:404` iterating `logitsprocs.non_argmax_invariant`) —
  and `DiffusionSampler` NEVER calls it. The entire denoise step is ONE
  `@torch.compile(dynamic=True)` function `_compiled_sample_step`
  (`diffusion_gemma.py:469`), called once at `:1285`, with temperature, Gumbel-max,
  **entropy-bound acceptance mask** (`:555-561`) and **renoise** (`:577`) all baked
  inside it. The `LogitsProcessor` that DOES appear in the file (`:252,:334`) is
  only the lm_head GEMM (`:248` comment "LogitsProcessor only handles the lm_head
  GEMM") — a vocab projection, NOT a control seam. **There is no per-step Python
  hook, no canvas-position callback, no LP route into the diffusion path. Sealed,
  source-proven.**

- **The PREFIX-CACHING win is REAL and source-confirmed — and it's a free
  accelerator for the dynamic-context pattern, orthogonal to control.** DiffusionGemma's
  encoder pass is ordinary CAUSAL attention that WRITES the KV cache
  (`diffusion_gemma.py:5-7`); its config does NOT disable automatic prefix caching
  (the only model that does is Unlimited-OCR, `config.py:150-163` — DiffusionGemma's
  config class `:241` leaves APC ON). So a shared ~2400-token SKILL prefix is encoded
  ONCE and its KV reused across every generation that shares it. Raw transformers
  re-encodes that prefix on EVERY call. For the buzzsaw's "prepend a big skill, then
  refine many blocks" pattern, vLLM's APC is a genuine per-call prefill saving the
  control worker cannot match — a real reason vLLM serving is attractive ONCE control
  is no longer needed per-call.

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

- **Don't reinvent the wheel — the control worker's speed is ADOPTED in-the-loop
  features, not a custom engine (§5b).** The FP8-with-control answer already exists:
  **torchao quantization** (`TorchAoConfig` + `Float8DynamicActivationFloat8WeightConfig`
  on Hopper, `Int8DynamicActivationInt8WeightConfig` on the A100) is applied at
  `from_pretrained`, is TRANSPARENT to `generate()`, and so composes with
  DiffusionGemma's custom denoise loop WHILE keeping the `:1034` control seam —
  vendored in transformers (`quantizer_torchao.py`), `cache_implementation="static"`
  auto-compiles, `disable_compile=True` for the parse-stop experiments. Plus
  `static`-cache `torch.compile`, `past_key_values`/`QuantizedCache` reuse, and —
  for more headroom — **Fast-dLLM** (NVlabs, training-free block/Dual KV cache +
  confidence-parallel decoding, all in-the-loop ⇒ control-compatible). transformers'
  OWN continuous-batching/`transformers serve` is real but AR-only (the diffusion
  `generate()` bypasses it). We compose existing PyTorch-ecosystem features; we
  hand-roll nothing; the serving endpoint is vLLM adopted whole.

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

## 1b. Source-grounded answers (reference-code/vllm @ 311ad689a)

The owner vendored the vLLM source mid-task and asked four sharpened questions.
Answered from `vllm/model_executor/models/diffusion_gemma.py` (the real
implementation), not the blog. Every claim is `file:line`.

### Q1 — Can we run our CLJS validator via a vLLM hook and act per-step? NO seam exists in the diffusion path.

- **`custom_sampler()` REPLACES the standard sampler.**
  `DiffusionGemmaModelState.custom_sampler(self, sampler)` (`:827`) returns
  `DiffusionSampler(...), None` (`:844-859`). The model-runner installs THIS as the
  sampler for diffusion requests. The blog's "takes the place of vLLM's usual
  (Sampler, RejectionSampler) pair" is literal: the returned tuple is `(custom_sampler,
  custom_rejection_sampler=None)`.
- **The standard sampler is the ONLY place user logits processors run, and the
  diffusion path bypasses it.** `vllm/v1/sample/sampler.py:98` calls
  `self.apply_logits_processors(...)`; `:371` defines it; `:404` iterates
  `sampling_metadata.logitsprocs.non_argmax_invariant` (the registered custom LPs).
  `DiffusionSampler` (`diffusion_gemma.py:1038`) NEVER calls `apply_logits_processors`
  and holds no `logitsprocs` — it goes straight from raw model logits (`:1286`) into
  `_compiled_sample_step` (`:1285`). So the AR logits-processor plugin API, the
  guided/structured-output backends, none of them are on the diffusion code path.
- **The only `LogitsProcessor` in the diffusion file is a red herring.** `:252`
  constructs `self.logits_processor = LogitsProcessor(...)` and `:334` calls it —
  but the `:248` comment states "LogitsProcessor only handles the lm_head GEMM." It
  is the hidden-state→vocab projection, not a sampling intervention. Do not mistake
  the name for a hook.
- **What IS configurable is behavior only, not code injection:** `entropy_bound`,
  `t_min`/`t_max`, `confidence_threshold`, `canvas_length`, `max_denoising_steps`
  are read from `generation_config.json` / `--diffusion-config` at sampler-build
  time (`:828-852`, `config.py:244-249`). Knobs, not callbacks.

> **Q1 verdict:** there is NO per-step logits processor, custom-op plugin, or
> sampler callback exposed on vLLM's DiffusionGemma decode path. The accept/stop/
> renoise the buzzsaw needs all live INSIDE one compiled op with no Python entry
> point. A 0.1 ms CLJS validator has nowhere to attach per-step. **Sealed —
> source-proven, not inferred.**

### Q2 — Even if a hook existed, would per-step Python intervention forfeit vLLM's fast path? YES, harder than transformers.

The DiffusionSampler docstring is explicit about the design constraint
(`:1041-1043`): "all GPU state in pre-allocated buffers, **no GPU→CPU syncs on the
hot path**." The whole decode is `@torch.compile(dynamic=True)` (`:469`) over
continuous-batching: one `_compiled_sample_step` call processes ALL in-flight
decode requests at once (`:516` `num_decode = decode_slots.shape[0]`).

- A per-step Python callback that decodes the canvas to text and runs our validator
  REQUIRES a GPU→CPU sync of `argmax_canvas` every step — exactly the sync the hot
  path is engineered to avoid — AND a **graph break** in the compiled region.
- This is the SAME failure we MEASURED in transformers (a Python stopping criterion
  forfeits `torch.compile`, ≈4× slower), but **strictly worse in vLLM** because the
  compiled op is batched: a sync/break stalls the WHOLE batch of concurrent
  requests, not one. vLLM's throughput edge IS the batched compiled op; a per-step
  Python intervention dismantles the very thing you came to vLLM for.
- **Conclusion:** per-step Python control and vLLM's compiled continuous-batching
  decode are fundamentally at odds. There is no "hook it cheaply" — any external
  per-step intervention collapses vLLM to (worse-than-)transformers speed. The
  compile-forfeit tension is not a transformers quirk; it is intrinsic to compiled
  diffusion decode, and vLLM amplifies it.

### Q3 — Custom vLLM sampler baking control INTO the compiled op: feasible, but buys only TENSOR-expressible control, and the eval-loop is the part you can't get.

The sampler is **pure Python + compiled PyTorch ops, NOT Triton/CUDA.**
`_compiled_sample_step` (`:469-...`) is plain `torch` (sort/cumsum/scatter/randint
— e.g. the entropy-bound mask `:555-561`, renoise `:577`) under
`@torch.compile`. So control logic that can be written as VECTORIZED TENSOR OPS can
be added inside it and stay compile-compatible:

- **Clamp / structured-constraint / a tensor-expressible stop (entropy/stability/
  token-pattern): FEASIBLE.** Add a clamp-mask tensor (pin good canvas positions to
  ~0 entropy so the accept mask always keeps them) and a custom renoise override —
  all torch ops, all inside the compiled region, no sync. Effort: fork ONE ~1300-line
  file (`diffusion_gemma.py`), add a few tensor ops + a per-request mask buffer +
  wire a config field. A competent engineer-week for a first cut; the clamp itself is
  small.
- **Eval-driven control (run the CLJS parser/eval on the partial canvas, stop/renoise
  on the RESULT): NOT feasible in the compiled op.** Our validator is external
  Python/Clojure — it cannot be expressed as a tensor op, so it cannot live inside
  `_compiled_sample_step` without a sync+graph-break (back to Q2). A custom sampler
  gets you compile-compatible CLAMP and tensor-predicate stops; it does NOT get you
  the buzzsaw's parse-and-eval feedback loop. That loop is precisely the thesis.
- **Plus the maintenance tax:** the fork inherits vLLM's TP>1/PP>1 crash inside
  `_compiled_sample_step` (issue #45719, vocab-parallel sharding) and re-breaks on
  every vLLM bump (this file is rc-stage, churning).

> **Q3 verdict (price it out):** ~1 engineer-week for a compile-compatible CLAMP
> sampler fork; ongoing maintenance against a churning rc file + the TP crash. It
> buys clamp + tensor-predicate stops at vLLM speed — genuinely useful for a
> *constrained-but-fast* serving mode. It does NOT buy the eval-renoise loop (the
> differentiated capability). So the "custom adapter win" is real but PARTIAL:
> it's a speed-up for the control we can express as tensors, never a home for the
> eval feedback. Don't fund it until the eval loop has proven its worth on
> transformers AND a fast constrained-serving mode is actually demanded.

### Q4 — Prefix caching: confirmed from source; quantify the skill-prefix win.

- **DiffusionGemma's encoder mode is causal and writes KV** (`diffusion_gemma.py:5-7`
  module docstring: "encoder mode: causal attention, writes KV cache; decoder mode:
  bidirectional attention, reads encoder KV, doesn't write"). A causal-prefix KV is
  reusable across requests — the precondition for automatic prefix caching (APC).
- **APC is NOT disabled for DiffusionGemma.** The only model whose config turns APC
  off is Unlimited-OCR (`config.py:150-163`, "Disable it for this model" — R-SWA
  decode KV isn't cacheable). DiffusionGemma's config class
  (`DiffusionGemmaModelForBlockDiffusionConfig:241`) inherits Gemma4 and leaves
  `enable_prefix_caching` untouched → APC ON by default.
- **Quantify the win for the buzzsaw pattern.** The thesis prepends a shared
  ~2400-token SKILL to many generations. With APC, that prefix is prefilled ONCE; the
  KV is reused for every subsequent request sharing it → the per-call prefill of the
  skill drops to a cache hit. Raw transformers re-encodes all ~2400 tokens on EVERY
  call (no cross-call KV reuse in the `generate()` path). For N generations sharing
  the skill, vLLM does ~1 skill-prefill; transformers does N. At a 2400-token prefix
  and the buzzsaw's "one skill, many block-refinements" shape, that is the dominant
  prefill cost eliminated for all but the first call — a large, control-independent
  accelerator.
- **Caveat (memory, from source):** the diffusion sampler materializes `[num_seqs,
  canvas_length, vocab]` fp32 transients, so concurrency is memory-bound — vLLM
  defaults `max_num_seqs` to 8 and notes ">8 OOMs a single H200" (`config.py:282-292`).
  APC helps prefill reuse; it does not lift the canvas-buffer concurrency ceiling.

> **Q4 verdict:** prefix caching is real, enabled, and a genuine win for the
> shared-skill pattern — the skill KV is encoded once and reused, where transformers
> re-encodes every call. This is an accelerator the vLLM serving endpoint gives FREE,
> independent of the control question, and it strengthens the case for vLLM as the
> SERVING backend once per-call control is no longer required.

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
Priced from source in §1b/Q3: the sampler is pure Python + compiled torch (NOT
Triton/CUDA), so a compile-compatible CLAMP + tensor-predicate stop CAN be baked
into `_compiled_sample_step` for ~1 engineer-week. **But the eval-renoise loop — the
differentiated capability — CANNOT** (external CLJS parse/eval is not a tensor op;
inside the compiled op it forces a sync+graph-break, §1b/Q2). And the fork inherits
the TP>1 crash (#45719) + per-release churn. Cost/benefit: **negative for the
thesis, marginal for serving.** It would give a fast *constrained* serving mode, never
a home for the feedback loop. Only revisit if (a) a fast constrained-serving mode is
actually demanded AND (b) the eval loop has already proven its worth on transformers.
Until then, don't fork.

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

## 5b. Proper serving — ADOPT existing features, don't reinvent (the owner's directive)

The directive: don't hand-roll serving; mine PyTorch/transformers/vLLM + other
repos for features we can lift. Surveyed the vendored source + the ecosystem. The
load-bearing reframe: **for the CONTROL path, the accelerators that matter all live
IN the decode loop (quantization, compile, KV-cache tricks) and are therefore
control-compatible — we adopt them; for the no-control SERVING path, we adopt vLLM
whole.** Concretely:

### 5b.1 torchao quantization — FP8 (Hopper) / INT8 (A100), TRANSPARENT to the custom generate loop

This is the biggest "don't reinvent" win, and it's already vendored in transformers
(`src/transformers/quantizers/quantizer_torchao.py`, `quantizer_fbgemm_fp8.py`,
`quantizer_finegrained_fp8.py`). `TorchAoConfig` is applied at `from_pretrained` and
quantizes the LINEAR layers — it is **transparent to `generate()`**, so it composes
with DiffusionGemma's custom denoise loop AND keeps the per-step `:1034` control
seam. Verbatim from the HF torchao doc
(<https://huggingface.co/docs/transformers/main/en/quantization/torchao>):

- **Hopper/Ada FP8:** `from torchao.quantization import
  Float8DynamicActivationFloat8WeightConfig` →
  `TorchAoConfig(quant_type=Float8DynamicActivationFloat8WeightConfig())` (listed
  under "H100 GPU"). FP8 dynamic quant needs compute capability ≥ 8.9 (Hopper/Ada).
- **A100 (Ampere, no FP8):** the doc's "A100 GPU" recipe uses
  `Int8DynamicActivationInt8WeightConfig` (A8W8 INT8) — the right quant for our
  current hardware.
- **Compile + quant together:** "Set the `cache_implementation` to `"static"` to
  automatically `torch.compile` the forward method." And the escape hatch we need
  for the parse/eval-stop experiments: "**Pass `disable_compile=True` in
  `generate()` to quantize without compilation.**"

> **This is the FP8-with-control answer that does NOT require vLLM.** On a Hopper box,
> load DiffusionGemma with `Float8DynamicActivationFloat8WeightConfig` +
> `cache_implementation="static"` → FP8 speed THROUGH the transformers diffusion
> generate loop, with the clamp/renoise/`:1034` seam intact. On the A100, the INT8
> variant is the analog. **Must verify** the quantized linears behave under
> DiffusionGemma's two-mode (causal/bidirectional) forward — the mechanism is
> generic (it swaps `nn.Linear` weights), but the model is 18 days old; measure
> quality + speed before trusting it. This single feature reclaims most of the
> reason people reach for vLLM (dtype speed) while keeping everything that makes
> the transformers path the buzzsaw's home.

### 5b.2 transformers' OWN continuous batching / paged attention / `transformers serve` — real, but AR-ONLY

transformers v5 ships a full serving stack we should NOT rebuild: `transformers
serve` (OpenAI-compatible server, `src/transformers/cli/serve.py`), continuous
batching with PagedAttention (`src/transformers/generation/continuous_batching/`),
CUDA-graph decode-fast-path (`continuous_api.py:375,529`), AND a logits-processor
adapter for the CB path (`cb_logits_processors.py`, `ContinuousBatchingLogitsProcessor`).

**BUT it does not drive the diffusion decode.** `generate_batch`
(`continuous_api.py:1153`) is the standard AR one-token-per-step loop
(`use_decode_fast_path`, CUDA graphs over single-token decode);
DiffusionGemma defines its OWN `DiffusionGemmaGenerationMixin.generate`
(`generation_diffusion_gemma.py:537,543`) — the canvas denoise loop — which BYPASSES
the CB manager entirely. Same structural reason vLLM's diffusion sampler bypasses
the AR sampler. **Do not try to force the diffusion model through transformers CB.**
For the control worker, batching = drive B prompts (or B renoise variants) in ONE
diffusion `generate()` call (the loop is already batched — grounding #8); that's the
free amortization we DO get.

### 5b.3 Fast-dLLM (NVIDIA Labs) — the published menu of diffusion-decode accelerations, control-compatible

The owner said "look through other repos." The relevant one is **Fast-dLLM**
(<https://github.com/NVlabs/Fast-dLLM>, arXiv 2505.22618,
<https://nvlabs.github.io/Fast-dLLM/>): **training-free** acceleration of diffusion
LLMs, applied at inference IN the decode loop. Three techniques, verbatim:

- **Block-wise KV Cache** — "By reusing attention Key-Value activations across
  multiple steps within each block… avoids redundant computation."
- **DualCache** — "also caches masked suffix tokens, enabling even greater speedup
  with negligible accuracy loss."
- **Confidence-aware parallel decoding** — "only tokens with confidence over a
  threshold are unmasked in parallel, while uncertain ones remain masked."
- Numbers: "up to 11× (GSM8K, length 512)… up to **27.6× throughput improvement**"
  on LLaDA / Dream.

**Honest read for DiffusionGemma:** Fast-dLLM targets MASK-based diffusers (LLaDA,
Dream); DiffusionGemma is NOT mask-based (random-init renoise — grounding). So it's
not a drop-in. The value is that **two of its three ideas are the published, formal
versions of things DiffusionGemma ALREADY does** (its entropy-bound acceptance ≈
confidence-aware parallel decoding; its encoder-writes-KV / decoder-reads-KV design ≈
block-wise KV reuse). The genuinely ADDITIVE idea is **DualCache (caching the suffix
KV, not just the prefix)** — a candidate accelerator for our control worker. The
decisive property: **all Fast-dLLM techniques live in the decode loop, are
training-free, and intervene per-step — exactly where OUR clamp/eval-renoise control
also lives — so they are control-COMPATIBLE.** This is the opposite of vLLM's sealed
sampler. If the control worker needs more speed than torchao+compile gives, the
Fast-dLLM repo is where to shop, not a custom kernel.

### 5b.4 KV-cache features we already get free in transformers

- **`QuantizedCache`** is a supported cache type IN the diffusion generate path
  (`generation_diffusion_gemma.py:98`) — lower-memory long-context KV.
- **`past_key_values` reuse across calls** (grounding #6, `:826`) — the
  transformers-side prefix reuse: feed the committed prefix's KV back into the next
  `generate()` so the shared skill isn't re-encoded per outer-loop call. It is NOT
  vLLM's automatic cross-REQUEST prefix cache (§1b/Q4), but for a single control
  worker running an outer eval-renoise loop it gives the same "encode the skill once"
  benefit within a session.

### 5b.5 What we DON'T reinvent — the serving endpoint is vLLM, as-is

For the no-control serving path, vLLM already IS the proper-serving implementation:
continuous batching, PagedAttention, automatic prefix caching (§1b/Q4), FP8/NVFP4,
OpenAI-compatible server. We adopt it whole (a thin OpenAI-style adapter), we do not
rebuild any of it. The ONLY thing we never get from it is per-step control — which is
why the control worker exists in parallel, accelerated by 5b.1–5b.4 instead.

> **Net for the directive:** the control worker's speed comes from ADOPTED,
> in-the-loop features — torchao INT8/FP8 quant (transparent to generate),
> `static`-cache `torch.compile`, `past_key_values`/`QuantizedCache` reuse, and (if
> needed) Fast-dLLM's DualCache — NONE of which forfeit the `:1034` control seam, and
> none of which we hand-roll. The serving endpoint is vLLM, adopted whole. We reinvent
> nothing; we compose existing PyTorch-ecosystem features around the one thing only we
> have — the eval-driven per-step control loop.

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
- **When it IS stood up, prefix caching is its free bonus:** the shared ~2400-token
  skill prefix is KV-cached once and reused across generations (`config.py:241`
  leaves APC on; encoder writes KV `diffusion_gemma.py:5-7`), where the transformers
  control worker re-encodes it every call. That accelerates the dynamic-context
  pattern independent of control — a real, source-confirmed reason vLLM serving wins
  once per-call control is no longer needed.

---

## Sources (verbatim quotes preserved inline above)

- **vLLM SOURCE `reference-code/vllm` @ `311ad689a`** — `diffusion_gemma.py`: `_compiled_sample_step` `@torch.compile` `:469` (entropy-mask `:555-561`, renoise `:577`, single call `:1285`); `custom_sampler` replaces standard sampler `:827,844`; `DiffusionSampler` "no GPU→CPU syncs on hot path" `:1041-1043`; lm_head-only LogitsProcessor `:248,252,334`; encoder writes KV `:5-7`. `vllm/v1/sample/sampler.py` — `apply_logits_processors` `:98,371`, custom-LP iteration `:404` (the AR-only path DiffusionSampler bypasses). `config.py` — APC disabled ONLY for Unlimited-OCR `:150-163`; DiffusionGemma config `:241` leaves APC on; canvas-buffer memory ceiling `:282-292`.
- vLLM blog — DiffusionSampler `_compiled_sample_step`, FP8 1,288/1,008 numbers, no per-step LP in denoise: <https://vllm.ai/blog/2026-06-10-diffusion-gemma>
- vLLM custom logits processors — `apply(logits) # (num_requests)×(vocab_size)`, "every engine step", no canvas axis, no diffusion mention: <https://docs.vllm.ai/en/latest/features/custom_logitsprocs/>
- vLLM recipe (DiffusionGemma) — **BF16 375 tok/s (1.9×)** row, entropy_bound/canvas_length only, no `--logits-processors`: <https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it>
- vLLM issue #45719 — DiffusionGemma TP>1/PP>1 crash inside `_compiled_sample_step` (coupling/fork-hostility evidence): <https://github.com/vllm-project/vllm/issues/45719>
- GB10 NVFP4 notes (Blackwell-only quant path): <https://github.com/miter37/diffusiongemma-vllm-gb10-notes>
- HF model card (entropy_bound 0.1, 15-20 tok/forward, FP8 Hopper headline): <https://huggingface.co/google/diffusiongemma-26B-A4B-it>
- Transformers source grounding (the `:1034` seam, compile/stop tension, static-cache fast path): [[transformers-diffusion-source-grounding-2026-06-28]]
- **torchao quantization (transformers)** — FP8 `Float8DynamicActivationFloat8WeightConfig` (Hopper), INT8 `Int8DynamicActivationInt8WeightConfig` (A100), `cache_implementation="static"` auto-compile, `disable_compile=True`: <https://huggingface.co/docs/transformers/main/en/quantization/torchao>; vendored quantizers `reference-code/transformers/src/transformers/quantizers/quantizer_torchao.py`
- **Fast-dLLM (NVIDIA Labs)** — training-free block-wise KV cache + DualCache + confidence-parallel decoding, up to 27.6×, in-the-loop (control-compatible): <https://github.com/NVlabs/Fast-dLLM>, <https://nvlabs.github.io/Fast-dLLM/>, arXiv <https://arxiv.org/abs/2505.22618>
- **transformers serving (AR-only)** — `transformers serve` `reference-code/transformers/src/transformers/cli/serve.py`; continuous batching + PagedAttention `.../generation/continuous_batching/continuous_api.py:1153`; CB logits-processor adapter `.../cb_logits_processors.py`; diffusion's own generate `.../models/diffusion_gemma/generation_diffusion_gemma.py:537,543` (bypasses CB)
- Prior serving survey (two-endpoint split, ModelState/DiffusionSampler, A100 no-FP8): [[serving-optimization-survey-2026-06-28]]
</content>
</invoke>
