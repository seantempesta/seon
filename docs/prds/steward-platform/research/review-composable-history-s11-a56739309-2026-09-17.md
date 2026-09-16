---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, prompt, history, S11, F3]
---

# Orchestrator review — S11 composable history (`a56739309`)

Read: the landing note, the diff stat (13 files, +858/−58), the
`src/seon/cluster/prompt.clj` additions (`priced-unit`, `retained-count`,
`dropped-elision`, `selection-segments`, `select`, `compose`), the transcript
removal (`bounded-scalar`, `floor-text` and their two call sites), the schema
additions, and the three regression files' stats.

**Accepted.**
- `select` is pure over units, budget, calibration and agent identity; it
  keeps the newest whole units the budget admits, oldest dropped first, and
  produces ONE elision value (`:seon.print/elision-unit :evaluations`,
  `:seon.print/bound-by :seon.config.ai/prompt-token-budget`, omitted count,
  next offset, requery form). `compose` joins them with the elision named
  first. `budget-report` stays the verdict on the composed result.
- Each unit's token estimate is computed at composition from its stored
  bytes, never stored (`priced-unit`).
- The second clipping spot is deleted: `bounded-scalar`/`floor-text` and
  both callers are gone from the transcript; nothing re-fits a rendered unit.
- `;;` comments render as their own thinking block on the page
  (`seon-eval-thinking`); the AI pair is unchanged.
- The lane correctly REPLACED the previous lane's reach-based regression
  (acceptance (d)) with a behavioural one: reach from the render family
  legitimately includes `seon.render.value/render-ai`, the one clipping spot,
  so "does not reach fit-text" was vacuous or wrong; the behaviour assertion
  (a 4,000-char unit crosses composition whole under budget 1 with no
  character prefix) is the right pin.
- Live on `default`: at the shipped 1,000,000 budget the composed history is
  byte-identical to the previous join (10,612 chars); at budget 300, the 3
  newest whole units plus one elision value and no prefix; the transcript
  render carries zero omissions.

**Follow-ups filed by the lane, accepted as separate small slices:**
`seon.render/captured-history` compares a selected capture against an
unselected join (`render.clj`, outside its paths; inert at the shipped
budget); three `:entity-id/syntax` log lines when rendering Juniper's
transcript (an id string handed where an eid belongs; pre-existing).

**Boundary accepted:** no in-process run possible (the publication
rejection blocker); the cold gate is the first execution of the new
regressions. **Gate requested:** platform, then `seon.cluster.prompt-test
seon.render.transcript-run-test seon.concurrency-independence-test
seon.render.web-debug-test seon.repl-test`, once a base can be built.
