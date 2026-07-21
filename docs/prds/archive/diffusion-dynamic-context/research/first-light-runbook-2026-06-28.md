---
type: reference
status: active
tags: [reference, agent, web]
---

# First-light runbook — DiffusionGemma on Flash

> The single ORDERED sequence to execute from the moment the worker image is
> pushed. Not a re-explanation of the thesis — for depth follow the links. This
> doc is the checklist; the prep docs are the reference.
>
> - [[index]] — push-ready state, deploy mechanics, the env-fix recipe.
> - [[research/infill-experiment-plan-2026-06-28]] — capability #1 (J), unknowns U1–U4.
> - [[research/eval-renoise-experiment-plan-2026-06-28]] — capability #2 (L), unknowns V1–V3.
> - [[research/retrieval-denoising-experiment-plan-2026-06-28]] — capability #3, unknowns W1–W3.

All commands run from `tmp/flash-diffgemma/` (gitignored). The venv is `.venv/`;
keys (`RUNPOD_API_KEY`, `HF_TOKEN`) are in `.env` — `source .env` first.

**First light deploys ONE endpoint: `diffgemma`** (proven generate + probe + the
unified introspect, in `gpu_worker.py`, helper `diffgemma_common.py`). The three
capability `@Endpoint`s — `diffgemma-infill` (J), `diffgemma-renoise` (L),
`diffgemma-retrieval` (#3) — are **staged out of the bundle** (`staged/`,
gitignored) so they do NOT register stray A100 endpoints; deploy each later (§3)
once introspect confirms its API. All four share one NetworkVolume `diffgemma-vol`
(EU-RO-1).

## STOP — read the cross-doc conflicts first (§4)

Four consistency findings reconcile the three prep docs. **Three are now RESOLVED
in code** (C1 — one unified `introspect` on one endpoint, one cold load; C3 — the
ONE canonical offset_map in `diffgemma_common`; C4 — defensive mask-id
resolution). C2 (encoder vs prefix-LM) stays a watch on W1. Read §4 before step 1;
a conflict caught on paper is free, the same conflict caught on a live A100 is a
wasted 50 GB cold start.

## 1. Deploy

Image already built + pushed by the orchestrator (`build-image.sh`,
`REGISTRY=docker.io/seantempesta TAG=cu128-v1`). Resume from the export:

```bash
cd tmp/flash-diffgemma
source .env                                        # RUNPOD_API_KEY, HF_TOKEN
export FLASH_GPU_IMAGE=docker.io/seantempesta:cu128-v1
.venv/bin/flash deploy
```

- `FLASH_GPU_IMAGE` **replaces the base image** for the code `@Endpoint`s
  (bypasses version validation) — this is the key that ships the pristine
  torch 2.9.0 + transformers 5.11.0 cu128 stack instead of the broken base
  torch 2.9.1.
- `FLASH_GPU_IMAGE` is a **structural** field, so deploy **recycles stale
  workers** with the **endpoint id preserved** (no `undeploy`, no id churn — code
  changes alone only bump a rolling fingerprint and keep the old warm handler).
- The **NetworkVolume auto-deploys** idempotently (by name + DC) in **EU-RO-1**;
  the endpoint is pinned to EU-RO-1 to match (a volume is single-DC).
- `flash deploy` prints the single `https://api.runpod.ai/v2/<id>/runsync` URL for
  `diffgemma` — capture the `<id>`. `client.py` reads the target from
  `DIFFGEMMA_EP`.

### Bundle mechanism (Flash v1.17) — why only `diffgemma` deploys

`.flashignore` is **dead** in v1.17 (deploy warns and ignores it). Flash decides
the bundle by **recursively** scanning the project dir, applying built-in patterns
**+ the project `.gitignore`** (`runpod_flash/cli/utils/ignore.py`
`load_ignore_patterns` + `cli/commands/build.py:262-263`), copying every surviving
file into the tarball, and **importing every surviving `.py`** — at which point any
module-level `@Endpoint(...)` **registers**. So exclusion = match a `.gitignore`
pattern. The worker dir's `.gitignore` (read by Flash even though the whole dir is
repo-gitignored — Flash reads the file off disk, it does not call git) excludes:

- `client.py` — local driver, run separately;
- `staged/` — the three capability stubs (each declares its own `@Endpoint`);
- `Dockerfile`, `build-image.sh`, `*.log`, `*.out`, `logs/`, `tmp/` — build-time / scratch.

Result (verified by `flash build`, no deploy): `built flash-diffgemma  2 files` —
tarball = `gpu_worker.py` + `diffgemma_common.py` only; manifest `resources` =
`['diffgemma']`. To re-verify before deploy: `.venv/bin/flash build` then
`python -c "import json;print(list(json.load(open('.flash/flash_manifest.json'))['resources']))"`.

Rebuild path (if the image needs a fix): `TAG=cu128-v2 ./build-image.sh` →
re-`export FLASH_GPU_IMAGE` → `flash deploy` (structural bump = free clean
recycle).

## 2. First warm window — batch ALL of these before idle scale-down

**The cold start is the expensive part.** Each endpoint's first call loads ~50 GB
from the NetworkVolume into its A100 (seconds-to-load-from-volume, not
minutes-to-download — but still a real per-endpoint load). `idle_timeout=600`
keeps a worker warm 10 min; do every probe below inside that window or pay the
load again.

**C1 is now RESOLVED in code (see §4).** The three per-endpoint introspects are
folded into ONE unified `introspect` mode on the proven `diffgemma` endpoint
(`gpu_worker.py`), so a single deploy gives probe + generate + the FULL introspect
(U1–U4 + V1–V3 + W1–W3) in one ~50 GB cold load. The U/V/W probes are **pure
reflection (`inspect`/decode, no `generate`)**, so they all run in the same warm
window against the one cached model. The per-endpoint introspects in the three
capability stubs still exist as fallbacks, but first light uses the unified one.

Run in this order against the proven `diffgemma` endpoint (`export
DIFFGEMMA_EP=<diffgemma id>`):

1. **`probe`** — validate load + versions (no generate):

   ```bash
   .venv/bin/python client.py '{"mode":"probe"}'
   ```

   Expect `class_ok=true`, `config_ok=true`, `model_type="diffusion_gemma"`,
   torch `2.9.0`, transformers `5.11.0`, `cuda=true`, A100 `vram_gb≈80`. A
   `config_err` here = HF gating / token problem; stop and fix before generate.

2. **First `generate`** — the Clojure `mean` prompt → coherence + tok/s (the
   milestone not yet hit):

   ```bash
   .venv/bin/python client.py        # default payload = the mean prompt, 256 tok
   ```

   Success = a coherent `(defn mean …)` in the ```clojure block, a sane
   `tok_per_s`, no `gen_error`. This is the first real DiffusionGemma output.

3. **`introspect`** — the first-deploy oracle, now ONE consolidated call on the
   same warm `diffgemma` worker (C1):

   ```bash
   .venv/bin/python client.py '{"mode":"introspect"}'
   ```

   It returns one structured dict: `mask_resolution` (C4 — the resolved mask id +
   its source, or a loud `mask_resolution_fatal` if undeterminable), then nested
   `U` (U1–U4 infill seams), `V` (V1–V3 re-noise, incl. the LIVE offset-map
   fidelity demo), and `W` (W1–W3 encoder/cross-attn inject). What each probe
   reads off the live model:

   - **U1 — canvas seeding** (J): `model.generate` param list — look for a
     canvas / `decoder_input_ids` / `infill_mask` / `prefix_ids`+`suffix_ids`
     kwarg; the tokenizer special-token map for FIM sentinels
     (`<|fim_*|>` / a `<mask>` the chat template understands). If no public seam
     → the canvas is seeded inside the `accept_canvas` override on `cur_step==0`.
   - **U2 — sampler attach** (J): which `EntropyBoundSampler` instance
     `generate()` actually uses, and how it is reachable (model attr? built
     per-call from `generation_config`? a `get_sampler()` hook?). We monkeypatch
     the instance, not a stray class.
   - **U3 — `accept_canvas` contract** (J): dump the source +
     `inspect.signature`. Does it RETURN the accepted canvas or MUTATE
     `current_canvas` in place? Which arg is running-canvas vs post-denoise
     proposal? (Decides clamp = `canvas[clamp]=ids` pre-return vs `torch.where`
     over the return.)
   - **U4 — mask id + layout** (J): `tokenizer.mask_token_id` (**or a reserved
     id** — see §4-C4, this is load-bearing for all three) and the 256-canvas /
     block layout, so `prefix+holes+suffix ≤ 256` fits one block.
   - **V1 — re-seed a partially-good canvas** (L): does the U1 seam accept a full
     256-id canvas with embedded MASK ids + a fresh step budget (mostly committed,
     a few positions re-masked), or must re-seed run inside `accept_canvas` on
     `cur_step==0`?
   - **V2 — offset-map fidelity** (L): decode a known canvas piece-wise vs joint
     `tokenizer.decode`; report the per-position char-range table + any mismatch
     (SentencePiece/BPE leading-space `▁` / byte-fallback). Pick the faithful
     method (likely joint decode for `text` + fast-tokenizer
     `return_offsets_mapping` re-encode for ranges). **This ships LIVE** — it
     runs against the real tokenizer the moment it loads, no generate.
   - **V3 — in-place re-denoise convergence** (L): the self-test (seed a
     fully-committed canvas, re-mask one interior position, re-denoise, assert all
     other positions byte-identical + the masked one re-committed). This is a
     `generate` — gated on U1/U2/U3/V1; run it AFTER the seam is wired (step 3a),
     not in the reflection pass.
   - **W1 — encoder KV extensibility** (#3): is there a real `encoder` attr
     (`model.encoder` / `model.model.encoder`) with a forward accepting
     `past_key_values` + `use_cache`/`cache_position`? **See §4-C2** — if
     `has_encoder_attr` is False the model is decoder-only/prefix-LM and W1/W2
     reframe from cross-attention to prefix-cache extension.
   - **W2 — cross-attn mask widening** (#3): when the encoder/prefix cache grows
     `Lp → Lp+Ls`, does the decoder mask auto-cover the new positions or must it
     be rebuilt? A stale mask = the injected spec is **invisible (silent no-op,
     the worst failure)**. Dump the mask shape before/after a synthetic extend.
   - **W3 — mid-denoise inject point** (#3): can the cache be extended BETWEEN
     decode steps (inline, per-step) or only at a `generate()` boundary
     (round-trip)? Checks whether `accept_canvas`/the decode loop can reach +
     mutate the encoder `past_key_values`.

   Per-endpoint fallback commands (three cold loads — only if you deliberately
   keep the stubs separate; the unified `introspect` above supersedes these):

   ```bash
   export DIFFGEMMA_EP=<infill id>;    .venv/bin/python gpu_worker_infill.py introspect     # U1–U4
   export DIFFGEMMA_EP=<renoise id>;   .venv/bin/python gpu_worker_renoise.py introspect    # V1–V3 (+ offset-map demo)
   export DIFFGEMMA_EP=<retrieval id>; .venv/bin/python gpu_worker_retrieval.py introspect  # W1–W3 (+ offset-map demo)
   ```

   Record the JSON. U1/U2/U3/U4 + V1/V2 + W1/W2/W3 must all resolve before any
   capability issues a real `generate`/`renoise`/`inject` — the stubs report
   `STUB` and withhold the guessed call by design.

## 3. Per-capability execution (dependency order: #1 → #2 → #3)

Each capability: wire the confirmed seam (gated on what introspect revealed) → run
the first test case → check the success criterion. **#2 reuses #1's U-answers; #3
reuses #1's U + #2's V (offset_map, clamp, stateless round-trip) verbatim** — do
not re-derive.

**Deploying a capability endpoint (moving it out of `staged/`).** Each stub lives
in `staged/` (gitignored, so first light never registers it). To bring one live
after introspect confirms its API — e.g. infill:

```bash
mv staged/gpu_worker_infill.py .      # back to the project root → now in the bundle
.venv/bin/flash build                 # confirm: resources now == ['diffgemma','diffgemma-infill']
.venv/bin/flash deploy                # adds the new endpoint; diffgemma id is preserved
```

The stub imports `diffgemma_common` from the root (always bundled), so no path
edits are needed. Move only the capability you're ready to run; leave the others in
`staged/` so you never pay for an A100 endpoint you aren't using yet.

### #1 infill (J) — cheapest "clearly better than AR", no Seon integration

- **Wire:** at the CONFIRM-ON-DEPLOY spots in `gpu_worker_infill.py`, attach the
  `accept_canvas` override to the real sampler instance (U2) using the
  return-vs-mutate contract (U3); seed the canvas `[pre_ids … MASK*H … suf_ids]`
  via the U1 seam; set `mask_id` from U4. The override clamps the typed positions
  to their ids every step + records hole commits/entropy.
- **First test:** case 1 `mean-reduce` — prefix `(defn mean [xs] (/ `, suffix
  `(count xs)))`, hole ≤16.

  ```bash
  export DIFFGEMMA_EP=<infill id>
  .venv/bin/python gpu_worker_infill.py infill
  ```

- **Success:** the hole denoises to `(reduce + xs)`; concatenated with the suffix
  it (a) parses (`seon.repl.internal/parse-forms`, no model call) and (b) evals to
  `5/2` in the SCI cage. Then run cases 2–4 + the suffix-blind AR baseline
  (`gemma-4-26B-A4B-it`, suffix WITHHELD) and tabulate the win rate.
- **Gate (T2):** if diffusion shows no edge over suffix-blind AR, the structural
  premise is weak — stop and reassess.

### #2 eval-renoise (L) — the most Seon-native; round-trip, Seon owns the oracle

- **Wire:** at the CONFIRM-ON-DEPLOY spots in `gpu_worker_renoise.py`, wire
  `generate_canvas` (all-MASK seed via U1) and `renoise` (re-seed the
  partially-good canvas via V1; re-mask `renoise_positions`, clamp the rest via
  U3's contract). The `build_offset_map` + `span_to_positions` linchpin already
  ships LIVE (proven at introspect against the real tokenizer) — use the
  V2-faithful decode method.
- **First test:** the runtime (`:seon/error`) case — leg 1 denoises to
  `(defn mean [xs] (/ (reduce + xs) (count ys)))`.

  ```bash
  export DIFFGEMMA_EP=<renoise id>
  .venv/bin/python gpu_worker_renoise.py generate_canvas
  ```

  Then in the **pod** (not the GPU): eval `text` in the SCI cage → `:seon/error`
  "Unable to resolve symbol: ys" → locate `ys`'s char span → `span_to_positions`
  via the returned `offset_map` → call `renoise {canvas_tokens,
  renoise_positions}`.
- **Success:** the divisor span re-commits to `xs`
  (`(defn mean [xs] (/ (reduce + xs) (count xs)))`), ALL other positions
  byte-identical (the clamp held), re-eval clean (`(mean [1 2 3 4]) ;=> 5/2`).
  Record rounds-to-clean. Then run the secondary `:invalid-token` case (`3xs`,
  exercises `parse-forms` `:span` directly) + the AR forward-regeneration
  contrast.
- **Gate:** if re-noise does not converge faster/cleaner than AR
  forward-regeneration on single-region errors, the in-place-revision premise is
  weak for code repair — reassess.

### #3 retrieval (RAG in the loop) — gated by `SEON_EMBED`; closes the AUROC-0.471 gap

- **Prove Part 1 first — NO GPU needed (do it before/independent of the warm
  window).** With `SEON_EMBED` set on the pod, call `embed/search-pull` (or the
  proposed `spec-for-span`) with `:where [[?e :seon.fn/source]]` on a canned
  `span_context` → confirm it returns real `:seon.fn/sym` specs, distance-
  ascending. The retrieval seam (`seon.embed/search-pull`, wire `knn-search`,
  Proximum HNSW fn index, Vertex embed) is **live, shipping infra** — verifiable
  today.
- **Wire Part 2 (GPU inject):** at the CONFIRM-ON-DEPLOY spots in
  `gpu_worker_retrieval.py`, wire `generate_canvas` (entropy read +
  committed-symbol extraction — both ship LIVE) and `inject` (extend the
  encoder/prefix KV with `spec_text` via the W1 route — **Route INCREMENTAL** if
  W1 exposes it, else **Route RE-PREFILL**, the always-correct fallback; re-mask +
  clamp = L's mechanism verbatim).
- **First test:** the Trigger-B case — leg 1 denoises to
  `(defn sum [xs] (reduce-kv + 0 xs))` (a real fn, committed confidently → low
  entropy → entropy is blind).

  ```bash
  export DIFFGEMMA_EP=<retrieval id>
  .venv/bin/python gpu_worker_retrieval.py generate_canvas
  ```

  Then pod-side: eval → `reduce-kv` flagged by **Trigger B** (graph membership /
  eval `:seon/error`, regardless of entropy) → `spec-for-span` returns
  `clojure.core/reduce`'s spec → map span → `inject {canvas_tokens,
  renoise_positions, spec_text}`.
- **Success:** the span re-commits to `reduce`
  (`(defn sum [xs] (reduce + 0 xs))`), other positions byte-identical, re-eval
  clean (`(sum [1 2 3 4]) ;=> 10`). The decisive contrast: re-noise WITHOUT
  injection (expect: re-commits `reduce-kv` / another confident-wrong name) vs
  WITH the injected spec (expect: `reduce`) — the delta IS the capability's value.
  Then run the Trigger-A (entropy-gated) secondary case.
- **Gate:** if injection does not change the re-committed name vs re-noise without
  injection on the wrong-name case, cross-attention conditioning is too weak to
  steer commits — reassess. If it does, capability #3 closes the AUROC-0.471 gap
  empirically.

## 4. Cross-doc consistency — conflicts found on paper (the highest-value output)

The three prep docs **agree** on the load-bearing architecture, which de-risks
first light:

- **`accept_canvas` seam — identical in all three.**
  `accept_canvas(current_canvas, denoiser_canvas, logits, cur_step)`, logits
  `[1, 256, 262144]`, transformers 5.11.0, class
  `DiffusionGemmaForBlockDiffusion`, model_type `diffusion_gemma`, no
  `trust_remote_code`. One override surface, three uses (clamp / re-mask /
  entropy-read). No conflict.
- **Canvas seeding — one mechanism, deferred to U1.** J seeds prefix/suffix at the
  edges; L's V1 re-seeds a mostly-committed canvas; #3 reuses L's V1. All three
  route through the same U1 seam (a `generate()` canvas kwarg, else the
  `cur_step==0` override). No conflict — V1 is a re-check of U1 for the re-seed
  case, not a competing assumption.
- **Stateless worker + Seon-drives-the-loop — fully consistent.** #3's loopback
  constraint (the pod is `127.0.0.1:7890`, unreachable from a RunPod worker → Seon
  must drive, worker stays a stateless denoiser) lands on **exactly** L's
  round-trip shape ("mirrors capability #2"). Both hold `canvas_tokens` +
  `offset_map` pod-side between calls; no tensors cross the wire; #3 explicitly
  rules out the inline per-step variant for the same loopback reason. The
  strongest agreement in the set. (#1 is one-shot, no round-trip — also
  consistent, just simpler.)

C1, C3, and C4 are now RESOLVED in code (the unified `gpu_worker.py` worker +
`diffgemma_common.py`); C2 remains a watch:

- **C1 (RESOLVED in code). The three introspects are folded into ONE unified
  `introspect` mode on the proven `diffgemma` endpoint (`gpu_worker.py`).** The
  "do-everything-in-one-warm-window" economy only holds *within* one endpoint (the
  shared NetworkVolume makes the model *files* cheap to mount, but each endpoint
  still loads 50 GB into its own A100 on first call). Since U1–U4 + V1–V3 + W1–W3
  are **pure `inspect`/decode reflection (no generate)**, they now all run against
  the one cached model the generate path already loaded — probe + generate + the
  full introspect in ONE ~50 GB cold load. The probe + generate path in
  `gpu_worker.py` is unchanged byte-for-byte (verified by diff); introspect is an
  added branch that reuses `_CACHE`. The per-endpoint introspects remain in the
  capability stubs as fallbacks.

- **C4 (RESOLVED in code). Mask-id resolution is now defensive
  (`diffgemma_common.resolve_mask_id`).** U4 warns the mask may be a **reserved id,
  not `mask_token_id`**. The old `mask_id = getattr(tkz, "mask_token_id", None)` in
  all three stubs would, on `None`, make `build_offset_map` treat *no* position as
  a hole (every token gets a char range) and the clamp/re-mask write `None` into
  the canvas — silent total breakage. `resolve_mask_id` checks, in order:
  `tokenizer.mask_token_id` → `tokenizer.mask_token` → `special_tokens_map` → a
  `<mask>`-like added token → `model.config.{mask_token_id,mask_index,mask_id}`,
  and RETURNS the source. The unified introspect reports `mask_resolution` and
  raises a loud `mask_resolution_fatal` (and SKIPS the offset-map demo) if the id
  cannot be determined, rather than proceeding with `None`. All four workers now
  call it; the unsafe `getattr` defaults are gone.

- **C2 (paper-vs-code framing — watch W1). #3's injection assumes a *separate
  encoder* with a cross-attended KV cache.** The index/J/L describe "an AR encoder
  → KV cache; a decoder cross-attends it." But the Transformer Lab paper
  (2606.14620) describes a **block-diffusion model with the prompt as prefix
  context** — which is just as likely **decoder-only / prefix-LM** (prompt in the
  same sequence, prefix self-attention) as a true encoder/decoder with
  cross-attention. #3's W1 probe already hedges (`has_encoder_attr`): if it comes
  back **False**, W1/W2 reframe from "extend the encoder cache + widen the
  cross-attn mask" to "extend the prefix KV cache" — and **Route RE-PREFILL works
  regardless**, so injection is still provable. Flagged so a False `has_encoder_attr`
  reads as "reframe," not "blocked."

- **C3 (RESOLVED in code). The duplicated offset_map is now ONE canonical
  function (`diffgemma_common.build_offset_map`).** It was written twice: L's
  `build_offset_map` (`gpu_worker_renoise.py`,
  `tkz.decode([tid], skip_special_tokens=False)`) and #3's `_offset_map`
  (`gpu_worker_retrieval.py`, `tkz.decode([tid])` — relied on the HF default).
  Same math, two copies, drifting on the BPE-boundary / byte-fallback artifact; J
  builds none (it works in canvas positions directly). L's version is canonical
  (it makes `skip_special_tokens=False` explicit and owns the V2 fidelity
  question) and now lives in `diffgemma_common` with `span_to_positions`; the
  unified worker, the renoise stub, and the retrieval stub all import it, and the
  `_offset_map` duplicate is deleted. When V2 settles the faithful decode method,
  there is now ONE place to change it.

## Decision-gate ladder

The T2/repair/injection gates above feed the T-ladder go/no-go in
[[../agent-fsm/research/diffusion-llm-test-plan-2026-06-27]]. Stop and reassess at
the first gate a capability fails — a failed structural premise is a result, not a
setback.
