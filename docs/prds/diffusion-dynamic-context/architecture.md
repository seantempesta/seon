---
type: architecture
status: active
tags: [architecture, agent, schema, flow]
---

# The buzzsaw — diffusion dynamic-context architecture

> **The map** (present tense — the system as it is). The **Seon-side buzzsaw is
> BUILT + offline-proven** in `src/seon/diffusion/*` + `seon.worker-eval` + the
> parse-span change (no GPU, no embeddings; full cljs suite green) — the three
> control legs, the unified `refine` dispatcher, and the `:defn-with-specs`
> scaffold are real code, source-cited in [[grounding]] and detailed in
> [[research/unified-control-oracle-2026-06-29]]. What still **AWAITS GPU** (the
> worker's mid-denoise encoder-KV injection W1–W3 + the end-to-end loop) is marked
> inline. What is PROVEN on the live A100 vs. ON TRIAL, and the gated path between,
> live in [[roadmap]]. The **mode engine, op-axis, and multi-pass convergence are
> designed, not built** — CUT from the MVP until one forced infill beats arm 3
> ([[roadmap]] "CUT from the MVP"); their sections below are marked not-yet-built.

This is the map. The thesis, the one vocabulary, the worker, the unified `refine`
oracle, the scaffold, the mode abstraction, and the staged convergent build — one
orienting pass per piece, each with a pointer to its depth.

## Thesis

A strong **autoregressive** model (Opus) sets DIRECTION; a **diffusion** model
(DiffusionGemma) is the **buzzsaw** that refines whole blocks of Clojure *fast*,
taking feedback **between denoise steps**. The control signal is Seon itself —
its **parser**, its **eval cage**, and its **program-graph retrieval** — fed back
into the generation loop, not waited on after it. AR completion is left-to-right
and blind to what comes after the cursor; a diffusion canvas is **bidirectional
and revisable in place**, so Seon can pin the good spans, re-noise the bad ones,
and inject the right fn-spec mid-generation. The unit the buzzsaw works on is a
**block/form** — a Seon context block becomes the encoder prompt, the 256-token
canvas becomes the form being shaped.

The bet is narrow and explicit: this apparatus earns its keep on **NOISY**
generation (a diffusion model's per-step commits, a weak model), NOT on a capable
AR model that already writes clean Clojure — both strong-model A/Bs were null
(see [[grounding]] "oracle"). The diffusion arm is the part on trial; the path
that tests it is [[roadmap]].

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
  bound re-decides only them. The buzzsaw's revise-in-place dial. BUILT Seon-side:
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
- **the gate (quality ladder)** — the four-rung oracle a generation must clear:
  **parse → eval → instrument → generative-test**. A mode names the prefix it
  must clear; a stage cannot complete until its gate passes. [[grounding]] "oracle".
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
  `denoise_to_step`, `resume_renoise`. Every response carries a `worker_sha`
  source fingerprint.
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
surface the buzzsaw needs, and it is a **supported public seam**, no fork of
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
- `refine` (the unified op, **AWAITS GPU**) — at each checkpoint K the worker calls
  Seon's `seon.diffusion.oracle/refine` ONCE with the current `canvas_text` (+ its
  `offset_map`) and applies the ONE combined control set in a single pass: **clamp**
  the good-form spans, **steer** each retrieval injection (force its span toward
  `replacement`, append `spec_text` to the encoder KV), **re-noise** the broken /
  eval-bad spans, then resume at K+1. The Seon side is built + offline-proven; the
  worker's encoder-KV injection seam (W1–W3) + the end-to-end loop are the remaining
  GPU step ([[research/unified-control-oracle-2026-06-29]],
  [[research/retrieval-denoising-experiment-plan-2026-06-28]]).

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
  one authoritative basis (the loop's `offset` + the token `:end`).
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
clamp spans for the clean forms ONLY — plus disjointness and the wire object. Full
suite green. The remaining end-to-end step is the worker calling `refine`
mid-denoise on the live A100 (see "The worker", AWAITS GPU above).

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
> `refine` oracle. They are CUT from the MVP until one forced-spec infill beats arm
> 3 ([[roadmap]] "CUT from the MVP"). This section is the target shape, not current
> code; the built realization of `:defn-with-specs` is the scaffold above.

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
    for K in checkpoints:                                    ; AWAITS GPU — worker drives the denoise
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
classes and eval-tier failures actually re-noise. The clamp/scaffold half is the
part on trial — the gate that decides whether it beats plain `prompt + the same
post-hoc oracle loop` is [[roadmap]]'s front kill-gate.

## The oracle loop — quality by construction (and its honest limit)

The gate is the four-rung ladder, each rung a real Seon mechanism with MEASURED
economics (full table + method in [[grounding]] "oracle",
[[research/parser-as-generation-oracle-2026-06-28]]):

| Rung | Check | Measured |
|---|---|---|
| **parse** | `parse-forms` → no `:read` entries (the `refine` PARSE leg) | 92.7% of corruptions detected, no model call |
| **eval** | does the form RUN? — `{:ok true}` (the `refine` EVAL leg) | 62.5% caught reference-free / 91.5% with a comparator |
| **instrument** | `:malli/schema` registers + a probe call doesn't throw | always-on Malli in/out/arity |
| **generative-test** | `deftest` passes on N `mg/sample` inputs | property-level |

The first two rungs are now the BUILT `refine` legs. Parse is `parse-forms` (the
measured 92.7%). The eval rung's CO-LOCATED realization is `seon.worker-eval`
(cljs.js self-host, NOT the pod's SCI `seon.eval` — the worker emits
`^:async`/interop CLJS that SCI mis-evaluates), returning
`{ok? error{kind:compile|throw|interrupt}}`; the measured numbers above are the
pod-SCI oracle's, the co-located bundle answers the same "does it RUN?" question on
the worker image. The factual/retrieval residual (the AUROC-0.471 wrong-name class)
is the `refine` RETRIEVE leg's job.

Parse+eval together catch 93.5% of meaning-altering corruptions; the residual is
dead-data mutation + wrong-name, the retrieval tier's job. A stage cannot advance
until its gate is green, and the artifact sits in the DB as proof (a `:seon.fn/spec`
set, a passing `:seon.deftest`), live-provable by query.

**The honest limit (load-bearing for the roadmap).** The 93.5% number measured
DETECTING corruptions of known-good code; it does NOT certify that a from-scratch
generated spec is a faithful contract. Clamping the `:malli/schema` frame
guarantees a spec is PRESENT, not that it is non-vacuous — `[:map]` parses, evals,
and instruments. "Quality by construction" forces presence; faithfulness is a
separate, oracle-blind axis the generative-test rung only indirectly pressures.
This is why the roadmap's kill-gate scores *faithfulness*, not just
instrumentability.

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

## The Seon interface (designed, NOT YET BUILT — roadmap P3)

> The `:diffusiongemma` provider + the two-endpoint adapter + the gym predicates
> are designed, not wired — they land after the kill-gate ([[roadmap]] P3). Target
> shape below.

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

- **AR guides, diffusion refines.** The strong model sets direction; the buzzsaw
  iterates blocks as fast as Seon can parse/eval/retrieve. The value is on noisy
  generation; the clamp/scaffold half is on trial against prompt+oracle.
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

- [[roadmap]] — the single "we are here" + the dependency-ordered, kill-gate-first
  path (canvas-length probe → three-arm E1 → eval-renoise live → the MVP mode → E2–E6).
- [[grounding]] — every load-bearing claim → its `reference-code/…:LINE` cite (the
  transformers seams, the parser oracle, the malli→datahike bridge, the flash source).
- [[research/unified-control-oracle-2026-06-29]] — THE built mechanism: the
  `refine` dispatcher, the three legs folding into the `{clamps, renoise-spans,
  injections}` partition, the offline proof, the AWAITS-GPU mid-denoise integration.
- [[research/retrieval-denoising-experiment-plan-2026-06-28]] — the retrieve leg
  (built) + the GPU-side encoder-KV injection W1–W3 (the AWAITS-GPU half).
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
