---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Diffusion dynamic-context — auto-loaded index (one-stop shop)

> The buzzsaw: a diffusion LLM (DiffusionGemma) as a LIVE-CONTEXT interface that
> refines whole blocks of Clojure fast, taking feedback BETWEEN denoise steps, with
> Seon's parser+eval+retrieval as the control signal. This file is the INDEX +
> runbook; the forward-looking spine carries the depth. Keep it tight + current.

## The spine (read these first)

- [[architecture]] — the target buzzsaw: the thesis, the glossary, the control
  seam, the worker, the **mode abstraction**, the oracle loop, the staged
  convergent build, the Seon interface. Present-tense target, NO hedges.
- [[roadmap]] — the single **we-are-here** → the kill-gate-first path. What's
  PROVEN (clamp, infill, spec-slot, the 0→100% data-modeling A/B, deploy
  stability) vs NEXT (canvas-length probe → three-arm E1 → eval-renoise live → the
  `:defn-with-specs` MVP → E2–E6) + the CUT list (sentinel, op-axis, multi-pass).
- [[grounding]] — every load-bearing claim → its `reference-code/…:LINE` cite (the
  transformers v5.11.0 seams, the parser oracle, the malli→datahike bridge, the
  Flash source).
- [[owner-gpu-runbook]] — the ordered, `verify_fresh`-gated checklist to execute
  top-to-bottom on the next A100 redeploy (cheapest decisive probe first).

## Current state (2026-06-28)

The model is **PROVEN running** (A100, endpoint `kzonsp5b18hpq5`, ~66 s load) and
the **control primitives are PROVEN**: clamp holds, infill holds both ends, the
`:malli/schema` spec-slot infills, and the dynamic-context half is validated
(data-modeling A/B **0→100%**). The mechanism is **source-corrected** — NOT an
absorbing-mask diffuser; commit is emergent low-entropy persistence and clamp is a
custom `LogitsProcessor` (see [[grounding]]). The eval-renoise worker
(`denoise_to_step`/`resume_renoise`) is BUILT but UNTESTED on GPU. **NEXT** is the
canvas-length probe + the three-arm kill-gate ([[roadmap]] P0–P1).

## How to run it

Worker lives in gitignored `tmp/flash-diffgemma/` (Python `@Endpoint` + `client.py`
driver; snapshot in `flash-worker/`). Keys in `.env` (`RUNPOD_API_KEY`, `HF_TOKEN`).
`.venv` = python3.12.

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a                     # load keys

# DEPLOY — then ALWAYS verify-fresh (see "Deployment stability" below).
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v1
.venv/bin/flash deploy                        # bundles gpu_worker.py + diffgemma_common.py
python3 verify_fresh.py                        # MUST print "FRESH ✓" before any measuring

# DRIVE a run (modes: probe | introspect | generate | clamp_smoke | infill | denoise_to_step | resume_renoise)
export DIFFGEMMA_EP=kzonsp5b18hpq5            # from deploy output
python -u client.py '{"mode":"probe"}'                                   # cheap: imports+config, no 50GB load
python -u client.py '{"mode":"introspect"}'                              # reflect live model (output fields, sampler, gen-config, CANVAS_LENGTH)
python -u client.py '{"mode":"generate","prompt":"...","max_new_tokens":256,"trace":"canvas"}'

# THE PROVEN PRIMITIVES
python -u client.py '{"mode":"clamp_smoke","trace":"canvas"}'            # clamp holds positions (PROVEN)
python -u client.py '{"mode":"infill","prefix":"(defn mean [xs] (/ ","suffix":" (count xs)))","max_hole_tokens":16}'

# TUNING KNOBS (any generate mode — A/B without redeploying logic):
#   max_denoising_steps (int) — the step CAP (do NOT shrink to "checkpoint"; it compresses the temp ramp)
#   entropy_bound (float, dflt 0.1) — HIGHER => more tokens accepted/forward
#   t_min / t_max, stability_threshold + confidence_threshold (early-stop, pass BOTH)
python -u client.py '{"mode":"generate","prompt":"...","entropy_bound":0.3,"max_denoising_steps":64,"trace":"entropy"}'

# RESULT FIELDS: worker_sha, attn_impl (sdpa|eager), denoise_steps, committed_per_step, tokens_per_forward, gen_s, tok_per_s
# COST / billing: running>0 = executing; workersMin=0 = $0 idle
curl -s https://api.runpod.ai/v2/$DIFFGEMMA_EP/health -H "Authorization: Bearer $RUNPOD_API_KEY"

# REBUILD/PUSH the custom image (stops at push; needs docker login)
REGISTRY=docker.io/seantempesta TAG=cu128-v1 ./build-image.sh
```

- **Scale-to-zero** (`workers=(0,1)`): $0 when idle, ~66 s cold reload. **Keep-warm**
  for fast iteration: min worker = 1 in the `@Endpoint` + redeploy (continuous A100
  ~$1.19/hr — owner's call once iterating). `.flashignore` is DEAD in Flash v1.17 —
  use `.gitignore`.

## Deployment stability — KNOW what's live (do NOT skip)

A plain `flash deploy` does NOT recycle a WARM worker — it keeps serving OLD code
until it scales to zero (`idle_timeout`) or a structural field changes. Grounded in
the Flash source ([[grounding]] "Flash", [[research/flash-deployment-stability-2026-06-28]]):

- **`worker_sha`** — every response carries `sha256(gpu_worker.py +
  diffgemma_common.py)[:12]`, computed INSIDE the container. It proves which code
  produced a result.
- **`verify_fresh.py`** (gitignored) — asserts `worker_sha == local`; prints
  `FRESH ✓` or refuses. Run it after ANY deploy before trusting a single number.
- **Force-fresh that PRESERVES the endpoint id:** bump `FLASH_GPU_IMAGE` to a new
  tag (`imageName` is structural → server-side worker recreation). `flash undeploy
  --all && flash deploy` also works but CHANGES `DIFFGEMMA_EP`.

## Settled — do NOT re-litigate

See [[roadmap]] "Settled" for the full list. The load-bearing ones: torch 2.9.1
stock WORKS (custom image kept only for Seon co-location); A100-80 BF16 (FP8 1000
tok/s is Hopper-only); two endpoints behind one provider (vLLM speed / transformers
control); commit is emergent random-init NOT a mask; `max_denoising_steps` is a CAP
(stop externally); stay on transformers 5.11.0.

## Research index (the dated depth)

The spine links the depth inline; this table is the full map — one line per file.

| Research file | What it covers |
|---|---|
| `mode-driven-guided-generation` | **THE design** — the mode abstraction, the four modes, the convergent-pass frame, E0–E6 |
| `mode-design-critique` | the adversarial review the roadmap's sequencing is built on (missing arm-3, vacuity, canvas gating, cut-list) |
| `transformers-diffusion-source-grounding` | the real v5.11.0 mechanism — per-step seam `:1034`, stopping ABC `:466`/`:1207`, temp ramp `:311`, streamer verdict |
| `parser-as-generation-oracle` | the measured three-tier oracle (92.7% parse / 62.5% free / 91.5% w-ref / 93.5% combined) + the strong-model nulls |
| `seon-diffusion-interface-design` | the `:diffusiongemma` provider (two backends) + the gym predicate machinery |
| `serving-optimization-survey` | vLLM runs the decode but seals the sampler → the two-endpoint split; the 137 vs 1000 tok/s explanation |
| `flash-deployment-stability` | why a warm worker keeps old code + the stable deploy procedure (Flash source) |
| `flash-warm-reuse` | FlashBoot reality (platform-side, decays with idle) → keep-warm is the dependable lever |
| `eval-renoise-worker-build` | the built `denoise_to_step`/`resume_renoise` worker + the two GPU-only unknowns |
| `gym-third-party-adoption` | making the gym consumer-drivable (`SEON_CONFIG` + `SEON_EXTRA_SRC`, no `src/seon` edits) |
| `thesis-capstone` | the session synthesis + the first-light GO/NO-GO against the T0–T5 ladder |
| `first-light-runbook` | the ordered deploy → capabilities execute sequence |
| `custom-image-and-seon-colocation` | the torch finding (stock works) + the co-location latency play |
| `runpod-flash-grounding` | RunPod/Flash SDK grounding + the env-fix recipe (`dependencies` is build-time pip) |
| `model-mechanics-grounding` | the pivotal mask→random-init correction — **absorbed into** `transformers-diffusion-source-grounding` (kept for the history) |
| `infill` / `eval-renoise` / `retrieval-denoising` / `live-feedback`-experiment-plan | capabilities #1–#4 — capability INTENT valid; the **mask-based mechanism is SUPERSEDED** by `transformers-diffusion-source-grounding` + `eval-renoise-worker-build` |

Also top-level: [[infra-flash-runpod]] (the operational deploy/debug log).
`archive/index.md` = the original "push the image" handoff (superseded by the spine).

## How to work here

- **Docs + experiments only on this track** — `src/seon` integration (the
  `:diffusiongemma` provider, gym predicates) lands in [[roadmap]] P3, after the
  kill-gate. Don't wire the pod before the thesis clears P1.
- **The GPU is the owner's single worker** — agents design + ground + write worker
  modes (py_compile-clean, off-GPU unit-checked); the owner deploys + drives.
- **Every experiment is a gym scenario + a predicate + a scorecard** (`scenario ×
  git-sha`) — a knob sweep is a MOVED number, not an anecdote.
- **Read the source before you build** — [[grounding]] maps every claim to a
  `reference-code/…:LINE`; guessing diffusion semantics produces confident, wrong code.
