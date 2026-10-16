---
title: "live run 7: five fresh render chains on a nuked store at d76806d39, observed read-only"
type: evidence
status: "run complete 2026-10-16: default never faulted on heap (old gen peak 836 MB of 4096), 1 of 5 merged (fddd), 4 capped at 40 calls with the proof green or red; two of the four bails rest on tests that are red on HEAD"
---

# Live run 7: five fresh chains, attempt 1, observed read-only

Lane live-run-7 (Sonnet), observer only. Default JVM pid 18697 (started 11:28:32 UTC) at HEAD d76806d39, alive and
unchanged for the whole run (and after). Root started the five chains with one `seon.task/chain!` each; the first
model call opened at 11:28:57 UTC. Hand acts by this lane: 0 (JVM-mode reads only: `d/q`, `d/pull`,
`seon.competency/grade` and `classify` over `seon.db/branch-database`; `bin/seon status`; `git log`; two throwaway
`bin/seon check --at HEAD` JVMs after the run; no SCI request as root, no `run!`, no message, no restart). All five
had ended by about 11:32:20 UTC, so the 45-minute poll was not needed (I polled about every 30 to 60 s from 11:29:57
to 11:33:50). Each chain made exactly one attempt; nothing reran by itself.

## Summary

Model for every call: `deepseek-flash` (the merge commit's `Model:` trailer). Wall is first call opened to last call
closed (`seon.competency/grade`). Tokens are prompt / cached / completion. Score: calls to green, a non-green run one
past its cap (41).

| chain | task | end | calls | wall | tokens p / c / out | forms (in-ns / adjacent-identical) | wasted calls | agent errors | score |
|---|---|---|---|---|---|---|---|---|---|
| `fddd9dcbfe66` | `seon.background/render-ai-returns-data`, proof `seon.background-test/the-background-error-ai-render-returns-data` | **done at call 35, merged** `225a66566` (11:30:21) | 35 | 79.5 s | 619,360 / 540,416 / 6,049 | 68 (2 / 0) | 11 | 12 | 35 |
| `ce9d664c1be0` | `seon.effect/render-ai-returns-data` | **capped**, proof green, done refused: red `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence` | 40 | 94.8 s | 755,807 / 673,664 / 8,236 | 91 (19 / 15) | 8 | 10 | 41 |
| `42e0e4c46b71` | `seon.error/elision-prose-returns-data` | **capped**, proof green, done refused: 5 red `seon.error-test` members | 40 | 112.9 s | 978,426 / 847,232 / 8,047 | 110 (7 / 3) | 11 | 9 | 41 |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai-returns-data` | **capped**, proof **red**, no render written | 40 | 97.9 s | 930,557 / 799,872 / 8,552 | 120 (2 / 0) | 7 | 14 | 41 |
| `fd3ba8395135` | `seon.repl/render-ai-returns-data` | **capped**, proof green, done refused: 6 red tests | 40 | 106.7 s | 920,480 / 818,304 / 9,795 | 112 (19 / 10) | 10 | 17 | 41 |

Totals: 195 calls (run 4: 172), 4,204,630 prompt tokens (3,872,776), 501 forms (412), 62 agent errors (30), 49 `in-ns`
forms (9.8%; run 4: 62, 15%), 28 of them identical to the previous `in-ns` (57%; run 4: 55%), 47 wasted calls (62).
Sum of wall 491.8 s (run 4: 563.8 s). The fifth chain (`fd3b`) opened its first call about 85 s after the others
(`max-running` 4; first test run 11:30:22). Grade rows, `:seon.competency/proofs`: `{:green 1}` for fddd, ce9d, 42e0,
fd3b; `{:red 1}` for 91a8.

## Broken things first

1. **Two of the four bails rest on tests that are red on HEAD (`225a66566`, compiled), so the done rule refuses an
   agent for failures it cannot fix and did not cause.** `bin/seon check --at HEAD` on the 13 red-named tests of the
   four verdicts: run `0341e32bb37f`, executed 13, pass 147, fail 5, error 2, 6 red:
   `seon.error-test/entity-acquisition-observations-have-no-program-digest-promise` (`(not (= seon.id seon.id/id))`),
   `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner`,
   `seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm` (`(not (= :guard :output))`),
   `seon.render-coverage-test/important-runtime-entities-declare-and-use-readable-faces` (model face prints a map),
   `seon.turn-continue-test/provider-progress-has-its-own-bound` and `.../read-results-arrive-before-done`
   (fixture "did not publish its required turn closure"). 42e0's verdict names three of them; fd3b's names the last
   two, and its refused run holds the fourth. The refusal text itself says "A proof, or a test not red at the merge
   base, is not green here", yet no run on main exists to read a base from (`seon.test.run/at` after 11:28 on main:
   0 runs). This is the filed class `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`
   (`6a403c2e27bf`, in the store's task list); it is the cause of 42e0 and part of fd3b's cap.
2. **Six more reds are green compiled on HEAD and red interpreted on the chain, and nothing says whether the agent
   caused them.** On HEAD: green `seon.error-test/every-error-owner-function-declares-its-input-and-output`,
   `seon.error-test/the-signature-ignores-the-message`, `seon.repl-test/a-unit-that-is-not-an-evaluation-refuses-instead-of-throwing`,
   `seon.repl-test/every-reply-path-reads-the-recorded-cost-its-findings-need`,
   `seon.repl-test/the-handle-is-the-evaluations-own-identity`, `seon.turn-test/evaluation-ai-is-only-the-repl-session`
   and `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence`. On the chains they are red
   interpreted (live-run-4 Broken 1 saw an `(ns-interns 'seon.error)` interpreted difference for one of 42e0's; fd3b's four sit on the
   repl face its own `render-ai` changed). I did not isolate which are the agent's.
3. **A 64-member run, then a 15-member run stopped `:member-unfinished`, then 3 members: 42e0's done step stopped
   mid-list.** Runs `1d5cc5628ad4` (n 64, `:interpreted`, 7,053 ms, `:reasons {:named 64}`), `cffc1a527e83` (n 15,
   `:stop :member-unfinished`, 754 ms) and `7f63f77266e2` (n 3, 850 ms), the first two stamped 11:30:32.658. The
   stop reason sits on a run the verdict then reads as five red tests (`seon.error-test/the-signature-ignores-the-message`
   red only in the unfinished run). Whether `:member-unfinished` is a timeout or a collision with the 64-run is not
   known from the stored run.
4. **The verdict of 91a8 names `user/proof-probe` as uncontracted after the agent unmapped it.**
   `:seon.task/verdict` of `unfinished-task-bf156465a3b9` carries `:seon.source/uncontracted [user/proof-probe]` with
   `:seon.source/contract-gaps` (position `:seon.fn.contract.position/contract`, `:missing-spec`). Form 113 `defn`s
   `proof-probe` in `user`, form 117 `(my.program/ns-unmap! 'user/proof-probe)`, and on the chain branch no
   `:seon.fn/sym` `user/proof-probe` entity exists at the end (`d/q` returned `#{}`). The refused run is the first one,
   `90c342c38802`; the done rule read a function the branch no longer held, or the verdict is stale.
5. **91a8 wrote nothing in 120 forms and 40 calls.** 43 reads and 20 re-reads, 19 probes, 11 repeats, 8
   unresolved; 4 progress forms, all a scratch `proof-probe` / `probe` pair. It read `(my.program/source
   'seon.turn/render-ai)` 9 times and `seon.render.transcript/render-ai` 4 times. The last probe (form 119) failed on
   `transcript/::projection` ("Unable to resolve symbol"). Same class as live-run-4 Broken 6.
6. **The render is defined at form 49 to 90 of 68 to 112, so the cap, not a hunt, ends three chains.** First
   `defn` of the target: fddd form 49 (of 68), ce9d 56 (of 91), 42e0 90 (of 110), fd3b 54 (of 112, and the real
   `render-ai` at 110 of 112, call 40). Run 4's hunt for a way to run a test (95 forms in `seon.test.runner`, 31
   calls of `step-verdict`) did not recur: `step-verdict` forms 0, 0, 2, 0, 0, `test.runner` / `run-vars` forms 0.
7. **One agent form cost 1,207 ms and 2,194 MB.** fddd form 27:
   `(seon.db/q '[:find ?e ?a ?v :where [?e ?a ?v] [(= ?v 'seon.background/render-ai)]] (seon.db/db))`: a whole-store
   scan, the only form over one second in 501 (every other form is 193 ms or less, median 0 ms). It is 72% of
   fddd's 3,033 MB allocated. The reply carried `:seon.repl/finding :slow`; the plan that agent holds does not stop
   it.
8. **A test run printed its error maps into default's `seon.log`.** After 11:30:21 the log holds
   `ERROR-MANIFEST stored-large-value {...}` and a `#:seon.error-test{:unowned-refusal {...}}` map (a full
   `:seon.db.write/validation-refusal` with `:seon.db/tx-data`), printed by 42e0's 64-member interpreted run on the
   main JVM (`seon.error-test` prints to stdout). Printing outside the value printer, into the log.
9. **`in-ns` repeats: ce9d 19 forms (15 identical to the previous `in-ns`), fd3b 19 (10), 42e0 7 (3).** The other two
   chains have 2 and 2. I did not read what precedes each repeat.
10. **Recorded errors.** Root's start request recorded two entries for `seon.cluster.store/acquire-branch!` at
    11:28:56.317 and 11:28:56.545 (the "Connection is being released" signature), 1 s before the first model call.
    Health after the run: `:seon.problems/errored-receipt` 1 -> 5 (one per bail, the four verdicts above), open faults 5
    (unchanged), and a new `:seon.cache/discipline-warning` 1 with `seon.cache` weighing 486,357,930 of 512,000,000
    bytes. No `:request-threw` on any run (every `seon.test.run/stopped` is nil except the one `:member-unfinished`).
11. **The merge's own test run is not readable after the merge.** Main holds 0 `seon.test.run` entities after 11:28
    and no kept merge branch (the `:merged` chain's merge branch was deleted); the assignment row carries
    `:seon.task/merge-commit`, `:seon.task/merged-declarations 2` and `:seon.task.attempt/end :done` and nothing about
    the run that gated it (`my.root/merge!` evidence). The trailers `Assignment`, `Merge-commit`, `Agent`, `Model` are
    on the commit; `Written:` lists `src/seon/background.clj test/my/background_test.clj`.

## Done and merge

| chain | done | merge | how the agent met its red reaching tests |
|---|---|---|---|
| fddd | call 35, 11:30:19 | merged 11:30:21 (`225a66566`, 2 declarations, `:stopped` none) | the reaching test `my.background-test/background-error-renderers-cover-every-declared-declared-schema` went red at 11:29:46 after its `defn`; the agent redefined the whole deftest twice (forms 65, 67); green at 11:30:19, done |
| ce9d | not done | none | `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence` red at 11:30:24 (the last run before the cap, 1 s before the end); the agent read its source twice and never edited it; its `render-ai` was defined at form 82 of 91 |
| 42e0 | not done | none | redefined the red `seon.error-test/specialist-renderers-use-their-declared-evidence` once (form 105, golden justification), green at 11:30:02; then the done step's 64-member run found 5 more red (Broken 1, 2, 3) with no verb to act on them |
| 91a8 | not done | none | no test red but the proof; no `render-run-ai` written (Broken 5) |
| fd3b | not done | none | defined `render-ai` at call 40; the 8-member run (reaching) then showed 7 red; no call left |

Done and merge agreed on all five this run: fddd's done was followed by a merge two seconds later; the other four were
never merged. The member set done reads is the one list (the 64 members of 42e0's `:named` run is the same count
live-run-4 saw as the merge's `reaches-changed` selection), so the live-run-4 disagreement on the member set did not
recur; `step-verdict` 0 to 2 forms per chain. No platform JVM ran during any chain (one JVM process, pid 18697, the
whole run; fddd's slice is `src/seon/background.clj` and a test only). No `:seon.unmerged/entry` on main (count nil).

## Members per test step (change's reach versus the full list)

Each row is one test step's run on the chain branch (`seon.test.run/members`, `:policy :named`), in time order.

| chain | runs (members) | note |
|---|---|---|
| fddd | 1, 1, 1, 1, 1, 1 | proof; then the one reaching test (red twice, 23 ms and 0 ms), compiled recheck, green |
| ce9d | 1, 1, 1, 1 | proof, the reaching coverage test (red), then both evaluators of the proof |
| 42e0 | 1, 1, 1, 1, 1, 1, 64, 15, 3 | six singles, then done's 64-member list (7,053 ms), 15 (stopped), 3 (850 ms) |
| 91a8 | 1 | the proof, red at 11:28:57, never rerun: the agent never changed anything the tests read |
| fd3b | 1, 8, 7 (compiled), 1 | proof; at call 40 the 8 reaching (2,202 ms, 7 red) and their compiled 7 (1,218 ms, 3 red) |

The change's reach is 1 member for four chains until the done step; the one full list this run (64) is 42e0's, and its
cost is 7 s of member time. Reaching-test selection followed the change: a 1-member run after almost every form, never
the 64-list until the done step. No run stored `:reasons` other than `:named`. Test-step wall per run was 0 to 430 ms for
the single-member runs.

## Heap timeline (default, JVM mode, `G1 Old Gen`, max 4,096 MB)

| UTC | old gen used | note |
|---|---|---|
| 11:29:57 | 530 MB | chains starting, 4 running |
| 11:30:55 | 573 MB | |
| 11:31:05 | 590 MB | |
| 11:31:40 | 694 MB | fddd merged 11:30:21; ce9d, 42e0 capped |
| 11:32:10 | 738 MB | fd3b in its test step |
| 11:32:30 | 771 MB | all five ended |
| 11:33:40 | 836 MB | **peak** (`MemoryPoolMXBean` peak 836 MB; Eden peak 1,540 MB) |

Old-gen collections 0; young 210 (1,030 ms in all); concurrent cycles 80. No memory-pressure refusal after 11:28:32
(`MEMORY PRESSURE` 48 lines in `seon.log` before and after: the count did not move), no OOM, `data/heap/` empty. The
store, five chains and one merge raised the old gen by 300 MB in five minutes, about one third of the 3.0 GB enter bound
named in `seon.sci.kernel/acquire-arm`. Default answered `bin/seon status` (OK, `:faulted` for the 5 standing faults)
the whole time. Answer to "five concurrent agents without hurting default": yes in this run.

## Per-request cost

Every agent form: median 0 ms, max 1,207 ms (Broken 7) and 193 ms for the next. Summed evaluation ms per chain: 1,733
(fddd, incl. the scan), 702, 1,438, 880, 1,016; allocated per chain 3,033, 1,304, 2,238, 1,070, 1,727 MB (a form's
largest: 109 to 180 MB outside fddd's scan). Wall per call: 79.5 s / 35 = 2.3 s (fddd), 2.4 s, 2.8 s, 2.4 s, 2.7 s: model
latency, not platform cost (`:seon.competency/platform-errors` 0 on all five). My own read per poll cost 10 to 170 ms,
alloc 7 to 200 MB.

## Wasted-turn classes, against runs 2 to 4

This run's 501 forms: read 198, re-read 37, navigation (`in-ns`) 49, probe 53, search 32, progress 44, repeat 26,
error 29, unresolved 24, contract refusal 9. Run 4's 412: read 151, re-read 69, navigation 62, probe 32, search 24,
progress 23, repeat 21, contract refusal 13, error 11, unresolved 6.

1. **Reading sources: 235 of 501 forms (47%; run 4 53%, run 3 59%, run 2 44% plus 50 re-reads).** Still first.
   Re-reads fell from 69 to 37 (91a8: 20 of them).
2. **Errors: 62 of 501 (12%; run 4 30 of 412, 7%; run 3 9%, run 2 about 10%).** The largest rise. 24 unresolved
   symbols (run 4: 6), 29 other errors (11). fd3b 17 errors of 112 forms.
3. **`in-ns`: 49 forms (9.8%; run 4 15%, run 3 10%, run 2 23%), 28 identical to the previous (57%).** Down, but two
   chains still own 38 of the 49 (ce9d 19, fd3b 19).
4. **Hunting how to run a test: gone** (run 4: fddd 95 forms in `seon.test.runner`, fd3b 31 calls). Replaced by
   fewer, later writes: the first render `defn` at form 49 to 90.
5. **Repeat forms: 26 (run 4 21):** 91a8 11, 42e0 10, fd3b 5.
6. **Writing too late to use the reach:** three chains' only post-edit feedback is the done step at the cap
   (ce9d's reaching test went red 1 s before its cap; fd3b's 8-member run came at call 40).

## Cumulative tally of the nine render tasks

| chain | run 2 | run 3 | run 4 | run 7 (fresh store) | end state |
|---|---|---|---|---|---|
| `dcc8` model-ai | merged | | | | merged |
| `419a` config-render-ai | merged | | | | merged |
| `8d9e` mcp-prose | merged | | | | merged |
| `05db` provider-ai | refused | merged | | | merged |
| `fddd` background-render-ai | write-back refused | merge refused | capped | **merged** (35 calls) | **merged** |
| `ce9d` effect-render-ai | capped | capped | capped | capped, proof green | open, held |
| `42e0` elision-prose | capped | capped | done, merge refused | capped, proof green | open, held |
| `91a8` render-run-ai | capped | capped | capped | capped, proof red, no render | open, held |
| `fd3b` repl-render-ai | capped | capped | capped | capped, proof green | open, held |

**5 of 9 merged** (4 before this run), 4 open and held, none abandoned. Run 7's store was nuked, so these four
re-ran from attempt 1, not attempt 4. Model calls over runs 2, 3, 4 and 7: 275 + 173 + 172 + 195 = 815. Prompt tokens
5,599,023 + 4,021,050 + 3,872,776 + 4,204,630 = 17,697,479. Forms 835 + 399 + 412 + 501 = 2,147.

## Proofs of the merge at HEAD

`bin/seon check --at HEAD --test seon.background-test/the-background-error-ai-render-returns-data --test
my.background-test/background-error-renderers-cover-every-declared-declared-schema --test
seon.babashka-load-test/declared-namespaces-load-in-babashka --include-long`: run `674e7f71e6bd`, executed 3, reused 0,
pass 23, fail 0, error 0, 3,685 ms, `passed? true`, total 81.8 s (seed 55.0 s, boot 15.2 s, archive 2.0 s). The
candidate was `225a66566962fd6b90939a7a3b112ab8e0e118e1`, the merge commit. The babashka test is green at HEAD.

## Defects found (exact forms and values)

- Done refuses on a test already red on HEAD: Broken 1. Reproduce: `bin/seon check --at HEAD --test
  seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm` answers `(not (= :guard :output))` at
  `test/seon/error_test.clj:760`.
- Interpreted-only reds attributed to the chain with no base: Broken 2.
- `:member-unfinished` on a 15-member run beside a 64-member run at the same instant: Broken 3 (`seon.test.run/id`
  `1d5cc5628ad4`, `cffc1a527e83`, on `:chain-42e0e4c46b71`).
- A verdict naming an unmapped var: Broken 4 (`:seon.task/verdict` of `unfinished-task-bf156465a3b9`).
- A whole-store scan allowed through for 1.2 s: Broken 7.
- Test stdout in the platform log: Broken 8.
- `d/pull` of `[:seon.task/last-verdict]` on a branch database answers `:transact/schema` "Bad entity attribute
  :seon.task/last-verdict ... not defined in current schema" (the stored attribute is `:seon.task/verdict`; `last-verdict`
  is the render's name). Minor: the assignment and `agent.clj` name the schema, a reader that pulls it throws an
  uncaught `ex-info` from `seon.db`'s pull (my own probe; the REPL answered as data).

## Top three fixes the evidence supports

1. **Judge a reaching test red only when it is green at the merge base on the same evaluator.** 42e0 and fd3b ended
   on `seon.error-test/entity-acquisition-observations-have-no-program-digest-promise`,
   `.../error-declared-schemas-persist-through-the-real-occurrence-owner`,
   `.../preparation-and-recording-preserve-every-instrumentation-arm`,
   `seon.render-coverage-test/important-runtime-entities-declare-and-use-readable-faces`,
   `seon.turn-continue-test/provider-progress-has-its-own-bound` and `.../read-results-arrive-before-done`, which fail
   on HEAD with nobody's edit (run `0341e32bb37f`). The task `seon.turn/a-reached-test-unrun-or-red-on-main-does-not-block-done`
   (`6a403c2e27bf`) is that fix; its proof is red today by this evidence. Without it, four of five chains can reach
   `done` only when every reached test happens to be green on the base.
2. **Put the first write before call 15.** Three chains first write at form 49 to 90, and 91a8 never. The task's
   plan carries the references; the agent re-reads them (235 read forms, 9 reads of one source by 91a8). Hand the
   agent the red proof's failure and the referenced sources in its first block so the first `defn` is one of the first
   five forms; the reach is already 1 member per form, so a late write is the only thing the cap is spent on.
3. **Make the interpreted evaluator agree with the compiled one for the tests the chains reach (Broken 2), then stop
   `in-ns` repeats at the producer.** Six tests are green compiled and red interpreted on the same program; 28 identical `in-ns` repeats (15 on ce9d, 10 on fd3b) cost a form each. The `in-ns` repeats are 28 forms; the cause of the repeats is not read here.

## What this run answers

- Five concurrent agents did not hurt default: old gen peak 836 MB of 4,096, no pressure refusal, no OOM, no new heap
  dump, `bin/seon status` OK throughout, no platform JVM.
- Merged: 1 of 5 (35 calls, 79.5 s); 5 of 9 in all.
- Per request: median form 0 ms, one 1.2 s outlier (an agent's whole-store `d/q`); total evaluation time per chain 0.7
  to 1.7 s of a 79 to 113 s chain; the time is the model's.
