---
type: research
status: draft
tags: [research, agent, web]
---

# RunPod 5090 + DiffusionGemma NVFP4 — turnkey runbook

> Copy-paste path to **step 2 (black-box agent test)**: a 5090 on RunPod serving
> the NVFP4 checkpoint over an OpenAI-compatible endpoint, with Seon pointed at
> it via env vars (no Seon code change). Companion to [[diffusion-llm-test-plan-2026-06-27]].

## What we serve

- Checkpoint: **`nvidia/diffusiongemma-26B-A4B-it-NVFP4`** (~18 GB, NVFP4,
  vLLM-ready). Fits the 5090's 32 GB with KV headroom; ~700+ tok/s.
- Engine: vLLM (native diffusion support), OpenAI-compatible API on :8000.
- Seon already has an `:openai-compat` provider (`seon.ai`) — wiring is env-only.

## 1. RunPod box

1. New Pod → **RTX 5090** (community cloud = cheaper).
2. Template: a vLLM or CUDA+Docker base (e.g. `vllm/vllm-openai`).
3. **Attach a Network Volume mounted at `/workspace`** — this is the point:
   the HF cache lives here so the ~18 GB checkpoint downloads **once** and
   survives pod restarts. Without it, every spin-up re-pulls 18 GB.
4. Expose **HTTP port 8000** (RunPod gives a proxy URL like
   `https://<pod-id>-8000.proxy.runpod.net`).
5. Set env `HF_TOKEN=<your hf token>` (accept the model license on HF first;
   the `nvidia/` repo may be ungated but the base Gemma license can gate it).

## 2. Serve (run on the box)

NVFP4 quant is **built into the checkpoint** — no `--quantization` flag. Canonical
flags from the NVFP4 model card (Blackwell/5090-native):

```bash
docker run -itd --name diffgemma \
  --ipc=host --gpus all -p 8000:8000 \
  -v /workspace/hf:/root/.cache/huggingface \
  -e HF_TOKEN=$HF_TOKEN -e VLLM_USE_V2_MODEL_RUNNER=1 \
  vllm/vllm-openai:gemma \
    vllm serve nvidia/diffusiongemma-26B-A4B-it-NVFP4 \   # verify exact case at pull time
      --trust-remote-code \
      --attention-backend TRITON_ATTN \
      --max-num-seqs 4 \
      --max-model-len 8192 \
      --enable-auto-tool-choice --tool-call-parser gemma4 --reasoning-parser gemma4 \
      --host 0.0.0.0 --port 8000
```

`--max-num-seqs 4` is required-low (diffusion state buffers); `--max-model-len
8192` keeps KV small (we generate small forms). Watch `docker logs -f diffgemma`
for "Application startup complete".

**RISK — the vLLM diffusion image may be preview.** The card notes the commands are
"tentative until the supporting vLLM image is publicly released." So step 0 on the
box: confirm an image that supports `model_type: diffusion_gemma` (the recipe's
`vllm/vllm-openai:gemma` tag, or the current release per build.nvidia.com/rtx/vllm).
If no vLLM image serves it yet, **fall back to transformers** —
`DiffusionGemmaForBlockDiffusion` + `AutoProcessor` (what the interp. paper used) —
and wrap a 20-line FastAPI `/v1/chat/completions` shim, OR skip the server and use
transformers directly (that IS the path for step 3's `accept_canvas` override anyway).

**Caching bonus:** vLLM has **automatic prefix caching** — unlike the OpenRouter
route (no caching, every turn full-price), the agent's stable prompt prefix is
cached across turns here for free. Confirm with two identical calls: 2nd shows
`prompt_tokens` cached / faster TTFT.

## 3. Smoke test (from anywhere)

```bash
curl https://<pod-id>-8000.proxy.runpod.net/v1/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"nvidia/diffusiongemma-26B-A4B-it-NVFP4",
       "messages":[{"role":"user","content":"Write a Clojure fn returning the mean of a vector of numbers."}]}'
```

Pass = parseable Clojure back. Note tok/s from the vLLM logs (T0 baseline).

## 4. Point Seon at it (env-only, then restart pod)

`seon.ai` resolves provider/base-url/key from these env vars:

```bash
export SEON_AI_PROVIDER=openai-compat
export SEON_AI_BASE_URL=https://<pod-id>-8000.proxy.runpod.net/v1
export SEON_AI_MODEL=nvidia/diffusiongemma-26B-A4B-it-NVFP4
export SEON_AI_API_KEY_ENV=SEON_VLLM_KEY   # names the env var holding the key
export SEON_VLLM_KEY=EMPTY                  # vLLM ignores the key by default
```

Then `bin/seon restart pod` and drive the agent. This IS the black-box test:
the Seon agent loop now runs on DiffusionGemma as a vanilla LLM. Watch latency +
whether the agent completes tasks.

## 5. Decision

- Agent functions + acceptably fast → green-light **step 3** (dynamic context:
  override `accept_canvas`, see [[diffusion-llm-test-plan-2026-06-27]] T1–T5).
- Too weak as a plain agent → the fancy stuff won't save it; stop or reassess.

## Cost

5090 ~$0.43–0.69/hr; black-box test is ~1–2 hr ⇒ **~$1–2**. Network volume is a
few cents/GB-month so the 18 GB cache persists between sessions cheaply.

## Gotchas

- **Undeploy/stop the pod when done** — it bills while running (RunPod is per-pod,
  not per-request).
- If vLLM errors on the quant, add `--quantization modelopt_fp4`.
- If 8192 context still tight, drop `--gpu-memory-utilization` to 0.85 or cut
  `--max-model-len` to 4096.
- Step 3 (custom `accept_canvas`) uses **transformers**, not this vLLM container —
  same box, different process; load the NVFP4 checkpoint via
  `DiffusionGemmaForBlockDiffusion`.
</content>
