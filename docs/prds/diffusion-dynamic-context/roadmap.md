---
type: prd
status: active
tags: [prd, agent, flow]
---

# Diffusion roadmap — we are here → the target

The single **we-are-here** doc. [[architecture]] describes the verified canvas in present
tense (the target as it IS when built); [[grounding]] cites every load-bearing
claim in real source. THIS doc holds what is PROVEN, the gap, and the
kill-gate-first, dependency-ordered path to close it.

The discipline (owner-settled, [[research/mode-design-critique-2026-06-28]]):
**gate the riskiest assumption BEFORE building the engine — and don't build
un-measured oracle features.** The kill-gate RAN and its result is **VOIDED — a
proven harness defect** (the run scored against a dead eval bundle; audit:
[[research/e1-behavioral-zero-audit-2026-07-02]]); the mode engine, op-axis,
convergence loop, and `mode/enter` sentinel stay CUT while whole-scaffold
steering is shelved awaiting the re-run. The bar for any steering machinery is a
MEASURED lift on behavioral correctness under the now-FIXED harness — starting
with the E1 re-run and the phased grammar gate after the ladder's own lift is
proven on GPU.

## ▸ WE ARE HERE (2026-07-05 — local-first reboot)

**The verified canvas v2 is LIVE on the local MLX model and lifting every
gate.** The approved plan (verified-canvas v2) superseded the GPU-gated
path below: the loop now locks-and-EXECUTES forms as they parse (stateful
eval session), harvests them into the encoder KV, auto-repairs provable
near-misses for $0 forwards, hints via clamped `; fix:` comments, and
terminates on T3 proof. **LIVE PROOF #1 (N=18/arm): parse 0.94→1.00, eval
0.78→1.00, behavioral 0.72→0.94 vs free.** Maintained home =
`src-diffusion/` (`seon.diffusion.loop` retired → pytest fixtures; CUDA
worker frozen). Remaining phases: P2 wire (guided mode + provider +
`:tests` grammar + worker-eval `run-tests`/`repair` ops), P3 pod
`diffusion/build!` + TDD phases, P4 planning/multi-model + throughput
sweeps (perf ALWAYS in tok/s). The section below is the pre-reboot state,
kept for history.

## ▸ WE WERE HERE (2026-07-02, pre-reboot — GPU-gated)

**The offline control surface is COMPLETE and wired on-worker; what remains is GPU
MEASUREMENT.** The validation ladder — T0 parse → T1 structural lint → phased
grammar gate → T2 eval → T3 behavioral, plus the retrieval leg — is built and
proven with the REAL bb+node oracles; **validation-as-early-stop** is the
`refine_loop` termination criterion (stop when the code is PROVEN correct, not
when the model feels confident); and the shared `seon.diffusion.grammar.cljc`
predicates run natively in bb `op:"refine"`, so the worker reaches the cheap tiers
mid-denoise with no pod round-trip (suite 876/4043 green). The three-arm
kill-gate RAN on GPU and its behavioral result is **VOIDED — a proven harness
defect**: the run scored against a DEAD eval bundle (rebuilt only AFTER the
scorecard; it threw on every input — simulating a dead eval tier reproduces the
recorded arm means to the third decimal, and a known-correct submission would
also have scored 0). The audit is
[[research/e1-behavioral-zero-audit-2026-07-02]]; the harness is FIXED
(oracle-liveness gate, sample persistence, contract-stating prompts). What
survives the void: guided generation's STRUCTURAL win (parse 1.0 / structural
1.0 vs naked — live-oracle-scored); the behavioral tier carries no evidence
either way until the re-run. Whole-scaffold clamp steering is SHELVED awaiting
that re-run; the PHASED-constraint direction is the parallel retry. The A100 is
undeployed ($0).

**The next move is the GPU-measurement session** ([[owner-gpu-runbook]], ordered):
deploy the co-location image → `verify_fresh` → **exp D (entropy_bound sweep — the
free ~2-3×) FIRST** → **re-run E1 on the fixed harness** (~$0.50 — the first
meaningful behavioral numbers) → point `refine_loop` at bb `op:"refine"` and
**measure the ladder's LIFT on behavioral correctness** → the over-commit ×
free-renoise Pareto sweep → KV bit-exactness. The phased-constraint
sub-directions (`;; PLAN:` clamp tokens, best-of-N renoise, context-as-target
embedding search) and the ~$5 TPU v5e spike wait behind the GPU proof.

## PROVEN (fingerprint-certified, 2026-06-28)

Each is live-verified on the A100 worker (endpoint `kzonsp5b18hpq5`) with
`worker_sha == local`, OR measured in the live pod.

- **Model runs.** DiffusionGemma loads (~66 s) and generates on stock torch
  2.9.1; the `def`-vs-`defn` thesis demo passed (parser passes, eval catches).
  The ~12-cycle "broken torch" saga was a hallucinated smoke-test symbol —
  [[research/custom-image-and-seon-colocation-2026-06-28]].
- **Clamp holds.** `clamp_smoke` — chosen canvas positions survive denoising while
  the rest denoise. The `LogitsProcessor` clamp is real.
- **Infill works.** Clamp prefix AND suffix → both ends held, the middle denoises
  co-conditioning on the suffix (the move AR cannot make).
- **The spec-slot infills.** Forcing a `:malli/schema` via an infill hole produces
  a fillable spec at the PRIMITIVE level (content can still be wrong — that is
  precisely the parse→eval→span-renoise loop's job).
- **The dynamic-context half is validated.** The data-modeling skill A/B took
  generation from **0→100%** correct map-in/map-out (62%-hallucinating without the
  context, 100%-correct with it). This is the SAFE half of the bet — reactive
  section-fns help a generator regardless of the clamp question — and it is
  already paying off.
- **Deployment-stability fingerprint discipline.** `worker_sha` + `verify_fresh.py`
  — a measurement on an unverified worker is worthless; the procedure is grounded
  in the Flash source ([[research/flash-deployment-stability-2026-06-28]]).
- **The eval-renoise worker is BUILT and GPU-verified** (`denoise_to_step` /
  `resume_renoise` + `StepCountStopping` + `good_clamp_for_renoise`) — the live
  round-trip held the clamp and re-noised the span
  ([[research/eval-renoise-worker-build-2026-06-28]]).
- **The oracle is measured.** Parser 92.7% detect / 100% safe-recover; eval 62.5%
  free / 91.5% with a comparator; combined 93.5% ([[grounding]] "oracle").

## PROVEN — the live GPU battery + the kill-gate run (2026-06-29; E1 since voided)

The first full battery ran on a verified worker (~$2-3, 122 jobs) — **6/7 wins**:

- **The verified-canvas mechanics hold live.** Baseline ~360 tok/s; the canvas-length probe
  (the scaffold fits one 256-token canvas, boundaries token-aligned); clamp + infill
  re-held; the closed loop's renoise REDUCES errors; injection turns a hallucinated
  symbol into the real API; **`unified_refine` converges end-to-end** — the full
  verified-canvas loop runs. The model reproducibly makes the def-vs-defn error (the thesis case).
- **The batched_mm compiled-path probe RAN and FAILED:** it clears find_spec but hits
  a CUDA device-side assert on the whole-canvas compiled forward → the compiled
  ~1000 tok/s path stays blocked. Settled: no kernel lever on the A100 (below).
- **THE E1 KILL-GATE MEASUREMENT (N=6, all three arms): behavioral_rate = 0.0
  everywhere — Δ +0.000 → VOIDED, a PROVEN harness defect
  ([[research/e1-behavioral-zero-audit-2026-07-02]]).** Primary cause, explaining
  all 18 zeros: the run scored against a **DEAD eval bundle** — the eval-fix
  source landed before the run but `out/worker-oracle-eval/main.js` was rebuilt
  only AFTER the scorecard; that bundle threw "single colon" on every input.
  Simulating a dead eval tier reproduces the run's recorded arm means to the
  third decimal (0.85/0.65/0.625), and a known-correct submission would ALSO
  have scored 0 — the run measured the bundle, not the model. Secondary: the
  behavioral harness demanded a namespaced map-in/map-out convention the prompts
  never stated, so naked arms couldn't pass by construction. Genuine model
  failures exist (the transducer body, live-pod-demonstrated) but the run's raw
  generations weren't persisted, so they're unquantifiable from it. **Harness
  FIXED** (sample persistence, an `assert_oracle_live` golden-sample fail-loud
  gate, contract-stating prompts). What survives: guided's STRUCTURAL win
  (structural 1.0, parse 1.0 vs naked 0/0.83 — parse/structural-tier, live
  oracle); the behavioral-tier claims carry no evidence either way.
  **Whole-scaffold clamping: SHELVED awaiting the ~$0.50 re-run** (next GPU
  session, after exp D); the **phased-constraint direction** is the parallel
  retry. (Owner: exploratory failures are signals — try again.)
- **The free-gen capstone sharpens the division of labor:** free generation
  produces the CORRECT MATH with hygiene-only errors — def-vs-defn (real: T1
  lint catches it structurally, the eval tier throws "Too many arguments to
  def") — exactly the errors the cheap ladder tiers catch. (The companion claim
  that the `9/5` ratio literal is CLJS-invalid was FALSIFIED by live test
  2026-07-02: the real node eval tier evaluates `9/5` → 1.8, ok:true.) Free-gen
  + oracle + targeted fix is the proven-cheap strategy; whether steered
  generation beats it remains open pending the harness fix.

## PROVEN — the validation ladder, offline with the REAL oracles (2026-06-30)

- **The full ladder is BUILT + proven:** T0 parse (bb, ~0.05 ms) → T1 structural
  lint (`seon.diffusion.oracle/malformed-def?` — def-vs-defn is AST-catchable,
  eval is the wrong motivator for it) → the **phased grammar gate**
  (`phase-grammars` / `phase-violation?`, `::phase` `:schemas` → `:functions`) →
  T2 eval (node cljs.js, ~2.6 ms) → T3 behavioral (`[{call,expect}]` — the literal
  "who cares about probability" gate). Cheapest decisive tier first.
- **Validation-as-early-stop is the loop's termination criterion:**
  `refine_loop`'s gate is parse → eval → behavioral (`eval_gate` default on);
  `eval_gate_earlystop_proof.py` (6 cases, real bb+node oracles) shows the loop
  refusing to stop on code that parses but fails eval, refusing to stop on code
  that runs but returns the wrong answer, and stopping at iter 0 on proven-correct
  code.
- **The cheap tiers run ON-WORKER with no drift (#51):** the T1/phase predicates
  live in the shared dependency-free `seon.diffusion.grammar.cljc`, loaded by BOTH
  the pod oracle and babashka; bb `op:"refine"` folds structural + phase renoise
  natively — live-proven on bb.
- **Co-location prep is DONE (O1-O6):** persistent bb+node oracle servers over a
  pipe = 0.05 ms warm vs ~21-26 ms spawn (~489×); the in-worker `mode:"refine_loop"`
  runs the loop server-side. The KV-reuse + injection-apply worker halves are built;
  the KV crop+reuse mechanism is CPU-de-risked bit-exact
  ([[colocation-performance-plan]]).

## The gap to the target

- **The ladder's LIFT is unmeasured on GPU.** Every tier is built and
  offline-proven, but no live run yet shows the ladder-steered `refine_loop`
  beating the free loop on behavioral correctness. This is THE measurement — it is
  what the north-star's "same oracle steers and terminates generation" claim rests
  on.
- **The over-commit × free-renoise curve is unmeasured.** exp D (`entropy_bound`
  sweep) is prepped in `battery.py` and UNRUN; the three-arm §3 sweep (baseline vs
  over-commit vs over-commit+renoise) that tests whether the free oracle
  Pareto-dominates the baseline has not run
  ([[research/forward-speedup-levers-2026-06-30]] §3).
- **KV bit-exactness on the real hybrid cache is GPU-only.** The mechanism is
  CPU-proven bit-exact; the DiffusionGemma hybrid-cache case needs the A100
  (test C).
- **The phased-constraint direction is unproven as steering.** The phase gate is
  built as a renoise source; whether phased grammar enforcement lifts behavioral
  correctness is an open experiment — deferred until the ladder's lift is proven
  and the E1 re-run (fixed harness) reads out.
- **TPU tok/s is an estimate.** The JAX DiffusionGemma exists (port-light,
  `_early_stopping.py` is validation-as-early-stop natively) but no real number —
  the ~$5 v5e-4 spike is the de-risk
  ([[research/fastest-tok-per-dollar-hardware-2026-06-30]]).

Superseded gaps (closed or transformed): canvas sizing (probe ran — fits,
token-aligned); the arm-3 baseline (E1 ran — VOIDED by a dead eval bundle, see
P1; the open gap is now the ~$0.50 re-run on the fixed harness); Seon-side
wiring (the `:diffusiongemma` provider is wired core+acme, graceful-down proven).

## The GPU-measurement path (NEXT — the ordered live sequence)

All build/debug is done; the A100 session is pure measurement, `verify_fresh`-gated
throughout ([[owner-gpu-runbook]] carries the commands, [[colocation-performance-plan]]
the design):

1. **Deploy the co-location image** (persistent bb+node oracles in-container) →
   `verify_fresh` → FRESH ✓ before any number.
2. **exp D — the `entropy_bound` / tokens-per-forward sweep (FIRST).** Free ~2-3×
   on the same A100; prepped in `battery.py` (`D` alias); find the knee.
3. **The compile-ceiling payload probes** ($0-rebuild, one chain, after D):
   `battery.py 1 --param max_length=288` tests the top device-assert hypothesis
   (static-cache under-sizing → unchecked `index_copy_` OOB on multi-canvas
   runs), and the 2-line worker-side find_spec monkeypatch is deployable without
   an image rebuild — the compiled path was never actually measured
   ([[research/compile-control-ceiling-2026-07-02]]).
4. **Re-run E1 on the FIXED harness (~$0.50).** The original run is voided (dead
   eval bundle — [[research/e1-behavioral-zero-audit-2026-07-02]]); the harness
   now has the `assert_oracle_live` fail-loud gate, `e1_samples.jsonl` raw-sample
   persistence, and contract-stating prompts. This re-run produces the FIRST
   meaningful behavioral numbers and decides the whole-scaffold question.
5. **The ladder's LIFT.** Point `refine_loop` at bb `op:"refine"` (parse +
   structural + phase renoise, was parse-only) and measure behavioral correctness
   ladder-on vs ladder-off — the thesis measurement.
6. **The §3 over-commit × free-renoise Pareto sweep.** Three arms — baseline (eb
   0.1), over-commit naked, over-commit + oracle renoise; win = arm C
   Pareto-dominates baseline on (pass-rate, wall-clock)
   ([[research/forward-speedup-levers-2026-06-30]] §3d).
7. **KV test C.** Bit-exactness on the real hybrid cache + the prefill drop.

Behind the GPU proof: the phased-constraint sub-directions (`;; PLAN:` clamp
tokens, best-of-N renoise, context-as-target embedding search) and, separately,
the **TPU v5e-4 spike** (~$5, one real tok/s number for the high-ceiling path).

## The build path (executed — kill-gate first, dependency-ordered)

### P0 — Canvas-length probe — **DONE (fits)**

Ran live: `canvas_length` = 256 and the `:defn-with-specs` scaffold FITS one
canvas; the offline BPE check found + fixed 4 straddled boundaries (the `]]`
closers) — all spans land on token edges. (Critique F3, closed.)

### P0 — Speed bench — **PARTIAL (exp D still unrun)**

`attn_impl=sdpa` confirmed live; baseline ~360 tok/s generate / ~130-140 tok/s
decode at ~17 tokens/forward. The `entropy_bound` sweep (exp D) is prepped and
UNRUN — it is step 2 of the measurement path. The FP8 1000 tok/s headline is
Hopper-only by construction — measure the achievable A100 number, don't chase the
headline ([[research/serving-optimization-survey-2026-06-28]],
[[research/fastest-tok-per-dollar-hardware-2026-06-30]]).

### P1 — THE KILL-GATE: three-arm forced-spec infill (E1) — **RAN; VOIDED (proven dead-eval-bundle defect) — harness fixed, RE-RUN next GPU session**

The single experiment that decides whether modes are worth building. Same handful
of fns whose bodies are given; clamp the `defn` + `:malli/schema` frame, leave
`:in-spec`/`:out-spec`/`:body` as holes. THREE arms:

1. **forced-spec infill** + the post-hoc parse/eval/renoise loop,
2. **free completion** (naked),
3. **plain prompt** "write the `:malli/schema`" **+ the IDENTICAL post-hoc
   parse/eval/renoise loop** ← the baseline the original plan omitted.

- **Metric A (validity):** % of attempts that yield an instrumentable spec (parses
  + registers, no `:seon.fn/schema-error`) within `renoise.max-retries`.
- **Metric B (the real discrimination):** does arm 1 BEAT arm 3? If arm 1 ≈ arm 3,
  the entire clamp/scaffold apparatus is dead weight — the post-hoc oracle loop
  (which we build anyway) carries it. KILL modes, keep only the dynamic-context
  half.
- **Metric C (faithfulness — the vacuity score):** of the specs each arm produces,
  what % are `[:map]` / over-permissive (a static check) and what % REJECT an
  obviously-wrong input for the given body (an adversarial property / LLM-judge)?
  If >~30% vacuous, "quality by construction" is hollow and Stage 3 must carry the
  quality. (Critique F1 + F2.)

Every run lands in the gym scorecard (`scenario × git-sha`). Read the numbers
honestly — they, not the engine, decide whether anything below gets built.

**Status — RAN on GPU (N=6, verified worker); behavioral result VOIDED by a
proven harness defect ([[research/e1-behavioral-zero-audit-2026-07-02]]).** The
scorer was first upgraded from instrumentable to BEHAVIORAL
(call-the-fn-check-the-output — the instrumentable proxy had passed a
semantically wrong transducer body in a live-pod check). Recorded result:
behavioral_rate = 0.0 for ALL three arms, every sample — but the audit proved
the run scored against a **DEAD eval bundle** (`out/worker-oracle-eval/main.js`
rebuilt only AFTER the scorecard; it threw "single colon" on every input;
simulating a dead eval tier reproduces the recorded arm means to the third
decimal, and a known-correct submission would ALSO have scored 0). Secondary
defect: the behavioral harness demanded a namespaced map-in/map-out convention
the prompts never stated — naked arms couldn't pass by construction. What
survives: guided generation uniquely enforces STRUCTURE (structural 1.0 / parse
1.0 after the refine-loop rewire held the clamp through `denoise_to_step` and
renoise stopped the ramble; naked = 0 / 0.83 — live-oracle-scored tiers). The
behavioral tier carries NO evidence either way from this run (raw generations
weren't persisted). **Harness FIXED**: `e1_samples.jsonl` persistence, the
`assert_oracle_live` golden-sample fail-loud gate, contract-stating prompts.
Decisions standing: **whole-scaffold clamp steering SHELVED awaiting the ~$0.50
re-run** (measurement-path step 4); the phased-constraint direction is the
parallel retry.

### P1 — Eval-renoise live test — **DONE (live-demonstrated)**

Both honest unknowns resolved on the live worker: `StepCountStopping` fires
precisely at K (the schedule intact); the closed loop ran end-to-end (GPU denoise
+ local oracle) — short-circuit on a clean parse saved ~67% of steps, and the
renoise mechanism held the good positions. The load-bearing lesson it surfaced:
**renoise must be ORACLE-DRIVEN** — a blind renoise of an already-correct span
REGRESSED it (`defn`→`def`); only re-noise spans the oracle flags. That lesson is
now structural: `refine_loop`'s renoise sources are exactly the ladder's flagged
spans. ([[research/eval-renoise-worker-build-2026-06-28]],
[[research/eval-renoise-experiment-plan-2026-06-28]].)

### P2 — The `:defn-with-specs` MVP mode — **SHELVED pending the E1 re-run; the phased gate is the live steering direction**

E1's behavioral verdict on this mode is VOID (dead eval bundle — see P1); the
mode is shelved until the ~$0.50 re-run on the fixed harness produces the first
meaningful behavioral numbers. The live steering direction meanwhile: the
**phased grammar gate** — per-phase grammar
enforcement (`:schemas` allows ns+`register!`, rejects def/defn; `:functions`
allows ns+defn, rejects `register!`/bare-def) as a `refine` renoise source, built
in `seon.diffusion.grammar.cljc` + wired into bb `op:"refine"`. Whether phased
enforcement lifts behavioral correctness is the post-ladder-proof experiment. The
scaffold code (`build-scaffold`) remains as a tested primitive; do not build on it
before that experiment reads out. Keep the dynamic-context section-fns regardless
— they are the safe half (0→100% proven) and they help free-gen too.

**Status — the Seon-side scaffold is BUILT + OFFLINE-PROVEN, end-to-end
AWAITS-GPU (2026-06-29).** `seon.diffusion.scaffold/build-scaffold`
(`src/seon/diffusion/scaffold.cljs`) is the template generator: given
`{::fn-name ::ns ::intent}` it emits the `:defn-with-specs` clamp frame —
`::frame-text` (the two `schema/register!` `:map`s + the `defn` with its
`[:=> [:cat ::name-request] ::name-response]` wiring, valid Clojure with
placeholder slots), `::infill-spans` (the four slots the worker generates: the
request `:map` body, the response `:map` body, the arglist destructure, the fn
body), and `::clamp-spans` (the fixed structure the worker HOLDS: the
`defn`/`schema/register!` forms, the `:malli/schema` `:=>` wiring, the
`::request`/`::response` refs). The span sets TILE the frame exactly (no gap, no
overlap) by construction. Span vocabulary + `to-wire`'s `{op,span,role}`
flattening REUSE `seon.diffusion.retrieval` so the worker consumes scaffold
spans the same way it consumes retrieval injections. Offline-proven (NO GPU) in
`test/seon/diffusion/scaffold_test.cljs` — 27 assertions, 0 fail: the frame
parses via `seon.repl.internal/parse-forms` (3 clean top-level forms), every
infill span lands on its slot, the clamp text holds every structural token, and
the spans partition `[0, len)`. The worker clamps `::clamp-spans` and infills
`::infill-spans` (spec slots first so the body generates against a known
contract) → a complete map-in/map-out fn. The GPU round-trip + the E1 A/B both
RAN — see the P1/P2 status above; the scaffold stays a tested primitive, not a
build target.

**BPE token-boundary alignment — MEASURED, $0, CPU tokenizer-only (2026-06-29).**
The clamp/infill/inject control primitives are CHAR-spans but the worker maps each
to canvas TOKEN positions by OVERLAP (`diffgemma_common.py span_to_positions`): a
boundary that falls mid-token puts that one token in BOTH the clamp and the infill
set, so the two ops can't be cleanly separated. An offline checker
(`tmp/flash-diffgemma/span_token_align_check.py`, gitignored — loads only the
tokenizer, ~MBs, no model, no GPU: `AutoTokenizer.from_pretrained(
"google/diffusiongemma-26B-A4B-it")` gives a `GemmaTokenizer`, vocab 262144)
tokenizes a real `build-scaffold` frame with `return_offsets_mapping=True,
add_special_tokens=False` (mirroring the worker's `skip_special_tokens=False`
canvas basis) and asserts every span boundary lands on a token edge. **Measured: 4
of the scaffold's boundaries STRADDLED** — at each `:map` body slot's end the slot's
trailing `]` abutted the clamp's `])`, and BPE merges `]])` into ONE token, so the
slot end and the `map-close` start shared a token. **Fix (applied to
`scaffold.cljs`): the `map-close` clamp text now opens with a newline (`"\n  ])\n\n"`)
so the `])` lands on its own line** — a token boundary falls between the slot and
the structural close; re-run gives all 11 scaffold boundaries plus the
retrieval-injection symbol span (`transct!`) aligned cleanly, across multiple
fn-name/intent variants. The scaffold offline test stays green (27 assertions).
**Verdict: the span-based control primitives survive real BPE tokenization** — the
only token-merge hazard was the huddled `]]` closers, now nudged apart. (A live-pod
re-eval would need a pod restart; the running shared pod is detached from shadow
hot-reload and still holds pre-nudge `segments` in memory — the disk source, the
fresh-compile suite, and the offline checker are the proof.)

### P3 — The Seon interface — **WIRED**

The `:diffusiongemma` provider is a first-class, config-selectable seon LLM
provider (two backends behind `SEON_DG_BACKEND`: `vllm` reuses `:openai-compat`;
`control` is `seon.ai.diffusiongemma` over the RunPod async-job API), conforming
to the standard `llm-fn` contract with errors-as-values and graceful-down —
proven wired in both the core and the acme harness. The consumer-drivable gym
entry point exists (`bin/acme gym-diffusion <scenario>`, `SEON_CONFIG` +
`SEON_EXTRA_SRC`, zero `src/seon` edits).
([[research/seon-diffusion-interface-design-2026-06-28]],
[[research/gym-third-party-adoption-2026-06-28]], [[CLAUDE]] "provider".)

### P4 — Generalize: the engine, the stages, the convergence loop (E2–E6) — **STAYS CUT**

The kill-gate's behavioral result was voided (dead eval bundle), so the mode
engine stays cut with the question OPEN; if the E1 re-run or the
phased-constraint experiment earns a green, this is the generalization path it
re-opens. The full design exists
([[research/mode-driven-guided-generation-2026-06-28]]); build it in this order:

- **E2 — `:design-schema` adapt loop:** does showing `mg/sample` example data
  tighten the next schema? (Its own kill-gate — the premise is unproven.)
- **E3 — per-step gate latency:** the `:eval`/`:instrument` rungs run real
  compilation; if decode+parse+eval per step dominates the ~12 ms forward, defer
  eval to the END of each K-step call. The custom stopping criterion forfeits
  `torch.compile` (mutually exclusive) — measure the throughput cost of being
  allowed to gate. (Critique F1's mechanism.)
- **E4 — span re-noise hits the right region:** the parser `:span` overlaps the
  known-bad region AND `span_to_positions` selects the right canvas positions.
- **E5 — the four-mode staged build, single pass:** the first capstone demo on a
  real "build a small domain" scenario.
- **E6 — multi-pass convergence:** the iterative-refinement proof (CREATE/UPSERT/
  RETRACT, the monotonicity guard, the `namespace × git-sha × pass-n` scorecard).
  KILL gate: does a schema upsert that breaks a downstream fn RE-SURFACE it as an
  open item without being called? If instrumentation only throws on call, the
  reactive-convergence premise needs explicit dirty-marking. (Critique F5.)

### P5 — `:edit-namespace` + embedding-driven dependency discovery (design, unbuilt)

A capability EXTENSION, not a gate — fold in once the engine + convergence loop
(P4) exist, since it composes with the refinement-pass model. Design in
[[architecture]] "discover, require, understand".

- **`:mode/edit-namespace`** — the `ns` form + its `:require`s become a mutation
  target, not just the fns/schemas inside the ns. Clamp the `ns` skeleton, infill
  the require vector + aliases; same lowering as every mode, op-axis applies (add =
  `:upsert`, drop dead = `:retract`); it writes `:seon.ns/requires`.
- **Embedding-driven discovery section-fn** — parse the namespace's
  words/identifiers → embedding search over the **Vertex + Proximum/HNSW** index
  (gated by `SEON_EMBED`) for relevant existing namespaces + reusable fns within
  them; surface them so the agent DISCOVERS deps to require. Retrieval-as-control
  applied to dependency discovery.
- **Composition (in flight, agent a88b157):** pairs with the required-API render
  (signatures+docstrings of the namespaces an agent already `:require`s).
  Discovery finds NEW deps; the required-API render explains the ONES IT HAS —
  discover → require → understand.
- Grounding: the embeddings infra (Vertex/Proximum over `:seon.fn/source`) + the
  program graph (`:seon.ns/requires`, `:seon.fn`). See [[grounding]].

## CUT (E1's run voided — the bar is a measured behavioral lift on the fixed harness)

These are elegant superstructure on a primitive whose kill-gate has yet to
produce a valid behavioral number (the first run scored a dead eval bundle).
The mode engine stays cut; generation-steering returns via the E1 re-run or the
phased grammar gate, and only on a moved behavioral number (critique §3):

- **The `mode/enter` sentinel + model-initiated invocation.** Circular,
  unbootstrapped, dispensable — the `missing-spec` detector forces a mode with zero
  model cooperation. System-forced only for the MVP.
- **The op-axis `:create/:upsert/:retract` and the whole multi-pass convergence
  loop** (the monotonicity guard, the scorecard trend line). None of it validates
  before one forced infill works.
- **`:generative-test` and `:repl-explore` as modes.** Stage 4 is the normal agent
  loop with an empty scaffold — not a mode. Stage 3 matters as a quality CHECK on
  Stage 2's output (the vacuity pressure), not as a fifth piece of machinery to
  stand up first.
- **The "one engine vs per-stage" debate.** Moot with one mode — you cannot prove
  one engine subsumes four before you have one.

**Keep regardless of P1:** the dynamic-context section-fns (`namespace-state`,
`missing-spec-target`, `related-fns-and-schemas`). Pure reactive queries, already
validated (the 0→100% A/B), and they help a prompted model too. They are arm 3's
context — the safe half of the bet.

## Settled — do NOT re-litigate

- **torch 2.9.1 stock WORKS** — custom image NOT needed for torch (kept only for
  the Seon co-location latency play).
- **A100-80 BF16** — confound-free entropy dynamics; the FP8 1000 tok/s headline is
  Hopper-only and not the target.
- **Two endpoints behind one provider** — vLLM = speed/no control, transformers =
  control. A single deployment cannot do both.
- **Commit is emergent (random re-init), NOT a mask.** Infill = clamp + random-noise
  the hole; never build on `mask_token_id`.
- **`max_denoising_steps` is a CAP** — stop externally, never shrink it.
- **Stay on transformers 5.11.0** — the control/streamer/sampler seam is
  byte-identical through `main`; upgrading buys nothing and risks the cache-API
  rename. Sharper reason: 5.11.0 ALREADY ships the find_spec
  `assume_constant_result` patch but it's inert (`is_torch_greater_or_equal` is
  `@lru_cache`-wrapped and Dynamo unwraps to the unmarked inner fn) — a
  transformers bump does NOT fix find_spec; a 2-line worker-side monkeypatch
  does, no rebuild ([[research/compile-control-ceiling-2026-07-02]]).
- **FP8 / every fast grouped-expert MoE kernel is Hopper (SM90+)-only** — the
  deepgemm FP8 experts kernel, sonicmoe, and the Triton persistent grouped-GEMM
  win all gate on Hopper; on the A100 the experts are BF16-or-nothing and the
  cuBLAS-per-group fallback is already near the SM80 ceiling. **Drop L40S/A6000;
  no custom Triton MoE kernel for the A100 control worker**
  ([[research/fastest-tok-per-dollar-hardware-2026-06-30]],
  [[research/forward-speedup-levers-2026-06-30]]).
- **The forward is MoE-bound (~85-92%)** — attention is ~10%; FA2 is a <10%
  nice-to-have. The real A100 speed lever is sampler-side: `entropy_bound`
  over-commit + free-oracle renoise, not a kernel.
- **The validation ladder is the project's spine** — a dead oracle silently
  zeroing a whole experiment (the voided E1) is exactly why every tier now has a
  fail-loud liveness gate. (Whole-scaffold clamping is NOT settled-dead: E1's
  behavioral result is VOID — re-run on the fixed harness decides it; the
  phased-constraint direction is the parallel retry.)

## Detail docs

- [[architecture]] — the target verified canvas, the glossary, the mode abstraction.
- [[grounding]] — the source-cited claims table per area.
- [[research/mode-driven-guided-generation-2026-06-28]] — THE design (the engine,
  the four modes, the convergent-pass frame, E0–E6).
- [[research/mode-design-critique-2026-06-28]] — the adversarial review this
  roadmap's sequencing is built on.
- [[CLAUDE]] — the index + the copy-pasteable run/deploy loop.
