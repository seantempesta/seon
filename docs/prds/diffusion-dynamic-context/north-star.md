---
type: vision
status: active
tags: [vision, diffusion, agent]
---

# North star — context as an empirically-tuned, test-gated artifact

> The dream, stated plainly: **every piece of an agent's context earns its place by
> measured lift on generation correctness.** Skills, namespace code, context
> sections — each is A/B-tested against the diffusion model and kept, refined, or
> cut based on whether it makes generation *more correct*. And the verified canvas makes
> this LIVE: **the same oracle that gates context BEFORE generation steers and
> TERMINATES it DURING** — renoising the spans it proves wrong between denoise
> steps and stopping the moment the code is proven correct (parses + runs +
> returns the right answer), phase by phase, not when the model feels confident.
> The agent reliably writes correct, fully-spec'd, map-in/map-out Seon code
> because its context is **empirically optimal** AND its generation is **held to
> a ground-truth oracle** end to end.

## ▸ OWNER HANDOFF — where the loop left it (operational state lives in [[roadmap]])

The autonomous loop ran the no-GPU build/research surface to the floor — every
buildable half is BUILT, offline-proven with the real bb+node oracles, and wired
on-worker. **The single operational we-are-here is [[roadmap]] "▸ WE ARE HERE"; the
ordered GPU session is [[owner-gpu-runbook]].** This section keeps only the proven
headline + what superseded the older handoff inventory.

**PROVEN (fingerprint-verified, committed):** context lifts generation **0→100% on
6/6 skills** (the deep finding: context overrides confident-wrong priors); the canvas control
primitives (clamp, infill, spec-slot, denoise_to_step, resume_renoise); the
**short-circuit closed loop** (denoise→K → local oracle → stop, ~67% steps saved);
the **full validation ladder** (T0 parse → T1 structural lint → phase grammar → T2
eval → T3 behavioral) with **validation-as-early-stop** as the loop's termination
criterion; **prefill = 62% of latency at 9k ctx** (KV-caching is essential, exact
full-prefix caching is feasible — encoder is causal). torchao INT8 = dead end (MoE
experts skipped). And the E1 kill-gate run: guided generation enforces STRUCTURE
(parse/structural 1.0 vs naked, live-oracle-scored), but its behavioral zeros
were **VOIDED — a proven dead-eval-bundle defect**
([[research/e1-behavioral-zero-audit-2026-07-02]]); the harness is fixed
(fail-loud oracle-liveness gate, sample persistence) and the ~$0.50 re-run is
queued after exp D ([[roadmap]] P1). The lesson is the vision restated: **the
oracle must prove its own liveness before its verdict counts.**

**Superseded from earlier handoffs (so this file stops contradicting itself):**
the "run the batched_mm probe FIRST" advice — it RAN (clears find_spec, then a
CUDA device-side assert; the assert is now root-cause-hypothesized as
static-cache under-sizing with a $0 payload probe, and the compiled path itself
was never actually measured — [[research/compile-control-ceiling-2026-07-02]];
exp D is first); the
"eval tier needs rebuild/redeploy" gap — the tier was revived and is proven as the
ladder's T2; the "pivot the sweep to eval-tier next" queue item — done and
exceeded: the whole ladder is built, T3 behavioral included. The 17-item
built-inventory this section used to carry is landed history — the durable record
is the commits + [[roadmap]] "PROVEN".

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
async, parser-explanation, data-mindset, UI). The pivot this called for HAPPENED:
the eval tier and the full ladder above it (T3 behavioral — does it give the RIGHT
answer?) are built and are the `refine_loop` gate; the eval-renoise loop is closed
on-worker. Still open in the ledger: the required-API render's lift (must justify
its ~2.9k tok or trim the cap) and the ladder's own measured lift on GPU
([[roadmap]] "The GPU-measurement path").

### Capability — the verified-canvas eval-renoise loop (PRIMITIVES PROVEN, GPU-verified)

Round-trip test on the live worker (`33d2`, fingerprint-verified):
- **`denoise_to_step` fires PRECISELY at K** (`denoise_steps_fired: 24`). The
  `StepCountStopping` ABC override works — stops at K with the temp schedule intact.
- **At K=24 the `mean` fn is ALREADY CORRECT** (`(defn mean [v] (/ (reduce + v)
  (count v)))`) — the model converges in ~half the step budget. So the **short-circuit
  is the dominant win**: parse/eval at K, see it's clean, STOP → ~2× faster AND verified.
  (K=8 was still noise — confirms "wait for later steps"; K~24 is the sweet spot here.)
- **`resume_renoise` mechanism works** (`good_held: true` — the clamp holds the good
  positions while the span re-denoises). BUT the test re-noised an already-correct span
  and REGRESSED it (`defn`→`def`). **LESSON: renoise must be ORACLE-DRIVEN — only
  re-noise spans `parse-forms`/eval flags as wrong; never a correct span.**
- **The loop's real shape:** denoise→K → parse/eval the partial → if clean SHORT-CIRCUIT
  (stop, the common case) → else renoise ONLY the flagged char-spans → resume. **Next
  build: the Seon-side orchestration** (parse the partial → map error `:span` → decide
  stop/renoise) that turns the two proven primitives into the closed loop.
- **CLOSED LOOP DEMONSTRATED end-to-end (GPU denoise + LOCAL bb-oracle):** denoise_to_step at
  K → `bin/oracle-server` parses the partial locally → SHORT-CIRCUIT when clean. At K=16 the
  `mean` fn parsed clean → stop, ~67% steps saved. **KEY insight it surfaced:** K=20/24 produced
  `(def mean [nums] …)` — PARSES clean but is semantically WRONG (`def` not `defn`). The
  parse-tier alone CAN'T gate correctness; the **eval-tier (cljs.js, in build) is the needed
  semantic gate**. (Local oracle ran 25-27ms here = bb subprocess cold-start per call; a
  persistent server is 0.05ms — the demo spawned fresh each call.) Renoise path not yet exercised
  (all partials parsed clean — needs a delimiter-error case).

**MEASURED SPEED REALITY — since RE-ATTRIBUTED
([[research/compile-control-ceiling-2026-07-02]]):** the `mean`-fn delta (full
48-step run `gen_s 0.57s` vs `denoise_to_step` K=24 `gen_s 2.32s`) was originally
recorded as "custom stopping forfeits `torch.compile` → ~4× compile tax". That
attribution is WRONG: `is_compiling` gates purely on a static cache, which wasn't
working then — BOTH runs were eager, and the delta is FORWARD-COUNT (the built-in
early-stop converged in ~4 forwards ≈ 0.57s at ~140 ms/forward; forcing K=24 paid
24). The compiled path was never actually measured. What stands: fewer forwards
IS the speed lever (early-stop / over-commit), `denoise_to_step`/`resume_renoise`
are for CORRECTNESS, and only a custom Python StoppingCriteria conflicts with
compile — the per-step clamp is compile-COMPATIBLE. Validation itself is cheap
(parse-forms = **366 µs** vs a ~140 ms forward) — the cost was the internet hop
(~100 ms), which co-location removes. Architecture split unchanged: **built-in
early-stop / oracle-proof termination for speed + co-located validator for
correctness (sparse renoise on oracle-flagged spans only).**

**CO-LOCATION VALIDATOR BUILT + measured (`18c600f5`):** a 50KB standalone CLJS bundle
(`:worker-validator` shadow target, `src/seon/worker_validator.cljs`) runs `parse-forms`
LOCALLY at **0.065-0.115ms** warm (persistent `--serve` line-server; a fresh subprocess
is ~100ms so the hot loop MUST reuse one process). Lean deps (rewrite-clj only — no
datahike/malli/pod). Its `{:error-kind, :span [s e], :source}` output feeds straight into
the worker's `span_to_positions`. So **on-worker validation adds ~0.1ms/checkpoint
(negligible vs a ~12ms step)** → the per-step adaptive correctness loop is viable once
this bundles onto the worker image. The eval-tier (does-it-RUN) is a separate build seam
(keeps `cljs.js` out of this lean bundle). Remaining speed blocker is unchanged: Python
control forfeits torch.compile — validation latency is now SOLVED, generation-control
compatibility is the open one.

### The deep finding (5/5 skills) — context OVERRIDES confident-wrong priors

In EVERY skill the control fails the SAME way: it hallucinates a confident,
plausible-but-wrong answer the model already "knows" — JSON/XML for "parser," the
*commercial* Seon fraud-detection SaaS for data-modeling, a fake query DSL, an
`async` binding form. The skill's job is **not adding facts — it's overriding the
model's strong wrong priors** and redirecting them to Seon's actual reality. This is
*why* dynamic context is load-bearing for THIS model: a small, capable model has
confident defaults, and the right context is what reroutes them. (Implication for
the verified canvas: the per-step parse/eval/renoise control signal is doing the same job
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
- **Reliable capabilities**: the verified-canvas loop (denoise → parse/eval → renoise) and the
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

## Architecture decisions (settled tonight, source-grounded)

- **Engine: stay on raw transformers INDEFINITELY as the control backend** (`pytorch-vs-vllm-roadmap`).
  vLLM's diffusion sampler is sealed, AND its custom-logits-processor API has the WRONG SHAPE —
  `apply(logits: (num_requests, vocab_size))`, **no canvas/position axis**; the verified canvas needs
  `(req, canvas_len, vocab)`. vLLM physically can't address canvas positions. And on A100 **BF16**
  vLLM = **375 tok/s** vs our compiled **~450** — vLLM's win is **FP8+Hopper, not the engine**.
  vLLM = a SERVING-only second endpoint, gated on 3 triggers (thesis-cleared + serving-scale-bound +
  Hopper-FP8). Forking the sampler = PARTIAL (pure-Python+compiled, no CUDA → a compile-compatible
  clamp+tensor-stop is ~1 eng-week → fast CONSTRAINED serving, but the eval-renoise loop CANNOT be
  forked in; + TP>1 crash #45719).
- **KV/prefix caching split (source-confirmed):** vLLM's automatic prefix caching IS ENABLED for
  DiffusionGemma (`diffusion_gemma.py` causal encoder writes KV; APC on) → it gives the shared-skill
  prefix-cache win FOR FREE, but **serving-only (no control)**. So: **serving-without-control → vLLM APC**
  (likely nets ahead on our repeated-context workload despite 375<450 raw BF16); **control+caching (the
  verified-canvas path) → CUSTOM KV caching on transformers** (the `kv-section-caching-design` work) — the only
  way to get both the caching win AND per-step control. **GREEN LIGHT (source-proven):** the prompt
  encoder is CAUSAL (incremental KV append, `modeling_diffusion_gemma.py:281`), so **exact full-prefix
  caching is feasible with ZERO accuracy loss** — the position-dependence worry doesn't apply to the
  prompt prefix. Seon is near-ideal: `default-seed-blocks` orders ctx static→volatile (head = "the
  cacheable prefix"); each skill's ~2400-tok KV encoded ONCE, reused across N tasks (vLLM chain-hash,
  salted per `:seon.agent/id`). Per-block varying-position reuse = NOT needed (fixed order) + not clean
  (MoE/mixed-RoPE) → deferred. HARD GATE: encoder `Cache` can't ride JSON → needs the CO-LOCATION image
  (caching + co-location = one build). **Phase 0 MEASURED → STRONG GREEN:** a single skill prefix
  (4531 tok) prefill = **0.945s = 40.7% of generate() latency** (short 36-tok prompt gen_s 1.38s vs
  skill-laden 2.32s, fingerprint-verified). Caching the stable prefix saves ~40%/gen when reused. **Phase 0b
  (scaling) makes it airtight — prefill fraction CLIMBS with context:** 36-tok 0% → 4.5k-tok 35% →
  **9k-tok 62%** (gen_s 1.32 → 2.03 → 3.49s). At a realistic multi-block context (~9k) prefill DOMINATES
  → caching the stable prefix ≈ **2.6× faster/gen at scale**. The more we lean into dynamic context, the
  more decisive caching is. Build is gated on the co-location image (Cache can't serialize over JSON) →
  co-location is now a TOP-priority enabler (gates caching AND the per-step renoise loop).
- **A100 speed = ADOPT in-the-loop features, reinvent NOTHING** (all control-compatible, keep the
  `:1034` seam): (1) **torchao quantization — RESEARCHED, DEAD END for this model** (`8c4402bf`): it's
  control-compatible (only swaps `nn.Linear` weights, doesn't touch `:1034`) BUT DiffusionGemma's MoE
  experts are fused 3D `nn.Parameter`s, and the transformers quantizer converts ONLY `nn.Linear` → the
  experts (bulk of a 26B-A4B MoE) are SKIPPED → negligible speedup. Not a lever via the simple path
  (the torchao prototype MoE-3D path is unproven/gated). Test-to-confirm: `vram_alloc` BF16 ~50GB vs
  int8dq — if it only drops to ~44-46GB the experts were skipped. (2) `static`-cache **`torch.compile`** — **#7 RESEARCH (`5f0297d3`): REAL + the model is ENGINEERED for it**
  (opposite of torchao): `generate()` compiles encoder/decoder/accept/renoise/built-in-stop (`fullgraph`);
  the MoE does NOT break it (default `grouped_mm` is the graph-capturable kernel — the fused experts torchao
  skipped are exactly what it consumes). Switch = `cache_implementation="static"`. **CRITICAL: the live
  worker loads Dynamic cache = EAGER, so the "~450 tok/s" above is EAGER — the COMPILED path is UNTESTED.**
  Web band 2.5-3.8× → potentially **~1000 tok/s on the A100 WITH control** (the built-in stop compiles; the
  Python parse/eval stop stays eager for the correctness experiments). **#8 TESTED → naive wiring ERRORS:**
  `cache_implementation="static"` alone → `RuntimeError: upper/lower bound inconsistent with step sign`
  (warmup) + `AttributeError: StaticSlidingWindowLayer has no max_batch_size` (steady). So "the switch is
  just the cache" was too simple — DiffusionGemma's SLIDING-WINDOW static cache needs explicit init + a
  compiled-loop range bug. **ROOT-CAUSED + FIX TESTED (1a475ce9):** the static-cache errors were MY args
  bug — passing `max_new_tokens` WITH `max_length` drops max_length (`:880` gate honors it only at
  `max_new_tokens==256`) → negative cache. Fix (omit `max_new_tokens`, only `max_length`) CLEARED those.
  **BUT a DEEPER 2nd blocker then surfaced: a Dynamo graph-break** — `Unsupported: find_spec in
  <frozen importlib.util>` under `fullgraph=True` (a lazy importlib lookup in the compiled region
  torch.compile won't trace). So the ~1000 tok/s compiled path has TWO blockers (args [fixed] + the
  find_spec graph-break [deep, uncertain]) → **NOT a quick win → owner/upstream torch.compile-compat
  territory.** KV-cache (62%) is the primary remaining A100 speed lever. *(Since ROOT-CAUSED —
  the find_spec break is a 2-line worker-side monkeypatch, the shipped `assume_constant_result`
  patch being inert under Dynamo's lru_cache unwrap; the device-assert has a $0
  static-cache-sizing probe; the compiled path was never actually measured.
  [[research/compile-control-ceiling-2026-07-02]].)* (3) **`past_key_values` / `QuantizedCache` reuse** (transformers-native
  prefix/skill KV reuse, composes with the per-block `kv-section-caching` design); (4) **Fast-dLLM
  DualCache** (NVIDIA, training-free in-loop suffix-KV, control-compatible) for extra headroom. The control
  worker's speed comes from COMPOSING these, not a custom engine. (`pytorch-vs-vllm-roadmap` §5b.)
- **Co-located oracle runtime = persistent Node sidecar, NOT GraalVM** (`colocated-oracle-package-design`).
  GraalVM = nothing to revive (only a wishlist box), wrong language (oracle is CLJS; polyglot runs JVM
  Clojure → banned parallel reimpl), AND repo-proven crash risk (Substrate-VM signal fights → SIGSEGV
  in-process with PyTorch → ~66s reload). IPC tax (~50-100µs) is noise vs the ~100ms hop killed. Shape:
  `op`-dispatched (`parse` / `eval` / `retrieve`-stub), identical JSON-line API across runtimes.
  **TIER SPLIT (resolved): parse-tier → BABASHKA** — parse-forms is purely STRUCTURAL (rewrite-clj, no
  semantics) so bb parses CLJS-flavored canvas forms BIT-IDENTICALLY to the pod (zero fidelity loss),
  simplest deploy (native binary + `.cljc`, no build, ~0.1ms warm); **bb SUPERSEDES the Node
  `:worker-validator` for the parse-only hot loop**. **eval-tier → Node/cljs.js** self-host (the only
  true-CLJS eval; bb-SCI/GraalVM eval Clojure → false-negatives on `^:async`/interop). Python `Oracle`
  unchanged when eval migrates (same API). **PARSE-TIER BUILT + offline-proven (`45e801d6`):**
  `bin/oracle-server` (bb persistent line-server) measured **~0.05ms warm / ~21ms cold-spawn** (p99
  0.24ms), byte-identical span contract to the Node validator, no shadow build. The worker spawns it once
  (`Oracle(["bb", "bin/oracle-server"])`) → sub-ms local validation per checkpoint. **EVAL-TIER BUILT +
  offline-proven (`310f8652`):** `seon.worker-eval` (cljs.js self-host, `:worker-oracle-eval` target) =
  the CORRECTNESS gate ("does it RUN?") — compiles the form so it CATCHES `(def mean [nums] …)` /
  undeclared-var (the def-vs-defn parse missed); `^:async`/interop compile clean (why cljs.js not bb-SCI);
  non-termination fenced by V8 `vm` timeout. Warm **~2.6ms/call**, cold ~276ms (one-time). Same `{op,…}`
  contract → split confirmed: **parse-per-step (bb 0.05ms), eval-at-checkpoint (cljs.js 2.6ms)**. The
  co-located oracle is now COMPLETE. Remaining for full co-location: image bundling + worker spawn wiring
  (the `co-location-image-build` doc) + the in-process KV cache.

## Pointers

- [[architecture]] — the verified-canvas system + the modes this serves.
- [[roadmap]] — the kill-gate-first build path (this loop executes its NEXT items).
- [[grounding]] — every mechanism cited to `reference-code/`.
- The proven A/B: the `data-modeling` skill, 0→100% (the loop's existence proof).
