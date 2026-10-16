---
title: "live run 4: attempt 3 of the five held render tasks, started by my.root/run!, zero hand acts by the observer"
type: evidence
status: "run complete 2026-10-16: 1 done then merge refused (42e0), 4 capped with the proof green; nothing merged; done and merge disagree again, on the member set"
---

# Live run 4: five held chains, attempt 3, observed read-only

Lane live-run-4 (Sonnet), observer only. Default JVM pid 6929 (started 07:40:04 UTC) at HEAD a169e0993, unchanged for
the whole run. Root ran `my.root/run!` on the five tasks still held after `live-run-3.md`; attempt 3 of each ran on its
existing chain branch `:chain-<handle>`. Hand acts by this lane: 0 (JVM-mode reads only: `d/q`, `d/pull`,
`seon.competency/grade`, `seon.render.ns/task-ai` over `seon.db/branch-database`; `bin/seon check --at HEAD`; no SCI
request as root). All five had ended by about 07:44 UTC (first poll that saw them ended: 07:46), so the 45-minute poll
was not needed. A fifth attempt never started: every chain shows exactly attempts 1 to 3 (nothing reran by itself).

## Summary

Wall is first call opened to last call closed (`seon.competency/grade`). Tokens are prompt / cached / completion. In
brackets, run 3's attempt 2 of the same chain.

| chain | task | attempt 3 end | calls (run 3) | wall (run 3) | tokens p / c / out | forms | in-ns / adjacent-identical (run 3 in-ns) | wasted calls | agent errors | score |
|---|---|---|---|---|---|---|---|---|---|---|
| `fddd9dcbfe66` | `seon.background/render-ai` `cb020f9c51d7` | **capped**, proof green, done refused (27 reaching tests with no current result, 9 red `seon.test-*` tests, `seon.test.runner/ambient-snapshot` uncontracted) (run 3: done, merge refused) | 40 (13) | 147.3 s (42.8 s) | 987,443 / 828,416 / 10,221 | 107 | 29 / 19 (3) | 11 | 10 | 41 |
| `ce9d664c1be0` | `seon.effect/render-ai` `cd9665907318` | **capped**, proof green, done refused (stray red: its own rewrite of `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence`) | 40 (40) | 142.5 s (150.2 s) | 977,524 / 818,304 / 11,453 | 123 | 24 / 13 (8) | 4 | 5 | 41 |
| `42e0e4c46b71` | `seon.error/elision-prose` `0a6a55057f93` | **done at call 12, merge refused** `:seon.source/test-evidence-error` (run 3: capped) | 12 (40) | 52.4 s (143.2 s) | 156,489 / 116,736 / 5,596 | 25 | 2 / 0 (9) | 4 | 2 | 12 |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai` `07157fa7e0bb` | **capped**, proof green, done refused (4 reaching tests with no current result; `render-run-ai` reaches no green test) | 40 (40) | 119.3 s (132.0 s) | 1,025,646 / 846,848 / 8,443 | 94 | 3 / 1 (19) | 20 | 2 | 41 |
| `fd3ba8395135` | `seon.repl/render-ai` `de7491351fa5` | **capped**, proof green, done refused (12 reaching tests with no current result) | 40 (40) | 102.3 s (157.8 s) | 725,674 / 610,304 / 5,562 | 63 | 4 / 1 (1) | 23 | 11 | 41 |

Totals: 172 calls (run 3: 173), 3,872,776 prompt tokens (run 3: 4,021,050), 412 forms (399), 62 `in-ns` forms (15%; run 3:
40, 10%), 34 of them identical to the previous `in-ns` (55%; run 3: 19, 48%, measured as the same ns entity, a
different but near measure). Wall of the five: 563.8 s (run 3: 626.0 s). Score: 41, 41, 12, 41, 41 (run 3: 13, 41, 41,
41, 41). Every capped attempt ends `proofs {:green 1}`: the named proof is green in all five; the done rule refused
each for something else. "In-ns adjacent-identical" is an `in-ns` form whose text equals the previous `in-ns` form.

## Broken things first

1. **Done and merge disagree again, now on the member set: `42e0e4c46b71` is done with no refusal and the merge ran
   three more tests and refused.** Chain done at 07:41:42; the merge branch `merge-989e44c36983` (kept; held merge step
   `unfinished-task-0a6a55057f93`) ran policy `:named` run `eea50b7aed31` at 07:41:45: 64 members, reason
   `:reaches-changed`, and three failed:
   `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner` (fail 2),
   `seon.error-test/every-error-owner-function-declares-its-input-and-output` (fail 1, interpreted),
   `seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm` (fail 1). On the chain branch these
   three have no result at all (a `d/q` over `:seon.test.run/members` of `:chain-42e0e4c46b71` for each symbol returns
   `[]`); only the proof and `specialist-renderers-use-their-declared-evidence` ran there. b54d25f68 made done require a
   current result from every member, but the member set that done reads is smaller than the merge's `reaches-changed`
   selection of 64. The `b54d25f68` fix removed fddd's disagreement of run 3 and moved it to a different member set.
   Two of the three are also red on main: run `07:41:59` on main, compiled, `(fail 2, pass 36)` and `(fail 2, pass 4)`
   for the first and third; the second (`every-error-owner-function-declares-its-input-and-output`) is green compiled
   on main and red interpreted on the merge branch: its failure value is `(ns-interns 'seon.error)` returning
   `clojure.core` functions (`restart-agent ensure ref-min-history gen-class ...`) so `(nil? (:malli/schema ...))`
   holds for them. That is an interpreted-evaluation difference, not 42e0's edit.
2. **The refusal's message does not say what refused.** The merge refusal is `:seon.source/test-evidence-error`,
   message "Every proof must be a member of the named run.", `:seon.error/offending
   [seon.error-test/the-elision-ai-render-returns-data]`, and `:seon.source/red` (a bare symbol vector) with the three
   red tests. The proof is absent from the 64-member run (a `:named` run that did not select its own proof) and three
   members are red; one message covers both and the red tests are not under `:seon.source/red-tests` as in the done
   refusals (`:seon.source/red-test-error`).
3. **A scratch `def` in a platform namespace changed the definition digest of two unrelated functions of it, and
   `fddd` burned the attempt on the consequences.** On `:chain-fddd9dcbfe66`, `seon.test.runner/ambient-snapshot` and
   `seon.test.runner/live-cluster-schema-states` have `:seon.program/definition-digest` (for `ambient-snapshot`) `b3a85f9c...`
   against `92b3b10f...` on main, with identical `:seon.fn/source` and `:seon.fn/references`. The digest
   datoms were written in tx 536874692 at 07:41:27.650 UTC, the recording transaction of the eval request holding
   `(in-ns 'seon.test.runner)`, `(def snap-before (keys @(var-get (resolve 'seon.operator.runtime/running-instances))))`
   and `(count snap-before)` (tx 536874691); the same tx rewrote 30 `:seon.ns/aliases` of ns entity 9771. The agent
   unmapped the Var in its next call, but the digests stayed. Consequences in the final verdict (run `6de45e22745a`):
   `seon.test.runner/ambient-snapshot` listed `:seon.source/uncontracted` (it is `defn-` without a schema on main too),
   9 `seon.test-*` / `seon.competency-oracle-test` tests red, 27 reaching tests with no current result. Not isolated:
   why equal source digests differently; the correlation (digest tx immediately after the `def` request, nothing else
   writing it) is observed, the mechanism is not.
4. **Four of five capped chains ended on tests that the run never ran: the reaching tests of a render change are not
   selected into the run the done rule reads.** The last `:named` run of each capped chain has 1 member, the proof
   (fddd `6de45e22745a`, ce9d `ec2bf3665296`, 91a8 `d0c313c19bc6`, fd3b `5219a254db21`; all `:not-ended []`,
   `:red []`). The refusal then says the 12 (fd3b), 4 (91a8) and 27 (fddd) reaching tests have "no current executed
   result; a test with none is unknown, not green". Nothing in the agent's reachable verbs runs a named test: fd3b
   called `(:seon.source/refusals (seon.context/step-verdict))` 15 times (calls 10 to 40), then tried
   `seon.test.runner/run-vars!` with hand-built arguments (NullPointerException `fut` is null; contract refusals on
   `seon.db/supplied-connection` and `seon.env/environment` arities, forms 38 to 57). That is the "never-run reaching
   test keeps done waiting" finding of a169e0993, reproduced on four chains.
5. **`ce9d664c1be0` still ends on `seon.effect/payload-unit seon.effect/render-ai` "reaches no green test": third
   run, same pair.** The reaching test is `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence`;
   attempt 3 is the first in which the agent rewrote it (form 94, call 31 of 40) and the rewrite is itself red (stray
   red, run `90d7d4d7600d`). Its refusal text, "A replaced function reaches no green test of the named run", read as a
   walk failure in runs 2 and 3, is the red coverage test; the verdict now names it under `:seon.source/red-tests`.
6. **`91a8` re-read the same four test sources 33 times without writing.** 94 forms, 2 progress forms (a
   scratch `def connection nil` and its unmap). `source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute`
   read 12 times, `page-review-test/turn-headers-and-all-evaluations-have-one-place-each` 13 times, 42 re-reads in all.
   It never edited either test; the new problem task names neither (its refusals list unknown tests as `:offending`).
7. **`in-ns` regressed from 10% to 15% of forms, driven by two chains: fddd 29 of 107 (19 identical in a row), ce9d 24
   of 123 (13 identical).** The other three chains have 2, 3 and 4. The cause is the same in both: each form is sent
   from `user` or another ns and `in-ns` is repeated before a `(require '[clojure.repl :refer [source]])` (fddd's loop
   forms 11 to 44 repeats `(in-ns 'seon.test.runner)`, `(require ...)`, two `(source ...)`, 8 times).
8. **Nothing reran by itself** (no attempt 4 on any chain), and no run-2-style write-back refusals or agent reverts: no
   chain pasted main's text over its own edit (the fddd revert of run 3 did not recur; fddd spent no form editing a test).

## Problem tasks: the verdict as data (36c0cfbf2, bdbdf75a7)

All five bails produced a held problem task `unfinished-task-<attempt>` with a fixed-text `:seon.task/plan` and the verdict
as stored EDN in `:seon.task/verdict`. `seon.render.ns/task-ai` at distance 1 returns the keys `:seon.task/id`, `:sym`,
`:name`, `:ns`, `:plan`, `:references` and `:seon.task/last-verdict`; the plan text contains no read stamps and no
printed values. What `:seon.task/last-verdict` carries per chain:

| chain | refusals | red test names under `:seon.source/red-tests` | other names |
|---|---|---|---|
| fddd | test-evidence (27 offending), red-test, uncontracted (`seon.test.runner/ambient-snapshot`, with `:seon.source/contract-gaps`) | 9 | 27 offending |
| ce9d | stray-red, test-evidence | 1 (twice) | `:offending [seon.effect/payload-unit seon.effect/render-ai]` |
| 42e0 (merge) | test-evidence (`results-verdict`) | 0 | `:seon.source/red` 3 names, `:offending` the proof |
| 91a8 | test-evidence x2 | 0 | 4 offending (no current result), `seon.render.transcript/render-run-ai` |
| fd3b | test-evidence x2 | 0 | 12 offending (no current result), `seon.repl/entity-emission render-ai render-html` |

The names are present for every chain, but only fddd, ce9d have them under `:seon.source/red-tests`; the merge refusal
uses `:seon.source/red`, the unknown ones `:offending`. The merge refusal of 42e0 has no `:seon.task/proof-counts`
(done refusals do carry `{:green 1}`), and no `:seon.source/red-tests` entries with a run id per test.
"proofs {:green 1}" is data (`:seon.task/proof-counts`) now, not prose beside a refusal (run 3, Broken 4 fixed).

## How each agent handled the existing tests its change turned red

| chain | read the red tests' source | calls spent | outcome |
|---|---|---|---|
| fddd | no (read `my.background-test/background-error-renderers-cover-every-declared-declared-schema` once, form 95, call ~25) | 0 on the tests; calls 4 to 40 on `seon.test.runner` internals | did not edit a test; did not revert its work |
| ce9d | 14 reads of `seon.render-coverage-test/effect-receipts-render-state-from-attribute-presence` and its helpers (first call 3, deftest call 31) | 28 calls to the rewrite, 9 after | rewrote the deftest whole in one form (with a `:seon.test/golden` justification); rewrite still red; 9 calls of reading `render-request` and the walk |
| 42e0 | 5 reads of `seon.error-test/specialist-renderers-use-their-declared-evidence` (first call 3) | 9 calls to the edit (call 12) | redefined the whole deftest in one `(deftest ^{:seon.test/golden "..."} ...)` form; green at 07:41:41; done |
| 91a8 | 33 reads of 4 test sources (first call 6, last call 40) | 40 | no edit, no `defn` of the render this attempt; capped |
| fd3b | 1 read (`seon.turn-continue-test/provider-progress-has-its-own-bound`, form 25) | 31 calls on `step-verdict` and `run-vars!` hunts | no edit; capped |

The one chain that got a changed test green took 12 calls: it read the test's source (5 forms), found the new form
(`testing` form refused as unresolved outside a test, then `(in-ns 'seon.error-test)`), and redefined the whole
deftest with `my.program` semantics. The other four never had a path to "run this test": Broken 4 applies. No chain wrote an extra test helper that stayed defined (`running-custody-for`, `leaf-strings` of run 3,
were unmapped).

## Done and merge, and merged runs

Done rule: only 42e0 passed it (call 12), and root's merge refused it (Broken 1); the other four never reached a merge.
So done and merge agree on four chains and disagree on one. Merges: none (`:stopped` none this run; no
`:seon.unmerged/entry` on main, `d/q` count 0; no merge commit after a169e0993; no trailers to read). Kept merge branches:
`merge-989e44c36983` (42e0, this run), `merge-62f83095ac57` (fddd, run 3), `merge-87a414ff6dd9` and `merge-b14ff04ae163`
(run 2). No platform JVM was observed during any chain's attempt or the one merge run (`:seon.test.run/evaluator`
`:interpreted` for the 64 members of `eea50b7aed31`, none isolated).

`bin/seon check --at HEAD --test seon.ai-test/the-provider-ai-render-returns-data --test
seon.babashka-load-test/declared-namespaces-load-in-babashka --include-long` (the only proofs merged by runs 2 to 4
that bear on HEAD's rendering are in the four merged chains; 05db's is `seon.ai-test/...`): run `36af0a95124e`, executed
2, pass 5, fail 0, error 0, 4,547 ms, `passed? true`, total 72.9 s (seed 52.9 s, boot 9.1 s). The babashka test is
green at a169e0993. Run 4 merged nothing, so no merged proof of this run exists to check.

## Cumulative tally of the nine render tasks, runs 2 to 4

Run 2 table in `live-run-2.md`, run 3 in `live-run-3.md`, this run above. Attempts per chain: 1 in run 2, 2 in run 3, 3 in
run 4 (the five open chains; the four merged chains stop at 1, 1 and 2).

| chain | run 2 (attempt 1) | run 3 (attempt 2) | run 4 (attempt 3) | end state |
|---|---|---|---|---|
| `dcc8` model-ai | merged | | | merged |
| `419a` config-render-ai | merged | | | merged |
| `8d9e` mcp-prose | merged | | | merged |
| `05db` provider-ai | done, merge refused | merged (0 model calls) | | merged |
| `fddd` background-render-ai | done, write-back refused | done, merge refused | capped | open, held |
| `ce9d` effect-render-ai | capped, done refused | capped, done refused | capped, done refused | open, held |
| `42e0` elision-prose | capped, proof red | capped, done refused | **done, merge refused** | open, held |
| `91a8` render-run-ai | capped, proof red | capped, done refused | capped, done refused | open, held |
| `fd3b` repl-render-ai | capped, proof red | capped, done refused | capped, done refused | open, held |

Merged 4 of 9, open 5, no chain abandoned. Model calls: run 2 275, run 3 173, run 4 172: 620 in all. Prompt tokens 5,599,023 + 4,021,050 + 3,872,776 =
13,492,849. Forms 835 + 399 + 412 = 1,646. Wall of the capped attempts rose from 105 to 111 s (run 2) to 132 to 158 s
(run 3) and fell to 102 to 147 s (run 4); calls per capped chain stayed at the cap of 40 in all three runs. Attempt 3 is
the first time a non-trivial attempt (42e0) reached done by an agent edit alone (12 calls); the five chains' common
state at the end is "proof green, something else not": proof green in 1 of 5 capped attempts of run 2 (ce9d), 4 of 4
in run 3, 4 of 4 in run 4.

## Defects found (exact forms and values)

- Done and merge member sets differ: Broken 1. Reproduce: `(seon.context/step-verdict chain-42e0-db "attempt/0a6a55057f93")`
  answers done; the member list of run `eea50b7aed31` on `:merge-989e44c36983` has 64 members and none of the three red
  tests has a member row on `:chain-42e0e4c46b71`.
- Message and data disagree on the merge refusal: Broken 2.
- Digest rewrite after a scratch `def` in a platform ns: Broken 3 (`(d/datoms (d/history chain) :eavt)` filtered to tx
  536874692 on `:chain-fddd9dcbfe66`).
- Reaching tests never selected into the run done reads: Broken 4. Cost to the agent, fd3b: 31 of 40 calls.
- `seon.test.runner/run-vars!` throws `NullPointerException` ("Cannot invoke java.util.concurrent.Future.get() because
  fut is null") on a call that passes `{}` as custody (fd3b form 57): an uncaught exception from a platform function, a
  finding under "nothing crashes".
- Agent-facing: `(my.program/history 'sym)` refuses ("expected :my.program/history-request (a map)", fddd forms 67, ce9d 110),
  `(my.program/diff 'sym)` the same (fddd 49, 50); `(my.program/source ... :seon.ref/at :main)` works and an old `:seon.ref/at`
  keyword (`:current-src`) refuses on a string name. 30 agent errors of 412 forms (7%; run 3: 36 of 399, 9%).
- `seon.render.ns/task-ai` at distance 1 prints `:seon.task/last-verdict` through `edn/read-string` of the stored string
  on every render; 42e0's merge verdict shape (`:seon.source/red`, no `:seon.task/proof-counts`) differs from the done
  verdicts (`:seon.source/red-tests` with run ids, proof counts).
- Evidence reads here were cheap: each `seon.competency/grade` read under 90 ms; the single large form was my test-member
  print (57 MB allocated), not a system path.

## Wasted-turn classes, ranked against runs 2 and 3

Run 4's 412 forms: read 151, re-read 69, navigation 62, probe 32, search 24, progress 23, repeat 21, contract refusal 13,
error 11, unresolved 6.

1. **Reading sources: 220 of 412 forms (53%; run 3 59%, run 2 44% plus 50 re-reads).** Same first place. 91a8 alone has
   81 read forms and 42 re-reads with no edit, 33 of them the same four test sources (Broken 6). The reads are of the
   tests the refusal named, the class named in run 3's Broken 2.
2. **Hunting the way to run or select a test: fddd 95 forms in `seon.test.runner`, fd3b 31 calls.** New in run 4. Both
   chains had the render already; the done rule asked for test results nothing would produce (Broken 3 and 4). It
   replaces run 3's "existing tests the render change turned red" (about 130 forms in four chains).
3. **`in-ns`: 62 forms (15%; run 3 10%, run 2 23%), 34 identical to the previous (55%).** Worse than run 3 on two
   chains (fddd 29, ce9d 24), 4 of 5 chains had 4 or fewer; the dial-down seen in run 3 is not stable.
4. **`step-verdict` polling: 21 `repeat` forms, 15 of them fd3b's `(:seon.source/refusals (seon.context/step-verdict))`.**
   It re-asks a value that cannot change until a test runs; an agent has no verb that changes it (Broken 4).
5. **Wrong API shape: 30 `:error` / `:unresolved` / `:contract-refusal` forms (run 3: 36, run 2: 52).** Declining; the
   same verbs (`my.program/history`, `my.program/diff`, `seon.db/connection`, `seon.test.runner/run-vars!`).
6. **Unrecorded reaches-no-green-test refusals: ce9d's 28 forms after the rewrite** (Broken 5), now visible in the verdict
   but not to the agent before it ends.

Key numbers: calls to done 12 for one chain (run 3: 0 and 13; run 2: 10 to 37); capped chains still at 40 calls; the
score is 41, 41, 12, 41, 41 against run 3's 13, 41, 41, 41, 41.

## Top three fixes the evidence supports

1. **Run the reaching tests the refusal asks for, in the same selection the merge uses: done and merge must read one
   member list.** Four chains capped on "reaching test with no current result" because the chain's `:named` run holds
   only the proof (members 1), and 42e0 was done while the merge's `reaches-changed` run held 64 members with three red.
   Derive done's members from `seon.cluster.merge` / `merge-list` (one derivation, every consumer), run them after each
   eval request that evaluated a form on the chain, and the cap, the unknown refusals and the 42e0 disagreement all
   disappear. Proof: red `seon.cluster.source-test/done-and-merge-judge-the-same-member-list`, comparing the two sets
   for chain 42e0's kept branch.
2. **Find why a scratch `def` rewrites other functions' definition digests in its namespace, and fix it at the digest
   producer.** `ambient-snapshot` and `live-cluster-schema-states` changed digest with equal source and references in the
   transaction that recorded `(def snap-before ...)` in `seon.test.runner`; the branch then carried a "changed"
   platform function, an uncontracted one and 36 reaching tests. A `def`/`ns-unmap!` pair should leave every other
   definition digest where it was. Reproduce on a disposable branch: `(in-ns 'seon.test.runner)`, `(def x 1)`, compare
   `:seon.program/definition-digest` of the two functions before and after.
3. **Name the red tests the same way for every refusal and teach the verb that runs one.** Put merge refusals'
   `:seon.source/red` under `:seon.source/red-tests` with run ids like the done refusals; say "a proof is absent from the
   run" and "a test is red" in two refusals; and give the agent the one form that runs a named test (91a8 read the same
   test source 33 times, fd3b guessed `seon.test.runner/run-vars!` arities for 22 calls; the first of the two throws a
   `NullPointerException`). The render of a held problem task should print the names of red tests as plain data, which it
   now does for fddd and ce9d only.
