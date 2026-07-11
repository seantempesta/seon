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
