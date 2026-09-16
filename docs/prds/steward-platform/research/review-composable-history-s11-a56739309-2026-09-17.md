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

## Red (3) — selected-turn rendering restored, 2026-09-16

The file was released by `eec636a97`; a fresh `git status` showed
`src/seon/render/transcript.clj` clean before editing. The exact required
hunks were `render-run-ai` (formerly line 785) and `render-run-html`
(formerly line 888). Both now resolve the selected turn's identities,
retain the existing missing-selection diagnostic, and render only that
turn's evaluations. State text comes from the existing `seon.turn/render-ai`,
including the closed-without-reply interruption evidence; no second status
derivation or history clipping was introduced. HTML retains its header.
This does not restore the separate undisposed-turn notice dissolved in T2.

Verification: isolated `bin/test-fast seon.render.transcript-run-test
seon.repl-test` passed 17 tests / 93 assertions, zero failures or errors
(`tmp/s11-render-fast.log`). The unchanged selected-turn regression checks
both direct and schema-selected AI/HTML calls, presence of the selected
evaluations and interruption explanation, exclusion of the other turn, and
the missing-identity diagnostic. No browser-paint proof is claimed.

## Red (2) — select the evaluation pair for namespace refs, 2026-09-16

The batch-111 HTML was the generic map printer, not an evaluation article.
The wildcard pull supplied `:seon.cluster.eval/ns {:db/id ...}`, but
`:seon.eval/entity` required `{:seon.ns/name ...}`. Consequently the declared
evaluation pair never qualified. This was independent of the thinking-block
implementation and did not depend on hot reload.

After `5ae2337d1` released `resources/seon/schemas/seon.eval.edn`, status was
checked again before editing. The namespace member now admits its shared
reference contract while retaining the existing expanded-name shape.
`seon.repl/entity-emission` resolves an unexpanded namespace against the
database carried by the render request, just as its renderer ref already did.
No placeholder namespace or second pair-selection mechanism was added.
The page-pair regression uses `transacted!` and checks the original pulled
reference plus numeric and lookup references, keeping the thinking, prompt,
saved-result and AI-grammar assertions.

Verification at isolated HEAD `c74dc5c01`: the 13 web-debug tests, 16 REPL
tests and selected-turn test completed without failures or errors in
`tmp/s11-render-final-fast.log`. The broader run's 48-test aggregate had one
error in `supersession-chains-vanish-from-the-history`; that error concerned
the red-(4) bootstrap-message query and is handled in its separate slice.
The original thinking regression and its additional ref cases passed through
`render/render-call` with armed contracts. Default adoption and browser paint
remain unverified; the publication-bound observation is recorded in
`docs/seon/issues/concurrent-publications-serialize-past-the-hook-bound.md`.

## Red (4) — prove concurrent evaluations and render their messages, 2026-09-16

The batch-111 first scenario contains thirty committed evaluations with the
canonical hashed identities. It does not show a refused fixture write or a
failure to begin. The first assertion queried shown-text transaction times
strictly before the first close, but evaluation results settle with the close.
That query therefore cannot observe concurrent starts. The fixture now uses
the existing `:seon.turn.loop/await-part` observation at real evaluation entry:
all N agents must enter before any may finish its first form. The awaiting
test consumes those evaluation-entry events as well as terminal reports,
rather than putting a whole six-evaluation fold under one event backstop.
Every wait is bounded by `test-support/await-event!`; all terminal reports
are still required, and execution remains the real SCI path.

The fixture's other stale assumptions were independent: evaluation identities
are derived by `seon.id/evaluation`, and `seon.cluster.message/send` constructs
a message rather than delivering it. Plans now call `my.message/send`, then
query the delivered identities from their sender, recipient and content.
Creation and planned-turn writes use `transacted!`, so a writer refusal is
reported at its cause. Root's unrelated bootstrap is completed and its mailbox
paused before measuring scenario provider calls.

Actual delivery exposed two production history defects. The message render
pair supplied a future read form without the already-held message content;
history now retains that source and uses the existing message terminal
formatter to show the content, without evaluating or clipping it. The
bootstrap-message lookup passed an absent identity to a second query, which
could select an unrelated message. One relational query now follows the
selected agent's opening turn to an identified message. A canonical regression
proves an agent with no opening trigger cannot see its peer's message; an
existing supersession fixture also proves an unidentified trigger target is
not a message.

The transcript path was clean before the red-(3) edit and has been owned by
this lane since. Dependency seams remain the existing Datahike query/writer
(`reference-code/datahike/src/datahike/query.cljc`,
`reference-code/datahike/src/datahike/writer.cljc`), core.async Flow pause/ping
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`),
`seon.turn/turn`, `seon.cluster.agent/armed`, and `seon.test-support/await-event!`.
No new runtime machinery or writer was introduced.

Verification: the final isolated transcript run passed 18 tests / 212
assertions, zero failures or errors (`tmp/s11-transcript-verified-fast.log`).
It includes the previously failing supersession case and the new absent-trigger
case. An intermediate concurrency run reached 2,185 assertions with no
failures before its whole-fold await expired
(`tmp/s11-concurrency-serial-fast.log`); this exposed the missing progress
observation described above. An earlier attempt expired awaiting root's
bootstrap and reached no scenario (`tmp/s11-concurrency-verified-fast.log`);
the serial run passed that wait, which is not evidence of its earlier cause.
The progress-aware run then hit the suite's 300-second reporter-silence
watchdog (`tmp/s11-concurrency-progress-fast.log`), with its main thread
awaiting concurrent-fold events, not bootstrap or teardown. Cluster fixture
preparation consumed 229 seconds (22:27:30–22:31:19Z) before the scenario
work. Its virtual-thread dump is retained at
`tmp/s11-progress-watchdog-threads.json`. The subsequent verification uses
the runner's declared `SEON_TEST_SILENCE_SECONDS=600` option; the canonical
per-event backstop remains unchanged. Its result is recorded below after
completion. Only fast iterations were run, as assigned; no cold gate,
platform gate, or browser proof is claimed. The explicit default publication
ended in a source-changed refusal, recorded in the publication issue above.

Foreign boundary: verification used isolated HEAD `c74dc5c01` plus this lane's
owned edits, with `reference-code` linked as prescribed. Shared in-flight
changes to `bin/test`, `src/seon/test/runner.clj`, config/schema owners and
`test/seon/test_support.clj` were excluded, not edited or treated as an S11
failure. The final concurrency snapshot also includes the committed red-(2)
evaluation schema and REPL owner edits. The detector red remains outside
this assignment.

Touched paths across these four fixes:

- `src/seon/cluster/prompt.clj`, `test/seon/cluster/prompt_test.clj`;
- `resources/seon/schemas/seon.eval.edn`, `src/seon/repl.clj`,
  `test/seon/render/web_debug_test.clj`;
- `src/seon/render/transcript.clj`, `test/seon/render/transcript_test.clj`,
  `test/seon/concurrency_independence_test.clj`;
- this review note and
  `docs/seon/issues/concurrent-publications-serialize-past-the-hook-bound.md`.

## Continuation — the four reds verified in one snapshot, 2026-09-16

The killed session left reds (3) and (4) uncommitted and characterised its
last two gate errors as infrastructure. That characterisation is now proven
rather than asserted, and it was one cause, not two. Its own re-run
`tmp/s11-resume-fast-b2.log` (23:13:02–23:19:42Z) ran both
`seon.concurrency-independence-test` tests green — 2 tests, 2,871 assertions,
zero failures or errors — with no tree change between the red and the green.
Both earlier errors trace to `java.lang.Exception: Clj-kondo cache is locked
by other thread or process.` thrown from
`seon.fn.analyzer/discard-obsolete-cache-entries!`
(`src/seon/fn/analyzer.clj:217`): the second test threw it directly, and the
first — reported as a "refused canonical fixture setup" at
`seon.test-support/checked-fixture-result` — was the SHARED BASE build taking
the same lock through `seon.fn/build-manifest`. No fixture wrote an incomplete
entity, so AGENTS.md 5.8 does not apply; the fixture's own writes already go
through `transacted!`. The lock class is filed as
[a fast gate JVM dies on the shared kondo cache lock](../../../seon/issues/a-fast-gate-jvm-dies-on-the-shared-kondo-cache-lock.md),
which also records why the existing parallel-stress note does not cover it.

Verification of this continuation, all six namespaces in ONE isolated
snapshot (`tmp/test-runs/run.tsxdVc`, pid 69860, 23:28:12–23:34:33Z,
`tmp/s11-continuation-verify.log`):

```
bin/test-fast --paths src/seon/render/transcript.clj \
  test/seon/render/transcript_test.clj \
  test/seon/concurrency_independence_test.clj -- \
  seon.eval-test seon.cluster.prompt-test seon.render.transcript-run-test \
  seon.render.transcript-test seon.render.web-debug-test \
  seon.concurrency-independence-test
```

**48 tests, 3,394 assertions, 0 failures, 0 errors** (exit 0). This is the
first run in which all four batch-111 reds were exercised together on one
HEAD (`8e74014d6`) plus only this lane's three owned files. The concurrency
fold's six scenarios ran 11.0–22.3 s each, N=5 and N=10; its live cluster
rooted inside the snapshot rather than the shared checkout.

Acceptance (d) re-checked by reading rather than by reach: `fit-text` appears
nowhere in `src/seon/render/transcript.clj` (0 occurrences, at HEAD as well).
`floor-text` survives on exactly one call, the small `extra` metadata map in
`message-text` (`src/seon/render/transcript.clj:454`) — an ordinary
un-rendered value crossing `seon.render.value/render-ai`, which IS the one
clipping spot, not a re-fit of an already-rendered unit. `rendered-family`
and its re-admission of a rendered AI string are deleted.

Boundary: fast iterations only, as assigned. No cold `bin/test`, no
`--platform` tier, no recorded result facts, no default adoption and no
browser paint are claimed. `default` (pid 41413) was neither stopped,
restarted nor reset, and no prepl evaluation was used in this continuation.
Held foreign paths — `bin/test`, `src/seon/config.clj`, `src/seon/schedule.clj`,
`src/seon/cluster.clj`, `src/seon/cluster/wake.clj`,
`src/seon/test/runner.clj`, `test/seon/test_support.clj` and the rest of the
dirty tree — were excluded from the overlay and left untouched.
