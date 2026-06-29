---
type: orchestrator
status: active
tags: [orchestrator, diffusion, agent]
---

# Owner GPU-session runbook — execute top-to-bottom on A100 redeploy

> **we-are-here: A100 undeployed ($0); start at step 0.** This is the single ordered
> checklist the no-GPU loop built toward. Run it top-to-bottom — every step is
> `verify_fresh`-gated and the order is **cheapest decisive probe first**
> (value per GPU-minute). The depth lives in the linked docs; this is the runbook,
> not the explanation. Every command runs from `tmp/flash-diffgemma` with `.env`
> sourced (`set -a; . ./.env; set +a`) and `DIFFGEMMA_EP` exported.

> **Cost reminder:** scale-to-zero (`workers=(0,1)`) = **$0 idle**, ~66 s cold load
> per batch; keep-warm (min worker 1) = **~$1.19/hr** continuous A100 — owner's call
> once iterating. Do NOT set `(1,1)` and walk away.

**Why this order (value per GPU-minute):** step 0 is the non-negotiable gate
(a number on an unverified worker is worthless). Step 1 is a **$0, no-rebuild,
one-drive** lever that may 7× throughput — decisive either way (find_spec gone? tok/s
up? or OOM?), so it runs FIRST. Step 2 is the largest *measured* win (62% of latency
at 9k ctx) but needs an image deploy, so it follows the free probe. Steps 3–4 are the
thesis kill-gates (correctness, not speed) — they decide what gets BUILT, so they come
before the remaining incremental A/B measurements in step 5.

---

## 0. Deploy + verify (NOTHING measured before FRESH ✓)

`flash undeploy --all` OSErrors here — use the per-name force form. Then the
fingerprint gate.

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a
flash undeploy diffgemma --force && flash deploy     # recycle the warm worker
export DIFFGEMMA_EP=<ep-from-deploy-output>
python3 verify_fresh.py                               # asserts worker_sha == local
```

- **Win condition:** `verify_fresh.py` prints **`FRESH ✓`**. If it refuses, the warm
  worker is serving OLD code — bump `FLASH_GPU_IMAGE` to a new tag (structural →
  forces recreation) and re-deploy. Measure nothing until FRESH ✓.
- **Depth:** [[CLAUDE]] "Deployment stability"; [[research/flash-deployment-stability-2026-06-28]].

## 1. batched_mm compiled-path probe — the $0, no-rebuild lever (run FIRST)

The cheap lever the find_spec dig missed: force `experts_implementation="batched_mm"`
on the EXISTING 5.11.0 / torch-2.9 worker. `batched_mm` never calls
`_can_use_grouped_mm` (clears the find_spec graph-break, **no torch bump**) and is
CUDA-graph-clean per the HF compat table (clears the `reduce-overhead` wall, **no
transformers bump**). One-line change, no image rebuild for deps. May unlock the
~1000 tok/s compiled path.

BUILT (2026-06-29): `experts_impl` is now a **payload field**, no source edit, no
image rebuild — `_load(tok, experts_impl=...)` passes the `experts_implementation=`
from_pretrained kwarg; flipping the backend reloads (evict-first, single 50GB copy).
Worker CODE changed → `worker_sha` is `c65c68e5cfae` → **force-recycle** (a plain
`flash deploy` keeps serving old code on a warm worker).

```bash
cd tmp/flash-diffgemma && set -a; . ./.env; set +a
# 1) worker code changed → undeploy --force then deploy (recycles the warm worker):
.venv/bin/flash undeploy diffgemma --force && .venv/bin/flash deploy
export DIFFGEMMA_EP=<ep> && python3 verify_fresh.py        # FRESH ✓ (expects sha c65c68e5cfae) first
# 2a) full A/B probe (eager grouped / compiled grouped / compiled batched + #15 VERDICT):
python3 compile_test.py
# 2b) OR the single decisive drive — force batched on the compiled path:
TORCH_LOGS="graph_breaks,recompiles" python -u client.py \
  '{"mode":"generate","prompt":"Write a Clojure mean over a vector.","compile":true,"max_length":512,"experts_impl":"batched_mm"}'
```

- **Win condition:** clean compile (no find_spec break, no CUDA-graphs error) **AND**
  steady-state `tok_per_s` (the SECOND identical call — discard call-1 warmup) ≥ ~1.8×
  the eager baseline. Fail = `tok_per_s ≤ eager` or **OOM** (batched_mm duplicates
  expert params over the whole-canvas forward S = `canvas_length × top_k`) → compiled
  path is dead for this model shape; KV-cache reuse (step 2) is the only A100 speed lever.
- **Proof fields:** result carries `experts_impl` (read from `model.config`, want
  `batched_mm`) + `compiled` + `gen_error` (find_spec gone => None); the probe's
  `#15 VERDICT` block emits WIN/marginal/NO-WIN/OOM directly.
- **Cost:** one drive (~$0.05 warm), $0 rebuild. Decisive in a single call.
- **Depth:** [[research/torch-compile-speed-worker-2026-06-28]] §15 (+ §8 corrected
  call, §9 find_spec root-cause).

## 2. KV-cache 62% win — deploy co-location image, wire worker-reuse half

The largest measured win: prefill = **62% of latency at 9k ctx**, exact full-prefix
caching is feasible (encoder is causal, zero accuracy loss). The keying half is BUILT
(`seon.agent.ctx/block-chain-keys`, `14e8acb0`) — a pure `(blocks, agent-id) →
per-block chain-hashes`. The worker-reuse half is the §6 drop-in contract, gated on
the co-location image (the `Cache` can't ride a JSON payload).

```bash
# 1) deploy the co-location image (Dockerfile layer + spawn-wiring, co-location-image-build doc)
#    bump FLASH_GPU_IMAGE tag, flash deploy, verify_fresh → FRESH ✓
# 2) wire the worker-reuse half per the §6 contract: worker holds an LRU
#    {chain_hash → (encoder DynamicCache cropped to boundary, token_len)};
#    walk the ::chain-hashes top→down → longest hit = prefix boundary L →
#    crop(L) → generate(past_key_values=cached, ...) prefills only tokens [L:].
```

- **Win condition:** a cache-hit turn re-encodes ONLY the divergent suffix and produces
  output **bit-identical** to a full re-encode (assert `sequences` match for a fixed
  seed) at materially lower latency. Salt scopes by `:seon.agent/id`; a block edit
  auto-misses and re-encodes from the last hit (no stored invalidation).
- **Depth:** [[research/kv-section-caching-design-2026-06-28]] §6 (the contract), §5
  (Phase 0 measure `X` first), §1 (causal-encoder grounding).

## 3. Closed-loop renoise live drive — span-aligned eval-renoise (#13)

The wired driver (`scratchpad/closed_loop.py`, `44c8d635`) is offline-proven: it reads
`canvas_text` (NOT `partial_text`) and parses with `op:"parse-raw"` so the returned
`:span`s index the same basis the worker's `offset_map` was built over (the 11-char
fence shift removed). `verify_fresh`-gated, ready to run on GPU.

```bash
# from tmp/flash-diffgemma, .env sourced + DIFFGEMMA_EP exported, after verify_fresh → FRESH ✓
python3 <session-scratchpad>/closed_loop.py
```

- **Win condition:** `errors_before > 0` at the stop step → span-targeted renoise →
  `errors_after < errors_before` with the good (non-span) tokens **held**
  (`good_held: true`). Renoise must be ORACLE-DRIVEN — only re-noise spans
  `parse-forms` flags, never a correct span.
- **Depth:** [[research/closed-loop-span-alignment-2026-06-28]] (the basis + corrected
  orchestration); [[research/eval-renoise-worker-build-2026-06-28]] (the worker).

## 4. Three-arm kill-gate E1 — does guided-gen beat prompt+fix? (#14)

THE decision: is the clamp/scaffold half of the thesis worth building at all? The
driver (`scratchpad/e1_kill_gate.py`, `801211ee`) runs all three arms — (1) forced-spec
infill + post-hoc oracle, (2) free completion, (3) plain prompt + the IDENTICAL post-hoc
oracle loop — scored on FAITHFULNESS, with the shared oracle/repair loop so the only
variable under test is the clamp.

```bash
# from tmp/flash-diffgemma, .env sourced + DIFFGEMMA_EP exported, after verify_fresh → FRESH ✓
python3 <session-scratchpad>/e1_kill_gate.py celsius 6
```

- **Win condition:** arm 1 BEATS arm 3 on `faithful_rate` by **≥ 0.10** → guided-gen
  EARNS its place; build P2 (`:defn-with-specs` MVP). If arm 1 ≈ arm 3 → the driver
  says **KILL** plainly: cut the clamp/scaffold apparatus, keep only the dynamic-context
  half. Watch the F2 vacuity check (>30% `[:map]`/over-permissive specs = "quality by
  construction" is hollow).
- **Known gap:** the eval tier (`out/worker-oracle-eval/main.js`) currently throws on
  every input; the scorer rests on parse + structural + vacuity until the bundle is
  fixed (`EVAL_ENABLED` flips it back on).
- **Depth:** [[roadmap]] P1 (the three metrics + decision rule); the `e1_kill_gate.py`
  note.

## 5. Skill-lift sweep + required-API render lift — remaining A/B measurements

The structural skill sweep is DONE (6/6 skills, ~0→100%). Two measurements remain:
pivot the skill sweep from structural to the **EVAL-tier** oracle (does the generated
code RUN / return the right answer?), and measure the `seon.*` required-API render's
lift to justify its ~2.9k tok/turn (or trim the cap).

```bash
# each A/B: control = task alone; treatment = artifact + task; N=8/arm; score through
#   the real oracle (parse + structural + EVAL-tier). ~16 gens, ~80s warm, ~$0.03/skill.
# after verify_fresh → FRESH ✓; reuse skill_lift.py / score_ab.py paths.
```

- **Win condition:** each artifact's measured lift is recorded in the north-star ledger.
  Big lift → keep + lock as a regression gate; zero/negative → refine or cut. The
  required-API render must show lift or its 2.9k-tok cap gets trimmed.
- **Depth:** [[north-star]] "Work queue" + the measured-lifts ledger.

---

## Entry points (depth)

- [[north-star]] — the ▸ OWNER HANDOFF (PROVEN / BUILT-deploy-ready / AWAITS-OWNER) +
  the measured-lift ledger this runbook feeds.
- [[roadmap]] — the we-are-here → kill-gate-first sequence (P0–P5).
- [[CLAUDE]] — the index + the copy-pasteable run/deploy loop + deployment stability.
- [[research/torch-compile-speed-worker-2026-06-28]] · [[research/kv-section-caching-design-2026-06-28]] ·
  [[research/closed-loop-span-alignment-2026-06-28]] — the per-step depth.
