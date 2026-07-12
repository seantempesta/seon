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
  with `::expect` gating, `::pace :multi-session`), the `:plan` ctx
  block (▶ active / ☐ open, done dropped from render — the ledger
  contract folded in 2026-07-11), per-agent
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

## The separation of authority (owner design session, 2026-07-11 late)

Not "high vs low level" as a prose rule — **editability ZONES in the
one plan tree, enforced mechanically**:

- **Frontier = the skeleton, rarely.** Root `::goal` + the top layer of
  milestone steps, outcomes with falsifiable `::expect`s, never
  function names. Runs at task start + on escalation only; its
  markdown lands via `reconcile!` — strategy arrives as DATA.
- **Diffusion = the flesh, every step.** Below the frontier layer: the
  per-step plan pass may SPLIT a frontier leaf into substeps, SHARPEN
  expects into checkable form, reorder ITS OWN subtree, take ▶,
  propose closure.
- **The oracle = "done."** `done!` stays expect-gated and eval-proven —
  marking things off is a PROOF event, not either model's judgment.
- **Enforcement is clamps, not prose:** plan nodes carry authorship
  via tx provenance; frontier-authored titles/expects render CLAMPED
  in the plan-pass buffer — diffusion can nest under them and mark
  status but cannot rewrite or drop strategy, by construction (the
  unforgeable-ids trick, one level up).
- **Escalation = derived DB state:** stuck×N / repeated
  expect-failure is a query; rows → the planner re-plans THAT subtree
  via `reconcile!`, clamped to its own zone. No ad-hoc frontier calls;
  frontier spend stays proportional to genuine strategic trouble.

One tree, one function (`reconcile!`), two editability zones, closure
by proof. Each side is trusted only with decisions it can be
mechanically held to.

**Escalation BUILT (2026-07-12, task #20 — the W3-evidenced gap).**
All derived, nothing stored (reactive-context): the code lives with the
`:plan` block in `my.plan.internal`.

- *The flag query* (`my.plan.internal/escalation`): the ▶ `:active`
  step is flagged when, since the tx of its current `:active` assertion
  (the datom's own tx — no history walk, no stored `activated-at`), the
  agent's eval log shows ≥ N failures sharing a ROOT with no same-call
  success between. Root = `[head-sym error-kind]` — the head symbol of
  the eval's source (structural `read-forms`, never message parsing) +
  the `:seon.error/kind` read from the persisted `:seon.eval/error-data`
  envelope EDN. A success of the SAME call breaks the streak (progress
  on that root); an unrelated success (the W3 wedge's interleaved defn
  redefinitions) does not. N = `escalation-stuck-n` (default 3; the W3
  wedge ran 8+).
- *The section*: a `STUCK ▶` band in the `:plan` block (`plan-body`
  band 2 — zero new manifest rows, so every existing agent gets it) —
  the step, the repeated failure envelope ONCE, and the consult status.
  Vanishes when the wedge breaks.
- *The consult* (`maybe-consult!`, fired post-turn by
  `seon.agent.loop/run-loop!`): once per flag EPISODE — episode
  identity = the streak's first failing eval id, embedded as a marker
  line in the consult message, so fired-once is a message-log read,
  never a stored notified-flag. The planner is DERIVED: a live
  non-worker agent whose `seon.ai/resolved-config` provider is frontier
  (not `:diffusiongemma`/`:typeahead`); among several, the tx-provenance
  AUTHOR of the flagged step wins (the W3 shape — the planner authored
  the plan). No planner ⇒ no-op + a rendered note. The message rides
  the existing `message!` path (hops/wake/provenance intact).
- *Planner turn economy* (the W3 4-idle-turn leak): root cause — a
  message-delivered ask carried no completion condition, so nothing in
  the planner's context marked the task's end and the run idled to its
  bounds. Fix is context, not scold: the consult ASK carries its own
  completion contract ("when the reconcile receipt renders, the ask is
  fulfilled — `(complete …)` in that same turn").
- Suite 1231/5627/0/0 (+6 escalation behavior tests in `my.plan-test`:
  flag at N / not N−1, same-call-success break, since-active window,
  reactive render/vanish, once-per-episode consult, no-planner no-op).
- *Live acceptance (acme, as-grown store, 2026-07-12 ~04:39–04:46 UTC,
  0 core faults, 0 coaching)* — REPLAY-style, stated plainly: the wedge
  itself was synthetic (3 failed-eval rows with the W3
  `schema/register!`-misuse signature + `active!` on `XlN-2607112332`,
  transacted via the wire writer with worker tx provenance onto the
  goal-A worker `oOF-2607112331`'s real history); everything from the
  flag onward ran organically. Proven end-to-end: (1) the worker's next
  turn's byte-exact prompt (blob `fd3b8194…` line 692) carries the
  `STUCK ▶` band naming root + planner; (2) the flag correctly IGNORED
  the 7 pre-`:active` W3-era failures (since-active window live);
  (3) planner derivation picked `IHk-2607112331` — the tx-provenance
  AUTHOR of the flagged step — over 8 other frontier candidates;
  (4) post-turn consult fired ONCE (message 04:41:25, marker episode
  `ew1-2607120450`; still exactly 1 row after 3 more worker turns);
  (5) the band flipped to "has been consulted" in the very next prompt
  (blob `79d174e9…` line 701 — derived from the message log);
  (6) Muse read `document`, wrote back `reconcile!` → receipt
  `{:diff {:added 0 :dropped 2 :updated 1}}` — the updated node IS the
  flagged step, its description/expect rewritten to teach the exact
  `[:map …]`-not-`(map …)` fix; (7) one guidance `message/agent` to the
  worker; (8) `(complete "re-planned XlN-2607112332")` in the SAME turn
  as the receipt — `halt function — complete`, ZERO post-fulfillment
  idle turns (the W3 leak class, gone).
- *Honest negatives from the drive*: (a) Muse spent ~16 exploratory
  turns (~4.7 min, document/tree/schema-query loops) BEFORE fulfilling
  — a pre-fulfillment thoroughness cost, distinct from the
  post-fulfillment idle leak the contract fixed; if it matters, the
  consult ask (not scold text) is the lever. (b) The planner's
  `reconcile!` document was SUBTREE-scoped, so the compile dropped the
  worker's 2 open parentless "continue" address steps (`:dropped 2`) —
  the known W3 reconcile-scoping hazard (#16-adjacent) with a second
  live data point; the basis/scope argument for `reconcile!` remains
  the open design question, not patched here.

## The per-step plan pass (owner, 2026-07-11 late — the W2 headline)

**Owner directive: "if planning via diffusion is cheap and high
quality, run it at the beginning of EVERY step — agents are forced to
use it to think through what they're doing."** The mechanism: before
working the ▶ step, the driver runs a PLAN PASS — a small code-buffer
whose template hole is prefilled with the plan document (ids clamped,
plan grammar gated), with the goal + the frontier model's handed-down
guidance riding the render as prose. The model EDITS: split a too-big
step, sharpen the active step's `::expect`, reorder, drop a dead
branch, take the next `active!`. `reconcile!` writes it back; the `⟹`
diff receipt + the re-rendered `:plan` block are the confirmation. Then
the WORK loop runs on the (possibly revised) ▶ step.

- **Cost budget (the affordability gate):** the pass must stay ~2–3 s —
  a small render (the plan document is a few hundred tokens, NOT the
  full context) + a handful of decode forwards. Measure the real cost
  first; if a pass exceeds ~5 s median it is not "every step" material
  and falls back to on-`stuck`/on-`done!` only.
- **Why diffusion specifically:** AR regenerates the whole document to
  change three lines (full output-token cost, no identity guarantees);
  the code-buffer edits in place with ids unforgeable and structure
  grammar-gated. This is the diffusion-native operation applied at the
  highest-leverage point.
- **No new mechanism:** the pass = the draft-head prefill affordance
  invoked driver-side with head `my.plan/reconcile!` at step-open. A
  no-change pass (model leaves the document as-is) is valid and cheap —
  the diff receipt says `0/0/0` and the loop proceeds.
- **Measured, not assumed:** the `planner_worker` bench gains an arm —
  plan-once vs plan-every-step — scored on outcome-at-time-budget and
  plan-integrity. Kill criterion: if plan-every-step doesn't beat
  plan-once at the same budget, it demotes to on-stuck re-planning.

Sequencing: `reconcile!` + markdown parse land in W1 (it is W1's
write-back). W2 = the per-step plan pass (the clamped-id plan-document
edit + the draft-head prefill affordance, driver-wired at step-open)
once the focus loop works; the plan-once vs plan-every-step arm rides
the W4 bench.

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

  **W1 SHIPPED (2026-07-11 late) — `reconcile!` + the authoring live proof.**

  - *Landed (general planning code, zero diffusion knowledge)*:
    `my.plan/reconcile!` (`::tree` EDN OR `::markdown`, lenient parse:
    headings/nested lists/flat numbered lists; `[id]` prefixes keep
    identity; `— expect:` suffix or trailing second sentence → `::expect`;
    checkbox/enumerator markers stripped), `my.plan/document` (the OPEN
    projection — `tree`'s shape, done pruned; `tree-pattern` gained
    `::description` so ONE canonical node shape round-trips), and ONE
    compiler: `internal/compile-reconcile` — `plan!` now delegates
    (`compile-plan` = reconcile-against-empty, one code path). The
    `::tree` entry carries `:seon.render/prefill-fn 'my.plan/document`
    (the W2 affordance, declared only). 8 new tests in `my.plan-test`.
  - *Live proof (acme, as-grown store, worker `d43be833dac3`, k=2/arm,
    zero core faults)*: **Muse (frontier/falsification arm): 3/3 correct
    trees**, choosing the NEW `reconcile!` `::markdown` path unprompted
    in 3 of 4 authoring events (the one plan!-EDN event inlined
    "— expect:" into titles; the markdown path parsed expects cleanly) —
    1:1 steps, `::expect` on every leaf, 213–260 s to authored+executed.
    **Typeahead worker: 0/2 whole-document authoring** — both runs
    planned INCREMENTALLY via `step!` (1–3 flat condensed steps, expects
    on 1 of 2 runs), did real schema/store work, took a step `:active`,
    and timed out at 540 s (~60–67 s/turn on the grown store). The ▶/☐
    ledger + authored steps verified in the byte-exact prompt blob.
  - *Falsification verdict, plainly*: the frontier lands correct plan
    trees at a far higher rate than the diffusion worker — W1's measured
    value is the GENERAL function surface (frontier import via
    `reconcile!` markdown works end-to-end today) + W2's focus loop, NOT
    diffusion whole-form authoring. The "ONE large nested form" build
    (GROW budget / buffer capacity) is the remaining gap; first blocker
    found and fixed: the CAL length probe crashed on a hole clipped past
    the code-buffer end (`mx.max` zero-size, `cursor.py`
    `_first_step_confidence` — an empty span now scores 0.0).
  - *Drive-hygiene findings*: acme's global provider resolves Muse (env
    `SEON_AI_PROVIDER=typeahead` is shadowed by the manifest config
    row) — the typeahead arm needs the per-agent
    `:seon.ai/agent-provider :typeahead` overlay; keep the warmup
    `timeout_ms` SHORT (~12 s) — a 180 s warmup let Muse wander 15
    turns and author an unrelated plan. Acceptance (5) fresh-store was
    superseded by the as-grown-testbed ruling (store-scale OOM close,
    2026-07-11). Fixtures:
    `src-inspect-ai/src/seon_inspect/planner_worker_fixtures.py`.
- **W2 — keep-in-focus execution**: the step loop keyed to the ▶ step;
  active-step-derived retrieval feeding the render; `done!`/`active!`
  transitions driven by the worker. Live proof: a 3-step plan executed
  end-to-end in one session, each `done!` expect-verified.

  **W2 per-step plan pass BUILT + measured (2026-07-11 late).**

  - *The affordance (general, instance #1 = reconcile!)*: the step wire
    gained `prefills` (`head → template` with the new `"prefill"`
    segment kind) — derived seon-side by ONE computed rule
    (`seon.ai.typeahead/prefill-affordances`: registry scan for
    `:seon.render/prefill-fn` entry properties + program-graph join to
    the fn whose spec input IS that request schema; the projection fn
    resolves via `seon.instrument/find-js-var`, its injectable keys
    filled per its own spec). The driver (`cursor.py`) carries zero fn
    knowledge: an OPENED call to a listed head (cursor-oracle
    `slot-kind.head`, args not begun) skips the open-tail denoise and
    fills the template (`PREFILL-EDIT` arm); the pass = this affordance
    invoked at step-open by seeding `(head ` as the draft
    (`:seon.typeahead/plan-pass` policy knob: `:every-step` default /
    `:on-stuck` — fires once at the first stuck round / `:off`; the
    organic wire rides regardless).
  - *Two mechanical findings (live-measured, fixed at the root)*:
    (1) free-region document text invited RESTRUCTURE — one denoise
    round merged nodes and rewrote `:my.plan/_parent` to
    `:my.plan/steps`, unbalanced EDN every seed. Fix = **clamp
    structure and vocabulary** (braces, key names, ids, and every
    foreign-authored entry per `:seon.db/agent-id` tx provenance);
    ONLY the caller's own scalar VALUES are editable holes. (2) plain
    noise-renoise washed the prefill out after forward 1; slack
    newlines drew accepted junk (`: * :`). Fix = **sticky prefill**
    (unaccepted positions renoise to their INIT ids — unchanged by
    construction) + `PREFILL_SLACK 0` (growth stays with the DELTA
    functions `step!`/`move!`/`drop!` — the design's other update
    shape; the v1 pass is SHARPEN-only).
  - *Clamp-simplification choice (documented per the W2 brief)*:
    authorship is per-ENTRY tx provenance (the current datom's
    `:seon.db/agent-id` vs the editing agent) — mechanical, no v1
    shortcut needed; unattributed datoms clamp (unverifiable is
    unforgeable).
  - *Affordability (the gate)*: synthetic 3-node plan on the real
    worker — **0.83–0.89 s worker gen / 4 forwards, 3/3 seeds locked a
    parse-clean `reconcile!` form carrying real small edits** (title
    sharpening), well under the 2–3 s target; the all-clamped
    degenerate pass (nothing editable) costs ~0.03 s. Verdict:
    `:every-step` STANDS at small-document scale; the doc-size guard
    (`plan-pass-doc-token-budget` 190 est-tokens, the CL=256 buffer
    reality) SKIPS the pass for oversized documents — scope-down
    (active-subtree documents) is the W3-adjacent follow-up.
  - *No-change semantics*: a pass whose locked form equals the template
    text modulo whitespace is DROPPED (cheaper than a 0/0/0 receipt —
    zero eval, zero transcript tokens; the unchanged `:plan` block is
    the confirmation). A changed form is PREPENDED to the reply and
    threads into `committed`, so the diff receipt rides the transcript
    as its `⟹` row and the work loop sees the edit.
  - *Tests*: 5 new driver pytests (prefill segments, sticky workspace
    init, head-match rules, lock + broken-edit-keeps-draft) — 73
    passed; 5 new cljs tests (affordance derivation, clamp zones,
    pass-at-open, no-change drop, `:off`/`:on-stuck` knob).
  - *Live proof (acme, as-grown store ~18k-token renders, worker
    `50c1163bb3b3`, agent `qKW-2607112253`, 0 core faults)*: the pass
    fired at EVERY step-open across 7 turns. Wall per pass (worker
    `gen_s`, from the `:seon.typeahead/plan-pass? true` step rows):
    **0.027 / 0.038 / 0.040 s** (all-clamped degenerate no-change — the
    only open step was the user's address step, zero editable holes,
    0 forwards), **0.53 s / 2 fwd** (full 173-est-token document,
    no-change → form DROPPED), **2.35 s / 10 fwd** (real edit). The
    edit pass locked a parse-clean `reconcile!` that EVAL'd through the
    normal pipeline — receipt on the transcript:
    `{:my.plan/ok? true :my.plan/root "planw2root0001"
    :my.plan/diff {:added 0 :dropped 1 :updated 2}}` — and the DB
    shows the edits (root title → "Expense tracker MVP"; the typo'd
    own step title rewritten). Work proceeded after the pass (turn
    closed 4-ok: the reconcile + 3 work forms). **Gate verdict:
    median well under 5 s — `:every-step` STANDS**; the store-scale
    cost is the RENDER (unchanged ~60–90 s turns), not the pass.
  - *Found in the drive (report, not built)*: (1) **pass-doc
    staleness** — the pass document snapshots at step-open; a message
    arriving MID-TURN mints an open address step that is absent from
    the document, and the reconcile then legitimately DROPS it
    (`:dropped 1` above was exactly that). Candidate root fix:
    `reconcile!` scoping drops to nodes at-or-before the document's
    basis-t (needs a basis argument — a W3 design question, not a
    hack site). (2) **poll-cadence quantization** — the dg adapter's
    RunPod-era 3 s poll billed every local step ~3 s of wall for
    sub-second gens; fixed at the root in the same unit
    (`seon.ai.diffusiongemma/*local-poll-ms*` 250 ms for full-URL
    local workers, budget rescaled). (3) The doc-size budget
    (190 est-tokens) silently skipped passes once the forest grew
    past ~4 nodes + a long address step — the skip is correct
    (CL=256), but scope-down (active-subtree document) is needed
    before W3's multi-session plans; today the skip is silent, and a
    logged skip-reason would make the demotion measurement honest.
- **W3 — multi-turn + restart**: `::pace :multi-session` goal, pod
  restarted mid-run, worker resumes from the ledger. Live proof: the
  restart drill passes uncoached.

  **W3 RUN (2026-07-12) — restart-resume PASSES; goal completion does
  not.** Two builds + the workhorse drill on acme (as-grown store,
  worker `50c1163bb3b3`, zero core faults, zero coaching).

  - *Builds (seon.ai.typeahead, suite 1225/5600/0/0)*: (1) **pass-doc
    scope-down** — a document over the 190-tok budget now scopes to the
    ▶ active step's subtree + the root layer (titles/goal/status only
    for non-active roots) instead of silently skipping; the scoped
    document is an EDITOR VIEW only — on lock the edited scalar values
    MERGE BACK by node id into the FULL document and the emitted
    `reconcile!` form carries the whole open forest (`compile-reconcile`
    drops absent open nodes and retracts absent scalars, so a narrowed
    view must never reach it; for the same reason a scoped template is
    pass-only, never on the organic wire). (2) **skip-with-reason** — a
    document over budget even scoped records a marked step row
    (`:seon.typeahead/pass-skip "doc-over-budget (N tok)"`), never a
    silent skip. 3 new cljs tests (scope shape, loop-level merge-back,
    skip row).
  - *Two-agent shape — the full version ran*: Muse (planner agent,
    global provider) turned each host brief into the WORKER's plan via
    `reconcile!` scoped to the worker's `:seon.agent/id`, then
    `message/agent`'d it awake — 2/2 goals, 1:1 steps, `::expect` on
    every node, root `::pace :multi-session` (goal A), all nodes
    tx-provenance = planner ⇒ CLAMPED in the worker's pass (the
    authority zones held by construction: 9 passes over the
    Muse-authored tree, zero strategy edits possible, all no-change,
    0.014–0.73 s). Scope-down fired live on EVERY pass (full doc > 190
    tok from turn 0); zero pass-skips.
  - *Restart drill (goal A, reading-log, 4 steps)*: run 1 = 5 turns /
    104–134 s each, closed `:deadline-exceeded` (10-min run wall) with
    ZERO plan engagement. `bin/acme restart pod` (03:46:39→:52), then an
    uncoached "continue": the worker's FIRST post-restart reply was
    `(my.plan/active! {:my.plan/id "zHT…"})` — ▶ taken on exactly the
    ledger's `next ready` step, no re-planning, no repeat of closed
    work, turn numbering continued (5). Byte-exact proof: prompt blob
    `ef0742e38464d1c3…` (the surviving ☐ ledger, lines 693–705) → reply
    blob `9e072c597af08881…`. **Continuity win condition: PASS.** The
    #15 CAS race did not fire; the #16 staleness drop did not fire (the
    address step predates the pass snapshot).
  - *Goal completion: FAIL (the honest headline).* 12 worker turns
    (~19 min wall), goal A finished 1/4 frontier steps — and that close
    was FALSE: `done!` is docstring-gated only, and the worker closed
    the schema step while `(count :my.kb.book/title rows)` = 0; the
    final expects verify false (no book data exists). Plan integrity =
    0 expect-verified closes / 1 close. The worker wedged for 8+ turns
    redefining ONE broken form (`schema/register!` misused as a single
    call with a `(map [...])` blob) and re-calling it — the eval error
    envelope did not steer it. This is exactly the escalation case the
    §separation-of-authority design names (stuck×N ⇒ frontier re-plans
    the subtree) — unbuilt, and now drive-evidenced as the binding gap,
    together with a mechanical expect gate on `done!` ("closure by
    proof" is currently an honor system).
  - *Goal B (standup snapshot, 3 steps, no restart)*: plan landed 1:1
    (4 nodes) by the same two-agent path. Better plan discipline —
    `active!` in turn 0, a `done!` (again unverified-false: zero task
    rows exist), own substeps minted via `step!` (3 store-substeps
    under an own parent), `active!` advanced — but the run closed
    `:deadline-exceeded` at 7 turns / **10.2 min wall with the goal
    NOT completed** (wall-per-goal = DNF at the 10-min run budget).
    Plan integrity across both goals: 0 expect-verified closes / 2
    closes. Pass economics across both goals: 16 passes, 0 skips,
    all no-change; degenerate all-clamped 0.014–0.04 s, ▶-subtree
    docs (125–160 tok) 0.69–0.94 s / 3–4 forwards. The live drives
    never produced a pass EDIT, so the scoped merge-back write-back
    is test-proven only (loop-level cljs test), not yet drive-proven.
  - *Frontier economy*: one planner consult per goal — the plan landed
    in the planner's FIRST turn (~7 s, ~18 k tok in); the planner then
    burned 4 more turns before `complete` (bounded, but a leak worth a
    halt-when-done nudge).
  - *Ops findings*: run-deadline close logs "halt superseded — a newer
    run owns the agent" (misleading for `:deadline-exceeded`; smell);
    `logs/acme/pod.log` goes sparse/NUL-prefixed after a supervisor
    restart so plain grep (ugrep) silently sees binary — use
    `LC_ALL=C grep -a`; the "continue" chat auto-mints an open
    `✉ continue` address step that nothing closes (ledger noise,
    #16-adjacent).
- **W4 — measure**: the `planner_worker` task, one full run, honest
  verdicts against the win conditions.

## Rulings landed (owner, 2026-07-11 evening)

- **`:plan` is THE plan surface** — **IMPLEMENTED 2026-07-11**: the
  ▶/☐/done-dropped compactness contract folded into
  `my.plan.internal/plan-block` (frontier lines now ▶ active / ☐ open,
  glyph legend in the header); `:plan-ledger` retired
  (`plan-ledger-block` deleted from `seon.agent.ctx.menu`, its tests
  migrated to `my.plan-test`, `seon.ai.typeahead` strips `plan` only).
  Glyph-alignment finding: the ledger's ① numbering was render-side
  only — `function-offers` (`verb-offers` pre-2026-07-12) reads the
  recent+toolkit fn menu exclusively —
  so plan-step selection glyphs retired WITH the ledger and the
  duplicate-① render ambiguity is gone.
- **Acme testbed loadout** — **IMPLEMENTED 2026-07-11**: acme.edn
  declares `:seon.agent/ctx` (wholesale replace) — system.edn's tree
  mirrored by hand (the accepted cost) + `:function-menu` 46 (named
  `:recent-verbs` until 2026-07-12) +
  `:typeahead-steps` 95 (tile on by default on the TESTBED only; the
  default cluster stays minimal).
- **P7 frontier = BOTH**: DeepSeek (topped up; key verified live) as
  the reference arm, scheduled off-peak per the pricing memory; Muse
  as the planner.
- Heap-snapshot history strip: deferred to next push-need (owner).

## Open questions (resolve during W1)
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
