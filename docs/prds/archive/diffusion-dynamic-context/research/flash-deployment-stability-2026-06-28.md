---
type: research
status: active
tags: [research, diffusion, deployment]
---

# Flash deployment stability — why a warm worker keeps old code, and the stable deploy procedure

Source-grounded from the vendored RunPod Flash framework
(`reference-code/flash/`) and the RunPod SDK (`reference-code/runpod-python/`).
Every load-bearing claim cites `file:line`. Where the source is silent it says
so — no inference is presented as fact.

## TL;DR

- **Root cause (proven from source):** A code-only change is NOT a
  version-triggering change on the RunPod platform. Flash smuggles code changes
  past the platform's no-op detection by injecting a source-hash env var
  (`_FLASH_SOURCE_FINGERPRINT`) so the deploy isn't a pure no-op — but an **env
  change is a *rolling* change, NOT a version increment**, and **only a version
  increment recreates workers server-side**. A warm worker therefore keeps
  serving the OLD bundle until it **scales to zero on `idle_timeout` and a later
  call cold-starts a fresh worker** that reads the new build. Cites below.
- **Our config makes it worse:** `workers=(0,1), idle_timeout=600`. The worker
  stays warm 10 minutes between calls. If we deploy and immediately probe
  (resetting the idle clock), the warm worker **never drains**, so it serves old
  code indefinitely. That is exactly the `worker_sha`-missing symptom we saw.
- **The redeploy E2E test confirms the model:** for scale-to-zero `(0,1)` it
  *waits out an idle drain* (`_IDLE_WAIT = 60s`) then polls up to
  `_RECYCLE_TIMEOUT = 300s` ("recycle observed at >120s in practice") and
  asserts the **`worker_id` changed**. New code goes live only after the worker
  recycles, never instantly. Single-slot always-on `(1,1)` recycle is **known
  broken** (tests excluded, AE-2940/2941/2942).
- **The ONE reliable force-fresh that preserves a recycle but NOT the id:**
  `flash undeploy --all && flash deploy` deletes the endpoint
  (`delete_endpoint(self.id)`) and creates a new one → **new endpoint id**. Use
  it when you must be certain, but know `DIFFGEMMA_EP` changes.
- **The recommended force-fresh that PRESERVES the endpoint id:** bump
  `FLASH_GPU_IMAGE` to a new tag. `imageName` IS a structural field
  (`serverless.py:1294`) → server-side version increment → worker recreation,
  **in place** (`update()` sends `payload["id"] = self.id`). Same image string =
  no structural change = warm worker survives.
- **Always verify, never trust:** deploy → force-recycle → poll until
  `worker_sha == local_sha` → only then measure. `worker_sha` is already baked
  into every response (`gpu_worker.py:7-26,181`).

---

## 1. Root cause — the full chain, with source

### 1a. Flash itself documents "no hot reload"

`reference-code/flash/src/runpod_flash/cli/docs/flash-deploy.md` (Architecture
section):

> **No hot reload:** code changes require a new deployment

A "new deployment" updates the endpoint's build pointer/config; it does NOT, by
itself, kill a running worker. The rest of this section is *why*.

### 1b. A code-only change is invisible to the platform's diff — so Flash injects a fingerprint

`flash build` computes a SHA-256 over the user's source files:

`reference-code/flash/src/runpod_flash/cli/commands/build.py:34-61`

```python
def compute_source_fingerprint(project_dir: Path, files: list[Path]) -> str:
    """Compute a SHA-256 fingerprint of project source files.

    Produces a deterministic hash that changes if and only if the user's
    source files change. Used to detect code-only changes that should
    trigger a rolling release even when resource config is unchanged.
    ...
```

…stored on the manifest at `build.py:291`:

```python
manifest["source_fingerprint"] = compute_source_fingerprint(project_dir, files)
```

On deploy, that fingerprint is injected into **each resource's env**:

`reference-code/flash/src/runpod_flash/cli/utils/deployment.py:120-129`

```python
# Inject source fingerprint into each resource's env so that code-only
# changes (no resource config diff) still trigger a rolling release.
# The fingerprint is computed during flash build from user source files.
source_fingerprint = local_manifest.get("source_fingerprint")
if source_fingerprint:
    for resource_config in local_manifest.get("resources", {}).values():
        env = resource_config.setdefault("env", {})
        env["_FLASH_SOURCE_FINGERPRINT"] = source_fingerprint
```

So the *only* thing that changes on the platform for a code-only redeploy is an
**env var**. Note the comment's own words: this triggers "a **rolling
release**", not a version increment.

### 1c. Env is a ROLLING change — and rolling changes do NOT recreate workers

`reference-code/flash/src/runpod_flash/core/resources/serverless.py:1112-1118`
(the `update()` docstring — the saveEndpoint mutation path):

```python
async def update(self, new_config: "ServerlessResource") -> "ServerlessResource":
    """Update existing endpoint with new configuration.

    Uses saveEndpoint mutation which handles both version-triggering and
    rolling changes. Version-triggering changes (GPU, template, volumes)
    automatically increment version and trigger worker recreation server-side.
    ...
```

`_has_structural_changes` (the version-trigger classifier) —
`serverless.py:1263-1300`:

```python
    """Check if config changes are version-triggering.

    Version-triggering changes cause server-side version increment and
    worker recreation:
    - Image changes (imageName via templateId)
    - GPU configuration (gpus, gpuIds, allowedCudaVersions, gpuCount)
    - Hardware allocation (instanceIds, locations)
    - Storage changes (networkVolumeId)
    - Flashboot toggle

    Rolling changes (no version increment):
    - Worker scaling (workersMin, workersMax)
    - Scaler configuration (scalerType, scalerValue)
    - Timeout values (idleTimeout, executionTimeoutMs)
    - Environment variables (env)
    ...
    structural_fields = [
        "gpus",
        "gpuIds",
        "imageName",
        "flashboot",
        "allowedCudaVersions",
        "cudaVersions",
        "minCudaVersion",
        "instanceIds",
    ]
```

The decisive facts, verbatim from source:

1. **Code is NOT in `structural_fields`** — code rides in as the
   `_FLASH_SOURCE_FINGERPRINT` env var, and **"Environment variables (env)"** is
   explicitly listed under **"Rolling changes (no version increment)"**
   (`serverless.py:1273-1278`).
2. **Only a version increment recreates workers**: "Version-triggering changes
   cause server-side version increment **and worker recreation**"
   (`serverless.py:1265-1266`); the inverse — rolling changes — get **no version
   increment**, hence **no worker recreation**.

Therefore a plain `flash deploy` of changed code = a rolling env update = **the
warm worker is NOT recreated**. It keeps the prior bundle. Only a *new* worker
(a cold start after the warm one scales to zero) loads the new build. This is
the bug, exactly.

> Note: `_has_structural_changes` is "now informational for logging. The actual
> version-triggering logic runs server-side when saveEndpoint is called"
> (`serverless.py:1278-1280`). The client list mirrors the server's; the server
> is authoritative. We cannot see the server code, but the redeploy E2E test
> (below) confirms the observable behavior matches this list.

### 1d. The redeploy E2E test is the definitive behavioral oracle

`reference-code/flash/e2e/test_redeploy.py`.

**For scale-to-zero `workers=(0,1)` — OUR topology** —
`TestRedeployScaleToZero.test_new_code_live_after_redeploy` does NOT expect the
warm worker to pick up new code. It deploys v2, then:

```python
_deploy(_versioned_worker(name, "v2"), name, tmp_path, env, "v2")
time.sleep(_IDLE_WAIT)  # let worker drain idle so the recycle can fire

elapsed, out_v2 = poll_until_version(
    endpoint_id, api_key, "v2", timeout=_RECYCLE_TIMEOUT, interval=30
)
...
assert out_v2["worker_id"] != worker_id_v1, (
    f"worker_id unchanged after redeploy: {worker_id_v1!r}"
)
```

with the constants (`test_redeploy.py:26-27`):

```python
_RECYCLE_TIMEOUT = 300  # seconds — CPU worker recycle observed at >120s in practice
_IDLE_WAIT = 60  # seconds without requests after deploy so the worker can drain idle and trigger recycle
```

Reading: new code becomes live **only after** (a) the worker is allowed to
**drain idle** and (b) it **recycles** — proven by asserting the **worker id
changed**. The recycle is observed at **>120s, allowed up to 300s**. There is no
assertion anywhere that an existing warm worker hot-swaps code.

**Single-slot always-on `workers=(1,1)` is known BROKEN** —
`test_redeploy.py:5-7` (module docstring, tests *excluded*):

```text
Excluded from this file (known platform failures, tracked in Linear):
  TestRedeployAlwaysOn, TestRedeployNoDowntime, TestRedeployInFlight
  → single-slot always-on (workers=(1,1)) recycle not working (AE-2940/2941/2942)
```

Implication for us: do **not** move to `workers=(1,1)` to "stay warm" — its
recycle is the very thing that's broken. `(0,1)` recycles correctly, but only
through an idle drain.

### 1e. Why OUR specific deploy stayed stale: `idle_timeout=600` + active probing

`gpu_worker.py:154-156`: `workers=(0,1), idle_timeout=600`. The scale-to-zero
recycle only fires after the worker **drains idle** (`test_redeploy.py:186`
comment: "let worker drain idle so the recycle can fire"). With
`idle_timeout=600`, the worker stays warm for 10 minutes after the last call. If
we deploy and then immediately/periodically probe (as we did to "check if it's
fresh"), **every probe resets the idle clock**, the worker never drains, the
recycle never fires, and it serves old code forever. That is precisely the
"`flash deploy` reported success but the live worker had no `worker_sha` field"
observation — the warm pre-edit worker was still answering.

---

## 2. Forcing the live worker onto new code — ranked by reliability

| Rank | Method | Recycles warm worker? | Endpoint id | Cost / caveat | Source |
|------|--------|----------------------|-------------|---------------|--------|
| 1 (most certain) | `flash undeploy --all && flash deploy` | Yes — endpoint is **deleted** then **recreated**; no warm worker can survive | **CHANGES** (new endpoint) | Full cold start of the new endpoint; you must update `DIFFGEMMA_EP` | `serverless.py:_do_undeploy` → `client.delete_endpoint(self.id)`; redeploy creates new |
| 2 (id-preserving) | Bump `FLASH_GPU_IMAGE` to a new tag, then `flash deploy` | Yes — `imageName` is structural → server version increment → worker recreation **in place** | **PRESERVED** (`update()` sends `payload["id"] = self.id`) | Requires building/pushing a new image tag; recreation observed up to ~300s | `serverless.py:1294` (imageName structural), `:1117-1118` (version-trigger recreates), `update()` `payload["id"] = self.id` |
| 3 (passive, slow) | Plain `flash deploy`, then **stop all traffic** and wait `> idle_timeout` (600s), then a single cold call | Eventually — the warm worker drains to zero, the next call cold-starts the new build | PRESERVED | Slow (>10 min of zero traffic for us) and **fragile**: any stray probe resets the idle clock | `test_redeploy.py:186-189`; `idle_timeout` is rolling (`serverless.py:1276`) |
| — (do NOT use) | `workers=(1,1)` always-on to force a "live" worker | Recycle **broken** | — | Known platform failure | `test_redeploy.py:5-7` |

Notes on the options the task asked about:

- **(d) a runpod-python "terminate/refresh workers" API:** the vendored SDK
  exposes endpoint create/update/delete and the worker job loop, but **no
  per-worker terminate/refresh call** is present in
  `reference-code/runpod-python/runpod/api/` (only `create_endpoint`,
  `update_endpoint`, `delete_endpoint`-style mutations in
  `runpod/api/mutations/endpoints.py`). **Source does not provide** a "kill this
  worker now" primitive we can call. So the recycle must be triggered the
  platform's way (version increment) or by deletion.
- **(e) flashboot:** see §4 — it changes cold-start speed, not the recycle
  decision.

### Recommended ONE procedure

Use **rank 2 when the endpoint id must stay stable** (normal iteration on a
fixed `DIFFGEMMA_EP`), and **rank 1 when you need a guaranteed-clean slate** (or
when an image bump isn't worth it). Both must be followed by the **`worker_sha`
preflight** in §3 — a deploy is never trusted on the CLI's "✓ deployed" line
alone, because that line only means the *endpoint config* was updated
(`deploy.py:266-269`), not that any worker is running the new code.

---

## 3. THE STABLE DEPLOY PROCEDURE

Goal: after this runs, you KNOW the live worker is running the exact local code,
proven by a content hash, before you measure anything.

Preconditions (already built):

- Every response carries `worker_sha` — a 12-char SHA-256 over
  `gpu_worker.py` + `diffgemma_common.py` bytes (`gpu_worker.py:7-26`,
  emitted at `:181`).
- `verify_fresh.py` computes the SAME hash locally over the SAME two files and
  asserts equality against a live `probe`.

Procedure (id-preserving path — preferred for iteration):

```bash
# 0. Compute the local fingerprint you EXPECT the worker to report.
LOCAL_SHA=$(python verify_fresh.py --print-local-sha)   # sha over gpu_worker.py + diffgemma_common.py

# 1. Force a version-triggering deploy that PRESERVES the endpoint id.
#    Bump the image tag so imageName changes (structural -> recreate in place).
export FLASH_GPU_IMAGE=<your-registry>/diffgemma:<NEW_TAG>     # NEW tag, even if Dockerfile content is identical
flash deploy                                                   # build + upload + saveEndpoint(version++)

# 2. STOP probing for a beat. Do NOT poll faster than necessary; each call you
#    make is fine for waiting-on-recycle (it triggers a cold start of the new
#    version) but the worker_sha gate below is what you trust, not the clock.

# 3. Preflight: poll the live `probe` until worker_sha == LOCAL_SHA, up to 300s.
python verify_fresh.py --endpoint "$DIFFGEMMA_EP" --expect "$LOCAL_SHA" --timeout 300
#    verify_fresh.py MUST:
#      - call mode="probe" (cheap, no 50GB load)
#      - read resp["worker_sha"]
#      - if absent  -> STALE (pre-worker_sha code) -> keep polling / fail at timeout
#      - if present but != LOCAL_SHA -> STALE (old bundle) -> keep polling
#      - if == LOCAL_SHA -> FRESH -> exit 0

# 4. ONLY on exit 0 from step 3 do you run clamp_smoke / generate / measure.
```

Guaranteed-clean-slate path (when you accept a new endpoint id):

```bash
LOCAL_SHA=$(python verify_fresh.py --print-local-sha)
flash undeploy --all          # deletes the endpoint (delete_endpoint(self.id))
flash deploy                  # creates a NEW endpoint -> NEW id
export DIFFGEMMA_EP=<new-id-from-deploy-output>   # the printed endpoint URL/id changed!
python verify_fresh.py --endpoint "$DIFFGEMMA_EP" --expect "$LOCAL_SHA" --timeout 300
# then measure
```

The non-negotiable invariant: **no measurement before
`worker_sha == LOCAL_SHA`.** The CLI "✓ deployed to production" line is not
proof — it is printed right after `saveEndpoint` returns (`deploy.py:262-269`),
which is the *config* update, not a running-worker fact.

---

## 4. flashboot semantics + caveats

What the source actually shows about `flashboot=True`:

- It sets the endpoint's boot type to FLASHBOOT —
  `serverless.py:567`: `self.flashBootType = "FLASHBOOT"`; mirrored in the SDK
  mutation `runpod/api/mutations/endpoints.py:25-26`
  (`input_fields.append('flashBootType: FLASHBOOT')`).
- It is a **cold-start accelerator**. RunPod's worker loop treats an HTTP **400
  on job-acquire as the expected FlashBoot signal**
  (`runpod-python/runpod/serverless/modules/rp_job.py:150`:
  `"Received 400 status, expected when FlashBoot is enabled."`; arch doc
  `ARCHITECTURE.md:978`). The architecture notes a general lazy-load cold-start
  reduction of **32-42%** (`ARCHITECTURE.md:1135,1150`) — that is the class of
  optimization FlashBoot belongs to (faster startup), not a change to *which*
  code a worker runs.
- **`flashboot` is itself a structural field** (`serverless.py:1295`): toggling
  it on/off is version-triggering and recreates workers.

Caveat / **honest unknown (source does not specify):** whether FlashBoot caches
a worker *snapshot* that could PIN old code across a recycle is **not described**
in the vendored source. The vendored code only shows the 400-handshake and the
lazy-load cold-start win; there is no snapshot-keying-by-build logic visible in
`reference-code/`. We therefore cannot assert from source that FlashBoot is
snapshot-safe across versions. **This is exactly why the `worker_sha` gate (§3)
exists** — it is the empirical check that closes this gap regardless of
FlashBoot internals. If a future stale-after-recycle case appears with FlashBoot
on, the first experiment is: deploy via the rank-1 path (undeploy+deploy, new
id) which cannot reuse any prior snapshot, and compare.

---

## 5. Endpoint-id rules (so `DIFFGEMMA_EP` stays correct)

Grounded in the deploy/update/undeploy paths:

- **In-place `flash deploy` (same config, code-only change):** id **PRESERVED**.
  The update path sends `payload["id"] = self.id` to `saveEndpoint`
  (`serverless.py` `update()`), and the manifest reconcile reuses the stored
  `endpoint_id` (`deployment.py:178-190`). Deploy docs confirm "Incremental
  updates: Only updates what changed, **preserving endpoint URLs**"
  (`flash-deploy.md`).
- **`flash deploy` with a changed `FLASH_GPU_IMAGE` tag (structural):** id
  **PRESERVED** — it's still an `update()` with `payload["id"] = self.id`; the
  structural change drives a server-side version increment + worker recreation
  *of the same endpoint*, not a new endpoint.
- **`flash undeploy --all` (or `flash undeploy <name>`) then `flash deploy`:** id
  **CHANGES**. `_do_undeploy` calls `client.delete_endpoint(self.id)`
  (`serverless.py`), the endpoint ceases to exist, and the next `flash deploy`
  provisions a brand-new endpoint with a new id. **You must re-read the printed
  endpoint id and update `DIFFGEMMA_EP`** after this path.
- **Network volume / datacenter:** `networkVolumeId` and `locations` are
  version-triggering (`serverless.py:1268-1271` docstring) but, like image, ride
  through `update()` in place — id preserved (volume is mounted into the same
  endpoint).

Rule of thumb: **only `undeploy` changes the id.** Everything reachable through
`flash deploy` (rolling or structural) keeps `kzonsp5b18hpq5`.

---

## Appendix — files read

- `reference-code/flash/src/runpod_flash/cli/commands/deploy.py` (CLI deploy:
  build→upload→`deploy_from_uploaded_build`→"✓ deployed" at :266)
- `reference-code/flash/src/runpod_flash/cli/utils/deployment.py`
  (reconcile + `_FLASH_SOURCE_FINGERPRINT` injection :120-129)
- `reference-code/flash/src/runpod_flash/cli/commands/build.py`
  (`compute_source_fingerprint` :34-61, set on manifest :291)
- `reference-code/flash/src/runpod_flash/core/resources/serverless.py`
  (`update()` :1110+, `_has_structural_changes` :1263-1300, `_do_undeploy`,
  flashboot :567)
- `reference-code/flash/src/runpod_flash/core/resources/resource_manager.py`
  (`get_or_deploy_resource` :222-320 — update vs redeploy decision)
- `reference-code/flash/src/runpod_flash/core/resources/constants.py`
  (`FLASH_GPU_IMAGE` → `imageName` override :34-39, `get_image_name`)
- `reference-code/flash/e2e/test_redeploy.py` (the behavioral oracle)
- `reference-code/flash/src/runpod_flash/cli/docs/flash-deploy.md`,
  `flash-undeploy.md`
- `reference-code/runpod-python/runpod/serverless/modules/rp_job.py` (FlashBoot
  400 handshake :150), `runpod/api/mutations/endpoints.py` (flashboot, idle,
  workersMin fields), `ARCHITECTURE.md` (cold-start, worker state lifecycle)
- `tmp/flash-diffgemma/gpu_worker.py` (our `worker_sha` fingerprint + `@Endpoint`
  config)
