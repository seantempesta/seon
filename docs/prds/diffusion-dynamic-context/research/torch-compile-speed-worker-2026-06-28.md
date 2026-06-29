---
type: research
status: active
tags: [research, diffusion, agent]
---

# `static`-cache + `torch.compile` on the DiffusionGemma worker — does it speed up, or does the MoE/custom-loop break it?

> The owner pressed: torchao INT8 was just proven a DEAD END (MoE experts are fused
> 3D params the quantizer SKIPS — `torchao-quant-worker-2026-06-28.md` / `8c4402bf`).
> The remaining non-KV A100 speed lever is the COMPILED path: the roadmap
> (`pytorch-vs-vllm-roadmap-2026-06-28.md` §5b) CLAIMED `cache_implementation="static"`
> + `torch.compile` is compile-compatible and the model's built-in entropy stop
> survives compile. That is a CLAIM — exactly like torchao's "transparent" claim
> that turned out materially wrong on the MoE. This doc VERIFIES it from source
> before we believe it.
>
> Grounds in the vendored `reference-code/transformers` @ **v5.11.0** — every claim
> is `file:LINE` in that tree (the same v5.11.0 the worker pins). Web evidence cited
> inline for the real-world speedup band + warmup cost.

## TL;DR

- **VERDICT: the compiled path is REAL and the model is ENGINEERED for it — this is
  the OPPOSITE of the torchao outcome.** Unlike torchao (which silently skips the
  fused 3D experts), `torch.compile` is a FIRST-CLASS, author-built path in
  DiffusionGemma's `generate()`. Passing `cache_implementation="static"` flips an
  internal `is_compiling` flag that compiles the encoder, the decoder `forward`, the
  sampler's `accept_canvas`/`renoise_canvas`, AND the built-in stopping criterion —
  all with `fullgraph=True, mode="reduce-overhead"` (CUDA graphs)
  (`generation_diffusion_gemma.py:692-696, 1235-1265`). The custom block-AR +
  denoise Python loop stays EAGER (it's orchestration); only the heavy tensor
  regions are compiled. There is no `disable_compile` flag — the toggle IS
  `cache_implementation` (`static`→compile, `dynamic`→eager).

- **The MoE does NOT break it — the default expert path is the COMPILE-FRIENDLY one,
  not the Python loop.** The torchao lesson was "the fused 3D experts are special and
  get skipped." Here the fused 3D experts are special and get a PURPOSE-BUILT
  vectorized kernel. `DiffusionGemmaTextExperts` carries `@use_experts_implementation`
  (`modeling_diffusion_gemma.py:529`), and the **default implementation is
  `grouped_mm`**, NOT `eager` (`modeling_utils.py:2048-2049`:
  `applicable_experts = "grouped_mm" if requested_experts is None else …`). The
  data-dependent Python `for`-loop over `.nonzero()` (`modeling_diffusion_gemma.py:554`)
  — the thing that WOULD graph-break under `fullgraph=True` — is the `eager` fallback,
  only used if you explicitly ask for `experts_implementation="eager"`. The default
  `grouped_mm_experts_forward` (`integrations/moe.py:380-481`) is fully vectorized:
  sort-by-expert → `torch.histc` offsets → `grouped_mm` → masked reduce, with the
  author's own comments stating they AVOID `torch.unique`/`bincount` precisely
  "to avoid cuda graph issues" / "breaks the graph capture (data-dependent)"
  (`moe.py:401, 429`). It is engineered to be graph-capturable.

- **On the A100 specifically, `grouped_mm` dispatches.** A100 = SM80; the kernel
  gate is `get_device_capability >= (8,0)` for torch≥2.9 (`moe.py:297-304`), and the
  worker runs torch 2.9.x BF16 — so `torch._grouped_mm` is used (BF16 is the one dtype
  it accepts under dynamo, `moe.py:280`). Even if it ever can't dispatch, there is a
  registered custom-op fallback WITH a `register_fake` shape stub
  (`moe.py:251-263`), so the fallback is ALSO compile-traceable. Either way
  `fullgraph=True` holds.

- **The built-in stop survives compile; a custom Python stop does NOT — and the
  worker authors ALREADY KNOW this.** `StableAndConfidentStoppingCriteria.__call__`
  is pure tensor ops and is itself compiled (`generation_diffusion_gemma.py:1258-1263`).
  A Python parse/eval/step-counter stop is incompatible: the live worker's
  `step_stopping` mode has an explicit comment KEEPING DynamicCache because "a static
  cache compiles the criterion (:1258-1263) and a Python counter won't survive
  torch.compile" (`tmp/flash-diffgemma/diffgemma_common.py:301-303`). So compile and
  the buzzsaw's per-step CLJS-oracle control are MUTUALLY EXCLUSIVE — compile is the
  SERVING-default lever, the eval-renoise experiments stay eager. This matches the
  roadmap's framing and is now source-confirmed.

- **HONEST GAP: the compiled path has NEVER run on this deployment.** The live worker
  (`tmp/flash-diffgemma/gpu_worker.py:55-62`) loads with the DEFAULT cache (Dynamic) →
  `is_compiling=False` → fully eager. The 137 tok/s first-light number is EAGER. The
  roadmap's "~450 tok/s compiled (measured tonight)" is **NOT** backed by any worker
  that sets `cache_implementation="static"` — treat it as a projection, not a
  measurement, until a `compile` mode is deployed and `verify_fresh`'d. The web band
  for static+compile on similar-size HF generate is ~2.5–3.8× (Gemma-2B ~3.8×,
  Llama-2-7B ~2.5×, larger models less), so 137→~340–450 tok/s is plausible — but
  UNPROVEN here.

- **Riskiest assumption to test first (the one number that decides it):** the
  **first-call compile/warmup wall-time** under `mode="reduce-overhead"` + the
  **steady-state `tok_per_s` vs eager** on the SECOND identical call. If warmup is
  minutes AND every distinct prompt length forces a re-capture (the buzzsaw's
  variable-length reality), the amortized win can evaporate for short interactive
  calls even though the per-step kernel is faster. This is the analog of torchao's
  VRAM check: measure warmup-seconds + recompile-count, not just the hot number.

---

## 1. The diffusion `generate()` × compile interaction — source-grounded

### 1a. There IS a built-in compiled inner path; it is auto-triggered by a static cache

`generate()` does NOT compile the whole loop. It compiles the heavy REGIONS and
leaves the Python block-AR/denoise orchestration eager. The trigger is a single flag:

```python
# generation_diffusion_gemma.py:692-696
is_compiling = past_key_values is not None and past_key_values.is_compileable
if is_compiling:
    encoder_forward_after_prefill, decoder_forward, sampler, diffusion_stopping_criteria = (
        self._compile_functions(sampler, diffusion_stopping_criteria)
    )
```

`is_compileable` is `True` for `StaticCache` and `False` for `DynamicCache`
(`cache_utils.py:363` vs `:40`). And the static cache is selected purely by the
generation-config knob: `cache_implementation` ∈ the static set →
`_prepare_static_cache` → `StaticCache` (`generation_diffusion_gemma.py:900-911,
1127-1160`). So **`cache_implementation="static"` IS the compile switch.** There is
**no `disable_compile` flag anywhere in the diffusion model** (grepped: zero hits) —
the roadmap's mention of `disable_compile=True` was wrong on the mechanism; the real
"turn compile off" is simply leaving the cache `dynamic` (the current worker default).

What `_compile_functions` compiles (`generation_diffusion_gemma.py:1235-1265`), each
`torch.compile(..., mode="reduce-overhead", fullgraph=True)`:

1. `self.model.encoder` — but only the POST-PREFILL encoder (fixed canvas_length
   input). Prefill (variable prompt length) runs the UNCOMPILED encoder
   (`:726` `encoder_forward = self.model.encoder if is_prefill else encoder_forward_after_prefill`)
   — deliberately, so a varying prompt length does NOT recompile the encoder.
2. `self.forward` — the decoder forward (operates on a FIXED `canvas_length` always).
   This is the path that contains the MoE (§2).
3. `sampler.accept_canvas` and `sampler.renoise_canvas` — the entropy-bound accept +
   renoise tensor ops.
4. `diffusion_stopping_criteria.__call__` — the built-in stop (§3).

The loop body itself (`_denoising_step`, `:997-1070`) is NOT compiled as a whole;
its glue (the temperature `logits_processor`, `torch.multinomial`, `argmax`) runs
eager between the compiled-region calls. This is the standard "compile the hot
regions, keep Python orchestration eager" pattern, and it is engineered with care:

- `cur_step` is converted to a `torch.tensor` "so a plain `int` won't trigger
  recompilations" (`:1017-1018`).
- `torch.compiler.cudagraph_mark_step_begin()` + `.clone()` on the accepted/renoised
  canvases are "needed for the compiled EB sampler" / "clone needed for compiled
  sampler" — CUDA-graph buffer-reuse safety (`:1019, 1045, 1047`).
- `_prepare_encoder_inputs` clones inputs `memory_format=torch.contiguous_format`
  "to prevent stride-related graph breaks" and pre-builds the 4D mask "in advance
  [to] prevent graph breaks" (`:942-945`).
- When compiling, the decoder attention mask is pre-allocated to
  `max_cache_len + canvas_length` (static shape, `:698-704`) and the per-canvas
  update writes in-place into that fixed buffer (`:1115-1117`).

This is not an accidental compile-survivability — the authors clearly tested
`fullgraph=True`. Graph breaks would have thrown.

### 1b. Graph-break / sync inventory (where eager glue remains)

- `if torch.all(finished_denoising): break` (`:782`) forces a GPU→CPU sync EACH
  denoise step. This is in the EAGER outer loop, NOT inside a compiled region, so it
  is a sync, not a `fullgraph` break. It bounds the cudagraph win (one sync/step), but
  does not defeat compilation.
- `streamer.put_draft(... .cpu())` (`:775-779`) — another per-step sync, but only if a
  streamer is attached (the worker does not use one for batch generate).
- The custom-LP temperature ramp (`LinearTemperatureScheduleLogitsProcessor`,
  `:272-312`) is applied in the eager glue (`:1034`). Pure division by a Python float
  → fine eager, and it is NOT in a compiled region so it can't break one.

**Conclusion (1):** the diffusion `generate()` is compilable on this model by design;
`cache_implementation="static"` is the switch; the outer Python loop stays eager and
that's intended.

## 2. The MoE + compile gotcha (the torchao lesson, re-run) — it does NOT break

This is the crux the owner flagged. The torchao failure was: the fused 3D
`gate_up_proj`/`down_proj` experts are a special param layout the INT8 quantizer
skips. Does that SAME 3D layout cause a graph-break under `fullgraph=True`?

**No — because the expert forward is dispatched, and the default dispatch is the
vectorized `grouped_mm`, not the Python loop.**

- The experts class is decorated: `@use_experts_implementation` then
  `class DiffusionGemmaTextExperts(nn.Module)` with the 3D params
  (`modeling_diffusion_gemma.py:529-539`).
- The decorator REPLACES `forward` with a dispatcher that reads
  `self.config._experts_implementation` and looks the impl up in `ExpertsInterface`
  (`integrations/moe.py:570-573`). If the config value is `eager`/None it falls back
  to the original (the Python loop); otherwise it uses the registered vectorized fn.
- The DEFAULT chosen at load time is **`grouped_mm`**:
  `get_correct_experts_implementation` →
  `"grouped_mm" if requested_experts is None else requested_experts`
  (`modeling_utils.py:2048-2049`), gated by `_grouped_mm_can_dispatch()` which only
  checks the decorator is present (`modeling_utils.py:1849-1858`) and otherwise falls
  back to `eager`.
- `grouped_mm_experts_forward` (`integrations/moe.py:380-481`) is the compile-target:
  `top_k_index.reshape(-1)` → `torch.sort` → `torch.histc` (NOT `bincount` — comment
  `:401` "using histc instead of bincount to avoid cuda graph issues") → `cumsum`
  offsets → `_grouped_mm` → masked `view().sum()`. NO Python loop over experts, NO
  `.nonzero()`, NO `torch.unique` (comment `:429`: "torch.unique … breaks the graph
  capture (data-dependent)"). It is explicitly authored to be graph-capturable.

The Python-loop forward (`modeling_diffusion_gemma.py:542-566`) — the one with
`expert_hit = …nonzero()` (`:552`), `for expert_idx in expert_hit` (`:554`),
`if expert_idx == self.num_experts: continue` (`:556`) — is data-dependent control
flow that WOULD break `fullgraph=True`. But it is the `eager` path, reachable ONLY by
explicitly passing `experts_implementation="eager"`. The current worker does NOT pass
it, so the model loads `grouped_mm` by default.

### 2a. A100 dispatch + dtype

- A100 is compute capability SM80. `_can_use_grouped_mm` on CUDA returns
  `get_device_capability >= (8,0)` when `torch._grouped_mm` exists and torch≥2.9
  (`moe.py:297-304`). The worker's torch is 2.9.x → SM80 qualifies for
  `torch._grouped_mm`.
- The one dynamo caveat: `if is_torchdynamo_compiling() and weight.dtype != torch.bfloat16: return False` (`moe.py:280`). The model is BF16 (`dtype="auto"`
  → bf16 on A100), so it passes. If it were ever loaded fp16/fp32, grouped_mm would
  fall through to the registered custom-op fallback (`moe.py:185-208, 251-263`) which
  is STILL compile-safe (has `register_fake`). So `fullgraph=True` holds in every
  branch — the worst case is "uses the eager-ish fallback inside an opaque custom op,"
  not "graph break."

**Conclusion (2):** the MoE is the OPPOSITE of the torchao casualty. The fused 3D
expert layout is precisely what `grouped_mm`/`batched_mm` consume natively. The
compile-hostile loop exists but is the non-default `eager` fallback. Verified
unproven-on-GPU only in the sense of §5 (nobody has RUN compile here yet), but the
source shows no structural break.

> Web cross-check on MoE + compile generally: HF and PyTorch threads confirm grouped-
> /batched-GEMM MoE is the standard compile-friendly path and that the per-expert
> Python loop is the known graph-breaker — consistent with the source above.

## 3. Static cache feasibility for the diffusion KV path

The KV design noted the encoder is causal and APPENDS KV per block. Does that work
with `StaticCache` (which only supports fixed pre-allocated length), or only
`DynamicCache`?

- `generate()` explicitly supports `StaticCache`: `_prepare_static_cache` allocates a
  `StaticCache` sized to `max_length - canvas_length` ("the last generated canvas
  won't be cached", `:664, 1127-1160`) and REUSES/`reset()`s it across calls unless a
  bigger one is needed (`:1144-1159`).
- The per-block encoder append writes into the pre-allocated static buffer at
  `valid_cache_length` (`:1115-1117`), and the decoder attention mask is the
  pre-sized `max_cache_len + canvas_length` buffer (`:698-704`). So the
  incremental-append pattern is handled WITHIN a fixed allocation — the static cache
  is first-class here, not a bolt-on.
- `max_new_tokens`/`max_denoising_steps` bound the allocation; `max_new_canvases =
  ceil(max_new_tokens / canvas_length)` (`:638`).

Caveat (recompile trigger): if a LATER `generate()` needs a LARGER cache than the
captured one, `_prepare_static_cache` builds a NEW `StaticCache` (`:1144-1157`), which
will force a re-capture of the reduce-overhead CUDA graphs. For the buzzsaw's
variable-length / variable-`max_new_tokens` calls this means **recompiles unless you
pin a single `max_length`**. Pin it.

**Conclusion (3):** StaticCache is fully supported by the diffusion path; the append-
per-block is engineered into the fixed allocation. The cost is recompile-on-grow —
mitigated by a fixed `max_length`.

## 4. Web: real torch.compile speedups + the caveats

- **Speedup band:** HF's own torch.compile/static-cache docs state "up to 4×," with
  smaller wins on larger models; reported real numbers ~3.8× (Gemma-2B), ~2.5×
  (Llama-2-7B), ~3× (Llama-2-13B). A **26B-A4B** MoE (4B active) is bigger than these,
  so expect the LOWER end of the band — call it ~2–3× as the realistic planning
  number, not 4×.
- **Warmup cost:** first-call compile under `mode="reduce-overhead"` is the expensive
  part — typically tens of seconds to (with many shapes/entrypoints) "nearly minutes"
  of tracing + inductor + CUDA-graph warmup iterations. Reduce-overhead runs extra
  warm-up iterations to capture the graph. This is a ONE-TIME-per-shape cost amortized
  over many calls — bad for a single short interactive call, good for a warm serving
  loop.
- **Recompile caveat:** torch.compile recompiles on shape change; the canonical static-
  cache pattern exists specifically because the growing dynamic KV defeats compile.
  Pin shapes (fixed `max_length`, fixed canvas_length — the latter is already fixed by
  config) to avoid repeated recaptures.
- **Static-cache-without-speedup trap:** the known issue #30055 ("no speed-up with
  StaticCache + compile") was a case where compile didn't actually engage — which is
  exactly why §5's `verify_fresh` + warm/steady-state measurement matters: confirm
  `is_compiling` actually fired, don't assume.

## 5. The minimal worker change to TEST (diff in the doc — no deploy here)

Add a `compile` boolean to `_load` so the SAME warm worker can A/B eager vs compiled.
Critically: a compiled model and a Python-stop model are different load configs —
keep them in separate `_CACHE` slots. (Schematic; current `_load` is
`tmp/flash-diffgemma/gpu_worker.py:41-62`.)

```python
# _load(tok, *, compile=False)  — new kwarg, separate cache slot
key = "model_compiled" if compile else "model"
if key not in _CACHE:
    _CACHE[key] = DiffusionGemmaForBlockDiffusion.from_pretrained(
        MID, dtype="auto", device_map="auto", token=tok,
        attn_implementation="sdpa",        # sdpa compiles cleaner/faster than eager
        # experts_implementation defaults to "grouped_mm" — do NOT pass "eager"
    )
    # (optional belt-and-suspenders) confirm the compile-friendly expert path:
    #   assert _CACHE[key].config.text_config._experts_implementation == "grouped_mm"

# generate-time: the SWITCH is the cache, nothing else.
gen_kwargs = {**inp, "max_new_tokens": n}
if compile:
    gen_kwargs["cache_implementation"] = "static"   # → is_compiling=True (:692) → _compile_functions
    gen_kwargs["max_length"] = FIXED_MAX            # PIN to avoid recompile-on-grow (:1144)
# else: omit cache_implementation → DynamicCache → eager (today's path)
out = model.generate(**gen_kwargs)
```

Notes baked into the change:

- **Warmup must be excluded from the number.** First `generate(cache_implementation=
  "static")` pays the full compile + CUDA-graph capture. Run it ONCE (discard
  `gen_s`), then measure the SECOND identical-shape call. Report BOTH:
  `compile_warmup_s` (call 1) and steady `tok_per_s` (call 2).
- **Do NOT combine with `step_stopping`/eval-renoise modes.** Those need a Python
  stop → keep them on DynamicCache (the worker already does, and comments why,
  `diffgemma_common.py:301-303`). `compile` is for the plain-generate serving path
  only.
- **`worker_sha` + `verify_fresh.py` before trusting any number** (per the track's
  deploy-stability rule) — and additionally assert compile actually engaged by logging
  whether the static cache was used (e.g. `type(out.past_key_values).__name__ ==
  "StaticCache"`), to dodge the #30055 "compile silently didn't fire" trap.

## 6. Measure-on-deploy plan (the owner drives the GPU)

Single warm worker, A100, fixed prompt, `max_new_tokens=256`, `max_length` pinned:

1. **Baseline (eager, today):** `generate` with default cache → record `gen_s`,
   `tok_per_s` (expect ~137 from first light, confirm).
2. **Compile warmup:** first `generate(cache_implementation="static", max_length=FIXED)`
   → record `compile_warmup_s` (wall time of call 1). Expect tens of seconds → ~minutes.
3. **Compile steady-state:** SECOND identical call → record `tok_per_s`. The decision
   number is steady `tok_per_s` vs baseline. Win threshold: ≥1.8× (below that, the
   warmup + brittleness isn't worth it on the A100).
4. **Recompile watch:** then call with a DIFFERENT prompt length / different
   `max_new_tokens` and watch for a second multi-second stall (a recapture). If every
   distinct shape recompiles, the interactive buzzsaw pattern (varied lengths) loses
   most of the win — quantify how many distinct shapes the real workload has.
5. **Correctness:** assert the compiled output text is sane (compile must not change
   results) and `tokens_per_forward` is unchanged.

Scorecard row: `scenario × git-sha → {compile_warmup_s, eager_tok_per_s,
compiled_tok_per_s, speedup, recompiles_on_shape_change}`.

## 7. The riskiest assumption (test FIRST — the torchao-VRAM analog)

**The single number that decides it: steady-state `compiled_tok_per_s` on the second
identical call, paired with `compile_warmup_s`.** Everything in §1–§4 says the path
COMPILES (no structural break, MoE default is vectorized, static cache supported). The
open risk is not "does it break" but "is the win worth the warmup + recompile
brittleness on Ampere." Concretely, the assumption most likely to be wrong:

> "The first-call warmup is a one-time cost amortized across a warm serving loop, and
> steady-state is ~2–3× eager."

If instead the workload sends many DISTINCT prompt/length shapes (the live-context
buzzsaw does), each new shape re-captures the reduce-overhead CUDA graphs, and the
amortization never happens — you pay warmup repeatedly for a per-call win that doesn't
land. Test step 4 (recompile-on-shape-change) is therefore as decisive as step 3.

## 8. Bottom line for the roadmap

- The roadmap's §5b claim is **directionally CORRECT and now source-grounded**: static
  cache + compile is a real, author-built lever, the MoE does not break it, and the
  built-in stop survives while a custom Python stop forfeits it. This is NOT a
  torchao-style false "transparent" claim.
- BUT the "~450 tok/s measured" figure is **not backed by any deployed worker** — the
  live worker is eager-only. The honest status is: **compile is the most promising
  non-KV A100 lever and is structurally sound, but UNMEASURED here.** It needs the
  §5 change + the §6 plan to become a number.
- If §6 step 3 lands ≥1.8× AND step 4 shows few recompiles → compile is the A100 speed
  answer for the SERVING path (eval-renoise stays eager). If step 4 shows
  recompile-per-shape thrash → compile only helps fixed-shape batch serving, and
  KV-cache reuse remains the only broadly-applicable A100 lever — itself a critical
  finding worth recording.

## #8 follow-up — the static-cache-init errors, root-caused (no GPU; source-cited)

The §5 diff was deployed and FAILED with two errors on the live A100
(transformers 5.11.0), exactly as the §4 #30055 caution warned ("confirm compile
actually engaged, don't assume"):

- **WARMUP (call 1):** `RuntimeError: upper bound and lower bound inconsistent with
  step sign` (a `torch.arange(start, end, step)` with a step whose sign disagrees
  with `end - start`).
- **STEADY (call 2+):** `AttributeError: 'StaticSlidingWindowLayer' object has no
  attribute 'max_batch_size'`.

The two are NOT independent — error 2 is a CASCADE of error 1. Root cause below.

### The trigger: the §5 diff passed `max_new_tokens` AND `max_length` together — a self-inconsistent combo on THIS model

The deployed call was `generate(**inp, cache_implementation="static",
max_length=512, max_new_tokens=128)`. DiffusionGemma does NOT use the standard
`generate()` length precedence. Its own `_prepare_generated_length`
(`generation_diffusion_gemma.py:879-886`) is:

```python
if generation_config.max_length and generation_config.max_new_tokens == 256:   # :880
    max_length = generation_config.max_length
    max_new_tokens = max_length - cur_len
else:
    max_new_tokens = generation_config.max_new_tokens
    max_length    = max_new_tokens + cur_len     # :885
```

`max_length` is honored **only when `max_new_tokens` is left at its default 256**
(the literal `== 256` gate at `:880`). We passed `max_new_tokens=128` (≠ 256) → the
`else` branch fires → **`max_length=512` is silently DROPPED** and `max_length`
becomes `128 + cur_len`. The "PIN max_length to avoid recompile-on-grow" advice in
§5 is therefore INERT the way the diff used it — and worse, it set up error 1.

### Error 1 (warmup RuntimeError) — the static cache is sized `max_length - canvas_length`, which the dropped `max_length` drives too small

`config.canvas_length = 256` (`configuration_diffusion_gemma.py:196`) and
`config.sliding_window = 512` (`:102`) — every layer is a `StaticSlidingWindowLayer`.
The static cache is allocated to **`max_length - canvas_length`** ("the last
generated canvas won't be cached", `generation_diffusion_gemma.py:664`), i.e.
`(128 + cur_len) - 256 = cur_len - 128`.

Because `max_new_tokens (128) < canvas_length (256)`, this collapses below
`canvas_length` and goes **negative for any prompt shorter than 128 tokens** (the
smoke prompt is short). `StaticCache` then builds `StaticSlidingWindowLayer(
max_cache_len = cur_len-128 < 0, sliding_window=512)` →
`effective_max_cache_len = min(512, negative) = negative` (`cache_utils.py:478-480`).
A negative/degenerate `max_cache_len` flows into the compiled sliding-window
forward's index math and surfaces as the `torch.arange` step-sign `RuntimeError`
during the reduce-overhead capture. (The exact internal `arange` site is inside the
compiled region and is NOT pinned to a single source line from static reading — see
"honesty" below — but the bad bound is unambiguously the `max_length - canvas_length`
math at `:664` fed by the dropped `max_length`.) The EAGER (DynamicCache) path never
hits this: `DynamicLayer` grows on demand and never pre-allocates a `max`, which is
why today's eager worker runs and the static path does not.

### Error 2 (steady AttributeError) — a pure cascade from error 1

`max_batch_size` is **NOT** a constructor arg of any static layer. `StaticLayer`/
`StaticSlidingWindowLayer` set `self.max_batch_size` ONLY inside
`lazy_initialization` (`cache_utils.py:387`), which runs on the FIRST `update()` —
i.e. the first decoder forward pass. The `Cache.max_batch_size` **property**
(`cache_utils.py:1352-1358`) reads `layer.max_batch_size` for every layer, with NO
`is_initialized` guard.

`_prepare_static_cache` assigns `self._cache = StaticCache(...)` (`:1157`) BEFORE the
forward runs, and on the NEXT call probes the cached instance to decide reuse:
`cache_to_check.max_batch_size != batch_size` (`:1147`). Sequence:

1. Call 1 builds `self._cache` (layers un-initialized, no `max_batch_size`), then
   dies in the warmup `arange` (error 1) — **before any `update()`**.
2. Call 2 finds the stale, never-initialized `self._cache`, hits the reuse probe at
   `:1147` → the property iterates `layer.max_batch_size` → `AttributeError`.

So error 2 cannot occur unless call 1 aborted pre-forward. Fix error 1 (let call 1
complete one forward) and `lazy_initialization` sets `max_batch_size`, the reuse
probe succeeds, and error 2 vanishes. Note this is a LATENT fragility in transformers
itself, not diffusion-specific: upstream `generation/utils.py:1795` has the identical
unguarded `cache_to_check.max_batch_size` probe — any failed static-cache warmup
poisons subsequent calls until `model._cache` is cleared.

### VERDICT — LIKELY a simple input-args fix; confirm with ONE cheap re-run

This is **not** a fundamental sliding-window-static incompatibility. The model is
purpose-built for `StaticCache` over sliding-window layers (`StaticSlidingWindowLayer`
exists precisely for compile; §1/§3 above). Both errors trace to ONE mistake: the §5
diff passed `max_new_tokens=128` alongside `max_length=512`, which (a) drops
`max_length` via the `==256` gate and (b) drives the cache size negative because
`max_new_tokens < canvas_length`. Remove that inconsistency and the structural path
is clean.

**Caveat to the "simple" verdict (be honest):** I could not pin the exact internal
`arange` that emits the step-sign error to a source line from static reading alone (it
is inside the compiled sliding-window forward), and a *longer* prompt (`cur_len > 128`)
would make `max_length - canvas_length` positive yet was still reported failing — so
there is residual risk that the warmup `arange` has a second contributor in the
reduce-overhead capture, independent of cache sizing. The corrected call below is
cheap to try and is the decisive test: if it runs, it was the args trap (simple); if
it STILL throws the step-sign `RuntimeError` with a clean positive cache, the compiled
sliding-window-static path has a deeper 5.11.0 issue that becomes an upstream/owner
call, and KV-cache reuse stays the only broadly-applicable A100 lever.

### The EXACT corrected worker call to test (minimal diff vs §5)

The switch is `cache_implementation="static"` ALONE. To pin a fixed, prompt-INDEPENDENT
cache, pass **only** a fixed `max_length` and **DO NOT pass `max_new_tokens`** (leave
it at the config default 256 so the `:880` gate honors `max_length`). With
`max_length=512`: cache = `512 - 256 = 256`, fixed regardless of prompt length → no
recompile-on-prompt-length, and `max_new_tokens` derives to `512 - cur_len`.

```python
# WRONG (the §5 diff as deployed): max_new_tokens=128 trips the :880 gate AND
# (128 < canvas_length 256) drives cache = (128+cur_len)-256 negative for short prompts.
# out = model.generate(**inp, cache_implementation="static", max_length=512, max_new_tokens=128)

# RIGHT: pass ONLY a fixed max_length (> canvas_length, and > any prompt you'll send).
#   - leave max_new_tokens UNSET → stays default 256 → :880 honors max_length
#   - cache = max_length - canvas_length = 512 - 256 = 256, prompt-INDEPENDENT (no recompile)
FIXED_MAX = 512   # must satisfy: FIXED_MAX > canvas_length(256) AND FIXED_MAX > max prompt cur_len
out = model.generate(**inp, cache_implementation="static", max_length=FIXED_MAX)

# Belt-and-suspenders so a FAILED warmup can't poison the next call (error-2 cascade):
# clear the stale cache on any generate exception before retrying.
#   try:    out = model.generate(**inp, cache_implementation="static", max_length=FIXED_MAX)
#   except Exception:
#       if hasattr(model, "_cache"): del model._cache   # drop un-initialized StaticCache
#       raise
```

Constraints baked in (all source-cited above):

- **`FIXED_MAX > canvas_length` (256)** or the cache is ≤ 0 → error 1 returns. With a
  256-token canvas, `max_length=512` gives a 256-slot cache (one canvas of history);
  raise it (768/1024 …) if more KV history is wanted — keep it a fixed constant so the
  compiled graph is captured once.
- **`FIXED_MAX > max prompt `cur_len`** so `max_new_tokens = max_length - cur_len > 0`
  (validated at `:182`). If prompts can exceed 256 tokens, raise `FIXED_MAX`
  accordingly (and re-capture once).
- **Do NOT pass `max_new_tokens` on the compiled path.** Any value ≠ 256 both drops
  `max_length` AND re-couples the cache size to `cur_len` (`max_new_tokens + cur_len -
  canvas_length`) → recompile-per-prompt-length, defeating the whole point.
- Then run §6 as written: discard call 1 (warmup), measure call 2 steady `tok_per_s`,
  and assert `type(out.past_key_values).__name__` is the static cache to prove compile
  engaged.

## #9 — the `find_spec` graph-break, root-caused

> The §8 static-cache fix (`1a475ce9`) cleared the FIRST blocker; running the
> corrected compiled call then surfaced a SECOND, deeper one under `fullgraph=True`:
> `Unsupported: Attempted to call function marked as skipped: find_spec in <frozen
> importlib.util>`. This section pins the exact call site from source and gives the
> honest cheap-vs-deep verdict. No GPU; every claim is `file:LINE` in vendored
> `transformers@5.11.0` (the deployed pin) + cited web.

### (1) The exact `find_spec` call site — it is the grouped_mm version-check, torch-2.9-only

The deployed worker pins **`torch==2.9.0` + `transformers==5.11.0`**
(`gpu_worker.py:193`, `flash-worker/Dockerfile:24,30`). DiffusionGemma "always
assumes the MoE code path" ([HF DiffusionGemma docs](https://huggingface.co/docs/transformers/model_doc/diffusion_gemma)),
its `DiffusionGemmaTextExperts` carries `@use_experts_implementation`
(`modeling_diffusion_gemma.py:529`), and the default backend is `grouped_mm`
(`modeling_utils.py:2049`). The compiled decoder forward (`self.forward`, wrapped
`torch.compile(..., fullgraph=True)` at `generation_diffusion_gemma.py:1245`) therefore
runs `grouped_mm_experts_forward → _grouped_linear → _grouped_mm → _can_use_grouped_mm`
(`integrations/moe.py:380→347→311→266`).

Inside `_can_use_grouped_mm`, on **A100 (SM80) + bf16 + torch 2.9.0** the dispatch
walks to exactly ONE version check:

```python
# integrations/moe.py:296-304
if weight.device.type == "cuda":
    if hasattr(torch.nn.functional, "grouped_mm"):   # :297 — FALSE on torch 2.9.0 (it lands in 2.10)
        return torch.cuda.get_device_capability(weight.device) >= (8, 0)   # :298 — early return, NOT taken on 2.9
    if hasattr(torch, "_grouped_mm"):                 # :300 — TRUE on torch 2.9.0
        if is_torch_greater_or_equal("2.9", accept_dev=True):   # :301 ← the find_spec fires HERE
```

`is_torch_greater_or_equal` → `get_torch_version` → `_is_package_available("torch", return_version=True)`
→ `spec = importlib.util.find_spec(pkg_name)` (`utils/import_utils.py:166→162→50→52`).
`find_spec` lives in `<frozen importlib.util>`, which Dynamo carries in its skip list
and refuses to trace — hence "marked as skipped" ([pytorch#155426](https://github.com/pytorch/pytorch/issues/155426),
[Dynamo core concepts](https://docs.pytorch.org/docs/main/user_guide/torch_compiler/compile/programming_model.dynamo_core_concepts.html)).

The two CPU-only checks (`is_torch_less_or_equal("2.10.0", …)` at `:283`) are NOT
reached on CUDA — `weight.device.type == "cpu"` short-circuits the `and` chain first.
So `:301` is the sole reachable site, and it is reachable **only because torch is 2.9.x**:
in torch ≥2.10 `torch.nn.functional.grouped_mm` exists, so `:297` is True and the
function returns at `:298` BEFORE any version check — the find_spec call site is never
entered. The HF compatibility table confirms grouped_mm is `fullgraph=True`-clean on a
supported torch ([Experts backends](https://huggingface.co/docs/transformers/en/experts_interface)).

**Why the existing patch doesn't save it:** `moe.py:37-38` wraps both helpers in
`torch._dynamo.assume_constant_result` precisely to make Dynamo evaluate-once-at-trace
and inline the bool (comment at `:34-36`). That patch is present and byte-identical in
5.11.0 and 5.12.1 — yet the break is observed, so on this **torch 2.9.0** Dynamo the
wrapper is not suppressing the trace of the kwarg call `is_torch_greater_or_equal("2.9",
accept_dev=True)`. (The maintainers' CI runs newer torch, where `:301` is never reached,
so the wrapper's torch-2.9 behavior is effectively untested by them.)

### (2) VERDICT — NOT a cheap pre-import; it's a torch-2.9 artifact gating a genuinely-deep wall

**The "eager-import once before compile" idea does NOT work — answered decisively.**
The break is a **trace-time** event (graph capture), not a runtime call. `is_torch_*`
is already `@lru_cache`, and prefill (uncompiled) calls `_can_use_grouped_mm` first, so
the runtime value is *already cached* before the compiled decode forward runs — and it
makes no difference, because Dynamo **re-traces the function body at capture** regardless
of Python-level memoization. There is nothing to pre-trigger: the find_spec is never the
runtime bottleneck, it is a symbol Dynamo refuses to trace. So this is the
**per-(re)compile / structural** case, not the cacheable one.

The decisive, infra-only dodge is a **torch bump, not a warmup**:

- **Bump `torch==2.9.0` → `torch==2.10.x`** in `flash-worker/Dockerfile:24` (+ matching
  `torchvision`/`torchaudio`), rebuild the image, redeploy. On torch ≥2.10
  `torch.nn.functional.grouped_mm` exists → `_can_use_grouped_mm` returns at `moe.py:298`
  and `:301`'s find_spec is structurally unreachable in the compiled forward. (Residual
  risk: `:298` then evaluates `torch.cuda.get_device_capability(...) >= (8,0)` under
  Dynamo — normally constant-folded, but verify on redeploy.)

**But clearing find_spec only exposes the real, deep blocker — and it is owner/upstream.**
The HF docs state it flatly: *"the `grouped_mm` experts backend … is not compatible with
CUDA graphs, so you must use `mode=None` or `mode="max-autotune-no-cudagraphs"` when
compiling"* ([Experts backends](https://huggingface.co/docs/transformers/en/experts_interface)).
DiffusionGemma's `_compile_functions` **hardwires `mode="reduce-overhead"` (CUDA graphs)**
for every compiled region (`generation_diffusion_gemma.py:1241,1245,1249,1253,1260`), with
no knob to change it. So the model's own author-built compiled path is, on this stack,
mode-incompatible with its own default MoE backend: bumping torch turns the find_spec break
into a CUDA-graphs break next. The only clean escape is the **transformers 5.12.0
decode-stage auto-switch** — *"when using `experts_implementation="grouped_mm"` on GPU, the
model automatically switches to `"batched_mm"` during the decode stage"* (same doc), and
`batched_mm` IS compatible with all modes incl. CUDA graphs (compat table). The compiled
region here IS the decode forward, so a **torch≥2.10 + transformers≥5.12.0** pair *might*
land the compiled path (decode runs batched_mm → CUDA-graph-clean → reduce-overhead holds).
That is unverified for DiffusionGemma specifically (5.11.0's diffusion_gemma has no such
switch; 5.12.x's wiring not confirmed here) and is a model/library question, not a worker
one.

Canonical non-bump workarounds were checked and rejected: `@torch._dynamo.dont_skip_tracing`
forces Dynamo INTO frozen `importlib` → strictly more breaks, not fewer
([pytorch#155426](https://github.com/pytorch/pytorch/issues/155426)); `torch._dynamo.config.suppress_errors=True`
converts the break into an **eager fallback** (no speedup) and in any case fights the
library-hardwired `fullgraph=True`. Monkeypatching the two helpers to plain lambdas before
compile would work but is brittle/answer-shaped and still hits the CUDA-graphs wall.

**Bottom line:** the find_spec break is a *shallow, torch-2.9-pin artifact* (a one-line
Dockerfile bump removes it), but it sits on top of a *deep* incompatibility between
DiffusionGemma's hardwired `reduce-overhead` compile and the grouped_mm MoE backend.
Net for the A100: the compiled ~1000 tok/s path is **not** unblockable by a cheap worker
change alone — it needs a torch+transformers stack bump AND validation that the 5.12.0
decode→batched_mm switch covers the compiled region; absent that it is an owner/upstream
call. This re-confirms §8: **KV-cache reuse remains the only broadly-applicable A100 speed
lever.**

### (3) Redeploy + re-test flag for the owner/loop

Cheap to TRY, decisive either way — worth one redeploy:

1. Edit `flash-worker/Dockerfile:24,30`: `torch==2.10.x torchvision==… torchaudio==2.10.x`
   and `transformers==5.12.1` (5.12.x for the decode→batched_mm switch). Rebuild + redeploy.
2. Re-run the §-corrected compiled call (`compile=true`, `max_length=512`, no
   `max_new_tokens`).
3. Falsify-don't-confirm: capture `TORCH_LOGS="graph_breaks,recompiles"`. Expected outcomes —
   (a) clean compile + a real `tok_per_s` jump → the torch pin was the whole story; (b) the
   find_spec is gone but a **CUDA-graphs / `reduce-overhead`** error replaces it → it's the
   deep wall → stop, it's upstream, KV-cache is the lever. Do NOT chase (b) with
   `suppress_errors` (that just silently de-compiles).

## #15 — does transformers 5.12 batched_mm rescue the compiled path?

> #9 left a live hope: *"the only clean escape is the transformers 5.12.0 decode-stage
> auto-switch (grouped_mm → batched_mm), unverified for DiffusionGemma."* The owner asked
> for a go/no-go on a `torch 2.10 + transformers 5.12` redeploy built on that hope. This
> section RESOLVES it from source. Grounds: vendored `reference-code/transformers` is at
> **v5.11.0** (`git describe` → `v5.11.0`; `__init__.py:__version__ = "5.11.0"`) — the SAME
> pin the worker bakes — cross-checked against transformers `main` (raw GitHub) for 5.12.x.
> No GPU.

### VERDICT — the 5.12 auto-switch is CONFIRMED DEAD for DiffusionGemma; redeploy-for-the-switch is a NO-GO. But a cheaper lever the #9 dig MISSED is live: force `batched_mm` on the EXISTING worker (no torch/transformers bump).

Three independent nails kill the auto-switch hope, then one finding reopens the path more cheaply than #9's torch-bump.

#### Q1 — the auto-switch is NOT a num_tokens branch in `moe.py`; it's a whole-decode context-manager swap, and it's already in 5.11.0

There is **no decode-stage / `num_tokens==1` branch inside `moe.py`** — not in v5.11.0, not on `main`. `moe.py` dispatch is a pure config-string lookup in BOTH: `experts_forward = experts_interface.get_interface(self.config._experts_implementation, original_forward)` (`integrations/moe.py:572`), resolving a FIXED per-model string (`grouped_mm` / `batched_mm`), set once at load (`modeling_utils.py:2048-2049`, default `grouped_mm`). Fetching `main`'s `moe.py` confirms the same — no `cache_position` / `num_tokens` / `is_decoding` condition selecting between the two `*_experts_forward` fns.

The actual switch is a **context manager that swaps the config wholesale for the entire decode phase**, in the generation layer, not the MoE layer:

```python
# generation/utils.py:2098-2114  (PRESENT IN v5.11.0 — not new in 5.12)
@contextmanager
def _optimize_model_for_decode(self):
    original_experts_implementation = self.config._experts_implementation
    if original_experts_implementation == "grouped_mm" and self.device.type != "cpu":
        ...  # "switching to 'batched_mm' for the decoding stage ... more performant ... on smaller inputs"
        self.set_experts_implementation("batched_mm")
    try:
        yield
    finally:
        if original_experts_implementation == "grouped_mm" and self.device.type != "cpu":
            self.set_experts_implementation(original_experts_implementation)
```

So the trigger is **not token count at all** — it is "are we inside the standard generate() decode loop." That already settles the #9 framing: bumping transformers 5.11→5.12 changes **nothing** about this mechanism, because the mechanism is byte-present in 5.11.0 (the deployed pin) already.

#### Q2 — it does NOT fire for DiffusionGemma's forward, because DiffusionGemma's custom `generate` never enters that context manager (THE CRUX)

`_optimize_model_for_decode` is invoked from exactly two sites — both the *standard* autoregressive decode loop — and **neither is DiffusionGemma**:

```
$ grep -rn "_optimize_model_for_decode" src/transformers/
generation/utils.py:2099                          # def (the context manager)
generation/utils.py:2795                          # standard GenerationMixin._sample decode loop
models/higgs_audio_v2/generation_higgs_audio_v2.py:312
```

DiffusionGemma ships a **full override** generate — `DiffusionGemmaGenerationMixin.generate` (`generation_diffusion_gemma.py:543`), explicitly self-described as replacing GenerationMixin logic ("Overriding GenerationMixin-related functions that are not relevant to DiffusionGemma", `:231-232`; "refactor GenerationMixin and this to reuse logic without requiring inheritance", `:857,878`). Its denoise loop is **never wrapped** in `_optimize_model_for_decode`. Therefore the compiled decoder forward — `self._compiled_decoder_forward = torch.compile(self.forward, mode="reduce-overhead", fullgraph=True)` (`generation_diffusion_gemma.py:1245`; `cudagraph_mark_step_begin()` at `:1019` confirms CUDA graphs are live) — runs with `_experts_implementation` still `= "grouped_mm"` for the WHOLE block-diffusion run. And the HF compat table is explicit: `grouped_mm` is *"not compatible with CUDA graphs … use `mode=None` or `mode="max-autotune-no-cudagraphs"`"* ([Experts backends](https://huggingface.co/docs/transformers/main/en/experts_interface)), while DiffusionGemma hardwires `reduce-overhead` (CUDA graphs) with no knob (`generation_diffusion_gemma.py:1241,1245,1249,1253,1260`). The auto-switch that would have dodged this is simply not on DiffusionGemma's code path. (It looks like an upstream gap — DiffusionGemma's custom generate forgot the wrap — but as-shipped it does not fire, and that is what matters.)

#### Q3 — 5.12 alone does NOT clear the find_spec; and clearing it (torch 2.10) only re-exposes the CUDA-graphs wall

The `find_spec` at `moe.py:301` is reachable **only on torch < 2.10** (on ≥2.10 `torch.nn.functional.grouped_mm` exists → `_can_use_grouped_mm` returns at `:298` before the version check — #9 §1). transformers 5.12 does **not** change this: `moe.py`'s `_can_use_grouped_mm` logic and the `assume_constant_result` wrapper (`moe.py:37-38`, already in 5.11.0) are the same on `main`. So **5.12 on torch 2.9 still breaks at find_spec**; only a **torch 2.10** bump removes it — and per Q2 that bump just turns the find_spec break into the grouped_mm-⊥-CUDA-graphs break, with no batched_mm rescue for DiffusionGemma. Net: `torch 2.10 + transformers 5.12` for DiffusionGemma's *grouped_mm* path = **no win**. NO-GO on that redeploy.

#### The lever #9 missed — force `batched_mm` explicitly, on the EXISTING 5.11.0 / torch-2.9 worker

The auto-switch is unreachable, but the BACKEND it selects is a first-class, documented load kwarg — and selecting it by hand sidesteps BOTH blockers at once, with zero stack bump:

- **Clears find_spec (no torch bump):** `batched_mm_experts_forward` (`moe.py:118-179`) is pure `repeat_interleave` → gather → `torch.bmm` (`_batched_linear`). It **never calls `_grouped_mm` / `_can_use_grouped_mm`**, so the `find_spec` version-check at `:301` is never on the traced graph. (verified: `grep` of `moe.py:118-179` for `_grouped`/`_can_use`/`find_spec` → none.)
- **Clears the CUDA-graphs wall (no transformers bump):** the compat table rates `batched_mm` as **all modes, `fullgraph=True` Yes** — explicitly CUDA-graph / `reduce-overhead` clean ([Experts backends](https://huggingface.co/docs/transformers/main/en/experts_interface)). It is exactly the backend the auto-switch would have chosen — not a brittle monkeypatch, the legitimate API (`set_experts_implementation` / `experts_implementation=`).

This is a **one-line worker change on the current stack** (transformers 5.11.0, torch 2.9.0 — no image rebuild for the deps, no `Dockerfile` edit). It strictly dominates #9's `torch 2.10 + 5.12` redeploy as the next probe.

**The one thing only a GPU run resolves (→ STILL UNCERTAIN, but a $0-rebuild probe):** `batched_mm` **duplicates** the selected expert params per token and "has no offset to skip" the wasted GEMM (`moe.py:130,136`; doc: *"Uses more memory due to parameter duplication … Fastest for small inputs"*). DiffusionGemma's forward processes the **whole canvas** each pass (S = `canvas_length × num_top_k` — large), precisely the regime where `grouped_mm` wins on raw GEMM and `batched_mm` balloons memory. So whether **CUDA-graph-captured `batched_mm` net-beats eager `grouped_mm`** (or OOMs the A100-80) for the canvas shape is genuinely GPU-only — but it costs one drive, not a torch/transformers/Docker redeploy.

#### The exact change + re-test (supersedes #9 §(3) as the next probe)

1. `tmp/flash-diffgemma/gpu_worker.py:55-57` and the eager fallback `:60-62` — add `experts_implementation="batched_mm"` to BOTH `DiffusionGemmaForBlockDiffusion.from_pretrained(...)` calls:

   ```python
   _CACHE["model"] = DiffusionGemmaForBlockDiffusion.from_pretrained(
       MID, dtype="auto", device_map="auto", token=tok,
       attn_implementation="sdpa", experts_implementation="batched_mm")
   ```

   No `Dockerfile` / torch / transformers edit. Bump `FLASH_GPU_IMAGE` tag so the warm worker is recreated (per CLAUDE.md "Deployment stability"); `verify_fresh.py` → `FRESH ✓` first.
2. Re-run the §8-corrected compiled call (`compile=true`, `max_length=512`, no `max_new_tokens`), `TORCH_LOGS="graph_breaks,recompiles"`. Outcomes —
   (a) clean compile + real `tok_per_s` jump → forcing `batched_mm` was the whole story (compiled path LANDS on the existing stack); (b) compiles clean but `tok_per_s` ≤ eager grouped_mm, or OOM → `batched_mm`'s param-duplication loses on the large canvas → compiled path is a dead end for THIS model shape, KV-cache reuse stays the only A100 lever (#8). Do NOT fall back to torch 2.10 + 5.12 — Q1–Q3 prove it adds nothing for DiffusionGemma.

**Bottom line for the owner:** the `torch 2.10 + transformers 5.12` redeploy is a **NO-GO** — the 5.12 batched_mm auto-switch is real but structurally cannot fire for DiffusionGemma's override-generate, and it isn't even new in 5.12. The compiled path is **not yet confirmed dead**: the cheaper, correct next probe is **force `experts_implementation="batched_mm"` on the current 5.11.0/torch-2.9 worker** and measure once. That single $0-rebuild drive is decisive either way.

#### BUILT (2026-06-29) — the `experts_impl` knob + probe are in place, awaiting the owner's $0 GPU drive

The §15 change is implemented on the existing 5.11.0 / torch-2.9 worker — **no Dockerfile / dep edit**, so it's a code recycle, not an image rebuild:

- **Worker knob** — `tmp/flash-diffgemma/gpu_worker.py` (gitignored): `_load(tok, experts_impl=None)` now passes the recognized `experts_implementation=` from_pretrained kwarg (modeling_utils.py:1589-1590) when the payload sets `experts_impl`. UNSET => the model's `grouped_mm` default (modeling_utils.py:2049) — **zero behavior change** vs the pre-knob worker. The backend is part of the cache key: flipping it **reloads** (evict-first + `cuda.empty_cache()`) because two 50GB copies OOM the A100-80 and a fresh model drops stale compile graphs. The generate result now carries `experts_impl` read from `model.config._experts_implementation` (PROVES which backend ran). The `find_spec` bypass is source-confirmed: `batched_mm_experts_forward` (moe.py:118-179) is pure `repeat_interleave`→`_batched_linear`(`torch.bmm`, moe.py:84-115) and **never** calls `_grouped_mm`/`_can_use_grouped_mm` — so `moe.py:301`'s find_spec is off the traced graph.
- **Probe** — `compile_test.py` (scratchpad, gitignored) extended with a `#15 BATCHED_MM` arm (`compile=True, experts_impl="batched_mm"`, warmup+reload then 2 steady calls) and a `#15 VERDICT` block. It captures: did find_spec disappear (`find_spec_break` on the grouped-compiled control c1 vs the batched arms), the live `experts_impl`, steady tok/s, and OOM. Verdict logic → **OOM** / **NO-WIN** (still find_spec, or errored, or batched ≤ 1.1× eager grouped) / **marginal** (1.1-1.8×) / **WIN** (≥1.8× eager grouped).
- **worker_sha implication:** the worker source changed → `worker_sha` changes (local `c65c68e5cfae`) → `verify_fresh.py` will correctly report STALE on the warm worker until it's recycled. Since worker CODE changed (not just a payload), a plain `flash deploy` keeps serving old code on a warm worker — **force-recycle**.

**Owner runbook (no agent did this — it's the $0 GPU step):**

```bash
cd tmp/flash-diffgemma && set -a; . ./.env; set +a
.venv/bin/flash undeploy diffgemma --force && .venv/bin/flash deploy   # worker CODE changed → force-recycle
python3 verify_fresh.py                                                 # MUST print FRESH ✓ (expects sha c65c68e5cfae)
python3 compile_test.py                                                 # runs eager / compiled-grouped / compiled-batched + emits #15 VERDICT
# one-line single drive (skip the full probe): force batched on the compiled path
python -u client.py '{"mode":"generate","prompt":"Write a Clojure mean over a vector.","compile":true,"max_length":512,"experts_impl":"batched_mm"}'
```
