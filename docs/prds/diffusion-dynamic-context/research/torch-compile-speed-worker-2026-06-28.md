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
