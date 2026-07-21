---
type: reference
status: active
tags: [reference, agent, web]
---

# Infra — RunPod Flash + the DiffusionGemma worker (debugging log)

> Companion to [[index]]. This is the hard-won operational knowledge: the Flash
> project layout, the data flow, and the **complete debugging log** (every issue
> hit + fix) so a fresh continuation does NOT repeat the ~12 deploy cycles.

## Flash project layout

`/Users/sean/src/seon/tmp/flash-diffgemma/` (under gitignored `tmp/`):

- **`gpu_worker.py`** — the `@Endpoint(name="diffgemma", gpu=NVIDIA_A100_80GB_PCIe,
  workers=(0,1), idle_timeout=600, dependencies=[...], env={HF_TOKEN},
  template=PodTemplate(containerDiskInGb=120), execution_timeout_ms=1_500_000)`
  function `diffgemma(**payload)`. Two modes via payload: `probe` (cheap env
  check, no model load) and `generate` (load BF16 + generate).
- **`client.py`** — async driver: POST `/run`, poll `/status/{id}` until
  COMPLETED. Reads endpoint id from `DIFFGEMMA_EP` env. Guarded under
  `if __name__ == "__main__"` (Flash imports project .py files at deploy).
- **`.env`** — `RUNPOD_API_KEY`, `HF_TOKEN`, `FLASH_SENTINEL_TIMEOUT=1800`.
  Gitignored.
- **`.venv`** — python3.12 venv (Flash needs ≥3.10,<3.13; system python is 3.9,
  too old — use `/opt/homebrew/bin/python3.12`).
- **`.flashignore`** — excludes `client.py`, `*.log`, `.venv/`, `*.output`.

**Run pattern (the proven flow):**

```bash
cd /Users/sean/src/seon/tmp/flash-diffgemma
. .venv/bin/activate && set -a && . ./.env && set +a
flash undeploy --all --force            # kill stale workers (changes endpoint id!)
flash deploy 2>&1 | tee deploy.out      # build + deploy; auto-selects 'production' env
EP=$(grep -oE 'v2/[A-Za-z0-9]+/runsync' deploy.out | head -1 | sed -E 's|v2/(.*)/runsync|\1|')
DIFFGEMMA_EP="$EP" python -u client.py  # async run + poll
```

Always run long jobs as **harness-tracked background** (`run_in_background: true`)
— cold start + 50 GB load is 7–15 min; `nohup … &` in a normal Bash call gets
killed when the call returns.

## Data flow (how data goes back and forth)

**JSON over HTTPS** via RunPod's serverless queue API — no tensors on the wire:

1. Local POST `{"input": {"mode","prompt","max_new_tokens"}}` →
   `https://api.runpod.ai/v2/<endpoint>/run` (async; **retains results**).
2. RunPod queues → a worker cold-starts (provision A100 + install deps + first
   request) or reuses a warm one → calls `diffgemma(**job_input)`.
3. The function tokenizes the **prompt string**, runs the PyTorch model on the
   A100, returns a **JSON-serializable dict** (text, prompt/completion tokens,
   gen_s, tok_per_s, diagnostics).
4. Local polls `/status/<id>` until `COMPLETED`; the dict is `output`.

The **50 GB weights download from HuggingFace to the worker's container disk**
(`containerDiskInGb=120`) and stay GPU-resident; only prompt text in, result out.
Flash packages our code (~48 MB) + a generated handler `/app/handler_diffgemma.py`
that does `result = diffgemma(**job_input)`.

## THE DEBUGGING LOG — every issue + fix (do not repeat these)

In the order hit. Each was a distinct, real problem.

1. **Direct `await diffgemma(...)` → "endpoint not found, deploy first."** Flash
   does NOT auto-provision on a decorated-function call in this SDK build (the
   docs' minimal example is misleading). Must `flash deploy` first, then drive.
2. **`request timed out after 90s`.** The flash client's default sync timeout is
   90 s; cold start (provision + pip install) is minutes. Fix: env
   `FLASH_SENTINEL_TIMEOUT=1800` (baked into the Flash `.env`).
3. **`Could not import module 'Gemma4Processor'`.** Loading `diffusion_gemma`
   pulls in the multimodal Gemma4Processor, which needs a **vision backend**
   (torchvision). The probe passed because it only read the config; the MODEL
   load triggers it. Fix: ensure torchvision on the worker.
4. **`cannot import name 'setup_compilation_env' from torch._higher_order_ops.utils`.**
   transformers' `masking_utils` imports `flex_attention`, which the base image's
   torch 2.9.1 lacks. Fix attempt: monkeypatch-shim the missing symbol before
   importing transformers + load with `attn_implementation="eager"` so flex is
   never used. (Worked for THIS import; see #10 for the deeper torch problem.)
5. **`diffgemma() got an unexpected keyword argument 'max_new_tokens'`.** Flash's
   handler calls `diffgemma(**job_input)` — it **spreads the input dict as
   kwargs**, not a single `payload` arg. Fix: `def diffgemma(**payload)`. (This
   class of error fails in ~150 ms, BEFORE the 50 GB load — cheap to iterate.)
6. **`runsync` returns `{status: IN_QUEUE}` then `/status` 404s.** runsync does
   NOT retain results for long jobs; the flash client returns the ticket and
   doesn't poll to completion. Fix: use **async `/run`** + poll `/status/{id}`
   (results retained) — `client.py`.
7. **`flash deploy` "Failed to load client.py: JSONDecodeError".** Flash scans &
   **imports every project `.py`** at deploy; client.py ran module-level code
   (an API call) and errored, corrupting the deploy (old code persisted). Fix:
   guard client.py under `if __name__ == "__main__":` + add `.flashignore`.
8. **Stale warm worker serves OLD code after deploy.** Same `workerId`, same old
   error across redeploys — a warm worker (idle_timeout 600 s, kept alive by our
   requests) never recycles to the new build. Fix: `flash undeploy --all --force`
   then `flash deploy`. **Caveat: this creates a NEW endpoint id each time** →
   client.py must read `DIFFGEMMA_EP` dynamically (extract from deploy output).
9. **`No module named 'transformers'` despite it in `dependencies`.** Flash's
   **deploy-time dependency install is FLAKY** — transformers present on some
   deploys, absent on others (likely a dep-set-hash cache quirk). The ONLY
   reliable mechanism is **runtime `pip install` inside the function** (that's how
   torchvision reliably got in). Fix direction: runtime-install transformers too.
10. **`Config() got an unexpected keyword argument 'deprecated'` in
    `torch._dynamo/config.py`.** With transformers 5.11.0 installed, its
    masking_utils imports `torch._dynamo`, whose own `config.py` is internally
    inconsistent → torch is **broken/half-upgraded**. Strong suspicion: adding
    `torchvision`/`timm` to **deploy-deps upgraded torch** and corrupted it. Fix
    direction: keep torch **pristine** — drop torchvision/timm from build deps,
    install torchvision `--no-deps` at runtime (can't touch torch). Whether
    pristine torch 2.9.1 is ITSELF broken is the open question the research agent
    is resolving.

**Net current state:** the model code runs, the A100 + async API + endpoint
lifecycle all work, transformers loads — but we cannot yet get a STABLE
torch+transformers env on the Flash base image to actually load+generate the
model. No real output produced yet.

## Key facts / gotchas to remember

- **Base image torch:** 2.9.1+cu128, transformers (when present) 5.12.1 — these
  are **ABI-incompatible** with each other in this image. The paper's known-good
  combo is **transformers 5.11.0 + torch 2.9.0**.
- **Reliable dep mechanism = runtime `pip install` in the function**, not Flash
  `dependencies=`. But each cold start re-pays it → a **NetworkVolume** caching
  site-packages + the HF model is the real fix.
- **`probe` mode is cheap** (no 50 GB load) — use it to validate env changes for
  pennies before a full generate.
- **Errors-as-data:** the worker function wraps load+generate in try/except and
  returns the traceback in the result dict — so a failure after the 50 GB load
  returns diagnostics instead of crashing. Keep this.
- **Cost so far:** ~12 cold-start cycles, several with the 50 GB download ≈ a few
  dollars of A100 time. Within the $50 loaded. Scale-to-zero means $0 between runs;
  `flash undeploy --all --force` for a hard stop.
- **Python:** use `/opt/homebrew/bin/python3.12` for the venv (system 3.9 too old).

## Open questions for the research agent (being answered)

- Is pristine base-image torch 2.9.1 itself broken, or only after dep upgrades?
- Can we pin a clean `torch+torchvision+transformers` (cu128) at build or runtime
  reliably? Exact versions?
- NetworkVolume: create (runpod-python/REST) + attach + `HF_HOME`/pip-cache onto
  it; which DC (EU-RO-1?).
- Can a *code* `@Endpoint` use a custom Docker base image with the stack
  pre-installed (vs `image=` external-server mode)?
- How to force workers to pick up new code without `undeploy` (stable endpoint id)?
</content>
