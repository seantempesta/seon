---
type: research
status: active
tags: [research, agent]
---

# Plan-preload live-drive pilot — 2026-07-12

**Owner directive:** "we should be providing the plan to the agent so the
agent isn't expected to do everything on their own. I'm open to using the
planning tool and preloading it with a plan just to see if we can drive
real agents." Exploratory iterate-live-before-hardening; the inspect-ai
hardening comes later (recommendation at the end).

## TL;DR

- **Markdown-first-turn preload was used on all three scenarios** (the
  scenario-1 fumble-fallback never triggered) — and authoring turned
  out to be the HIGH-VARIANCE half: under an identical preamble the
  plan tree landed at **t0 (s1), t17 after self-correction (s2), and
  never (s3)**. When authored it was perfect both times (1:1 steps,
  expects verbatim, always `plan!` — the empty-plan teaching's coached
  form; DeepSeek never chose `reconcile!`).
- **The plan window steers real work when it exists**: `active!` on
  real rendered ids, verify-then-`done!` cycles (s2: 3/3
  expect-consistent closes), textbook 4–7-turn recall arcs, and no
  off-scenario wander in any 30+-turn run. When the tree does NOT
  exist the failure is **expect-blindness**: scenario 3 did all the
  work competently and persisted a WRONG number into durable memory
  (`my.kb`, `:confidence :verified`) because no expect was in view to
  falsify it.
- **The escalation chain fired organically in scenario 1** (first
  un-staged live firing): stuck×3 same-root → consult → the root
  agent pulled `document`, `reconcile!`d, messaged guidance,
  `complete`d in the same turn. Mechanically flawless — and it made
  two authority mistakes the separation-of-authority design predicts
  (reopened the worker's verified-done step; re-minted the root by
  omitting its id). Function-called plan writes are unclamped.
- **Database memory works across runs AND a pod restart**: the new
  `my.kb/remember`/`recall` contract round-tripped organically
  (s3B), and s2B answered from a live query over the agent's own
  schema — grounded, sourced, correct.
- **Dominant flail: parenthesized prose** (~14 turns) — DeepSeek in
  `:stream` mode opens explanations with "(" and the reader takes a
  form; it killed scenario 1's endgame (`:no-forms` close, report
  never delivered). Zero `:core` faults; 9 `:agent` faults, all
  recovered or correctly escalated. Spend ≈ $0.64 worst-case.

## Harness (the pinned isolated cluster)

- Worktree `/Users/sean/src/seon-plan-pilot`, detached at `299b37f7`
  (= pin `93c8d8ad` + the lane commits, cherry-picked as `5258e166`
  [e2e4ce92 toolkit] + `299b37f7` [b255e23c my.ns fix]) — the toolkit
  agent's pattern, own worktree. `reference-code/datahike` submodule
  must be initialized (`git submodule update --init`) or the
  wire-server dies at boot on the missing proximum classpath.
- Own acme cluster: pod `7984`, wire REPL `7985`
  (`SEON_PORT=7984 SEON_WRITER_REPL_PORT=7985 bin/acme …`), fresh
  store **`/Users/sean/src/seon-plan-pilot/data/clusters/acme`**
  (store + blobs — the drive turns for the exporter live HERE; note
  for the mining pass).
- Provider: DeepSeek direct, `deepseek-v4-pro`, thinking `"false"`,
  temp 0.7 — the `.env.acme` copied from the toolkit worktree already
  says `SEON_AI_PROVIDER=deepseek` (the diffusiongemma trap was absent
  in that copy). DeepSeek resolves repl-mode **`:stream`** (per-model
  default) — one form per turn, form-limit 60, 10-min run deadline; so
  "turns" below are stream turns (≈ forms), not batch turns.
- Drive door: `POST /agents/run` (`{"input", "timeout_ms"}`), fresh
  agent per scenario; observations read back from the store over the
  wire REPL (eval log, plan rows, status tx history, faults) — no
  coaching, no mid-run edits.

## Scenario 1 — data-work arc ("bird sighting log", generator shape)

Stimulus: `lora_gen_situations.py`'s sighting domain as a planner
hand-down — preamble ("Record it as your durable plan before anything
else, then take the steps strictly one at a time and close a step only
once its outcome really holds") + `# Bird sighting log` + `Goal:` + 3
numbered steps with `— expect:` suffixes. No function names anywhere.

Agent `cxs-2607122208` — 33 turns / 34 evals / 192 s, closed
`:no-forms`. Turn-by-turn:

| turn | form (head) | ok | note |
|---|---|---|---|
| 0 | `plan/plan!` | ok | **the preload lands**: 1:1 tree, expects verbatim, `:pace :one-shot` |
| 1 | `plan/active!` | ok | but on the auto-minted ✉ ADDRESS step (`CCL…`), not its own step 1 |
| 2–5 | `schema/register!` ×4 | ok | the four attrs — but id registered WITHOUT `:seon.db/identity` |
| 6 | `message/user` | ok | progress prose (good channel discipline) |
| 7 | `schema/register!` | ok | first identity-fix attempt |
| 8–9 | `db/transact!` | ok | probe + seed rows |
| 10 | `db/pull` | ERR | `Lookup ref attribute should be marked as :db/unique` — fault #1 |
| 11–13 | `register!` ×2, `transact!` | ok | fix loop |
| 14 | `db/pull` | ERR | same root — fault #2 (streak forming) |
| 15–17 | `query`/`transact!`/`query` | ok | |
| 18 | (reply fragment) | ERR | READ ERROR: backticked `` `:db.unique/identity` `` — markdown leaking into a form |
| 19 | `db/transact!` | ok | |
| 20 | `db/pull` | ERR | `Nothing found for entity id` — fault #3 → **STUCK flag** |
| 21–22 | `transact!`, `pull` | ok | probe finally round-trips — step-1 expect HOLDS |
| 23 | `plan/done!` | ok | closes step 1 — an HONEST close (expect verified at t22) |
| 24 | `message/user` | ok | "Step 1 is complete…" |
| 25 | `plan/active!` | ok | takes step 2 (real id from the plan window) |
| 26–27 | `transact!`, `query` | ok | remaining seeds; 3 rows confirmed |
| 28–29 | bare-symbol forms | ERR | `(s-301 appears twice…)` — prose in parens, "not defined" |
| 30–32 | prose-guard | "ok" | REPL reads them as PROSE, not evaluated, teaching narration |
| — | (no forms) | — | run closes `:no-forms`; **step 3 (report) never delivered** |

Organic escalation (all in-store, zero staging):

- 02:09:56 consult message `worker → root`: `[escalation :my.plan/step
  "CCL-2607122208" :my.plan/episode "cPs-2607122209"] Worker … is stuck
  on plan step CCL… «Below is the session p…»` — the streak detector
  keyed the ▶ step, which was the ADDRESS step (the worker had
  `active!`d the ✉ step, not its own step 1), so the consult framed a
  real wedge (the identity-register loop) against a meta step.
- Root (DeepSeek, frontier-provider, derived planner): `document` →
  `status` → one READ-ERROR (a `:` colon-led prose line) →
  `reconcile!` (tree, worker-scoped) → `reopen!` → `reconcile!` again
  → `message/agent` guidance → `complete` in the same turn (the
  consult completion contract held; zero idle turns).
- The planner's reconcile preserved the 3 real step ids but its
  document's ROOT carried no `:my.plan/id` → the original root was
  DROPPED and a new root minted (identity hazard, third live sighting
  after W1/W3). It also **added a meta step "Record this plan as your
  durable plan"** with two substeps whose expects NAME function calls
  ("Call plan! with the session plan structure") — a frontier-authored
  step violating the planner-words doctrine, and re-importing exactly
  the meta-instruction that wedged the worker.
- The planner **`reopen!`d the worker's verified-done step 1** — the
  probe read-back had already proven the expect. Authority zones exist
  only as clamps in the diffusion buffer path; a function-called
  `reopen!` is unguarded.

Data outcome (live-verified over the wire REPL): 3 sighting rows,
correct values (`s-301/302/303`, cedar waxwing 12+5, varied thrush 2).
Steps honestly done: 1 of 3 (step 2's expect held in the data but was
never `done!`d; step 3 never ran). Faults: 5, all `:agent`, zero
`:core`.

## Scenario 2 — kb-memory arc ("orchard variety knowledge", two-phase)

Stimulus A: same preamble + `# Orchard variety knowledge`, 3 steps
(design fact shape / record 3 sourced facts / confirm to human).
Stimulus B (a later `/agents/run` on the SAME agent): "which varieties
are ready in September AND resist scab? Answer by querying the stored
facts back — never from memory — and say which source."

**Phase A** — agent `Aeo-2607122214`, 32 turns / 33 evals / 152 s,
closed **`:completed`** with the exact confirmation the plan asked for.

| turns | forms | note |
|---|---|---|
| 0 | `active!` | on the ✉ address step again — and then it did NOT author the plan |
| 1 | `schema/schemas-in-namespace` | orientation read before designing (good) |
| 2–6 | `register!` ×5 | `:orchard.variety/{name,harvest-month,scab-resistant?,source}` (+ identity fix) |
| 7–9 | `transact!` ×2, `entity` | probe + facts, probe read-back verified |
| 10–11 | `done!`, `active!` | closed the ADDRESS step; work done, still NO plan tree |
| 12–14 | `next`, `list-open`, `query` | **the self-correction moment**: the plan window comes back empty |
| 15–16 | flail ×2 | parenthesized prose — "(id \`LmW…\`) doesn't exist … the plan was never actually recorded" (read-error + prose-guard) |
| 17 | `plan/plan!` | **authors the 4-node tree at t17** — 1:1 steps, expects verbatim |
| 19–27 | `active!`/verify/`done!` ×3 cycles | retroactive but HONEST: every `done!` preceded by a live verification read (t21 `entity`, t25 `query` returning all 3 facts + sources) |
| 28 | `message/user` | the exact step-3 confirmation (3 facts, sources named) |
| 29–31 | `done!` ×2, `complete` | root closed, run `:completed` |

Deviation worth keeping: the preamble said "record it as your durable
plan before anything else" — the agent worked FIRST and authored at
t17, after `next`/`list-open` showed an empty queue. The plan surface
itself supplied the corrective signal (no coaching); the recovery cost
~5 turns.

**Phase B (recall)** — same agent, 7 turns / 35 s, zero flails,
textbook: `plan!` (a small 2-step plan for the ask, unprompted) →
`active!` → ONE pull query over `:orchard.variety/*` → `message/user`
**"Only Liberty is ready in September AND resists scab — source: WSU
extension chart."** (correct, grounded) → `done!` ×2 → `complete`.
Database memory store→retrieve: **PASS** — the answer was computed by
a live query over the schema the agent designed a run earlier, and the
source rode the row.

Note: the agent did NOT use the `my.kb` toolkit (`remember`/`recall`)
— it designed its own `:orchard.variety/*` schema and queried it back.
That satisfies the doctrine (knowledge = schema'd data, never a memory
blob) but means the new `my.kb/recall` contract went unexercised by
this stimulus shape; a recall ask that does NOT name the domain (where
token-match over kb text is the only handle) is the shape that would
force it.

## Scenario 3 — mixed arc ("ferry crossing log" + later recall)

Stimulus A: same preamble + `# Ferry crossing log`, 4 steps (model
crossings / load 4 rows / report delay per route, expect "north-sound
13, island-loop 25" / store the takeaway durably with provenance).
Stimulus B: pod RESTARTED, then "recall the worst offender from what
you stored — route, number, provenance" on the same agent.

**Phase A** — agent `blr-2607122220`, 34 turns / 35 evals / 168 s,
closed **`:completed`** — but the plan tree was **NEVER authored**.

| turns | forms | note |
|---|---|---|
| 0 | `active!` | the ✉ address step — and this time no self-correction ever came |
| 1–7 | `register!` ×7 | `:my.agent.blr….crossing/*` incl. a STORED `delay-minutes` (derive-don't-store deviation — clamped at write time, see below) |
| 8–9 | `transact!`, `query` | 4 crossings land, verified |
| 10–11 | prose-guard ×2 | "(all …)", "(the …)" |
| 12 | `done!` | closes the ADDRESS step — the only step — with steps 3–4 not yet done (premature close) |
| 13–14 | `db/query` ERR ×2 | aggregation fumbles (`:north-sound is not ISeqable`, bad binding) |
| 15–16 | `query`, `message/user` | "…Step 3 computed: north-sound 13, **island-loop 26**" |
| 17 | **`my.kb/remember`** | the new toolkit used organically: claim + `::source` + `:confidence :verified` |
| 18–26 | `active!`, `next`, `list-open`, `tree`, `in-ns`, `tree`, `query` ×2 | a ~9-turn ORIENTATION WANDER hunting for step ids that never existed |
| 27–28 | ERR ×2 | prose-in-parens; a query using `clojure.string/includes?` (unsupported predicate in the CLJS engine) |
| 29–33 | `message/user` ×2, `query`, `complete` | honest self-diagnosis: "steps 2–4 were implied but never minted as explicit sub-steps … I'll complete now" |

**The headline defect — a wrong number persisted as verified
knowledge.** Expected island-loop delay = (52−40)+(41−40)=13 and
(64−65)+(91−65)=**25**. The agent stored a per-row `delay-minutes`
column with c-3's −1 silently clamped to 0 (`["c-3" 0]` live-read),
summed the stored column, reported **26**, and `my.kb/remember`ed
"island-loop is the worst offender route with 26 total delay minutes"
at `:my.kb/confidence :verified`. The plan's step-3 `::expect` said 25
— but since the tree was never authored there was no expect in view,
so nothing falsified the number. The causal chain is exactly the
pilot's thesis: **no plan tree → no expect → no falsification → a
wrong fact enters durable memory marked verified.** (Clamping early
arrivals is a defensible semantics — but the agent neither matched the
handed-down expect nor flagged the disagreement; it never saw it.)

**Phase B (restart + recall)** — `bin/acme restart pod` (12 s), then
the recall ask on the same agent: 4 turns / 20 s, zero flails —
**`my.kb/recall` used organically** (`{:my.kb/about "worst offender
route ferry crossing delay"}` — the e2e4ce92 toolkit contract's first
live-drive exercise), answer delivered WITH provenance ("stored during
the 2026-07-12 ferry crossing analysis session, verified"), the
recall-message's own address step closed, `complete`. Continuity
across restart: **PASS** (no re-planning, no repeated work). The
recalled number is the wrong 26 — faithful recall of a wrong stored
fact, with the provenance trail that makes it auditable. Both new
toolkit fns (`remember`+`recall`) round-tripped across a process
boundary.

## Plan-following verdict

- **Preload variant used: markdown-first-turn on ALL THREE scenarios**
  (the task's fallback rule said pre-transact if scenario 1 fumbled —
  it did not fumble, authoring at t0 with a perfect tree, so variant A
  was kept for 2–3, and the variance then showed up there). Authoring
  reliability under an IDENTICAL preamble: **t0 / t17
  (self-corrected) / never** — when the tree WAS authored (2/2) it was
  1:1 with expects verbatim, `plan!` both times (the empty-plan
  teaching's coached form; `reconcile!` never chosen by DeepSeek,
  unlike Muse in W1).
- **The plan window steers real work when it exists.** `active!`
  transitions used real ids from the render; s2's every `done!` was
  preceded by a live verification read (entity/query) — plan integrity
  3/3 expect-consistent closes; s2B/s3B were textbook 4–7-turn arcs.
  Work stayed on-scenario in all 33/32/34-turn runs — the wander
  radius under a plan is small (inside the active step), consistent
  with P6's "context is the binding constraint".
- **When the tree does NOT exist the failure is expect-blindness, not
  topic drift**: scenario 3 did all the work competently and shipped a
  wrong number into durable memory because no expect was in view to
  falsify it, then spent ~9 turns hunting for steps to close.
- **`done!` honesty tracks the expect's in-band checkability**: probe
  read-backs and count queries produced honest closes; the
  address-step close (s3 t12) was premature — `done!` remains
  docstring-gated only (the W3 gap, now with a memory-poisoning
  consequence).
- **The escalation chain fired organically** (first un-staged firing)
  and worked mechanically end-to-end — but exposed two authority
  defects: the planner `reopen!`d a verified-done step, and its
  id-less document ROOT re-minted the root node (identity hazard,
  third live sighting). Function-called plan writes are unclamped —
  the separation-of-authority zones exist only in the diffusion
  buffer path today.
- **Budget honesty**: the pilot's 12-turn budget was blown by every
  phase-A run in STREAM-turn units (33/32/34 — DeepSeek's per-model
  repl-mode default is `:stream`, one form per turn) but each run was
  only 2.5–3.2 min wall and ≈6 batch-equivalent turns; phase-B runs
  took 4–7 turns. Budgets for the bench should be denominated in the
  loop's work unit (forms for `:stream`) or wall-clock, never raw
  "turns".

## Stall/wander taxonomy (per-turn, all live-observed)

1. **Parenthesized-prose emission** — the dominant flail, ~14 turns
   across the pilot: DeepSeek opens an explanation with "(" and the
   `:stream` reader takes it as the turn's form. Three sub-shapes:
   read-errors (backticked keywords, `1-6` invalid numbers),
   `not defined` bare-symbol heads, and the prose-guard's ok/nil
   ("read as PROSE, not code"). Killed scenario 1's endgame: after
   the data work, the model narrated the report in parens for 5
   turns, then emitted pure prose → run closed `:no-forms` with the
   report never delivered via `message/user`.
2. **Address-step capture (3/3 scenarios)** — the first `active!`
   always went to the auto-minted ✉ address step, not an authored
   step. Consequences observed: the escalation consult framed the
   real wedge against the meta step (s1); work proceeded plan-less
   (s2, s3); the address step ate a premature `done!` (s3).
3. **Plan-authoring variance** — t0 / t17 / never under identical
   stimulus (above).
4. **Schema-identity wedge** (s1) — `register!` without
   `:seon.db/identity`, then 3 lookup-ref failures sharing a root;
   the same-root streak detector caught exactly this and escalated.
5. **Missing-tree orientation wander** (s3) — ~9 turns of
   `next`/`list-open`/`tree`/`in-ns`/queries hunting for step ids
   that never existed.
6. **Query-engine idiom gaps** — aggregation binding fumbles,
   `clojure.string/includes?` as a query predicate (unsupported);
   1–2 turns each, all self-recovered via the error envelope.
7. **Derive-don't-store with silent mutation** (s3) — a stored
   `delay-minutes` column, clamped at write time, undisclosed; the
   direct cause of the wrong-26.

Faults: **9 total, all `:agent`, zero `:core`** across 119 LLM turns
and one mid-pilot pod restart. Faults were findings, not failures —
the error envelopes steered recovery in every case except the s1
identity wedge (which correctly escalated instead).

## Harvest list

- **Zero agent-defined fns across 119 turns** — every form was
  call-shaped (plan ops, register!, transact!, query, messages),
  consistent with the mined ~93% call-shaped distribution. No
  function promotion candidates.
- **Gold-pair material in the store** (the exporter's target — REAL
  turns with plans in context): s1 t0's perfect markdown→`plan!`
  authoring pair; s2's verify-then-`done!` cycles (t21–t27); s2B's
  entire 7-turn recall arc (mini-plan → pull query → grounded answer
  → closes → complete); s3B's 4-turn `my.kb/recall` arc; the root
  agent's consult arc (`document` → `reconcile!` id-preserving edit →
  `message/agent` → same-turn `complete`).
- **Negative/correction pairs**: the parenthesized-prose turns with
  their prose-guard narrations; the id-less-root `reconcile!` that
  re-minted the root; the clamped `delay-minutes` transact (the
  derive-don't-store counter-example); the `clojure.string/includes?`
  query.
- **Store path** (single-writer — do not run a second wire-server on
  it): `/Users/sean/src/seon-plan-pilot/data/clusters/acme` (store +
  blobs; prompt/reply blobs intact, `rendered-as-of` on every turn).
  Worktree pinned at `299b37f7`; pod 7984 / wire REPL 7985 while up.
- Spend: 119 turns, 2,260,073 prompt + 8,294 completion tokens
  (usage-row sum over the store) ≈ **$0.64 worst-case** (all
  cache-miss DeepSeek pricing) — under the $2 cap.

## Recommendation for the inspect-ai hardening

The pilot says the two owner-sanctioned preload shapes measure
DIFFERENT capabilities — keep them as separate arms, don't conflate:

- **Arm P (pre-transacted plan)**: the harness lands the scenario's
  goal/steps/expects via `reconcile!` (worker-scoped, from the wire
  REPL or a host-side call) BEFORE turn 1. Removes the authoring
  variance entirely; measures plan-FOLLOWING. This is the shape the
  product wants if authoring stays 1/3-reliable.
- **Arm A (markdown-first-turn)**: today's stimulus; measures
  authoring + following. The t0/t17/never variance IS the metric.
- **Arm C (no plan)**: control, per the planner_worker design.

What to measure (all mechanically derivable from the store — no LLM
judge):

1. **Outcome-at-oracle** — the scenario card's exact-answer `::oracle`
   run against the store (NOT the reply text). This is what catches
   the wrong-26 class: the reply read as plausible and only the data
   falsifies it.
2. **Plan integrity** = expect-verified closes / closes (a
   verification read of the step's subject within N forms before the
   `done!`, plus the oracle holding at close time). Pilot values:
   s1 1/1-then-reversed, s2 3/3, s3 0/1.
3. **Report delivery** — a completion must carry the oracle answer
   through `message/user` before the run closes; s1's `:no-forms`
   close after finished data work shows runs can end silently.
4. **Address-step discipline** — turns where ▶ sits on the ✉ step
   while authored steps are open (a one-query derivation); 3/3
   scenarios hit it, so it will move.
5. **Prose-emission rate** — read-error + prose-guard + bare-symbol
   `not defined` evals per run (the dominant flail; a free counter).
6. **Faults**: `:core` = 0 rides as an invariant; `:agent` fault
   count per run as a difficulty signal.
7. Budgets in the loop's work unit (forms under `:stream`) or
   wall-clock — never raw turns.

Win condition: **Arm P beats Arm C on outcome-at-oracle AND plan
integrity at the same wall budget** (the preload earns its place); if
Arm A ≈ Arm P, authoring is free and the markdown hand-down is the
product shape; if Arm A ≪ Arm P, planner hand-downs should land as
DATA (reconcile!), not prose.

Fix-first candidates the bench would otherwise bake in as noise (all
drive-evidenced here, none built in this unit): the mechanical expect
gate on `done!` (W3 gap; now memory-poisoning-evidenced), an identity
guard in `reconcile!` for id-less roots over id-bearing children (or
the basis/scope argument — the #16-adjacent design question, third
sighting), and the address-step capture (why ▶ lands on the ✉ step:
it is the only open step at turn 0 and the coaching says "take one
up" — a root-cause look at whether the address step should be
`active!`-able at all while an authored tree exists).

## Ops findings (pinned-harness runbook deltas)

- A fresh worktree needs `git submodule update --init
  reference-code/datahike` before `bin/acme start wire-server` — the
  `:simd` alias's `reference-code/datahike/src-secondary` path is a
  submodule, and without it the writer dies at boot on the missing
  proximum namespace.
- The toolkit worktree's `.env.acme` already carries
  `SEON_AI_PROVIDER=deepseek` — the diffusiongemma trap named in the
  task brief was absent from that copy (report: no override needed
  beyond copying that file).
- `SEON_DEBUG_CAPTURE=1` wrote nothing at this pin (`logs/acme/turns/`
  never created) — the capture path appears superseded by the
  blob-store capture dial; observation via the wire REPL + blobs was
  sufficient. Smell, not a blocker.
- Port overrides must ride EVERY `bin/acme` call
  (`SEON_PORT=7984 SEON_WRITER_REPL_PORT=7985`) — they are shell-side
  `${:-}` defaults, not `.env.acme` keys.
