---
type: research
status: completed
tags: [research, agent, ui, gym]
---

# my.canvas composability — the last toolkit piece, PROVEN

Hermetic paid gym drive of `interactive-tile-checklist` (DeepSeek), closing
the toolkit-composability proof. `my.data` (15x/23x) and `my.ui` (9x/11x)
were already proven composable; `my.canvas` (interactive controls) was the last
untested piece.

- **Scenario:** `:interactive-tile-checklist` (`test/seon/gym/scenarios/interactive-tile-checklist.edn`)
- **Run:** `bin/gym --paid=tile` — hermetic node-test JVM, no live-pod touch, no Core edits.
- **git-sha:** `2da444c5` · **run-id:** `1cb51403-4507-4816-940a-b0bae5b42517`
- **agent:** `GrZ-2606282304` (DeepSeek) · suite verdict: PASS 300s, 83/83 ns.
- **card:** `tmp/gym-paid-card-tile-1cb51403-4507-4816-940a-b0bae5b42517.edn`
- **agent evals (full):** `logs/turns/GrZ-2606282304/5-RhD-2606282304/prompt.txt`

## TL;DR — TOOLKIT FULLY PROVEN

`my.canvas` is composable. The agent composed **`(canvas/button …)` 3x**, each
**wired to its own handler fn** (`toggle-water!` / `toggle-vitamins!` /
`toggle-walk!`), inside a derive-from-db `morning-tile` fn it set as its
canvas. `toolkit-calls{:my.canvas} = 6`, `wired-to-an-own-fn` PASS,
`drove-its-canvas` PASS, judge **100/100**, **eval-error-rate 0**. All three
toolkit pieces (`my.data` / `my.ui` / `my.canvas`) now compose.

The scorecard's `pass? false` is driven by **two predicate-calibration bugs,
not a my.canvas failure** (detailed below). The headline criterion from the
drive — `toolkit-calls{:my.canvas} > 0` AND wired to an own fn — is met.

## The headline metric

```
:seon.gym.scorecard/toolkit-calls {:my.data 0, :my.ui 6, :my.canvas 6}
```

`my.canvas` referenced **6x** (whole-store eval-source scan). The 6 are the
fully-qualified keyword keys `:my.canvas/label` + `:my.canvas/action` across the
three buttons (2 keys x 3 buttons). `my.ui = 6` is `ui/section` +
`ui/progress` keys; `my.data = 0` (no aggregation needed for a checklist).

## The tile the agent built (verbatim from its evals)

The agent registered three boolean attrs on its own entity, defined three
handler fns, and a derive-from-db tile fn — then wired the fn-symbol to its
canvas:

```clojure
;; home ns require (seeded): [my.canvas :as tile] [my.ui :as ui]
(schema/register! :my.agent.me/morning-water    :boolean)
(schema/register! :my.agent.me/morning-vitamins :boolean)
(schema/register! :my.agent.me/morning-walk     :boolean)

(defn ^:async toggle-water! [_]
  (let [id  (db/current-agent-id)
        cur (-> (db/pull {:seon.db/db @db/*conn*
                          :seon.db/pull-pattern '[:my.agent.me/morning-water]
                          :seon.db/ref [:seon.agent/id id]})
                :my.agent.me/morning-water)]
    (db/transact! {:seon.db/tx-data
                   [{:seon.agent/id id :my.agent.me/morning-water (not cur)}]})))
;; toggle-vitamins! / toggle-walk! identical shape

(defn morning-tile [{:keys [seon.db/db]}]
  (let [eid   [:seon.agent/id (db/current-agent-id)]
        pull  (fn [attr] (-> (db/pull {:seon.db/db db
                                       :seon.db/pull-pattern [attr]
                                       :seon.db/ref eid}) attr))
        water    (pull :my.agent.me/morning-water)
        vitamins (pull :my.agent.me/morning-vitamins)
        walk     (pull :my.agent.me/morning-walk)
        done     (count (filter true? [water vitamins walk]))]
    (ui/section
      {:my.ui/title "☀ Morning Routine"
       :my.ui/blocks
       [(ui/progress {:my.ui/label "Done" :my.ui/current done :my.ui/total 3
                      :my.ui/tone (if (= done 3) :success :signal)})
        (canvas/button {:my.canvas/label (if water "✓ Water plants" "○ Water plants")
                      :my.canvas/action (list 'my.agent.GrZ-2606282304/toggle-water!)})
        (canvas/button {:my.canvas/label (if vitamins "✓ Take vitamins" "○ Take vitamins")
                      :my.canvas/action (list 'my.agent.GrZ-2606282304/toggle-vitamins!)})
        (canvas/button {:my.canvas/label (if walk "✓ 10-min walk" "○ 10-min walk")
                      :my.canvas/action (list 'my.agent.GrZ-2606282304/toggle-walk!)})]})))

(db/transact!
  {:seon.db/tx-data
   [{:seon.agent/id (db/current-agent-id)
     :seon.render.live-canvas/content 'my.agent.GrZ-2606282304/morning-tile}]})
;; => {:seon.db/ok? true … :seon.db/added 7}
```

This is the ideal answer: derive-from-db (booleans pulled live, progress
re-computed each render), `canvas/button` composed from the toolkit (not a
hand-rolled `[:button]`), each action wired to a fn the agent owns, canvas as
primary surface + an honest backup message.

`my.canvas` renders **FULL** in the namespaces block (the worked
`button`/`input`/`select`/`toggle`/`form` examples are visible at
`prompt.txt:977-1214`) — the agent had the worked example and used it.

## Scorecard — all predicates

| predicate | axis | pass? | actual |
|---|---|---|---|
| `:modelled-the-checklist` | models-work-directed | **false** | `domain attrs: [] expect=[:count>= 1]` |
| `:composed-an-interactive-control` | drives-canvas | **false** | `0/14 evals match my\.canvas/(button\|form\|input\|select\|toggle)` |
| `:wired-to-an-own-fn` | drives-canvas | true | `1/14 evals match :my\.canvas/(action\|submit)…'` |
| `:drove-its-canvas` | drives-canvas | true | `:seon.render.live-canvas/content PRESENT` |
| `:agent-replied-to-the-user` | replies-honestly | true | 2 outbound messages |
| `:makes-few-errors` | makes-few-errors | true | `eval-error-rate=0 max=0.2` |
| `:agent-ends-idle` | terminates | true | idle, no open run |
| `:terminates-under-cap` | terminates | true | 6 turns (<= 19) |
| `:judge-control-fits-task` | replies-honestly | true | **score 100** |

Judge justification: *"The agent built an interactive checklist with a toggle
button for each of the three requested items … the reply explicitly describes
the toggle behavior and progress bar, matching the rubric's requirement for an
actionable control covering all three tasks."*

Turn-1 context profile: `:namespaces` 18035 tok (the ~64% / #42 block),
`:live-tile` 1899, `:soul` 1933 — total ~24.8k tok.

## The two reds are predicate bugs, NOT my.canvas failures

**1. `:composed-an-interactive-control` is ALIAS-BLIND (false negative).** Its
regex is `my\.canvas/(button|form|input|select|toggle)`, but the seeded home-ns
require is `[my.canvas :as tile]`, so every agent calls the verb as
**`canvas/button`**, never `my.canvas/button`. The agent composed `canvas/button`
3x and the predicate scored it **0/14**. This predicate will give a false RED
on *every* correct composition. The robust signal — `toolkit-calls{:my.canvas}`
(=6) — caught it because it counts the fully-qualified keyword KEYS
(`:my.canvas/label`), which survive the alias. Fix: match `\b(my\.tile|tile)/…`
(accept the seeded alias), or scan for the keyword keys like toolkit-calls
does. (Owner-facing: a gym-integrity bug — flag, do not work around.)

**2. `:modelled-the-checklist` expects stored item datoms a button-checklist
never writes until clicked.** The agent modelled the checklist as three
boolean attrs flipped by the handlers; those datoms only exist *after a
button is clicked*, and the gym **can't click**. So the post-run store has no
`:my.agent.me/morning-*` datom → `domain-attrs []`. This is the same
"gym can't click, score the BUILT control not the round-trip" principle the
scenario doc states for the canvas axis — but the `:modelled-the-checklist`
predicate contradicts it by requiring stored state. Either the agent should
seed the three items as entities up front, or the predicate should accept the
registered schema / the wired handlers as the "modelling" evidence.

## eval-error-rate + the #73 caveat

**eval-error-rate = 0.** The task anticipated the known home-ns alias
collision (#73: an agent authoring a `db/`-using fn in a `my.*` ns hits
"db/transact! not defined"). **It did NOT manifest this run** — the agent's
home ns `my.agent.GrZ-2606282304` carried `[seon.db :as db]` in its seeded
require and `db/current-agent-id` / `db/pull` / `db/transact!` all resolved
cleanly inside its own `toggle-*!` and `morning-tile` fns. Whether #73 is
fixed or simply not exercised by this path, this drive was clean — note it,
but it is a separate gap and did not touch the my.canvas result.

## Verdict

**TOOLKIT FULLY PROVEN.** `my.canvas` composes: the agent built `canvas/button`
3x wired to its own handler fns, derived from db, set as canvas, judge 100,
zero eval errors. With `my.data` and `my.ui` already proven, all three
toolkit pieces compose. The scenario's `pass? false` is two predicate
miscalibrations (alias-blind control-scan + a click-dependent domain-attrs
expectation), each worth a precise gym fix, neither a my.canvas deficiency.
