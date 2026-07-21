---
type: research
status: active
tags: [research, agent, diffusion, schema, flow]
---

# Mode-driven guided generation — the staged-build engine

> The capstone synthesis of the diffusion dynamic-context thesis: an agent that
> builds software in STAGES (schema → spec'd fns → generative tests → REPL
> exploration) where the DiffusionGemma model's per-step controllability is the
> engine. The model INVOKES a MODE; that triggers Seon to run GUIDED GENERATION —
> a clamped scaffold + infill slots, fed by reactive dynamic context, gated by the
> parse→eval→instrument→test quality ladder, with span-based re-noise on failure.
> DESIGN/SPEC only. Nothing here touches `src/seon`, the worker, or the GPU.
> Every mechanism cites a real artifact. Unproven proposals are labelled
> HYPOTHESIS with the experiment that would test them.

## TL;DR

- **A MODE is data, not code.** A `:seon.dg.mode` entity is a clamped-scaffold
  template + named infill slots + a list of dynamic-context section-fns + a quality
  gate + a re-noise policy + a predetermined vocabulary set. It lowers entirely onto
  primitives that are already PROVEN or source-grounded: the `ClampLogitsProcessor`
  (`tmp/flash-diffgemma/diffgemma_common.py:35-78`, applied per-step at
  `reference-code/transformers/.../generation_diffusion_gemma.py:1034`), the `infill`
  worker mode (`gpu_worker.py:335-412`), `span_to_positions`/`build_offset_map`
  (`diffgemma_common.py:184-222`), the parser oracle (`seon.repl.internal/parse-forms`,
  `:span`/`:error-kind`/`:source`, `repl/internal.cljc:561-677`), and the eval cage.
- **The model invokes a mode with a sentinel call** the parser already recognizes —
  `(mode/enter :defn-with-specs {…})` — NOT a new token and NOT a fine-tune.
  The seam is the same one Seon already uses to turn agent replies into actions: the
  reply is parsed, a `mode/enter` form is detected, and Seon switches the *next*
  generation from free completion to guided generation. (HYPOTHESIS that a sentinel
  beats just prompting — kill-gate in §7.)
- **Four worked modes** map 1:1 to the owner's stages: `:design-schema` (Stage 1, the
  schema→example-data→adapt loop), `:defn-with-specs` (Stage 2, clamp the `defn`
  skeleton + FORCE the `:malli/schema` in/out into infill slots), `:generative-test`
  (Stage 3), `:repl-explore` (Stage 4). The missing-spec detector that triggers Stage 2
  is grounded: a `:seon.fn` entity with NO `:seon.fn/spec` (or a `:seon.fn/schema-error`)
  IS the program graph saying "this fn is unspecced" (`src/seon/agent.cljs:210,215`,
  `src/seon/eval.cljs:1357`).
- **One general mode ENGINE, parameterized per stage** — argued in §6. The lowering
  (clamp → infill → per-step logits gate → parse/eval checkpoint → span re-noise) is
  identical for all four; only the scaffold template, the slot set, the context-fns,
  and the gate predicate differ, and those are DATA. Per-stage hardcoded modes would
  be the "foo-v2" anti-pattern at the mode layer.
- **Quality is enforced BY CONSTRUCTION via stage gates.** A stage cannot complete
  until its gate passes: parse (no `:read` entries) → eval (`{:ok true}` in the SCI
  cage) → instrument (the fn's `:malli/schema` registers + survives a call) →
  generative-test (Malli-generated data passes the property). The ladder's economics
  are MEASURED: parser detects 92.7% of corruptions, eval catches 91.5% of the rest,
  combined 93.5% (`parser-as-generation-oracle-2026-06-28.md`).
- **The build is ITERATIVE REFINEMENT over a NAMESPACE, not one-shot (§1.4).** A
  "build" is a sequence of PASSES that converge the namespace toward a goal. Each pass
  reads the CURRENT namespace state from the program graph (`:seon.ns`/`:seon.fn`/
  `:seon.schema` entities ARE the namespace — `docs/seon/concepts/code-as-data-runtime.md`)
  and the mode-ops generalize from CREATE to UPSERT/RETRACT. A pass = query current
  state → mode-guided mutation → re-query; convergence = the "what's missing" reactive
  sections go EMPTY (every fn specced + instrumented + generatively tested, goal
  predicates green). The quality bar is ALREADY grounded in the code-as-data publish
  gate: a fn propagates only when `:seon.fn/specced? true` AND
  `:seon.test/last-passed-at > :seon.test/last-failed-at` (`code-as-data-runtime.md:84-92`).
- **Live evidence (2026-06-28, fingerprint-certified): infill is mechanically PROVEN.**
  Clamping a prefix AND suffix holds both ends (`prefix_held=True`, `suffix_held=True`)
  while the middle denoises co-conditioning on the suffix. Content can be imperfect
  (a run produced `(length xs)` where `(count xs)` was wanted — parses clean, evals
  wrong) — which is PRECISELY the parse→eval→span-renoise loop's job. So the Stage-2
  forced-spec infill is sound at the PRIMITIVE level; quality comes from the oracle loop
  on top (§4-5), not from the clamp alone.
- **Skeptic (§8):** the single biggest risk is that the schema→example→adapt loop
  and forced-spec infill produce *incoherent* specs (a `[:map …]` that validates
  nothing useful), and that per-step eval is too slow at the worker's real step
  budget. Two more: canvas-length ceilings on big scaffolds, and the regime-change
  trap (shrinking `max_denoising_steps` is a DIFFERENT generation, not a checkpoint —
  `generation_diffusion_gemma.py:311`). The honest stance: **infill-the-spec-slot is
  the first kill-gate** — if a clamped `defn` scaffold with two spec holes does not
  reliably yield Malli that instruments, the whole "modes" idea is unjustified and we
  fall back to plain prompting.

---

## 1. The MODE abstraction

### 1.1 What a mode IS (as data)

A mode is a reactive recipe for one guided generation. It is an entity in the agent's
DB (so it is itself code-as-data and overridable per cluster, like every other
`:seon.*` row), carrying SIX things:

1. **A clamped scaffold template** — a string with `{{slot}}` holes. The non-hole text
   is what gets CLAMPED (held fixed every denoise step via `ClampLogitsProcessor`); the
   holes are where the model writes.
2. **Named infill slots** — each hole is a `:seon.dg.slot` with an id, a max token
   budget, and an optional per-slot quality predicate (e.g. "this slot must parse as a
   Malli schema").
3. **Dynamic-context section-fns** — symbols resolved at render time, exactly like
   `:seon.ctx/fn` (`docs/seon/concepts/reactive-context.md:31-38`). These produce the
   *prompt* that conditions the generation (generated example data, the missing-spec
   list, retrieved related fns/schemas, the last error+span).
4. **A per-step parse/eval gate** — the stopping criterion: decode the partial canvas,
   run the parser (and optionally eval), stop when it passes.
5. **A re-noise policy** — on gate-failure, which spans get re-randomized
   (`:span` → `span_to_positions` → drop from the clamp set) and how many retries.
6. **A predetermined vocabulary/function set** — the verbs this mode is allowed to
   emit (Stage 2 may call `defn`/`schema/register!`; Stage 4 may call any verb). This
   is the *scaffold's* fixed tokens plus an allow-list the gate can check.

### 1.2 Concrete mode schema (namespaced-keyword Malli)

Registered via `seon.schema/register!` (the single source of truth — `CLAUDE.md`
"Schema Registration"). Doc-embedded in ONE block for readability (same rationale as the
interface-design doc §6 — do not break the shared build with an incomplete ns). At
implementation time each keyword namespace becomes its OWN ns file — `:seon.dg.slot/*` in
`seon.dg.slot`, `:seon.dg.renoise/*` in `seon.dg.renoise`, `:seon.dg.gate/*` in
`seon.dg.gate`, `:seon.dg.mode/*` (via `::`) in `seon.dg.mode` — per the "keyword
namespaces = real code namespaces" rule. The single block here is illustrative, not the
file layout.

```clojure
(ns seon.dg.mode
  "Mode = a clamped-scaffold recipe for one guided generation. Data, not code:
   the model INVOKES a mode (parsed `mode/enter` form), Seon lowers the mode onto
   the worker's clamp/infill seam + the parser/eval gate. Self-host / pod ns."
  (:require [seon.schema :as schema]))

;; --- the slot: one infill hole in the scaffold -------------------------------
(schema/register! :seon.dg.slot/id        [:keyword {:seon.db/identity true}])
(schema/register! :seon.dg.slot/name      :string)        ; appears in the scaffold as {{name}}
(schema/register! :seon.dg.slot/max-tokens :int)          ; the hole's canvas budget
;; a predicate SYMBOL resolved like a section-fn; (fn [slot-text db] -> bool|error)
;; optionality lives on the :map key (below), NOT on the attr registration
(schema/register! :seon.dg.slot/gate-fn   :symbol)
(schema/register! :seon.dg.slot
  [:map
   [:seon.dg.slot/id :seon.dg.slot/id]
   [:seon.dg.slot/name :seon.dg.slot/name]
   [:seon.dg.slot/max-tokens :seon.dg.slot/max-tokens]
   [:seon.dg.slot/gate-fn {:optional true} :seon.dg.slot/gate-fn]])

;; --- the re-noise policy: what to re-decide on failure -----------------------
(schema/register! :seon.dg.renoise/strategy
  [:enum :span         ; drop only the parser :span's canvas positions from the clamp
         :slot         ; re-noise the whole failing slot
         :canvas])     ; re-noise everything non-scaffold (full retry)
(schema/register! :seon.dg.renoise/max-retries :int)
(schema/register! :seon.dg.renoise
  [:map
   [:seon.dg.renoise/strategy :seon.dg.renoise/strategy]
   [:seon.dg.renoise/max-retries :seon.dg.renoise/max-retries]])

;; --- the quality gate: how this mode's output is judged complete --------------
;; the ladder rungs a mode must clear (a SUBSET — Stage 1 stops at :eval since a
;; schema/register! has no in/out to instrument; Stage 2 goes to :instrument).
(schema/register! :seon.dg.gate/rung [:enum :parse :eval :instrument :generative-test])
(schema/register! :seon.dg.gate/rungs [:vector :seon.dg.gate/rung])

;; --- the mode itself ----------------------------------------------------------
(schema/register! :seon.dg.mode/id        [:keyword {:seon.db/identity true}])
(schema/register! :seon.dg.mode/scaffold  :string)        ; clamped text + {{slots}}
(schema/register! :seon.dg.mode/slots     [:vector {:seon.db/component true} :seon.db/ref])
;; section-fn SYMBOLS — resolved per render exactly like :seon.ctx/fn
(schema/register! :seon.dg.mode/context-fns [:vector :symbol])
(schema/register! :seon.dg.mode/gate-rungs :seon.dg.gate/rungs)
(schema/register! :seon.dg.mode/renoise   :seon.db/ref)
(schema/register! :seon.dg.mode/vocab     [:vector :symbol])  ; allowed verbs
(schema/register! :seon.dg.mode/next-mode :keyword)          ; stage advance (optional on the :map key)
(schema/register! :seon.dg.mode
  [:map
   [:seon.dg.mode/id :seon.dg.mode/id]
   [:seon.dg.mode/scaffold :seon.dg.mode/scaffold]
   [:seon.dg.mode/slots :seon.dg.mode/slots]
   [:seon.dg.mode/context-fns :seon.dg.mode/context-fns]
   [:seon.dg.mode/gate-rungs :seon.dg.mode/gate-rungs]
   [:seon.dg.mode/renoise :seon.dg.mode/renoise]
   [:seon.dg.mode/vocab :seon.dg.mode/vocab]
   [:seon.dg.mode/next-mode {:optional true} :seon.dg.mode/next-mode]])
```

Modes are SEED-COPIED into a cluster like context blocks (the `install!`/`remove!`
override pattern in MEMORY's unified design), so a downstream consumer can add or
re-shape a mode with zero `src/seon` edits — the same override discipline as
context blocks and routes.

### 1.3 How the model INVOKES a mode

**Decision: a parsed sentinel form, not a special token, not a fine-tune.** The model
emits, as ordinary output, a call:

```clojure
(mode/enter :defn-with-specs {:seon.dg/target-fn 'my.inventory/restock})
```

This rides the seam Seon ALREADY has. Agent replies are parsed by
`seon.repl.internal/parse-forms` (`repl/internal.cljc:561`), which returns each
top-level `(…)` as a `:kind :form` entry with its `:form` sexpr
(`internal.cljc:624-628`). Seon's eval path already dispatches on the head symbol of
such forms (that is how `db/transact!`, `todo/add!`, etc. become actions). `mode/enter`
is one more recognized head: instead of being eval'd as a side-effecting verb, it sets
the agent's `:seon.agent/dg-mode` row, so the agent's NEXT generation is routed through
guided generation for that mode instead of free completion.

Why a sentinel form and not a token: (a) zero model surgery — DiffusionGemma emits it
as text and the existing parser catches it; (b) it carries structured args (the target
fn, the schema id) in a shape the parser already returns; (c) it is itself
parse/eval-gateable (a malformed `mode/enter` is caught by the same oracle).

Why not just prompt ("now write the spec")? That is the §7/§8 kill-gate. The
hypothesis is that *clamping a scaffold* (forcing the `(defn name [args]` skeleton and
holding it fixed while only the spec hole denoises) yields more reliable, more
instrumentable specs than free prose. If forced-spec infill does NOT beat a plain
prompt, modes collapse to prompts and we keep only the dynamic-context half.

**Two invocation flavors:**

- **Model-initiated** (above): the agent decides "I'm now designing a schema" and
  enters the mode itself. This is the owner's "the MODEL invokes a mode".
- **System-forced** (Stage 2's signature move): Seon DETECTS an unspecced fn (§3.2) and
  forces `:defn-with-specs` on it — the model doesn't get to skip the spec, because the
  scaffold makes the spec a clamped, must-fill hole. This is guided generation as a
  CONSTRAINT, not a suggestion.

### 1.4 The convergent-pass frame — a build is iterative refinement over a namespace

The four stages are NOT a one-shot pipeline run once top-to-bottom. A build is a
sequence of REFINEMENT PASSES that converge a NAMESPACE toward a goal. This reframes
modes from "generate from scratch" to "mutate the current working namespace", and it is
a direct fit with two Seon theses — lean on both explicitly.

**Code-as-data: the namespace IS DB entities** (`docs/seon/concepts/code-as-data-runtime.md`).
The working namespace is not a scratchpad in some side store — it is the
`:seon.ns`/`:seon.fn`/`:seon.schema` entities the program graph already holds
(`code-as-data-runtime.md:31`). detect-and-tee captures every successful agent eval as
these entities (`:56-66`); identity-attr upsert means a redefinition REPLACES in place
and history retains the prior version (`:67`). So a pass that "overwrites a fn" is just
an eval whose `:seon.fn/sym` already exists; a pass that "deletes a dead fn" is a
retract. There is no separate from-scratch mode and overwrite mode at the storage layer
— it is all upsert/retract against the one program graph.

**Reactive context: namespace state is a function of the DB at render time**
(`docs/seon/concepts/reactive-context.md`). Each pass re-renders the CURRENT
schemas/data/fns/tests by querying the DB; when the goal is met, the "what's missing"
sections (unspecced fns, untested fns, failing predicates) return empty rows and VANISH
— the build is self-healing and the convergence signal is "the missing-work sections are
all blank".

**A pass, concretely:**

1. **Read** the current namespace state from the program graph (§3.0 whole-namespace
   render): every schema + generated example data + every fn + its spec/test status.
2. **Decide** the next mode-op: which schema to upsert/retract, which fn to write/
   overwrite/delete, which test to add — driven by the "what's missing / what's wrong"
   reactive sections.
3. **Mutate** via a mode-guided generation (clamp scaffold + infill + gate + renoise),
   which on success transacts new/updated program-graph entities (detect-and-tee).
4. **Re-query** — the next pass sees the mutated namespace; resolved gaps disappear.

**Pass boundary / convergence:** a pass ENDS when its mode-op clears its gate (or
exhausts retries and flags the target). The NEXT pass is triggered while any
"missing-work" section is non-empty OR any goal predicate is red. The build CONVERGES
when nothing more needs upserting: every fn is `:seon.fn/specced?` + instrumented +
its `:seon.test/last-passed-at > :seon.test/last-failed-at` (the EXISTING publish-gate
bar, `code-as-data-runtime.md:84-92`) AND the goal's behavioral predicates pass. This is
MEASURABLE as a namespace scorecard (§5.3) — convergence is a number trending to zero
open items, not a vibe.

**Critically evaluating this frame (the owner invited a better one):** "iterative upsert
passes converging a namespace" is the RIGHT abstraction, for three reasons grounded in
what already exists. (a) It needs NO new storage — the program graph + detect-and-tee +
identity-upsert already make the namespace a mutable-by-eval DB object; a from-scratch
model would have to invent a scratchpad and then reconcile it, which is the very
bifurcation reactive-context bans. (b) The convergence bar already EXISTS as the publish
gate — we are not inventing a quality metric, we are reusing the one that already governs
cross-agent code propagation. (c) Pass boundaries and the next-pass trigger are reactive
queries (missing-work sections), not a hand-coded state machine, so they self-heal and
can't drift. The honest weakness is **oscillation / non-convergence** (§8 item 8): pass N
upserts a schema that breaks a fn pass N-1 had passing, so the "untested fn" section
re-populates and the loop ping-pongs. The mitigation is a monotonicity guard — a pass may
not REDUCE the count of green (specced+tested) fns without explicitly retracting them as
intentional; if a schema upsert breaks downstream fns, those fns become new
missing-work items in the SAME convergence accounting, and the scorecard's open-item
count must trend down across passes or the build halts and flags. A cleaner alternative
considered and rejected: a fixed DAG of stages run once. Rejected because real builds
discover mid-way that an early schema was wrong — a one-pass DAG can't revisit Stage 1
from Stage 3, whereas the refinement loop just re-enters `:upsert-schema`. The loop
subsumes the DAG (a DAG is a refinement loop that happens to converge in one pass).

---

## 2. A worked mode taxonomy for the staged build

Each mode below specifies: the scaffold it clamps, the slots it infills, the context it
shows, the quality gate, and how it advances. Stages map to the owner's four — but each
mode is OPERATION-oriented over the current namespace (§1.4): it does CREATE *or* UPSERT
*or* RETRACT against the existing program-graph entities, not only generate-from-scratch.

**The op axis (orthogonal to the stage axis).** Every mode carries a
`:seon.dg.mode/op ∈ {:create :upsert :retract}` and the engine selects it from the
target's current state: if the target `:seon.fn/sym` / `:seon.schema/key` already exists,
the op is `:upsert` (the scaffold is SEEDED with the existing source so the model edits,
not rewrites); if the missing-work section says "this entity is dead / superseded", the op
is `:retract` (no generation — a `[:db/retract …]` transact). So `:design-schema`+`:create`
= "model a new attr"; `:design-schema`+`:upsert` = "tighten/rename an existing attr's
shape"; `:design-schema`+`:retract` = "drop a dead attr". Same mode row, three ops. This
is why one engine subsumes the lot (§6): CREATE is just UPSERT against an absent entity.

```clojure
(schema/register! :seon.dg.mode/op [:enum :create :upsert :retract])
;; added to the :seon.dg.mode :map as a key; selected by the engine from target state
```

### 2.1 `:mode/design-schema` (Stage 1 — schema with generated-data feedback)

- **Scaffold (clamped):** the `register!` skeleton; the model fills the shape.

  ```
  (schema/register! ::{{attr-name}}
    {{schema-shape}})
  ```

- **Slots:** `:attr-name` (short, ~8 tok), `:schema-shape` (the Malli form, ~64 tok).
- **Context shown (section-fns, run per generation):**
  - `generated-example-data` — THE loop. After each candidate schema evals, Seon runs
    the Malli generator on it (`mg/sample`, the existing generative-test path) and
    shows the model 3-5 concrete example values. The model "thinks about the examples"
    (next denoise) and adapts the shape. (HYPOTHESIS: seeing generated data tightens
    the schema — kill-gate convergence metric in §7.)
  - `sibling-schemas` — other `:seon.schema/source` rows in the same ns (so the new
    attr is consistent with neighbors). Grounded: `:seon.schema/key`/`/source` exist
    (`agent.cljs:218-221`).
- **Quality gate:** `[:parse :eval]`. A `schema/register!` form has no in/out to
  instrument, so the ladder stops at "it evals clean and the generator produces
  samples without throwing". Gate-complete = `mg/sample` returns N values.
- **The feedback loop (the owner's Stage 1 verbatim):**
  1. Generate a candidate schema (clamped scaffold, spec-shape slot).
  2. Eval it → register. If it throws, re-noise the slot (`:span` policy) and retry.
  3. Run `mg/sample` → show the generated examples back as the `generated-example-data`
     section.
  4. The model re-enters `:design-schema` for the SAME attr (or a sibling) and adapts.
     Loop until the model emits `(mode/satisfied :design-schema)` or a max-iteration cap.
- **Advance:** `:next-mode :defn-with-specs` once the data model is satisfied.

### 2.2 `:mode/defn-with-specs` (Stage 2 — the signature mode)

This is the kill-gate mode. Force a spec'd function by CLAMPING the `defn` skeleton and
making the `:malli/schema` in/out two mandatory infill slots.

- **Scaffold (clamped — the fixed text holds every step):**

  ```
  (schema/register! ::{{req-name}}-request {{in-spec}})
  (schema/register! ::{{req-name}}-response {{out-spec}})

  (defn {{fn-name}}
    "{{docstring}}"
    {:malli/schema [:=> [:cat ::{{req-name}}-request] ::{{req-name}}-response]}
    [{{arglist}}]
    {{body}})
  ```

  The clamped tokens are everything OUTSIDE the `{{…}}` — including the literal
  `:malli/schema`, the `[:=> [:cat …] …]` frame, and the `register!` heads. The model
  CANNOT omit the spec because the spec frame is part of the immovable scaffold; it can
  only fill the holes.

- **Slots (infill):** `:in-spec` (a Malli `[:map …]`, ~96 tok), `:out-spec` (~64 tok),
  `:fn-name`, `:docstring`, `:arglist`, `:body` (~160 tok).
- **Context shown:**
  - `missing-spec-target` — the unspecced fn this mode was forced on (§3.2), with its
    current source so the model writes a spec that MATCHES the body.
  - `related-fns-and-schemas` — program-graph retrieval (§3.3): fns this body calls,
    schemas it references, so the spec reuses registered shapes instead of re-inlining
    (the "register once, reference everywhere" rule, `CLAUDE.md`).
  - `last-error-and-span` — on a retry, the parser/instrument error + the `:span` that
    failed.
- **Quality gate:** `[:parse :eval :instrument]`. Promotion requires:
  1. **parse** — no `:read` entries (`parse-forms`).
  2. **eval** — the whole form `{:ok true}` in the SCI cage (`seon.eval/eval`).
  3. **instrument** — the registered `:malli/schema` is wellformed AND a probe call
     through the instrumented fn does not throw `:malli.core/invalid-input/-output`
     for a generator-produced input. Grounded: instrumentation validates every
     schema'd fn (`CLAUDE.md` "Function Instrumentation"); a bad spec throws at
     runtime, which is exactly the signal the gate reads. The program graph records
     the result: `:seon.fn/spec` set + no `:seon.fn/schema-error` =
     "fully specced + reconstituted" (`eval.cljs:1357`).
- **Advance:** `:next-mode :generative-test`.

#### Example canvas layout for `:defn-with-specs`

`C` = clamped (held fixed every step via `ClampLogitsProcessor`); `▒` = infill slot
(denoises freely, bidirectionally co-conditioned on the clamped suffix — the property
an AR model cannot have, `infill` mode `gpu_worker.py:335-412`).

```
canvas pos:  0        10        20        30        40        50        60   …
             |---------|---------|---------|---------|---------|---------|----
content:     (defn ▒▒▒ [▒▒▒▒]                          ← fn-name, arglist slots
             CCCCC▒▒▒▒C▒▒▒▒C
               {:malli/schema [:=> [:cat ::req] ▒▒▒▒▒▒▒]}   ← out-spec slot
               CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC▒▒▒▒▒▒▒C
               ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒)               ← body slot
               ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒C
```

The clamp set = the positions of every `C`. `ClampLogitsProcessor.__call__`
(`diffgemma_common.py:69-78`) forces those positions' logits to a near-one-hot on the
scaffold token EVERY step; `accept_canvas` always keeps them (they are the lowest-entropy
positions, `entropy_bound` accepts them — `diffgemma_common.py:43-47` documenting
`generation_diffusion_gemma.py:431-442`); `renoise_canvas` only re-randomizes the `▒`
positions (`:457-463`). The result: the `defn`/`:malli/schema` frame is GUARANTEED in
the output; only the holes vary. The `:malli/schema` LITERAL being clamped is what
"forces" the spec.

### 2.3 `:mode/generative-test` (Stage 3 — instrumented generative testing)

- **Scaffold (clamped):** the `deftest` + `mg/sample` skeleton.

  ```
  (deftest {{test-name}}
    (doseq [{{binding}} (mg/sample ::{{req-schema}} {:size {{n}}})]
      (is {{property}})))
  ```

- **Slots:** `:test-name`, `:binding`, `:property` (the assertion over the
  generator-produced input + the fn's output).
- **Context shown:**
  - `target-fn-spec` — the fn-under-test's `:seon.fn/spec` (so the property matches the
    declared out-spec).
  - `schemas-for-generation` — the request schema the data is sampled from.
- **Quality gate:** `[:parse :eval :generative-test]`. Promotion = the `deftest` runs
  and PASSES on N generator-produced inputs (the existing generative-test machinery,
  `CLAUDE.md` "Generative testing"). A failing property re-noises the `:property` slot
  with the failing input shown as context.
- **Advance:** `:next-mode :repl-explore` once every fn in the ns has a passing
  generative test (the "until all functions are fully tested" gate — a query over the
  program graph: every `:seon.fn/sym` in the ns has ≥1 passing `:seon.deftest`).

### 2.4 `:mode/repl-explore` (Stage 4 — experimental REPL phase)

- **Scaffold:** MINIMAL/none. This stage is interactive, so the scaffold is just a
  thin clamp of the call frame `({{fn}} {{args}})` when the model is exercising a known
  fn, or no clamp at all for free exploration.
- **Slots:** `:fn`, `:args` (when calling) — or the whole canvas is free.
- **Context shown:** the full reactive agent context (the eval log, the value-explorer
  tile, the live results) — this is where modes hand back to the normal agent loop.
- **Quality gate:** `[:parse :eval]` per form (the existing per-eval gate). No
  promotion — Stage 4 is the terminal "the agent now just works in the REPL" state,
  with the buzzsaw available on demand (the model can re-enter any mode).
- **Advance:** none; the agent re-enters earlier modes as needed (e.g. it discovers a
  fn needs a spec → `(mode/enter :defn-with-specs …)`).

---

## 3. Dynamic context strategy

The owner's question (a): *what context do we dynamically show the model to help each
generation?* Answer: a small set of section-fns, each a pure function of the DB at
render time, exactly as `docs/seon/concepts/reactive-context.md` mandates. A mode's
`:seon.dg.mode/context-fns` lists the symbols; the composer resolves and calls each with
`{:seon.db/db <db> :seon.agent/id <id> :seon.dg/mode-state <state>}` and joins the
non-blank returns into the generation prompt. They UPDATE between generations
automatically because they are re-queried each time — no stored prompt, no stale copy.

On pass 2+ these sections render the CURRENT namespace, not a blank scratchpad (§1.4) —
the existing schemas/fns/tests are program-graph entities, so the "context" a refinement
pass conditions on IS the working namespace read back from the DB.

### 3.0 `namespace-state` — the WHOLE namespace as context (load-bearing)

The owner's load-bearing section: render the ENTIRE namespace — every schema + generated
example data for each + every fn (source + spec + test status) — as one reactive section.
This is what lets a refinement pass understand the current state AND guide the generative
portions: you cannot generate good test data or reason about what changed without the
full schema graph + representative example values in view. It is a pure fn of the program
graph (code-as-data: `:seon.ns`/`:seon.fn`/`:seon.schema` ARE the namespace).

```clojure
(defn namespace-state
  "The whole working namespace as guidance context: schemas + example data +
   fns + their spec/test status. A pure fn of the program graph — re-renders the
   CURRENT state every pass; the convergence picture the model refines against."
  [{:seon.db/keys [db] :seon.dg/keys [target-ns]}]
  (let [schemas (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?key ?src
                             :in $ ?ns
                             :where [?s :seon.schema/ns ?nse] [?nse :seon.ns/name ?ns]
                                    [?s :seon.schema/key ?key] [?s :seon.schema/source ?src]]
                           :seon.db/args [target-ns]})
        fns     (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?sym ?src (pull ?f [:seon.fn/spec :seon.fn/schema-error])
                             :in $ ?ns
                             :where [?f :seon.fn/ns ?nse] [?nse :seon.ns/name ?ns]
                                    [?f :seon.fn/sym ?sym] [?f :seon.fn/source ?src]]
                           :seon.db/args [target-ns]})]
    (str "<namespace " target-ns ">\n"
         ;; schemas WITH generated example data inline — the generative guidance
         (str/join "\n" (for [[k src] schemas]
                          (str src "\n  ;=> examples: "
                               (pr-str (mg/sample k {:size 3})))))
         "\n;; functions (✓=specced+tested, ✗=needs work):\n"
         (str/join "\n" (for [[sym src spec] fns]
                          (str (if (:seon.fn/spec spec) "✓ " "✗ ") sym)))
         "\n</namespace>")))
```

Updates every pass: a new schema → a new example block; an overwritten fn → new source +
status; a retracted fn → it drops out of the query. The convergence picture is literally
this section's `✗`-count trending to zero.

### 3.1 `generated-example-data` (Stage 1's engine)

Pure fn of the current schema set. For the schema the model just wrote, call the Malli
generator and render N concrete values. This is the feedback that makes Stage 1 a loop.

```clojure
(defn generated-example-data
  "Sample the schema the model just registered; show concrete values back.
   Empty until a schema exists. The Stage-1 feedback collar."
  [{:seon.db/keys [db] :seon.dg/keys [mode-state]}]
  (let [k (:seon.dg/last-schema-key mode-state)]
    (when k
      (let [samples (mg/sample k {:size 4})]   ; existing generative-test path
        (str "<generated-from " k ">\n"
             (str/join "\n" (map pr-str samples))
             "\n</generated-from>")))))
```

Updates: after each schema eval, `mode-state`'s `:last-schema-key` advances → next
render samples the new shape. Self-healing: a retracted schema → no samples → section
vanishes.

### 3.2 `missing-spec-target` (Stage 2's trigger)

The detector the owner described — "detect when a function's `:malli/schema` is
MISSING". GROUNDED: a `:seon.fn` entity stores its spec under `:seon.fn/spec`
(`agent.cljs:210`) and a malformed one under `:seon.fn/schema-error`
(`agent.cljs:215`); `eval.cljs:1357` states the reconstitutable predicate is exactly
"has a `:seon.fn/spec` and NO `:seon.fn/schema-error`". So "unspecced" is a one-line
query: a `:seon.fn/sym` whose entity is MISSING `:seon.fn/spec`.

```clojure
(defn missing-spec-target
  "Fns in the agent's namespaces that lack a :malli/schema. The trigger that
   FORCES :defn-with-specs. Vanishes when every fn is specced."
  [{:seon.db/keys [db]}]
  (let [unspecced (db/query
                    {:seon.db/db db
                     :seon.db/query
                     '[:find ?sym ?src
                       :where
                       [?f :seon.fn/sym ?sym]
                       [?f :seon.fn/source ?src]
                       [(missing? $ ?f :seon.fn/spec)]]})]  ; ground via get-else/absent pattern
    (when (seq unspecced)
      (str "<unspecced-fns>\n"
           (str/join "\n" (map first unspecced))
           "\n</unspecced-fns>"))))
```

(The `missing?`/absence test uses the standard datahike absent-attr idiom — see the
`/datahike` skill; the point is the data is THERE.) When the model fills the spec and
the fn re-evals with a `:seon.fn/spec` set, the query returns empty and the section
disappears — the reactive-context self-healing guarantee.

### 3.3 `related-fns-and-schemas` (retrieval)

For the body the model is writing, surface the fns it calls and schemas it references,
so it reuses registered shapes (the "register once, reference everywhere" rule) and
calls real verbs (the wrong-fn-name blind spot the parser-oracle doc flags as the
retrieval tier's job, `parser-as-generation-oracle-2026-06-28.md` "FLAG kinds"). Two
retrieval sources, both grounded: the program graph (`:seon.fn/source` /
`:seon.schema/source` rows, queryable by symbol/keyword substring) and, when
`SEON_EMBED` is on, the Proximum embedding index over `:seon.fn/source`
(`embed.clj:544`). Updates as the ns grows.

### 3.4 `last-error-and-span` (the re-noise driver)

After a failed gate, the parser's `:error-kind` + `:span` + `:source` (the bad span
text) — `parse-forms` returns these on a `:read` entry (`internal.cljc:674-677`). This
section both informs the model AND drives the re-noise mechanism (§4): the `:span` is
mapped to canvas positions and dropped from the clamp set. Vanishes when the form
parses clean.

### 3.5 How they update between generations

They DON'T store anything. Each generation re-runs the section-fns against the current
DB + the current `mode-state` (last schema key, target fn, last error). Because Stage 1
eval'd a schema, Stage 2 registered a spec, or Stage 3 ran a test — the DB changed, so
the next render's sections reflect it. This is the reactive-context contract applied to
generation prompts: **the prompt is a function of the DB, recomputed every step we
re-enter the loop.**

---

## 4. Guided generation mechanism — how a mode lowers to the primitives

The owner's question (b): *how do we guide the generation itself?* A mode is compiled
to a worker call + a control loop. The lowering, step by step, every step grounded:

### 4.1 Compile the scaffold → clamp set + holes

1. Take `:seon.dg.mode/scaffold`, split on `{{slot}}` markers.
2. Tokenize the clamped text to get canvas token ids and positions; build the
   `offset_map` (`build_offset_map`, `diffgemma_common.py:184-208`) so char spans ↔
   canvas positions later.
3. Each clamped run becomes entries in `clamp_by_pos` (`{position → token-id}`); the
   `▒` runs are the holes with their `:max-tokens` budgets. The clamp set is passed as
   the `clamp_text`/`clamps` payload (`infill`/`clamp_smoke` mode, `gpu_worker.py`).

### 4.2 Generate one canvas under clamp (the proven primitive)

Call the control worker with the clamp set. Per step
(`generation_diffusion_gemma.py:1034`): `ClampLogitsProcessor` runs FIRST (before the
temperature schedule, `:1170-1181`), forcing the clamped positions to a near-one-hot;
`accept_canvas` keeps them; `renoise_canvas` re-randomizes only the holes. The holes
denoise bidirectionally, co-conditioned on the clamped suffix (the infill property,
`gpu_worker.py:335-412`). This is PROVEN at the `clamp_smoke` level (the CLAUDE.md
decisive test) and is exactly the `infill` mode generalized from one hole to many.

### 4.3 Per-step parse/eval checkpoint (the gate, source-grounded)

The gate is a custom diffusion stopping criterion. GROUNDED:
`DiffusionGemmaAdaptiveStopping` is an ABC (`generation_diffusion_gemma.py:466`);
override `_prepare_diffusion_stopping_criteria` (`:1207`) to inject a subclass whose
`__call__(argmax_canvas, processed_logits)` decodes the canvas, runs the parser (and,
for the `:eval` rung, the SCI cage), and returns "stop" once the partial canvas clears
the mode's gate rungs. This runs on the default non-compiled DynamicCache path
(`is_compiling=False`, `:692`) so a Python parse/eval doesn't break torch.compile
(`:1258-1263`). This is the CORRECT mechanism per the source-grounding doc §3/§5 item
4 — NOT lowering `max_denoising_steps` (see §4.5 caveat).

The ladder per rung:

- **`:parse`** — decode → `parse-forms` → stop iff no `:read` entries
  (`internal.cljc:561-677`). Cheap, no model call (the parser-oracle's 92.7% detection).
- **`:eval`** — additionally `seon.eval/eval` the form in the SCI cage; stop iff
  `{:ok true}` (the eval tier's 91.5% catch). Idempotent forms (`def`/`defn`/`register!`/
  `deftest`) are safe to eval-check (CLAUDE.md "eval cage").
- **`:instrument`** — additionally confirm the fn's `:malli/schema` registers and a
  probe call doesn't throw `invalid-input/-output`.
- **`:generative-test`** — additionally the `deftest` passes on N samples.

### 4.4 Span-based re-noise on failure (the dial)

When the gate FAILS and the partial canvas has a parser `:read` entry, the `:span`
`[start end]` (`internal.cljc:676`) is the re-noise dial. Map it:
`span_to_positions(offset_map, [start end])` (`diffgemma_common.py:211-222`) → the
canvas token positions the bad char-span covers → DROP those from the clamp set (so the
entropy bound re-decides them) → continue/restart the K-step generate. For the SAFE
parser classes (`:eof`/`:unmatched-delimiter`, 95/124 corruptions, 100% mechanically
recoverable — `parser-as-generation-oracle`), `seon.repair` fixes the canvas in place
with NO re-noise and NO model round-trip; only the FLAG classes (`:invalid-token`,
20/124) and eval-tier failures actually re-noise.

### 4.5 The control loop (assembled)

```
enter-mode(mode, target):
  scaffold, clamp_set, holes = compile(mode.scaffold)           ; §4.1
  ctx   = render(mode.context-fns, db, mode-state)              ; §3, reactive
  pkv   = nil
  for attempt in 0 .. mode.renoise.max-retries:
    out = control-worker.generate(                              ; §4.2, proven clamp
            prompt = ctx,
            decoder_input_ids = seed-from(clamp_set),
            logits_processor  = Clamp(clamp_set),
            max_denoising_steps = N,                            ; KEEP N — do NOT shrink (§4.5 caveat)
            stopping = ParseEvalGate(mode.gate-rungs),          ; §4.3, ABC override
            past_key_values = pkv)
    canvas = out.sequences[:, -canvas_len:]
    rungs  = ladder-check(canvas, mode.gate-rungs)              ; §5
    if rungs.all-pass: 
      persist(canvas) ; advance-to(mode.next-mode) ; return     ; §5 promotion
    span  = parser-span(canvas)                                 ; §4.4
    clamp_set = renoise(clamp_set, span, mode.renoise.strategy) ; drop bad positions
    pkv   = out.past_key_values                                 ; §3 grounding item 6
    ctx   = render(mode.context-fns, db, mode-state')           ; re-query (error+span now present)
  flag-unresolved(target)                                       ; honest failure, surface to agent
```

CAVEAT (source-grounded, do not get this wrong): the loop keeps
`max_denoising_steps=N` and stops EXTERNALLY via the gate. Shrinking
`max_denoising_steps` to "checkpoint at K" COMPRESSES the temperature ramp
(`temperature = t_min + (t_max-t_min)*(cur_step/max_denoising_steps)`,
`generation_diffusion_gemma.py:311`) — it is a DIFFERENT generation regime, not a peek
at step K. The recommended outer-loop-of-K-step-`generate()` calls reuse only public
seams (`decoder_input_ids` + `ClampLogitsProcessor` + `past_key_values`,
source-grounding §3).

---

## 5. The quality ladder + stage gates

The ladder is the same four rungs at every stage; a mode names the SUBSET it must
clear, and a stage cannot complete until its gate passes. This is how "very high
quality standards" are enforced BY CONSTRUCTION — not by asking the model to be careful,
but by refusing to advance until the artifact provably clears the rung.

| Rung | Check | Oracle | Measured economics |
|---|---|---|---|
| **parse** | `parse-forms` → no `:read` entries | `seon.repl.internal` | 92.7% of corruptions detected, no model call |
| **eval** | `seon.eval/eval` → `{:ok true}` | SCI cage | 91.5% of masked-divergent caught (62.5% reference-free) |
| **instrument** | `:malli/schema` registers + probe call doesn't throw | Malli instrumentation | always-on, validates in/out/arity |
| **generative-test** | `deftest` passes on N `mg/sample` inputs | generative testing | property-level |

Combined parse+eval catch 93.5% of meaning-altering corruptions
(`parser-as-generation-oracle-2026-06-28.md`). The residual ~6.5% (dead-data mutation,
off the live path) is exactly what the generative-test and retrieval tiers exist to
cover — a corruption to data the program never reads is invisible to running it, but a
property test that compares against intent catches it.

**Promotion rule:** `mode.gate-rungs` is an ordered prefix of `[:parse :eval :instrument
:generative-test]`. Promotion to `:next-mode` requires EVERY listed rung green. A stage
that can't clear its gate after `renoise.max-retries` does NOT silently pass — it flags
the target as unresolved in the agent's reactive context (honesty > completion), and the
agent (or a human) sees it on the next render. There is no "mostly done" — the gate is
binary, and the artifact is in the DB as proof (a `:seon.fn/spec` set, a passing
`:seon.deftest`), live-provable by query.

### 5.3 The namespace scorecard — convergence is measurable

A build's progress is not per-form, it is per-NAMESPACE, and it is a query — the same
publish-gate predicate the system already uses for cross-agent propagation. Convergence
is "open items trend to zero across passes":

```clojure
(defn namespace-scorecard
  "Open-work count for a namespace. Converged = 0. Reuses the publish-gate bar
   (specced? AND last-passed-at > last-failed-at, code-as-data-runtime.md:84-92)."
  [{:seon.db/keys [db] :seon.dg/keys [target-ns goal-predicates]}]
  (let [fns      (fns-in-ns db target-ns)                  ; :seon.fn entities
        unspecced (remove :seon.fn/spec fns)
        untested  (remove publish-gate-green? fns)          ; specced? ∧ passed>failed
        goal-red  (remove #(eval-goal-predicate db %) goal-predicates)]
    {:seon.dg.scorecard/ns target-ns
     :seon.dg.scorecard/open-unspecced (count unspecced)
     :seon.dg.scorecard/open-untested  (count untested)
     :seon.dg.scorecard/open-goals     (count goal-red)
     :seon.dg.scorecard/converged?     (and (empty? unspecced)
                                            (empty? untested)
                                            (empty? goal-red))}))
```

The GOAL is represented as a set of behavioral predicates (the gym's existing
`:seon.gym.predicate` shape, `driver.cljs`) — "the namespace converges toward whatever
the goal is" = those predicates go green AND every fn clears the publish gate. The
NEXT-PASS trigger is `(not converged?)`; the MONOTONICITY guard (§1.4, anti-oscillation)
is "open-item count must not increase across two consecutive passes without an explicit
retract" — if it does, halt and flag rather than ping-pong. Each pass's scorecard lands
in the gym scorecard `(namespace × git-sha × pass-n)` so convergence is a trend line, not
a vibe.

---

## 6. Generalization — one engine or per-stage modes?

**Argument FOR one parameterized engine (the recommendation):** The lowering in §4 is
IDENTICAL across all four stages — compile scaffold → clamp → infill holes → per-step
gate → span re-noise → promote. The ONLY differences between stages are DATA:

- the scaffold template string,
- the slot set + budgets,
- the `context-fns` list,
- the `gate-rungs` prefix,
- the `renoise` policy,
- the `vocab` allow-list.

All six are fields on `:seon.dg.mode`. A new stage (or a downstream consumer's custom
stage) is a new `:seon.dg.mode` ROW, not new code — the same code-as-data /
"turtles all the way down" discipline that governs context blocks, routes, and
sections. Building four hardcoded mode functions would be the "foo-v2" anti-pattern
(`CLAUDE.md` "Don't be a dumbass") at the mode layer: four copies of the same control
loop that drift. The engine is ONE `run-mode!` that reads the mode row and executes
§4.5.

**Argument AGAINST (where one engine strains):** Stage 4 (`:repl-explore`) barely uses
the scaffold — it is mostly free generation with an optional thin call-frame clamp. And
Stage 1's feedback loop (generate → sample → show → re-enter) is an OUTER loop the
generic engine doesn't express; it is "run the mode, then re-run it with new context".
But neither breaks the engine: Stage 4 is just a mode with an empty/minimal scaffold and
`[:parse :eval]` gate (the engine degenerates to the normal eval loop), and Stage 1's
outer loop is the SAME `for attempt` loop in §4.5 with `renoise.strategy :canvas` and
the `generated-example-data` section providing the "show it back" — the convergence
criterion is the model emitting `(mode/satisfied …)` rather than a gate pass.

**The refinement loop subsumes the stages (the strong generalization).** Once the build
is reframed as iterative upsert-passes converging a namespace (§1.4), the four "stages"
collapse into ONE outer loop with two inner axes — a `mode` (what shape to generate) and
an `op` (`:create`/`:upsert`/`:retract` against the current entity). The outer loop is:

```
converge-namespace(target-ns, goal-predicates):
  while (not (namespace-scorecard target-ns goal-predicates).converged?):  ; §5.3
    item = pick-open-item(target-ns)        ; from the missing-work reactive sections (§3)
    mode = mode-for(item)                   ; :design-schema | :defn-with-specs | :generative-test
    op   = op-for(item, current-state)      ; absent→:create, exists→:upsert, dead→:retract
    run-mode!(mode, op, item)               ; §4.5 — one guided generation, gate-enforced
    ;; re-query happens implicitly: next scorecard reads the mutated program graph
  ;; converged: every fn specced+tested, goal predicates green; "what's missing" sections empty
```

`run-mode!` is the SAME §4.5 control loop for every (mode, op). `pick-open-item` and the
loop condition are reactive queries (§3, §5.3), not a state machine. So the owner's "keep
tuning the namespace for whatever the goal is" IS a single `converge-namespace` over a
table of mode rows — the stages are emergent (you happen to do schema-ops before
fn-ops because the missing-work sections surface unspecced fns only after schemas exist),
not hardcoded sequence.

**Verdict: one engine, modes-as-data, wrapped in one convergence loop.** The risk (§8) is
that the generic gate/renoise loop is too rigid for Stage 1's open-ended adapt loop; if
so, `:design-schema` carries a `:seon.dg.mode/loop-kind :converge-on-satisfied` flag (one
field, not a fork) and the engine branches on it. The deeper risk is non-convergence /
oscillation, handled by the §5.3 monotonicity guard.

---

## 7. The experiment plan

Ordered, with the kill-gate at the front. The metric discipline: every run lands in the
gym scorecard (`scenario × git-sha`, `driver.cljs`) so a knob sweep is a MOVED number,
not an anecdote.

### E0 — clamp holds (prerequisite, already on the worker plan)

Run `clamp_smoke` (CLAUDE.md plan §1). PASS = `all_held` true. Without this nothing
below is possible. This is the worker owner's in-flight experiment, not new here.

### E1 — FIRST KILL-GATE: infill-the-spec-slot

**The single experiment that decides whether modes are worth building.** Build the
`:defn-with-specs` scaffold for a handful of fns whose bodies are given (clamp the
`defn` + `:malli/schema` frame, leave `:in-spec`/`:out-spec`/`:body` as holes). Drive
the control worker `infill` with the multi-hole clamp.

- **Metric A (validity):** does the filled `:in-spec`/`:out-spec` PARSE as Malli and
  REGISTER without `:seon.fn/schema-error`? Target: ≥80% of attempts yield an
  instrumentable spec within `renoise.max-retries`.
- **Metric B (the discrimination — does infill BEAT a prompt?):** A/B the SAME fns:
  (arm 1) forced-spec infill, (arm 2) free completion prompted "write the
  `:malli/schema`". PASS the kill-gate iff arm 1's instrumentable-spec rate
  meaningfully exceeds arm 2's. This reuses the gym's `:infill-beats-ar` predicate
  shape (`seon-diffusion-interface-design-2026-06-28.md` §3b).
- **Metric C (speed):** wall-time + `denoise_steps` + `tokens_per_forward` per filled
  spec.
- **KILL:** if arm 1 ≈ arm 2 (infill gives no validity lift), modes-as-clamped-scaffolds
  are unjustified. Keep ONLY the dynamic-context half (the section-fns) and fall back to
  prompting. Report this honestly — it is the most likely failure (§8).

### E2 — schema→example→adapt convergence (Stage 1)

Drive `:design-schema` on a target ("model a small inventory domain"). Metric: does the
generated-data feedback loop CONVERGE (the schema stops changing / the model emits
`satisfied`) within K iterations, and is the final schema TIGHTER than the first
(measure: does `mg/sample` produce more domain-plausible values; a human/LLM-judge
rubric)? KILL: if showing generated data doesn't change the model's next schema (the
loop is a no-op), Stage 1's feedback premise is false — fall back to one-shot schema gen.

### E3 — per-step gate latency

Measure the cost of running `parse-forms` (+ `seon.eval/eval` for the `:eval` rung)
inside the stopping criterion at the worker's real step budget. The source-grounding
doc warns the full-logit trace is GB/step; the gate only needs the CHEAP argmax canvas
decode (`_takes_logits=False`, source-grounding §2.4). Metric: gate overhead per step
vs the ~12ms forward. KILL: if decode+parse+eval per step dominates the forward, run the
gate every Kth step (or once at the end of each K-step outer call), not every step.

### E4 — span re-noise actually fixes the right region

Drive `generate` to produce a deliberately-broken form, run the gym
`:eval-renoise-converges` predicate (`interface-design` §3b): PASS iff the parser
`:span` overlaps the known-bad region AND `span_to_positions` selects the right canvas
positions. Then the closed-loop version: re-noise those positions and confirm the retry
clears the gate more often than a blind full re-noise.

### E5 — full staged build end-to-end (single pass)

Drive all four modes in sequence on a real "build a small domain" gym scenario
(long-term-planning + DB-memory shape, per CLAUDE.md "Exercising agents"). Scorecard:
fns specced, generative tests passing, wall-time per stage. The first capstone demo.

### E6 — MULTI-PASS convergence (the iterative-refinement proof)

The experiment that validates the §1.4 frame: a build is passes that converge a namespace
under CREATE/UPSERT/RETRACT, with every fn staying specced+tested throughout.

- **Pass 1 (create):** drive `converge-namespace` on an empty target ns toward a goal
  ("an inventory domain: items with restock"). Outcome: schemas created, ~3 fns written
  with `:defn-with-specs`, each generatively tested. Scorecard (§5.3) → `converged? true`
  at pass-1 end.
- **Pass 2 (upsert + overwrite + retract):** change the goal — "items now carry a
  supplier ref; drop the dead `legacy-restock` fn". The pass must: UPSERT a schema (add
  the supplier attr → `:design-schema`+`:upsert`, seeded with the existing source),
  OVERWRITE a fn whose body now reads the new attr (`:defn-with-specs`+`:upsert`), and
  RETRACT the dead fn (`:retract`, a `[:db/retract …]`, no generation).
- **The MEASURED claim:** across pass 2, the namespace stays coherent — the scorecard's
  open-item count trends DOWN (monotonicity guard, §5.3), and at pass-2 end EVERY
  surviving fn is still `:seon.fn/specced?` AND `last-passed-at > last-failed-at` (the
  upsert that touched the schema did not silently break a downstream fn's spec/tests
  without that fn re-appearing as an open item and being re-converged).
- **Predicate:** `:namespace-converges` — PASS iff `converged? true` at pass end AND the
  open-item count did not increase across the final two passes without an explicit
  retract. RED if the loop oscillates (pass N re-opens what pass N-1 closed, indefinitely)
  or leaves any fn unspecced/untested.
- **KILL:** if pass 2's schema upsert reliably leaves downstream fns broken-and-unnoticed
  (the missing-work sections don't re-surface them), the reactive-convergence premise
  fails — the namespace doesn't self-heal and we need explicit dependency tracking.

**Gym fit:** every experiment is a gym scenario (EDN) + a predicate +
scorecard (`scenario × git-sha`, or `namespace × git-sha × pass-n` for E6), exactly the
diffusion-gym plan (`seon-diffusion-interface-design-2026-06-28.md` §3). New predicate
kinds: `:spec-infill-instruments` (E1-A), `:infill-beats-ar` (E1-B, already specced),
`:schema-loop-converges` (E2), `:gate-latency-under` (E3), `:eval-renoise-converges`
(E4, already specced), `:namespace-converges` (E6). All mechanical, reading the worker
`output` or the post-run program graph — a predicate that throws scores RED
(`driver.cljs:770`), never a silent pass.

---

## 8. Skeptic section — where this breaks

Honest failure modes, roughly in order of how likely they are to sink the idea.

1. **Forced-spec infill may produce SYNTACTICALLY valid but SEMANTICALLY useless
   specs.** A clamped `:malli/schema` frame guarantees the model writes *a* spec; it
   does NOT guarantee the spec is a faithful contract for the body. The model can fill
   `[:map]` (an empty map that validates everything) or a `[:map [::x :any]]` that the
   no-`:any` rule bans but that still instruments. The instrument rung catches a spec
   that THROWS, but not a spec that is vacuously permissive. This is the E1 kill-gate
   AND the deepest risk: if forced specs are vacuous, the whole "very high quality by
   construction" claim is hollow. Mitigation idea (HYPOTHESIS): the generative-test rung
   (Stage 3) indirectly pressures the spec — a too-loose spec generates garbage inputs
   that fail the property — but that pushes the quality check downstream, not "by
   construction" at Stage 2. State plainly: clamping forces PRESENCE of a spec, not its
   QUALITY.

2. **Per-step eval may be too slow.** The `:eval` and `:instrument` rungs run real
   ClojureScript compilation + Malli in the stopping criterion. At ~12ms/forward and a
   real step count (the worker's `denoise_steps`), decode+parse+eval EVERY step could
   dominate. Grounded mitigation (source-grounding §2.4, §5 item 1): use the CHEAP
   argmax decode, run the `:parse` rung every step (no model call, fast) but defer
   `:eval`/`:instrument` to the END of each K-step outer `generate()` call, not
   per-step. E3 measures this. If even per-K eval is too slow, the gate becomes a
   post-generation check, losing the "stop early when correct" benefit but keeping the
   "re-noise on failure" benefit.

3. **Canvas-length ceiling on big scaffolds.** The `:defn-with-specs` scaffold (two
   `register!` lines + the `defn` + spec frame) plus the holes may exceed one canvas
   (`canvas_length`, the inner-loop unit, `generation_diffusion_gemma.py:638`). A
   multi-canvas scaffold spans the OUTER block-AR loop, and the clamp/infill bidirectional
   co-conditioning is WITHIN a canvas — a slot in canvas 2 cannot co-condition on a
   clamped suffix in canvas 1 the way single-canvas infill does. So large scaffolds
   partially lose the infill advantage. Mitigation: keep scaffolds canvas-sized;
   decompose a big fn into a `register!`-only mode + a `defn`-only mode (two smaller
   guided generations) rather than one giant scaffold. Flag: measure `canvas_length` on
   the live worker before committing to scaffold sizes.

4. **The regime-change trap (already grounded, easy to fall into).** "Denoise to step K
   then check" tempts shrinking `max_denoising_steps`, which COMPRESSES the temperature
   ramp (`:311`) → a different generation, not a checkpoint. The design uses the ABC
   stopping-override + keep-N approach (§4.3/§4.5), but any implementer who "optimizes"
   by lowering the step cap silently changes the regime. This is a documented footgun,
   not a maybe.

5. **Modes may not beat plain prompting (the meta-risk).** A capable model prompted
   "write a fully-spec'd defn, here are the related fns and example data" might match the
   whole mode apparatus. The parser-oracle doc already found the repair collar's value
   is NULL on capable AR models (`parser-as-generation-oracle` "Live acme drive") — the
   mechanism earns its keep on NOISY generation. The bet is that DiffusionGemma's
   per-step commits ARE noisy enough that clamp+gate+renoise beats prompting. If
   DiffusionGemma turns out to write clean Clojure unprompted, modes are over-engineering
   — E1 metric B is the explicit test. Keep the dynamic-context half regardless (it is
   cheap and clearly helps); the CLAMP/SCAFFOLD half is the part on trial.

6. **`mode/enter` parsing is a soft contract.** The model must emit a well-formed
   sentinel; a malformed one is caught by the parser but then we have no mode to enter.
   Early on the model won't reliably emit `mode/enter` at all without prompting/examples
   — so "the model invokes a mode" is itself a learned behavior, and bootstrapping it may
   need few-shot examples in the always-on context (which costs tokens). Less risky than
   1-2 but real: the invocation layer is the least-grounded piece (everything below it is
   proven primitives; the sentinel-dispatch is a design choice riding the existing
   parser).

7. **Self-conditioning / `past_key_values` reuse across re-noise retries is untested.**
   The control loop feeds `out.past_key_values` back (source-grounding §5 item 6), but
   re-noising spans WITHIN a committed prefix may interact badly with a KV cache built
   from the old tokens. This is an implementation hazard for E4's closed loop, flagged so
   it is measured not assumed.

8. **Oscillation / non-convergence of the refinement loop (the iterative-frame risk).**
   §1.4's pass loop assumes monotone progress, but an UPSERT can re-open closed work:
   pass N tightens a schema and breaks a fn pass N-1 had specced+tested, so the
   "untested fn" section re-populates. If two passes keep re-opening each other's work,
   the loop never converges. The §5.3 monotonicity guard (open-item count must trend down
   or halt-and-flag) bounds it, but the guard is a HEURISTIC — a genuinely entangled
   namespace (a schema change that legitimately requires re-converging five fns) looks
   like oscillation. Harder failure: reactive self-healing assumes the missing-work
   sections actually re-surface a fn that a schema upsert silently broke; if instrumentation
   doesn't fail until the fn is CALLED with the new shape, the break may not appear as an
   open item until exercised — E6's KILL gate. Mitigation if it bites: explicit dependency
   edges (`:seon.fn` → schemas/fns it references) so an upsert can mark dependents dirty —
   but that is stored derived state, which reactive-context warns against, so prefer
   re-deriving "what does this fn reference" from `:seon.fn/source` at pass time.

**Bottom line:** the primitives are real and proven (clamp, infill, parser oracle, eval
cage, the program-graph spec detector). The mode ABSTRACTION over them is sound and
data-clean (one engine, modes-as-rows). The two things that could make it not worth
building are (1) forced specs being vacuous and (2) modes not beating prompts — both
fall to the SAME first experiment, E1. Build E1, read the number honestly, and let it
decide whether to build the rest.

## Entry points (depth)

- [[../CLAUDE]] — live worker state, the capability ladder, the deploy/run loop.
- [[transformers-diffusion-source-grounding-2026-06-28]] — the per-step seam
  (`:1034`), the stopping-criterion ABC (`:466`/`:1207`), the temperature caveat
  (`:311`), the outer-loop-of-K-step pattern.
- [[parser-as-generation-oracle-2026-06-28]] — the measured parse/eval ladder
  (92.7% / 91.5% / 93.5%) the quality gates rest on.
- [[seon-diffusion-interface-design-2026-06-28]] — the `:diffusiongemma` control
  adapter + the gym predicate machinery the experiments extend.
- `tmp/flash-diffgemma/diffgemma_common.py` — `ClampLogitsProcessor` (`:35-78`),
  `build_offset_map`/`span_to_positions` (`:184-222`).
- `src/seon/repl/internal.cljc` — `parse-forms`, `:span`/`:error-kind`/`:source`
  (`:561-677`).
- `src/seon/agent.cljs:198-303` — the `:seon.fn`/`:seon.schema`/`:seon.ns` program-graph
  entities (`:seon.fn/spec` is the missing-spec detector).
- `docs/seon/concepts/reactive-context.md` — the section-fn pattern the dynamic-context
  strategy IS.
