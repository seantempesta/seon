---
type: vision
status: active
tags: [vision, diffusion, agent]
---

# North star — context as an empirically-tuned, test-gated artifact

> The dream, stated plainly: **every piece of an agent's context earns its place by
> measured lift on generation correctness.** Skills, namespace code, context
> sections — each is A/B-tested against the diffusion model and kept, refined, or
> cut based on whether it makes generation *more correct*. The agent reliably
> writes correct, fully-spec'd, map-in/map-out Seon code because its context is not
> guessed — it is **empirically optimal**.

## Why this is now possible (the unlock)

The diffusion model (DiffusionGemma, ~250-500 tok/s warm on the A100) is a **cheap,
fast generation oracle**. One A/B — control prompt vs prompt-with-the-artifact, N
samples each, scored through Seon's real `parse-forms` + a structural check — is
**~16 generations in ~80s warm (~$0.03)**. That means we can afford to test the
contribution of *every* context artifact, systematically, and let data — not taste
— decide what an agent sees.

**The proof it works:** the `data-modeling` skill A/B took generation from **0/8
correct (62% hallucinating fake APIs) → 8/8 correct** on every dimension (real
`schema/register!`, namespaced keys, `:malli/schema`, map-in/map-out). The right
context is the difference between garbage and correct code. That single result is
the whole thesis.

## The systematic refinement loop

For each context component (a skill, a namespace's code, a context section-fn):

1. **Pick a representative task** in the component's domain (e.g. for `datahike`: "write
   a query that finds all sources rated ≥ 4").
2. **A/B it:** control = task alone; treatment = the artifact + task. N samples each.
3. **Score through the real oracle** (`parse-forms` syntactic + structural domain check
   + where cheap, eval). Compute the **lift** (treatment − control).
4. **Act on the lift, durably:**
   - Big lift → keep; lock it with the A/B as a regression gate.
   - Small/zero lift → the artifact isn't pulling weight: **refine it** (tighter, more
     worked examples, the real API) and re-test.
   - Negative lift → cut it.
5. **Commit** the refined artifact + record the lift. The improvement is durable code,
   not a chat insight.

Repeat across all components → a context where **every section is there because it
measurably improves generation**, and capabilities (clamp / infill / eval-renoise /
modes) are **test-gated and reliable**.

## Ledger — measured lifts (live, growing each cycle)

Each row: an A/B of a context artifact, control vs treatment, N=8/arm, scored through
`parse-forms` + a domain-structural check. "Lift" = treatment − control on the headline
correctness metric.

| Artifact | Task domain | Control | Treatment | Verdict |
|---|---|---|---|---|
| `data-modeling` skill | write a spec'd fn (map-in/map-out) | 0/8 correct, 62% hallucinated | **8/8** correct | KEEP — huge lift; lock as gate |
| `datahike` skill | write a Datalog query | 0/8 real-API, 62% hallucinated | **8/8** real Datalog | KEEP — huge lift; lock as gate |
| `clojurescript` skill | write a pod `^:async`/`await` fn | 0/8 `^:async`, 0/8 real-API, 7/8 hallucinated | **8/8** `^:async` + real `transact!` + interop | KEEP — huge lift; the model gets `await` unaided but NOT the `^:async` wrapper/real-API |
| `repl` skill | EXPLAIN a parser error (prose) | 0/8 real-parser, **8/8 hallucinated JSON/XML** | 7/8 real `parse-forms`/parinfer, 0/8 hallucinated | KEEP — huge lift; works for PROSE/explanation too, not just code-gen |
| `data-oriented-clojure` skill | design session-history storage (mindset) | 1/8 EAV, 0/8 namespaced, **8/8 hallucinated commercial-Seon SaaS** | 8/8 EAV + namespaced, 1/8 hallucinated | KEEP — huge lift; redirects the commercial-Seon prior to our EAV model |
| `seon.*` required-API render (feat `844ec448`) | (context section, not a skill) | — | shipped, ~2.9k tok/turn | **LIFT UNMEASURED** — must A/B to justify the token cost or trim the cap |

| `ui-live-tiles` skill | render a live todos tile | 0/8 real-render-ns, 0/8 namespaced, 7/8 hallucinated tile-map | 8/8 `:seon.render` + namespaced + `:malli/schema` | KEEP — huge lift |

**▸ SWEEP COMPLETE — 6/6 skills, ALL ~0→100% structural.** The north-star's
structural thesis is proven: context takes this model from ~0% correct (confidently
hallucinating) to ~100% structural correctness, in every domain (schema, queries,
async, parser-explanation, data-mindset, UI). **Next: the structural ceiling is hit —
pivot to (1) the EVAL-TIER oracle (does the generated code RUN / give the right
answer?), (2) closing the eval-renoise buzzsaw loop, (3) measuring the required-API
feature's lift to justify its 2.9k tok.**

### The deep finding (5/5 skills) — context OVERRIDES confident-wrong priors

In EVERY skill the control fails the SAME way: it hallucinates a confident,
plausible-but-wrong answer the model already "knows" — JSON/XML for "parser," the
*commercial* Seon fraud-detection SaaS for data-modeling, a fake query DSL, an
`async` binding form. The skill's job is **not adding facts — it's overriding the
model's strong wrong priors** and redirecting them to Seon's actual reality. This is
*why* dynamic context is load-bearing for THIS model: a small, capable model has
confident defaults, and the right context is what reroutes them. (Implication for
the buzzsaw: the per-step parse/eval/renoise control signal is doing the same job
*during* generation that the skill does *before* it — overriding a wrong commit.)

**Read of the data so far (3/3 skills, all ~0→100%):** without context the model knows
~none of Seon's API (it hallucinates a plausible DSL); a good skill takes it to 100%
STRUCTURAL correctness. The `clojurescript` row is the most informative: the skill's
value is the NON-OBVIOUS parts (the `^:async` meta that makes `await` valid, the real
`transact!`, correct interop) — the model already reaches for the obvious token
(`await`). So structural lift is near-maxed by any skill that teaches the real API —
the next discriminator is **semantic/eval correctness** (does it actually RUN and
return the right answer), which needs the eval-tier oracle, not just `parse-forms`.
That is where skill-refinement will start to separate good from great → **next sharper
move: pivot the sweep from structural to eval-tier** once the 6-skill structural
baseline is complete.

**Scoring method note (cycle 2 lesson):** prefer SUBSTRING checks over clever regex for
API-presence (a `\b` after `transact!`'s `!` false-zeroed a real 8/8). Verify any
surprising metric against the raw sample before recording it — falsify, don't confirm.

## How cheap can the tests be? (the economics that make this work)

- One skill A/B: ~16 gens, ~80s warm, ~$0.03. The whole 6-skill suite: ~$0.20, ~10 min.
- Scale-to-zero (`workers=(0,1)`) → $0 between batches; each batch pays one ~66s cold
  start. For an overnight systematic sweep that's the right trade (not time-critical).
- The scoring is FREE (Seon's parser is microseconds; eval is low-ms; both local).
- So the binding constraint is not money or the GPU — it's **having a good task +
  scorer per component.** Building that library of (task, scorer) pairs IS the work.

## The end state we're building toward

- A **context regression suite**: every skill/section has an A/B that asserts its lift;
  CI-style, a change that drops generation quality fails the gate.
- **Self-justifying context**: nothing renders into an agent's prompt that hasn't earned
  it on the measured-lift ledger (this is the reactive-context principle made empirical).
- **Reliable capabilities**: the buzzsaw loop (denoise → parse/eval → renoise) and the
  modes are gated on passing their kill-experiments, not on hope.
- The agent **builds software in convergent, quality-gated passes** ([[architecture]],
  [[roadmap]]) — and we trust it because each rung is measured.

## Overnight / autonomous operating procedure (the loop reads THIS)

When iterating autonomously (e.g. overnight), each cycle:

0. **On resume from a compaction or a fresh session — DO NOT immediately start working.**
   First READ (this file, the [[roadmap]] ▸we-are-here, the latest ledger entries, the
   last few commits, the in-flight agents/batches) and REFLECT: is the plan still right?
   what did the last results actually teach? is there a sharper next move than what's
   queued? Improve the plan/queue/docs FIRST, then re-enter the work. A few minutes of
   reading + reflection beats charging back into the fray with stale assumptions.

1. **Harvest:** read what the last batch/agents produced; COMMIT any durable progress
   (skill edits, namespace code, context sections, doc updates) with explicit pathspecs.
2. **Assess productivity (the kill-switch):** am I still learning / lifting numbers /
   shipping durable improvements? If YES → continue. If I'm repeating, stuck, or
   producing low-value churn → **`flash undeploy diffgemma --force` (shut down the
   A100) and STOP the loop**, leaving a summary + memory handoff.
3. **Pick the next durable unit** from the queue below (highest measured-value first).
4. **Execute:** run the GPU A/B (cheap), and/or launch ONE focused refinement agent.
   Always `verify_fresh.py` before trusting a number. Keep the GPU on `(0,1)`
   scale-to-zero — do NOT set `(1,1)` overnight (it bills continuously).
5. **Update the docs** (this file's ledger + roadmap) with the measured result.
6. **Schedule the next wake-up** (~20-30 min fallback; agent/batch completions wake
   sooner). When the queue is dry or productivity stops → step 2's shutdown.

### Work queue (durable, test-gated — reorder by measured value)

- **Skill-lift sweep:** A/B each existing skill (`datahike`, `clojurescript`, `repl`,
  `ui-live-tiles`, `data-oriented-clojure`) for generation lift; refine the laggards;
  re-test. (`data-modeling` = done, 0→100%.) Build the (task, scorer) pair per skill.
- **Close the eval-renoise loop:** `resume_renoise` (clamp good / re-noise garbled
  spans) + tune K (~24-32, not 8 — the canvas is still noise early). Gate: the loop
  takes a `def`→`defn` / `length`→`count` miss to a correct form.
- **Three-arm kill-gate** (the critique's #1): guided-infill vs naked prompt vs
  prompt+post-hoc-oracle, scored on FAITHFULNESS not just shape. Decides whether
  guided generation beats prompt+fix.
- **Context-section lift:** A/B the namespace-render, the required-API render
  (agent a88b157), the missing-spec section — does each lift generation?
- **New skills where gaps appear** (e.g. `function-specs`, `generative-testing`),
  each shipped with its A/B gate.
- **Keep the docs** (architecture/roadmap/grounding/this ledger) current each cycle.

## Pointers

- [[architecture]] — the buzzsaw system + the modes this serves.
- [[roadmap]] — the kill-gate-first build path (this loop executes its NEXT items).
- [[grounding]] — every mechanism cited to `reference-code/`.
- The proven A/B: the `data-modeling` skill, 0→100% (the loop's existence proof).
