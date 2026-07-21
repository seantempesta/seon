---
type: orchestrator
status: active
tags: [orchestrator, diffusion, agent, vision]
---

# Transfer prompt — diffusion dynamic-context (buzzsaw), 2026-07-02

> Paste the block below into a fresh session to continue this work. It is a
> self-contained handoff: what shipped, the vision to align docs to, and the
> open research. Everything cited is committed on `feature/agent-fsm`.

---

## ▸ THE TRANSFER PROMPT (give this to the next agent)

You are picking up the **diffusion dynamic-context** project (the "buzzsaw"):
DiffusionGemma (26B-A4B MoE block-diffusion LLM) used as a *live-context*
interface where Seon's own parser / eval / retrieval act as a **control signal
that steers generation between denoise steps** — and, newly, as the **termination
signal** (stop when the code is *proven* correct, not when the model feels
confident).

**Before doing anything, READ (in order):**

1. `docs/prds/diffusion-dynamic-context/CLAUDE.md` — the always-loaded index +
   runbook + "▸ CONTINUATION (2026-06-30)" block (current state, the validation
   ladder, the speed levers).
2. `docs/prds/diffusion-dynamic-context/north-star.md` — the vision: *context as
   an empirically-tuned, test-gated artifact*; the "▸ OWNER HANDOFF" ledger.
3. `docs/prds/diffusion-dynamic-context/colocation-performance-plan.md` — the
   speed design + the all-prep-offline GPU test plan (A/B/C/D; run D first).
4. The two research docs: `research/fastest-tok-per-dollar-hardware-2026-06-30.md`
   and `research/forward-speedup-levers-2026-06-30.md`.
5. The memory `project-diffusion-overnight-loop-2026-06-28` (auto-loaded).

Then **reflect before acting** (north-star §"operating procedure" step 0): is the
plan still right? what did the last results teach? Improve the plan/docs FIRST.

### What has SHIPPED (committed, suite green 876/4043, proven with REAL oracles)

The buzzsaw's control oracle is now a full **validation ladder**, each tier
mechanism-proven against the real bb-parse + node-cljs.js-eval servers:

| tier | catches | where |
|---|---|---|
| **T0 parse** (0.05ms) | not well-formed — delimiters | bb `parse-raw` / `seon.repl.internal` |
| **T1 structural lint** | wrong SHAPE — `(def mean [v] …)` (def-vs-defn) | `seon.diffusion.grammar/malformed-def?` |
| **phase-grammar gate** | form not allowed *yet* in phase (schemas→functions) | `seon.diffusion.grammar/phase-violation?` |
| **T2 eval** (2.6ms) | won't RUN — undeclared var, arity | `seon.worker-eval` node bundle |
| **T3 behavioral** | runs but WRONG — off-by-one mean | `gpu_worker.py refine_loop` + eval bundle |
| retrieval | hallucinated symbol → inject real API | `seon.diffusion.retrieval` |

- **Validation-as-early-stop** — the `refine_loop` (`tmp/flash-diffgemma/gpu_worker.py`)
  terminates when the ORACLE validates (parse→eval→behavioral), NOT on
  entropy/step-count. `eval_gate` + `behavioral` payload flags. The literal
  "who-cares-about-probability" gate: stop the instant the fn returns the RIGHT
  answer. Offline-proven: `tmp/flash-diffgemma/eval_gate_earlystop_proof.py` (6 cases).
- **Phased grammar gate** — the owner's schemas-only→functions-only idea: lock
  data-modeling to `register!` first (reject def/defn), then unlock `defn`.
- **#51 shared `seon.diffusion.grammar.cljc`** — the T1/phase predicates live in a
  dependency-free `.cljc` that BOTH the pod oracle AND babashka load, so bb
  `op:"refine"` folds structural+phase natively (the worker reaches the cheap
  ladder mid-denoise, no drift, no pod round-trip). Live-proven on bb.
- **Hardware verdict (#48):** FP8-on-the-MoE-experts is **Hopper-only** → L40S/A6000
  DEAD; **A100-80 BF16 is the cheapest card that runs the whole model with the
  control seam**; **TPU-JAX DiffusionGemma EXISTS** (`google-deepmind/gemma/gemma/diffusion`,
  has `_early_stopping.py`) — port-light, the high-ceiling tok/$ bet.
- **Forward-speedup verdict (#49):** the forward is **MoE-bound (~85-92%)**; every
  fast grouped-expert kernel is Hopper-gated, so a custom Triton MoE kernel is the
  WRONG A100 investment. The real free lever is **§3 tokens-per-forward × free
  renoise** (crank `entropy_bound`, let the free oracle un-commit the wrong spans).

### The VISION to align all docs to

**Every piece of an agent's context earns its place by measured lift on generation
correctness** — skills, namespace code, context sections are A/B-tested against the
diffusion model and kept/refined/cut on data, not taste. The buzzsaw makes this
*live*: the same parse/eval/retrieve oracle that gates context *before* generation
also steers + terminates it *during* generation. The agent writes correct,
fully-spec'd, map-in/map-out Seon code because (a) its context is empirically
optimal and (b) generation is held to a ground-truth oracle, phase by phase, until
it PARSES + RUNS + RETURNS THE RIGHT ANSWER. Probability is irrelevant once we have
proof. Speed comes not from a faster kernel but from *over-committing tokens and
letting the free co-located oracle renoise only the wrong spans* — a move an
autoregressive model structurally cannot make.

### TASK 1 — align ALL docs to this vision (do this first, it's mostly writing)

Bring these into one coherent, present-tense picture. Design docs describe the
TARGET (no hedges); the roadmap has ONE "we are here" (per the owner's doc
conventions). Reconcile any drift with what actually shipped (above):

- `north-star.md` — fold the validation LADDER (T0-T3 + phase) into the thesis as
  the *live* half of "empirically-tuned, test-gated context"; update the ledger.
- `architecture.md` — the buzzsaw diagram + modes must show the 5-tier ladder,
  the phase gate, validation-as-early-stop, and the co-located oracle wire
  (bb `op:"refine"` now carries parse+structural+phase; node bundle = eval;
  pod = retrieval).
- `roadmap.md` — single "we are here": offline control surface COMPLETE + wired
  on-worker; next = GPU measurement. Delete the old path.
- `grounding.md` — add the `grammar.cljc` predicates + the entropy_bound/over-commit
  citation chain; keep every mechanism cited to `reference-code/`.
- `CLAUDE.md` — keep it the tight index/runbook it is; make sure the ladder + #51
  + the GPU-first next-step are the headline.
- Component notes under `docs/seon/components/` that touch the oracle/worker.

Verify with `bin/test-cljs` (NOT ad-hoc pod-repl require — the `seon.diffusion.*`
subtree is worker-destined and not in the pod's compiled module graph). The
markdown linter validates every `docs/**/*.md` on save.

### TASK 2 — the open research + what to understand better

1. **Measure the ladder's LIFT on the GPU (owner-gated, THE decisive step).** Deploy
   the co-location image (`tmp/flash-diffgemma/deploy-colocation.sh`), point the
   worker `refine_loop` at bb `op:"refine"` (currently `parse-raw` — so the T1/phase
   tiers aren't yet DRIVING the loop), and run A/B/C/D. **Run D (entropy_bound sweep)
   FIRST** — free ~2-3× on the A100. Win = the ladder's convergence + tok/s vs the
   naked baseline, a MOVED number not an anecdote.
2. **§3 over-commit × free-renoise sweep (the unique buzzsaw speed×quality lever).**
   Crank `entropy_bound` high (commit 30-50+/forward), let the free oracle renoise
   the wrong spans. Does C (over-commit + renoise) Pareto-dominate A (baseline)?
   This is where "the free oracle changes the speed×quality frontier" gets proven.
3. **De-risk the TPU-JAX path ($5 v5e-4 spike).** Load `google-deepmind/gemma/gemma/diffusion`,
   run `example.ipynb` generate, read tok/s. If a v5e clears ~300 tok/s the tok/$
   likely beats everything WHILE keeping the control seam (the JAX `_sampler.py` +
   `_early_stopping.py` IS our control loop, natively).
4. **The remaining phased-constraint sub-directions (build only once GPU proves the
   ladder — else it's un-measured machinery).** `;; PLAN:` clamped-token scaffolds;
   best-of-N via renoise (reset randomness, keep the best); **context-as-target via
   embedding search** (retrieve the RIGHT context to inject — the north-star's A/B
   made empirical). Understand better: what's the cheapest scorer per context
   artifact (the binding constraint is a good (task, scorer) pair, not GPU $).
5. **Understand the decode/compile ceiling better.** The compiled path is blocked
   (find_spec graph-break → CUDA device-assert with batched_mm); a transformers bump
   *might* clear find_spec but the batched_mm CUDA-graphs wall stands. Is there a
   clean compile-compatible control seam, or is eager-with-control the permanent
   trade? (Relates to the "custom Python stopping forfeits torch.compile" finding.)

### Standing constraints (DO NOT violate)

- Keys live in gitignored `.env` (RUNPOD_API_KEY, HF_TOKEN); **never commit
  credentials or the GCP project id**. Never name the downstream consumer (use
  `acme`/"the third party").
- Full control over the **acme** pod (7980) for live verification; **NEVER**
  `bin/seon` restart/reset the live **default** pod (7890).
- Commit after each unit with explicit pathspecs (shared multi-agent tree). Run
  the full `bin/test-cljs` suite ONCE at the natural checkpoint, not per-edit.
- Report code smells; falsify don't confirm; live proof over inference; honesty >
  completion. Don't build un-measured oracle features — the north-star is empirical.

### Open flags

- **#50** — the #42 `:minimal` config-profile is a no-op (`lean == full == 27607`
  tokens; doesn't drop `:skill/repl`). Pre-existing, UI/config lane, NOT diffusion.
- **Verification gotcha** — the `seon.diffusion.*` control nses aren't in the pod's
  compiled build; verify via `bin/test-cljs`, not ad-hoc pod-repl `require`.

---

## Provenance (this session's commits, newest first)

- `grammar.cljc` cleanup (drop empty refer-clojure exclude) + linter docstring
  reformats across the diffusion lane.
- `47527a33` #51 shared grammar ns (bb+pod, no drift).
- `47d1cdf2` phased grammar gate.
- `940b4bc9` / `d5d80fad` validation ladder T0-T3 (T1 lint, T2 eval, T3 behavioral).
- `521e550e` validation-as-early-stop + the ladder framing.
- Research: `#48` hardware (`a4914bb9`-era), `#49` forward-speedup (`ef6aa575`).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
