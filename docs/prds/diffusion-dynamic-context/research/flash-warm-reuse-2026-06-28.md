---
type: research
status: active
tags: [research, diffusion, deployment]
---

# Flash warm reuse — does FlashBoot snapshot VRAM, where to load the 50GB model, and how to keep one worker hot

Follow-up to `flash-deployment-stability-2026-06-28.md`. Goal: avoid cold
redeploys, maximize warm reuse, and stop paying the 66-147s model reload on every
recycle. Source-grounded from `reference-code/flash/` and
`reference-code/runpod-python/`; every load-bearing claim cites `file:line`.
Where the source is silent it says so — and that silence is itself the headline
finding.

## TL;DR

- **THE answer to "does FlashBoot snapshot GPU/VRAM?": the open-source code does
  NOT implement FlashBoot at all — so it cannot be confirmed OR refuted from
  `reference-code/`.** Both repos only (a) *toggle* it on the endpoint
  (`flashBootType: FLASHBOOT`, `runpod-python/.../mutations/endpoints.py:26`,
  `flash/.../serverless.py:571`) and (b) treat an **HTTP 400 on job-acquire as
  the expected "FlashBoot enabled" signal**
  (`runpod-python/.../rp_job.py:150`). There is **zero** snapshot/restore/VRAM
  /checkpoint code for FlashBoot in either repo. FlashBoot is a **RunPod
  platform-side feature**; its snapshot scope (container-only vs CPU-process vs
  GPU-VRAM) is **not in the source we have**. This must be settled by
  MEASUREMENT (§5), not by reading code — anyone claiming "source proves VRAM is
  snapshotted" is guessing.
- **Strong corroborating signal that Flash's OWN serialization does NOT carry a
  loaded model:** Flash's class wrapper explicitly resets init state on
  pickle — `__getstate__` sets `state["_initialized"] = False`
  (`execute_class.py:236`) and `_ensure_initialized()` re-runs construction on
  the far side (`execute_class.py:250-266`). So at the Flash layer, a restored
  worker re-initializes; it does not deserialize a live model. Whether the
  *platform's* FlashBoot layer underneath does something richer is the open
  question.
- **Model-load placement IS a real, source-backed lever — independent of the
  FlashBoot unknown.** Our worker loads the model **lazily inside the request
  handler** (`_load(tok)` on the first request, cached in `_CACHE`,
  `gpu_worker.py:40-64,210`). Flash's documented pattern is a **class-based
  endpoint whose `__init__` loads the model**, "instantiated once per worker
  (singleton)" (`Deployment_Architecture.md:46`,
  `Flash_Deploy_Guide.md:79-92`). Moving the load to construction-time makes the
  model part of *worker-ready* state instead of *first-request* state — which is
  the only state a snapshot-at-ready could ever capture. We cannot prove from
  source that this lands it in a FlashBoot snapshot (snapshot timing is
  platform-side, §2), so this is a **hypothesis to measure**, with a clear
  mechanism for why it could help and zero downside if it doesn't.
- **Keep-warm for a stable-code iteration session is the cheap, certain win.**
  `idle_timeout` is a *rolling* field (no recycle to change it,
  `serverless.py:1276`), and the docs say plainly: "Set `workers=(1, N)` to keep
  workers warm" (`Flash_Deploy_Guide.md:365`). During a session where CODE
  doesn't change, there is NO recycle — the 54x-warm batch is exactly right.
  Raise `idle_timeout` and/or set `workers_min=1` to widen the warm window.
- **The unavoidable recycle (code changed → new `FLASH_GPU_IMAGE` tag):** how
  fast it comes back depends entirely on whether FlashBoot restores VRAM — the
  §1 unknown. Baking the model into the image does NOT remove the VRAM-load cost
  (it removes *download*, which our network volume already removes); it only
  helps if paired with a real VRAM snapshot. So the recycle cost is bounded
  below by "load 50GB into VRAM" UNLESS FlashBoot snapshots VRAM. Measure first
  (§5); don't pre-optimize the image around an unproven assumption.

---

## 1. The FlashBoot snapshot mechanism — what the source actually contains

Every FlashBoot touch point in both vendored repos:

**Toggle (client → platform), runpod-python SDK:**
`reference-code/runpod-python/runpod/api/mutations/endpoints.py:25-26`

```python
if flashboot:
    input_fields.append('flashBootType: FLASHBOOT')
```

**Toggle, Flash framework:**
`reference-code/flash/src/runpod_flash/core/resources/serverless.py:571`

```python
        if self.flashboot:
            ...
            self.flashBootType = "FLASHBOOT"
```

**The ONLY runtime awareness of FlashBoot in the worker loop** —
`reference-code/runpod-python/runpod/serverless/modules/rp_job.py:148-151`:

```python
        if response.status == 400:
            log.debug("rp_job | Received 400 status, expected when FlashBoot is enabled.")
            return
```

That is the complete surface. The worker, on polling for a job, may get a 400
that simply means "FlashBoot is managing this worker's lifecycle, no job for
you" — and the SDK just returns quietly. There is **no code that writes a GPU
snapshot, freezes a process, dumps VRAM, or restores any of it.** The
architecture doc's worker "State Management" is exclusively the **job-id
progress file** `.runpod_jobs.pkl` (`ARCHITECTURE.md` State Lifecycle; the
`worker_state.py:76-210` "snapshot" comments are a *job-id* mirror to the ping
process, **not** model/VRAM state).

**Conclusion (honest):** FlashBoot's snapshot/restore is implemented on the
RunPod platform, outside `reference-code/`. From source we **cannot determine**
whether it captures GPU VRAM, CPU process memory, or only the container
filesystem. Hypothesis "FlashBoot restores a loaded 50GB model in seconds" is
**neither confirmed nor refuted by the source** — it is an empirical question
(§5).

> Corroborating (Flash-layer, not platform-layer): when Flash serializes a
> class-based remote instance, it deliberately drops the live state —
> `execute_class.py:230-248`: `_UNPICKLABLE_ATTRS = {"_init_lock", "_stub"}`,
> `__getstate__` sets `_initialized = False`, and `_ensure_initialized()`
> reconstructs on first use (`:250-266`). Flash's own model of a "restored"
> worker is *re-initialize*, not *resurrect a loaded model*. This biases the
> prior toward "VRAM is NOT for free" — but it is the framework layer, not proof
> about the platform's FlashBoot.

## 2. WHEN is the worker snapshotted, and does model-load placement matter?

Two facts from source frame this:

1. **Our model load is request-triggered, not ready-triggered.** `gpu_worker.py`
   loads inside the handler: `_load(tok)` is called only when a request arrives
   in `probe`/`introspect`/`clamp_smoke`/`generate`/`infill`
   (`gpu_worker.py:210, 289, 439, …`), and caches into the module-level `_CACHE`
   (`:30, 50-64`). At *worker-ready* (process started, polling for jobs) the
   50GB model is **not yet loaded**. So any snapshot taken at worker-ready — the
   natural point for a fast-cold-start cache — would capture an EMPTY `_CACHE`.

2. **Flash's documented model-load home is the class constructor, run once per
   worker.** `Deployment_Architecture.md:46`: "The class is instantiated once
   per worker (singleton), and methods are dispatched per request."
   `Flash_Deploy_Guide.md:79-92` shows `__init__` doing `self.pipe =
   pipeline(...)`. This moves the heavy load from *first request* to *instance
   construction*.

**What the source does NOT tell us:** the exact wall-clock moment the platform
takes the FlashBoot snapshot — at process-ready (before the singleton is
constructed)? after the singleton is constructed? after the first job? on an
external signal? `reference-code/` has none of this (it's platform-side, §1).

**Therefore the recommendation, stated honestly:**

- Moving the model load to construction-time / module-import is the ONLY way it
  could *possibly* be in a snapshot taken before the first request. It cannot
  hurt (the model still loads exactly once per worker; warm reuse via the
  singleton/`_CACHE` is unchanged), and it is the documented idiom.
- BUT we must not claim it "gets the model into the FlashBoot snapshot" — that
  depends on the unknown snapshot timing. It is a **measured bet** (§5),
  justified by mechanism, not a source-proven win.
- Concretely: either (a) convert `diffgemma` to a class-based endpoint with
  `__init__` calling the current `_load`, or (b) cheaper, call `_load` at
  **module import** in `gpu_worker.py` (top-level, after `WORKER_SHA`) so the
  model is resident before the first job. Both preserve the existing `_CACHE`
  warm-reuse path. Then run the §5 experiment to see if cold-restore time drops.

## 3. Keep-warm economics for a stable-code session (the certain win)

From source:

- `idle_timeout` is a **rolling** config field — "Timeout values (idleTimeout,
  executionTimeoutMs)" are listed under "Rolling changes (no version increment)"
  (`serverless.py:1273-1276`). So you can raise it and the live warm worker is
  NOT recycled to apply it.
- The official keep-warm guidance: "Workers take 30-60s to cold start. Set
  `workers=(1, N)` to keep workers warm." (`Flash_Deploy_Guide.md:365`,
  echoed `Load_Balancer_Endpoints.md:157`).

**Pattern for an active iteration session (code stable):**

- There is **no recycle while code is stable** — a redeploy is what recycles
  (prior doc §1), and you're not redeploying. So the warm worker persists as
  long as it doesn't drain idle.
- Two ways to hold it: (1) keep calling within the idle window — our
  `idle_timeout=600` gives a 10-minute grace per call; the 54x-warm batch proves
  this works and IS the right pattern. (2) Set `workers_min=1` (i.e.
  `workers=(1,1)`) so the platform keeps one worker resident regardless of
  traffic. **Caveat from the prior doc:** `(1,1)` *recycle-on-redeploy* is the
  broken case (AE-2940/2941/2942, `e2e/test_redeploy.py:5-7`) — but that only
  bites when you redeploy; for pure keep-warm with stable code it's the standard
  knob. If you keep `(0,1)`, just don't let 600s of silence elapse.
- To **extend the warm window**: raise `idle_timeout` (rolling, applies without
  recycle). Our `_load` already makes warm reuse free: on a warm hit the model
  is in `_CACHE` so `_load` returns `load_s ≈ 0` (`gpu_worker.py:50-64`) — that
  field is our live cold-vs-warm signal.

**Does an in-flight or queued call reset the idle clock?** The idle accounting is
platform-side; the SDK does not implement it (`reference-code/` has the toggle
and timeout *value* only). **Source does not specify** the reset semantics. The
observable contract is "worker stays alive `idle_timeout` seconds after the last
*completed* job"; treat queued/in-flight reset as unverified until measured.

## 4. The unavoidable recycle (code changed) — how fast can it come back?

When code changes you must recycle via a new `FLASH_GPU_IMAGE` tag (prior doc §2,
id-preserving) or undeploy+deploy (new id). The come-back cost:

- **If FlashBoot snapshots VRAM** (unknown, §1): a restore could bring the loaded
  model back in seconds — this is the win RunPod was chosen for, but it is
  **unproven from source**.
- **If it does not:** the new worker cold-starts and must load 50GB into VRAM →
  the 66-147s we see. Our network volume already removes the *download*
  (`gpu_worker.py:33-37`, model cached on `/runpod-volume`); the residual cost is
  CPU→GPU load + init, which neither a network volume nor baking the model into
  the image removes (both only address bytes-on-disk, not bytes-into-VRAM).
- **"Bake the model into the image":** from source, the image (`FLASH_GPU_IMAGE`)
  is the worker base image; nothing in `reference-code/` mmaps model weights or
  pre-warms VRAM from the image. Baking weights in would shave the download we've
  already eliminated via the volume — **no VRAM-load benefit** without a real
  snapshot. Not worth the image bloat on current evidence.
- **Warm-pool:** `workers_min ≥ 1` keeps a resident worker, but on a
  *version-triggering* redeploy that resident worker is *recreated* (that's the
  point of the version bump) — and the `(1,1)` recreate path is the known-broken
  one. A multi-slot `workers=(1,2)`+ pool *might* drain-and-replace more
  gracefully (the multi-worker redeploy tests `TestRedeployMultiWorker*` DO pass
  with zero-error cutover, `test_redeploy.py:200-345`), but that costs an
  always-resident A100. Evaluate only if the §5 measurement shows recycle is
  genuinely unavoidable and slow.

**Bottom line:** the recycle floor is "load 50GB into VRAM" unless FlashBoot
restores VRAM. Settle that with §5 before spending effort on image-baking or
warm-pools.

## 5. Concrete experiment — measure cold-restore-with-FlashBoot vs the 66-147s

Owner is driving the worker; this is the protocol for them to run (no calls from
us). Everything needed is already in the worker except a worker-identity field.

**Preadd (one tiny worker change):** include the worker identity so we can tell a
*reused* worker from a *recycled* one — the same trick the Flash e2e test uses
(`test_redeploy.py:_versioned_worker`: `os.environ.get("RUNPOD_POD_ID")`). Add to
`info` in `gpu_worker.py` alongside `worker_sha`:

```python
"worker_id": os.environ.get("RUNPOD_POD_ID", "unknown"),
```

`load_s` is already emitted (`gpu_worker.py:210/290/…`): ≈0 on a warm `_CACHE`
hit, full load time on a cold load. `worker_sha` (code hash) and `worker_id`
(process identity) together disambiguate the three cases below.

**Experiment A — confirm warm reuse (baseline, expect cheap):**
1. Cold-call `clamp_smoke` once → record `worker_id=W1`, `load_s≈cold`.
2. Immediately call again ×N within `idle_timeout` → expect same `worker_id=W1`,
   `load_s≈0`. This is the 54x-warm pattern; confirms the singleton/`_CACHE`
   path. (No FlashBoot involved — just a live warm worker.)

**Experiment B — does idle-drain + cold call reload (FlashBoot OFF-equivalent
floor)?**
1. Note `worker_id=W1`, then STOP all traffic for `> idle_timeout` (600s) so the
   worker scales to zero.
2. One call → record `worker_id` and `load_s`. New `worker_id` (≠W1) +
   `load_s≈cold` (66-147s) = full reload on cold start. This is the floor we're
   trying to beat. (Note: this path uses FlashBoot if enabled — so if `load_s`
   here is already seconds-not-minutes, FlashBoot IS restoring loaded state.)

**Experiment C — the decisive FlashBoot-VRAM test (current lazy-load worker):**
- With `flashboot=True` (our current config), repeat B several times. If on the
  2nd+ cold start (after a real scale-to-zero) `load_s` collapses to seconds
  while `worker_id` changes, FlashBoot is restoring a snapshot that *includes the
  loaded model* → VRAM (or at least loaded-process) snapshot CONFIRMED
  empirically. If `load_s` stays at 66-147s every cold start, FlashBoot is NOT
  restoring model state for the current (lazy-load-in-handler) layout.

**Experiment D — does moving model-load to init change C?**
- Apply §2's change (load at module-import or class `__init__`). Redeploy via a
  new `FLASH_GPU_IMAGE` tag (id-preserving recycle). Repeat C. If `load_s` on
  cold restore now collapses where it didn't in C, then **load placement
  determines what FlashBoot captures**, and the recommendation (move load to
  init) is proven by measurement. If no change, FlashBoot doesn't snapshot VRAM
  regardless of placement, and the recycle floor stands → keep-warm (§3) is the
  only lever.

Record for each call: `worker_id`, `worker_sha`, `load_s`, `gen_s`,
`attn_impl`, wall-clock since deploy. The matrix of (worker_id changed?) ×
(load_s cold/warm) answers every open question above with live numbers instead
of inference.

---

## Appendix — files read (beyond the prior doc's list)

- `reference-code/runpod-python/runpod/serverless/modules/rp_job.py:148-151`
  (the entire FlashBoot runtime surface — the 400 handshake)
- `reference-code/runpod-python/runpod/api/mutations/endpoints.py:25-26,71-78`
  (flashBootType toggle in the saveEndpoint mutation)
- `reference-code/runpod-python/runpod/serverless/modules/worker_state.py:76-210`
  (the "snapshot" here is job-id mirror state, NOT model/VRAM — ruling it out)
- `reference-code/runpod-python/ARCHITECTURE.md` (State Lifecycle: disk state is
  `.runpod_jobs.pkl` job progress only)
- `reference-code/flash/src/runpod_flash/core/resources/serverless.py:567-571`
  (flashboot → flashBootType), `:1273-1276` (idleTimeout rolling)
- `reference-code/flash/src/runpod_flash/execute_class.py:230-266`
  (`__getstate__` resets `_initialized=False`; `_ensure_initialized` re-runs —
  Flash-layer "restore" is re-init, not model resurrection)
- `reference-code/flash/docs/Deployment_Architecture.md:29,46` (class singleton
  instantiated once per worker)
- `reference-code/flash/docs/Flash_Deploy_Guide.md:65-92` (class `__init__`
  model-load idiom), `:365` (workers=(1,N) keep warm)
- `reference-code/flash/docs/Flash_SDK_Reference.md:92-94`,
  `Load_Balancer_Endpoints.md:157` (cold-start 30-60s)
- `tmp/flash-diffgemma/gpu_worker.py:30-64,210` (our lazy `_load` + `_CACHE` +
  network-volume model cache)
