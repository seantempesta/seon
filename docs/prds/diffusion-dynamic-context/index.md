---
type: prd
status: active
tags: [prd, agent, research, web]
---

# Diffusion Dynamic Context — PRD + Handoff

> **RESTART HERE.** This is a solo-driven exploration; this doc is the complete
> handoff for a fresh-context continuation. Read this top-to-bottom, then
> [[infra-flash-runpod]] for the gory infra detail and the live debugging log.

## TL;DR — where we are (2026-06-28)

- **Goal:** test whether a **diffusion LLM (DiffusionGemma)** can be a better
  *live-context* interface than autoregressive (AR) generation — the "buzzsaw"
  thesis: a smart AR model guides, a diffusion model **refines whole blocks of
  Clojure forms fast**, taking feedback *between denoising steps*.
- **Decided:** run **BF16 on an A100-80GB** via **runpod-flash** (serverless
  Python `@Endpoint`), using HF `transformers` **directly** (PyTorch) because the
  `accept_canvas` hook — the whole point — lives in the transformers decode loop.
- **Status:** the harness works (deploy, async run/poll, A100 spins up, model
  CODE runs, `transformers` loads) — but we are **blocked on the Flash serverless
  worker environment**: its base image ships a **broken/mismatched torch
  2.9.1**, and Flash's **deploy-time dependency installs are flaky**. We have
  **not yet produced real DiffusionGemma output.**
- **Immediate next action:** read the research agent's grounding doc
  `research/runpod-flash-grounding-2026-06-28.md` (it vendored the Flash/RunPod
  SDK source into `reference-code/` and worked out the env-fix recipe), then set
  up a **NetworkVolume** to cache a **clean torch+transformers stack + the 50 GB
  model**, and get the first generate. Full step list in §"Next steps".

## The thesis (the idea)

Replace static AR context with a **diffusion** model so data coming out of the
model *mid-generation* guides generation. Four capabilities, in build order:

1. **Typeahead via infilling** — clamp the human's typed tokens, denoise the
   holes. Bidirectional attention sees code *after* the cursor (AR cannot). The
   cheapest "clearly better than AR" win; needs no Seon integration.
2. **Whole-form refinement + eval feedback** — generate a form, eval it in Seon's
   SCI cage, and on `:seon/error` **re-noise only the failing span** and
   re-denoise in place (AR must regenerate forward).
3. **Retrieval-augmented denoising** — when a symbol region commits with high
   entropy, embed the partial canvas, hit Seon's Vertex + Proximum/HNSW program
   graph, and inject the right fn-spec into the encoder for the next steps. RAG
   *inside* the generation loop.
4. **Live human feedback into the denoiser** — accept/clamp/re-noise canvas
   regions between steps, streamed over Seon's SSE. Most novel, most fork.

The vision: a strong AR model (Opus) sets direction; the diffusion model is the
**buzzsaw** iterating/refining code as fast as we can eval or retrieve.

## The model — DiffusionGemma

- 26B-A4B **MoE** (Gemma 4 base), 25.2B total / **3.8B active**, released
  2026-06-10, Apache 2.0. HF: `google/diffusiongemma-26B-A4B-it` (BF16, gated);
  NVFP4 4-bit at `nvidia/diffusiongemma-26B-A4B-it-NVFP4` (~18 GB, Blackwell).
- **Encoder/decoder:** an AR **encoder** processes the prompt → KV cache (once,
  prefill). A **decoder** refines a **256-token canvas** with **bidirectional**
  attention, cross-attending the cached prompt. Masked discrete diffusion: canvas
  starts masked → each denoise step commits low-entropy tokens, re-noises the
  rest, ≤48 steps; ~15–20 tokens/forward pass. Sliding window 1024; context up to
  262k. **block-autoregressive multi-canvas** chains 256-blocks for long output.
- **Why it fits Seon:** the diffusion **canvas ≈ a Seon block/form**; the prompt
  becomes the encoder KV cache (= Seon's *ai render*); refinement is in-place.

### The control seam (the whole point)
`EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
cur_step)` — `logits` shape `[1, 256, 262144]` — is the per-step commit
decision, in **open `transformers`** (model class `DiffusionGemmaForBlockDiffusion`,
`model_type: diffusion_gemma`, no `trust_remote_code`). Override it (clamp,
re-noise, inject retrieved context, read external feedback) → capabilities 2–4.

## Research grounding (papers + key findings)

Two papers downloaded + read (PDFs were in the session scratchpad — **re-fetch by
arXiv id**, they won't survive a context restart). Detailed write-up:
`docs/prds/agent-fsm/research/diffusion-llm-live-context-2026-06-27.md`.

- **arXiv:2606.14620** — "Neither Parallel Nor Sequential: How DiffusionGemma
  Actually Commits Tokens" (Transformer Lab). The most useful paper. Inference-only
  hook study. Findings: the `accept_canvas` hook + signature (above); decode is a
  **partial, granularity-dependent L→R bias** (Kendall τ ≈ 0.43–0.60), NOT clean
  block-AR; **commits are NOT frozen** (positions re-mask, ~7.5/gen) → in-place
  revision is native; **commit-entropy predicts correctness on math/code (GSM8K
  AUROC 0.749) but is NULL on factual recall (0.471)** → the model self-detects
  code-shaped uncertainty (trigger lookups) but NOT wrong fn/API names (external
  retrieval must catch those — capability 3 earns its keep here); structured
  output is order-independent (JSON τ ≈ −0.044). Accuracy comparable to the AR
  sibling `gemma-4-26b-a4b-it`.
- **arXiv:2503.09573** — "Block Diffusion (BD3-LMs)" (Arriola et al., ICLR 2025).
  The architectural foundation (arbitrary-length + KV caching + parallel sampling
  + bidirectional). **Vendored at `reference-code/bd3lms`** (code:
  github.com/kuleshov-group/bd3lms) — the free, small mechanism testbed.
- Related (next reads): infilling 2506.13579; steering 2602.00250 (TABES),
  2511.05664 (KLASS); order/token search 2601.20339; survey 2506.13759.

## GPU + cost decision

Full analysis: `docs/prds/agent-fsm/research/diffusion-gpu-cost-comparison-2026-06-28.md`.

- **Both requirements force an 80 GB card:** ≥100k context needs Flash Attention
  (disabled on the 5090's Blackwell SM120 → 5090 caps ~10k via llama.cpp), and the
  BF16 `accept_canvas` experiments need ~50 GB weights. → **A100-80GB.**
- **Why BF16 first:** confound-free quality baseline (the thesis hinges on the
  model's entropy/commit dynamics, which quantization perturbs). Then sweep
  Q4_K_M/Q6_K/NVFP4 for speed + bigger KV/context, measured vs the BF16 baseline.
- **Max context with quantized weights (FA on; ~estimates):** 5090 ~10k (FA
  blocker), 4090 ~20–40k, **A6000/L40S 48GB ~100–180k**, **A100/H100 80GB ~250k**,
  H200 141GB → full 262k. KV-cache fp8 ≈ doubles these.
- **Cost (per-hr):** A100-80 — Flash serverless ~$1.19, Vast pod ~$0.78. H100 —
  serverless ~$1.91. A6000 — Community **pod $0.33** (cheapest for 100k quantized,
  but 48 GB can't do BF16 and serverless A6000 is a pricey ~$2.07). Real Reddit
  5090 data: Q4_K_M ~252 tok/s, max ~10k ctx (FA blocker).
- **Pick:** A100-80 on **Flash serverless** (bursty, scale-to-zero, pay-per-run).

## Infra — RunPod Flash (see [[infra-flash-runpod]] for full detail)

- **Flash project:** `/Users/sean/src/seon/tmp/flash-diffgemma/` (under gitignored
  `tmp/`). `gpu_worker.py` = the `@Endpoint` (`diffgemma`), `client.py` = async
  `/run` + poll `/status` driver, `.env` = keys, `.venv` = python3.12 venv,
  `.flashignore` excludes client.py/logs.
- **Data flow:** **JSON over HTTPS** via RunPod's serverless queue API. Out: POST
  `{"input":{mode,prompt,max_new_tokens}}` → `/v2/<ep>/run` → poll `/status/<id>`.
  On the A100: RunPod calls `diffgemma(**input)`; it tokenizes the **prompt
  string**, runs the PyTorch model, returns a **plain JSON dict** (text, tok/s,
  diagnostics). **No tensors cross the wire** — only prompt text in, result text +
  metrics out; the 50 GB weights stay GPU-resident.
- **Keys (all in gitignored `.env` files; NEVER commit):** `RUNPOD_API_KEY` +
  `HF_TOKEN` live in the Flash project `.env`, and also in repo-root `.env` (seon)
  and `.env.acme`. HF token = seantempesta's "2026 agentic" (write-scoped; read
  would suffice). Model download confirmed (HTTP 200).
- **Endpoint id CHANGES on every `undeploy`+`deploy`** — `client.py` reads it from
  `DIFFGEMMA_EP` env; the deploy step extracts it from the deploy output.

## Env-fix — SOLVED (recipe known; not yet implemented)

Root cause, grounded in the vendored SDK source (full detail +
`file:line` cites in `research/runpod-flash-grounding-2026-06-28.md`):

- `@Endpoint(dependencies=[...])` is a **build-time `pip install --target`** baked
  into the uploaded tarball, **not** a worker install — and
  **torch/torchvision/torchaudio/triton are force-stripped** (`SIZE_PROHIBITIVE_PACKAGES`).
  Torch comes ONLY from the base image **`runpod/flash:py3.12-latest`**, which
  ships the broken **torch 2.9.1+cu128**. The "transformers missing some deploys"
  flakiness = the **1500 MB tarball cap** nudging an `--exclude transformers`.
- **`FLASH_GPU_IMAGE`** env var **replaces the base image** for a *code*
  `@Endpoint` (bypasses version validation, wins over the default). The `image=`
  decorator arg is a *different* feature — external-image client mode (your code
  wouldn't run). This is the key that unlocks everything.

**The recipe (do this):**

1. **Custom base image.** Build `FROM runpod/flash:py3.12-latest`, pip-install a
   verified `torch+torchvision+transformers` **cu128** triple (transformers 5.11.0
   + the matching torch), with a build-time `import torch._dynamo; setup_compilation_env`
   **smoke test as the gate**. Push it; `export FLASH_GPU_IMAGE=<repo>:cu128-vN`.
   This **deletes** all the `gpu_worker.py` runtime hacks (torchvision `--no-deps`,
   the dynamo probe, the `setup_compilation_env` shim). Stay on **Python 3.12**
   (other versions trigger an in-image torch reinstall → mismatch).
2. **NetworkVolume.** `NetworkVolume(name=..., size=200, datacenter=EU_RO_1)` via
   `volume=`, mounted at **`/runpod-volume`**; set `env={HF_HOME, HF_HUB_CACHE,
   PIP_CACHE_DIR}` into it. Volume **and** endpoint must both be **EU-RO-1**.
   Caches the 50 GB model → fast cold starts; survives `undeploy`; idempotent by
   name+DC.
3. **Stale workers, no more `undeploy`.** Code-only changes only bump a *rolling*
   fingerprint, so warm flashboot snapshots keep the old handler. Changing a
   **structural** field (the `FLASH_GPU_IMAGE` tag, `gpus`, `flashboot`) recreates
   workers **with the endpoint id preserved** — so re-tagging the custom image
   gives clean recycles for free, no endpoint-id churn.

(Fallback if the custom image stalls: RunPod's official **vLLM serverless** for
black-box "does it run / context / tok/s" measurements, keeping the transformers
path for `accept_canvas`.)

## Next steps (ordered)

**PREPPED (2026-06-28) — push-ready, blocked on ONE owner decision.** The recipe
is implemented in `tmp/flash-diffgemma/` (snapshot in `flash-worker/`):

- `Dockerfile` — `FROM runpod/flash:py3.12-latest` + pristine cu128 triple
  (**torch 2.9.0 / torchvision 0.24.0 / torchaudio 2.9.0 + transformers 5.11.0**,
  `--force-reinstall` over the broken base torch) + the build-time smoke-test gate
  (`import torch._dynamo; from torch.nn.attention.flex_attention import
  setup_compilation_env; import transformers`).
- `gpu_worker.py` — REWRITTEN clean: torch+transformers imported straight from the
  image; the torchvision `--no-deps`, `torch._dynamo` probe, and
  `setup_compilation_env` shim are DELETED. NetworkVolume `diffgemma-vol`
  (200 GB, EU-RO-1) wired via `volume=`, mounted `/runpod-volume`, with
  `HF_HOME`/`HF_HUB_CACHE`/`PIP_CACHE_DIR` env pointed into it; endpoint pinned to
  EU-RO-1 to match the volume DC.
- `build-image.sh` — `docker buildx --platform linux/amd64` build+push; documents
  `export FLASH_GPU_IMAGE=<registry>:cu128-v1` before deploy. **Stops at the push.**

**THE ONE OPEN DECISION — which registry to push to.** RunPod workers cannot pull
the local private corp registry; the realistic options are **public Docker Hub**
(simplest, no RunPod-side auth) vs **private ECR** (already in the local docker
config, but needs RunPod registry-auth creds wired into the endpoint/template).
Pick one, set `REGISTRY`, run `build-image.sh`, then the remaining ordered steps:

1. **Push the image** to the chosen registry; `export FLASH_GPU_IMAGE=<tag>`.
2. **`flash deploy`** — `FLASH_GPU_IMAGE` is structural, so it also recycles stale
   workers (endpoint id preserved). The NetworkVolume auto-deploys idempotently.
3. **First real generate** — the Clojure `mean` prompt → coherence + tok/s. The
   milestone we haven't hit yet.
4. **Max-context measurement** on A100-80 BF16 (how far past 100k).
5. **`accept_canvas` observability** — surface the per-step canvas/entropy.
6. **The 4 dynamic-context experiments** (infill → eval-renoise → retrieval →
   live feedback), wiring Seon's eval cage + Proximum index as oracles over HTTP.

## Pointers

- [[infra-flash-runpod]] — full infra + the complete debugging log (12 issues).
- `docs/prds/agent-fsm/research/diffusion-llm-live-context-2026-06-27.md` — papers
  + the 4 ideas assessed + the `accept_canvas` mechanism.
- `docs/prds/agent-fsm/research/diffusion-llm-test-plan-2026-06-27.md` — the T0–T5
  test ladder + go/no-go gates.
- `docs/prds/agent-fsm/research/diffusion-llm-runpod-runbook-2026-06-27.md` — the
  vLLM/NVFP4 serve commands + transformers fallback.
- `docs/prds/agent-fsm/research/diffusion-gpu-cost-comparison-2026-06-28.md` — GPU
  + cost + context-per-card.
- `research/runpod-flash-grounding-2026-06-28.md` — RunPod/Flash platform grounding
  (written by the research agent; the env-fix recipe).
- `reference-code/bd3lms` — block-diffusion source (mechanism testbed).
- `reference-code/<runpod-flash, runpod-python, flash-examples>` — vendored by the
  research agent.
</content>
