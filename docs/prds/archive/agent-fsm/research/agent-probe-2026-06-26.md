---
type: research
status: active
tags: [research, agent, flow]
---

# Live agent context probe — adk-2606262009 (2026-06-26)

Goal-oriented, behavior-verified context audit. Drove the live default-pod
agent (`adk-2606262009`, DeepSeek) through five probe tasks via `POST /chat`,
read its VERBATIM per-turn LLM responses (`logs/turns/adk-2606262009/`, debug
capture forced on for the run), read the actual eval log
(`:seon.eval/source` / `:seon.eval/result-edn` / `:seon.eval/ok?`), and
VERIFIED every result against the live DB with my own `db/query`. North star:
does the agent's natural, unprompted move turn out correct?

## TL;DR — headline findings

1. **Result-vars teaching does NOT land. 0 / 54 evals referenced `result/<id>`.**
   Across all five probes the agent NEVER once passed a `result/<id>` var into a
   later form. For the sum-then-square probe it copied the literal `29` into
   `(* 29 29)`. The teaching (prompt "RESULT VARS", lines 67-77) is detailed and
   present; it produced no behavior. (Caveat: probe values were trivial scalars
   that are cheaper to retype than to reference — the probe set never made a
   result-var the obviously-easier move. See Probe 1.)

2. **Premature / duplicate messages to the human on ANY multi-turn task — the
   most user-visible bug.** The human received 10 messages for 5 tasks; 6 were
   spurious. The agent writes the happy-path batch — including
   `(message/user "Done…")` — in ONE turn, predicting `;=>` success it has not
   seen. When an earlier form in the batch actually fails, the "Done" message
   has already fired; the next turn redoes the work and messages AGAIN. Probes
   1/2/3 each sent a false-or-premature "done" plus a duplicate. Probes 4/5
   were clean ONLY because they happened to complete in a single batch.

3. **First `defn` in a fresh home namespace fails with a cryptic JS error and
   cost 6 wasted turns.** `(defn highest-rated-titles [] (db/query …))` threw
   `Cannot read properties of undefined (reading 'adk_2606262009')` —
   referencing the agent's OWN munged ns, not `db`. The identical defn shape
   works in `cljs.user`. It only started working after the agent re-evaluated
   its `(ns …)` form (turn 17), which then BROKE the bare `wait`/`complete`
   refers. This is the single worst context/runtime experience in the run.

4. **Bare prose is evaluated as code when it contains a parenthesized list.**
   The agent's un-`;`-prefixed reasoning "two books tied (Piranesi and The
   Dispossessed)" was parsed and evaluated → `my.agent…/Piranesi is not
   defined`. Same for quoting an error: `(reading 'adk_2606262009')`. The parser
   (`seon.repl.internal`) demotes bare symbols/strings/maps/backtick-forms to
   prose, but a list `(…)` is ALWAYS code, so natural-language parentheticals
   slip through and pollute the transcript with spurious errors.

5. **Envelope-as-value teaching lands PERFECTLY (Probe 4).** Asked to transact
   an unregistered attr, the agent read `{:seon.db/ok? false :seon.db/error …}`
   as a value and told the human verbatim: "returned an error envelope … rather
   than throwing an exception … the write did not happen … to fix it, run
   `(schema/register! :my.reading/pages :int)`." Verified accurate.

6. **The good tool choices:** the agent used `:with ?e` in its `(sum ?r)` query
   and got the correct total 14 (the dedup footgun returns 9) — it learned
   `:with` from `db.examples`. It handled the highest-rated TIE correctly. Final
   answers for all 5 tasks were correct; only the PATHS were buggy.

7. **Self-critique is half-honest (Probe 5).** The agent led with the real pain
   (the 6-turn defn disaster) — not pure sycophancy. But it MISATTRIBUTED two
   root causes (blamed the `db` alias and, worse, blamed the SYSTEM PROMPT for
   the `wait`-not-bound issue that its OWN ns-rewrite caused), and was entirely
   BLIND to the premature/duplicate messages it sent the human and to its zero
   result-var usage.

---

## Method

- Wake: `POST http://127.0.0.1:7890/chat?agent=adk-2606262009` form param `text`
  (`seon.web.serve/handle-chat!` → `seon.agent/message!`). One task at a time;
  waited for the loop to park (`halt verb` in `logs/pod.log`) before the next.
- Observe: forced `(seon.debug/set-override! :on)` → verbatim prompt+response per
  turn under `logs/turns/adk-2606262009/<n>-<turn-id>/`; cross-read the eval log
  (`:seon.eval/*`) via `db/query` on the pod. Restored `:env` at the end.
- Verify: every numeric/stored claim re-derived with my own `db/query` against
  `@seon.db/*conn*`.

---

## Probe 1 — RESULT VARS  ("sum of [3 5 8 13], then its square")

Verified final answer: **841 (correct).** Natural move correct; mechanism missed.

Actual evals (turns 1-4): `(seon.agent.todo/complete! …)` for the inbound-message
todo, two `add!` sub-todos, `(+ 3 5 8 13) ;=> 29`, then **`(* 29 29) ;=> 841`**.
The sum's result var was annotated right there in the transcript
(`;=> 29 ; result/cmz-2606262022`); the agent IGNORED it and re-typed the literal
`29`. Final `(message/user "Sum … = 29, and 29² = 841.")`.

- **Context observation (probe-design, not a pure bug):** for a 2-char scalar,
  literal-copy is the natural human move and is actively ENCOURAGED by the
  "REPORT THE VALUE YOUR LAST EVAL RETURNED … quote its real result" section
  (prompt lines 61-65), which sits just above the "RESULT VARS … reference
  `result/<id>`" section (67-77). The two create a mild tension and, for small
  values, literal-copy wins. To actually exercise/falsify the result-var
  teaching, a probe must use a value that is PAINFUL to retype (a large nested
  map / collection) — the current probe set never does, and adoption was 0/54.

- **BUG — transcript-echo degeneration (turn 6).** After answering (turn 4), the
  loop did not stop because the agent never emitted a halt verb (turn 5 was
  PROSE ONLY: "I'll go idle" with no `(wait)` form). On the next forced turn the
  model REGURGITATED ITS ENTIRE TRANSCRIPT as new forms — re-running the boot
  `(message/user "Hi — I'm up …")`, `(wait …)`, and every probe form. Verified
  side effects: the human received a DUPLICATE "Hi I'm up" (00:22:55) and a
  DUPLICATE answer (00:22:58), and TWO orphan `:open` todos were left behind.
  Root chain: prose-only turn with no halt verb → extra turn → small-model
  transcript-echo when the prompt ends at a bare `=>` with nothing to do.

## Probe 2 — DB + TOOL CHOICE  ("store three readings … total rating")

Verified: 3 readings stored (Piranesi 5, Exhalation 4, The Dispossessed 5),
`(sum ?r) :with ?e` = **14 (correct)**; the footgun `(sum ?r)` without `:with`
= 9. Schema got registered. Final answer correct.

- **GOOD:** the agent used `(seon.db/query '[:find (sum ?r) . :with ?e :where
  [?e :my.reading/rating ?r]])` — it knew the `:with` dedup fix from
  `db.examples` and reported 14, not 9.

- **CONTEXT BUG — `db.examples` reads as "already registered/live".** Turn 7 the
  agent transacted `:my.reading/*` WITHOUT registering, narrating "the
  my.reading schema is already registered from db.examples." It is not:
  `db.examples` (prompt lines 640-696) presents `register-reading-schema!` and
  `seed-readings!` as runnable recipe `defn`s over the `:my.reading/*` demo
  domain, framed as "runnable … exercised by `seon.db.examples-test`." Seeing
  that schema code in-context, the agent concluded the schema was live. The
  actual transact returned `{:seon.db/ok? false … "Unregistered attributes …"}`.
  The "register first" law IS stated (prompt lines 103-104) and the my.kb worked
  example shows it, yet the recipe `defn`s using concrete `:my.reading/*` attrs
  still misled the first move.

- **Recovery = organic Probe 4 (GOOD).** Turn 8 the agent READ the error
  envelope as a value, diagnosed it correctly ("the db.examples ns only
  registers them in its own test context"), ran `(schema/register! :my.reading/id
  …)` ×3, re-transacted, re-queried 14, closed the CORRECT todo ids. Live proof:
  the recorded turn-7 transact result is `:seon.eval/ok? true` with value
  `{:seon.db/ok? false …}` (an envelope, no throw). Note the `(sum ?r)` QUERY on
  the unregistered attr actually THREW (`:seon.eval/ok? false`) — guarded-query
  throws while `transact!` returns an envelope; the agent handled both.

- **BUG — hallucinated `;=>` with real consequences.** Turn 7 pre-wrote `;;=>`
  success lines AND acted on PREDICTED todo ids (`JcB`, `WHK`) that the runtime
  never assigned (real ids were `oXh`, `Uwj`), so its turn-7 completes were
  no-ops; it also sent the "Stored three readings … Total 14" message at 00:26:42
  BEFORE the transact had succeeded (it hadn't), then sent it AGAIN at 00:26:53.
  One premature/false + one duplicate.

## Probe 3 — BUILD-TEST LOOP  ("fn returning the highest-rated reading title, prove it")

Verified: `highest-rated-titles` returns `["Piranesi" "The Dispossessed"]`
(both rated 5, the max) — **correct, ties handled.** But it took **11 turns
(10-20)** for one function.

- **MAJOR BUG — first `defn` in the home ns fails cryptically.**
  `(defn highest-rated-titles {:malli/schema … :test (fn [] (assert (not (empty?
  (highest-rated-titles)))))} [] (db/query …))` threw `Cannot read properties of
  undefined (reading 'adk_2606262009')` (the agent's OWN munged ns, not `db`).
  The same defn shape — incl. a self-referencing `:test` and `db/query` — works
  fine in `cljs.user` (I reproduced: both pass). So the failure is HOME-NS
  specific and this was the FIRST `defn` ever attempted in the freshly-seeded
  `my.agent.adk-2606262009` (probes 1-2 only CALLED fns). It only began working
  after the agent re-evaluated its `(ns …)` form (turn 17). Hypothesis for the
  fix loop: the home ns created by `seon.eval/setup-agent-ns!` (eval.cljs:1094)
  is not materialized to receive `defn` vars until the ns form is (re-)analyzed —
  reproduce against a fresh minted agent's first defn.

- **The error is undebuggable.** Turns 12-16 the agent flailed (swapping
  `db/query`↔`seon.db/query`, `(identity db)`, finally `(ns-map …)`); the JS
  TypeError names neither `db` nor "alias" nor "unmaterialized ns", so even a
  capable model can't recover quickly. The failed-eval error surface needs to be
  legible.

- **BUG — re-require breaks the bare verbs.** The agent's turn-17 fix re-wrote
  the ns form as `[seon.agent.lifecycle :as lifecycle]` (an alias) instead of the
  seeded `[… :refer [wait complete pause resume terminate]]`. Consequently
  `(wait …)` then failed "not defined" (turns 19-20) until it discovered
  `(seon.agent.lifecycle/wait …)`. A cascade caused by the agent's own repair of
  bug #3.

- **BUG — prose evaluated as code (root-caused).** Recorded failed evals include
  `(Piranesi and The Dispossessed)` → "Piranesi is not defined" and
  `(reading 'adk_2606262009')` → "reading is not defined" and a backtick READ
  ERROR — all from the agent's un-`;`-prefixed reasoning. `seon.repl.internal`
  demotes bare symbols/strings/maps/backtick-forms to prose, but a parenthesized
  LIST is always treated as a form, so any natural-language parenthetical is
  evaluated. DeepSeek writes paragraph reasoning without `;` prefixes, so this
  fires whenever a parenthetical appears.

- **BUG — premature/false "Done" message.** Turn 11 pre-wrote `;=>` success and
  sent `(message/user "Done. highest-rated-titles returns […]")` at 00:29:13 —
  while the defn had FAILED and the fn did not exist. The real "Done" went at
  00:29:55 (turn 17). One false + one duplicate to the human.

- **Build-test loop is SHALLOW.** The agent DID attach `:test (fn [] (assert
  …))` metadata (matching prompt lines 152-155), but it never ran the assertion
  as an independent check — "proof" was just calling `(highest-rated-titles)`
  and reading the value. The define→assert→run loop the probe targeted did not
  happen as a deliberate step; the `:test` was decorative.

## Probe 4 — ENVELOPE-AS-VALUE  ("transact an unregistered attr, tell me what happened")

Verified: `:my.reading/pages` unregistered, row `r13` NOT created, reading-count
still 3, recorded result `{:seon.db/ok? false :seon.db/error {…Unregistered…}}`.

- **PASS — teaching lands perfectly, no sycophancy.** Single clean batch
  (turn 21). The agent told the human: "returned an **error envelope** —
  `{:seon.db/ok? false}` — rather than throwing an exception … the write did
  **not** happen … the whole transaction was rejected … to fix it I'd run
  `(schema/register! :my.reading/pages :int)` and re-transact." Every claim
  verified true, incl. atomic rejection. It parked correctly with
  `(seon.agent.lifecycle/wait …)`. This is the cleanest probe — and notably the
  only outcome with exactly one human message — because it fit in one turn.

## Probe 5 — SELF-CRITIQUE (cross-checked against the transcript)

The agent's reflection (turn 24) vs what its transcript ACTUALLY shows:

| Agent's claim | Cross-check |
|---|---|
| #1 "`db` alias not in scope for `defn`, 6 turns, opaque error, re-require fixed" | Symptoms TRUE (6 turns 11-16; opaque error; re-require fixed). Root-cause MISATTRIBUTED: the error was about its own ns `adk_2606262009`, not `db`; even in reflection it can't name the real cause — evidence of how opaque the error is. |
| #2 "stale todos from a prior session; backtick comment choked the reader" | TRUE. Turn 18 + recorded backtick parse errors. |
| #3 "system prompt says `(wait …)`/`(complete …)` as bare fns but they're `lifecycle/…`" | WRONG / deflected. They WERE bare (seeded `:refer [wait complete …]`); the agent's OWN turn-17 re-require replaced the refer with an alias and broke them. It blames the context for self-inflicted breakage. |
| #4 "my.reading wasn't registered; db.examples registers it in test context only; handled well" | TRUE and well-characterized. |
| #5 "no backticks in prose comments" | TRUE. |

- **Honest about felt pain** (led with the worst issue) — NOT pure sycophancy.
- **Blind spots (never mentioned):** the premature/false "Done" and "Stored"
  messages and duplicates it sent the human; the prose-evaluated-as-code class
  (it noticed backticks but not `(Piranesi …)`); zero result-var usage (no felt
  pain, so invisible to it). The damage the agent CAUSES but cannot PERCEIVE is
  exactly what a self-report misses — verify behavior, never trust the report.

---

## Cross-cutting findings (for the fix loop)

- **A. Batch + predicted-`;=>` ⇒ premature side effects.** The model emits the
  full happy path (incl. the human message and todo-completes) in one batch,
  pre-writing `;=>` it has not seen. On any failure-or-multi-turn task this fires
  messages/mutations on optimistic assumptions, then repeats them next turn. The
  "write form, read `;=>` next turn" format directly fights the model's instinct;
  the human inbox and the todo set both get polluted (verified: 6 of 10 messages
  spurious; orphan todos in probes 1-3).
- **B. Loop continues without an explicit halt verb.** A prose-only or
  side-effect-only turn does not park; the agent must remember to emit
  `(wait …)`. When it forgets (probe 1 turns 4-5), the next turn degenerates
  (transcript echo). Consider: idle when there are no open todos + no new inbound
  and the last turn produced no new work, rather than requiring a verb.
- **C. First-`defn`-in-home-ns is broken.** Blocks the core "colocate fns with
  schemas" workflow on the very first attempt. High priority — reproduce against
  `setup-agent-ns!` / home-ns materialization.
- **D. Opaque failed-eval errors.** Raw JS `TypeError`s (`Cannot read properties
  of undefined …`) reach the agent with no mapping to the real cause. The agent
  cannot self-correct (and cannot even self-diagnose in reflection) from them.
- **E. Prose-as-code for parenthetical lists.** `seon.repl.internal` should also
  demote a list that is plainly prose (or the context must train hard `;`-prefix
  discipline); today any `(…)` in un-commented reasoning is executed.
- **F. Result-vars: 0 adoption.** Either the value is never painful enough to
  matter (drop/shrink the teaching) or the teaching needs a worked example where
  referencing `result/<id>` is unmistakably the easier move. Measure with a
  large-value probe before deciding.

## What WORKS (keep)

- `:with ?e` dedup idiom learned from `db.examples` (correct 14 vs footgun 9).
- Error-envelope reading + recovery (probe 2 turn 8; probe 4) — excellent.
- Tie handling in the highest-rated fn.
- All five final answers were CORRECT; the failures were path/UX, not logic.
- The agent surfaces real pain honestly when asked (just misdiagnoses cause and
  is blind to self-caused side effects).

## Live-proof appendix (verifiable)

- Messages to user (`adk-2606262009`), 10 total; spurious = 00:22:55, 00:22:58
  (echo dupes), 00:26:42 (premature stored), 00:26:53 (dup), 00:29:13 (false
  Done), 00:29:55 (dup).
- `(sum ?r) :with ?e` = 14; `(sum ?r)` = 9; readings = 3 (r10/r11/r12).
- `:my.reading/pages` unregistered, r13 absent (probe-4 atomic rejection).
- `result/<id>` referenced in 0 of 54 agent eval sources.
- Probe-3 defn error recorded verbatim: `Cannot read properties of undefined
  (reading 'adk_2606262009')`; same shape passes in `cljs.user`.
</content>
</invoke>
