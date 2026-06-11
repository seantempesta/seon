---
type: prd
status: draft
tags: [prd, agent]
---

# Gym Upgrade — per-turn fidelity, harness integrity, context-driven improvement (2026-06-11)

Fresh-read spec. Executes the findings of
`research/gym-disconnect-analysis-2026-06-11.md` (read it for evidence;
this doc is self-contained for implementation). Builds on the gym that
exists today: `test/seon/gym/driver.cljs` (1051 lines, scenario EDNs in
`test/seon/gym/scenarios/`, `bin/gym`, `paid_test.cljs`), the scenario
catalog `gym-scenario-catalog-2026-06-10.md`, and the one composer
`seon.ctx/assemble-context`.

User directives (2026-06-11, law for this PRD):

- The gym ALWAYS represents a quality benchmark of the system as it
  evolves. It exposes problems; it never hides them. **Correctness >
  benchmark continuity** — when a fix invalidates old numbers, we
  re-baseline and say so.
- This PRD DRIVES the stability and context improvements: "we need to
  understand what the agent is seeing and keep simplifying and
  clarifying things so the live data is always the right context — this
  is a main differentiating factor of the harness."
- The transcript is a bounded window, NOT an ever-growing conversation
  (exact bound is an open decision, §6).
- Reactive freshness is the product: another agent's new knowledge and
  functions show up on the NEXT turn — the gym must TEST that latency,
  not assume it.
- More demonstrated examples of agents registering DB queries as live
  context sections — beyond the live tile: the kb section and THE TASK
  LIST as a worked example.
- Fix ALL known harness issues (§3).

## 1. Goal + non-goals

**Goal.** Upgrade the gym from a turn-0-parity, post-run-store referee
into a per-turn context-fidelity benchmark: every scenario can assert
against what the agent ACTUALLY SAW each turn (the persisted prompt
blobs), against the composer's own structural output, and against a
world that churns mid-run the way the live store does (re-boots,
foreign writers). Fix the four harness defects that currently corrupt
the measurement itself. Then use the upgraded referee to drive the
context-improvement ladder (§4): bounded transcript, cross-agent
freshness, registered-query sections.

**Non-goals.**

- No changes to the live agent loop, composer, or wake path EXCEPT
  where a gym scenario turns red and the fix is a separate, named unit
  (the gym pins target behavior; fixes ride their own queue items).
- No writable fork of the real cluster store (open question §6.4; the
  read-only live-shadow tier gets most of the value).
- No design of the live-tile `:seon.render/ai` twin ("agent knows what
  its human sees") — under separate user review; referenced in §4.4
  only.
- No new judge machinery; LLM-judge stays as-is.

## 2. The fidelity model — what "the gym represents the system" means

Operationally: **a gym run is faithful when every channel by which a
live turn's context varies is either reproduced or asserted.** The
disconnect analysis taxonomized six variance channels (§1.2 there);
today the gym reproduces channel 1 (intended dynamics) and channel 2
(within-run catalog churn) and is structurally blind to channels 3–6
(boot re-seeding, foreign writers, cross-session asymmetry, loop
policy). The 095a00b world-parity work proved the **turn-0 static
prefix**; this PRD extends the same principle — **every expectation is
derived from the boot's/composer's own code, never a hand-maintained
list** — to per-turn dynamics.

Six mechanisms, each named with the problem class it exposes:

### 2.1 Prompt-blob predicates (the referee's eyes)

`run-turn!` already persists every full prompt to
`logs/prompts/<agent-id>/<turn-id>.txt` in BOTH live and gym runs; the
turn datom carries `:seon.agent.turn/prompt-file` +
`:seon.agent.turn/prompt-chars`. New mechanical predicate kinds read
those blobs:

- `:prompt-includes` — some turn's prompt contains `:text`
  (optionally `:seon.gym.predicate/turn` to pin a specific turn index,
  `:seon.gym.predicate/agent` to scope).
- `:prompt-excludes` — NO turn's prompt contains `:text`.
- `:prompt-every-turn` — EVERY turn's prompt contains `:text`.

This revives the catalog's standing predicate G2 (sees-question),
currently **unimplementable as written** because
`:seon.agent.turn/prompt-text` was retired to file blobs on 2026-06-09
and no predicate kind reads files. G2 becomes
`:prompt-every-turn {:text <the user message>}`.

**Exposes:** the run-3 transcript-loss class — a regression that drops
the question (or a seeded consult surface) from turn N's prompt while
the agent still muddles to a right answer. Also makes the #26 salience
axis honest: "the seeded claim TEXT rendered in the prompt the agent
acted on" (context worked) is a SEPARATE predicate from "the agent's
first eval consulted it" (agent behaved) — "context failed" vs "agent
ignored context" become distinct failure signatures.

### 2.2 Structural per-turn section profile

`assemble-context` already returns `:seon.render/sections` (layout
provenance, all merged names in render order) and
`:seon.render/section-texts` (non-blank contributions with text). The
driver captures a schema-validated `:seon.gym/turn-profile` per driven
turn: section-name list + per-section char counts. Two checks fall out
structurally, derived from the composer's own code:

- **(a) Layout completeness** — the section list equals
  `(map :seon.ctx/name (substrate-default-ctx))` merged with the
  agent's own sections, minus blank-rendered names. Derived, not a
  hand-list in a test.
- **(b) Cache-prefix byte-stability** — rendering twice against the
  SAME db value must be byte-identical up to the `:transcript`
  boundary. This is the provider-cache invariant the whole
  most-static→most-dynamic section ordering exists to serve
  (`substrate-default-ctx` docstring) and it is currently **asserted
  nowhere**. A timestamp or counter leaking into a static section is a
  silent cache-bust and spend regression.

**Exposes:** sections silently going blank (a render-error one-liner or
an empty query where content should be), section-order regressions,
per-turn-volatile bytes leaking above the prompt tail, budget
violations (`agent-section-char-budget`, `transcript-char-budget`).

### 2.3 World-churn mechanics — `:reboot` and `:foreign-write`

New per-turn churn hook (`:seon.gym.turn/before`, a vector of churn
ops) executed between turns, with two STRUCTURAL generators that reuse
boot/eval code paths verbatim — no hand-written row soup:

- `:reboot` — re-run the boot's own seed sequence
  (`schema/all-entity-schemas-tx-data` + `client/substrate-index-tx`)
  against the same scratch conn after registering one extra schema,
  simulating a pod restart on a populated store (the observed E1 class:
  agent kXQ's catalog moved "6 schemas" → "8 schemas" mid-session when
  the P6 re-seed landed at 23:14:03). Assert idempotence (no duplicate
  catalog rows — the `:db/ident` upsert claim) AND that the NEXT turn's
  prompt (via 2.1) reflects the new rows.
- `:foreign-write` — a `db/with-agent <other-id>` transact of
  registrations/findings/rows between turns, asserting the cross-agent
  surfaces (warnings, domain-attrs, finding-claims, catalogs) appear in
  the next turn's prompt.

**Exposes:** the entire channel-3/4 family — catalog drift, duplicate
accumulation across boots, cross-agent visibility breakage — today
discoverable only in paid live runs.

### 2.4 Trigger-driven wake mode

Optional `:seon.gym.scenario/wake :trigger`: the driver installs
`install-user-trigger!` on the scratch conn with the scenario's llm-fn
and lets `message!` do the waking; await-idle polls
`:seon.agent/state` + the `!kick-scheduled` latch (expose a read fn).
Encode as stubs: the hop-guard scenario (a message with hops ≥ cap
wakes nothing) and the double-message-single-loop latch scenario. The
ns-docstring's reason for avoiding this path ("stub self-wake bug burns
trigger-driven stub loops to the turn cap") should be re-evaluated once
P21's #35 replied-halt terminates the loop.

**Exposes:** the wake path itself — hop guard, latch, state-machine
guard, inbound-datom filter, DIS foreign-tx listener adaptation — which
today has ZERO gym coverage ("no dispatcher is armed — the driver
drives") and is exactly where live races live.

### 2.5 Auto-appended standing predicates

The catalog says G1–G5 are "appended to every behavioral scenario's
mechanical set"; the driver has no such mechanism (analysis E8 —
authors copy by hand, mostly don't). The driver appends at load: G1
(terminates: turns < cap, final state `:idle`, last turn `:done`), G2
(the new prompt-based sees-question, from the scenario's turn
messages), G3 (no blank messages), G5 (multi-segment attrs). Catalog
intent becomes code; scenarios add only their specifics.

**Exposes:** regression classes that should never need re-stating, on
every scenario, forever.

### 2.6 Live-shadow tier (read-only, zero spend)

The direct answer to "the gym must benchmark the SYSTEM as it now
operates": a gym mode that runs `assemble-context` against a db VALUE
of the REAL cluster store (`data/clusters/default/store`) for each live
agent and evaluates the structural checks from 2.2 plus invariants —
budgets respected, no `render failed:` lines, the FilteredDB
schema-fallback path exercised, catalogs render rows whose attrs aren't
in the local registry. No transacts, no LLM. The benchmark inherits the
real store's accumulated mess (multi-boot duplicates, foreign attrs,
long message logs) on every run instead of being insulated from it.

**Exposes:** composer breakage that only manifests on a lived-in store
— the class the gym's frozen scratch worlds can never reach.

## 3. Harness defect fixes (measurement integrity)

Four concrete defects from the analysis. Each fix carries a
falsification test — "how would I know this is broken" — because a
referee that can't see its own defects hides problems.

### 3.1 S-12 async double-done (double spend)

Evidence E3: S-12 emitted two scorecards 51s apart in paid sweep 2 —
one paid scenario ran twice, two cards under one (scenario × sha) key.
Fix: wrap each async test's `done` in a call-once guard in
`paid_test.cljs` (and find/fix the actual double-resolution path in
`run-paid!` — the `.then` + `.catch` chain can call `done` twice if the
`.then` body throws). Additionally key scorecards
(scenario × sha × run-uuid) so any future double-run is VISIBLE as two
distinct cards instead of ambiguous.

**Falsification:** a driver test that forces a rejected promise inside
the `.then` body and asserts `done` is invoked exactly once; grep the
suite log for duplicate `SEON-GYM SCORECARD` lines with identical
run-uuid (must be zero).

### 3.2 s32 bait-question seeding

Evidence E5: the seeded `:my.kb.codebase/question` is verbatim the
asked question, and the consult predicate (`first-eval-matches ":my\\."`)
passes on ANY `:my.*` touch — including the schema-catalog's own
recipe step 0. The predicate measures whether the bait was rendered and
pattern-matched, not whether consulting replaced research. Fix:
paraphrase the seeded question away from the asked question (same
meaning, different surface form), AND anchor the consult predicate on
the seeded DOMAIN attrs (`:my\.kb\.codebase/`) rather than any
`:my\.`. Pair it with a 2.1 salience predicate (`:prompt-includes` the
seeded claim text) so "surface rendered" and "agent consulted" score
separately.

**Falsification:** a stub run whose scripted first eval queries only
`:my.agent.*`/unrelated `:my.*` attrs must score the consult predicate
RED; a stub run whose first eval queries `:my.kb.codebase/*` scores
green.

### 3.3 Paid-gate anomaly — UNCONFIRMED; pin it, don't "fix" a ghost

Evidence E6: the suspicion that a partial `--paid=` list enabled all
scenarios could not be confirmed — `enabled?` in `paid_test.cljs` reads
correct, and the sweep logs don't record the `SEON_GYM_PAID` value. We
state that honestly and pin it: print the gate value + the resolved
enabled-scenario set as one greppable line at suite start, and add a
pure unit test over `enabled?` (exact-match split: `"s32"` enables only
s32; `"s32,s21"` enables exactly those; `"all"` enables all; `""`
enables none).

**Falsification:** the `enabled?` unit test; absence of the gate line
in a future paid log = the observability fix regressed.

### 3.4 Stub/paid question-text reuse

The same question strings recur across fixture bait, scenario turns,
and tiers (s32's question doubles as its fixture's `question` value;
catalog scenarios reuse run-7 phrasing the live store already holds).
Any predicate keyed on question TEXT can then pass by string
coincidence. Rule, enforced by a driver load-time check: a scenario's
turn message text must not appear verbatim inside its own fixture
values (loud load failure with the offending fixture path); predicates
about consultation anchor on attrs/structure, never on the question
string.

**Falsification:** a deliberately self-baited test scenario EDN must
fail to load with the named error.

## 4. The context-improvement ladder this gym drives

The upgraded referee exists to drive context quality: "keep simplifying
and clarifying things so the live data is always the right context."
Each rung is a gym scenario FIRST (red), then a context change
(green) — the gym pins target behavior.

### 4.1 Transcript-window policy

Current implementation (`seon.ctx/transcript-section`): 24,000-char
budget, newest-first EVAL eviction, messages eviction-exempt as of
commit 8ab8cbd (each message individually bounded by
`message-render-cap`), per-stream fetch cap of 50 messages + 50 evals.
The user's directive: NOT an ever-growing conversation — a bounded
window; floated "last ~50 turns?".

DECIDE(user): turn-bound vs char-budget (vs both — a turn-bound outer
window with the char budget inside it). **Proposal:** keep the
char budget as the hard cap (it bounds spend directly, which a turn
count does not — one turn can be 1.6KB or 50 bytes), add an explicit
TURN-bound on messages (e.g. last N user/agent exchanges) so the
message exemption cannot grow without bound on a long-lived agent, and
make both constants visible in the elision note. The decision should be
MEASURED, not argued: the 2.2 turn-profiles give per-turn transcript
char counts across a long stub run — run the experiment, pick the
bound from data.

Gym coverage: a long scripted-replay scenario (30+ turns) asserting via
2.1/2.2 that (a) the newest user message is ALWAYS in the prompt, (b)
the transcript section never exceeds its bound, (c) the elision note
appears once eviction starts.

### 4.2 Cross-agent next-turn visibility (reactive freshness)

The product claim: "if another agent adds new knowledge and functions
it shows up on the next turn." Test it, don't assume it. A
`:foreign-write` (2.3) scenario: between turn N and N+1, a synthetic
other agent registers a schema, stores a finding, and defines a fn
(via the real eval/tee path); turn N+1's prompt blob must contain the
new catalog line, the finding claim, and the functions-catalog count
bump. The predicate IS the latency bound: next turn, not eventually.

### 4.3 Registered-query sections — worked examples

Demonstrate (and pin) agents registering DB queries as live context
sections, beyond the live tile:

- **The task list as the canonical worked example.** A scenario where
  the agent (scripted at stub tier; deepseek variant later) calls
  `seon.agent/add-section!` with a query-backed render fn over its open
  todos/tasks; the gym then transacts a task-state change between turns
  and asserts via 2.1 that the NEXT prompt's section tracks the store
  (new task appears, completed task vanishes — reactive-context: the
  surface is derived, nothing stored needs clearing).
- **The kb section.** Same mechanism over `:my.kb.*` rows the agent
  cares about: register a section rendering its domain's findings;
  foreign-write a new finding; next prompt shows it.

Both reuse the existing section machinery (`:seon.agent/ctx`,
`agent-section-char-budget`) — no new mechanism, new section fns only.

### 4.4 "Agent knows what its human sees"

The live-tile `:seon.render/ai` twin idea (one render, two twins — the
tile the human sees and the ai-text the agent sees) is under separate
user review (`live-tiles-prd-2026-06-11.md`). Referenced here as the
fourth rung: when it lands, the gym predicate shape from 4.3 (section
tracks store, asserted in prompt blobs) covers it with zero new
machinery. Do not design it in this PRD.

## 5. Unit breakdown

Ordered so the measurement infrastructure lands FIRST — you can't
drive improvements with a referee that can't see. Each unit ≤7 files,
one mechanism. All units include updating `gym-scenario-catalog`'s §2
where they change predicate semantics.

### U1 — prompt-blob predicate kinds (2.1) — LANDS FIRST

- **Files:** `test/seon/gym/driver.cljs`,
  `test/seon/gym/driver_test.cljs`,
  `test/seon/gym/scenarios/s32-consult-before-research.edn` (adds the
  salience `:prompt-includes` predicate),
  `docs/prds/agent-runtime/gym-scenario-catalog-2026-06-10.md` (G2
  rewrite).
- **Mechanism:** predicate kinds `:prompt-includes` /
  `:prompt-excludes` / `:prompt-every-turn`; the driver collects the
  run's turn-ids + `:seon.agent.turn/prompt-file` from the post-run
  store and reads the blobs. Optional `:seon.gym.predicate/turn`
  (index) and existing `:seon.gym.predicate/agent` scoping.
- **Falsification:** a stub scenario whose scripted turn runs with a
  known prompt; assert `:prompt-every-turn` on the question text passes,
  then a predicate for text that is NOT in the prompt fails with the
  blob path in `:seon.gym.result/actual`. A missing/unreadable blob
  file = predicate scores RED naming the path (never a silent pass).

### U2 — harness defect fixes (§3, all four)

- **Files:** `test/seon/gym/paid_test.cljs`, `bin/gym`,
  `test/seon/gym/driver.cljs` (load-time self-bait check + run-uuid
  scorecard key + schema), `test/seon/gym/driver_test.cljs`,
  `test/seon/gym/scenarios/s32-consult-before-research.edn`
  (paraphrase + anchored pattern).
- **Mechanism:** call-once `done` guard; gate-value + resolved-set log
  line; `enabled?` unit test; scorecard `:seon.gym.scorecard/run-id`
  (uuid); self-bait load check.
- **Falsification:** per §3.1–3.4 above.

### U3 — structural per-turn profile + cache-prefix stability (2.2)

- **Files:** `test/seon/gym/driver.cljs` (capture
  `:seon.render/sections`/`section-texts` per driven turn — thread
  through `run-turn!`'s render or call `assemble-context` against the
  pre-turn db value), `test/seon/gym/driver_test.cljs`,
  `src/seon/ctx.cljs` ONLY if a returned shape needs one extra key
  (avoid if possible).
- **Mechanism:** `:seon.gym/turn-profile` schema + two derived checks
  (layout completeness from `substrate-default-ctx` itself;
  double-render byte-stability up to `:transcript`).
- **Falsification:** inject a section fn that embeds `(js/Date.)` into
  a static-priority section in a test — the stability check must go
  RED; remove it — green.

### U4 — auto-appended standing predicates (2.5)

- **Files:** `test/seon/gym/driver.cljs`,
  `test/seon/gym/driver_test.cljs`, catalog doc.
- **Mechanism:** at `load-scenarios!`, append G1/G2/G3/G5 (G2 built
  from the scenario's own turn messages via U1's kind); a scenario may
  opt out per-predicate with an explicit
  `:seon.gym.scenario/skip-standing` set naming WHY (e.g. S-08's error
  turn legitimately fails G1's `:done`).
- **Falsification:** load any scenario EDN with zero predicates →
  the run still scores ≥4 predicates; a stub run that ends `:running`
  must fail G1 without the scenario declaring it.

### U5 — world-churn mechanics (2.3)

- **Files:** `test/seon/gym/driver.cljs`,
  `test/seon/gym/driver_test.cljs`, two new scenario EDNs
  (`churn-reboot.edn`, `churn-foreign-write.edn` — the 4.2
  cross-agent-freshness scenario IS the foreign-write one), catalog
  doc.
- **Mechanism:** `:seon.gym.turn/before` churn ops `:reboot` /
  `:foreign-write`, both invoking boot/eval code paths verbatim.
- **Falsification:** reboot idempotence — run `:reboot` twice; catalog
  row count unchanged (duplicates = RED). Freshness — turn N+1's
  prompt blob must contain the foreign rows; assert with U1 kinds
  (delete the assertion mentally: nothing else would catch a listener
  regression — that's why it exists).

### U6 — registered-query section worked examples (4.3)

- **Files:** two new scenario EDNs (task-list section, kb section),
  `test/seon/gym/driver_test.cljs`, possibly a small fixture-source
  string inside the EDNs (no new driver mechanism — U1+U5 carry it).
- **Mechanism:** scripted agent registers a section via
  `add-section!`; driver churns the underlying rows between turns;
  prompt-blob predicates assert the section tracks the store both
  directions (appear AND vanish).
- **Falsification:** break the section fn symbol in a test variant —
  the prompt must carry the `render failed:` guard line and the
  tracking predicate goes RED.

### U7 — transcript-window scenario + measurement (4.1)

- **Files:** one new long scripted-replay scenario EDN,
  `test/seon/gym/driver_test.cljs`; `src/seon/ctx.cljs` only AFTER the
  DECIDE lands (the scenario goes in red-or-green against current
  policy first).
- **Mechanism:** 30+ scripted turns; predicates: newest user message
  always present (U1), transcript section ≤ bound (U3 profiles),
  elision note present once evicting.
- **Falsification:** raise the scripted eval-result sizes until
  eviction must trigger; if the elision predicate stays green without
  the note actually rendering, the predicate is broken.

### U8 — trigger-driven wake mode (2.4)

- **Files:** `test/seon/gym/driver.cljs`, `src/seon/agent.cljs` (a
  read fn for the `!kick-scheduled` latch IF none exists — minimal),
  `test/seon/gym/driver_test.cljs`, two new stub scenario EDNs
  (hop-guard, latch).
- **Mechanism:** `:seon.gym.scenario/wake :trigger`; await-idle =
  state + latch poll with timeout → RED (a hang is a finding, not a
  hung suite).
- **Falsification:** the hop-guard scenario seeds hops = cap; ANY turn
  woken = RED. Depends on P21's #35 replied-halt being committed
  (else stub loops burn to cap — re-check the ns-docstring caveat).

### U9 — registry-bleed hygiene

- **Files:** `test/seon/gym/driver.cljs` (gym-side filter on
  `substrate-index-tx` input) or `src/seon/client.cljs` (preferred:
  derive the allowed key set from the boot's own ns set —
  `substrate-ns-set` ∪ `my.*`), `test/seon/gym/driver_test.cljs`.
- **Mechanism:** `:seon.schema` index rows limited to keys whose
  owning ns the pod itself would carry — derived, not a deny-list of
  test namespaces.
- **Falsification:** register a `:seon.gym.bogus/x` key in a test;
  the seeded world's prompt must NOT contain it (U1
  `:prompt-excludes`).

### U10 — live-shadow tier (2.6)

- **Files:** new `test/seon/gym/shadow.cljs`, `bin/gym` (a
  `--shadow` flag), `test/seon/gym/driver_test.cljs` or a dedicated
  `shadow_test.cljs`, catalog doc.
- **Mechanism:** open a read-only db value of the real cluster store,
  enumerate live agents, run `assemble-context` + the U3 structural
  checks + invariants per agent; emit one scorecard per agent.
  DECIDE(user): CI cadence — see §6.3.
- **Falsification:** point it at a store with a known
  broken-section-symbol agent — the `render failed:` invariant must go
  RED. If the real store is unreachable, the tier REFUSES loudly
  (never a vacuous green sweep).

### U11 — restart/resume scenario (S-06, channel 5)

- **Files:** `test/seon/gym/driver.cljs` (mid-scenario "restart": drop
  compile state, `d/connect` the same `:memory` store id, run
  `start-agent!`'s resume branch), one scenario EDN,
  `test/seon/gym/driver_test.cljs`.
- **Mechanism:** run → restart → one more turn. Assert the
  messages-global/evals-session-scoped asymmetry DELIBERATELY (prompt
  contains prior messages; evals only the new session's) — pending the
  §6.5 decision on whether that asymmetry is intended.
- **Falsification:** seed-idempotence — fn-row count unchanged across
  the restart's re-seed (duplicates = RED; this is also U5's check on
  a different path).

Sequencing: U1 → U2 → U3 → U4 → U5 → U6 → U7 → U8 → U9 → U10 → U11.
U1–U4 restore and extend measurement integrity; U5–U7 are the
context-improvement drivers; U8–U11 close the remaining live-channel
gaps. Full test suite once per unit, at the unit's natural checkpoint
(test-cadence directive).

## 6. Open questions — DECIDE(user)

1. **Transcript bound:** turn-bound (~50 turns floated), char-budget
   (current 24k), or layered both (§4.1 proposal)? Recommend deciding
   from U7's measured turn-profiles, not a priori.
2. **Paid-spend cadence for re-baselining:** after each landed unit
   that changes prompts (U5/U7/U9 all move bytes), the paid scenarios'
   numbers move. One paid sweep per unit is the test-cadence-consistent
   answer, but it's real money — per-unit, per-day, or only at
   §7 migration time?
3. **Live-shadow in CI or on demand?** It reads the real store, so its
   results vary run-to-run by design (that's the point). Recommendation:
   on demand (`bin/gym --shadow`) + a daily run, NOT in the blocking
   suite — a live-store mess should be a loud report, not a blocked
   commit. Confirm.
4. **Writable fork of the real store** (analysis Q1): the read-only
   shadow tier ships in U10; is a writable fork (drive scenarios
   against a copy of real accumulated state) worth the machinery later?
5. **Is `ensure-session!`'s cross-pod-restart session reuse intended?**
   (Docstring says per-pod-run; implementation reuses across restarts.)
   Decides what U11 asserts.
6. **Prompt blobs as scorecard evidence** (analysis Q4): should the
   scorecard carry per-turn prompt-file paths/hashes so a moved number
   is diffable to the exact context bytes? Cheap to add in U1 —
   default-on unless vetoed.

## 7. Migration — correctness > continuity

- **Existing scenarios:** all current EDNs keep their ids; U2 edits
  s32's fixture text + predicate pattern, and U4 appends standing
  predicates everywhere. Both CHANGE what existing scenarios measure —
  that is the point. No compatibility shims, no `-v2` scenarios.
- **Existing baselines/scorecards:** scorecards are keyed
  (scenario × git-sha [× run-uuid after U2]); old cards remain valid
  records of old shas. After U2 and after U4 land, run one paid sweep
  to re-baseline the deepseek scenarios and note in the scorecard log
  which units the new baseline includes. Numbers will move — e.g. s32's
  consult predicate gets STRICTER (3.2), so a green→red flip there is
  the fix working, not a regression.
- **Known-RED encodings stay deliberate** (catalog §6): U4's standing
  G1 will be red on scenarios hitting the stub self-wake class until
  P21's fixes land; U8's scenarios are red until the wake path holds.
  Document expected-red in each scenario's `:doc`, never weaken the
  predicate.

## 8. In-flight work intersections

- **P21 (#35 loop economy):** its deferred terminates-under-cap
  predicates for S-32/S-12 belong to U4's standing G1 — do not
  duplicate them in P21; P21's replied-halt also changes
  scripted-replay turn counts, so the stub baselines re-run after it
  lands (the unowned hand-off from analysis Q3 — this PRD's owner runs
  it as part of U4's checkpoint). U8 depends on P21's halt.
- **P22 (#36 legibility):** the new warn wording changes warning-section
  bytes; the S-21 re-registration flake should be re-MEASURED against
  P22's wording (with U1's prompt predicates, which can finally see the
  warning text the agent saw) before anyone "fixes" it.
- **Live tiles PRD (2026-06-11):** §4.4 — reference only; the 4.3
  predicate shape will cover its `:seon.render/ai` twin when it lands.
- **Uncommitted working tree:** `src/seon/agent.cljs` / `ctx.cljs`
  carry in-flight P21/P22 edits; U1–U3 touch only `test/seon/gym/*`
  plus (U3, only if unavoidable) `ctx.cljs` — coordinate that one edit
  with the orchestrator.
