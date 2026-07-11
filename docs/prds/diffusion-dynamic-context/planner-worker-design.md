---
type: prd
status: draft
tags: [prd, agent]
---

# Planner/worker — the diffusion agent lives inside `my.plan` (P7 design)

**Owner direction (2026-07-11):** the diffusion agent must FULLY integrate
planning into the system. A smarter frontier model hands down a plan as a
normal text block; the diffusion agent BUILDS that plan as `my.plan`
datoms, REFINES it as reality bites, and keeps it IN FOCUS while doing the
actual work — defining functions, running functions, processing data —
across multiple turns. The measured target is no longer single-form eval:
it is **goal completion at a time budget**.

The glyph/menu SELECTION channel is frozen where P6 left it (measured:
uptake .019, 3 fires — well-built, marginal). Menus stay as passive
context; no further investment there. The plan tree is the steering
surface now.

## Why this wins (the evidence chain)

- P6 proved the binding constraint is **what's in context**, not model
  steering: putting task-required fns on the menu moved fires from 0/13
  task-relevant to 3/3. A plan step that names its intent IS the
  strongest possible "menu source" — it makes every render task-relevant
  by construction.
- The within-turn loop (denoise → oracle → lock/harvest → repair) is
  proven: .633 outcome / 4.8 s vs DeepSeek .40 / ~23 s on the fresh
  corpus. What is UNMEASURED is stringing turns together toward a goal.
- Every mechanism already exists: `my.plan` (plan!/step!/active!/done!
  with `::expect` gating, `::pace :multi-session`), the `:plan-ledger`
  ctx block (▶ active / ☐ open, done dropped from render), per-agent
  provider routing (`::agent-provider` in `seon.ai`), and the typeahead
  step-loop provider. **Zero new mechanisms.** New capability = the
  worker agent actually USING them, plus one bench task that measures it.

## The two roles (both are just agents)

- **Planner** — a frontier-model call (DeepSeek or Muse via the existing
  provider routing; or simply the orchestrator/owner) produces a SHORT
  plain-text plan: goal, ordered steps, each step's falsifiable outcome.
  Prose, not code. It arrives as a normal message — no special channel.
- **Worker** — the diffusion agent (`SEON_AI_PROVIDER=typeahead`). Its
  first job on receiving plan text is to author
  `(my.plan/plan! {::title … ::goal … ::children […]})` — nested steps,
  `:ref`/`:after` deps, `::expect` per step. **Plan authoring is itself
  the first test of the worker**: turning prose into one correct nested
  `plan!` form, eval-proven like any other form.

## The turn loop (keep the plan in focus)

1. RENDER: the plan ledger derives from the DB — ▶ = the `:active` step
   (`my.plan/active!`, one position per agent, a real datom, not a
   glyph), ☐ = open, done DROPPED. The active step's title/description
   text rides the render; program-graph retrieval over that text (not
   recency) supplies the fn candidates.
2. WORK: the existing step loop works the ACTIVE step only — define the
   fn, run it, process the data. Locked forms harvest as today.
3. CLOSE: `done!` only when the step's `::expect` is verified live (the
   docstring already says so: done = the outcome holds, not "I did a
   thing"). Then `active!` the next ready step (`my.plan/next`).
4. REFINE: discoveries mid-run are `step!`/`needs!`/`reopen!` calls —
   ordinary forms in the same loop, no mode.

Render budget rule: a step must be workable inside a ≤4k-token render
(~1.7 s/step prefill). The PLANNER owns decomposition granularity — steps
too big to render small are a planner defect, and the bench will show it
as blown time budgets.

## The plan document round-trip — `my.plan/reconcile!` (owner, 2026-07-11)

The revival of verified-canvas v2's variant B ("the canvas plans
itself"), sharpened: the plan tree is also editable as ONE document, and
the write-back is one function with three callers.

- **The document** is a canonical EDN projection of the agent's OPEN
  tree (`my.plan/tree`'s shape, made round-trippable): every node
  carries its `:my.plan/id`; done steps are EXCLUDED (derive-don't-
  store — an edit can never un-happen history).
- **`my.plan/reconcile!`** takes the edited document (or frontier
  markdown, parsed leniently to the same shape), diffs it against the
  live tree, and compiles the delta into ordinary plan transactions.
  Identity rules: node with id → update in place (title/description/
  expect/parent/needs edits); node without id → minted; OPEN node
  absent from the document → dropped. Returns
  `{::ok? true ::root … ::diff {::added n ::dropped n ::updated n}}` —
  the `⟹` result line IS the receipt, and the next render's plan block
  IS the confirmation (reactive context; no notification mechanism).
- **The three callers, one mechanism**: (1) the frontier model's
  handed-down markdown — full encode all at once, and later re-hands
  reconcile as UPDATES (unchanged nodes keep identity/status);
  (2) the diffusion agent editing the document on the CODE-BUFFER —
  the diffusion-native operation (whole-document refinement in one
  denoise pass, which AR models structurally can't do): ids CLAMPED
  (identity unforgeable by construction), free regions = titles/
  expects/structure, the June plan-phase grammar gate + oracle proof
  before lock; (3) the agent calling `reconcile!` from code like any
  function.
- **Boundary rule (owner, 2026-07-11): `my.plan/reconcile!` carries
  ZERO diffusion knowledge.** It is general planning semantics —
  document in, diff, transactions, receipt out — usable by any agent
  on any provider. Everything diffusion-specific (rendering the
  document onto the code-buffer, the id clamp map, the plan-phase
  grammar gate, the denoise/lock driver) lives diffusion-side
  (`src-diffusion` + `seon.diffusion.*`) and CALLS the general
  function like any other client. A clamp/glyph/buffer parameter
  appearing in `my.plan` is the violation.
- **The document is the ARGUMENT (owner, 2026-07-11)** — the general
  variant is any model simply emitting
  `(my.plan/reconcile! {::tree <edited-document>})` (or `::markdown`)
  as ordinary code output; the diffusion buffer mode is just a better
  editor for producing that same argument. Two update shapes, both
  general, no third: DELTAS = the existing `step!`/`done!`/`move!`/
  `drop!` functions; WHOLE-DOCUMENT = `reconcile!` with the document
  as argument.
- **Authoring = reconciling against an empty tree** — so W1's
  plan-authoring, frontier import, and mid-run re-planning are ONE
  code path, not three.

- **Draft-head argument affordance — the GENERAL rule (owner,
  2026-07-11: "smart detection, not gated on specific behavior")**:
  the diffusion driver never detects "a plan command". Instead, ONE
  computed rule: the cursor oracle already resolves the draft head's
  registered request schema (op:"cursor", 3–5 ms — how EXPAND builds
  templates today); a schema PROPERTY on an argument (e.g.
  `:seon.render/prefill-fn` on `::reconcile-request`'s `::tree` entry,
  registered where the data lives) names the projection function for
  that argument's CURRENT value. When the head resolves to a fn whose
  argument declares a prefill, the driver pre-fills the template hole
  with the live projection (plus the buffer rules the schema implies —
  ids clamped for the plan document), so the model EDITS instead of
  regenerating. `my.plan/reconcile!` is instance #1; any future
  document-shaped fn gets the affordance by declaring the property —
  no fn list, no mode, no plan knowledge in the driver (same
  registry-driven pattern as the slot masks).

Sequencing: `reconcile!` + markdown parse land in W1 (it is W1's
write-back). The clamped-id whole-buffer edit mode + the draft-head
prefill affordance are the W2 headline once the focus loop works.

## Win conditions (per the exercising-agents doctrine)

- **Continuity**: a mid-run `bin/seon restart pod` (acme) and the worker
  resumes from the open ▶/☐ state without re-planning.
- **Goal completion at a time budget** (30 s / 60 s tiers), not
  single-shot parity.
- **Frontier economy**: frontier tokens spent per completed task — the
  planner is consulted once (plus optional re-plan on `stuck`), never
  per-form.

## Measurement (inside src-inspect-ai — never a fourth harness)

One new task: `planner_worker` (name final at implementation). 3–5
multi-turn goals shaped by the exercising-agents rules (long-term
planning + DB memory; goals stated as outcomes, never API calls).
Arms: (1) frontier plan text + diffusion worker; (2) diffusion agent
alone, no plan text; (3) frontier model alone doing the whole task
(reference). Metrics: outcome at time budget, wall-clock, frontier
tokens per completed task, plan integrity (steps closed with `::expect`
verified / steps closed), restart-resume pass. k=3 seeds 100–102,
sha-stamped evidence dir + ledger rows, worker sha verified first.
One measurement per unit — the bench falsifies; it is not the work.

## Phases (each ends in a live proof on acme)

- **W1 — plan authoring**: prose plan text → correct `plan!` form,
  eval-proven; refinement functions exercised (`step!`, `done!` expect-gated,
  `reopen!`). Live proof: a real plan tree in the acme DB authored by the
  worker from handed-down text, visible in the ledger render.

  **W1 unit brief (implementable now):**
  - *Stimulus*: a message to a typeahead-provider agent on acme carrying
    a short frontier-style plan (goal line + 3–4 ordered steps, each
    with a falsifiable outcome sentence). Author two fixture texts
    (planning-shaped + db-memory-shaped per the exercising-agents
    rules); they live with the bench task, never in any context block.
  - *Expected behavior, no new mechanism*: the worker's step loop
    produces one `(my.plan/plan! {::title … ::goal … ::children […]})`
    form (nested `:children`, `:ref`/`:after` where the text implies
    order, `::expect` per step), which the pod evals through the normal
    turn pipeline → datoms land.
  - *Build surface (src-diffusion + bench only)*: whatever the step
    loop needs to reliably emit ONE large nested form — likely GROW
    budget for a multi-line map, the code-buffer working a form bigger
    than one hole. Measure the failure mode first; change second.
  - *Acceptance*: (1) `(my.plan/tree {})` on acme shows the authored
    tree matching the text's steps 1:1; (2) `::expect` non-empty on
    every leaf; (3) the next render's plan ledger shows the ☐ steps and
    `active!` takes one up; (4) zero hand-edits between text and
    datoms; (5) fresh store per drive (planner-bug rule, runbook).
  - *Falsification*: run the same texts through the plain guided
    provider — if it lands correct `plan!` forms at a similar rate,
    W1's step machinery added nothing for this shape (report the
    number; the value would then be W2's focus loop, not authoring).
- **W2 — keep-in-focus execution**: the step loop keyed to the ▶ step;
  active-step-derived retrieval feeding the render; `done!`/`active!`
  transitions driven by the worker. Live proof: a 3-step plan executed
  end-to-end in one session, each `done!` expect-verified.
- **W3 — multi-turn + restart**: `::pace :multi-session` goal, pod
  restarted mid-run, worker resumes from the ledger. Live proof: the
  restart drill passes uncoached.
- **W4 — measure**: the `planner_worker` task, one full run, honest
  verdicts against the win conditions.

## Open questions (resolve during W1, owner rulings where marked)

- `:plan` vs `:plan-ledger` block overlap (both render open steps today)
  — OWNER ruling pending; W1 should land with ONE plan surface.
- Where the planner call lives for the bench: a real second agent via
  `::agent-provider`, or host-side plan text in the corpus. Start
  host-side (deterministic, cheaper), graduate to live two-agent.
- Whether `stuck×2` in the step loop should trigger a re-plan request
  (frontier consult) or just `reopen!` + hint. Start with the cheap one.

## Non-goals

- No new glyphs, no selection-channel tuning, no threshold work.
- No new config surface (the policy row + ctx blocks suffice).
- No benchmark iteration loops — the bench runs once per unit to
  falsify, per the owner's "not benchmark maxing" directive.
