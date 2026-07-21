---
type: research
status: active
tags: [research, agent]
---

# Agent-gym live run — collapsed runtime (2026-06-26)

Live DeepSeek drives of the AGENT-GYM against the freshly-collapsed run-model
runtime (HEAD `bf43a5f` — Snap-to-Tx + per-turn db threading + in-tx CAS
work-fence + since-t wake + the `seon.derive` leaf + index-everything), to
(a) catch runtime bugs and (b) confirm the gym measures the right thing.

## TL;DR — confidence verdict

- **Runtime: HEALTHY.** Eight live DeepSeek drives exercised every run-model
  path (wake → `open-run!` → turns → reply → close; error-recovery; multi-agent
  A→B cross-session; per-turn db threading + eval-result re-render; the derive
  leaf). **No runtime bugs found** — no instrumentation throw, no CAS misfire
  (no wrongly-rejected turn, no zombie write, no spurious "beat fence lost"),
  no wake/since-t miss, no derive inconsistency, no broken eval. Full CLJS
  suite: **593 tests / 2686 assertions / 0 failures / 0 errors** (incl. the
  re-enabled `paid_test`). Judge calibration discriminates (good 100 PASS,
  bad 0 FAIL). This is a "found no bugs," not a "proved bug-free" — but the
  green exercised real paths, not nothing.
- **Metrics: TWO stale-attr bugs the collapse introduced/exposed — both FIXED**
  in the gym lane, plus two flags.
  1. **FALSE-RED** — 8 scenarios asserted "agent ends idle" via the
     never-stored `:seon.agent/state` (state is now DERIVED). Always `[]` →
     always RED regardless of behavior. **Fixed** → derived-idle datalog.
  2. **FALSE-GREEN** — `consults-findings-run8` asserted the per-agent
     turn-cap via the dead session model (`:seon.agent/sessions` /
     `:seon.agent.session/turns`). Unregistered attrs → query maps to `[]` →
     `[:count<= 19]` passes VACUOUSLY (`0<=19`), hiding a cap overrun.
     **Fixed** → run-model `agent ← run ← turn` count.
  3. **FLAG** — `:*-replied-to-the-user` is satisfiable by the bootstrap
     greeting (hops 1).
  4. **FLAG** — s32 `:seeded-claim-rendered-in-prompt` asserts a render
     section (`:findings`) that was deliberately deleted (`d227b79`).
- **Observation** — `index-everything` (`bf43a5f`) ballooned the per-turn
  `:namespaces` context section to ~148k chars (~37k tokens), ~84% of the
  ~44k-token prompt.

## What I ran

- **run1**: `SEON_AI_PROVIDER=deepseek bin/gym --paid=calib,err,s21,x1`
  (build + free suite + 4 paid drives). Bundle compiled at `bf43a5f`.
- **run2**: `SEON_AI_PROVIDER=deepseek bin/gym --no-build --paid=all`
  (reused the `bf43a5f` bundle; scenario EDN is read at runtime, so the metric
  fixes were picked up without a rebuild). 9 paid items: s32, s21, s12, todo,
  err, calib, x1, x3, x12.
- Scenario count: 14 free (driver_test) + 8 paid drives + the calibration
  probe. Drive count: 12 distinct live DeepSeek agent runs across the two
  invocations (single- and two-agent).
- Isolation confirmed: every drive booted fresh agents on a scratch `:memory`
  conn via `client/open-agent-conn!`; the live default cluster (7890) and the
  acme harness (7980) were untouched (the pod REPL was down throughout —
  the gym never needs it).
- Re-enabled `test/seon/gym/paid_test.cljs.disabled` → `.cljs` (no old-model
  attrs in it — it only calls `run-scenario!`/`calibrate-judge!`; the run-model
  drift lived in the scenario EDN, fixed below). `+11` tests vs the 582 baseline.

> Note: run2 scorecards label `git-sha 65815a9` because HEAD advanced to
> unrelated web/time-travel commits between runs; the `--no-build` bundle under
> test is still the `bf43a5f` collapse compile.

## Runtime findings (priority) — none

The runtime held on every exercised path. Evidence:

- **Single-agent wake→run→reply→close** (`s21`, `s32`): clean 3-turn runs,
  `(wait …)` closed the run, pointer retracted → derived idle.
- **Error-recovery / CAS-fenced close** (`err-recovery`): the agent hit the
  unregistered-attr error envelope, ran `schema/register!` for `:my.books/score`,
  re-transacted onto the EXISTING `"The Hobbit"` entity (`:new-data-landed-on-the-seeded-entity`
  GREEN: `[:my.books/author :my.books/score :my.books/title]`), replied; 7 turns,
  close worked. No turn-:error misfire, no fence abort of a legitimate write.
- **Multi-agent + cross-session derive** (`x1`): A stored 5 subs → B booted
  cold on the same store → B's first message-driven eval correctly queried
  `:my.subscription/*` (`:b-discovery-reads-store-first` GREEN). **Per-turn db
  threading + eval-result render verified from the prompt blob**: B's turn-1
  query RESULT (`;=> #{["Netflix" 18 …] ["Adobe CC" 45 …] …}`) was persisted and
  re-rendered into turn-2's transcript, and the `:inventory` section showed the
  seeded kind. The runtime delivered the data; B's judge-fail ("generic
  greeting, no total") is answer quality (weak model), surfaced correctly on
  the SEPARATE judge axis — the gym's "behaved right, answered wrong"
  separation working as designed.
- **Bounded loop**: `s12` agent B ran 10 turns (under the 20 cap) and closed
  cleanly — no runaway, no turn-limit overrun.

## Metric-validity findings + fixes

### 1. FALSE-RED — stale `:seon.agent/state` idle predicate (FIXED, 8 scenarios)

`s21 s32 todo-multistep err-recovery consults-findings-run8 x1 x3 x12` each
asserted "agent ends idle" with `[?a :seon.agent/state :idle]`. The collapse
made state DERIVED — `src/seon/agent.cljs:35` "There is no stored
`:seon.agent/state`"; it is projected by `seon.derive/state-from-primitives`.
So the query returns `[]` ALWAYS, failing `:non-empty` / `[:count 2]`
regardless of whether the agent actually ended idle.

Live proof (run1, before fix): `s21` `:agent-ends-idle` = `pass? false`,
`actual "rows=[] expect=:non-empty"` while EVERY behavioral leg was GREEN
(row landed with reused attrs, no fork, zero registrations, replied,
terminates-under-cap) and the agent demonstrably idle. Same on `err` and `x1`
(`[:count 2]` → `rows=[]`).

Fix: the faithful derived-idle datalog (mirrors `derive/derive-state`'s `:idle`
= not terminated AND owns no open run):

```clojure
[:find ?a
 :where
 [?a :seon.agent/id _]
 (not [?a :seon.agent/terminated-at _])
 (not-join [?a]
   [?r :seon.agent.run/agent ?a]
   [?r :seon.agent.run/status :open])]
```

Live proof (run2, after fix): the idle leg flips GREEN on ALL 8 scenarios —
single-agent (`s32`, `s21`, `todo`, `err`) and two-agent `[:count 2]`
(`s12`/`x1`/`x3`/`x12`, e.g. `x1` `rows=[[1532] [1558]]` — was `rows=[]` RED in
run1). The `not-join` + `not` shape runs cleanly in datahike-cljs: **zero**
"predicate THREW" across both runs.

### 2. FALSE-GREEN — dead session-model turn-cap (FIXED, consults-findings-run8)

`:a-terminates-under-cap` / `:b-terminates-under-cap` queried the OLD session
model:

```clojure
[?ag :seon.agent/sessions ?s]
[?s :seon.agent.session/turns ?t]   ; both attrs removed by the run model
```

Those attrs are unregistered in the collapsed runtime. The driver's `:datalog`
handler maps a missing-attr query error to `[]` (`driver.cljs:706-711`), so
`expect-pass? [:count<= 19] []` → `0 <= 19` → **PASS**. A turn-limit overrun
(exactly what the predicate exists to catch) would be hidden as a vacuous green.

Fix: the run-model count (mirrors `seon.derive/agent-turn-count`):

```clojure
[?ag :seon.agent/id ?aid]
[?r :seon.agent.run/agent ?ag]
[?t :seon.agent.turn/run ?r]
```

Live proof (run2): the predicates now return REAL turns — `s12`
`:a-terminates-under-cap` `rows=[3 turns]`, `:b-terminates-under-cap`
`rows=[10 turns]` (was `[]` under the old query). B's 10 turns are now visible
to the cap check instead of silently passing.

### 3. FLAG — `:*-replied-to-the-user` satisfiable by the bootstrap greeting

`[?m :seon.agent.message/from ?a][?a :seon.agent/id _][?m hops ?h][(pos? ?h)]`
matches the boot-turn greeting `(message/user "Hi — I'm up …")` (hops 1, observed
in the x1 transcript), so the leg is GREEN even if the agent never answered the
question. Pre-existing (not collapse-caused); the judge axis covers the real
answer, so this is a weak/near-vacuous mechanical leg, not a wrong verdict.
Suggested fix (driver-level, deferred — out of "edit the EDN" scope): scope to
messages at/after the question's `:at` (the `q-from` logic `agent-reply-text`
already uses), or exclude the bootstrap run's cause.

### 4. FLAG — s32 `:seeded-claim-rendered-in-prompt` asserts a deleted section

The predicate asserts the seeded finding's full claim text
(`"the transaction report itself is swallowed at the boundary"`) renders in the
prompt via a `:findings` section. `d227b79 refactor(ctx): nuke findings
entirely — discoverability is :inventory + query` removed that section;
`findings.cljs` is gone and priority-48 is now the volatile `:relevant-source`
(which didn't fire). Seeded findings now render only as attr-name summaries in
`:inventory` (`my.kb.codebase: claim 4 question 4`). So the predicate is
permanently RED — even though the agent ANSWERED CORRECTLY (judge PASS, from the
now-fully-indexed `:namespaces` section). Per the gym's own "test the agent, not
the layout" rule this layout-coupled predicate is obsolete. Owner design call:
retire it, or re-point at inventory kind-discoverability (`prompt-includes
"my.kb.codebase"`) — I did not change it (its INTENT, the #26 salience pin, is
the owner's to redefine).

## Observation — context economy after index-everything

`bf43a5f` (index ALL public fns, drop the `:malli/schema` gate) ballooned the
per-turn `:namespaces` context section to ~147,920–148,156 chars (~37k tokens)
across every gym turn — ~84% of the ~44k-token prompt (turn open logs:
`159k–179k ctx-chars`). Informational only (turn-profiles never gate `pass?`),
but every agent now carries the full public-fn index every turn. Worth a
conscious owner decision given the standing "distance/volume degrades
comprehension" principle. (Side effect, neutral-to-positive: s32's agent found
the `message!` answer directly in the now-fully-rendered `seon.agent.message`
namespace instead of needing the seeded finding — which is why finding #4's
predicate is moot for the agent's actual success.)

## Files touched (gym lane only)

- `test/seon/gym/paid_test.cljs.disabled` → `test/seon/gym/paid_test.cljs` (re-enabled)
- `test/seon/gym/scenarios/{s21,s32,todo-multistep-tracking,err-recovery-unregistered-attr,consults-findings-run8,x1-subscriptions-total-and-max,x12-narrow-question-no-over-retrieval,x3-expense-reuse-and-category-total}.edn`
  — idle predicate → derived-idle; consults turn-cap → run-model; one stale
  `:seon.agent.turn/wake` comment corrected in x1.

No `src/` or `web/` changes (runtime issues are flagged, not fixed — no-cheating).
