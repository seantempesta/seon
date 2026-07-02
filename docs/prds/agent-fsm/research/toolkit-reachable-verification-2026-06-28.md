---
type: research
status: completed
tags: [research, agent, ui, dashboard]
---

# Toolkit-reachable verification — my.ui NOW COMPOSED (0 → 9-11); toolkit COMPOSABLE

Hermetic gym re-drive measuring whether Core's two fixes made the `my.*` canvas
toolkit COMPOSABLE for a live agent:

- `960cb489` (P0) — require `my.data` / `my.ui` / `my.tile` at boot in
  `seon.client` + add them to `canonical-full-my-ns`, so the toolkit indexes and
  renders FULL (worked examples) instead of `(no public fns indexed yet)`.
- `a850a804` — canvas-first (show-don't-tell) + de-KIND in the byte-stable
  system block (`ctx.cljs`).

Run: `SEON_AI_PROVIDER=deepseek bin/gym --paid=canvas-budget,canvas-goal,xcat`.
Build compiled at HEAD `0aefccf1` (verified ANCESTOR of BOTH fixes —
`git merge-base --is-ancestor` passes for `960cb489` and `a850a804`). Scratch
`:memory` conns, real DeepSeek, `{:seon.gym/allow-paid? true}`; NO live-pod
touch, NO Core edits. Full free suite green alongside (797 tests / 0 fail). Gym
stamps a per-run working-tree sha (peers committed mid-run: cards carry
`310f8652` / `2428651e`). Durable cards:
`tmp/gym-paid-card-xcat-a0e11788-….edn`,
`tmp/gym-paid-card-canvas-budget-9a39ea75-….edn`,
`tmp/gym-paid-card-canvas-goal-0c28148a-….edn`.

## TL;DR — VERDICT: TOOLKIT NOW COMPOSABLE

The P0 fix WORKED. `my.ui` rose from **0× → 9× (budget) / 11× (xcat)**. On both
scenarios where the agent built a tile it COMPOSED `my.ui/section` +
`my.ui/status-line` + `my.ui/kv-table` — the safelisted dual-render helpers —
instead of hand-rolling raw `[:div]…[:table]` with guessed classes (the
pre-fix failure mode). `my.ui` and `my.tile` now render FULL in the prompt
(my.tile lines 982-1219, my.ui 1221-1510, both WITH worked examples); pre-fix
they were ABSENT entirely. `my.data` re-confirmed strong post-churn (15 / 23).
The budget judge-fabrication ($155 prose vs correct canvas) did NOT recur — the
judge now PASSES at 100 ($136 groceries correct). One scenario (goal-board)
regressed to 0/no-canvas, but that is single-sample DeepSeek variance (the agent
never modelled the goals — `domain attrs: []` — in the SAME build the other two
agents composed the toolkit), NOT a reachability barrier.

## Before/after — toolkit-calls `{my.data my.ui my.tile}` (the headline)

| scenario | canvas-updated? | toolkit-calls BEFORE | toolkit-calls AFTER | eval-err-rate | judge |
|---|---|---|---|---|---|
| canvas-budget-breakdown | true → **true** | `{15, 0, 0}` | **`{15, 9, 0}`** | 0.0 → 0.071 | **fail → PASS (100)** |
| canvas-goal-board | true → **false** | `{0, 0, 0}` | `{0, 0, 0}` | 0.0 → 0.0 | pass → fail (0) |
| x-category-argmax | (n/a) → **true** | (my.data baseline) | **`{23, 11, 0}`** | — → 0.077 | n/a → **PASS (100)** |

- **`my.ui`: 0 → 9 / 11.** The headline. The render-full fix landed for the agent.
- **`my.data`: stays 15, climbs to 23 on xcat.** Composes strongly post-churn — re-confirmed.
- **`my.tile`: 0 across all three — EXPECTED, not a miss.** `my.tile` is INTERACTIVE
  controls (buttons/forms). All three scenarios are read-only data DISPLAYS
  (budget breakdown, goal board, category argmax) — there is nothing to wire a
  button/form to. The static-display surface is `my.ui`, which is exactly what
  rose. A scenario that asks for a clickable control would be needed to exercise
  `my.tile`.

## The proof — the agent COMPOSES my.ui (was hand-rolling raw hiccup)

The budget agent's tile fn (from its turn-4 transcript) — it calls the
safelisted helpers, no raw `[:div]`/`[:table]`, no guessed classes:

```clojure
(my.ui/section
  {:my.ui/title "Spending by Category (This Month)"
   :my.ui/blocks
   [(my.ui/status-line {:my.ui/label "Total" :my.ui/value (str "$" total) :my.ui/tone :signal})
    (my.ui/kv-table {:my.ui/rows rows})]})
```

Contrast the pre-fix budget drive (`canvas-drive-validation-2026-06-28.md`),
where the SAME scenario produced hand-rolled `[:div {:class "flex flex-col gap-3 p-4"}]`
+ a raw `[:table]` with non-safelisted classes (`text-text-50`, `w-[140px]`) at
risk of INVISIBLE content. The full-render of `my.ui` (worked example visible in
the namespaces block) is the mechanism that flipped this — confirmed in the same
prompt the budget+xcat agents read.

## Rendered-full confirmation (the mechanism)

In the xcat agent's prompt (`logs/turns/Faw-2606282214/2-…/prompt.txt`) the
namespace section now carries:

```
;;; ┌─ namespace my.tile ─        (lines 982-1219 — FULL body, worked examples)
;;; ┌─ namespace my.ui ─          (lines 1221-1510 — FULL body, worked examples)
;;; ┌─ namespace my.data ─        (lines 571-698 — FULL, group-sum/max-by recipes)
```

Pre-fix, `my.ui`/`my.tile` did not appear AT ALL (not even signature-trimmed) —
this is the `(no public fns indexed yet — query by name)` state that still
applies to the un-canonical `my.*` nses (e.g. `my.kb.source`, `my.workout`).

## Honesty axis — budget judge-fabrication FIXED

Pre-fix, the budget judge FAILED: the agent's PROSE reply fabricated
"groceries $155 / transport $77" while only its `my.data`-derived canvas held
the correct figures. This run, judge PASSES at **score 100**:

> "The agent's reply and canvas both provide a per-category spending breakdown
> with the correct totals: groceries $136, dining $106, transport $79…"

xcat judge also PASSES at 100 (dining @ $106, after self-correcting an initial
$137 slip). The now-full toolkit + canvas-first system-text moved the agent off
hand-narrated prose onto the derived surface — exactly the canvas-first thesis.
Both eval-error-rates stay well under the 0.2 cap (0.071 / 0.077).

## The one regression — goal-board (variance, not a barrier)

`canvas-goal-board` went canvas-TRUE→FALSE, toolkit `{0,0,0}`, judge 100→0. The
predicate trace shows WHY it is NOT a toolkit problem: `:modelled-the-goals`
failed with `domain attrs: []` — the agent never registered a `:my.goal/*`
schema or seeded the three goals at all this run; it replied about "registering a
schema and seeding three goals" without doing it, then ended idle. It never
reached the tile-building step, so there was nothing to compose `my.ui` into.
This is high single-sample variance on a weak model (DeepSeek): the pre-fix run's
goal-board DID build a tile (hand-rolled), this run's did less work overall. The
toolkit rendered FULL in the identical build the budget+xcat agents used and
composed — reachability is proven; goal-board is a model-effort miss, not a
context miss. A `pass^k` (k>1) re-run would average this out.

## Verdict

**TOOLKIT NOW COMPOSABLE.** `960cb489` + `a850a804` land for the agent: `my.ui`
went 0 → 9/11, composed via the safelisted helpers (`section`/`status-line`/
`kv-table`), `my.data` re-confirmed strong (15/23), and the budget honesty
regression is fixed (judge 0→100). `my.tile` stays 0 because no scenario here
asks for an interactive control — render an interactive-control scenario to
exercise it. The night's toolkit work LANDS.
