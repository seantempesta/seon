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

## Cold batch 111 follow-up — protected-path stop, 2026-09-16

Red (3) requires the explicitly held `src/seon/render/transcript.clj`.
`render-run-ai` at line 785 returns empty text for a valid selected turn;
`render-run-html` at line 888 delegates only to `turn-header` (line 866).
Batch 111's retained log at
`tmp/orchestrator/gate-results/batch-111.log:294-325` independently records
the empty AI result and the header-only HTML result, including the missing
evaluation source and interrupted-turn explanation. This is a production
rendering boundary, not grounds to weaken the regression's assertions.

Required hunks: the selected-turn AI and HTML functions and their shared
turn presentation in that file, preserving selection of only the requested
turn. `git status --short` confirms the file remains dirty; the assignment
names its holder as `pulled-ref-is-a-ref` and explicitly requires stopping
when a hunk there is needed. No held file was edited and no other lane was
contacted. No production fixes or tests were run before this stop; reds
(1), (2), and (4) remain unverified and unresolved by this follow-up.
`bin/seon status` reported default alive (PID 41413); no prepl evaluation
was used. This note records the boundary only, not acceptance or a green
verification claim.

## Red (1) — carry the composition profile, 2026-09-16

The one permitted read-only default JVM evaluation reproduced the exact
`select` return-contract refusal for the missing `:seon.render.profile/id`.
`seon.print/elision` copies a supplied profile identity; it does not invent
one (`src/seon/print.cljc:982`). `acquire-context-report` now resolves the
request profile once, passes it to acquisition and to `select`, and returns
a profile refusal without attempting composition. `select` takes that profile
explicitly and carries its identity into the omission. The regression uses
`:seon.cluster.prompt-test/composition-profile`, so a hard-coded agent profile
would fail. The calibration fixture now supplies the history unit represented
by its text; the missing-units refusal remains intact.

Verification: `bin/test-fast seon.cluster.prompt-test` in the isolated
HEAD-plus-owned-edits worktree passed 13 tests / 96 assertions, zero failures
or errors (`tmp/s11-prompt-fast.log`). The strengthened nondefault-profile
assertion also completed green in `tmp/s11-prompt-concurrency-fast.log`.
The initial `--paths` invocation encountered the orchestrator-only marker;
the authorized plain fast runner in the isolated worktree supplied the proof.
No cold gate or post-edit default evaluation is claimed.
