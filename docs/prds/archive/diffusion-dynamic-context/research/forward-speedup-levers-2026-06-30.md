---
type: research
status: active
tags: [research, agent, flow]
---

# DiffusionGemma forward-speedup levers — Triton MoE, attention, tokens/forward × renoise (2026-06-30)

> No-GPU research. Measured baseline: DiffusionGemma 26B-A4B MoE block-diffusion
> LM, A100-80 BF16 eager = **~130-140 tok/s, ~17 tokens committed per forward,
> ~130ms/forward over a 256-position canvas**. The forward processes ALL 256
> positions each step; `EntropyBoundSampler` finalizes the confident ones. The
> compiled path is BLOCKED (find_spec graph-break → CUDA device-assert under
> batched_mm). This doc ranks the levers to speed per-forward time and raise
> tokens-per-forward, grounded in `reference-code/transformers` + external kernels.
>
> Companion to [[model-mechanics-grounding-2026-06-28]] (decode mechanics),
> [[serving-optimization-survey-2026-06-28]] (the 137-vs-1000 explanation, two
> endpoints), [[unified-control-oracle-2026-06-29]] (the renoise oracle this doc's
> §3 joint-sweep builds on).

## TL;DR — the verdict up front

1. **The big one (Triton MoE kernel) is grounded-NEGATIVE on the A100.** The
   reason eager is all we have is **not just** the find_spec break — it is that
   **DiffusionGemma is decisively MoE-bound** (128 experts, top-8 → **S = 256×8 =
   2048 token-expert pairs/forward**, batch 1) and **every fast grouped-expert
   GEMM kernel in the ecosystem — torch's own `grouped_mm` fast path, HF's
   `sonicmoe` (CuteDSL), and the PyTorch persistent grouped-GEMM Triton kernel —
   requires Hopper SM90** (TMA, warp-specialization, wgmma). The **A100 is SM80**,
   so it sits on the for-loop fallback by hardware, not by a missing kernel. A
   custom Triton kernel CAN dodge the find_spec break and be CUDA-graph-clean, but
   on SM80 it would at best match the existing fallback's cuBLAS-per-group — there
   is no 1.5-3× to unlock on Ampere from the MoE kernel. **The 1.5-3× compiled
   speedup the prompt hoped for is a Hopper number.** (Grounded; see §1.)
2. **Attention is NOT the bottleneck** — at hidden=2304, 8 heads, sliding-window
   512, a 256-canvas forward is ~MoE-bound by a wide margin (rough estimate:
   MoE FLOPs ≈ 10-15× attention FLOPs at this size). **FA2 on the A100 is a
   small, real win (the worker is on sdpa, not FA2)** but it shaves the smaller
   slice. FA3 does NOT activate on Ampere. **Do it (cheap), but expect <10%.**
   (Estimated split; §2.)
3. **tokens-per-forward × free-renoise is the highest impact × feasibility lever,
   and it's the buzzsaw's unique one.** We already commit ~17/forward at
   entropy_bound 0.1. The novel lever: because the co-located oracle is now ~free
   (0.05ms, off the critical path), **crank entropy_bound HIGH (commit 30-50+/
   forward → 2-3× fewer forwards → 2-3× tok/s) and let the free oracle's renoise
   un-commit the wrong tokens.** An AR model cannot do this. This is a **sampler
   config sweep + the renoise driver we already built** — zero new kernels, runs
   on the A100 today. (Design in §3.)
4. **Diffusion-specific:** fewer denoise steps is the SAME lever as #3 (higher
   commit → loop hits the early-stop sooner); cross-step decode KV-cache does NOT
   apply (the canvas re-attends fresh each step, bidirectional, no causal KV to
   reuse — the encoder-KV reuse we built is prompt-side only); the relevant
   speculative-decoding analog (DFlash / self-speculative dLLM) IS real and is a
   2026 research frontier, but it's a serving-path (vLLM) capability, not a
   transformers-control-worker lever. (§4.)

**Recommended order (impact × feasibility):** **§3 tokens/forward × renoise sweep
(do first — free, on-A100, unique)** → **§2 FA2 swap (cheap, small, do alongside)**
→ §4 step-count tuning (falls out of §3) → **§1 custom Triton MoE (DEPRIORITIZE on
A100; revisit only on a Hopper/Blackwell serving box).**

---

## Architecture facts (grounded — these decide everything)

From `reference-code/transformers/src/transformers/models/diffusion_gemma/configuration_diffusion_gemma.py`
and `modeling_diffusion_gemma.py`, plus the model card:

| Fact | Value | Cite |
|---|---|---|
| hidden_size | 2304 | `configuration:85` |
| num_hidden_layers | 30 | `configuration:87` |
| num_attention_heads / kv_heads | 8 / 4 (GQA) | `configuration:88-89` |
| head_dim | 256 | `configuration:90` |
| sliding_window | 512, 5:1 pattern (5 sliding : 1 full) | `configuration:102, 118-121` |
| num_experts (total) | **128** | model card; `configuration:108` (`num_experts`) |
| top_k_experts | **8** | model card; `modeling:514-516` (`torch.topk(..., k=self.config.top_k_experts)`) |
| canvas_length | 256 (the per-forward position count) | `configuration:152, 196` |
| 26B total / 4B active (A4B) | — | model card |

**The load-bearing derived number:** the MoE block flattens the canvas to
`hidden_states_flat = residual.reshape(-1, hidden)` (`modeling:625, 700`), routes
top-8 (`modeling:514-526`), and the experts forward processes **S = num_tokens ×
top_k = 256 × 8 = 2048** token-expert pairs **per layer, per forward**, batch 1
(`grouped_mm_experts_forward`, `moe.py:380-481`; `S = num_tokens * num_top_k`,
`:391`). 30 layers × 2048 pairs × two GEMMs (gate_up 2304→2·moe_inter, down
moe_inter→2304) per forward. **This is the dominant compute and it is the thing a
faster MoE kernel would speed.**

---

## §1 — Custom Triton MoE kernel (the big one): grounded-NEGATIVE on A100

### 1a. Why eager is all we have — the TWO reasons, ranked

The prompt names the find_spec graph-break as the blocker. It is real but it is
the *second*-order reason. The first-order reason is **hardware**.

**Reason A (hardware — the real wall): every fast grouped-expert GEMM needs SM90.**
Three independent fast paths exist in the HF/torch MoE surface, and **all three
gate on Hopper**:

- **torch's own `grouped_mm` fast path** (`_can_use_grouped_mm`, `moe.py:266-308`):
  on CUDA it returns the fast path for `get_device_capability() >= (8, 0)` **when
  `torch.nn.functional.grouped_mm` exists (torch≥2.10)** OR `torch._grouped_mm`
  (torch≥2.9) — i.e. on torch 2.9.1 (what we run) the **SM80 path technically
  exists** (`:301-302`, `>= (8,0)`). BUT this fast path is exactly what trips the
  find_spec break under compile (Reason B), AND the fast `_grouped_mm` on Ampere
  is **not the Hopper-tuned kernel** — it falls to a cuBLAS-per-group path roughly
  equivalent to the for-loop fallback. So even when it "works" on SM80 eager, it's
  not a speedup over what we have.
- **HF `sonicmoe`** (already registered in `ExpertsInterface`, `moe.py:491`;
  `integrations/sonicmoe.py`): **explicitly refuses below SM90** —
  `"sonic-moe requires a Hopper (SM90+) or newer GPU"` raises ImportError at
  `sonicmoe.py:62-68` (`major = get_device_capability()[0]; if major < 9: raise`).
  It's a CuteDSL kernel (`nvidia-cutlass-dsl`) using wgmma. **Dead on the A100.**
- **PyTorch persistent cache-aware grouped-GEMM Triton kernel**
  (torchtitan `triton_contiguous_group_gemm`, the [PyTorch blog](https://pytorch.org/blog/accelerating-moes-with-a-triton-persistent-cache-aware-grouped-gemm-kernel/)):
  the headline 1.42-2.62× is **H100, and the load-path uses TMA (Hopper-exclusive)**.
  The persistent-launch + grouped-tile-ordering ideas are arch-agnostic, but the
  measured win leans on TMA + the H100 memory system. On SM80 you lose TMA and the
  speedup collapses toward the cuBLAS-per-group baseline.

**Conclusion A:** on the A100 there is **no off-the-shelf fast grouped-expert
kernel** and a hand-rolled SM80 Triton kernel would, realistically, **match (not
beat) the existing for-loop/cuBLAS fallback** — the for-loop already calls
`torch.mm` per group (`_grouped_mm_fallback`, `moe.py:185-208`), which is cuBLAS,
which on SM80 is near-peak for these shapes. The 2048-pair, 128-expert,
fine-grained layout means each group is small (avg 16 pairs/expert) — a regime
where a custom Triton kernel CAN beat naive cuBLAS-per-group (kernel-launch
overhead, L2 reuse across experts), but the win is "fold 128 launches into 1
persistent kernel," i.e. **launch-overhead + L2, not raw FLOPs** — plausibly
10-30%, not 1.5-3×, and only if you out-engineer cuBLAS on Ampere, which is hard.

**Reason B (the find_spec break — secondary, and partly already patched).** The
break the prompt cites at `moe.py:301` is `is_torch_greater_or_equal("2.9", ...)`
inside `_can_use_grouped_mm`, which transitively calls `importlib.util.find_spec`,
which Dynamo refuses to trace → graph break. **The source we have ALREADY patches
this** (`moe.py:34-38`): `is_torch_greater_or_equal =
torch._dynamo.assume_constant_result(is_torch_greater_or_equal)` (and the `_less_`
twin). `assume_constant_result` makes Dynamo evaluate it once at trace time and
inline the bool — no body trace, no find_spec. **So on the readable source the
break should be GONE.** That the deployed worker still hits it means **the pinned
worker transformers (5.11.0) predates this patch** — the fix is **bump
transformers to a version with the `assume_constant_result` wrap** (or backport
the 5-line patch into the worker image), NOT a custom kernel. The CUDA
device-assert under `experts_implementation=batched_mm` is a separate
shape/indexing bug (the clamp at `moe.py:138` keeps ids in-bounds in eager but the
compiled whole-canvas path with a static cache mis-sizes something) — worth a
focused dig, but again it's an indexing/cache-shape bug, not a kernel-availability
problem.

### 1b. CAN a custom Triton kernel (a) dodge find_spec, (b) be CUDA-graph-clean, (c) beat eager?

- **(a) Dodge find_spec: YES, trivially.** A custom expert-matmul registered as a
  `torch.library.custom_op` (the pattern `moe.py:251-263` already uses for
  `grouped_mm_fallback`) is an **opaque graph node** — Dynamo doesn't trace its
  body, so no version-check, no find_spec. `sonicmoe` does the same via
  `@torch._dynamo.allow_in_graph` (`sonicmoe.py:100`). **This is the standard,
  proven way to make a kernel compile-clean** and it works regardless of card.
- **(b) CUDA-graph / fullgraph-clean: YES, with a fixed-shape contract.** A custom
  op with static input shapes (canvas always 256, S always 2048, experts always
  128) is CUDA-graph-friendly. The current device-assert is *because* the existing
  paths do data-dependent things (`torch.unique`, dynamic offsets) the source
  itself flags as graph-hostile (`moe.py:427-430` "torch.unique ... breaks the
  graph capture"). A custom kernel that takes `(input, weight, offs)` with fixed
  max sizes sidesteps that. **Feasible.**
- **(c) Match/beat eager grouped_mm: on SM80, NO meaningful beat.** See 1a — the
  ceiling on Ampere is cuBLAS-per-group, which the existing fallback already hits.
  **On a Hopper/Blackwell serving box: YES** — that's exactly where sonicmoe / the
  torchtitan kernel deliver 1.4-2.6×. So the kernel work is **real and high-value
  for the SERVING endpoint (H100/H200/Blackwell), worthless for the A100 control
  worker.**

### 1c. Existing fused-MoE kernels surveyed (layout match)

| Kernel | Layout it wants | Matches our fused 3D params? | A100? | Verdict |
|---|---|---|---|---|
| **vLLM `fused_moe`** (Triton) | expert-batched `E × max_tok × K` OR contiguous grouped, with vLLM's own dispatch/combine | **Re-layout needed** — vLLM owns the routing+scatter; tightly coupled to vLLM internals, "not available as a standalone library" ([vLLM discussion #3888](https://github.com/vllm-project/vllm/discussions/3888), [vLLM moe_kernel_features](https://docs.vllm.ai/en/latest/design/moe_kernel_features/)) | runs on SM80 but tuned for serving | Not drop-in; it's the vLLM serving path (which seals the sampler — see serving survey) |
| **HF `sonicmoe`** (CuteDSL) | **exactly our `gate_up_proj`/`down_proj` 3D fused params** (`w1=to_local(self.gate_up_proj)`, `sonicmoe.py:173-176`) | **PERFECT layout match** — it's already wired as `experts_implementation="sonicmoe"` | **NO — SM90+ only** (`sonicmoe.py:62-68`) | **Drop-in on Hopper, hard-refuses on A100** |
| **Megablocks grouped GEMM** (Triton, block-sparse) | block-sparse / contiguous grouped; absorbs variable group sizes via fixed BLOCK_M ([megablocks](https://github.com/databricks/megablocks)) | needs the scatter-to-block-sparse adapter; not the fused-3D layout directly | SM80-capable (Triton) | Possible SM80 port, but engineering-heavy and ~for-loop-parity at our small group sizes |
| **torchtitan persistent grouped-GEMM** (Triton) | concatenated `[E·N, K]` ([PyTorch blog](https://pytorch.org/blog/accelerating-moes-with-a-triton-persistent-cache-aware-grouped-gemm-kernel/)) | re-layout from 3D `(E,N,K)` is a contiguous view — close | win is TMA (Hopper); recompiles on token-count change | drop-in via torchtitan, **but the speedup is Hopper-bound** |
| **SGLang fused MoE** (align&sort + Triton) | its own align/sort dispatch ([SGLang blog](https://huggingface.co/blog/yiakwy-xpu-team/efficient-moe-align-sort-design-for-sglang)) | needs SGLang plumbing | SM80-capable | not drop-in to transformers' grouped_mm |

**The cruel irony:** the ONE kernel with a perfect layout match for our fused 3D
params (`sonicmoe`, already in the interface) is the one that hard-refuses the
A100. Everything that runs on SM80 needs a re-layout/adapter and lands at
for-loop-parity for our fine-grained 128-expert/top-8 small-group regime.

### 1d. §1 verdict

**Custom Triton MoE kernel: DEPRIORITIZE for the A100 control worker.** It cannot
deliver the hoped 1.5-3× on Ampere because the fast-kernel ecosystem is Hopper-gated
and the SM80 ceiling (cuBLAS-per-group) is what the existing fallback already hits.
**The find_spec break is fixable by a transformers bump / 5-line backport, NOT a
kernel.** Reserve the custom-kernel / `sonicmoe` work for a **Hopper/Blackwell
SERVING endpoint**, where it's the legitimate 1.4-2.6× path (and where vLLM already
ships it). *(Reason A: grounded. The 10-30% SM80 launch-overhead/L2 upside: estimated.)*

---

## §2 — Attention: MoE-bound, not attn-bound; FA2 is a small real win

### 2a. The split (estimated, from shapes)

Per forward, per layer, batch 1, canvas N=256:

- **Attention FLOPs** ≈ 2 · N² · d_model for QK^T+AV, but **sliding-window 512 ≥
  256** so the whole 256-canvas is within one window → effectively dense 256×256.
  With 8 heads × head_dim 256 (d=2048 for attn) and the 5:1 sliding/full pattern,
  attention is ~`O(N² · d)` = ~256² · 2048 ≈ 1.3e8 · (heads bookkeeping) per layer.
  The QKV/O projections add ~`N · d_model · d` linear FLOPs.
- **MoE FLOPs** = the dominant term: **S=2048 pairs × 2 GEMMs**, gate_up
  (2304 → 2·moe_inter) + down (moe_inter → 2304). With moe_inter ≈ 768-1024 (a 4B-
  active, 128-expert fine-grained model has small per-expert FFN), that's
  ~2048 · 2304 · ~1800 · 2 ≈ 1.7e10 FLOPs **per layer** — i.e. **~100× the
  attention's quadratic term and ~10-15× the QKV projections.**

**Estimate: the 256-canvas DiffusionGemma forward is ~85-92% MoE, ~8-15%
attention+projections.** The model is **MoE-bound**, consistent with why the whole
ecosystem optimizes the MoE kernel and why FP8/NVFP4 (which quantize the expert
GEMMs) are the headline levers. *(Estimated — worth confirming with a one-shot
`torch.profiler` trace on the live A100; that probe is cheap and decisive.)*

### 2b. FA2 vs sdpa on the A100

- The worker currently loads **`attn_implementation="sdpa"`** (`gpu_worker.py:90-94`),
  falling back to eager only if sdpa fails. It is **NOT using FA2.**
- **FA2 runs on the A100 (SM80) and supports both sliding-window and bidirectional
  (causal=False) masks** ([Dao-AILab flash-attention](https://github.com/Dao-AILab/flash-attention),
  [flash-attn A100 issue #1481](https://github.com/Dao-AILab/flash-attention/issues/1481)).
  The decode pass is bidirectional over the canvas; the encoder/prefill is causal —
  FA2 handles both. **FA3 does NOT activate on Ampere** (warp-spec/async need SM90;
  vLLM/SGLang auto-fall-back to FA2 on Ampere) — so FA3 is irrelevant here.
- **Expected win: small.** Because attention is only ~10% of the forward (2a), even
  a generous 1.5-2× attention speedup from FA2-over-sdpa is **<10% end-to-end**.
  But it's **cheap** (a `from_pretrained(..., attn_implementation="flash_attention_2")`
  swap + the flash-attn wheel in the image) and **stacks** with §3. *(Estimated.)*

### 2c. §2 verdict

**Swap sdpa → flash_attention_2 (cheap, do it alongside §3), expect <10%.** First
run a `torch.profiler` forward trace to confirm the MoE/attn split before investing
— if attn is genuinely ~10%, FA2 is a nice-to-have, not a lever. **The model is
MoE-bound; do not expect attention to move the needle.**

---

## §3 — tokens-per-forward × renoise: the JOINT frontier (highest impact × feasibility, UNIQUE to the buzzsaw)

### 3a. How `EntropyBoundSampler` decides commits (grounded)

`generation_diffusion_gemma.py` `EntropyBoundSampler.accept_canvas` (`:402-444`):

1. Compute per-position **entropy** of the logits: `H = -Σ p·log p`.
2. Sort positions **ascending** by entropy (most-confident first).
3. Accept the `k` lowest-entropy positions while `cumulative_entropy −
   max(entropy_1..k) ≤ entropy_bound` (`:439`).
4. `accepted_canvas = where(accepted_mask, denoiser_canvas, current_canvas)`
   (`:443`). Non-accepted positions are **renoised to fresh random ids** next
   (`renoise_canvas`, `:446-465`).
5. **No locking** — `accepted_token_mask` is recomputed from scratch every step
   (`:435-442`); a "committed" position can be renoised again later if its entropy
   rises. The paper's "~7.5 re-masks" is **emergent from this bound**, not a
   separate mechanism (see [[model-mechanics-grounding-2026-06-28]] §1b).

**The knob: `entropy_bound` (default 0.1, `:225`).** HIGHER bound → more positions
clear the cumulative threshold per step → **more tokens committed/forward → fewer
forwards to fill the canvas → higher tok/s.** This is a **generation-config field**
(`gen_kwargs`, the worker already plumbs it: `_gen_overrides`, `gpu_worker.py:156+`)
— a pure A/B, no redeploy of logic.

### 3b. The entropy_bound → tokens/forward → tok/s → quality relationship

| entropy_bound | tokens/forward (est.) | forwards to fill 256 (est.) | tok/s (est., from ~130ms/fwd) | quality |
|---|---|---|---|---|
| 0.05 (tight) | ~8-10 | ~30-48 | ~70-90 | highest |
| **0.1 (default)** | **~17** (measured) | **~15-24** | **~130-140** (measured) | reference |
| 0.3 | ~25-35 | ~8-12 | ~250-400 | knee likely here |
| 0.6-1.0 (aggressive) | ~40-60 | ~5-7 | ~400-600 | degrades — over-commit |

*(The 0.1/17/130 row is measured; the rest is estimated from the
linear-ish "more positions clear a looser bound" relationship and the per-canvas
cost model — must sweep on the A100 to find the real curve.)*

**Where the quality knee likely is (estimated):** the entropy bound is a
*cumulative* threshold, so doubling it more-than-doubles accepted count (positions
that were just over the old line now clear it AND drag the cumulative sum). Quality
holds while the accepted positions are genuinely low-entropy; it falls off a cliff
once the bound is loose enough to accept *medium*-entropy positions whose argmax is
a coin-flip. For a 128-expert code model the knee is **plausibly around
entropy_bound 0.3-0.5** standalone — i.e. you can roughly **double tok/s
(~250-400) before standalone quality visibly cracks.**

### 3c. The NOVEL lever: over-commit + free-oracle renoise correction

**The buzzsaw's unique speed move.** The co-located oracle (parse/eval/retrieve) is
now **~free** (0.05ms warm parse, off the critical path —
`gpu_worker.py:106-153`, the persistent sidecar). The renoise driver
(`resume_renoise` / the `unified-control-oracle` `refine` partition) can
**un-commit (re-open) any span the oracle judges wrong** by dropping its clamp so
the entropy bound re-decides it (`mode-mechanics`: re-open = stop forcing logits,
NOT a mask). Therefore:

> **Crank entropy_bound HIGH (commit aggressively, 30-50+/forward → 2-3× fewer
> forwards), accept that some committed tokens are wrong, and let the free oracle's
> renoise pass un-commit and re-denoise exactly the wrong spans.** An AR model
> CANNOT do this — once it emits a wrong token it must either continue or restart;
> it has no "un-commit position 47, keep the other 200" primitive. The diffusion
> canvas + the free oracle gives us that primitive.

The economics: aggressive commit saves forwards globally; the oracle-driven renoise
costs forwards *locally* (only on the wrong spans). **Net win iff
`forwards_saved_by_overcommit > forwards_spent_on_renoise_correction`.** Because the
oracle is free to *evaluate* (it's the *forward* that costs), the only cost is the
extra denoise forwards on the re-opened spans — which are a small fraction of the
canvas (a parse error reopens one form; a spec mismatch reopens one slot).

### 3d. The joint sweep design (speed × quality × renoise)

Three-arm gym sweep, each arm = `(scenario × git-sha × config)`, scored by the
existing predicate machinery (`mode-driven-guided-generation`, `e1_kill_gate.py`):

- **Arm A (baseline):** entropy_bound 0.1, no renoise. Measure tok/s, wall-clock/
  turn, pass-rate. (The reference.)
- **Arm B (over-commit, no correction):** sweep entropy_bound ∈ {0.2, 0.4, 0.7,
  1.0}, no oracle. Plots the **standalone speed×quality curve** — finds the naked
  knee (3b).
- **Arm C (over-commit + free renoise):** same high entropy_bound as B's fastest
  *still-mostly-correct* point, PLUS the oracle renoise driver re-opening
  parse/eval/spec-failing spans each macro-step. **Measure: does C recover B's lost
  quality while keeping most of B's speed?** Win condition: **C's
  (pass-rate, wall-clock) Pareto-dominates A** — i.e. the free oracle buys back the
  quality the over-commit spent, net faster.

**Metrics per arm:** `tokens_per_forward` (already surfaced, `gpu_worker.py:1279`),
`denoise_steps`, `gen_s`, `tok_per_s`, oracle-renoise-forward count, final
parse/eval/spec pass. **The sweep is the deliverable** — a moved number across
(entropy_bound × renoise on/off), not an anecdote. **Runs on the A100 today with
the worker + driver we already have.**

### 3e. §3 verdict

**Highest impact × feasibility. Do FIRST.** Pure config + the renoise driver we
built; no kernels, no new hardware. Standalone over-commit plausibly ~2× (to
~250-400 tok/s) before quality cracks; **over-commit + free-renoise is the unique
buzzsaw lever that an AR model structurally cannot match**, and the place to prove
the thesis's "free oracle changes the speed×quality frontier" claim with a real
sweep. *(The mechanism is grounded; the exact curve + the C-dominates-A result are
the experiment to run.)*

---

## §4 — Diffusion-specific levers

### 4a. Denoise-step-count reduction — SAME lever as §3, with a CAVEAT

`max_denoising_steps` is a **CAP, not a target** (settled; [[roadmap]]). The loop
runs `for cur_step in reversed(range(1, max_denoising_steps+1))`
(`generation:757`) and exits early on `StableAndConfidentStoppingCriteria`
(`:1056-1065`, stable argmax × `stability_threshold` + mean-entropy <
`confidence_threshold`). So:

- **You don't "set fewer steps" to go faster** — higher `entropy_bound` (§3) makes
  the canvas converge sooner, hitting the early-stop in fewer ACTUAL forwards. The
  step count is downstream of commit-rate.
- **CAVEAT (from the worker docstring, `gpu_worker.py` knob notes):** the
  temperature ramp (`t_max` 0.8 → `t_min` 0.4, `AnnealingTemperatureShaper`) is
  parameterized over `cur_step / max_denoising_steps`. **Shrinking the cap
  compresses the temp ramp** (you reach low temperature too fast → premature
  over-confidence). So tune **`entropy_bound` + early-stop thresholds**, leave the
  cap generous. *(Grounded in source + the settled roadmap note.)*

### 4b. Better/faster samplers

The shipped `EntropyBoundSampler` is already the throughput-tuned one (it's what
yields the H100 1000 tok/s number at bound 0.1). The realistic sampler lever is
**not a new sampler, it's tuning the existing bound + the §3c over-commit**. A
custom sampler subclass is possible (`_prepare_sampler`, `:1229-1239` constructs it
per-call) but offers nothing the entropy_bound knob + logits-processor renoise
doesn't already give. **Skip.**

### 4c. Cross-denoise-step KV caching — does NOT apply to the decode forwards

We built **encoder-KV reuse** (the prompt prefill cached once, cross-attended every
step — `KVPrefixCache`, `gpu_worker.py:35-41`; the 62%-of-latency prefill win).
**That is prompt-side and already captured.** The DECODE forwards canNOT reuse a KV
cache across denoise steps because:

- The canvas is re-attended **fresh every step** with **bidirectional** attention
  (`use_bidirectional_attention`, `configuration:107`); every position attends every
  other, and the canvas *contents change every step* (accept/renoise rewrites ids).
  There is no causal, append-only KV to carry forward — the K/V for every position
  is recomputed because the tokens at those positions changed.
- This is intrinsic to block diffusion (it's why a forward is ~constant-cost
  regardless of fill — the "per-canvas not per-token" finding,
  [[serving-optimization-survey-2026-06-28]] §1c). **No decode-side KV lever
  exists.** The encoder-KV reuse we have is the only KV win; it's done.

### 4d. Speculative-decoding analog for diffusion — REAL, but a serving-path lever

A 2026 research frontier exists and is directly on-point:

- **DFlash** (block-diffusion drafter, [LMSYS blog](https://www.lmsys.org/blog/2026-06-15-next-generation-speculative-decoding-dflash-v2/),
  [arXiv 2602.06036](https://arxiv.org/pdf/2602.06036)) — a lightweight block-
  diffusion draft model proposes a whole block in parallel, verified in one forward.
  Xiaomi's MiMo uses it for >1k tps. This is the "custom drafter" the headline-
  number caveat referenced.
- **Self-Speculative Decoding for dLLMs (SSD)** ([arXiv 2510.04147](https://arxiv.org/abs/2510.04147),
  [OpenReview](https://openreview.net/forum?id=rKJ7A30lQQ)) — the dLLM is **both
  drafter and verifier, no auxiliary model**: it self-drafts multiple positions and
  verifies via hierarchical trees in one forward. **This is the most relevant**
  (no second model to host).
- **Trajectory-Level Speculative Decoding** ([ICML 2026 poster](https://icml.cc/virtual/2026/poster/61516))
  — confidence-stratified draft denoising trajectories, blockwise-parallel verify
  with bidirectional masking — practically a *principled version of the §3c
  over-commit-then-correct idea*.

**Verdict:** these are real and the §3c over-commit is a poor-man's, oracle-driven
cousin of trajectory-level speculation. BUT they require **modifying the decode
loop / a drafter**, which is a **serving-framework (vLLM) or a fork-the-sampler**
job — not a knob on the transformers control worker. **Note them as the serving-
endpoint frontier; they are not an A100-control-worker lever this session.**
*(Grounded that the methods exist; integration is out of scope for the control
worker.)*

---

## Ranked recommendation (impact × feasibility)

| Rank | Lever | Impact (A100) | Feasibility | Grounded/Estimated |
|---|---|---|---|---|
| **1** | **§3 tokens/forward × free-renoise joint sweep** | **HIGH (~2-3× tok/s; the unique buzzsaw lever)** | **HIGH — config + the driver we built, on-A100 today** | mechanism grounded; curve = the experiment |
| **2** | **§2 FA2 swap (sdpa → flash_attention_2)** | LOW-MED (<10%; model is MoE-bound) | **HIGH — one from_pretrained kwarg + the wheel** | estimated split; swap grounded |
| **3** | **§4a step-count / early-stop tuning** | MED (falls out of #1) | HIGH — same knobs | grounded |
| **4** | **§1 fix the find_spec break (transformers bump / 5-line backport)** | LOW on A100 (unblocks compile, but SM80 ceiling = fallback) | MED — version bump + the batched_mm device-assert dig | grounded |
| **5** | **§1 custom Triton MoE kernel** | **~0 on A100 (Hopper-gated ecosystem); HIGH on a Hopper/Blackwell serving box** | LOW on A100 (matches, not beats, fallback) | Reason-A grounded; SM80 upside estimated |
| — | §4d speculative decoding (DFlash/SSD) | HIGH but serving-path only | LOW — needs loop/drafter mods or vLLM | methods grounded; integration out of scope |

**Pursue order:** **#1 first (free, unique, on-A100, proves the thesis), #2
alongside (cheap), #3 falls out, #4 only if you want compile for other reasons.**
**#5 (the custom MoE kernel) is the WRONG investment for the A100 — bank it for a
Hopper/Blackwell SERVING endpoint, where `sonicmoe` / the torchtitan Triton kernel
already deliver the real 1.4-2.6×.**

### The single headline answer

**Does an existing Triton MoE kernel drop in to unblock compile + beat eager on the
A100? NO.** The find_spec break is fixable by a transformers bump, not a kernel; and
every fast grouped-expert kernel (torch's tuned path, `sonicmoe`, the torchtitan
persistent kernel) is **Hopper-SM90-gated** — the A100 sits on the cuBLAS-per-group
fallback by *hardware*, which is already near the SM80 ceiling for our fine-grained
128-expert/top-8 small-group shapes. **The forward is MoE-bound (~85-92%), not
attention-bound. The real, free, A100-today lever is §3: over-commit
(`entropy_bound` high) + the free co-located oracle's renoise correction — the
buzzsaw's unique speed×quality move that an AR model structurally cannot make.**

---

## Sources

- transformers MoE: `reference-code/transformers/src/transformers/integrations/moe.py` (`_can_use_grouped_mm:266-308`, SM-cap `:297-304`, find_spec-patch `:34-38`, fallback `:185-208`, `grouped_mm_experts_forward:380-481`, S=`:391`, custom_op pattern `:251-263`)
- transformers sonicmoe (SM90 gate, layout match): `reference-code/transformers/src/transformers/integrations/sonicmoe.py:62-68, 100, 173-176`
- DiffusionGemma config/modeling: `models/diffusion_gemma/configuration_diffusion_gemma.py:85-121, 152, 196`; `modeling_diffusion_gemma.py:514-526, 625, 700` (router top-8, MoE flatten)
- Sampler/decode seams: `generation_diffusion_gemma.py` `EntropyBoundSampler:339-465` (accept `:402-444`, bound default `:225`), early-stop `:1056-1065`, step loop `:757`
- Worker: `tmp/flash-diffgemma/gpu_worker.py:35-41` (KV cache), `:90-103` (sdpa load), `:106-153` (free oracle sidecar), `:156+` (`_gen_overrides`), `:1253-1265` (compile knob), `:1279` (tokens_per_forward)
- vLLM fused_moe (coupled, not standalone): <https://github.com/vllm-project/vllm/discussions/3888>, <https://docs.vllm.ai/en/latest/design/moe_kernel_features/>
- PyTorch persistent cache-aware grouped GEMM (Hopper/TMA, 1.42-2.62×, torchtitan): <https://pytorch.org/blog/accelerating-moes-with-a-triton-persistent-cache-aware-grouped-gemm-kernel/>
- Megablocks grouped GEMM (block-sparse, variable groups): <https://github.com/databricks/megablocks>
- SGLang MoE align&sort: <https://huggingface.co/blog/yiakwy-xpu-team/efficient-moe-align-sort-design-for-sglang>
- FlashAttention A100/SM80 (FA2 yes, FA3 no; sliding+bidirectional): <https://github.com/Dao-AILab/flash-attention>, <https://github.com/Dao-AILab/flash-attention/issues/1481>
- Speculative decoding for dLLMs: DFlash <https://www.lmsys.org/blog/2026-06-15-next-generation-speculative-decoding-dflash-v2/> / <https://arxiv.org/pdf/2602.06036>; Self-Speculative (SSD) <https://arxiv.org/abs/2510.04147>; Trajectory-Level <https://icml.cc/virtual/2026/poster/61516>
- DiffusionGemma 26B-A4B 128-expert/top-8: <https://recipes.vllm.ai/Google/diffusiongemma-26B-A4B-it>, model card <https://huggingface.co/google/diffusiongemma-26B-A4B-it>
</content>
</invoke>
