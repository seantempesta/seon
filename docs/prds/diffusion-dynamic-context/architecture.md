---
type: architecture
status: active
tags: [architecture, agent, schema, flow]
---

# The verified canvas — diffusion dynamic-context architecture

> **The map** (present tense — the system as it is). The **verified canvas is
> BUILT on both sides**: the Seon control surface (`src/seon/diffusion/*` +
> `seon.worker-eval` + the shared `seon.diffusion.grammar.cljc`) and the worker
> (`gpu_worker.py` `refine_loop` over co-located persistent bb+node oracles) —
> the validation ladder, the unified `refine` dispatcher, validation-as-early-stop,
> and the phased grammar gate are real, proven code, source-cited in [[grounding]].
> What **AWAITS GPU is MEASUREMENT** (the ladder's lift, the over-commit×renoise
> curve, KV bit-exactness, the E1 re-run), not wiring. The kill-gate status + the
> measurement path live in [[roadmap]]. The **mode engine, op-axis, and
> multi-pass convergence stay CUT** — E1's whole-scaffold behavioral measurement
> was VOIDED by a proven harness defect (a dead eval bundle;
> [[research/e1-behavioral-zero-audit-2026-07-02]]), so no valid behavioral
> number exists yet; their sections below are the shape steering WOULD take if
> the E1 re-run or the phased-constraint experiment earns it.

This is the map. The thesis, the one vocabulary, the worker, the unified `refine`
oracle, the scaffold, the mode abstraction, and the staged convergent build — one
orienting pass per piece, each with a pointer to its depth.

## Thesis

A strong **autoregressive** model (Opus) sets DIRECTION; a **diffusion** model
(DiffusionGemma) drives **the verified canvas** — guided, verified generation
that refines whole blocks of Clojure *fast*, taking feedback **between denoise
steps**. The control signal is Seon itself —
its **parser**, its **eval cage**, and its **program-graph retrieval** — fed back
into the generation loop, not waited on after it. AR completion is left-to-right
and blind to what comes after the cursor; a diffusion canvas is **bidirectional
and revisable in place**, so Seon can pin the good spans, re-noise the bad ones,
and inject the right fn-spec mid-generation. The unit the loop works on is a
**block/form** — a Seon context block becomes the encoder prompt, the 256-token
canvas becomes the form being shaped.

The bet is narrow and explicit: this apparatus earns its keep on **NOISY**
generation (a diffusion model's per-step commits, a weak model), NOT on a capable
AR model that already writes clean Clojure — both strong-model A/Bs were null
(see [[grounding]] "oracle").

**The division of labor (E1 + the free-gen capstone):** clamping demonstrably
enforces STRUCTURE (parse 1.0 / structural 1.0 vs naked, live-oracle-scored),
and free generation produces the correct MATH with hygiene-only errors
(def-vs-defn — structurally caught by T1). Structure vs behavioral correctness
is still an OPEN measurement: E1's behavioral zeros were voided by a proven
harness defect — a dead eval bundle that would have zeroed a known-correct
submission too ([[research/e1-behavioral-zero-audit-2026-07-02]]) — and that
failure itself argues the design: **the oracle must be held to the same
fail-loud standard as the code it judges** (the liveness gate now enforces
this). The oracle's role is the thesis: it gates context before generation (the
measured-lift ledger, [[north-star]]), it steers generation mid-denoise
(renoise the flagged spans), and it TERMINATES generation
(validation-as-early-stop — stop when the code is proven to parse, run, and
return the right answer, not when the model feels confident). Whole-scaffold
clamping is SHELVED pending the E1 re-run on the fixed harness; the live
steering direction is the **phased-constraint gate** below. ([[roadmap]] P1.)

## Glossary

One vocabulary, each name grounded in an artifact.

- **canvas** — the 256-token block the diffusion decoder refines with bidirectional
  attention, cross-attending the AR-encoded prompt's KV cache. `canvas_length` is
  the inner-loop unit; long output chains canvases block-autoregressively.
  `generation_diffusion_gemma.py:638`.
- **commit (emergent, not a lock)** — a position "sticks" because `accept_canvas`
  keeps the lowest-entropy positions and `renoise_canvas` re-randomizes the rest
  with **uniformly random vocab ids** — there is **no mask token** in the
  generation path. "Commit" is emergent low-entropy persistence. `EntropyBoundSampler`,
  `generation_diffusion_gemma.py:388/400/444`. See [[grounding]].
- **clamp** — holding chosen canvas positions fixed every denoise step by forcing
  their logits to a near-one-hot, so `accept_canvas` always keeps them. The
  primitive behind infill and span-renoise. `ClampLogitsProcessor`,
  `tmp/flash-diffgemma/diffgemma_common.py:35-78`, applied per-step at
  `generation_diffusion_gemma.py:1034`. PROVEN ([[roadmap]]).
- **infill** — clamp a prefix AND a suffix, let the hole between denoise
  bidirectionally (the move AR cannot make — the hole co-conditions on the
  clamped suffix). PROVEN: both ends held, middle co-conditioned.
  `gpu_worker.py` infill mode.
- **span-renoise** — on a failed gate, map a parser/eval `:span` `[start end]` to
  canvas positions (`span_to_positions`/`build_offset_map`,
  `diffgemma_common.py:184-222`) and DROP those from the clamp set so the entropy
  bound re-decides only them. The loop's revise-in-place dial. BUILT Seon-side:
  `parse-forms` now emits `:span` on every `:form` (not just `:read`) so good and
  broken spans share one basis (`seon.repl.internal`).
- **refine (the unified oracle call)** — `seon.diffusion.oracle/refine`
  (`src/seon/diffusion/oracle.cljs`): ONE call the worker makes per denoise
  checkpoint K — `{::canvas-text ::offset-map}` in, `{::clamps ::renoise-spans
  ::injections ::legs}` out. It runs all three control legs (parse, retrieve,
  eval-fold) and returns ONE combined control set that PARTITIONS the canvas: a
  clamp is a good form whose span overlaps NEITHER an injection NOR a renoise span,
  so no region is double-covered. BUILT + offline-proven. See the oracle-loop
  section + [[research/unified-control-oracle-2026-06-29]].
- **the three legs** — the three control signals `refine` folds together: **parse**
  (`parse-forms`, good-form + broken-syntax spans), **retrieve**
  (`seon.diffusion.retrieval`, hallucinated-symbol → real-API injection), **eval**
  (`seon.worker-eval`, a separate node self-host bundle whose verdicts arrive as
  span-keyed data). BUILT.
- **the validation ladder** — the tiered oracle a generation climbs, cheapest
  decisive tier first: **T0 parse** (bb, ~0.05 ms — well-formed?) → **T1
  structural lint** (~free — AST shape: `malformed-def?` def-vs-defn) → **the
  phased grammar gate** (per-phase allowed heads) → **T2 eval** (node cljs.js,
  ~2.6 ms — does it RUN? the only tier that resolves symbols) → **T3 behavioral**
  (`[{call,expect}]` — does it return the RIGHT answer?). Each tier's failures
  become renoise spans; passing every applicable tier is PROOF. The
  persistence-side rungs (**instrument → generative-test**) are the same ladder
  continued at the DB boundary when code lands as `:seon.fn` entities. [[grounding]].
- **validation-as-early-stop** — the loop's termination criterion: STOP when the
  canvas is oracle-proven (parse-clean AND eval-clean AND behavioral-clean), not
  when entropy/step-count says the model is confident. The model's probability is
  irrelevant once the code is proven to run. `refine_loop`'s gate, `gpu_worker.py`.
- **the phased grammar gate** — per-phase grammar enforcement as a renoise
  source: `::phase :schemas` allows `ns`+`register!` (rejects `def`/`defn`);
  `::phase :functions` allows `ns`+`defn` (rejects `register!`/bare `def`).
  `phase-grammars`/`phase-violation?` in the shared grammar ns. The live
  steering direction while whole-scaffold clamping awaits its E1 re-run.
- **`seon.diffusion.grammar.cljc`** — the ONE dependency-free definition of the
  T1/phase predicates, loaded by BOTH the pod oracle and babashka; bb
  `op:"refine"` folds structural + phase renoise natively, so the worker reaches
  the cheap tiers mid-denoise with no pod round-trip and no drift.
- **mode** — a `:seon.dg.mode` entity: a clamped-scaffold template + named infill
  slots + dynamic-context section-fns + a gate-rung prefix + a re-noise policy +
  an allowed-vocab set + an op (`:create`/`:upsert`/`:retract`). Data, not code —
  one engine reads the row and runs the generation. Overridable per cluster via
  the same `install!`/`remove!` seed-copy discipline as context blocks.
- **scaffold** — the template the model INFILLS: literal text that gets CLAMPED
  plus slots the model fills. The `:malli/schema` frame being clamped is how a spec
  is FORCED, not requested. BUILT: `seon.diffusion.scaffold/build-scaffold`
  (`src/seon/diffusion/scaffold.cljs`) emits the `:defn-with-specs` frame —
  `::frame-text` + `::infill-spans` + `::clamp-spans` that TILE the frame exactly
  (no gap, no overlap); same span vocabulary + `to-wire` shape as the retrieval leg.
- **slot** — one infill hole: an id, a token budget, an optional per-slot quality
  predicate.
- **dynamic-context section-fn** — a pure function of the DB at render time that
  produces the prompt conditioning a generation (the whole namespace, generated
  example data, the unspecced-fn list, retrieved related fns, the last
  error+span). Exactly the reactive `:seon.ctx/fn` pattern. Re-queried every
  generation; never stored.
- **the control worker** — the RunPod transformers `@Endpoint` (`gpu_worker.py` +
  `diffgemma_common.py`) that keeps the per-step `LogitsProcessor`/sampler seam.
  Modes: `probe`, `introspect`, `generate`, `clamp_smoke`, `infill`,
  `denoise_to_step`, `resume_renoise`, `inject`, `refine_loop` (+ the `kv_reuse`
  payload path). Every response carries a `worker_sha` source fingerprint.
- **the vLLM endpoint** — the fast serving backend that genuinely runs the
  block-diffusion decode but seals the sampler shut (no per-step hook): SPEED, no
  CONTROL. The two ride behind one Seon provider.
- **`:diffusiongemma` provider** — the Seon LLM provider with two backends
  (`SEON_DG_BACKEND=vllm|control`): `vllm` reuses the `:openai-compat` path
  verbatim; `control` is the new `seon.ai.diffusiongemma` adapter over the RunPod
  async-job API. See [[grounding]] "interface".
- **pass / convergence** — a build is a sequence of refinement PASSES over a
  namespace; each reads the current program-graph state, mutates via one
  mode-guided generation, re-queries. Converged = the "what's missing" reactive
  sections go empty.

## The control seam

The whole thesis rests on one fact, source-verified against transformers v5.11.0
(the exact deployed version): **`logits_processor` is applied once per denoise
step**, at `generation_diffusion_gemma.py:1034`, BEFORE the built-in temperature
schedule. A custom `LogitsProcessor` (our `ClampLogitsProcessor`) therefore gets a
per-step vote on every position, every step. That is the between-step control
surface the verified canvas needs, and it is a **supported public seam**, no fork of
`generate()`.

Two corollaries that are easy to get wrong (both grounded, [[grounding]]):

- **Commit is emergent, not a mask.** The canvas inits and renoises with random
  vocab ids; `mask_token_id=4` is vestigial for text generation. Build infill by
  CLAMPING kept spans + random-noising the hole — never on a mask token.
- **`max_denoising_steps` is a CAP, not a checkpoint.** Shrinking it COMPRESSES
  the temperature ramp (`:311`) — a different generation regime, not a peek at
  step K. To stop early at the model's natural intermediate state, keep N and stop
  EXTERNALLY: either an outer loop of K-step `generate()` calls re-seeded via
  `decoder_input_ids` + `ClampLogitsProcessor` + fed-back `past_key_values`, or a
  `DiffusionGemmaAdaptiveStopping` subclass injected via
  `_prepare_diffusion_stopping_criteria` (runs on the non-compiled path —
  mutually exclusive with `torch.compile`).

## The worker

The control worker is a stateless denoiser: prompt text in, result text +
per-step diagnostics out; the 50 GB weights stay GPU-resident, no tensors cross
the wire. Its modes lower the architecture onto the proven primitives:

- `probe` / `introspect` — cheap env + live-model reflection (no 50 GB load /
  one load): output fields, sampler, gen-config, `canvas_length`.
- `generate` — plain generation + the denoise-step / commit-per-step trajectory.
- `clamp_smoke` — the decisive proof the clamp holds positions fixed (PROVEN).
- `infill` — clamp prefix+suffix, denoise the hole (PROVEN, incl. the spec-slot).
- `denoise_to_step` / `resume_renoise` — the eval-renoise round-trip: stop the
  real N-step schedule at step K via an external `StepCountStopping`, hand the
  partial canvas to Seon to parse/eval, clamp the GOOD spans + free the BAD span,
  resume. Built, not yet GPU-tested. See [[grounding]], `eval-renoise-worker-build`.
- `refine` (the unified op) — at each checkpoint K the worker calls the oracle
  ONCE with the current `canvas_text` (+ its `offset_map`) and applies the ONE
  combined control set in a single pass: **clamp** the good-form spans, **steer**
  each retrieval injection (force its span toward `replacement`, append
  `spec_text` to the encoder KV via W1/W2/W3), **re-noise** the broken /
  lint-flagged / phase-violating / eval-bad spans, then resume at K+1. The cheap
  tiers (parse + structural + phase) answer from the CO-LOCATED persistent bb
  server's `op:"refine"` (~0.05 ms, no pod round-trip); injections need the pod
  graph, eval the node bundle ([[research/unified-control-oracle-2026-06-29]],
  [[research/retrieval-denoising-experiment-plan-2026-06-28]]).
- `refine_loop` (the in-worker closed loop) — the whole
  denoise-to-K → oracle → renoise → resume loop runs SERVER-SIDE over the
  persistent oracle pipe (0.05 ms warm vs ~21 ms spawn — the round-trip was the
  bottleneck, [[colocation-performance-plan]]), with
  **validation-as-early-stop as its gate**: parse → eval → behavioral
  (`eval_gate` default on; `behavioral` = `[{call,expect}]`), stopping at the
  cheapest tier that proves the canvas correct. Wired + proven with the real
  bb+node oracles; what awaits the A100 is the MEASUREMENT of its lift.

**Deployment discipline (load-bearing):** a warm worker keeps serving OLD code
after a plain `flash deploy` until it scales to zero — so every response carries a
`worker_sha` (`sha256` of the worker source, computed inside the container) and
`verify_fresh.py` refuses to trust a measurement until `worker_sha == local`. The
force-fresh-that-preserves-the-endpoint-id is bumping `FLASH_GPU_IMAGE` (a
structural field → server-side worker recreation). The full procedure is
[[research/flash-deployment-stability-2026-06-28]]; keep-warm + FlashBoot reality
is [[research/flash-warm-reuse-2026-06-28]].

## The unified control oracle — `refine` (BUILT, Seon-side)

The three control signals are not three round-trips — they fold into ONE call the
worker makes per denoise checkpoint: `seon.diffusion.oracle/refine`
(`src/seon/diffusion/oracle.cljs`). `{::canvas-text ::offset-map}` in, ONE combined
control set out:

```clojure
{::clamps        [{::span [s e] ::source "…"}]                    ; HOLD — do not re-noise
 ::renoise-spans [{::span [s e] ::error-kind :eof ::source "…"}]  ; RE-NOISE these
 ::injections    [<retrieval/injection {span replacement spec_text}>]  ; clamp-toward-real-API
 ::legs          [:parse :retrieve]}                              ; (+ :eval when verdicts folded)
```

**The three legs fold in (each a real Seon mechanism):**

- **PARSE** — `seon.repl.internal/parse-forms` on the no-fence raw basis (the
  `parse-raw` path, `{:strip-fences? false}`). It now emits a `:span [start end]` on
  every `:kind :form` entry, not just `:read` entries — the load-bearing change that
  lets GOOD-form spans (clamp candidates) and BROKEN-syntax spans (renoise) share
  one authoritative basis (the loop's `offset` + the token `:end`). Over the good
  forms, the parse leg then runs the CHEAP ladder tiers from the shared
  `seon.diffusion.grammar.cljc`: **T1 structural lint** (`malformed-def?` — a
  `(def name [args] body)` is unambiguously a defn typo → renoise its span) and,
  when `::phase` is supplied, **the phased grammar gate** (`phase-violation?` —
  a `defn` in the `:schemas` phase, a `register!` in the `:functions` phase →
  renoise). Because the predicates are the SAME ns bb loads, bb `op:"refine"`
  answers all of parse+structural+phase in one ~0.05 ms call.
- **RETRIEVE** — `seon.diffusion.retrieval/retrieve-for-canvas` reads
  `:seon.fn/sym` from the program graph and yields the hallucinated-symbol
  injections (`{::span ::replacement ::spec-text}`) — a confidently-wrong name
  (`db/transct!`) → the real API (`db/transact!`, edit-distance 1) + its signature.
  Pure over a db value; the `SEON_EMBED` semantic enhancement
  (`retrieve-for-canvas+semantic`) augments candidates from the Proximum/Vertex fn
  index, fail-soft.
- **EVAL** — `seon.worker-eval` (`src/seon/worker_eval.cljs`) is a SEPARATE node
  self-host bundle (cljs.js, NOT SCI, NOT bb — it must compile `^:async`/interop
  faithfully), so it runs out-of-process and its verdicts arrive as DATA via
  `::eval-verdicts` (span-keyed `{ok? error{kind:compile|throw|interrupt}}`). The
  fold: a bad verdict becomes a renoise span UNLESS retrieval already named the real
  API for that span (the injection supersedes). When no verdicts are supplied,
  `refine` runs PARSE + RETRIEVE only and says so in `::legs`.

**The partition is the contract.** A CLAMP is a good form whose span overlaps
NEITHER an injection (it carries a hallucination → steer, don't freeze) NOR a
renoise span (parse error or eval-bad). The three span sets never double-cover a
region — proven disjoint offline. `to-wire` flattens the set to the worker's
`{op:"refine", legs, clamps, renoise_spans, injections}` JS object (each injection
reuses `retrieval/to-wire`, byte-identical to the standalone emit), and
`bin/oracle-server` exposes `op:"refine"` — though bb's pure `.cljc` classpath
covers only the PARSE tier (clamps + renoise, `injections: []`); the full three-leg
`refine` runs in the pod (or is assembled by the Python `Oracle` shim from the bb
parse call + the node eval call + a pod retrieve call).

**Offline-proven, no GPU.** `test/seon/diffusion/oracle_test.cljs` feeds one canvas
carrying BOTH a syntax error AND a `db/transct!` hallucination and asserts the
combined set: the eof renoise span, the `db/transct! → db/transact!` injection, and
clamp spans for the clean forms ONLY — plus disjointness and the wire object; the
`structural-def-vs-defn` and `phase-grammar-gate` regressions pin the cheap tiers.
Full suite green. The worker side is WIRED (`refine_loop` over the persistent bb
`op:"refine"`); the remaining step is MEASURING its lift on the live A100
([[roadmap]] "The GPU-measurement path").

**Char-span → token-position is the worker's job, via `offset_map`.** Every span
Seon emits — clamp, renoise, injection, scaffold infill — is absolute CHAR offsets
`[start end)`. The worker maps each to canvas TOKEN positions by overlap
(`diffgemma_common.py span_to_positions`/`build_offset_map`). A boundary that falls
mid-token would put one token in BOTH the clamp and the infill set, so the two ops
can't separate cleanly. Measured offline (CPU tokenizer only, no GPU): the
`:defn-with-specs` scaffold's huddled `]]` closers merged into one BPE token and
STRADDLED 4 boundaries; the scaffold's `map-close` clamp text was nudged to open on
a newline so every boundary now lands on a token edge. The span-based control
primitives survive real BPE tokenization. ([[roadmap]] P2 "BPE token-boundary
alignment".)

## The scaffold — the `:defn-with-specs` frame (BUILT)

`seon.diffusion.scaffold/build-scaffold` (`src/seon/diffusion/scaffold.cljs`) is the
Seon-side template generator that CONSTRUCTS the clamp frame a fn is generated INTO,
before any GPU call (where the retrieval leg CORRECTS a symbol mid-denoise). Given
`{::fn-name ::ns ::intent}` it emits the roadmap's MVP frame: a `defn` plus its
map-in/map-out `:malli/schema` contract as a partially-fixed canvas —

- `::frame-text` — valid Clojure with placeholder slots: the two
  `(schema/register! ::name-request [:map …])` / `::name-response` forms + the
  `(defn name {:malli/schema [:=> [:cat ::name-request] ::name-response]} …)`,
  ns-relative so `::` expands in the TARGET ns at eval time.
- `::clamp-spans` — the fixed structure the worker HOLDS (the `defn`/`register!`
  heads, the `:=>` wiring, the `::request`/`::response` refs) so the map-in/map-out
  shape can't drift.
- `::infill-spans` — the four generated slots (request `:map` body, response `:map`
  body, arglist destructure, fn body); the spec slots infill FIRST so the body
  generates against a KNOWN contract — quality by construction.

The two span sets TILE the frame exactly (no gap, no overlap), reusing the same span
vocabulary + `to-wire` `{op,span,role}` shape as `seon.diffusion.retrieval` (op
`:clamp` for held structure, op `:infill` for slots) so the worker consumes scaffold
spans the same way it consumes retrieval injections. Offline-proven
(`test/seon/diffusion/scaffold_test.cljs`): the frame parses to 3 clean top-level
forms, every infill span lands on its slot, the clamp text holds every structural
token, the spans partition `[0, len)`. The GPU round-trip + the E1 kill-gate A/B vs
arm 3 are the remaining steps ([[roadmap]] P2).

## The mode abstraction (designed, NOT YET BUILT)

> The `:seon.dg.mode` row, the `run-mode!` engine, the op-axis, and the
> `mode/enter` sentinel are the GENERALIZATION layer above the built scaffold +
> `refine` oracle. E1's whole-scaffold behavioral measurement was voided (dead
> eval bundle, [[roadmap]] P1) — no valid lift number exists yet — so they STAY
> CUT; this section is the shape steering would take if the E1 re-run or the
> phased-constraint experiment earns it. The built realization of
> `:defn-with-specs` is the scaffold above — a tested primitive, not a build
> target.

A **mode is data** — a `:seon.dg.mode` row carrying six things: a clamped
**scaffold** template, named **slots**, a list of **context-fn** symbols, a
**gate-rung** prefix, a **re-noise** policy, and an allowed-**vocab** set, plus an
**op** (`:create`/`:upsert`/`:retract`) the engine selects from the target's
current state. ONE engine (`run-mode!`) reads the row and runs the same lowering
for every mode; a new stage is a new ROW, not new code — the same code-as-data
discipline that governs context blocks and routes. Building per-stage hardcoded
mode functions would be the `foo-v2` anti-pattern at the mode layer.

**The model invokes a mode two ways.** *System-forced*: Seon DETECTS an unspecced
fn (a `:seon.fn` with no `:seon.fn/spec`) from the program graph and forces
`:defn-with-specs` on it — the scaffold makes the spec a clamped, must-fill hole,
so guided generation is a CONSTRAINT, not a suggestion. *Model-initiated*: the
agent emits a parsed sentinel `(mode/enter :defn-with-specs {…})` that rides the
seam Seon already uses to turn replies into actions. The system-forced path needs
zero model cooperation and proves the thesis alone; the sentinel is deferred
(see [[roadmap]]).

**The worked modes map to the owner's stages:** `:design-schema` (Stage 1 —
schema → generated-example-data → adapt loop), `:defn-with-specs` (Stage 2 — clamp
the `defn` + force the `:malli/schema` in/out into infill slots — the kill-gate
mode), `:generative-test` (Stage 3), `:repl-explore` (Stage 4, a thin/empty
scaffold = the normal agent loop), and `:edit-namespace` (the ns DEFINITION
itself). Full taxonomy + scaffolds:
[[research/mode-driven-guided-generation-2026-06-28]] §2.

**`:mode/edit-namespace` — the ns definition is editable too** (design, unbuilt). A
refinement pass that converges a namespace is not just the fns and schemas inside
it — the `ns` form and its `:require`s are part of the namespace and are themselves
a mutation target. This mode shows the EXISTING `ns` form as a BEFORE and lays out
the WHOLE `ns` form as the infill AFTER: a clamped scaffold of the `ns` skeleton
with infill slots for the require vector + aliases, so the agent edits its
dependency set under the same clamp+infill lowering as every other mode. The
op-axis applies directly — adding a require is `:upsert`, dropping a dead one is
`:retract`; the persisted edge it writes is the program graph's `:seon.ns/requires`.
Editing requires is part of converging the namespace, so it composes with the
convergent-pass frame, not beside it.

### Dynamic context — discover, require, understand

Two reactive section-fns make a namespace edit informed rather than a guess. Both
are pure functions of the DB at render time (the section-fn pattern, re-queried
every generation):

- **Embedding-driven discovery** (design, unbuilt). When the agent is editing a
  namespace, parse its words/identifiers and run an **embedding search** over the
  existing **Vertex + Proximum/HNSW** program-graph index (gated by `SEON_EMBED`)
  for (a) relevant EXISTING namespaces and (b) interesting FUNCTIONS within them
  that might be reusable. Surface the hits as context so the agent DISCOVERS other
  software it could `:require` and call — the retrieval-as-control-signal thesis
  applied to dependency discovery, turning "what's already in this core I could
  reuse?" into a semantic-search-backed section rather than a guess. Grounding: the
  Vertex/Proximum embedding index over `:seon.fn/source`, the `:seon.ns`/`:seon.fn`
  program graph. See [[grounding]].
- **Required-API render** (in flight, agent a88b157). Render the API —
  signatures + docstrings — of the namespaces the agent ALREADY `:require`s, so it
  understands its current deps. Read off `:seon.ns/requires` + the `:seon.fn`
  entities those namespaces own.

Together they are the loop `:edit-namespace` runs on: **discover** (the embedding
search finds NEW deps) → **require** (the `:edit-namespace` infill writes them into
the `ns` form) → **understand** (the required-API render explains the ones it now
has).

## Guided generation — how a mode lowers (engine NOT YET BUILT; the oracle call IS)

The per-checkpoint oracle call (`refine`) and the frame builder (`build-scaffold`)
are BUILT; the `run-mode!` ENGINE that orchestrates them across a mode row is the
generalization layer ([[roadmap]] "CUT"). The target lowering, with the built parts
named:

```
run-mode!(mode, op, target):                                 ; ENGINE: not yet built
  scaffold = build-scaffold(fn-name, ns, intent)             ; BUILT — frame_text + clamp/infill spans
  ctx      = render(mode.context-fns, db, mode-state)        ; §reactive, re-queried
  for attempt in 0 .. mode.renoise.max-retries:
    for K in checkpoints:                                    ; the built refine_loop drives this
      canvas   = control-worker.denoise_to_step(prompt=ctx, clamp=scaffold, K)
      ctrl     = refine({canvas_text canvas, offset_map})    ; BUILT — one call, the three legs fold
      control-worker.apply(ctrl)                             ; clamp / steer injections / renoise spans
    if ctrl.renoise-spans empty AND ctrl.injections empty:
      persist(canvas); advance(mode.next-mode); return       ; detect-and-tee → program graph
    ctx = render(mode.context-fns, db, mode-state')          ; error+span now in view
  flag-unresolved(target)                                    ; honesty > completion
```

The SAFE parser classes (`:eof`/`:unmatched-delimiter`, the bulk of corruptions)
are repaired in place by `seon.repair` with NO model round-trip; only the FLAG
classes and eval-tier failures actually re-noise. The kill-gate on the
clamp/scaffold half RAN but its behavioral result was voided ([[roadmap]] P1) —
this lowering is retained as the shape the E1 re-run or phased-constraint
steering would generalize into, nothing more.

## The validation ladder — one oracle, mid-denoise control AND persistence gate

The ladder is ONE tiered oracle, climbed cheapest-decisive-tier-first. Its lower
tiers run MID-DENOISE (each failure is a renoise span; passing every applicable
tier TERMINATES the loop); its upper rungs run at the PERSISTENCE boundary when
code lands in the DB. Same ladder, two duty stations:

| Tier | Check | Cost / measured | Runs |
|---|---|---|---|
| **T0 parse** | well-formed? `parse-forms` → no `:read` entries | ~0.05 ms (bb, co-located); 92.7% of corruptions detected | every checkpoint |
| **T1 structural lint** | AST shape: `malformed-def?` (def-vs-defn) | ~free, same bb call | every checkpoint |
| **phase grammar** | allowed heads for the current `::phase` (`phase-violation?`) | ~free, same bb call | every checkpoint (when phased) |
| **T2 eval** | does it RUN? `{:ok true}` — the only tier that resolves symbols | ~2.6 ms (node cljs.js); 62.5% free / 91.5% w/ comparator | at the gate, `eval_gate` on |
| **T3 behavioral** | does it return the RIGHT answer? `[{call,expect}]` | low-ms; the ground-truth proof | at the gate, when tests supplied |
| **instrument** | `:malli/schema` registers + always-on Malli in/out/arity | persistence-side | when code lands as `:seon.fn` |
| **generative-test** | `deftest` passes on N `mg/sample` inputs | persistence-side | the publish gate |

Each tier catches what the tier below structurally cannot: T1 catches def-vs-defn
WITHOUT paying for eval (it's AST-catchable — eval is the wrong motivator for it);
T2 catches undeclared vars no AST can resolve; T3 catches the off-by-one body that
runs fine (the live-pod lesson: `instrumentable ≠ correct` — a semantically wrong
transducer body defined AND instrumented cleanly). And the ladder polices ITSELF:
a dead tier must fail loud, never silently zero (the voided-E1 lesson — the
`assert_oracle_live` golden-sample gate). **Termination is
validation-as-early-stop:** the `refine_loop` gate is parse → eval → behavioral,
and the loop stops the moment the canvas is PROVEN — proven by
`eval_gate_earlystop_proof.py` with the real bb+node oracles (won't stop on
parses-but-fails-eval, won't stop on runs-but-wrong, stops at iter 0 on correct).

The co-located realization: T0/T1/phase answer from the persistent bb server
(`op:"refine"`, the shared `grammar.cljc` predicates); T2 is `seon.worker-eval`
(cljs.js self-host, NOT SCI — the worker emits `^:async`/interop CLJS that SCI
mis-evaluates), returning `{ok? error{kind:compile|throw|interrupt}}`. The
factual/retrieval residual (the AUROC-0.471 wrong-name class) is the `refine`
RETRIEVE leg's job. A persisted artifact sits in the DB as proof (a
`:seon.fn/spec` set, a passing `:seon.deftest`), live-provable by query. Full
economics: [[grounding]] "oracle", [[research/parser-as-generation-oracle-2026-06-28]].

**The honest limit (measured, load-bearing).** Detection numbers were measured on
corruptions of known-good code; they do not certify a from-scratch spec is a
faithful contract — `[:map]` parses, evals, and instruments; a live-pod check
caught a semantically wrong body that defined and instrumented cleanly. E1's
attempt to quantify structure-vs-correctness was VOIDED by a dead eval bundle
([[research/e1-behavioral-zero-audit-2026-07-02]]; re-run pending on the fixed
harness — [[roadmap]] P1). The principle stands: T3 behavioral tops the
mid-denoise ladder — "who cares about probability" — the generative-test rung
guards the publish gate, and every tier must prove its OWN liveness before its
verdict counts. Faithfulness pressure comes from ground truth, not from shape.

## Speed — over-commit + free-oracle renoise, not a faster kernel

The forward is **MoE-bound (~85-92%)** and every fast grouped-expert kernel
(deepgemm FP8, sonicmoe, the Triton persistent grouped-GEMM) is **Hopper
SM90-gated** — on the A100 the experts run BF16 on a cuBLAS-per-group fallback
that already sits near the SM80 ceiling. There is no KERNEL lever on the control
card ([[research/forward-speedup-levers-2026-06-30]],
[[research/fastest-tok-per-dollar-hardware-2026-06-30]]).

The compile ceiling is characterized, not closed
([[research/compile-control-ceiling-2026-07-02]]): the find_spec graph-break is a
2-line worker-side monkeypatch away (the shipped `assume_constant_result` patch
is inert — Dynamo unwraps the `@lru_cache` wrapper past the mark; a transformers
bump does NOT fix it); the batched_mm device-side assert is most plausibly
static-cache under-sizing on multi-canvas runs (probe: `max_length=288`,
payload-only); and the per-step `ClampLogitsProcessor` is compile-COMPATIBLE (it
runs eager between compiled units) — only a custom Python StoppingCriteria
forfeits compile, so a compiled refine shape exists without forking the sampler.
The compiled path has never actually been measured; it is a $0-rebuild probe
chain on the measurement path, not a settled wall.

The verified-canvas speed lever is sampler-side and UNIQUE to a diffusion canvas with a
free oracle: **crank `entropy_bound` (over-commit — more tokens per forward, 2-3×
fewer forwards) and let the ~0.05 ms co-located oracle renoise exactly the wrong
spans.** An AR model cannot un-commit position 47 and keep the other 200; the
canvas + the free oracle give us that primitive. Net win iff forwards saved by
over-commit exceed forwards spent re-denoising flagged spans — the §3 three-arm
sweep is the measurement. The hardware ranking: A100-BF16 sampler-tuned is the
control path today; TPU v5e via the native JAX DiffusionGemma (whose
`_early_stopping.py` IS validation-as-early-stop) is the high-ceiling bet,
de-risked by one ~$5 spike; H100-FP8 is a serving number without the control seam.

## The staged convergent build (designed, NOT YET BUILT)

> The op-axis, the multi-pass convergence loop, and the monotonicity guard are CUT
> from the MVP until one forced infill beats arm 3 ([[roadmap]] P4/"CUT"). Target
> shape below; the built pieces it will compose are the scaffold + `refine`.

A "build" is not one-shot — it is iterative refinement PASSES converging a
**namespace** toward a goal, and it leans on two settled Seon theses:

- **Code-as-data: the namespace IS DB entities.** The working namespace is the
  `:seon.ns`/`:seon.fn`/`:seon.schema` program-graph entities; detect-and-tee
  captures every successful eval as these, identity-upsert means a redefinition
  REPLACES in place. So a pass that overwrites a fn is an eval whose sym exists; a
  pass that drops a dead fn is a retract. CREATE is just UPSERT against an absent
  entity — one storage model, three ops.
- **Reactive context: namespace state is f(DB).** Each pass re-renders the current
  schemas/fns/tests by query; when the goal is met the "what's missing" sections
  (unspecced fns, untested fns, red goal-predicates) return empty and VANISH. The
  build is self-healing and convergence is "the missing-work sections are blank" —
  a `namespace-scorecard` number trending to zero, reusing the EXISTING publish
  gate (`specced?` AND `last-passed-at > last-failed-at`).

The honest risk is oscillation / invisible breakage (a schema upsert that breaks a
downstream fn whose instrumentation only throws when CALLED), bounded by a
monotonicity guard and gated by the roadmap's E6. Full frame + the scorecard:
[[research/mode-driven-guided-generation-2026-06-28]] §1.4, §5.3.

## The Seon interface (WIRED — roadmap P3)

DiffusionGemma slots into Seon as one provider, `:diffusiongemma`, with two
backends behind `SEON_DG_BACKEND`: `vllm` (fast demo, no control) reuses the
existing `:openai-compat` request path with zero new code; `control` is the new
`seon.ai.diffusiongemma` adapter speaking the RunPod async-job JSON. Transport
retry is FREE — the adapter maps RunPod failures onto the standard
`:seon.ai/error` envelope and inherits `seon.agent.turn/call-llm!`'s backoff. The
**gym** drives it unchanged: a diffusion experiment is new scenario EDN + a small
set of new predicate kinds; every run lands in the `scenario × git-sha`
scorecard, so a knob sweep is a MOVED number, not an anecdote. A downstream
consumer drives the gym against THEIR config/provider via `SEON_CONFIG` +
`SEON_EXTRA_SRC` with zero `src/seon` edits. See
[[research/seon-diffusion-interface-design-2026-06-28]],
[[research/gym-third-party-adoption-2026-06-28]].

## Cross-cutting principles

- **AR guides, diffusion refines, the ORACLE decides.** The strong model sets
  direction; the canvas loop iterates blocks as fast as Seon can
  parse/lint/eval/test. The oracle carries the thesis — it gates context
  before generation, renoises the wrong spans during it, terminates it on
  proof, and proves its OWN liveness first. Steering is on trial: phased
  constraints live, whole-scaffold shelved pending the E1 re-run.
- **Modes are data, overridable.** Modes seed-copy into a cluster like context
  blocks; a consumer adds or reshapes a mode with `install!`/`remove!` and zero
  `src/seon` edits.
- **The prompt is a reactive projection.** Dynamic context is section-fns —
  pure functions of the DB re-queried every generation; no stored prompt, no stale
  copy, self-healing.
- **Stop external, never shrink the cap.** Keep `max_denoising_steps=N`; gate via
  an external stopping criterion or an outer loop of K-step calls. Shrinking the
  cap is a different regime.
- **Know what's live.** Never trust a measurement without `worker_sha == local`.
- **Honesty > completion.** A stage that can't clear its gate flags the target
  unresolved in the agent's reactive context; there is no "mostly done".

## Detail docs

- [[roadmap]] — the single "we are here" (offline surface complete + wired
  on-worker) + the GPU-measurement path (exp D → ladder lift → over-commit×renoise
  → KV) + the executed kill-gate history.
- [[grounding]] — every load-bearing claim → its `reference-code/…:LINE` cite (the
  transformers seams, the parser oracle, the malli→datahike bridge, the flash source).
- [[research/unified-control-oracle-2026-06-29]] — THE built mechanism: the
  `refine` dispatcher, the legs folding into the `{clamps, renoise-spans,
  injections}` partition, the offline proof (mid-denoise integration since wired
  as `refine_loop`; its LIFT is the pending measurement).
- [[research/retrieval-denoising-experiment-plan-2026-06-28]] — the retrieve leg
  + the worker's encoder-KV injection W1–W3 (both built; W2 re-prefill is the
  default route).
- [[research/mode-driven-guided-generation-2026-06-28]] — THE design: the mode
  abstraction, the four modes, the convergent-pass frame, the experiment ladder.
- [[research/mode-design-critique-2026-06-28]] — the adversarial review that shapes
  the roadmap (the missing arm-3 baseline, the vacuity gap, canvas-length gating,
  cut the sentinel/op-axis/multi-pass until one forced infill wins).
- [[research/transformers-diffusion-source-grounding-2026-06-28]] — the per-step
  seam, the stopping ABC, the temperature caveat, the streamer verdict.
- [[research/parser-as-generation-oracle-2026-06-28]] — the measured three-tier ladder.
- [[research/serving-optimization-survey-2026-06-28]] — vLLM runs the decode but
  seals the sampler → the two-endpoint split; the 137 vs 1000 tok/s explanation.
- [[infra-flash-runpod]] — the operational deploy/debug log + the full env-fix saga.
