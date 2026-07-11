---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Diffusion dynamic-context — auto-loaded index (one-stop shop)

> **The verified canvas** — guided, verified generation on a diffusion canvas: a
> diffusion LLM (DiffusionGemma) refines whole blocks of Clojure fast while Seon's
> oracle (parse/lint/eval/behavioral + retrieval) steers generation BETWEEN denoise
> steps and terminates it on proof. This file is the INDEX + runbook; the
> forward-looking spine carries the depth. Keep it tight + current. (The earlier
> working name "buzzsaw" is retired — same system, this name.)

## The spine (read these first)

- [[architecture]] — the target verified canvas: the thesis, the glossary, the control
  seam, the worker (`refine`/`refine_loop`), the **validation ladder** +
  validation-as-early-stop, the phased grammar gate, the speed levers, the Seon
  interface. Present-tense target, NO hedges.
- [[roadmap]] — the single **we-are-here** (offline surface complete + wired
  on-worker) → the GPU-measurement path (exp D → compile probes → the E1 re-run
  → ladder lift → over-commit×renoise → KV) + the voided-kill-gate history + the
  CUT list (sentinel, op-axis, multi-pass).
- [[grounding]] — every load-bearing claim → its `reference-code/…:LINE` cite (the
  transformers v5.11.0 seams, the parser oracle, the malli→datahike bridge, the
  Flash source).
- [[owner-gpu-runbook]] — the ordered, `verify_fresh`-gated checklist to execute
  top-to-bottom on the next A100 redeploy (cheapest decisive probe first).
- [[colocation-performance-plan]] — **CURRENT FOCUS = SPEED (tok/$).** Co-location prep DONE
  (oracle ~free 0.05ms; loop forward-bound). GPU session = pure measure (owner builds image →
  `tmp/flash-diffgemma/deploy-colocation.sh` → A/B/C/D). **Run exp D (entropy_bound sweep) FIRST —
  free ~2-5× on A100 (est.; the two research docs disagree on the measured tok/forward
  baseline — ~4 vs ~17 — and D is the measurement that settles it).** + [[research/fastest-tok-per-dollar-hardware-2026-06-30]] (FP8=Hopper-only →
  L40S/A6000 DEAD; A100 cheapest BF16; **TPU JAX DiffusionGemma EXISTS — port-light, has `_early_stopping.py`**)
  + [[research/forward-speedup-levers-2026-06-30]] (MoE-bound; over-commit×renoise
  is the lever, Triton kernel = Hopper-only) +
  [[research/compile-control-ceiling-2026-07-02]] (find_spec = 2-line monkeypatch;
  device-assert = static-cache-sizing hypothesis + $0 probe; clamp is
  compile-compatible — the compiled path was never measured).

## ▸ Current state (2026-07-11) — TYPEAHEAD ARC P1–P5 SHIPPED; the offer channel fires

The typeahead lane ([[typeahead-design]] — read its Phases section, every
phase carries its shipped note) is COMPLETE through **P5**: cursor oracle
(`op:"cursor"`), `cursor.py` driver + wire modes `fill`/`rank`/`step`,
seon-side menu/plan-ledger ctx blocks + `:seon.typeahead/policy` row +
the `SEON_AI_PROVIDER=typeahead` step-loop provider, the
**replay-corpus bench inside src-inspect-ai**
(`seon_inspect.typeahead_corpus` + `tasks/typeahead_replay.py`; corpus =
10 real acme sessions), and now **null-render calibration wired end to
end** (`seon.ai.typeahead/null-render` — the prompt minus its transcript
event log + the intent-derived plan sections — rides every step; bench
arm2 mirrors it via `build_null_render`) plus additive glyph-teaching
example lines in the menu header. Headline (local MLX, ≤4k renders,
k=3, SAME corpus, evidence `evals/runs/2026-07-11-typeahead-uptake/`,
ledger rows `2026-07-11:typeahead_replay:dev:k3:arm{2,3}`): **arm2
.567 outcome / .90 validity / verb-acc .429 / uptake .077 / 4.3 s**
(P4: .533/.90/.333/0.0/3.0 s); DeepSeek reference .70 on the same
corpus. All 13 fired selections were CALIBRATED AUTO-OFFERS — zero
organic glyph emissions across P4+P5 (the posterior channel is the
viable selection path in the step regime); 0/13 fires picked a
task-required verb because the captured menus lack the planning verbs
(a menu-SOURCE limitation, not calibration). Costs: calibration ≈
+0.5 s median (worker caches the baseline per null-render); EXPAND
~18 s/step at 3.5k ctx. Margin defaults untuned (fires between 3–6
nats were correctness-mixed). Next lever: menu SOURCES (task-relevant
/ schema-contract offers) + cheaper expansion — not threshold tuning.

## ▸ Prior state (2026-07-05) — LOCAL-FIRST REBOOT: guided loop PROVEN live on MLX; `src-diffusion/` is the home

**The GPU gating is OBSOLETE — the local MLX worker (M5, 8-bit, ~120 tok/s raw)
runs everything free.** The approved plan (verified-canvas v2,
`~/.claude/plans/floating-twirling-lightning.md`) reshaped the design:

- **The guided loop v2** (`src-diffusion/src/seon_diffusion/control.py`):
  round-denoise → bb oracle check → **auto-REPAIR provable near-misses**
  ($0 forwards: fuzzy candidate + eval-sandbox proof; `even`→`even?`) →
  **lock-and-EXECUTE** (stateful `worker-eval --serve` session; defs
  accumulate) → **harvest locked forms OFF the canvas into the encoder KV**
  (they never pay decode again) → scramble remaining bad spans under a
  clamped **`; fix:` hint comment** (content-channel feedback) → T3
  `[{call,expect}]` checks in-loop; failing checks RESTART the attempt with
  the failure as a hint. Termination = proof (validation-as-early-stop).
- **LIVE PROOF #1 (2026-07-05, N=18/arm, 6 tasks × 3 seeds):** guided vs
  free — parse 0.94→**1.00**, eval 0.78→**1.00**, behavioral 0.72→**0.94**;
  zero overhead on already-correct outputs. PoC baseline commit `f22a51f`
  (~/ml/diffusion-gemma), scorecards in `ab_runs/` (raw samples persisted).
- **The package** `src-diffusion/` (pattern: src-inspect-ai): pytest =
  scripted stub model + REAL bb/node oracles (loop decisions pinned
  offline); `bin/seon start dg-worker` (RunPod wire contract on :17860 →
  `SEON_DG_ENDPOINT=http://127.0.0.1:17860`); `python -m
  seon_diffusion.ab_guided` = the lift battery (tok/s on every row).
  `seon.diffusion.loop` retired (policy fixtures ported to pytest); the
  RunPod CUDA worker is FROZEN in `cuda/` (revive by need); the
  `tmp/flash-diffgemma` maintained-code violation is closed.
- **Perf convention (owner): tokens/second, always. Throughput first;
  brute force (more attempts on the cheap model) is a legitimate strategy.**
- **Next (plan phases):** P2 wire `mode:"guided"` + provider payload/response
  + `:tests` grammar + worker-eval `op:"run-tests"`/`op:"repair"` (the
  Python repair shim then DELETES — candidates move oracle-side, informed by
  the shared autofix research in
  `docs/prds/agent-ctx/research/form-autofix-system-2026-07-05.md`); P3 pod
  `diffusion/build!` (replay-commit tee, schemas→tests→functions TDD
  phases); P4 planning-phase + multi-model A/Bs (per-agent provider
  overlays already route strong-planner vs diffusion-implementer).

## ▸ Prior state (2026-07-02) — offline surface COMPLETE + wired on-worker; next was GPU measurement

**A100 UNDEPLOYED ($0).** Every buildable no-GPU half is BUILT, offline-proven with
the REAL bb+node oracles, and wired on-worker (suite 876/4043 green):

- **The validation LADDER, cheapest-decisive-tier-first:** T0 parse (bb ~0.05ms) →
  T1 structural lint (`oracle/malformed-def?` — def-vs-defn is AST-catchable) →
  the PHASED GRAMMAR GATE (`phase-grammars`/`phase-violation?`, `:schemas` →
  `:functions`) → T2 eval (node cljs.js ~2.6ms — the only tier that resolves
  symbols) → T3 behavioral (`[{call,expect}]` — the right ANSWER) + the retrieval
  leg. **Validation-as-early-stop** is `refine_loop`'s termination criterion
  (parse→eval→behavioral, `eval_gate` dflt on; proven by
  `eval_gate_earlystop_proof.py`, 6 cases). **#51:** the T1/phase predicates live
  in the shared dependency-free `seon.diffusion.grammar.cljc` loaded by BOTH the
  pod oracle and babashka — bb `op:"refine"` folds structural+phase renoise
  natively, so the worker reaches the cheap tiers mid-denoise, no pod round-trip.
- **E1 kill-gate RAN (N=6): behavioral 0.0 on ALL arms — VOIDED, a PROVEN
  harness defect** ([[research/e1-behavioral-zero-audit-2026-07-02]]): the run
  scored against a DEAD eval bundle (rebuilt only after the scorecard; threw on
  every input; a dead-tier simulation reproduces the arm means to 3 decimals —
  a known-correct submission would also have scored 0). Harness FIXED
  (`assert_oracle_live` fail-loud gate, `e1_samples.jsonl` persistence,
  contract-stating prompts) → **re-run next GPU session (~$0.50), after exp D.**
  Surviving: guided's STRUCTURAL win (parse/struct 1.0 vs naked); behavioral
  claims from that run carry no evidence either way. Whole-scaffold steering
  SHELVED pending the re-run; PHASED-constraint = the parallel retry. Free-gen
  capstone: correct MATH, hygiene-only errors (def-vs-defn — a cheap-tier catch;
  the `9/5`-ratio claim was FALSIFIED by live test 2026-07-02: node eval gives
  1.8, ok:true).
- **Speed:** the forward is MoE-bound; every fast grouped-expert kernel is
  Hopper-gated → NO kernel lever on the A100. The free lever is **exp D
  (entropy_bound sweep — prepped, UNRUN, ~2-3×)** + the §3 over-commit ×
  free-renoise sweep. Compile ceiling characterized, not closed: find_spec = a
  2-line worker monkeypatch (transformers bump does NOT fix it); the batched_mm
  device-assert has a $0 `max_length=288` probe; the compiled path was never
  measured ([[research/compile-control-ceiling-2026-07-02]]). Hardware: FP8 =
  Hopper-only → L40S/A6000 dead; A100-BF16 = the control card; TPU-JAX =
  port-light high-ceiling bet (JAX impl exists incl. `_early_stopping.py`),
  de-risk = one ~$5 v5e-4 spike.
- **Co-location prep DONE (O1-O6):** persistent bb+node oracles 0.05ms vs ~21-26ms
  spawn; in-worker `mode:"refine_loop"`. KV-reuse + injection-apply worker halves
  built; KV mechanism CPU-de-risked bit-exact. Owner step: build+push the image
  (amd64), `./deploy-colocation.sh`.

**NEXT (owner-gated, ordered — [[owner-gpu-runbook]]):** deploy the co-location
image → `verify_fresh` → **exp D FIRST** → the compile-ceiling payload probes →
the E1 RE-RUN (fixed harness, ~$0.50) → the LADDER-LIFT measurement (`refine_loop` at bb
`op:"refine"`) → the §3 Pareto sweep → KV test C. Deferred behind the GPU proof:
`;; PLAN:` clamp tokens, best-of-N renoise, context-as-target embedding search;
the TPU spike is the separate high-ceiling de-risk. Open flag: #50 (`:minimal`
config-profile no-op — UI/config lane, not diffusion).

## How to run it

Worker lives in gitignored `tmp/flash-diffgemma/` (Python `@Endpoint` + `client.py`
driver; snapshot in `flash-worker/`). Keys in `.env` (`RUNPOD_API_KEY`, `HF_TOKEN`).
`.venv` = python3.12.

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a                     # load keys

# DEPLOY — then ALWAYS verify-fresh (see "Deployment stability" below).
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v1
.venv/bin/flash deploy                        # bundles gpu_worker.py + diffgemma_common.py
python3 verify_fresh.py                        # MUST print "FRESH ✓" before any measuring

# DRIVE a run (modes: probe | introspect | generate | clamp_smoke | infill | denoise_to_step | resume_renoise)
export DIFFGEMMA_EP=kzonsp5b18hpq5            # from deploy output
python -u client.py '{"mode":"probe"}'                                   # cheap: imports+config, no 50GB load
python -u client.py '{"mode":"introspect"}'                              # reflect live model (output fields, sampler, gen-config, CANVAS_LENGTH)
python -u client.py '{"mode":"generate","prompt":"...","max_new_tokens":256,"trace":"canvas"}'

# THE PROVEN PRIMITIVES
python -u client.py '{"mode":"clamp_smoke","trace":"canvas"}'            # clamp holds positions (PROVEN)
python -u client.py '{"mode":"infill","prefix":"(defn mean [xs] (/ ","suffix":" (count xs)))","max_hole_tokens":16}'

# TUNING KNOBS (any generate mode — A/B without redeploying logic):
#   max_denoising_steps (int) — the step CAP (do NOT shrink to "checkpoint"; it compresses the temp ramp)
#   entropy_bound (float, dflt 0.1) — HIGHER => more tokens accepted/forward
#   t_min / t_max, stability_threshold + confidence_threshold (early-stop, pass BOTH)
python -u client.py '{"mode":"generate","prompt":"...","entropy_bound":0.3,"max_denoising_steps":64,"trace":"entropy"}'

# RESULT FIELDS: worker_sha, attn_impl (sdpa|eager), denoise_steps, committed_per_step, tokens_per_forward, gen_s, tok_per_s
# COST / billing: running>0 = executing; workersMin=0 = $0 idle
curl -s https://api.runpod.ai/v2/$DIFFGEMMA_EP/health -H "Authorization: Bearer $RUNPOD_API_KEY"

# REBUILD/PUSH the custom image (stops at push; needs docker login)
REGISTRY=docker.io/seantempesta TAG=cu128-v1 ./build-image.sh
```

- **Scale-to-zero** (`workers=(0,1)`): $0 when idle, ~66 s cold reload. **Keep-warm**
  for fast iteration: min worker = 1 in the `@Endpoint` + redeploy (continuous A100
  ~$1.19/hr — owner's call once iterating). `.flashignore` is DEAD in Flash v1.17 —
  use `.gitignore`.

## Use DiffusionGemma as an AGENT's LLM provider (`:diffusiongemma`)

DiffusionGemma is a first-class, config-selectable seon LLM provider alongside
deepseek/anthropic — `seon.ai.diffusiongemma` (the `:control` backend: RunPod
async `/run` + status poll, the per-step LogitsProcessor seam). It conforms to
the same `llm-fn` contract as the other adapters: `(fn [ctx-string])` →
`Promise<{:text … :seon.ai/raw …}>`, errors-as-values via `:seon.ai/error`
(never a throw into the agent loop). `seon.client/current-llm-fn` dispatches to
it; an undeployed/unreachable endpoint surfaces a graceful `:seon.ai/error`
value and falls back to the stub when unconfigured.

Select it (env seeds the DB-owned `:seon.ai/config` row once; a runtime transact
against the row also switches it):

```bash
# in .env (default cluster) or .env.acme (acme harness):
SEON_AI_PROVIDER=diffusiongemma
DIFFGEMMA_EP=u50y7khhos5t7o     # or SEON_DG_ENDPOINT — same value, either var
RUNPOD_API_KEY=<key>            # or point SEON_DG_API_KEY_ENV at another var
# optional: SEON_DG_BACKEND=control (default) | vllm ; SEON_AI_MAX_TOKENS=N
```

`SEON_AI_MAX_TOKENS` (the `:seon.ai/config` row's `::max-tokens`) is honored as
the worker's `max_new_tokens`. **To go live: deploy the worker, set `DIFFGEMMA_EP`
+ `RUNPOD_API_KEY`, set `SEON_AI_PROVIDER=diffusiongemma`** — then a configured
agent gets real completions, drop-in (proven wired + graceful-down; real
completions await an owner-deployed endpoint). The `:vllm` backend reuses
`seon.ai.openai-compat` (set `SEON_AI_BASE_URL` + key instead).

## Deployment stability — KNOW what's live (do NOT skip)

A plain `flash deploy` does NOT recycle a WARM worker — it keeps serving OLD code
until it scales to zero (`idle_timeout`) or a structural field changes. Grounded in
the Flash source ([[grounding]] "Flash", [[research/flash-deployment-stability-2026-06-28]]):

- **`worker_sha`** — every response carries `sha256(gpu_worker.py +
  diffgemma_common.py)[:12]`, computed INSIDE the container. It proves which code
  produced a result.
- **`verify_fresh.py`** (gitignored) — asserts `worker_sha == local`; prints
  `FRESH ✓` or refuses. Run it after ANY deploy before trusting a single number.
- **Force-fresh that PRESERVES the endpoint id:** bump `FLASH_GPU_IMAGE` to a new
  tag (`imageName` is structural → server-side worker recreation). `flash undeploy
  --all && flash deploy` also works but CHANGES `DIFFGEMMA_EP`.

## Settled — do NOT re-litigate

See [[roadmap]] "Settled" for the full list. The load-bearing ones: torch 2.9.1
stock WORKS (custom image kept only for Seon co-location); A100-80 BF16 (FP8 1000
tok/s is Hopper-only); two endpoints behind one provider (vLLM speed / transformers
control); commit is emergent random-init NOT a mask; `max_denoising_steps` is a CAP
(stop externally); stay on transformers 5.11.0.

## Research index (the dated depth)

The spine links the depth inline; this table is the full map — one line per file.

| Research file | What it covers |
|---|---|
| `unified-control-oracle` | **THE built mechanism** — `seon.diffusion.oracle/refine`: the legs (parse/structural/phase/retrieve/eval) folding into the `{clamps, renoise-spans, injections}` partition, offline-proven, wired on-worker; awaits GPU MEASUREMENT |
| `compile-control-ceiling` | the find_spec + batched_mm walls root-caused — the inert `assume_constant_result` patch (2-line monkeypatch fix), the static-cache-sizing assert hypothesis ($0 probe), clamp = compile-compatible, the mis-attributed "4× compile tax" corrected |
| `e1-behavioral-zero-audit` | why E1 scored 0.0 everywhere — the dead eval bundle proof (arm means reproduced to 3 decimals), the unstated-contract secondary defect, the harness fixes (liveness gate, sample persistence); the run is VOID, re-run queued |
| `fastest-tok-per-dollar-hardware` | the hardware ranking — FP8/fast-MoE = Hopper-only (L40S/A6000 dead), A100-BF16 = the control card, TPU-JAX port-light (the ~$5 v5e spike) |
| `forward-speedup-levers` | forward is MoE-bound (~85-92%); no kernel lever on SM80; the §3 over-commit × free-renoise joint sweep design |
| `mode-driven-guided-generation` | **THE design** — the mode abstraction, the four modes, the convergent-pass frame, E0–E6 |
| `mode-design-critique` | the adversarial review the roadmap's sequencing is built on (missing arm-3, vacuity, canvas gating, cut-list) |
| `transformers-diffusion-source-grounding` | the real v5.11.0 mechanism — per-step seam `:1034`, stopping ABC `:466`/`:1207`, temp ramp `:311`, streamer verdict |
| `parser-as-generation-oracle` | the measured three-tier oracle (92.7% parse / 62.5% free / 91.5% w-ref / 93.5% combined) + the strong-model nulls |
| `seon-diffusion-interface-design` | the `:diffusiongemma` provider (two backends) + the gym predicate machinery |
| `serving-optimization-survey` | vLLM runs the decode but seals the sampler → the two-endpoint split; the 137 vs 1000 tok/s explanation |
| `flash-deployment-stability` | why a warm worker keeps old code + the stable deploy procedure (Flash source) |
| `flash-warm-reuse` | FlashBoot reality (platform-side, decays with idle) → keep-warm is the dependable lever |
| `eval-renoise-worker-build` | the built `denoise_to_step`/`resume_renoise` worker + the two GPU-only unknowns |
| `gym-third-party-adoption` | making the gym consumer-drivable (`SEON_CONFIG` + `SEON_EXTRA_SRC`, no `src/seon` edits) |
| `thesis-capstone` | the session synthesis + the first-light GO/NO-GO against the T0–T5 ladder |
| `first-light-runbook` | the ordered deploy → capabilities execute sequence |
| `custom-image-and-seon-colocation` | the torch finding (stock works) + the co-location latency play |
| `runpod-flash-grounding` | RunPod/Flash SDK grounding + the env-fix recipe (`dependencies` is build-time pip) |
| `model-mechanics-grounding` | the pivotal mask→random-init correction — **absorbed into** `transformers-diffusion-source-grounding` (kept for the history) |
| `infill` / `eval-renoise` / `retrieval-denoising` / `live-feedback`-experiment-plan | capabilities #1–#4 — capability INTENT valid; the **mask-based mechanism is SUPERSEDED** by `transformers-diffusion-source-grounding` + `eval-renoise-worker-build` |

Also top-level: [[infra-flash-runpod]] (the operational deploy/debug log).
`archive/index.md` = the original "push the image" handoff (superseded by the spine).

## How to work here

- **Docs + experiments only on this track** — `src/seon` integration (the
  `:diffusiongemma` provider, gym predicates) lands in [[roadmap]] P3, after the
  kill-gate. Don't wire the pod before the thesis clears P1.
- **The GPU is the owner's single worker** — agents design + ground + write worker
  modes (py_compile-clean, off-GPU unit-checked); the owner deploys + drives.
- **Every experiment is a gym scenario + a predicate + a scorecard** (`scenario ×
  git-sha`) — a knob sweep is a MOVED number, not an anecdote.
- **Read the source before you build** — [[grounding]] maps every claim to a
  `reference-code/…:LINE`; guessing diffusion semantics produces confident, wrong code.
