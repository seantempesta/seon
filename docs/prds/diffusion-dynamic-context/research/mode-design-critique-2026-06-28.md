---
type: research
status: active
tags: [research, diffusion, agent, schema]
---

# Mode-driven guided generation — adversarial design critique

> An adversarial review of `mode-driven-guided-generation-2026-06-28.md` BEFORE we
> build it. The job is to find the holes the spec under-weighted, ranked by
> probability × wasted-effort, with the cheapest kill-experiment for each. The spec
> has an honest §8; this doc attacks what §8 missed or softened — and where its own
> grounding docs contradict its plan. No GPU was called; no other file modified.

## TL;DR

- **The single most likely way this wastes effort: the latency constraint FORCES the
  gate to be post-generation, at which point "modes" collapse into generate-then-fix
  — and the spec never benchmarks against that baseline.** The whole value of a
  per-step gate is "stop early when correct." But the source-grounding doc proves the
  custom stopping criterion runs ONLY on the non-compiled path (`is_compiling=False`,
  mutually exclusive with `torch.compile`, grounding §5 items 4+9), and §8.2 of the
  spec already concedes per-step eval is probably too slow → "defer eval to the end of
  each K-step call." Combine those two and the realistic mechanism is: generate K
  steps → decode → parse/eval → renoise → regenerate. That is *exactly* generate-then-fix
  with a clamp. The spec's kill-gate E1 A/B's forced-infill against *naked* prompting
  (arm 2), but the real competitor is **prompt + the same post-hoc parse/eval/renoise
  loop** (call it arm 3). If modes don't beat arm 3, the entire clamp/scaffold apparatus
  is dead weight. The spec does not include arm 3. **That omission is the biggest risk in
  the document.**

- **The "quality by construction" thesis imports the 93.5% oracle number into a context
  it was never measured in.** The parser-oracle's 92.7%/91.5%/93.5% (§5 table) measured
  *detecting corruptions of known-good code*. It says NOTHING about whether a
  from-scratch generated spec is semantically faithful. A vacuous `[:map]` spec parses,
  evals, instruments, and even passes a generative test (vacuous spec → vacuous data →
  vacuous property all agree). §8.1 names this but mislabels it "the E1 kill-gate"; it is
  actually a *separate, oracle-blind* failure that no rung in the ladder catches. "Quality
  by construction" is hollow exactly where it's loudest.

- **Foundational unknown treated as a detail: `canvas_length` is unmeasured, yet every
  scaffold in §2 assumes single-canvas infill.** The infill co-conditioning property
  (the ONE thing that justifies clamp over prompt) holds only WITHIN a canvas
  (grounding §1.1, `:638`). The `:defn-with-specs` scaffold is two `register!` lines +
  a `defn` + spec frame + six holes — plausibly multi-canvas. If it spills, the spec
  slots can't co-condition on the clamped frame and the mechanism degrades to
  block-AR. The spec flags this (§8.3) but as risk #3; it is actually a *gating
  measurement* that should precede the scaffold designs, and it costs one `introspect`
  call.

- **The model-invokes-a-mode headline is the least-grounded and most dispensable part —
  and it's circular.** The system already has a `missing-spec` detector (§3.2) that can
  FORCE a mode from the program graph with zero model cooperation. The
  `(mode/enter …)` sentinel needs the model to emit a learned form with no fine-tune
  (§8.6), which the spec hand-waves with "few-shot examples in always-on context." Cut
  it from the MVP entirely: system-forced modes prove the thesis without the
  bootstrapping risk.

- **What to build first is ONE mode, ONE fn, ONE canvas — not the engine.** §6's "one
  parameterized engine + convergence loop + op-axis" is correct *in principle* and
  premature *in sequencing*. The spec invokes "don't be a dumbass / no foo-v2" to justify
  generalizing, but building a 4-mode engine, a CREATE/UPSERT/RETRACT op-axis, a
  monotonicity guard, and a multi-pass scorecard BEFORE proving a single forced-spec
  infill beats arm 3 is the *over*-engineering the same memory warns against ("simple core
  over edge cases", "iterate live before hardening"). Generalize after E1 is green, not
  before.

---

## 1. The 3–5 most likely failures, ranked by probability × cost

### F1 — The gate can't run per-step, so modes degenerate to generate-then-fix, and the spec never benchmarks that baseline (HIGHEST)

**Mechanism + the constraint it collides with.** The spec's central claim is "stop early
when correct" via a per-step parse/eval gate (§4.3, §5). Two grounded facts kill the
per-step version:

1. A custom stopping criterion is only injectable on the **non-compiled** path
   (grounding §3 table, §5 item 4: keep DynamicCache so `is_compiling=False`). So
   choosing the per-step gate **forfeits `torch.compile`** (grounding §5 item 9 —
   compiled-fast and custom-stop are mutually exclusive). You pay a throughput penalty
   *just to be allowed to gate*.
2. The `:eval`/`:instrument` rungs run real SCI compilation + Malli registration + a
   probe call. The spec itself (§8.2) concedes this is likely too slow at the worker's
   step budget and retreats to "defer eval to the END of each K-step outer call."

Put (1) and (2) together and the realistic loop is the §4.5 outer loop: generate K steps
→ decode argmax canvas → parse/eval → renoise bad spans → regenerate. **That is
generate-then-fix.** The clamp pins the good scaffold; the renoise re-rolls the bad span;
the eval is post-hoc. Nothing about that requires "modes" as a per-step control surface —
it's a prompt + a post-generation repair loop.

**Why the spec under-weights it.** §8.5 ("modes may not beat plain prompting") frames the
competitor as *naked* prompting, and E1 metric B A/Bs forced-infill (arm 1) vs free
completion (arm 2). But the honest competitor is **arm 3: prompt the model normally, then
run the IDENTICAL post-hoc parse/eval/renoise loop on its output.** Both arms get the
oracle loop; the only difference under test is whether *clamping a scaffold* adds validity
over *prompting for the same shape*. The spec's parser-oracle doc already found the repair
collar's value was NULL on a capable AR model ("value is on NOISY diffusion gen") — so the
bet rides entirely on DiffusionGemma's per-forward commits being noisy enough that
clamp+renoise beats prompt+renoise. That is plausible but unproven, and **E1 as written
cannot answer it because arm 3 is missing.**

**Cost if wrong:** you build the mode engine, the scaffold compiler, the offset-map
lowering, the stopping-criterion override, the convergence loop — and a one-line prompt
plus the post-hoc oracle loop (which you're building anyway) matches it.

### F2 — Forced specs are syntactically valid but semantically vacuous, and NO ladder rung catches it (HIGH)

**Mechanism.** Clamping the `:malli/schema` frame guarantees a spec is *present*, not that
it is a faithful contract. The model fills `[:map]` (validates everything) or
`[:map [::x :any]]`. The ladder rungs each pass on garbage:

- **parse** — `[:map]` parses fine.
- **eval** — registers fine.
- **instrument** — a permissive spec never throws `invalid-input/-output`; the rung is
  *satisfied by emptiness*. The spec reads `:seon.fn/spec` set + no `:seon.fn/schema-error`
  as success (§4.3, §2.2) — both true for `[:map]`.
- **generative-test** — a too-loose spec samples permissive data, and a model writing the
  property under the same loose spec writes a property that trivially holds. Vacuous spec
  → vacuous data → vacuous assertion, all internally consistent. §8.1 hopes Stage 3
  "indirectly pressures the spec," but only if the property encodes external intent — and
  nothing forces it to.

**The deeper error:** §5 imports the 93.5% combined-catch number as if it underwrites
generation quality. It does not. That number measured **corruption detection** —
flipping a token in already-correct code and seeing if parse+eval notices. Semantic
vacuity of a *from-scratch* spec is a different population the oracle was never measured
against. The spec's strongest quantitative claim is load-bearing in the wrong place.

**Cost if wrong:** the headline "very high quality by construction" is false; you get
specced-but-meaningless fns that look green on the scorecard. Worse than no spec, because
the scorecard reports convergence.

### F3 — Canvas-length is unmeasured; the §2 scaffolds may not fit one canvas, and multi-canvas kills the infill advantage (HIGH)

**Mechanism.** Infill's bidirectional co-conditioning — the slot denoising while seeing
the clamped suffix — is the *only* thing clamp buys over prompting, and grounding §1.1
(`:638`, `max_new_canvases = ceil(max_new_tokens / canvas_length)`) confirms it is
**within one canvas**. A slot in canvas 2 cannot co-condition on a clamp in canvas 1; the
outer block-AR loop just continues left-to-right. The `:defn-with-specs` scaffold (§2.2)
is large: two `register!` lines, the `defn`, the docstring, the `:malli/schema` frame, and
six holes budgeted at ~8+96+64+8+? +160 tokens. If that exceeds `canvas_length`, the
forced-spec slot may land in a different canvas from the `defn` frame it's supposed to
match — and the mechanism silently degrades.

**Why it's worse than §8.3 says.** §8.3 lists it as risk #3 with a "keep scaffolds
canvas-sized" mitigation — but **nobody has measured `canvas_length`** (CLAUDE.md lists
"canvas-length limits" as a known unknown; the live worker has only run `generate`/first-light,
not even `clamp_smoke` yet). The entire §2 mode taxonomy is drawn assuming single-canvas
scaffolds that fit. This is a measurement that must *precede* scaffold design, not a
caveat after it.

**Secondary, unflagged hazard: clamp/slot token-boundary alignment.** The ascii canvas in
§2.2 assumes clamped chars and slot chars fall on clean token boundaries. BPE doesn't
respect `{{slot}}` markers — a clamped `::` abutting a hole, or `:=>` next to a slot, may
share a single BPE token, so `span_to_positions` (char→token, `diffgemma_common.py:211`)
can't cleanly separate clamp from slot at that seam. Unverified and load-bearing for the
clamp set's correctness.

### F4 — The model-invocation layer is circular and the weakest link, but it's on the critical path (MEDIUM)

**Mechanism.** The owner's headline is "the MODEL invokes a mode" via `(mode/enter …)`.
§8.6 admits the model won't emit it reliably without few-shot priming and no fine-tune
exists. But §1.3 *also* describes "system-forced" invocation: the program-graph
`missing-spec` detector forces `:defn-with-specs` with no model cooperation. So the
model-initiated path is **not necessary for the thesis** — it's an extra, least-grounded
behavior bolted onto a path that already works deterministically from the DB. Keeping it on
the MVP critical path imports a bootstrapping risk (few-shot tokens in always-on context,
unmeasured emit-rate) for zero thesis value.

**Cost if wrong:** modest — it's cut-able — but if not cut, it confounds E1/E5 (a mode
that doesn't fire because the sentinel wasn't emitted looks like a mode that failed).

### F5 — The refinement loop's convergence signal depends on breakage being VISIBLE, but instrumentation only fails on call (MEDIUM)

**Mechanism.** §1.4's self-healing convergence assumes a schema upsert that breaks a
downstream fn *re-surfaces* that fn in a missing-work section. But instrumentation throws
only when the fn is **called** with the new shape (CLAUDE.md "Function Instrumentation").
A schema tightened in pass N doesn't retroactively re-run pass N-1's tests; the
`last-passed-at > last-failed-at` predicate stays green on a now-stale pass. So the
scorecard reports `converged? true` while a latent break sits unexercised. §8.8 names this
as E6's kill gate but the spec's mitigation ("re-derive what a fn references from
`:seon.fn/source` at pass time") is sketched, not designed — and it requires re-running
every dependent's tests after any schema upsert, which the convergence loop doesn't
specify. The monotonicity guard (§5.3) is a count heuristic that can't distinguish
legitimate multi-fn re-convergence from oscillation.

**Cost if wrong:** false "converged" reports — the worst outcome for a system whose whole
pitch is "convergence is a measured number, not a vibe."

---

## 2. The cheapest experiment to confirm or kill each

| # | Risk | Cheapest kill-experiment | GPU? |
|---|---|---|---|
| **F1** | modes = generate-then-fix | **Add arm 3 to E1.** Three-arm A/B on the same 5 fns: (1) forced-spec infill + post-hoc oracle loop, (2) free completion, (3) **plain prompt "write the `:malli/schema`" + the IDENTICAL post-hoc parse/eval/renoise loop**. Kill modes iff arm 1 ≈ arm 3 on instrumentable-spec rate. This is the spec's E1 plus the one missing baseline. | yes (small) |
| **F2** | vacuous specs | **No engine needed.** Take the specs E1 produces (or even hand-generate 10 candidate specs), score each for *faithfulness* not just instrumentability: % that are `[:map]`/over-permissive (a static check), plus an LLM-judge or a held-out adversarial property ("does this spec REJECT an obviously-wrong input for this body?"). Report a vacuity rate. If >~30% vacuous, "quality by construction" is dead and Stage 3 must carry the quality, not Stage 2. | no (judge only) |
| **F3** | canvas overflow + token seams | **One `introspect`/`probe` call** to read `canvas_length`. Then tokenize the actual `:defn-with-specs` scaffold (offline, the worker tokenizer) and check (a) does it fit one canvas, (b) do clamp/slot boundaries fall on clean token edges. Pure arithmetic after one probe. Do this BEFORE designing any scaffold. | one probe |
| **F4** | sentinel bootstrap | **Drop it.** MVP runs system-forced only (the `missing-spec` detector triggers the mode). Defer `mode/enter` until after E1 proves the mechanism is worth invoking. Zero experiment cost — it's a scope cut. | no |
| **F5** | invisible breakage | **Defer to E6, but harden the gate first:** before trusting any "converged" report, the loop MUST re-run the generative tests of every fn whose source references a changed schema/fn (derived from `:seon.fn/source` at pass time). Test cheaply on a 3-fn ns: upsert a schema that breaks fn-C, assert fn-C re-appears as open *without* being called in normal flow. If it doesn't, the reactive-convergence premise needs explicit dirty-marking. | no (CLJS only) |

**Ordering:** F3 (one probe, gates everything) → F1+F2 (the same E1 run produces the
artifacts both need; run arm 3 and score vacuity in one pass) → F4 (free, just don't build
it) → F5 (only if E1 passes and you proceed to multi-pass).

---

## 3. What to CUT or DEFER — the minimal viable version

**The MVP that proves or kills the core thesis is ONE mode, ONE fn, ONE canvas:**
`:defn-with-specs`, system-forced on a single unspecced fn whose body is given, in a
scaffold verified to fit one canvas, A/B'd against prompt+oracle (arm 3). Everything below
is built on the assumption E1 passes; building it first inverts the risk order.

**Cut from the MVP (defer until after E1 is green):**

- **The `mode/enter` sentinel + model-initiated invocation (§1.3).** Circular,
  unbootstrapped, dispensable (F4). System-forced only.
- **The op-axis `:create/:upsert/:retract` (§2, §6) and the whole multi-pass
  convergence loop (§1.4, §5.3, E6).** This is a large, elegant superstructure on an
  unproven primitive. UPSERT-seeded-with-existing-source, the monotonicity guard, the
  scorecard trend line — none of it can be validated before one forced infill works.
- **`:design-schema`'s generate→sample→adapt loop (§2.1, E2).** Its premise ("seeing
  generated data tightens the next schema") is an explicit HYPOTHESIS with no evidence
  and an entirely separate kill-gate. Not on the critical path to the Stage-2 thesis.
- **`:generative-test` and `:repl-explore` as modes (§2.3, §2.4).** Stage 4 is "the normal
  agent loop with an empty scaffold" — i.e. not a mode at all; §6 admits the engine
  "degenerates." Don't build a mode to do nothing. Stage 3 matters for F2 but as a *quality
  check on Stage 2's output*, not as a fifth piece of machinery to stand up first.
- **The §6 "one engine vs per-stage" debate.** Moot with one mode. You cannot prove "one
  engine subsumes four" before you have one. Premature generalization, ironically the
  exact "build the abstraction before the instance" the doc argues against elsewhere.

**Keep regardless of E1 (cheap, independently useful):** the dynamic-context section-fns
(§3) — `namespace-state`, `missing-spec-target`, `related-fns-and-schemas`. These are pure
reactive queries, they help a *prompted* model too (they're arm 3's context), and the spec
itself says keep them if the clamp half dies. They are the safe half of the bet.

**Net:** MVP = `missing-spec-target` (already grounded) + ONE `:defn-with-specs` scaffold
(canvas-verified) + the post-hoc parse/eval/renoise loop + the three-arm E1. ~1 mode, ~1
gym predicate, no convergence loop, no op-axis, no sentinel.

---

## 4. What the design got RIGHT (brief)

- **Lowering onto proven/source-grounded primitives is honest.** Clamp (`:1034`), infill
  (`gpu_worker.py:335`), `span_to_positions`, the parser oracle, the eval cage — every
  mechanism cites a real artifact, and the "infill is mechanically proven, content can be
  wrong → that's the loop's job" framing (TL;DR) is exactly the right read of the live
  evidence.
- **The regime-change trap is correctly identified and correctly handled** (§4.5 caveat):
  keep `max_denoising_steps=N`, stop externally, never shrink the cap — matches grounding
  §3's temperature-compression finding precisely. This is the kind of footgun a weaker
  spec would have stepped on.
- **E1-as-front-kill-gate is the right instinct** — gate the riskiest assumption before
  building the engine. The fix is only that E1 is missing its real baseline (arm 3) and a
  vacuity metric.
- **Reactive section-fns as the context strategy** is sound, cheap, architecture-consistent,
  and the safe half of the bet — keep it whatever happens to the clamp half.
- **Modes-as-data / overridable rows** is the correct architectural shape *if* modes earn
  their existence — the discipline is right, only the sequencing (generalize after the
  instance) is wrong.

**Bottom line:** the spec's own §8 names most of the right risks but softens the two that
matter most — it treats the latency constraint as a tuning problem (F1) when it actually
dictates that modes must beat *prompt+oracle*, not prompt-alone; and it treats vacuous
specs as "the E1 kill-gate" (F2) when no ladder rung catches them and the 93.5% number
doesn't apply. Run the canvas probe, then run a three-arm E1 that scores faithfulness, and
let those two numbers — not the engine — decide whether any of §1.4–§6 gets built.
