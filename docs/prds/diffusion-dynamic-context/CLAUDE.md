---
type: orchestrator
status: active
tags: [orchestrator, agent, web]
---

# Diffusion dynamic-context — auto-loaded context (one-stop shop)

> Everything to get up to speed on this PRD: current state, HOW TO RUN it, open
> issues, next steps, and links to the depth. The thesis: a diffusion LLM
> (DiffusionGemma) as a LIVE-CONTEXT interface — a buzzsaw that refines whole
> blocks of Clojure fast, taking feedback BETWEEN denoise steps, with Seon's
> parser+eval+retrieval as the control signal. Keep this tight + current.

## Current state (2026-06-28)

**The model is PROVEN running** — DiffusionGemma loaded in 66s on a live A100
(endpoint `kzonsp5b18hpq5`), first generate ran at 137 tok/s with
`tokens_per_forward: [4]`. The mechanics are now **SOURCE-GROUNDED** (see
`research/model-mechanics-grounding-2026-06-28.md`): NOT an absorbing-mask
diffuser — `EntropyBoundSampler` random-inits the canvas and re-noises
non-accepted positions with random vocab ids; "commit" is emergent low-entropy
persistence. The clamp primitive is a custom `LogitsProcessor`, not a MASK hole.

The worker has been **rebuilt on the corrected mechanism** (ready to test on the
warm worker, NOT yet deployed): a `ClampLogitsProcessor` + a streamer-based
per-step trace + new `clamp_smoke` / `infill` modes, `sdpa` attention (was eager),
denoise-step + per-step-commit measurement, and tuning knobs
(`max_denoising_steps`, `entropy_bound`, …). The mask-based staged stubs were
DELETED (disproven premise); the corrected renoise/retrieval design lives in the
grounding doc §4/§5.

## How to run it

Worker lives in gitignored `tmp/flash-diffgemma/` (Python `@Endpoint` + `client.py`
driver). Keys in `.env` (`RUNPOD_API_KEY`, `HF_TOKEN`). `.venv` = python3.12.

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a                     # load keys

# DEPLOY (FLASH_GPU_IMAGE is structural → recycles workers, endpoint id preserved)
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v1
.venv/bin/flash deploy                        # bundles the single diffgemma endpoint
                                              #   (gpu_worker.py + diffgemma_common.py)

# DRIVE a run (modes: probe | introspect | generate | clamp_smoke | infill)
export DIFFGEMMA_EP=kzonsp5b18hpq5            # from deploy output
python -u client.py '{"mode":"probe"}'                                   # cheap: imports+config, no 50GB load
python -u client.py '{"mode":"introspect"}'                              # reflect live model (mask-free: output fields, sampler, gen-config, think-markers)
python -u client.py '{"mode":"generate","prompt":"...","max_new_tokens":256,"trace":"canvas"}'  # generate + denoise_steps/commit trajectory

# THE DECISIVE TEST — does the LogitsProcessor clamp hold positions fixed?
# Clamps ~3 canvas positions to chosen token ids; asserts they survive denoising
# (out.sequences[0][nprompt+pos] == forced id) while the rest denoise. If all_held
# is true, the whole slotted-gen / infill / eval-renoise primitive is PROVEN.
python -u client.py '{"mode":"clamp_smoke","trace":"canvas"}'            # defaults: pos 5/40/100 -> "hello"/"world"/"diffusion"
python -u client.py '{"mode":"clamp_smoke","clamp_text":{"5":"hello","40":"world"},"trace":"entropy"}'

# INFILL — clamp a prefix + suffix, let the middle denoise (reuses the clamp)
python -u client.py '{"mode":"infill","prefix":"(defn mean [xs] (/ ","suffix":" (count xs)))","max_hole_tokens":16,"expect_contains":"(reduce + xs)"}'

# TUNING KNOBS (optional, any generate mode — A/B without redeploying logic):
#   max_denoising_steps (int)  — the step CAP (loop bound)
#   entropy_bound (float, dflt 0.1) — HIGHER => more tokens accepted per forward
#   t_min / t_max (float)      — temperature schedule
#   stability_threshold (int) + confidence_threshold (float) — early-stop (pass BOTH)
python -u client.py '{"mode":"generate","prompt":"...","entropy_bound":0.3,"max_denoising_steps":64,"trace":"entropy"}'

# RESULT FIELDS to read: attn_impl (sdpa|eager), denoise_steps, committed_per_step
#   (full per-step list), tokens_per_forward (model's own metric), gen_s, tok_per_s.

# COST / is it billing? (running>0 = executing; workersMin=0 = $0 when idle)
curl -s https://api.runpod.ai/v2/$DIFFGEMMA_EP/health -H "Authorization: Bearer $RUNPOD_API_KEY"

# REBUILD/PUSH the custom image (stops at push; needs docker login)
REGISTRY=docker.io/seantempesta TAG=cu128-v1 ./build-image.sh
```

- **Scale-to-zero now** (`workers=(0,1)` in `gpu_worker.py`): $0 when idle, ~66s
  cold reload. **Keep-warm** for fast iteration: set min worker to 1 in the
  `@Endpoint` + redeploy (continuous A100 ~$1.19/hr — owner's call once iterating).
- Cold start = provision A100 + pull the 15GB image + load 50GB model (cached on
  the NetworkVolume after first load). `.flashignore` is DEAD in Flash v1.17 — use
  `.gitignore`.

## Current issues / blockers

- **Output-object bug — FIXED** (`out.sequences`, grounded). Mechanics GROUNDED.
- **NEXT (on the warm worker, not yet run):** (1) `clamp_smoke` — the decisive
  proof the LogitsProcessor clamp holds positions; (2) confirm `sdpa` loaded +
  re-measure tok/s (eager suspected for the 137 tok/s); (3) sweep `entropy_bound`
  / `max_denoising_steps` against `tokens_per_forward` to find the A100 ceiling.
- **Slow first gen (137 tok/s, tokens_per_forward=[4]):** suspects = eager attn
  (now switched to sdpa) + a low `entropy_bound` (only ~4 tokens accepted/forward).
  The new `denoise_steps` + `committed_per_step` trace + the knobs exist to diagnose.
- **`FLASH_GPU_IMAGE` didn't take effect** (worker ran stock torch 2.9.1) —
  harmless now (stock works), but MUST be solved for the Seon-co-location image.

## Settled — do NOT re-litigate

- **torch 2.9.1 (stock base) WORKS** — the ~12-cycle "broken torch" saga was a
  hallucinated `setup_compilation_env` symbol + the worker's runtime shims.
  Custom image NOT needed for torch.
- **Custom image KEPT** for Seon co-location (run parse/eval/retrieve ON the
  worker → local feedback loop, not an internet round-trip).
- **A100-80 BF16** (NOT 5090/NVFP4 — BF16 = confound-free entropy dynamics; the
  test-plan's "rent a 5090" hardware section is SUPERSEDED).
- **Route A** (per-step round-trip) for live feedback — Flash has no
  input-into-a-running-job surface.
- Token dynamics: COMMIT/LOCK low-entropy tokens + re-noise the rest (~7.5
  re-mask/gen) — NOT whole-canvas refinement every step.
- The parser-as-oracle is the feedback signal, fully measured: 92.7% detect /
  100% safe-recover / 93.5% combined-with-eval; two AR A/B nulls → value is on
  NOISY diffusion gen. `:span`/`:error-kind` = re-noise dial; program-graph =
  retrieval trigger.

## Plans + next steps (ordered)

1. **Ground the model mechanics** (research agent, IN PROGRESS) — canvas dynamics,
   the real `generate()` API + output object, slotted-gen viability, think-token.
2. **Fix the output-object bug** — grounded by (1), not guessed.
3. **First real generate** → coherence of the Clojure + tok/s (the milestone).
4. **Introspect** → resolve U/V/W unknowns + the think-token question, one warm window.
5. **Capability ladder (T0–T5):** #1 infill (**T2 = first kill gate**: must beat
   suffix-blind AR) → #2 eval-renoise (`:span`/`:error-kind` dial) → #3 retrieval
   (program-graph trigger) → #4 live feedback (Route A).
6. **Owner's experiments** (may leapfrog): slotted guided-generation (clamped
   scaffold + retrieval-injected slots + masked reasoning) + thinking-canvas
   (denoise-steps as a compute budget; scratchpad stripped after).
7. **Once iterating:** keep-warm endpoint + the **Seon co-location image** (the
   latency play — bundle parse/eval/retrieve onto the worker).

## Entry points (depth)

- [[index]] — push-ready state, env-fix recipe, deploy mechanics.
- [[thesis-capstone-2026-06-28]] — the measured oracle + first-light GO/NO-GO.
- [[first-light-runbook-2026-06-28]] — deploy + the one-warm-window sequence.
- [[custom-image-and-seon-colocation-2026-06-28]] — the torch finding + co-location play.
- [[research/parser-as-generation-oracle-2026-06-28]] — the measured 3-tier oracle.
- [[research/model-mechanics-grounding-2026-06-28]] — real model behavior (in progress).
- 4 capability plans: `research/{infill,eval-renoise,retrieval-denoising,live-feedback}-experiment-plan-2026-06-28`.
- [[infra-flash-runpod]] — the full deploy/debugging log (12+ issues).
