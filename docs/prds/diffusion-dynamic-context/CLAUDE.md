---
type: orchestrator
status: active
tags: [orchestrator, agent, web]
---

# Diffusion dynamic-context — auto-loaded context

> The orientation for anyone working this PRD. Tight + current; depth lives in the
> dated files linked at the bottom. The thesis: a diffusion LLM (DiffusionGemma)
> as a LIVE-CONTEXT interface — a buzzsaw that refines whole blocks of Clojure
> fast, taking feedback BETWEEN denoise steps, with Seon's parser+eval+retrieval
> as the control signal.

## Current state (2026-06-28)

**The model is PROVEN running** — DiffusionGemma loaded in 66s on a live A100
(endpoint `kzonsp5b18hpq5`) and `generate()` ran. We are **HOLDING all
experiments** while a research agent grounds the REAL model mechanics
(canvas commit/lock dynamics, the `generate()` output object, the
slotted-guided-gen viability) — because we hit a bug from coding against
ASSUMPTIONS. One trivial worker bug remains: `generate()` returns a
`DiffusionGemmaGenerationOutput` object, not a tensor (`gpu_worker.py:256/258`) —
fix held until mechanics are confirmed.

The 4 capability plans + unified worker + runbook are all prepped (execute-ready).

## Load-bearing findings + gotchas (cost cycles to learn)

- **torch 2.9.1 (the STOCK runpod/flash base) WORKS** — loads + generates. The
  ~12-cycle "broken torch" saga was a HALLUCINATED smoke-test symbol
  (`setup_compilation_env` doesn't exist in any torch 2.9.x — it's the private
  `_set_compilation_env`) + the worker's own runtime shims. **Custom image NOT
  needed for torch.** (`FLASH_GPU_IMAGE` didn't even take effect — worker ran
  stock 2.9.1.)
- **The custom image is KEPT for a different reason** — bundle Seon's
  parse-forms + SCI eval + retrieval ONTO the worker so the #2/#3 feedback loop
  is LOCAL on the A100, not an internet round-trip ([[custom-image-and-seon-colocation-2026-06-28]]).
- **Token dynamics:** the model COMMITS/LOCKS low-entropy tokens and re-noises
  the rest each step — it does NOT refine the whole 256-canvas every step
  (~7.5 positions re-mask/gen). The clamp-positions-fixed-across-steps primitive
  is what every capability depends on — being confirmed by the grounding agent.
- **Flash deploy:** `.flashignore` is DEAD in v1.17 (use `.gitignore`); the 3
  capability stubs each declare their own `@Endpoint`, so they live in
  `staged/` to stay OUT of the first-light bundle (only `diffgemma` deploys).
- **The parser-as-oracle is the feedback signal**, fully measured: 92.7%
  syntactic detection, 100% safe-class auto-recovery, 93.5% combined with eval.
  Two AR-model A/B nulls (gemini + DeepSeek) → the value is on NOISY diffusion
  generation, not capable AR. `:span`/`:error-kind` = the re-noise granularity
  dial; program-graph membership = the retrieval trigger.

## Settled — do NOT re-litigate

- **A100-80 BF16** (NOT 5090/NVFP4 — BF16 = confound-free entropy dynamics; the
  test-plan's "rent a 5090" hardware section is SUPERSEDED).
- **Route A** (per-step round-trip) for live feedback — Flash serverless has no
  input-into-a-running-job surface (X1 resolved from SDK source).
- **Scale-to-zero** for now; switch to **keep-warm (min-1 worker)** once we're
  iterating experiments (owner's call — proven-working unlocks it).
- Endpoint `kzonsp5b18hpq5`; image `docker.io/seantempesta/diffgemma-worker:cu128-v1`;
  worker lives in gitignored `tmp/flash-diffgemma/` (drive via `client.py`).

## Open / hot ideas

- **Owner's slotted guided-generation idea** — a structured canvas: clamped
  scaffold + retrieval-INJECTED spec slots + masked reasoning/answer slots, all
  co-conditioning via bidirectional attention. Viability being confirmed by the
  mechanics agent; may be the highest-leverage experiment.
- **Thinking-canvas** — denoise-steps as a thinking budget (compute without more
  tokens) + scratchpad regions stripped after. Does the `-it` model have a
  think-token? (introspect will check.)

## Entry points (depth)

- [[index]] — push-ready state, the env-fix recipe, deploy mechanics.
- [[thesis-capstone-2026-06-28]] — the measured oracle + first-light GO/NO-GO.
- [[first-light-runbook-2026-06-28]] — deploy + the one-warm-window sequence.
- [[research/parser-as-generation-oracle-2026-06-28]] — the measured 3-tier oracle.
- [[research/model-mechanics-grounding-2026-06-28]] — real model behavior (in progress).
- The 4 capability plans: `research/{infill,eval-renoise,retrieval-denoising,live-feedback}-experiment-plan-2026-06-28`.
- [[infra-flash-runpod]] — the full deploy/debugging log.
