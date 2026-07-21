---
type: research
status: completed
tags: [research, agent, gym, ui]
---

# Planning-canvas trigger — close the goal-board canvas-drive gap, prove with pass^k

pass^k (`research/gym-pass-k-2026-06-28.md`) measured `canvas-goal-board` at a
**1/3 canvas-drive rate**: on a PLANNING / GOAL ask the agent reached for its
canvas as the primary surface only 1 of 3 runs (the other 2 planned + messaged,
left the canvas blank, judge 0). The canvas-first guidance landed reliably for
DATA-DISPLAY asks (`canvas-budget-breakdown`) but inconsistently for
PLANNING / STATUS / GOAL asks. This closes the gap with a GENERAL guidance tweak
to the always-on live-tile block (not scenario-specific coaching) and re-measures.

## TL;DR — KEPT (accretive)

- **canvas-drive rate: 1/3 → 3/3** at k=3 on `canvas-goal-board` (DeepSeek). All
  three runs wired their own canvas this time.
- **scenario pass-rate: 0.333 → 0.667** (the one non-pass DROVE its canvas and
  halted idle — it missed a non-canvas predicate, not the canvas axis). judge-mean
  33.3 → 100.
- **No regression** on the data-display scenario: `canvas-budget-breakdown` at k=1
  stays canvas-driven (canvas-updated 1/1, pass 1/1, judge 100).
- **Stays lean:** the live-tile block measured 1948 tokens after the change — the
  tweak strengthened the existing "what belongs on the canvas" sentence rather than
  adding a new section.

Verdict: **KEPT** — committed the `live_tile.cljs` block change + the mirrored
`ui-canvas` skill line.

## The gap

`canvas-goal-board` asks: *"Three goals for me this quarter … give me a status
board I can glance at and keep an eye on as things move."* The honest answer is a
status board on the agent's OWN canvas (`:seon.render.live-canvas/content`), ideally
a tile fn so it re-derives. pass^k (sha 394520d1) found:

| run | canvas-driven? | toolkit | judge |
|---|---|---|---|
| 1 | yes | 7 | 100 |
| 2 | **no** (planned + messaged) | 0 | 0 |
| 3 | **no** (planned + messaged) | 0 | 0 |
| rate | **1/3** | 0–7 | 33.3 |

The 2 failing runs treated "make a plan / set goals" as todos + a prose message —
they never connected "I planned" to "render the plan as a board." The always-on
block already listed "a plan" among canvas-worthy things, but it read as one item
in a data-flavored list (status / data breakdown / result table) and did not fire
as a trigger on a PLANNING ask.

## The guidance change (before / after)

`src/seon/agent/ctx/live_tile.cljs` — the always-on `; ── THIS canvas is your
PRIMARY surface ──` block. General trigger, no scenario named, no answer coached.

BEFORE:

```
; Anything worth seeing at a glance — a status, a plan, a data
; breakdown, a result table, progress — belongs HERE, not recited
; in a paragraph. Set it with ONE transact of either literal
```

AFTER:

```
; Anything worth seeing at a glance — a status, a plan, goals, a
; checklist, a recommendation, a data breakdown, a result table,
; progress — belongs HERE as a board/view, not recited in a
; paragraph: a PLANNING / GOAL / STATUS ask answered only in prose
; (or only as todos) leaves this canvas blank — render the board
; FIRST, then narrate. Set it with ONE transact of either literal
```

The load-bearing addition is the one sentence: *a PLANNING / GOAL / STATUS ask
answered only in prose (or only as todos) leaves this canvas blank — render the
board FIRST, then narrate.* It names planning/goals/checklist/recommendation as
canvas content (a board/view), not just data tables, and makes "prose-or-todos
only" the explicit failure mode.

Mirrored in `seon-skills/ui-canvas/SKILL.md` ("When to message vs when to set
the tile") with the same general trigger: a plan / goals / checklist / status /
recommendation are canvas content too; a planning/goal/status ask answered only in
prose or todos leaves the canvas blank — render the board first, then narrate.

## The proof — k=3 before vs after (DeepSeek)

| metric | before (sha 394520d1) | after (sha 30bf0e8b) |
|---|---|---|
| pass-rate (k=3) | 0.333 (1/3) | **0.667 (2/3)** |
| canvas-updated-count | 1/3 | **3/3** |
| judge-mean | 33.3 | **100** |
| toolkit (my.ui) range | 0–7 | 7–16 |
| eval-error-rate-mean | 0.0 | 0.046 |

After-run scorecard line (`tmp/gym-passk-canvas-after.edn`):

```clojure
:seon.gym.battery/pass-rate 0.6667
:seon.gym.battery/canvas-updated-count 3
:seon.gym.battery/judge-mean 100
:seon.gym.battery/pass-k
  [{:seon.gym.scorecard/scenario          :canvas-goal-board
    :seon.gym.pass-k/k                     3
    :seon.gym.pass-k/passes                2
    :seon.gym.pass-k/rate                  0.6667
    :seon.gym.pass-k/canvas-updated-count  3
    :seon.gym.pass-k/toolkit-calls-min     7
    :seon.gym.pass-k/toolkit-calls-max     16
    :seon.gym.pass-k/judge-mean            100
    :seon.gym.pass-k/eval-error-rate-mean  0.046}]
```

The measured gap was the canvas-drive behavior; that is now **3/3** (was 1/3) —
every run designed its goal schema and wired a `goal-board` tile fn onto its own
canvas. The single non-pass (`Igh`) DROVE its canvas, replied, and halted idle
under cap — it missed a different (non-canvas) predicate, so the canvas trigger
itself fired on all three.

The minor eval-error-rate (0.046) is from two tile fns hitting `Unable to resolve
symbol` under SCI bounding (`todo/status`, `db/query` — unqualified aliases inside
a `my.agent.*` tile fn); both fell back to the unbounded compiled path and rendered
fine. Pre-existing alias-qualification footgun, NOT introduced by this change —
flagged for the toolkit/skill lane (the `ui-canvas` skill already warns to
fully-qualify inside a `my.*` ns).

## No regression — data-display stays canvas-driven

`canvas-budget-breakdown` at k=1 (same build, `tmp/gym-budget-regcheck.edn`):

```clojure
:seon.gym.battery/pass-rate 1
:seon.gym.battery/canvas-updated-count 1
:seon.gym.battery/judge-mean 100
```

Canvas-driven, correct breakdown, pass. The strengthened sentence still names the
data cases (data breakdown / result table), so the data-display trigger is intact.

## Driving it (repro)

```bash
# after (with the change) — k=3 on the goal-board planning scenario
SEON_AI_PROVIDER=deepseek bin/gym-scorecard --paid --k=3 \
  --only=canvas-goal-board --log=tmp/gym-passk-canvas-after.edn

# regression check — data-display still canvas-driven
SEON_AI_PROVIDER=deepseek bin/gym-scorecard --no-build --paid --k=1 \
  --only=canvas-budget-breakdown --log=tmp/gym-budget-regcheck.edn
```

## Takeaway

The canvas-as-primary framing was DATA-shaped; planning/goal/status asks needed
the trigger spelled out as a general rule. One load-bearing sentence in the
always-on block (mirrored in the skill) lifted the goal-board canvas-drive rate
from 1/3 to 3/3 with no data-display regression and no block bloat. Kept.
