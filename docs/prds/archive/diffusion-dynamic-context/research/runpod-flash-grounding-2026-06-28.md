---
type: research
status: active
tags: [research, agent]
---

# RunPod Serverless + runpod-flash SDK — source-grounded grounding for DiffusionGemma

All `file:line` citations are into the vendored submodules under
`reference-code/` (added by this task):

- `reference-code/flash` — the `runpod_flash` SDK (`runpod/flash` @ `f7cfc47`)
- `reference-code/runpod-python` — the lower-level `runpod` SDK (`runpod/runpod-python` @ `6c2cd64`)
- `reference-code/flash-examples` — official examples (`runpod/flash-examples` @ `2b45929`)

Doc URLs are RunPod's published docs; SDK behavior is grounded in the vendored
source, which is authoritative for what our installed SDK actually does.

## TL;DR

1. **`dependencies=[...]` is a BUILD-TIME, on-YOUR-machine `pip install --target`**
   that bakes wheels into the uploaded tarball — NOT a worker-side install
   (`build.py:340-393`, `build.py:779-948`). It uses
   `pip install --target <build> --platform manylinux... --only-binary=:all:`.
   "Flaky/missing package" = pip silently produced no compatible binary wheel
   for the target Python/manylinux (e.g. a source-only dep, or a wheel that
   needs the wrong Python ABI), or the package was auto-excluded. There is no
   dependency-set hash cache; every `flash build` re-runs pip into a fresh
   `.flash/.build`.
2. **`torch`/`torchvision`/`torchaudio`/`triton` are ALWAYS auto-stripped from
   your deps** (`SIZE_PROHIBITIVE_PACKAGES`, `build.py:92-99`,
   `build.py:260`). Torch comes ONLY from the base image. The base GPU image is
   **`runpod/flash:py3.12-latest`** (`constants.py:14, 22-23, 109-111`) and
   ships torch built for **Python 3.12 only** (`constants.py:1-14`). The broken
   `torch 2.9.1+cu128` is baked into that image; the SDK has no torch-version
   knob.
3. **You CAN override the entire base image** for a code `@Endpoint` by setting
   the env var **`FLASH_GPU_IMAGE=<your image>`** at build/deploy time — it
   bypasses version validation and wins over the default
   (`constants.py:90-95`). This is the clean fix: build a custom RunPod worker
   image with a matched `torch+transformers` stack. `image=` on the decorator is
   a *different* feature (external-image client mode, §f) and is NOT how you
   change a code function's base.
4. **NetworkVolume** mounts at **`/runpod-volume`**; declare it inline and pass
   `volume=`; it auto-deploys idempotently by name+datacenter
   (`network_volume.py:130-177`, example `flash-examples/05_data_workflows/01_network_volumes/gpu_worker.py`).
   Point `HF_HOME`/`HF_HUB_CACHE` (and optionally the pip cache) into it via
   `env=`. **Default + serverless-required datacenter is `EU-RO-1`**
   (`network_volume.py:42`; CPU/serverless DC list, `flash-examples/04_scaling_performance/02_datacenters/README.md`).
5. **`/run` retains results; `/runsync` does not** for long jobs — confirmed:
   `runsync` defaults to a 60s client timeout (`serverless.py:48`,
   `endpoint.py:888`), while `run`→`status/{id}`→`output` polls a retained job
   (`endpoint.py:866-886, 113-122`).
6. **Stale warm worker** = a code-only change updates the env-injected
   `_FLASH_SOURCE_FINGERPRINT` (`cli/utils/deployment.py:121-129`) which is a
   *rolling* (non-structural) change (`serverless.py:1262-1313` — `env` is NOT in
   `structural_fields`). To FORCE worker recreation without `undeploy`, change a
   **structural field** (`imageName`/`FLASH_GPU_IMAGE` tag, `gpus`, `flashboot`,
   `minCudaVersion`) — those increment the endpoint version server-side and
   recreate workers.

**Recommended recipe is in the last section.**

---

## a. Dependency install — how `dependencies=[...]` works, why it's flaky

### It is build-time, on your machine, baked into the tarball

`@Endpoint(dependencies=[...])` is read by an AST scan of your source at build
time (`build.py:713-776`, `extract_remote_dependencies` /
`_extract_deps_from_call`), merged with `requirements.txt`
(`build.py:622-660`), then installed with `pip --target` into the build dir
that becomes the uploaded artifact (`build.py:340-393`). The deploy path is
build → tarball → presigned upload → `activeBuildId`
(`deploy.py:74-87, 215-269`; `app.py:347-476`); the worker fetches the active
build's manifest/artifact at runtime (`runtime/state_manager_client.py:225-248`).
So **deps live in the tarball, not installed live on the worker.**

The exact pip invocation (`build.py:904-941`):

```python
cmd = pip_cmd + [
    "install", "--target", str(build_dir),
    "--python-version", pip_python_version,   # target container Python, default 3.12
    "--upgrade",
]
# standard pip branch:
for platform in RUNPOD_PLATFORMS:             # manylinux_2_28 / _2_17 / 2014, x86_64
    cmd.extend(["--platform", platform])
cmd.extend(["--implementation", "cp", "--only-binary=:all:"])
if no_deps:
    cmd.append("--no-deps")
cmd.extend(requirements)
```

`RUNPOD_PLATFORMS` / `RUNPOD_PYTHON_IMPL` at `build.py:74-79`;
`target_python_version` resolved from the manifest (`build.py:332-338`,
defaults to local interpreter, overridable by `--python-version`).

### Why a listed package is "present on some deploys, absent on others"

Grounded causes, in order of likelihood:

1. **`--only-binary=:all:` + `--platform manylinux...`**: pip will only place a
   package if a matching **binary wheel** exists for the chosen
   `--python-version` ABI (`cp312`) and one of the three manylinux tags. If the
   resolver can't satisfy a transitive constraint for the pinned platform it can
   drop/skip that branch. A package that has a pure-Python wheel installs fine; a
   package that needs to build from source is silently unavailable under
   `--only-binary=:all:`. `transformers` itself is pure-Python (should always
   install) — its *absence* is therefore almost always (b) below.
2. **It was excluded.** `excluded_packages = user_excluded |
   SIZE_PROHIBITIVE_PACKAGES` (`build.py:260`). If a prior deploy ran with
   `--exclude transformers` (the build error message even *suggests* this when
   the tarball is too big — `build.py:434-437`), transformers is stripped.
   Different commands → different presence. **This is the most likely
   "flaky" cause: the tarball-size guard nudges you to `--exclude transformers`,
   and then it's gone.**
3. **Tarball size fail-then-strip loop.** `MAX_TARBALL_SIZE_MB = 1500`
   (`constants.py:124`). Over-limit aborts the deploy (`build.py:428-444`);
   re-running with `--exclude` changes which deps land.
4. **`uv pip` fallback.** If standard pip isn't found, it falls back to `uv pip`
   which "has known issues with manylinux_2_27+" (`build.py:858-877`) — a
   different machine/venv can resolve a different wheel set.

### Caching / forcing a clean reinstall

- **No dependency-set hash cache.** Each build installs into a fresh
  `.flash/.build` (`deploy.py:89-91` removes it after; `build.py:322-327`
  on error). `--upgrade` is always passed (`build.py:911`). So a clean
  reinstall is simply re-running `flash build`/`flash deploy`. If you suspect a
  poisoned local pip wheel cache, `pip cache purge`
  (docs `reference-code/flash/src/runpod_flash/cli/docs`/troubleshooting and
  `flash-examples/docs/cli/troubleshooting.md:1229-1230`).
- **What persists across deploys is the *endpoint/template*, keyed by config
  drift** (`cli/utils/deployment.py:51-195`) — not the pip wheels.

### `--no-deps` and `--exclude`

- **`--no-deps`** (`build.py:455-457`, `build.py:938-939`): adds `--no-deps` to
  pip, so ONLY the exact names you list are installed — no transitive
  dependencies. Use it to stop a dep (e.g. torchvision) from dragging in a
  conflicting torch. Caveat: anything those packages need at import time must be
  supplied by the base image or listed explicitly.
- **`--exclude pkg1,pkg2`** (`deploy.py:33-37`, `build.py:343-373`,
  `should_exclude_package` `build.py:689-706`): removes matching packages from
  the requirement set entirely (name-normalized, PEP 503). torch/vision/audio
  are auto-excluded on top of whatever you pass.

---

## b. Base image / torch — what ships, and how to get a clean stack

### What the workers run

- GPU base image repo `runpod/flash`, default tag `py3.12-latest`
  (`constants.py:22-23, 109-111`, `get_image_name` `constants.py:64-111`).
- **Torch is pre-installed for Python 3.12 ONLY** in that image
  (`constants.py:1-14`): *"torch and CUDA-linked packages are pre-installed only
  for 3.12 ... Non-3.12 targets ... reinstall torch against the selected
  interpreter's ABI during build."* So choosing `python_version != "3.12"`
  triggers a torch *reinstall* — a likely source of a mismatched torch. **Stay
  on 3.12.**
- The image's torch is whatever RunPod baked in (the observed broken
  `torch 2.9.1+cu128`). The SDK exposes **no torch-version parameter**; the only
  CUDA-related knob is `min_cuda_version` (default `CudaVersion.V12_8`,
  `endpoint.py:419`; enum `serverless.py:183-194`), which constrains GPU host
  CUDA, not the torch wheel.

### Can we override the base image / torch for a *code* `@Endpoint`? — YES

`get_image_name` honors an **environment-variable override that bypasses version
validation and wins over the default** (`constants.py:90-95`):

```python
env_var = _IMAGE_ENV_VARS[image_type]      # "gpu" -> "FLASH_GPU_IMAGE"
override = os.environ.get(env_var)
if override:
    return override                         # used verbatim, skips py-version checks
```

`_IMAGE_ENV_VARS` (`constants.py:36-41`): `FLASH_GPU_IMAGE`, `FLASH_CPU_IMAGE`,
`FLASH_LB_IMAGE`, `FLASH_CPU_LB_IMAGE`. There is also `FLASH_IMAGE_TAG`
(`constants.py:109, 115`) to pin just the tag of the default repo.

So: **`export FLASH_GPU_IMAGE=<your-registry>/diffgemma-worker:cu128` before
`flash deploy`** makes every GPU code endpoint run on your image. That image must
still be a valid RunPod-serverless worker image (it runs the flash/runpod handler
that's bundled into the tarball at `build.py:395-405`; the worker downloads the
artifact and imports your function). Practically: base your Dockerfile on
`runpod/flash:py3.12-latest` (so the flash runtime + Python 3.12 layout is
intact) and just **overwrite the torch/transformers stack** in a layer.

Changing the image is also a **structural change** (`serverless.py:1267, 1292`
`imageName` ∈ `structural_fields`) → it forces a version bump + fresh workers,
which incidentally cures the stale-worker problem (§e).

### The broken torch 2.9.1 — known image issue?

Not documented in the vendored source as a tracked bug; the source only encodes
"torch pre-installed for 3.12, reinstall for others" (`constants.py:1-14`). The
specific `torch._dynamo Config(deprecated=...)` / missing
`flex_attention.setup_compilation_env` failures are an image/transformers
version-skew, not SDK logic. Two real fixes:

1. **Custom image (preferred)** — pin a known-good
   `torch+torchvision+transformers` triple at *image build* time so cold starts
   never run pip for the heavy stack (recipe section).
2. **Runtime pin inside the handler (stopgap, what gpu_worker.py does now)** —
   `pip install` a matched torch at first call. Fragile (re-runs every cold
   start, races the broken preinstalled torch) and slow; acceptable only until
   the custom image is built.

### Getting a clean matched stack at build time

Because torch is force-excluded from the tarball (§a), you cannot ship a clean
torch via `dependencies=`. The matched stack must come from the **image**
(`FLASH_GPU_IMAGE`) — install `torch+torchvision` from the cu128 index in the
Dockerfile and `transformers` either in the image or via `dependencies=`
(transformers is a pure-Python wheel and installs reliably). If you keep
installing torch at runtime, pin from the pytorch cu128 index and use
`--no-deps` on torchvision so it can't pull a conflicting torch.

---

## c. Network volumes — create, attach, point caches, datacenter

### Model: `NetworkVolume` is a first-class resource, mounted at `/runpod-volume`

`NetworkVolume` (Pydantic, `network_volume.py:22-46`):

```python
NetworkVolume(
    name="diffgemma-vol",          # name OR id required (network_volume.py:68-73)
    size=200,                      # GB, 10..4096 (network_volume.py:46)
    datacenter=DataCenter.EU_RO_1, # alias -> dataCenterId (network_volume.py:40-66)
)
```

Default datacenter is **`EU_RO_1`** (`network_volume.py:42`). Mount path is
**`/runpod-volume`** (canonical example
`flash-examples/05_data_workflows/01_network_volumes/gpu_worker.py:9, 73-77`;
README "weights cached in `/runpod-volume/models`").

### Create + attach — the SDK way (recommended)

Declare inline and pass `volume=` to the endpoint; the SDK deploys it
**idempotently** (reuses an existing volume with the same name+datacenter rather
than creating duplicates) before the endpoint comes up:

```python
from runpod_flash import Endpoint, GpuType, DataCenter, NetworkVolume

vol = NetworkVolume(name="diffgemma-vol", size=200, datacenter=DataCenter.EU_RO_1)

@Endpoint(
    name="diffgemma",
    gpu=GpuType.NVIDIA_A100_80GB_PCIe,
    datacenter=DataCenter.EU_RO_1,     # MUST match the volume's DC
    volume=vol,
    env={"HF_HOME": "/runpod-volume/hf",
         "HF_HUB_CACHE": "/runpod-volume/hf/hub",
         "PIP_CACHE_DIR": "/runpod-volume/pipcache"},
    ...
)
def diffgemma(**payload): ...
```

- `volume=` normalization: `endpoint.py:307-327`, serialized into
  `networkVolume`/`networkVolumes` for the API (`endpoint.py:578-586`).
- Idempotent deploy (find-by-name-or-create): `network_volume.py:130-177`
  (`_find_existing_volume` matches on `name` + `dataCenterId`; else
  `_create_new_volume`). Auto-deployed during endpoint update via
  `_ensure_network_volume_deployed()` (`serverless.py:1138-1139`).
- **`undeploy` of a volume is intentionally unsupported** — delete via console
  (`network_volume.py:191-219`). So your model cache survives `flash undeploy`.

### Create via API directly (if you want it out-of-band)

- Flash's REST client: `RunpodRestClient.create_network_volume(payload)` →
  `POST {REST}/networkvolumes` (`core/api/runpod.py:941-953`), list via
  `GET {REST}/networkvolumes` (`core/api/runpod.py:955-967`). `{REST}` =
  `https://rest.runpod.io/v1` (`core/urls.py:69, 120`). Payload is the
  `NetworkVolume.model_dump()` (`name`, `size`, `dataCenterId`).
- Lower-level `runpod-python` attaches an existing volume to an endpoint by id:
  `runpod.create_endpoint(..., network_volume_id=..., data_center_id=...)`
  (`runpod-python/runpod/api/ctl_commands.py:333-373`;
  mutation `runpod-python/runpod/api/mutations/endpoints.py:10, 33-34`). Note it
  back-fills `data_center_id` from the volume if omitted
  (`ctl_commands.py:177-181`) — i.e. the volume's DC pins the endpoint.

### Pointing HF + pip cache at the volume

Set via the endpoint `env=` (becomes worker env): `HF_HOME` (or `HF_HUB_CACHE`)
→ `/runpod-volume/...` so the ~50GB DiffusionGemma snapshot persists across cold
starts (this is exactly what the SD example does with `HF_HUB_CACHE`,
`flash-examples/.../gpu_worker.py:24`). `PIP_CACHE_DIR=/runpod-volume/pipcache`
makes any runtime `pip install` reuse cached wheels across cold starts.
First cold start downloads the model into the volume; subsequent cold starts
mount it. `env` is passed through `endpoint.py:587-588`.

### Datacenter requirement

Serverless network volumes must live in a serverless-capable DC; **`EU-RO-1`** is
the default and the one the CPU/serverless examples standardize on
(`network_volume.py:42`; `flash-examples/04_scaling_performance/02_datacenters/README.md`
— "CPU endpoints support: `EU-RO-1`"). Keep BOTH the volume and the endpoint on
`EU-RO-1`. A volume is single-DC; an endpoint reading it must be pinned to the
same DC (the `datacenter=` arg, `endpoint.py:476-478`).

---

## d. Async API + timeouts

### `/run` retains; `/runsync` does not (for long jobs)

- `run(input)` → `POST {url}/run`, returns an `EndpointJob` wrapping the
  retained job id (`endpoint.py:866-886`). Poll with
  `job.status()` → `GET {url}/status/{id}`, which updates `_data` so
  `job.output`/`job.error`/`job.done` reflect latest (`endpoint.py:113-128`).
  Terminal statuses: `COMPLETED, FAILED, CANCELLED, TIMED_OUT`
  (`endpoint.py:24`).
- `runsync(input, timeout=60.0)` → `POST {url}/runsync` with a **60s default
  client timeout** (`endpoint.py:888-898`; `DEFAULT_RUNSYNC_TIMEOUT_S = 60`
  `serverless.py:48`). For a job that takes minutes (50GB load + diffusion
  decode), the HTTP call times out and you lose the handle — hence "runsync does
  NOT retain results." Use `/run` + poll (your `client.py` already does).
- `cancel(job_id)` → `POST {url}/cancel/{id}` (`endpoint.py:900-909, 124-128`).
- `job.wait(timeout=None)` polls with exponential backoff
  (0.25s→5s, factor 1.5, `endpoint.py:27-29, 131-207`), tolerates up to 5
  consecutive transient httpx errors (`endpoint.py:33, 181-204`), raises
  `TimeoutError` on deadline. Raw REST your `client.py` uses
  (`/run`, `/status/{id}`) hits `https://api.runpod.ai/v2/{id}` (`core/urls.py:68`,
  `endpoint.py:811-815`).

### `execution_timeout_ms` and the server-side job TTL

`execution_timeout_ms` (decorator arg, `endpoint.py:413`; → `executionTimeoutMs`
`endpoint.py:563`) is the **server-side max execution time** per job — set it
generously (your 1.5M ms = 25 min is right for a 50GB cold-load + decode). This
is the cap that lets `/run` results stay retrievable while the job runs; it is
separate from the `runsync`/httpx client timeout above.

### `FLASH_SENTINEL_TIMEOUT`

Applies only to **cross-endpoint / load-balanced sentinel calls** (one flash
endpoint calling another), default **90s** (`flash_sentinel.py:31-49`),
override via `FLASH_SENTINEL_TIMEOUT=<seconds>` (`flash_sentinel.py:43-49`;
docs `flash-examples/docs/cli/README.md:284`). It does NOT govern your external
`/run` polling — irrelevant to a single QB endpoint driven by `client.py`. Bump
it only if the worker itself fans out to other flash endpoints.

---

## e. Stale workers — why, and how to force new code without `undeploy`

### Why a warm worker serves old code

On every `flash deploy`, code-only changes are detected by a **source
fingerprint** (SHA-256 of your source files, `build.py:34-50`,
`build.py:291`) injected into each resource's env as `_FLASH_SOURCE_FINGERPRINT`
(`cli/utils/deployment.py:121-129`):

```python
# Inject source fingerprint into each resource's env so that code-only
# changes (no resource config diff) still trigger a rolling release.
source_fingerprint = local_manifest.get("source_fingerprint")
if source_fingerprint:
    for resource_config in local_manifest.get("resources", {}).values():
        env = resource_config.setdefault("env", {})
        env["_FLASH_SOURCE_FINGERPRINT"] = source_fingerprint
```

But `env` is a **rolling** change, NOT a **structural** one:
`_has_structural_changes` only flags `gpus, gpuIds, imageName, flashboot,
allowedCudaVersions, cudaVersions, minCudaVersion, instanceIds`
(`serverless.py:1262-1313`) — `env` is explicitly listed under "rolling changes
(no version increment)" (`serverless.py:1273-1277`). Rolling changes update the
template/env but rely on the server to roll workers; a **warm flashboot worker**
(snapshot reuse, `flashboot=True` default `endpoint.py:413`,
`serverless.py:561-567`) can keep serving its already-imported handler from the
in-memory snapshot. Net effect: same `workerId`, stale handler — exactly what you
saw. (`undeploy --all --force` works because it deletes the endpoint+template
entirely — but a fresh `create_endpoint` yields a new endpoint id.)

### Forcing new code WITHOUT undeploy (keep the endpoint id)

Trigger a **structural** change so the platform increments the endpoint version
and recreates workers (`serverless.py:1113-1118, 1262-1271`). Cheapest reliable
levers:

1. **Bump the image tag** via `FLASH_IMAGE_TAG` or `FLASH_GPU_IMAGE` between
   deploys — `imageName` is structural (`serverless.py:1292`). If you adopt the
   custom-image recipe (§b/§f), every image rebuild+retag forces recreation for
   free.
2. **Toggle `flashboot`** (structural, `serverless.py:1295`): deploy once with
   `flashboot=False` to drain warm snapshots, then back to `True`. Heavy-handed
   but deterministic.
3. **Disable flashboot for dev iteration** (`flashboot=False`): no snapshot
   reuse means each cold start re-imports fresh code — slower cold starts, but no
   stale handler. Re-enable for production.

There is **no `flash redeploy --force-recycle`** verb in the vendored CLI
(`cli/commands/`: `init, run, build, deploy, env, apps, undeploy, update,
login, preview`). The version/recycle mechanism is purely "structural field
changed → server bumps version." `undeploy --cleanup-stale`
(`undeploy.py:75-116`) only prunes tracking for already-deleted endpoints; it is
not a recycle.

---

## f. Custom image for a code function — what `image=` does vs `FLASH_GPU_IMAGE`

Two distinct mechanisms — do NOT conflate them:

- **`FLASH_GPU_IMAGE` (env var)** — changes the base image your **code**
  `@Endpoint` runs on. Your decorated function still runs; flash bundles its
  handler into the tarball and the worker imports your function on that base
  (`constants.py:90-95`; `build.py:395-405`). **This is how you get your own
  torch/transformers under a normal code function.** (§b)

- **`image=` (decorator arg)** — switches the endpoint into **external-image
  client mode**: it deploys a *pre-built third-party server image* and turns the
  `Endpoint` object into an HTTP/QB **client** that posts raw JSON to that
  image's own API. Your Python body is NOT executed.
  Grounded: `is_client` is true when `image=` is set (`endpoint.py:496-504`);
  using `image=` as a decorator raises (`endpoint.py:657-663`); the docstring:
  *"external image (deploy a pre-built image, call it as an API client)"*
  (`endpoint.py:356-367`); `imageName` is passed straight to the resource
  (`endpoint.py:602-604`) and `__init__` logs *"using user-supplied image ...
  (overrides Flash runtime image)"* (`endpoint.py:457-462`). This is the vLLM/TEI
  pattern (`vllm.post("/v1/completions", ...)`), not a way to run *your* diffusion
  code.

**Conclusion:** to run *our* `accept_canvas`/diffusion code on a custom
torch+transformers base, use **`FLASH_GPU_IMAGE`**, not `image=`. Reserve
`image=` for if we ever wrap DiffusionGemma behind a standalone server image and
just call it.

---

## RECOMMENDED RECIPE — DiffusionGemma on a Flash A100-80GB, clean + cached

Goal: a clean, matched `torch+torchvision+transformers` stack and the 50GB model
persisted on a NetworkVolume, with reliable cold starts and no stale-worker
surprises.

### 1. Build a custom worker image (fixes torch once, at image-build time)

`Dockerfile` (based on the flash GPU image so the runtime/Python-3.12 layout and
the runpod handler are intact):

```dockerfile
FROM runpod/flash:py3.12-latest
# Replace the broken preinstalled torch with a matched cu128 stack.
RUN python3.12 -m pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cu128 \
      torch==<known-good> torchvision==<matched> torchaudio==<matched>
# A transformers release that supports DiffusionGemma + accept_canvas.
RUN python3.12 -m pip install --no-cache-dir \
      "transformers==<verified>" accelerate sentencepiece pillow
# Smoke-test the import chain at build time so a bad combo fails the build, not the worker.
# NOTE (CORRECTED 2026-06-28, live build): do NOT probe
# `flex_attention.setup_compilation_env` — that public symbol does NOT exist in
# torch 2.9.0 (it is the PRIVATE `_set_compilation_env`); probing it fails the
# build for a hallucinated reason even on a perfectly good stack. The gate that
# actually matters is importing the MODEL CLASS, which forces transformers'
# masking_utils to resolve its flex_attention imports against the installed torch:
RUN python3.12 -c "import torch, torch._dynamo, transformers; from transformers import DiffusionGemmaForBlockDiffusion; print('SMOKE OK', torch.__version__, transformers.__version__)"
```

Build for `linux/amd64`, push to a registry the RunPod workers can pull. Pin the
exact `torch`/`transformers` versions you've verified import cleanly together
(the build-time smoke test is the gate). **Verified combo (live build, 2026-06-28):**
torch 2.9.0 / torchvision 0.24.0 / torchaudio 2.9.0 (cu128) + transformers 5.11.0 —
`SMOKE OK`, and `DiffusionGemmaForBlockDiffusion` imports clean. This FALSIFIES the
"base-image torch is the blocker" worry: pristine torch 2.9.0 from the cu128 index
is internally consistent and ABI-matches transformers 5.11.0.

### 2. Point Flash at that image (env override, structural → also kills stale workers)

```bash
export FLASH_GPU_IMAGE=<registry>/diffgemma-worker:cu128-v1   # bump -vN each rebuild
```

Because `imageName` is structural (`serverless.py:1292`), changing the tag on the
next deploy recreates workers — no `undeploy`, endpoint id preserved (§e).

### 3. NetworkVolume for model + pip cache, pinned to EU-RO-1

```python
from runpod_flash import Endpoint, GpuType, DataCenter, NetworkVolume

vol = NetworkVolume(name="diffgemma-vol", size=200, datacenter=DataCenter.EU_RO_1)

@Endpoint(
    name="diffgemma",
    gpu=GpuType.NVIDIA_A100_80GB_PCIe,
    datacenter=DataCenter.EU_RO_1,          # MUST equal the volume DC
    volume=vol,
    workers=(0, 1),
    idle_timeout=600,
    flashboot=True,                         # keep for prod; set False while iterating to avoid stale handler
    execution_timeout_ms=1_500_000,         # server-side job TTL (25 min) so /run stays retrievable
    env={
        "HF_HOME": "/runpod-volume/hf",
        "HF_HUB_CACHE": "/runpod-volume/hf/hub",
        "PIP_CACHE_DIR": "/runpod-volume/pipcache",
        "HF_TOKEN": os.environ.get("HF_TOKEN", ""),
    },
    # transformers can also live in the image; if listed here it installs at
    # build-time into the tarball (pure-python wheel, reliable). torch is
    # auto-excluded regardless, so it MUST come from the image.
    dependencies=["transformers==<verified>", "accelerate", "sentencepiece", "pillow"],
)
def diffgemma(**payload):
    import torch, transformers   # both now from the clean image — NO runtime pip, NO dynamo shim
    ...
```

Once the image is clean, **delete the runtime workarounds from `gpu_worker.py`**:
the `pip install torchvision --no-deps`, the `torch._dynamo` health probe, and the
`setup_compilation_env` monkeypatch (`gpu_worker.py:23-44`) are all
symptom-patches for the broken base-image torch and become dead weight.

### 4. Invoke with `/run` + poll (already correct)

Keep `client.py`'s `/run` → `/status/{id}` loop (`endpoint.py:866-886, 113-122`)
— never `runsync` for a multi-minute job (60s client timeout drops the handle,
§d). First call downloads the 50GB model into `/runpod-volume/hf`; subsequent
cold starts mount it (seconds, not minutes).

### 5. Verify cold start cleanliness

Submit `{"mode":"probe"}` after deploy; assert the returned `torch`/`transformers`
versions match the image, `dynamo == true` (not an error string), and
`config_ok == true`. If `dynamo` ever reports a TypeError again, the image's torch
regressed — rebuild the image, bump `FLASH_GPU_IMAGE` tag, redeploy.

### Pitfall checklist

- Stay on **Python 3.12** — other versions trigger an in-image torch reinstall
  (`constants.py:1-14`).
- Volume and endpoint MUST share **EU-RO-1**.
- Don't `--exclude transformers` to dodge the 1500MB tarball cap
  (`constants.py:124`) — that's the likely cause of the "transformers missing
  some deploys" flakiness (§a). If the tarball is too big, move transformers into
  the image instead and drop it from `dependencies=`.
- `image=` is the WRONG knob for our code function — use `FLASH_GPU_IMAGE` (§f).
