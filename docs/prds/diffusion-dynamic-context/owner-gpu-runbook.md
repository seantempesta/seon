---
type: orchestrator
status: active
tags: [orchestrator, diffusion, agent]
---

# Owner GPU-session runbook — execute top-to-bottom on A100 redeploy

> **we-are-here: A100 undeployed ($0); the offline surface is complete — this
> session is PURE MEASUREMENT.** Run it top-to-bottom — every step is
> `verify_fresh`-gated and the order is **cheapest decisive probe first**
> (value per GPU-minute). The depth lives in the linked docs; this is the runbook,
> not the explanation. Every command runs from `tmp/flash-diffgemma` with `.env`
> sourced (`set -a; . ./.env; set +a`) and `DIFFGEMMA_EP` exported.

> **Cost reminder:** scale-to-zero (`workers=(0,1)`) = **$0 idle**, ~66 s cold load
> per batch; keep-warm (min worker 1) = **~$1.19/hr** continuous A100 — owner's call
> once iterating. Do NOT set `(1,1)` and walk away.

**Why this order (value per GPU-minute):** step 0 is the non-negotiable gate (a
number on an unverified worker is worthless) and deploys the CO-LOCATION image,
which every later step rides. Step 1 (exp D) is the free ~2-3× sampler knob —
payload-only, decisive either way. Step 2 is the $0-rebuild compile-ceiling probe
chain. Step 3 re-runs E1 on the fixed harness (the original run is voided — dead
eval bundle). Step 4 is THE thesis measurement — the validation ladder's lift.
Steps 5-6 are the speed frontier and the KV win.

**Executed in prior sessions (do not re-run as-is):** the batched_mm compiled-path
probe (RAN — clears find_spec, then a CUDA device-side assert; step 2 carries the
root-cause probes); the E1 three-arm kill-gate (RAN — behavioral 0.0 on ALL arms,
**VOIDED: proven dead-eval-bundle defect**,
[[research/e1-behavioral-zero-audit-2026-07-02]]; step 3 is the re-run on the
fixed harness); the closed-loop renoise + injection live drives (RAN in the
battery — renoise reduces errors, injections hold); the canvas-length probe
(fits, token-aligned).

---

## 0. Deploy the CO-LOCATION image + verify (NOTHING measured before FRESH ✓)

The image bundles the persistent bb parse server + the node eval bundle beside
the model, so `refine_loop` runs the ladder in-container (0.05 ms warm, no
internet hop). Owner-only step first: build+push the amd64 image (`docker login`,
then `REGISTRY=docker.io/seantempesta TAG=cu128-v2-oracle ./build-image.sh`).

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a
./deploy-colocation.sh                      # parks drivers, deploys, prints A/B/C/D
# (manual fallback: export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v2-oracle
#  && .venv/bin/flash deploy)
export DIFFGEMMA_EP=<ep-from-deploy-output>
python3 verify_fresh.py                     # asserts worker_sha == local
```

- **Win condition:** `verify_fresh.py` prints **`FRESH ✓`**. If it refuses, the
  warm worker is serving OLD code — bump `FLASH_GPU_IMAGE` to a new tag
  (structural → forces recreation) and re-deploy. Measure nothing until FRESH ✓.
- **Depth:** [[CLAUDE]] "Deployment stability"; [[research/flash-deployment-stability-2026-06-28]];
  [[colocation-performance-plan]] §4 (the A-D plan this session executes).

## 1. exp D — the entropy_bound / tokens-per-forward sweep (run FIRST)

The free raw-speed lever: the forward costs ~130-140 ms regardless of how many
positions commit (~17 at the 0.1 default); a higher bound commits more per
forward → fewer forwards → higher tok/s, until the quality knee. Payload-only,
prepped in `battery.py` (`D` alias), scored through the local oracle.

```bash
python3 battery.py D --param entropy_bound=0.05,0.1,0.2,0.3,0.5
```

- **Win condition:** the `entropy_bound × tokens_per_forward × tok_per_s ×
  faithful_rate` curve + its knee — a MOVED number per setting, `worker_sha` on
  every scorecard line. Expected ~2-3× before the knee.
- **Depth:** [[colocation-performance-plan]] §4-D;
  [[research/forward-speedup-levers-2026-06-30]] §3a-b.

## 2. Compile-ceiling payload probes ($0 rebuild, one chain)

The batched_mm device-side assert and the find_spec break are both root-cause
hypothesized with cheap discriminating probes — the compiled path has never
actually been measured ([[research/compile-control-ceiling-2026-07-02]]).

```bash
# a) static-cache under-sizing hypothesis: single-canvas budget → no assert?
python3 battery.py 1 --param max_length=288
# b) the 2-line find_spec monkeypatch (worker-side, no image rebuild) + compile on:
TORCH_LOGS="graph_breaks,recompiles" python -u client.py \
  '{"mode":"generate","prompt":"Write a Clojure mean over a vector.","compile":true,"max_length":288}'
```

- **Win condition (a):** `max_length=288` (one canvas) runs clean where 512
  asserted → the assert is cache sizing, not the MoE. **(b):** graph-break log
  clean of find_spec → steady-state compiled `tok_per_s` vs eager is the FIRST
  real compiled-path number.
- **Depth:** [[research/compile-control-ceiling-2026-07-02]] §2-3.

## 3. Re-run E1 on the FIXED harness (~$0.50 — the first meaningful behavioral numbers)

The original run is VOIDED: it scored against a DEAD eval bundle (rebuilt only
after the scorecard; threw on every input — a dead-tier simulation reproduces
the recorded arm means to the third decimal; a known-correct submission would
also have scored 0). The harness is fixed: the `assert_oracle_live`
golden-sample fail-loud gate (a known-correct body MUST score 1.0 before any
arm runs), `e1_samples.jsonl` raw-generation persistence, and prompts that
STATE the map-in/map-out contract (the old prompts never did — naked arms
couldn't pass by construction).

```bash
# after verify_fresh → FRESH ✓; the liveness gate aborts the run if any tier is dead
python3 <session-scratchpad>/e1_kill_gate.py celsius 6
```

- **Win condition:** the liveness gate passes, raw samples persist, and the arms
  produce non-degenerate behavioral rates — THIS run, not the voided one,
  decides the whole-scaffold question (arm 1 vs arm 3, Δ ≥ 0.10 = EARNS).
- **Depth:** [[research/e1-behavioral-zero-audit-2026-07-02]] (the void proof +
  fixes); [[roadmap]] P1 (status + decision rule).

## 4. THE LADDER-LIFT MEASUREMENT — refine_loop on bb op:"refine"

The thesis number: does the validation ladder, steering + terminating the loop
mid-denoise, LIFT behavioral correctness (and at what step cost)? The worker's
`refine_loop` gate is parse→eval→behavioral (`eval_gate` dflt on) and its renoise
source is bb `op:"refine"` — parse + structural (`malformed-def?`) + phase
(`phase-violation?`) in one ~0.05 ms call, the shared `grammar.cljc` predicates.

```bash
# ladder ON (bb op:"refine", eval gate, behavioral tests) vs ladder OFF (free gen):
python -u client.py '{"mode":"refine_loop","prompt":"<task>","max_iters":6,
  "eval_gate":true,"behavioral":[{"call":"(c2f 100)","expect":"212"},{"call":"(c2f 0)","expect":"32"}]}'
python -u client.py '{"mode":"generate","prompt":"<same task>"}'   # the OFF arm
# then the phased variant: payload "phase":"schemas" → "functions"
```

- **Win condition:** ladder-ON behavioral pass-rate > ladder-OFF at comparable
  wall-clock (report per-iteration `errors_before/after`, `oracle_ms`,
  `tok_per_s`, the stop tier). Early-stop must fire on oracle-proof, not step
  exhaustion. This is the "same oracle gates, steers, and terminates" claim,
  measured.
- **Depth:** [[architecture]] "The validation ladder"; [[colocation-performance-plan]]
  §4-A/B (oracle_ms + the before/after network contrast).

## 5. The §3 over-commit × free-renoise Pareto sweep

The verified canvas's unique speed×quality move: commit aggressively (high
`entropy_bound` from step 1's knee), let the ~free oracle renoise exactly the
wrong spans. Three arms — A baseline (eb 0.1, no renoise), B over-commit naked,
C over-commit + oracle renoise.

- **Win condition:** arm C Pareto-dominates arm A on (behavioral pass-rate,
  wall-clock) — the free oracle buys back the quality the over-commit spent, net
  faster. Scorecard per `(scenario × git-sha × config)`.
- **Depth:** [[research/forward-speedup-levers-2026-06-30]] §3c-d.

## 6. KV-cache test C — bit-exactness + the prefill drop

The 62%-of-latency win. Both halves are built (`block-chain-keys` Seon-side;
`_kv_reuse_generate` + `KVPrefixCache` worker-side); the mechanism is CPU-proven
bit-exact; only the DiffusionGemma HYBRID cache case is GPU-only.

```bash
# request 1 (COLD — fills the LRU) then request 2 (WARM — same static head, new tail):
python -u client.py '{"mode":"generate","kv_reuse":true,"blocks":["<static head>","<tail A>"],
  "chain_hashes":["<h0>","<h1>"],"max_new_tokens":64}'
python -u client.py '{"mode":"generate","kv_reuse":true,"blocks":["<static head>","<tail B>"],
  "chain_hashes":["<h0>","<h2>"],"max_new_tokens":64}'
```

- **Win condition:** (1) **bit-exact** — request-2's `sequences` == the same
  prompt with `kv_reuse:false` at fixed seed (divergence → the sliding layers:
  store full-attention layers only + recompute sliding); (2) **prefill drop** —
  request-2 `gen_s` materially lower, `kv_reuse_frac` high. Report `kv_hit_block`,
  `kv_reused_tokens`, `kv_suffix_tokens`.
- **Depth:** [[research/kv-section-caching-design-2026-06-28]] §6/§6a;
  [[colocation-performance-plan]] §4-C.

---

## Entry points (depth)

- [[roadmap]] — the single we-are-here + the GPU-measurement path this runbook
  executes + the executed kill-gate history.
- [[north-star]] — the vision (oracle gates context before, steers + terminates
  during) + the measured-lift ledger the results feed.
- [[colocation-performance-plan]] — the A-D test plan, the persistent-oracle
  architecture, the offline-prep table (O1-O6, all done).
- [[CLAUDE]] — the index + the copy-pasteable run/deploy loop + deployment stability.
- [[research/compile-control-ceiling-2026-07-02]] ·
  [[research/forward-speedup-levers-2026-06-30]] ·
  [[research/fastest-tok-per-dollar-hardware-2026-06-30]] ·
  [[research/kv-section-caching-design-2026-06-28]] — the per-step depth.
