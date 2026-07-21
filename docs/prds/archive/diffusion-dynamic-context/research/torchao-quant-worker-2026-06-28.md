---
type: research
status: active
tags: [research, agent]
---

# torchao quantization for the DiffusionGemma worker — grounded spec (INT8 on A100, keep the :1034 control seam)

## TL;DR

- **The canonical INT8-on-A100 config is `Int8DynamicActivationInt8WeightConfig()`**
  wrapped in `TorchAoConfig(quant_type=...)` and passed as `quantization_config=`
  to `from_pretrained`. The HF torchao doc explicitly recommends this config **for
  A100** (FP8 configs are the H100 path; the A100 has no FP8 tensor cores). It is
  applied at load, only swaps **weights** of quantizable layers, and is
  **transparent to `generate()`** — it never touches the sampler / `LogitsProcessor`
  / logits path, so the `:1034` per-step clamp seam is preserved. **Verified against
  source**, not the roadmap's assertion (see "Transparency proof").

- **BUT the big win does not arrive on THIS model via the stock path.** The
  transformers torchao quantizer only quantizes `nn.Linear` (optionally
  `nn.Embedding`) — `_QUANTIZABLE = [torch.nn.Linear]`
  (`quantizer_torchao.py:129`, gate at `:147`). DiffusionGemma stores its MoE
  experts as **fused 3D `nn.Parameter`s** (`gate_up_proj`, `down_proj`), NOT
  `nn.Linear` (`modeling_diffusion_gemma.py:530-562`). **The experts are skipped.**
  In a 26B-A4B model the experts are the overwhelming majority of the weights, so
  INT8 via `from_pretrained` quantizes only attention + router + shared MLP + (by
  default lm_head is excluded) — a minority of the model. Expect **small VRAM
  savings and small/negligible speedup**, not the 2x you'd hope for.

- **The router IS an `nn.Linear`** (`modeling_diffusion_gemma.py:501`) and WILL be
  quantized by default — exactly the layer MoE-quant literature says to keep in
  full precision (router perturbation misroutes tokens). **`modules_to_not_convert`
  must include the router.** lm_head is already auto-excluded
  (`base.py:58` `get_keys_to_not_convert`).

- **Honest verdict:** on this fused-expert 26B-A4B diffusion MoE, the *minimal*
  TorchAoConfig path is **low-upside and non-zero-risk** — worth a 30-minute A/B
  only to measure, but do NOT expect it to be the speed lever. The real lever
  (quantizing the 3D expert tensors) needs torchao's **MoE 3D-tensor-subclass /
  `FqnToConfig`** path, which is **prototype and NOT wired through DiffusionGemma's
  `from_pretrained`** — flagged below as the unproven combination. Riskiest
  assumption to test first: **"do the experts actually quantize?"** — measure VRAM
  delta; if it barely drops, the experts were skipped and the experiment is moot.

---

## 1. The grounded canonical usage (INT8 on A100)

### 1a. The config class + args (cited)

`TorchAoConfig` constructor (`transformers/utils/quantization_config.py:1452`):

```python
TorchAoConfig(
    quant_type,                          # an AOBaseConfig instance (REQUIRED, positional)
    modules_to_not_convert=None,         # list[str] of module-name fragments to skip
    include_input_output_embeddings=False,
    untie_embedding_weights=False,
)
```

`quant_type` must be an `AOBaseConfig` **instance**, not a string — string types
were removed (`:1472-1478` raises). The docstring's own example
(`:1437-1442`) uses `Int4WeightOnlyConfig(group_size=32)`; the class enumerates the
valid INT/FP8 configs at `:1421-1423`:
`Int4WeightOnlyConfig`, `Int8WeightOnlyConfig`,
`Int8DynamicActivationInt8WeightConfig`, `Float8WeightOnlyConfig`.

The **HF torchao doc** ([transformers/.../torchao.md](https://github.com/huggingface/transformers/blob/main/docs/source/en/quantization/torchao.md))
gives the canonical from_pretrained form and the A100 recommendation verbatim:

```python
from transformers import TorchAoConfig, AutoModelForCausalLM
from torchao.quantization import Int8DynamicActivationInt8WeightConfig

quant_config = Int8DynamicActivationInt8WeightConfig()
quantization_config = TorchAoConfig(quant_type=quant_config)

quantized_model = AutoModelForCausalLM.from_pretrained(
    model_id, dtype="auto", device_map="auto",
    quantization_config=quantization_config,
)
```

> The documentation recommends **`Int8DynamicActivationInt8WeightConfig` for A100
> GPUs**; for H100 it prioritizes Float8 configs. `Int8DynamicActivationInt8WeightConfig`
> = int8 dynamic symmetric **per-token activation** + int8 **per-channel weight**
> quantization of linear layers.

This matches the roadmap §5b call (INT8 on A100, FP8 on Hopper) and the A100
reality: **Ampere has INT8 tensor cores but no FP8 tensor cores**, so INT8 is the
only dtype-speed lever with hardware acceleration on this GPU.

### 1b. How it composes with `device_map="auto"`, `dtype`, compile

- **`device_map="auto"`** — supported; the quantizer's `validate_environment`
  (`quantizer_torchao.py:73-86`) only special-cases CPU/disk offload, and
  `adjust_max_memory` (`:101-104`) reserves 10% headroom for scales. Our worker
  already passes `device_map="auto"`; on a single A100-80 the whole model lands on
  cuda:0 — no offload branch taken.
- **`dtype="auto"`** — keep it. Non-quantized layers (the skipped experts, router
  if excluded, lm_head, norms) stay BF16; torchao dequantizes quantized linears to
  the compute dtype on the fly.
- **`cache_implementation="static"` / `torch.compile`** — the HF doc states INT8
  speedup is realized by **auto-compiling with `cache_implementation="static"`**
  (`output = model.generate(..., cache_implementation="static")`), and the quantizer
  declares `is_compileable = True` (`quantizer_torchao.py:158-159`). **Caveat for
  us:** DiffusionGemma's `generate()` is the **custom block-diffusion loop**, not the
  AR KV-cache loop — a static KV cache is an AR concept. Whether `static` does
  anything useful inside the diffusion denoise loop is **unverified**; INT8
  weight/activation kernels can still win without it, but the doc's headline
  speedups assume `torch.compile`. **Provide a `disable_compile` escape** (below) so
  the parse-stop experiments (`denoise_to_step` with an external
  `StepCountStopping`) are not fighting a compiled graph — `torch.compile` +
  early-exit stopping criteria interact badly.

---

## 2. Transparency proof — torchao does NOT touch the control seam

The roadmap §5b *asserts* torchao is transparent to `generate()`. **Confirmed
against source** (don't take it on faith):

- The quantizer's only model mutation before load is setting
  `modules_to_not_convert` and (optionally) un-excluding embeddings
  (`quantizer_torchao.py:106-120`). No sampler, no generation_config, no logits
  hook.
- The actual conversion (`integrations/torchao.py:57-147`, `TorchAoQuantize`) does
  exactly one thing per quantizable param: `module._parameters[tensor_name] =
  Parameter(value)` then `quantize_(module, config)` (`:77`, `:92`). It swaps a
  weight tensor for a torchao tensor-subclass. **It never references
  `logits_processor`, `streamer`, `sampler`, `EntropyBoundSampler`, or the canvas.**
- Therefore the worker's `ClampLogitsProcessor` (the `:1034` seam), the
  `EntropyBoundSampler` accept/renoise, and the temperature ramp all run unchanged.
  A quantized `lm_head` (we keep it un-quantized anyway) would still return an
  ordinary float logits tensor that the clamp processor edits in place.

**Conclusion: clamp / infill / denoise_to_step / resume_renoise are unaffected by
quantization.** The risk is purely **numerical** (does INT8 degrade output
quality / routing), never **structural** (it cannot break the control mechanism).

---

## 3. The MoE / diffusion gotchas (web-grounded)

### 3a. Fused experts are skipped — the load-bearing finding

DiffusionGemma experts (`modeling_diffusion_gemma.py:530-562`):

```python
class DiffusionGemmaTextExperts(nn.Module):
    self.gate_up_proj = nn.Parameter(torch.empty(num_experts, 2*intermediate_dim, hidden_dim))  # :538
    self.down_proj    = nn.Parameter(torch.empty(num_experts, hidden_dim, intermediate_dim))    # :539
    # forward uses nn.functional.linear(x, self.gate_up_proj[expert_idx])  (:560, :562)
```

These are **bare 3D `nn.Parameter`s on an `nn.Module`**, not `nn.Linear`
sub-modules and not named `weight`. The transformers torchao quantizer only
converts `nn.Linear` (+ optional `nn.Embedding`):

- `_QUANTIZABLE = [torch.nn.Linear]` (`quantizer_torchao.py:129`)
- `param_needs_quantization` returns `isinstance(module, _QUANTIZABLE) and
  tensor_name == "weight"` (`:147`)

So **every expert is skipped**. This is a known, general v5-era problem, not a
DiffusionGemma quirk — the move from `nn.ModuleList[nn.Linear]` (v4) to fused
`nn.Parameter` experts (v5, e.g. `Qwen3MoeExperts.gate_up_proj`) breaks the
`nn.Linear`-centric quantizers. Same root cause reported for bitsandbytes:
["Failed to quant MoE models with fused expert weights in transformers
v5"](https://github.com/bitsandbytes-foundation/bitsandbytes/issues/1849).

**Why it matters for 26B-A4B:** "A4B" = ~4B active of ~26B total; the gap is
expert weights. The experts are the **bulk** of the parameter mass and the bulk of
the bytes read per diffusion forward (each forward processes the whole 256-pos
canvas → many experts hit). Quantizing only attention/router/shared-MLP leaves the
dominant cost in BF16 → **small memory savings, small/negligible speedup.**

### 3b. The router must be preserved (`modules_to_not_convert`)

The router IS an `nn.Linear` (`modeling_diffusion_gemma.py:501`,
`self.proj = nn.Linear(hidden_size, num_experts)`) → it WILL be quantized by
default. MoE-quant literature is consistent that this is the one layer to keep in
full precision:

> "The router's expert selection mechanism is highly sensitive to
> quantization-induced logit perturbations… even minor deviations in gate scores
> can disrupt the Top-K expert assignment, degrading performance via misrouted
> tokens." … "the router accounts for <0.03% of parameters yet is crucial; this
> work retains the router at original precision."
> — MoE quantization survey findings ([MoEQuant arXiv:2505.03804](https://arxiv.org/pdf/2505.03804),
> [Pangu Pro MoE arXiv:2505.21411](https://arxiv.org/pdf/2505.21411))

→ **`modules_to_not_convert=["router"]`** (the module name is `router`, see
`:590`/`:665`). lm_head is already auto-excluded by
`get_keys_to_not_convert` (`base.py:58` — tied/last/output-embedding keys), which
is what we want for a clean logits path; keep `include_input_output_embeddings`
at its default `False`.

### 3c. The MoE-aware path exists in torchao but is NOT wired here

torchao itself does support MoE quantization — per the
[pytorch/ao README](https://github.com/pytorch/ao): two methods, (1) enhance the
quantized tensor subclass to handle 3D MoE expert tensors with indexing/slicing,
(2) a subclass simulating a 3D quantized param as a sequence of 2D slices. And the
transformers quantizer DOES have an `FqnToConfig` branch that can target a param by
fully-qualified name and **bypass the `isinstance(nn.Linear)` gate** on an explicit
(non-`_default`) match (`quantizer_torchao.py:135-145`; convert handles
`FqnToConfig` regex at `integrations/torchao.py:105-146`). The quantizer's own
metadata example even names `"...experts.gate_up_proj"` (`:181-182`) — proof the
fused-expert FQN path is contemplated.

**But:** wiring `FqnToConfig({"re:.*experts.(gate_up|down)_proj": Int8...})` to
quantize 3D expert Parameters on DiffusionGemma is **prototype + unproven for this
model** — it depends on torchao's 3D-tensor-subclass handling the
`nn.functional.linear(x, gate_up_proj[expert_idx])` indexing in the custom expert
forward (`:560`). This is the genuinely uncharted combination. **Do NOT ship it
blind.** Treat it as a follow-up experiment only if 3a's measurement proves the
experts are the bottleneck and the simple path leaves them BF16.

### 3d. Diffusion-specific reports

No public reports found of torchao applied to a **block-diffusion** LLM (this model
family is new; the only serving reference is the vLLM DiffusionGemma blog, which
uses its own sealed sampler — see §5b roadmap). So the diffusion × torchao
combination is **first-of-kind here** — another reason to measure correctness
(parse-rate / clamp-held / output sanity), not assume it.

---

## 4. The MINIMAL worker diff to TEST

A `quant` payload knob → an `AOBaseConfig`, plus a `disable_compile` escape. Only
`_load` and the `gen` static-cache choice change. **The owner applies + deploys +
measures** — do not edit the live worker from here.

In `gpu_worker.py`, replace the body of `_load` (currently `gpu_worker.py:41-65`)
with a quant-aware version, and pass the payload through:

```python
def _quant_config(spec):
    """spec: None | "int8dq" | "int8wo".  -> a TorchAoConfig or None.
    int8dq = Int8DynamicActivationInt8WeightConfig (the A100-recommended config);
    int8wo = Int8WeightOnlyConfig (weight-only — smaller activation overhead,
    bigger numeric drift). modules_to_not_convert=["router"] ALWAYS: the MoE router
    is an nn.Linear (modeling_diffusion_gemma.py:501) and must stay full-precision
    (top-k routing is quant-sensitive). lm_head is auto-excluded by transformers."""
    if not spec:
        return None
    from transformers import TorchAoConfig
    from torchao.quantization import (
        Int8DynamicActivationInt8WeightConfig, Int8WeightOnlyConfig)
    qt = {"int8dq": Int8DynamicActivationInt8WeightConfig,
          "int8wo": Int8WeightOnlyConfig}[spec]()
    return TorchAoConfig(quant_type=qt, modules_to_not_convert=["router"])


def _load(tok, quant=None):
    import time
    from transformers import AutoTokenizer, DiffusionGemmaForBlockDiffusion
    t0 = time.time()
    key = f"model:{quant or 'bf16'}"
    if key not in _CACHE:                         # cache PER quant variant
        _CACHE["tok"] = AutoTokenizer.from_pretrained(MID, token=tok)
        qcfg = _quant_config(quant)
        common = dict(dtype="auto", device_map="auto", token=tok)
        if qcfg is not None:
            common["quantization_config"] = qcfg
        attn, err = "sdpa", None
        try:
            _CACHE[key] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, attn_implementation="sdpa", **common)
        except Exception as e:
            attn, err = "eager", f"{type(e).__name__}: {e}"[:200]
            _CACHE[key] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, attn_implementation="eager", **common)
        _CACHE["attn_impl"], _CACHE["attn_fallback_err"] = attn, err
        _CACHE["model"] = _CACHE[key]             # keep "model" pointing at the live one
        _CACHE["quant"] = quant or "bf16"
    return _CACHE["tok"], _CACHE[key], round(time.time() - t0, 1)
```

Then thread the knob + report it. At each `_load(tok)` call site, pass
`_load(tok, payload.get("quant"))`, and add to the result `info`:

```python
info["quant"] = payload.get("quant") or "bf16"
info["vram_alloc_gb"] = round(torch.cuda.memory_allocated(0) / 1e9, 1)  # measured footprint
```

**`disable_compile` escape** for the parse-stop experiments — in the `generate`
kwargs builder (the `gen_kwargs = dict(...)` sites), gate the static cache:

```python
if payload.get("quant") and not payload.get("disable_compile"):
    gen_kwargs["cache_implementation"] = "static"   # the doc's INT8 speed path
# disable_compile=True (default for denoise_to_step/resume_renoise) => no static cache,
# no torch.compile graph fighting the external StepCountStopping.
```

Drive it:

```bash
# baseline (today)
python -u client.py '{"mode":"generate","prompt":"...","max_new_tokens":256}'
# INT8 dynamic-act (A100 path) — SAME prompt, compare gen_s + vram_alloc_gb + text
python -u client.py '{"mode":"generate","quant":"int8dq","prompt":"...","max_new_tokens":256}'
# INT8 weight-only variant
python -u client.py '{"mode":"generate","quant":"int8wo","prompt":"..."}'
# control still holds under quant (the decisive structural check)
python -u client.py '{"mode":"clamp_smoke","quant":"int8dq","trace":"canvas"}'
```

Note: caching per-variant means the warm worker holds **two ~50GB models** if you
A/B in one session — on an 80GB A100 that won't fit. Either (a) `flash restart`
between variants, or (b) evict: `_CACHE.clear(); torch.cuda.empty_cache()` keyed on
a `payload.get("reload")` flag. Simplest for the owner: **one variant per warm
worker**, redeploy/restart to switch (the worker_sha/verify_fresh discipline
already in place).

---

## 5. Measure-on-deploy plan + riskiest assumption

**Riskiest assumption, test FIRST: "do the experts actually get quantized?"**
This single number decides the whole effort. Measure `vram_alloc_gb` BF16 vs
`int8dq`:

- BF16 ≈ 50 GB. If INT8 drops it to **~30 GB** (most of 26B → int8), the experts
  WERE quantized (unexpected — would mean the FQN path caught them) → proceed to
  speed/quality.
- If it drops only to **~44-46 GB** (only attention/router/shared-MLP shrank), the
  **experts were skipped** (the expected outcome from §3a) → the simple path is a
  dead end for speed; the experiment's verdict is "needs the MoE 3D path" and you
  stop here.

**Scorecard (scenario × git-sha, per the PRD discipline):**

| metric | how | watch for |
|---|---|---|
| `vram_alloc_gb` | `torch.cuda.memory_allocated` in result | the §5 gate above |
| `gen_s` / `tok_per_s` | already in `generate` result | speedup vs 450 tok/s BF16 baseline — likely flat if experts skipped |
| correctness | run the parser oracle on the generated Clojure (the existing 92.7% parse / spec-slot gym) | INT8-induced parse-rate drop = numeric degradation |
| routing sanity | compare `router_logits` / top-k vs BF16 on a fixed prompt | router was preserved → should be ~identical; if it drifts, the exclude didn't take |
| `all_held` (clamp_smoke) | existing assertion | MUST stay True — proves the seam survives quant |

Run **once per variant at the natural checkpoint** (test-cadence economy), not per
tweak. Same fixed prompt set as the BF16 baseline so the diff is a moved number,
not an anecdote.

---

## 6. Honest verdict

**INT8 via the minimal `TorchAoConfig` path on THIS 26B-A4B fused-expert diffusion
MoE is most likely a non-win, and carries a small quality risk — but it's a cheap,
decisive measurement worth doing.**

- **Why probably not a win:** the experts (the bulk of 26B and the per-forward
  byte/FLOP cost) are fused `nn.Parameter`s that the transformers torchao quantizer
  skips (`:129`/`:147` vs `modeling:538-539`). You'd quantize a minority of the
  model → small VRAM drop, little speedup. The roadmap §5b's "transparent
  control-compatible dtype lever" is TRUE structurally, but it quietly assumed
  `nn.Linear` experts — they aren't.
- **The risk:** the router is an `nn.Linear` and quantizes by default; left
  un-excluded it can misroute tokens. The diff pins `modules_to_not_convert=
  ["router"]` to remove that risk, but the residual numeric drift on attention
  must still be checked against the parse-rate oracle.
- **What WOULD be a real win** (follow-up, not this patch): the torchao **MoE 3D
  tensor-subclass / `FqnToConfig`** path targeting `experts.gate_up_proj` /
  `experts.down_proj` — prototype, unproven on DiffusionGemma's custom expert
  forward, and the only route that touches the dominant weights. Gate it behind the
  §5 "experts skipped?" measurement.
- **The structural reassurance stands:** whatever the speed outcome, quantization
  cannot break clamp / infill / eval-renoise — torchao only swaps linear weights and
  never enters the sampler/logits path (§2). So the experiment is safe to run; the
  only question it answers is "is it worth it," and the honest prior is **probably
  not, until the MoE 3D path is wired.**

### Sources

- [HF transformers torchao quantization doc](https://github.com/huggingface/transformers/blob/main/docs/source/en/quantization/torchao.md)
  — canonical from_pretrained, A100 → `Int8DynamicActivationInt8WeightConfig`, H100 → FP8, `cache_implementation="static"` for speedup.
- [pytorch/ao README](https://github.com/pytorch/ao) — INT8/INT4/FP8 configs, MoE two-method 3D-tensor quantization, Ampere+ requirement.
- [bitsandbytes #1849 — fused MoE experts (nn.Parameter) skipped in transformers v5](https://github.com/bitsandbytes-foundation/bitsandbytes/issues/1849)
- [MoEQuant arXiv:2505.03804](https://arxiv.org/pdf/2505.03804) / [Pangu Pro MoE arXiv:2505.21411](https://arxiv.org/pdf/2505.21411) — router/gate quant-sensitivity, keep router full precision.
- Source (vendored `reference-code/transformers`): `quantizers/quantizer_torchao.py:129,147`; `integrations/torchao.py:57-147`; `utils/quantization_config.py:1416-1497`; `quantizers/base.py:58`; `models/diffusion_gemma/modeling_diffusion_gemma.py:501,530-562,1568`.
