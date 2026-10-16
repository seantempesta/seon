---
title: "live run 3: attempt 2 of the six held run-2 tasks, started by my.root/run!, zero hand acts by the observer"
type: evidence
status: "run complete 2026-10-16: 1 merged (carried work, 0 model calls), 1 done then merge refused, 4 capped with every proof green"
---

# Live run 3: six held chains, attempt 2, observed read-only

Lane live-run-3 (Sonnet), observer only. Default JVM pid 94902 (started 07:08:34 UTC) at HEAD 5793e03be; main moved to
7fba938ff during the run (the 05db merge). Root ran `my.root/run!` on the six held tasks of
`docs/research/agent-platform/2026-10-16/live-run-2.md`; attempt 2 of each ran on its existing chain branch. Hand acts by
this lane: 0 (JVM-mode reads only: `d/q` and `seon.competency/grade` over `seon.db/branch-database`,
`seon.context/step-verdict` on kept chain branches, `bin/seon check --at HEAD`). All six had ended before this lane's
first complete poll at about 07:13 UTC, so the 45-minute poll was not needed.

## Summary

Wall is first call opened to last call closed (`seon.competency/grade`). Tokens are prompt / cached / completion. Run 2
numbers are the same chain's attempt 1.

| chain | task | attempt 2 (run 2 attempt 1) end | calls (run 2) | wall (run 2) | tokens p / c / out | forms | in-ns / redundant (run 2) | wasted calls | agent errors |
|---|---|---|---|---|---|---|---|---|---|
| `05dbf263a53b` | `seon.ai/provider-ai-returns-data` `3c3c9beccb65` | **merged** `7fba938ff` (done at start, no model call; run 2: done, merge refused) | 0 (10) | 0 (22.3 s) | 0 / 0 / 0 | 0 | 0 / 0 (1/0) | 0 | 0 |
| `fddd9dcbfe66` | `seon.background/render-ai-returns-data` `0c7f6db9902d` | done, **merge refused** `:seon.source/red-test-error` (run 2: done, write-back refused) | 13 (37) | 42.8 s (102.4 s) | 165,711 / 121,984 / 4,048 | 35 | 3 / 2 (7/3) | 2 | 1 |
| `ce9d664c1be0` | `seon.effect/render-ai-returns-data` `d44420044784` | **capped**, proof green, done refused `:seon.source/test-evidence-error` (run 2: same) | 40 (40) | 150.2 s (111.0 s) | 1,152,209 / 979,840 / 12,894 | 111 | 8 / 5 (29/24) | 11 | 9 |
| `42e0e4c46b71` | `seon.error/elision-prose-returns-data` `7f145801a4b6` | **capped**, proof green, done refused (red reaching test + no green test) (run 2: capped, red) | 40 (40) | 143.2 s (105.2 s) | 874,575 / 738,048 / 11,093 | 74 | 9 / 3 (45/22) | 18 | 14 |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai-returns-data` `d70205d4d1c9` | **capped**, proof green, done refused `:seon.source/red-test-error` (run 2: capped, red) | 40 (40) | 132.0 s (105.4 s) | 847,288 / 723,456 / 11,276 | 79 | 19 / 9 (44/13) | 14 | 7 |
| `fd3ba8395135` | `seon.repl/render-ai-returns-data` `1812eed123f7` | **capped**, proof green, done refused `:seon.source/red-test-error` (run 2: capped, red) | 40 (40) | 157.8 s (108.0 s) | 981,267 / 847,744 / 11,078 | 98 | 1 / 0 (41/40) | 11 | 17 |

Totals of the five attempts that called the model: 173 calls (run 2 attempts 1 of the same six: 207), 4,021,050 prompt
tokens, 399 forms, 40 `in-ns` forms (10%), 19 redundant (48% of in-ns). Score (non-green = cap + 1 = 41): 0, 13 (done but
unmerged), 41, 41, 41, 41. Redundancy is measured as an `in-ns` form whose own `:seon.cluster.eval/ns` entity equals the
next form's, over adjacent model evaluations of the attempt's agent; run 2's 195 forms and 123 redundant are from
`in-ns-waste.md`.

Every capped attempt shows `proofs {:green 1}` in its end facts: the named proof is green in all four; the done rule
refused each for something other than the proof (below). Read-only, `seon.context/step-verdict` of each kept chain branch
(239 to 542 ms each; the four together 1,477 ms and 4,489 MB allocated) answers the refusals in "Broken things".

## Broken things first

1. **Done and the merge gate still disagree: `fddd9dcbfe66` is done on its chain and red at the merge.**
   Read-only: `(seon.context/step-verdict chain-fddd-db "attempt/0c7f6db9902d")` answers `:seon.task/status :done`,
   `:seon.source/refusals []`. Root's final merge (merge branch `merge-62f83095ac57`, kept, held merge step
   `render-ai-returns-data-2` / `8a5b6b52d53f`) then refused with `:seon.source/red-test-error`. The merge branch's run
   `a1f2440734d3` (07:10:12 UTC, policy `:named`) executed
   `my.background-test/background-error-renderers-cover-every-declared-declared-schema` `:interpreted` with
   `:seon.test.member/fail-count 6`, reason `:reaches-changed`. The failures are `(= message ai)` against
   `{:seon.error/message "The background call is invalid."}` (three, one per row) and
   `((schema/projection-validator (schema/handed-projection) :seon.render/ai) ai)` false. On the chain branch the same
   test (a deftest the agent rewrote in attempt 1 and 2) has four runs: `4ded460f0047` 05:33:49 fail 6, `4fc4c7ca645d`
   05:34:18 fail 3, `b24260cf334b` 05:34:56 fail 0, and in attempt 2 `4ef026800250` 07:09:25 fail 0. The merge's six
   failures equal run 2's first run, which is the test as it is in `test/my/background_test.clj` on main (its `deftest` still
   asserts the prose strings). So the chain judged the agent's edited test and the merge judged main's text of it: the
   agent's test edit is not in the merge candidate, or the merge picked the test from main. Cause not isolated here
   (the merge branch's `:seon.fn/*` rows for that symbol returned nothing under the attributes I tried). Done rule and
   gate are not one derivation for a changed existing test (CLAUDE.md "One derivation, every consumer").
2. **Three of four capped chains were refused for existing tests the render change turned red, and the agent was
   not shown the way to them.** `seon.context/step-verdict` refusals (`:seon.source/red-test-error`, red at the chain,
   not red at the merge base):
   - 91a8: `seon.render-source-test/source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute`,
     `seon.render.page-review-test/turn-headers-and-all-evaluations-have-one-place-each`.
   - fd3b: `seon.repl-test/every-reply-path-reads-the-recorded-cost-its-findings-need`,
     `seon.repl-test/the-handle-is-the-evaluations-own-identity`,
     `seon.turn-continue-test/provider-progress-has-its-own-bound`, `seon.turn-continue-test/read-results-arrive-before-done`,
     `seon.turn-test/evaluation-ai-is-only-the-repl-session`.
   - 42e0: `seon.error-test/specialist-renderers-use-their-declared-evidence` plus `seon.error/elision-prose` reaching no
     green test.
   The plans name only the one proof. The agents' last nine calls are reads of those tests (fd3b: `my.program/source` of
   four `seon.repl-test/*` vars then a `seon.db/q` over `:seon.test/source`; 91a8: `my.program/source` of
   `seon.render-source-test/*`, then a helper `leaf-strings` defined and unmapped); 42e0 spent its last calls on
   `my.edit/text` of a test, refused on arity 4 times ("expected a declared argument count (3)", calls 33 to 40).
3. **`ce9d664c1be0` hit the identical refusal as run 2, with the proof green again.** `step-verdict` refusal
   `:seon.source/test-evidence-error`, offending `[seon.effect/payload-unit seon.effect/render-ai]`, "A replaced function
   reaches no green test of the named run." (run `a063275e0deb`). It is the same two functions as run 2
   (`820a82e31349`). The agent again spent its last calls hunting (`my.program/source :seon.effect/view`, `:seon.fn/sym`,
   `:seon.turn/id`, `my.fs/section` of the render-bench report). The refusal was reproduced across two attempts and two
   code states: the nearest-test walk (a689f0a35) does not reach `payload-unit` and `render-ai` from the green proof.
4. **The bail problem task carries the verdict for a done refusal, but it drops the red test names and says
   "proofs {:green 1}" beside a refusal.** `unfinished-task-7f145801a4b6` (42e0) plan: "Its last verdict: proofs {:green 1}.
   The done rule refused: [... :seon.error/message "A proof, or a test not red at the merge base, is not green here."
   :seon.source/refused-test-run "8409917af0f5" :seon.error/type :seon.source/red-test-error}]". The refusal entry has no
   `:seon.source/red-tests`: root reads that a red test exists but not which (the data is in the step verdict, stripped
   by the same `dissoc` that `seon.context/shown-refusal` applies for the agent). Same for 91a8
   (`unfinished-task-d70205d4d1c9`) and fd3b (`unfinished-task-1812eed123f7`); only 42e0's and ce9d's (`:offending`)
   second refusal names functions. "proofs {:green 1}" in the same sentence as a refusal reads as a contradiction.
5. **The merge-refusal problem task carries no verdict at all.** `unfinished-task-0c7f6db9902d` (fddd) says only "The final
   merge refused it, :seon.source/red-test-error: A test of the named run is not green; a merge commits only when
   every test is. Its merge branch merge-62f83095ac57 is kept". No test name, run id or refusal data; the three-line
   message does not say which of 15 members was red. The done-refusal problem tasks do carry a refusal vector, so the
   verdict-as-data fix (811df23ac) covers only half of the bail paths.
6. **Nothing reran by itself** (relaunches 0 holds): each held chain shows exactly one attempt 2 and no attempt 3.

## Per chain (attempt 2)

- **05db merged `7fba938ff`.** The branch kept attempt 1's `defn seon.ai/provider-ai`; attempt 2 started with the proofs green,
  ended `:done` with 0 model calls (the "green at the start ends the task with no model call" rule), and root merged it at
  07:09:32 UTC (`Written: src/seon/ai.clj`, +5/-5; trailers `Assignment: 3c3c9beccb65`, `Merge-commit:
  6ad1cdab-7a77-5f81-87a6-f5e1062334fd`, `Agent: attempt/3c3c9beccb65`, no `Model:` line because the attempt made no model
  call; no `Co-Authored-By`). Task `:stopped` on main. No `:seon.unmerged/entry` on main. `seon.ai-test/the-provider-ai-render-returns-data` is green on HEAD (check below). Run 2's refusal
  (`:seon.source/test-evidence-error`, "replaced function reaches no green test") did not recur at this merge: done
  and merge agree for this chain. Whether the merge ran a platform JVM was not observed (no isolated member is
  named in the merge commit; not evidenced).
- **fddd done, merge refused: Broken 1.** 13 calls, 42.8 s, 2 wasted, 1 error: 10 reads, 10 probes, 6 progress. A third the
  calls and forms of run 2 attempt 1 (37 calls, 102 s), because attempt 1's render and test rewrite were already on the
  branch.
- **ce9d: Broken 3.** 40 calls, 111 forms, 53 reads, 10 re-reads, 9 errors. 8 `in-ns`, 5 redundant.
- **42e0: Broken 2 and 4.** First agent `defn elision-prose` was defined late (among its last nine calls) but the existing test
  `specialist-renderers-use-their-declared-evidence` stayed red; the last eight calls fought `my.edit/text`.
- **91a8: Broken 2.** 19 `in-ns`, 9 redundant (the most in this run, 24% of its forms), 15 re-reads. A helper `leaf-strings`
  defined in a test namespace and unmapped (final call).
- **fd3b: Broken 2.** 17 agent errors of 98 forms (10 `:error`, 5 `:unresolved` incl. `my.program/ns-source`, 2 contract
  refusals); one `in-ns` only (the `in-ns` fix landed here).

## Done and merge, and merged runs

Only one merge happened (05db). Merge trailers as above. `bin/seon check --at HEAD` on candidate 7fba938ff: run
`9a17210b820f`, executed 2 (`seon.ai-test/the-provider-ai-render-returns-data`,
`seon.babashka-load-test/declared-namespaces-load-in-babashka`, `--include-long`), pass 5, fail 0, error 0, 3,907 ms,
`passed? true`, total 80.9 s (seed 52.9 s, boot 15.2 s). `:stopped` on main for 05db only. Unmerged entries: none on main
(`d/q` over `:seon.unmerged/entry` = `#{}`). Kept merge branches: `merge-62f83095ac57` (fddd, this run), `merge-87a414ff6dd9`
and `merge-b14ff04ae163` (run 2, still kept).

## Defects found (exact forms and values)

- Done vs merge for a changed existing test: Broken 1.
- Done refusal not shown to the agent beyond what it must call itself: Broken 2 and 3 (the refused names are in the
  agent's context only if it opens the step section; four chains still burned 40 calls).
- Problem task verdict lacks `:seon.source/red-tests` and carries "proofs {:green 1}": Broken 4. Merge-refusal problem task
  has no verdict: Broken 5.
- `my.edit/text` refuses with "expected a declared argument count (3), got an argume..." on 4 forms of 42e0 (calls 33 to
  40); the same verb shape is taught by the context as 3 arguments, so the form the agent sent is a 4-argument call it
  made by hand. Cost: 4 of its last 8 calls. Unreproduced in a smaller probe; the error does not show the declared
  arities.
- `(my.program/ns-source 'seon.repl-test)` unresolved (fd3b call 39), `(seon.db/q ... :seon.fn/aritie...)` ClassCastException
  on a keyword (91a8 call 37): invented names, same class as run 2's list.
- `seon.context/step-verdict` costs 239 to 542 ms per chain here (1,477 ms for four, 4,489 MB allocated over the form):
  the allocation is high for a read that reuses green evidence.
- A fifth slow read by this lane: a 150 s poll loop of `seon.competency/grade` over six branches allocated 1,707 MB (41,305
  calls); not a system path.

## Wasted-turn classes, ranked against run 2

Run 2 ranks (nine chains, 835 forms) against run 3 (five attempts that called the model, 399 forms):

1. **Reading the pipeline / sibling sources: 177 read + 59 re-read forms in run 3 (59% of forms; run 2: 44% reads, 50 re-reads).**
   Ranked first again. The reading is now mostly tests and consumers (Broken 2), not the renderer pipeline: the agents
   already had their render written and were hunting the tests the refusal named. `my.fs/read` and `my.program/search`
   changes did not remove the class.
2. **Existing tests the render change turned red: 4 of 4 capped chains and the one refused merge (fddd), about 130 forms.**
   New to the top three (in run 2 these chains never wrote a render, so the red existing tests were not yet reached).
3. **`in-ns`: 40 forms of 399 (10%; run 2: 23%), 19 redundant (48% of in-ns; run 2: 63%).** Improved; fd3b went from 41 to 1.
   91a8 (19, 9 redundant) and ce9d (8, 5) still teach themselves the move. Proof target (the 4 needed per nine chains) not met
   on 91a8.
4. **Wrong API shape: 36 `:unresolved`/`:contract-refusal`/`:error` forms in the capped chains (14 + 7 + 17 + 9 etc.; run 2: 52 over nine).** Same verbs
   (`my.edit/text` arity, `my.program/*` invented names).
5. **Refusals the agent cannot see or act on: ce9d's final calls again**, now plus fd3b, 91a8 and 42e0.
6. **Sample hunting (run 2 rank 2, about 45 calls): not observed in this run's capped tails.** Attempt 1's work carried it.

Key numbers: calls to done 0 and 13 (run 2: 10 and 37); capped chains unchanged at 40 calls each with a wall 24 to 42%
longer (132 to 158 s vs 105 to 111 s); the four capped have the proof green (run 2: only ce9d). One merge, one
merge-refused.

## Top three fixes the evidence supports

1. **Make the merge judge the agent's test, not main's (or make done judge what the merge will carry).** fddd is done
   with 0 refusals and red at the merge on a test the agent edited; this is the one disagreement left in the system
   (`seon.cluster.source/done-refusals` vs the merge candidate). Reproduce on `merge-62f83095ac57` vs
   `chain-fddd9dcbfe66`: the member `my.background-test/background-error-renderers-cover-every-declared-declared-schema`.
2. **Show the agent the red existing tests and their sources at the first red reaching run, with the one form that
   edits a test.** 3 of 4 capped chains and 130 forms went to finding what the step verdict already names. Put
   `:seon.source/red-tests` (the names and failing assertions) into the problem task and the context step section
   (`seon.context/shown-refusal` removes `:seon.source/red-tests` from what the agent reads) and into the plan's
   "consumers to update" for render-conversion tasks, so a render change lists the tests that assert its old strings.
3. **Give a merge refusal and a red-test refusal the same verdict as the done rule in the problem task, and delete
   "proofs {:green 1}" next to a refusal.** Root cannot tell fddd's red member from the text; for the three done
   refusals it sees "a red test" without names. One derivation, one verdict value, every consumer.
