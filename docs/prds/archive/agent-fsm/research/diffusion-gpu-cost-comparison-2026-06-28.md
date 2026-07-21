---
type: research
status: draft
tags: [research, agent]
---

# GPU cost comparison — DiffusionGemma for Seon (100–250k ctx + experiments)

> Companion to [[diffusion-llm-test-plan-2026-06-27]] / [[diffusion-llm-runpod-runbook-2026-06-27]].
> Decision: which GPU to rent, and via which billing model, given the requirement
> shifted to **≥100k (ideally 250k) context** + the **`accept_canvas` experiments**.

## Requirement → which GPUs even qualify

- **Long context (≥100k):** needs Flash Attention, which is **auto-disabled on
  Blackwell (5090, SM120)** in llama.cpp today → 5090 caps ~10k now. FA works on
  Ampere/Hopper. So 100–250k ⇒ an **80 GB-class card**.
- **`accept_canvas` hook experiments:** run in **transformers, BF16 ≈ 50 GB** →
  again an **80 GB card** (a 48 GB card forces quantization, complicating the hook).
- **Net:** the 5090/32 GB and 48 GB cards (L40S/A6000) **don't meet the full need**.
  Real candidates: **A100-80, H100-80, H200-141.**

## Price table (per-hour, mid-2026; verify live before renting)

| GPU | VRAM | Meets need? | Vast pod | RunPod pod | RunPod **serverless** (Flash) |
|---|---|---|---|---|---|
| **A100 80GB** | 80 | ✅ 100–250k | **~$0.78** | ~$1.49 | **~$1.19** (est.) |
| **H100 80GB** | 80 | ✅ + ~2–3× faster | ~$1.38 | ~$2.89–3.29 | **~$1.91** (confirmed) |
| **H200 141GB** | 141 | ✅✅ 250k + big batch | ~$2.5–3 | ~$4.39 | ~$3.99 (est.) |
| L40S 48GB | 48 | ⚠️ quant-only | ~$0.40 | ~$0.79 | ~$0.79 | 
| RTX 5090 32GB | 32 | ❌ ~10k ctx | ~$0.43 | ~$0.69 | ~$0.69 |

Confirmed from sources: H100 serverless ≈ $1.91/hr; Vast A100-80 from $0.78, H100
from $1.38; RunPod pod A100 $1.49 / H100 $2.89–3.29. A100/H200 serverless are
estimates — check runpod.io/pricing.

## The real cost driver: usage pattern, not hourly rate

Our work is **bursty** — write code locally, fire a run, read results, edit,
repeat — NOT a 24/7 service. Two billing models:

- **Flash serverless (RunPod)** — scale-to-zero, **pay per second of actual
  compute**. Between runs you pay $0. Cost ≈ (real GPU-seconds) × rate. Caveats:
  cold start reloads the model (~30–90 s from a network volume) unless you keep a
  warm worker (`workers=(1,N)` → bills continuously like a pod during a session);
  **serverless is EU-RO-1 only**; 500 MB deploy limit (exclude torch).
- **Hourly pod (Vast cheapest / RunPod)** — model stays resident, **pay
  wall-clock** including your thinking/idle time. Best for a focused multi-hour
  interactive session; you must remember to **stop it** or it idle-bills.

For bursty iteration, **serverless usually wins total cost even at a higher hourly
rate**, because a left-running pod bills the (large) idle gaps. For a 3-hour
heads-down session, a Vast pod at the low hourly rate wins.

## Scenario: ~20 GPU-hours of real compute to a result

| Option | Math | Total |
|---|---|---|
| A100-80 **Flash serverless** | 20 compute-hr × ~$1.19 | **~$24** (no idle) |
| A100-80 **Vast pod** | ~30–40 wall-hr × $0.78 (incl. idle) | ~$23–31 |
| H100-80 **Flash serverless** | 20 × $1.91 (but fewer hrs — ~2× faster) | ~$20–38 |
| H100-80 **Vast pod** | ~30 wall-hr × $1.38 | ~$41 |

All comfortably inside the **$50** loaded. ~25–40 A100-hours either way.

## Recommendation

1. **Experiments (bursty, iterative) → A100-80GB on RunPod Flash serverless.**
   Scale-to-zero fits how we work; ~$1.19/hr of *actual* compute; the user's tool
   (Flash) targets exactly this. Use a **NetworkVolume** to cache the ~50 GB model
   so cold starts only reload (not re-download). `GpuType.NVIDIA_A100_80GB_PCIe`.
2. **Focused multi-hour live-drive session → Vast A100-80 pod ($0.78/hr)** if we
   want the cheapest wall-clock and don't mind SSH + remembering to stop it.
3. **H100** only when speed is the bottleneck (it's ~2–3× faster; ~60% more $).
4. **H200** only if we truly need 250k context with large batches.
5. **5090 / llama.cpp GGUF** stays useful as the **cheap coherence/speed smoke
   test** (Q4_K_M, ~250 tok/s) — just not for 100k+ context.

Net: **start on A100-80 via Flash serverless.** It's the value pick that meets
both the context and the experiment requirements, bursty-friendly, and well within
budget.

## Sources

- [RunPod pricing](https://www.runpod.io/pricing) · [RunPod serverless pricing](https://docs.runpod.io/serverless/pricing)
- [Vast.ai pricing](https://vast.ai/pricing)
- [Spheron GPU pricing 2026](https://www.spheron.network/blog/gpu-cloud-pricing-comparison-2026/)
- [H100 rental comparison](https://intuitionlabs.ai/articles/h100-rental-prices-cloud-comparison)
</content>
