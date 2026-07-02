---
type: reference
status: active
tags: [reference, agent, web]
---

# Custom worker image + the Seon-co-location latency play (2026-06-28)

> Why the custom image work is KEPT even though the torch fix turned out
> unnecessary: the image is the vehicle for running Seon's eval/parse/retrieve
> ON the worker, killing the per-step round-trip latency in capabilities #2/#3.

## The torch finding (corrects the ~12-cycle saga)

First live deploy + generate (endpoint `kzonsp5b18hpq5`, A100-80, 2026-06-28):

- **torch 2.9.1+cu128 (the STOCK `runpod/flash:py3.12-latest` base) WORKS.** The
  model loaded in **66 s** and `model.generate()` ran. `FLASH_GPU_IMAGE` did not
  take effect (worker reported 2.9.1, not our image's 2.9.0) — and it **didn't
  need to**.
- The entire "torch 2.9.1 is broken" premise was **wrong**. The real causes were
  (a) the **hallucinated `setup_compilation_env` smoke-test symbol** (doesn't
  exist in any torch 2.9.x — it's the private `_set_compilation_env`), and (b)
  the worker's own **runtime shims** (torchvision `--no-deps`, the dynamo probe,
  the monkeypatch) corrupting the stack. The CLEAN `gpu_worker.py` on stock torch
  2.9.1 loads + generates fine.
- **So the custom image is NOT required for the model to run.** Stock base +
  clean worker is enough for capabilities #1–#4 as far as the MODEL goes.

(One real worker bug remains, unrelated to torch: `generate()` returns a
`DiffusionGemmaGenerationOutput` object, not a tensor — `gpu_worker.py:256/258`
assume `.shape`/indexing. Being grounded by the model-mechanics research agent
before any fix; see [[research/model-mechanics-grounding-2026-06-28]].)

## Why the custom image is still the right investment — Seon co-location

Capabilities #2 (eval-renoise) and #3 (retrieval) are **round-trip** designs:
the worker denoises a canvas → ships it back to the Seon pod (over the internet)
→ Seon parses (`parse-forms`) + evals (SCI cage) + retrieves (Proximum/HNSW) →
ships the re-noise/inject instruction back to the worker. That internet hop, per
denoise segment, is the dominant latency in the feedback loop — and the loop is
the whole point ("responsiveness and control").

**The fix is co-location: run Seon's eval/parse/retrieve ON the worker image,
beside the model on the A100.** Then the feedback loop is a LOCAL call (sub-ms),
not an internet round-trip. The custom image is exactly the vehicle:

```
custom worker image =
    runpod/flash base (torch 2.9.1 — keep stock, it works)
  + transformers 5.11.0 + the model (via NetworkVolume cache)
  + Node + the seon CLJS bundle (parse-forms, the SCI eval cage, the
    retrieval client)            ← THE NEW LAYER, the latency win
  + gpu_worker.py glue that calls the LOCAL seon bundle between denoise steps
```

The diffusion canvas ≈ a Seon block/form; the parser's `:span`/`:error-kind`
(the granularity dial) and the SCI eval are exactly what the in-loop oracle
needs — and they're CLJS/Node, so they bundle into the image as a Node sidecar
the Python worker shells out to (or a local HTTP/UDS call), not a remote pod.

## Open questions (for when we pursue co-location)

- **Bundling shape:** Node sidecar process in the image vs a local HTTP/UDS
  endpoint the Python worker calls. The pod is already loopback-UDS-only
  (`#3`'s reachability note) — co-location makes that a feature, not a blocker.
- **Which Seon pieces:** `parse-forms` + the SCI eval cage are clearly needed
  on-worker. The Proximum/HNSW retrieval index is heavier — does the embedding
  index live on the worker (co-located, fast) or stay a remote knn-search call
  (the one round-trip we might keep, since retrieval fires rarely)?
- **Image size/cold-start:** adding Node + the seon bundle grows the image (the
  current torch image is 15 GB); weigh against the NetworkVolume cache.
- **Build keeps the validated recipe:** the `flash-worker/` Dockerfile +
  `build-image.sh` (validated cu128 stack + smoke gate) stay as the base layer;
  the Seon layer is added on top. Do NOT delete that work.

## Pointers

- [[index]] — push-ready state, deploy mechanics, the env-fix recipe.
- `flash-worker/{Dockerfile,build-image.sh,gpu_worker.py}` — the validated image
  artifacts (snapshot of the gitignored `tmp/flash-diffgemma/`).
- [[research/runpod-flash-grounding-2026-06-28]] — `FLASH_GPU_IMAGE` mechanism +
  the corrected smoke-test gate.
- [[research/eval-renoise-experiment-plan-2026-06-28]] /
  [[research/retrieval-denoising-experiment-plan-2026-06-28]] — the round-trip
  designs co-location would localize.
