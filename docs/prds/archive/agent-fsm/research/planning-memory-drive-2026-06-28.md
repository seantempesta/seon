---
type: research
status: active
tags: [research, agent, context]
---

# Planning + DB-memory drive — does plan→store→recall survive across two messages? (2026-06-28)

> Owner's "always be testing" loop. ONE un-coached DeepSeek child on the live
> default pod (7890), a realistic multi-step task (per CLAUDE.md "Exercising
> agents": long-term planning + DB-backed memory, store-then-recall across two
> human messages). Observe the REAL evals. No reset, no src edits, no coaching
> toward verbs. All sizes are TOKENS (`seon.ai.tokens/estimate`).

## TL;DR — the task SUCCEEDED, but the agent burned a turn re-doing its own setup and got knocked out of its home namespace

A fresh child (`AAt-2606282036`, parent `root`) was minted, armed, and given a
2-message trip-planning task. It **passed plan + store + recall**:

- **Planned** a todo tree up front (`todo/plan!` with 4 children incl. a
  depends-on "compute total").
- **Designed a schema** (`my.trip.city/{name,best-month,currency,daily-budget-usd,note}`)
  and **stored all 3 cities** with a rationale note each.
- **Computed the total correctly** — $280 (120+95+65) — and messaged it. **No
  fabrication** (contrast the discoverability drive's `fFy`, which messaged a
  number before it had the data).
- **Recalled across the 2nd message** — on a fresh run it re-required its home
  ns, queried the DB, and answered *cheapest = Mexico City $65 MXN, average =
  $93.33 (280÷3)* — all cited from a live query, all correct.

So the headline capabilities work. The drive's value is the **3 new frictions**
it surfaced, all stemming from ONE root: **the agent left its home namespace to
call a library verb, which silently stripped its home-ns verb aliases
(`message`/`wait`/`complete`).**

## Skeptic's bottom line — what BROKE

1. The agent tried bare `(plan! …)`, it resolved against its *current* ns and
   failed, and the error gave **no "did you mean `todo/plan!`?" hint** — so the
   agent went hunting for a way INTO `seon.agent.todo`.
2. It reached for `(in-ns 'seon.agent.todo)`. The `in-ns` error envelope
   **advised a full `(ns seon.agent.todo)` switch** — which the agent did — and
   that switch **stripped every home-ns verb alias**. `(message/user …)` and
   `(complete …)` then both failed "not defined" (evals 28-29). The agent only
   recovered by discovering the fully-qualified `seon.agent.message/user` /
   `seon.agent.lifecycle/complete`.
3. Turn 3 **re-ran the entire turn-1+2 setup** (re-registered all 5 schemas,
   called `plan!` a SECOND time) → a **duplicate plan tree** → 12 todos for a
   4-item plan, and ~8 redundant `done!` calls to clean up.

None of these is fatal (the agent self-corrected each time and finished
correctly), but together they roughly **doubled** the work and produced the
drive's only real token waste.

## The drive (live; writes = the mint + 2 human messages only)

```clojure
;; 1. mint under root
(seon.db/with-agent "root"
  (fn [] (seon.agent/start!
    {:seon.agent/purpose "…3-city trip; record best-month/currency/daily-budget
                          as schema'd data w/ provenance; plan it as todos;
                          compute total; recall in a later message…"})))
;; => AAt-2606282036

;; 2. arm (the #30 gap — a minted agent isn't woken by a message until this runs)
(seon.client/rearm-wake-triggers!)

;; 3. human message #1 (un-coached; never names a verb)
(seon.agent.message/message!
 {:seon.agent.message/from   seon.agent.message/user-ref
  :seon.agent.message/to     [[:seon.agent/id "AAt-2606282036"]]
  :seon.agent.message/origin :human
  :seon.agent.message/content "…record the facts I'll need … then tell me the TOTAL daily budget…"})

;; (agent runs 4 turns / 41 evals, messages $280, completes)

;; 4. human message #2 — the cross-turn memory test
;;    "…which city is cheapest + its currency? AVERAGE budget? Pull it from what you stored."
;;    (agent re-wakes: run Bxx, 1 turn, queries DB, answers, completes)
```

Observed via `mcp__seon_cljs__eval` (session `default`, all reads in
`(seon.db/with-agent "root" …)`) + `seon.agent.ctx/evals` + the store. 6 turns,
47 evals total across 2 runs.

## Did it plan + store + recall? — yes, verified against ground truth

```clojure
;; stored data (read back at observation time)
=> (["Lisbon" "May" "EUR" 95] ["Mexico City" "March" "MXN" 65] ["Tokyo" "April" "JPY" 120])
;; total messaged = 280  (120+95+65 = 280 ✓)
;; avg messaged   = 93.33 (280/3 = 93.33 ✓)
;; cheapest msg'd = Mexico City $65 MXN ✓
;; todos: 12 owned, 0 open (all closed) — plan tree used + drained
```

Every number the human saw was backed by a successful query the agent had
actually run. **Zero fabrication this drive.**

## The home-ns escape — verbatim, the load-bearing failure

```clojure
;; --- turn 1 (current ns = my.trip.city, the agent's own data ns) ---
6  FAIL (in-ns 'seon.agent.todo)
       => "in-ns is not available in this runtime — use (ns seon.agent.todo),
           same effect: it switches your namespace and your prompt follows."
7  FAIL (plan! {:seon.agent.todo/title "Record trip facts for 3 cities" …})
       => "`my.trip.city/plan!` is not defined — you have not defined it …"
;; --- turn 2: it TOOK the in-ns error's advice and switched ns wholesale ---
8   ok  (ns seon.agent.todo "Your plan + work queue as a TREE. …<186-tok docstring>…")
9   ok  (seon.agent.todo/plan! {…})            ; works, but now NOT in home ns

;; --- turn 3: RE-RAN the whole setup again (dup), still in/around todo ns ---
…   (ns my.trip.city …) + 5×(schema/register! …) + (in-ns…)FAIL + (plan!…)FAIL
    + (ns seon.agent.todo …) + (seon.agent.todo/plan! …)  ; SECOND plan tree
21  ok  (db/transact! {:seon.db/tx-data [<Tokyo 120><Lisbon 95><Mexico City 65>]})

;; --- turn 4: closing todos, then tries to report — HOME ALIASES ARE GONE ---
28 FAIL (message/user "…Total … $280")
       => "`message/user` is not defined — you have not defined it …"
29 FAIL (complete "All three trip cities recorded … $280.")
       => "`seon.agent.todo/complete` is not defined …"   ; resolved in todo ns!
;; recovery: fully-qualify
38  ok  (seon.agent.message/user "…**Total … $280**")
39  ok  (seon.agent.lifecycle/complete "…Total daily budget: $280.")

;; --- turn 5 (2nd message): it LEARNED — re-require home ns to restore aliases ---
41  ok  (ns my.agent.AAt-2606282036 (:require [seon.agent.message :as message] …
                                               [seon.agent.todo :as todo]))
42  ok  (db/query '[:find ?name ?currency ?budget :where …])
43  ok  (seon.agent.message/user "…Cheapest: Mexico City $65/day MXN … Average: $93.33…")
44  ok  (seon.agent.todo/done! {…})
;; --- turn 6 ---
46  ok  (seon.agent.lifecycle/complete "Answered …")
```

The `(complete …)` failure resolving to **`seon.agent.todo/complete`** (eval 29)
is the smoking gun: the agent is no longer in `my.agent.AAt-…`, so the
`:refer`'d lifecycle verbs are gone and bare `complete` binds against whatever
ns it's sitting in.

## NEW / ranked findings for the queue (file:line where known)

1. **`in-ns` error advises a namespace switch that STRIPS the home-ns verb
   aliases — `src/seon/eval.cljs:2410`.** The message *"use (ns seon.agent.todo),
   same effect: it switches your namespace and your prompt follows"* is true but
   harmful in the common case (the agent wanted to *call* a verb, not *define*
   in that ns). Switching loses `message`/`wait`/`complete`/`schema`/`db`/`todo`
   aliases from the home require head. **Fix:** the in-ns envelope should
   distinguish CALL from DEFINE — e.g. *"to call a fn in another ns use its alias
   (`todo/plan!`); only `(ns …)`-switch to DEFINE there, and note it replaces
   your home aliases (`message`, `wait`, `complete`)."* This single envelope
   change would have prevented the entire detour.

2. **"`X/foo` is not defined" gives no alias suggestion — the missing hint that
   launched the whole detour.** The agent wrote bare `(plan! …)`; it resolved to
   `my.trip.city/plan!` and failed with no *"did you mean `todo/plan!`?"*. The
   home require renders `[seon.agent.todo :as todo]`, so the agent knows the verb
   NAME (`plan!`/`done!`) but not that it lives behind the `todo/` alias. A
   "not-defined" error that scans the home-ns aliases' publics for a matching
   short name and suggests the qualified form (`todo/plan!`) closes finding 1 + 2
   together. Core unbound-symbol error path (`seon.eval`).

3. **No cross-turn "already done" awareness — turn 3 re-ran turns 1+2 wholesale.**
   It re-registered all 5 schemas (idempotent, harmless) AND called `plan!` a
   SECOND time, minting a **duplicate plan tree** (12 todos for a 4-item plan)
   that cost ~8 redundant `done!` calls to drain. The prior turns' work was in
   the transcript (only ~4k tok — not evicted), so this is the agent not trusting
   / not recognizing its own prior state, not a context-window problem. Mirrors
   discoverability-drive finding 4 (over-work). Candidate mitigations are
   context-side: surface the live plan-tree state and the existing `my.trip.city`
   rows so "this is already done" is visible without re-running.

4. **Provenance was stored as a prose `:note`, not a structured source field.**
   The task asked for facts "with provenance"; the agent put the rationale in
   `:my.trip.city/note` ("April for cherry blossoms…") rather than a distinct
   provenance/source attr. Not wrong, but if we want agents to model provenance
   as first-class data, the `my.kb`/store-then-retrieve exemplar should show a
   `…/source` attr explicitly (the agent imitates the exemplar's shape).

## result/<id> in-form Promise trap — NOT exercised; fix present, no live proof

This agent **never triggered the trap** — every eval resolved synchronously (no
auto-await timeout, no `defer`), so there is **no live agent proof** of the
self-heal in this drive. The fix from commit `8f2f8c50` is confirmed present at
`src/seon/eval.cljs:2640-2645`: the pending? branch attaches
`.then`→`stash-result-raw!`+`bind-result-var!` (with a no-op `.catch`) so the
resolved value replaces the raw Promise at `result/<id>` once it settles.

**Caveat to verify separately:** the self-heal only repairs the stash AFTER the
Promise settles. An in-form reference in the *immediately-next* eval, fired
before the Promise resolves, would still read the raw Promise — the timing window
is narrowed, not closed. A targeted drive that forces a slow `(defer …)` then an
in-form `(mapv k result/<id>)` is still owed.

## Per-tool token waste — NO tool output is bloated; the waste is agent-authored

Whole transcript = **4155 tok / 47 evals**. Split:

| component | tokens | share |
|---|---:|---:|
| agent-authored **source** | 2317 | 56% |
| **narration** (agent prose-before-form) | 1387 | 33% |
| eval **result-edn** (tool output) | 451 | 11% |

The **eval result is well-clipped** — the worst single result-edn is 49 tok
(`plan!`'s envelope); most are 0-31. **No tool's output is bloated** in this
drive; the clipping/elision is doing its job.

The actual waste is the agent **re-pasting the full `(ns seon.agent.todo
"…186-tok docstring…")` form THREE times** (evals 8, 18, 23 = 558 tok ≈ 13% of
the transcript) — a direct symptom of findings 1+3, not a tool-output problem.
Narration at 33% is notable but it's the agent's own reasoning, not waste per se.

```clojure
;; worst rows by (src+result+narration) tokens
=> ({:i 21 :src 242 :res 31 :nar 7  :total 280 :head "(db/transact! …<3 cities>…"}
    {:i 18 :src 186 :res 0  :nar 56 :total 242 :head "(ns seon.agent.todo \"Your plan…"}  ; re-paste #2
    {:i 23 :src 186 :res 0  :nar 41 :total 227 :head "(ns seon.agent.todo \"Your plan…"}  ; re-paste #3
    {:i 8  :src 186 :res 0  :nar 9  :total 195 :head "(ns seon.agent.todo \"Your plan…"}  ; re-paste #1
    {:i 43 :src 112 :res 24 :nar 59 :total 195 :head "(seon.agent.message/user \"Here's…"})
```

## Bottom line

**Plan + store + recall: SUCCESS, and honestly** — correct $280 / $93.33 /
cheapest-Mexico-City, all cited from real queries, **zero fabrication** (the
fabrication that bit `fFy` did not recur here). Cross-turn DB memory works: the
2nd message re-woke the agent, which re-required its home ns and answered from a
live query.

The binding constraint this drive exposes is **namespace ergonomics**: the agent
fell out of its home ns trying to call a library verb (`todo/plan!`), guided
there by an `in-ns` error that advises a destructive full-ns switch, and that
cost it its `message`/`wait`/`complete` aliases mid-task. Findings 1 + 2 (the
in-ns envelope + the missing alias suggestion on "not-defined") are the
highest-leverage, lowest-risk Core fixes — together they'd have collapsed this
33-eval-of-real-work task to ~12 and kept the agent home the whole time.
