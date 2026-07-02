---
type: research
status: active
tags: [research, agent]
---

# Compile-vs-control ceiling — the find_spec and batched_mm walls, root-caused (2026-07-02)

> No-GPU, source-grounded. Companion to
> [[forward-speedup-levers-2026-06-30]] (the MoE-kernel ceiling) and
> [[transformers-diffusion-source-grounding-2026-06-28]] (the control seams).
> Every claim carries a marker: **GROUNDED** (read in vendored source /
> measured), **ESTIMATED** (reasoned, version-adjacent source), or
> **GPU-ONLY-UNKNOWN** (needs one probe). Sources: vendored
> `reference-code/transformers` @ tag **v5.11.0** (exactly the deployed pin) and
> torch 2.12.1 dynamo source (the local driver venv; the worker runs torch
> 2.9.0 — marked where that gap matters).

## TL;DR

1. **The find_spec story was wrong — 5.11.0 already HAS the
   `assume_constant_result` patch** (it landed pre-v5.10.0; vendored
   `moe.py:34-38` is the deployed code). The break persists because the patch
   is **structurally inert**: `is_torch_greater_or_equal` is `@lru_cache`-wrapped
   (`import_utils.py:165-166`), and Dynamo unwraps lru_cache wrappers to their
   `__wrapped__` inner function BEFORE the constant mark is consulted — the mark
   sits on the wrapper, the traced inner fn doesn't have it, so Dynamo inlines
   the body → `is_torch_available()` → `_is_package_available` → `find_spec` →
   Unsupported. **A transformers version bump does NOT fix this.** The real fix
   is a 2-line worker-side monkeypatch (§2), no image rebuild.
2. **The batched_mm CUDA device-side assert is most plausibly NOT an MoE bug at
   all — it's static-cache under-sizing.** The cache is allocated
   `max_length − canvas_length` (`generation_diffusion_gemma.py:665`), which
   only fits when generation is a single canvas. The battery's exp1 config
   (short prompt, `max_length=512`) forces **2 canvases**; canvas 2's encoder
   append writes `prompt+256 ≈ 280-296` positions into a 256-slot `StaticLayer`
   via **unchecked `index_copy_`** (`cache_utils.py:433-445`) → device assert.
   Discriminating probe: payload-only, $0 rebuild (§3).
3. **The per-step clamp does NOT forfeit compile.** The `logits_processor` call
   (`generation_diffusion_gemma.py:1034`) runs in EAGER Python between the
   compiled units — `_compile_functions` (`:1235-1265`) compiles only
   encoder/decoder-forward/accept/renoise/built-in-stop. External processors
   are applied first (`:1171-1172`). So **compiled generation + ClampLogitsProcessor
   is architecturally compatible** — untested on GPU only because the cache
   wall (item 2) blocked every compiled run.
4. **The "custom stopping forfeits compile → 4× slower" measurement was
   mis-attributed.** `is_compiling` gates purely on a static cache (`:692`);
   the 0.57 s "compiled-at-48" run predates the working static-cache knob, so
   it was almost certainly **eager with built-in early-stop firing after ~4
   forwards**, vs `denoise_to_step` K=24 forcing 24 forwards (2.32 s). The 4×
   was **forward-count, not a compile tax**. Validation-as-early-stop already
   attacks exactly that cost.
5. **Verdict: the ceiling statement is NOT final.** One cheap payload-only probe
   chain (§5) could unlock a compiled control path on the A100. If the
   single-canvas probe still asserts, then eager + exp D over-commit is the
   A100 envelope and compile moves to the Hopper/TPU column for good.

---

## §1 The find_spec wall — corrected root cause

**What we knew:** `fullgraph=True` compile of the decoder dies with
`Unsupported: find_spec in <frozen importlib.util>`, traced to
`_can_use_grouped_mm`'s version check
(`reference-code/transformers/src/transformers/integrations/moe.py:301`).
The prior account ("5.11.0 predates the `assume_constant_result` patch; bump or
backport 5 lines") is **wrong on both counts**:

- **The patch is IN 5.11.0.** The vendored tree is tag `v5.11.0` (the exact
  deployed pin) and carries the wrap at `moe.py:34-38`
  (`is_torch_greater_or_equal = torch._dynamo.assume_constant_result(is_torch_greater_or_equal)`);
  `git tag --contains` shows the commit (`6f90cbb572`, "Better Grouped GEMM +
  EP") shipped in v5.10.0. **GROUNDED.**
- **The patch cannot work as written.** `assume_constant_result` sets
  `fn._dynamo_marked_constant = True` on the object it's given
  (torch `_dynamo/decorators.py:177`) and `UserFunctionVariable.__init__` honors
  it (`_dynamo/variables/functions.py:547-551`). But `is_torch_greater_or_equal`
  is `@lru_cache`-decorated (`import_utils.py:165-166`), so the marked object is
  a `functools._lru_cache_wrapper`. Dynamo's builder special-cases lru_cache
  wrappers FIRST: `is_lru_cache_wrapped_function(value)` →
  `WrapperUserFunctionVariable(value, "__wrapped__")`
  (`_dynamo/variables/builder.py:1413-1420`), which traces the **inner,
  unmarked** function (`_dynamo/variables/functions.py:2396-2429` — no
  constant-mark check anywhere in the wrapper variable). The inlined body calls
  `is_torch_available()` (another lru_cache wrapper, unwrapped the same way) →
  `_is_package_available` → `importlib.util.find_spec`
  (`import_utils.py:50-52`) → Unsupported → hard error under `fullgraph=True`.
  **GROUNDED in torch 2.12.1 source; the worker runs torch 2.9.0 — the same
  lru-unwrap path existing there is ESTIMATED, but it exactly matches the
  measured break, and the mechanism is version-stable (the builder's lru branch
  long predates 2.9).**

**Why only the grouped_mm path hits it:** on the worker's torch 2.9.0,
`torch.nn.functional.grouped_mm` doesn't exist (2.10+) but `torch._grouped_mm`
does, so `_can_use_grouped_mm` reaches the `is_torch_greater_or_equal("2.9",
accept_dev=True)` call at `moe.py:301` inside the compiled region.
`batched_mm_experts_forward` (`moe.py:118-179`) never calls
`_can_use_grouped_mm` — which is why the batched_mm knob clears find_spec
(live-confirmed by the battery: `find_spec_break=false`). **GROUNDED.**

### The actual fix (2 lines, worker-side, no image rebuild)

A transformers bump does NOT help (the bug is the patch's interaction with
`@lru_cache`, present through `main`). Two working options, both in
`gpu_worker.py` before `_load`:

```python
# Option A — replace the module-global with a plain constant fn (worker torch
# is 2.9.0, so the only compiled-region call site `("2.9", accept_dev=True)`
# is always True):
import transformers.integrations.moe as _moe
_moe.is_torch_greater_or_equal = lambda v, accept_dev=False: True

# Option B — re-wrap the UNWRAPPED inner fn so the constant mark lands on a
# plain function Dynamo will honor:
_moe.is_torch_greater_or_equal = torch._dynamo.assume_constant_result(
    _moe.is_torch_greater_or_equal.__wrapped__)
```

Option A is safer (no reliance on dynamo honoring the mark on torch 2.9.0).
Either lets the DEFAULT `grouped_mm` implementation compile — worth having so
the compiled probe isn't confounded by the batched-vs-grouped kernel change.
**ESTIMATED (mechanism grounded; the 2.9.0 run is the probe).** The next wall
after find_spec is §2's cache assert — which hits grouped and batched alike.

## §2 The batched_mm device-assert — ranked hypotheses

Observed (live battery, 2026-06-29): `experts_impl="batched_mm"` +
`compile:true` clears find_spec, then the whole-canvas compiled forward dies
with a CUDA device-side assert.

### H1 (top, GROUNDED arithmetic) — static-cache under-sizing → `index_copy_` OOB

The chain, every link cited:

- The cache is allocated for `max_length − canvas_length`
  (`generation_diffusion_gemma.py:662-665`, "the last generated canvas won't be
  cached").
- `max_new_canvases = ceil(max_new_tokens / 256)` (`:638`); the loop re-encodes
  each finished canvas into the encoder cache before the next one (`:713` loop,
  `:1120` region).
- So the cache must actually hold `prompt + (n_canvases − 1) × 256`. That fits
  `max_length − 256` **only when `max_new_tokens` is an exact multiple of 256**;
  otherwise it's under-sized by `256 − (max_new_tokens mod 256)`.
- The battery's exp1 config is exactly the failing case: P1 = "Write a Clojure
  function mean…" (~25-40 tokens with the chat template) + `max_length=512`
  (`tmp/flash-diffgemma/battery.py:199-210`) → `max_new ≈ 475` → **2 canvases**;
  cache = 512 − 256 = **256 slots**; canvas 2's encoder append needs
  `prompt + 256 ≈ 280-296` slots.
- The write is **unchecked**: `StaticLayer.update` does
  `cache_position = arange(kv_length) + cumulative_length` then
  `self.keys.index_copy_(2, cache_position, …)` (`cache_utils.py:433-445`) —
  `index_copy_` with an out-of-range index is precisely a CUDA device-side
  assert (asynchronous, so the traceback lands wherever the stream next
  synchronizes — plausibly inside the MoE, which is why it read as an MoE bug).
- The sliding-window layers are NOT the asserting site: `StaticSlidingWindowLayer`
  handles overflow with explicit `cat`/roll paths (`cache_utils.py:485-560`).
  The **full-attention layers** (every 6th + the last,
  `configuration_diffusion_gemma.py:117-128`) use the plain `StaticLayer` — the
  unchecked one.
- Eager runs never see this because the dynamic cache grows (`is_compiling`
  requires a static cache, `:692`) — matching "eager works, compiled asserts".

**Discriminating probes (cheapest first, all payload-only, $0 rebuild):**

1. `battery.py 1 --param max_length=288` (or any value making
   `max_new_tokens ≤ 256` for P1 → single canvas). H1 predicts **clean run**;
   if it still asserts, H1 is dead and H3 promotes.
2. Set `CUDA_LAUNCH_BLOCKING=1` in the endpoint env for one drive — the assert
   then localizes to `index_copy_` in `StaticLayer.update` (H1) vs somewhere
   else (H3).
3. Cross-check: with the §1 monkeypatch, run compiled **grouped_mm**
   single-canvas. H1 predicts it now runs too (the assert was never about the
   experts impl).

### H2 (negative result, GROUNDED) — the MoE gathers are NOT the asserting site

`batched_mm_experts_forward`'s only fancy indexing is
`gate_up_proj[expert_ids]` / `down_proj[expert_ids]` with `expert_ids`
**clamped in-bounds** first (`moe.py:138`, out-of-place exactly to protect the
routing tensor); `_batched_linear` is plain `bmm` (shape errors are host-side,
not device asserts); the accumulate is `view + sum` (`moe.py:177`), no scatter.
Router `topk` over 128 experts can't emit an OOB id (`modeling_diffusion_gemma.py:514-526`).
The prior hypothesis "indexing/cache-shape bug in the MoE" has no candidate
site in the batched path.

### H3 (rank 3, ESTIMATED) — reduce-overhead cudagraph buffer hazards

`_compile_functions` uses `mode="reduce-overhead"` (CUDA graphs) on five
functions (`:1241-1261`). The loop already defends the known aliasing points
(`cudagraph_mark_step_begin` `:1019`; the `.clone()`s at `:1046,:1048` "needed
for compiled sampler"), but `self_conditioning_logits = processed_logits.to(embeddings_dtype)`
(`:1064-1065`) is a no-op alias whenever the eager processor returns the
decoder's static output tensor and dtypes match — a stale-buffer read on the
next replay. Torch usually surfaces overwritten-cudagraph-output access as a
Python-side error, not a device assert, which is why this ranks below H1.
Probe: after H1's probe passes, one drive with `TORCH_LOGS=cudagraphs` (env,
payload-only) — noise here without an assert means H3 is a correctness
(garbage-logits) risk to watch, not the crash.

## §3 The control-compatibility map

What actually forfeits compile, feature by feature. The compile gate is ONE
condition: `is_compiling = past_key_values.is_compileable` — i.e. a static
cache (`generation_diffusion_gemma.py:692`). Everything else follows from what
`_compile_functions` does and doesn't compile (`:1235-1265`).

| Control feature | Compiled path OK? | Why (cite) |
|---|---|---|
| **ClampLogitsProcessor (the per-step clamp)** | **YES** | `logits_processor` runs eager INSIDE `_denoising_step`, which is never compiled — only decoder-forward/sampler/stop are (`:1034` vs `:1235-1265`); external processors applied first (`:1171-1172`); near-one-hot survives the temp schedule (`diffgemma_common.py:35-78`). GROUNDED |
| Built-in early-stop (`stability_threshold` + `confidence_threshold`) | YES | explicitly compiled (`:1258-1262`); it's the sampler-native stop the model ships with. GROUNDED |
| `entropy_bound`, `t_min`/`t_max`, step cap | YES | pure generation-config; consumed by compiled and eager paths alike (`:1173-1181`, `:1223-1233`). GROUNDED |
| Custom `StepCountStopping` / `denoise_to_step` / `resume_renoise` | NO (as built) | the worker runs them on the dynamic cache → `is_compiling` false → whole loop eager (`:692`); compiling a Python-stateful criteria `fullgraph` would fail. GROUNDED |
| KV-prefix reuse (`dynamic_full`, `gpu_worker.py:424,486`) | NO | same gate — the reuse contract needs a dynamic cache; static cache + crop/re-feed is a different build. GROUNDED |
| `refine_loop` (checkpoint-based mid-denoise control) | NO as built — but see below | it rides `denoise_to_step`/`resume_renoise`. |

**The shape that keeps BOTH compile and control (untested):** each refine
iteration = one **full compiled `generate()`** with (a) the built-in early-stop
and (b) a `ClampLogitsProcessor` holding every oracle-approved span to its
tokens. "Renoise the bad spans" is then expressed as *fresh generation with the
good spans clamped* rather than *resume at step K* — the clamp is the one
control primitive that is compile-compatible by construction (row 1), and the
oracle runs between `generate()` calls where Python is free. Cost: each
iteration re-denoises the whole canvas; benefit: each forward is the compiled
kind. Whether the fresh-temperature-ramp-per-iteration matches
`resume_renoise` quality is **GPU-ONLY-UNKNOWN** — but it is the only compiled
refine-loop shape that exists without forking the sampler.

### The 4×-slower measurement, re-read

The north-star records: full 48-step "COMPILED" `gen_s 0.57s` vs
`denoise_to_step` K=24 `gen_s 2.32s` → "custom stopping forfeits compile, 4×".
But `is_compiling` requires a static cache (`:692`), and the working
static-cache knob (`max_length`-only args, `1a475ce9`) postdates that
measurement — a plain `generate` call then ran the **dynamic** cache, i.e.
**eager**. The consistent read: 0.57 s = eager with the built-in early-stop
firing after ~4 forwards (~140 ms/forward × 4 ≈ 0.57 s, matching the
~130 ms/forward co-location number); 2.32 s = eager with 24 FORCED forwards
(24 × ~97 ms). **The 4× was forward-count, not a compile tax** — which is good
news twice: (1) checkpoint control costs extra forwards, and
validation-as-early-stop (stop at the cheapest decisive tier) is exactly the
mitigation we already built; (2) the compiled path's true speed on the A100 is
**unmeasured, not measured-and-small**. ESTIMATED (the re-read is arithmetic
over recorded numbers; one probe settles it).

## §4 Verdict — is any compile work worth an owner GPU-minute?

**Yes — exactly one probe chain, after exp D, all payload-only.** The prior
ceiling argument stands where it stood: the MoE GEMM itself won't get faster on
SM80 (the kernel ecosystem is Hopper-gated —
[[forward-speedup-levers-2026-06-30]] §1), and exp D's over-commit remains the
first, free lever. But this dig shows the *compiled path was never actually
measured* — every compiled attempt died on two walls that are both cheap,
grounded, payload-dodgeable bugs (a version-check tracing artifact and an
under-sized cache), not hardware limits — and compiled `reduce-overhead`
decode attacks precisely the cost the eager loop pays most (per-step Python +
launch overhead across 30 layers at batch-1). If the single-canvas probe runs
clean and steady compiled tok/s beats eager meaningfully, the compiled
clamp-based refine shape (§3) becomes a real speed×control lever that
COMPOSES with over-commit; if the probe still asserts, close the question —
eager + exp D is the A100 envelope and compile belongs to the Hopper/TPU
column. Ordered owner probes: (1) exp D (settled, first); (2)
`battery.py 1 --param max_length=288` (H1); (3) if clean → steady compiled
tok/s + one `clamp_smoke` with `compile:true`; (4) only if (3) wins →
`CUDA_LAUNCH_BLOCKING=1`/`TORCH_LOGS=cudagraphs` characterization and the
compiled-refine dry run.

## Sources

- Vendored transformers v5.11.0 (= deployed pin): `reference-code/transformers`
  `src/transformers/integrations/moe.py:34-38` (the inert patch), `:118-179`
  (batched_mm), `:138` (expert-id clamp), `:266-308` (`_can_use_grouped_mm`),
  `:301` (the traced call); `src/transformers/utils/import_utils.py:50-52`
  (find_spec), `:165-183` (`@lru_cache` version helpers);
  `src/transformers/models/diffusion_gemma/generation_diffusion_gemma.py:638`
  (max_new_canvases), `:662-665` (cache sizing), `:692` (the compile gate),
  `:1019-1065` (`_denoising_step`: eager processor seam, clones,
  self-conditioning), `:1162-1233` (processor/stop/sampler prep), `:1235-1265`
  (`_compile_functions`, reduce-overhead+fullgraph);
  `src/transformers/cache_utils.py:353-461` (StaticLayer, unchecked
  `index_copy_`), `:463-560` (sliding layer's guarded overflow);
  `src/transformers/models/diffusion_gemma/configuration_diffusion_gemma.py:117-128`
  (5:1 layer pattern).
- torch 2.12.1 (driver venv; mechanism check): `_dynamo/decorators.py:177`,
  `_dynamo/variables/functions.py:547-551, 2396-2429`,
  `_dynamo/variables/builder.py:1413-1420`, `_dynamo/utils.py:1301-1306`.
- Worker: `tmp/flash-diffgemma/gpu_worker.py:50-102` (load + experts_impl),
  `:1347-1359` (compile knob), `:424,486` (dynamic_full);
  `tmp/flash-diffgemma/diffgemma_common.py:35-78` (ClampLogitsProcessor);
  `tmp/flash-diffgemma/battery.py:197-228` (exp1 config).
