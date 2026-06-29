---
type: prd
status: active
tags: [prd, agent, flow]
---

# Diffusion roadmap — we are here → the target

The single **we-are-here** doc. [[architecture]] describes the buzzsaw in present
tense (the target as it IS when built); [[grounding]] cites every load-bearing
claim in real source. THIS doc holds what is PROVEN, the gap, and the
kill-gate-first, dependency-ordered path to close it.

The discipline (owner-settled, [[research/mode-design-critique-2026-06-28]]):
**build ONE mode, ONE fn, ONE canvas — gate the riskiest assumption BEFORE
building the engine.** The mode engine, the op-axis, the multi-pass convergence
loop, and the `mode/enter` sentinel are correct in principle and PREMATURE in
sequencing; they are CUT from the MVP until one forced-spec infill beats
prompt+oracle. Generalize after the kill-gate is green, not before.

## ▸ WE ARE HERE

The model is **PROVEN running** and the **control primitives are PROVEN**. The
mechanism has been corrected from the absorbing-mask premise to the real
`LogitsProcessor` clamp. The eval-renoise worker is built but UNTESTED on GPU. The
next move is the **canvas-length probe**, then the **three-arm kill-gate** that
decides whether the clamp/scaffold half of the thesis is worth building at all.

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
- **The eval-renoise worker is BUILT** (`denoise_to_step` / `resume_renoise` +
  `StepCountStopping` + `good_clamp_for_renoise`), py_compile-clean, lowering onto
  public seams only — but **UNTESTED on GPU** ([[research/eval-renoise-worker-build-2026-06-28]]).
- **The oracle is measured.** Parser 92.7% detect / 100% safe-recover; eval 62.5%
  free / 91.5% with a comparator; combined 93.5% ([[grounding]] "oracle").

## The gap to the target

- **Scaffold sizing is unmeasured.** Every `:defn-with-specs` scaffold assumes
  single-canvas infill, but `canvas_length` has not been read off the live worker.
  If a scaffold spills to a second canvas the spec slot loses its co-conditioning
  on the clamped frame — the ONE thing clamp buys over prompting. This GATES every
  scaffold design.
- **The clamp/scaffold half is unproven against the RIGHT baseline.** The real
  competitor is not naked prompting — it is `prompt + the same post-hoc
  parse/eval/renoise loop` (arm 3). No experiment has run arm 3.
- **"Quality by construction" is unproven on faithfulness.** No measurement
  separates a present spec from a non-vacuous one.
- **Speed is untuned.** 137 tok/s on the A100 is ~4 tokens/forward vs the
  reference 15–20; `sdpa` is switched in but unconfirmed live, and
  `entropy_bound`/`max_denoising_steps` are unswept.
- **No Seon-side wiring.** The `:diffusiongemma` provider + the two-endpoint
  adapter + the gym predicates are designed, not built.

## The build path (kill-gate first, dependency-ordered)

### P0 — Canvas-length probe (gates everything, one call)

Read `canvas_length` off the live worker (`introspect`/`probe`). Then tokenize the
actual `:defn-with-specs` scaffold offline and check (a) does it fit one canvas,
(b) do clamp/slot boundaries fall on clean BPE token edges (a `::` abutting a hole,
`:=>` next to a slot may share one token, so `span_to_positions` can't cleanly
separate clamp from slot). Pure arithmetic after one probe. Do this BEFORE
designing any scaffold. (Critique F3.)

### P0 — Speed bench (parallel, cheap)

Confirm `attn_impl=sdpa` loaded; sweep `entropy_bound` (0.1→0.3) and
`max_denoising_steps` against `committed_per_step` / `tok_per_s` to find the real
step count and the A100 BF16 ceiling. The FP8 1000 tok/s headline is unreachable on
Ampere by construction — measure the achievable number, don't chase the headline
([[research/serving-optimization-survey-2026-06-28]]).

### P1 — THE KILL-GATE: three-arm forced-spec infill (E1)

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

**Status — the three-arm driver is BUILT + OFFLINE-PROVEN, awaiting GPU
(2026-06-29).** The driver (`tmp/.../scratchpad/e1_kill_gate.py`, gitignored —
lives under `/private/tmp`, outside the repo) implements all three arms, a single
faithfulness scorer (parse-raw via the real `bb bin/oracle-server` + a structural
domain check + the F2 **vacuity** check + best-effort eval), the `scenario ×
git-sha × arm` scorecard, and the decision rule (arm1 must beat arm3 on
`faithful_rate` by ≥ 0.10, else say KILL plainly). It REUSES the existing
`score_ab.py` structural predicates and the `skill_lift.py`/`closed_loop.py`
RunPod+oracle path — not a fork. The whole pipeline (parse → score → aggregate →
verdict) is proven with ZERO GPU against a mock endpoint of canned worker
responses (`e1_mock.py` / `e1_mock_test.py`): a `guided_wins` fixture fires EARNS
(Δ +0.67) and a `guided_ties` fixture fires KILL (Δ 0.00) + the >30% vacuity
warning — both verdicts demonstrated on canned data. Arms 1 and 3 share the
IDENTICAL post-hoc oracle/repair loop so the only variable under test is the
clamp. The owner runs it once the A100 is back (verify_fresh-gated):
`cd tmp/flash-diffgemma && set -a; . ./.env; set +a && export DIFFGEMMA_EP=<ep> &&
python3 <scratchpad>/e1_kill_gate.py celsius 6`. KNOWN GAP: the eval tier
(`out/worker-oracle-eval/main.js`) is currently broken (throws `single colon` on
every input incl. `42`); the scorer runs eval as best-effort and rests
faithfulness on parse + structural + vacuity until the bundle is fixed
(`EVAL_ENABLED` flag flips it back on).

### P1 — Eval-renoise live test

Drive the built `denoise_to_step` / `resume_renoise` worker on the GPU. Confirm
the two honest unknowns: (1) does the duck-typed `StepCountStopping` actually fire
at step K on 5.11.0 (the OR-accumulate + break path); (2) the closed loop —
re-noise the parser-`:span` positions, confirm the retry clears the gate more often
than a blind full re-noise. ([[research/eval-renoise-worker-build-2026-06-28]],
[[research/eval-renoise-experiment-plan-2026-06-28]].)

### P2 — The `:defn-with-specs` MVP mode (only if P1 passes)

The minimal mode that proves the thesis: `:defn-with-specs`, **system-forced** on a
single unspecced fn (the `missing-spec-target` detector — already grounded, zero
model cooperation), in a canvas-verified scaffold, A/B'd against arm 3. One mode,
one gym predicate (`:spec-infill-instruments`). NO sentinel, NO op-axis, NO
convergence loop. Keep the dynamic-context section-fns regardless — they are the
safe half and they help arm 3 too.

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
contract) → a complete map-in/map-out fn. Remaining work is the GPU round-trip
+ the E1 kill-gate A/B against arm 3.

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

### P3 — The Seon interface

Wire the `:diffusiongemma` provider (two backends behind `SEON_DG_BACKEND`),
reusing `:openai-compat` for `vllm` and adding `seon.ai.diffusiongemma` for
`control`; add the gym predicate kinds. Then the consumer-drivable gym entry point
(`SEON_CONFIG` + `SEON_EXTRA_SRC`, no `src/seon` edits).
([[research/seon-diffusion-interface-design-2026-06-28]],
[[research/gym-third-party-adoption-2026-06-28]].)

### P4 — Generalize: the engine, the stages, the convergence loop (E2–E6)

ONLY after the kill-gate is green. The full design exists
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

## CUT from the MVP (defer until one forced infill beats arm 3)

These are elegant superstructure on an unproven primitive. Build none of them
before P1 is green (critique §3):

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
  rename.

## Detail docs

- [[architecture]] — the target buzzsaw, the glossary, the mode abstraction.
- [[grounding]] — the source-cited claims table per area.
- [[research/mode-driven-guided-generation-2026-06-28]] — THE design (the engine,
  the four modes, the convergent-pass frame, E0–E6).
- [[research/mode-design-critique-2026-06-28]] — the adversarial review this
  roadmap's sequencing is built on.
- [[CLAUDE]] — the index + the copy-pasteable run/deploy loop.
