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
(endpoint `kzonsp5b18hpq5`) and `generate()` ran. We are **HOLDING all
experiments** while a research agent grounds the REAL model mechanics (token
commit/lock dynamics, the `generate()` output object, the slotted-guided-gen
viability) — we hit a bug from coding against ASSUMPTIONS. The 4 capability plans
+ unified worker + runbook are prepped and execute-ready.

## How to run it

Worker lives in gitignored `tmp/flash-diffgemma/` (Python `@Endpoint` + `client.py`
driver). Keys in `.env` (`RUNPOD_API_KEY`, `HF_TOKEN`). `.venv` = python3.12.

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a                     # load keys

# DEPLOY (FLASH_GPU_IMAGE is structural → recycles workers, endpoint id preserved)
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v1
.venv/bin/flash deploy                        # bundles ONLY the diffgemma endpoint
                                              #   (staged/ stubs excluded via .gitignore)

# DRIVE a run (modes: probe | generate | introspect) — /run + poll, async
export DIFFGEMMA_EP=kzonsp5b18hpq5            # from deploy output
python -u client.py '{"mode":"probe"}'                                   # cheap: imports+config, no 50GB load
python -u client.py '{"mode":"generate","prompt":"...","max_new_tokens":256}'  # loads model + generates
python -u client.py '{"mode":"introspect"}'                              # reflect over live model (U/V/W probes)

# COST / is it billing? (running>0 = executing; workersMin=0 = $0 when idle)
curl -s https://api.runpod.ai/v2/$DIFFGEMMA_EP/health -H "Authorization: Bearer $RUNPOD_API_KEY"

# DEPLOY A CAPABILITY STUB later (after introspect confirms the API)
mv staged/gpu_worker_infill.py . && .venv/bin/flash build && .venv/bin/flash deploy

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

- **Output-object bug (HELD):** `generate()` returns a `DiffusionGemmaGenerationOutput`,
  not a tensor (`gpu_worker.py:256/258` assume `.shape`/indexing). Fix held until
  the mechanics agent confirms the correct attribute.
- **Mechanics unconfirmed:** token commit/lock + clamp-fixed-across-steps (the
  primitive every capability needs) being grounded — tests held until then.
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
