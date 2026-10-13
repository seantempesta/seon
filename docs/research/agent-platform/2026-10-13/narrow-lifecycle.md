---
title: "Narrow check of the step-lifecycle hybrid: 12 claims on default, plus the phase check"
type: research
status: narrow check of hybrid-lifecycle.md (ba818fa97) section 7; decides whether phases P1-P6 may be filed as tasks. Two claims refuted (Q5, Q7), D6 and P3 and P4 must change; P1, P2, P5, P6 need file or order corrections
model: Claude Sonnet 5.5 (lane narrow-lifecycle)
basis: "HEAD ba818fa97; default on basis commit 6acd9418 (:faulted, open-fault 1); reads in JVM mode as root, writes only on my own SCI agent narrow (branch :agent-f47372f8508d); no restart, no reset, no write to main or to :attempt-8667f9088300"
---

# Narrow check: the step-lifecycle hybrid

## Result in one table

| Q | Claim | Verdict | Consequence |
|---|---|---|---|
| 1 | `step-verdict` says `:done` while the merge's static half refuses `:not-equivalent` on `:attempt-8667f9088300` | REPRODUCED | P2 stands |
| 2 | Static half warm 125-225 ms, at most 175 MB | REPRODUCED (152-213 ms, 137-149 MB; cold 367 ms) | the once-per-request trigger stands |
| 3 | `step-due?` fires on about 6 of Run 24's 39 requests | PARTIAL (7 of 39 wrote program rows; the stale-proof arm not replayed) | trigger stands; 7 not 6 |
| 4 | The six requires leave `[seon.edit]` and duplicate `[seon.sci.reader]` in `:seon.ns/source`; the fact omits `seon.edit`, the cycle refusal removing it after the text was edited | PARTIAL (text yes; the fact held `seon.edit` first and lost it later) | P3 stands; its description of the order is corrected |
| 5 | Evaluating `(ns seon.edit (:require ...))` replaces the stored `ns` form and the proof then passes | **REFUTED** | P3 as written would not make the help's advice true |
| 6 | `my.program/source 'seon.edit` shows declarations only | REPRODUCED | P3's `source` change is needed |
| 7 | A message wakes a finished agent and reopens its task | **REFUTED: the installed rule is the opposite** | **D6 fails; P4's second red proof cannot be written as stated** |
| 8 | the second `leaf-done` rule is the only live reader of `:satisfied` besides `close!`, `task-end`, `step-status` | PARTIAL (nine readers in six files plus six schema members) | P2/P4 file lists and deletion order change |
| 9 | A run request can carry a named input bound for the body, else P6 adds it | PARTIAL (not today; a test member's world is built without a task by design) | P6 must change `seon.test/bounded-result` and the request schema |
| 10 | `proven-task-file` refuses stored children the file lacks | PARTIAL (the refusal is `:conflict`; and no derived children exist at HEAD) | D11's choice stands; its reason is moot |
| 11 | `merge!` with `:datahike/expected-basis-t` refuses when main's basis moved | REPRODUCED (source plus an existing test) | usable, but it is a whole-head fence; see D6 |
| 12 | 29 turn-area defects carry a red tagged proof and name an existing owner | PARTIAL (50 tasks, 17 carry a proof, 33 none; owners resolve; red status not readable) | the "29 conversions" figure is wrong; D7's count of 0 interface work holds on the 17 that I read |

## Questions in detail

### Q1 REPRODUCED. `step-verdict` is done, the static half refuses

Form, JVM mode as root (read only), at default basis commit `6acd9418`:

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      own  (seon.db/branch-database main :attempt-8667f9088300)]
  (seon.context/step-verdict own "attempt/8667f9088300"))
```

Reply: `:status :done`, `:step :implement`, `:unmet {}`, `:broken #{}`, `:new-red #{}`, `:uncontracted nil`, proofs
`seon.edit-test/an-odd-map-anywhere-is-not-one-form` and `.../every-source-holding-an-odd-map-is-not-one-form`; **16.2 ms**.

The static half by hand on the same branch (`seon.cluster.source/compare-branch` against main's head, then `replacement`,
`seon.db/with`, `proven-files` with root `/Users/sean/src/seon`, `contract-gaps`, `seon.task/candidate-dangling`):
delta 2 identities, conflict 0, `proven-files` answered `:seon.source/write-back-error`, path `src/seon/edit.clj`,
message "The reindexed candidate does not declare exactly the accepted program.", 21 offending identities,
`contract-gaps` 0, dangling `{}`. Lint did not run, as in `prepare-merge!`, because the proof refused first.

Note: `seon.cluster.source/accept-merge!` does not call `compare-branch`, and runs `replacement`, `contract-gaps`,
`uncovered` and `candidate-dangling` after the gate, not `db/with` or `proven-files`. Hybrid 1.1's sentence that
`accept-merge!` runs "the two static checks `compare-branch` and `replacement`" should read `replacement`,
`contract-gaps`, `candidate-dangling`; `compare-branch`, `db/with`, `proven-files` and `lint-errors` are
`prepare-merge!`'s. The extraction is still right; only the attribution is off.

### Q2 REPRODUCED. Cost of the static half

`seon.profile/measure` around the form of Q1's second paragraph, four calls on a warm JVM (the first is cold):

| Call | ms | thread alloc |
|---|---|---|
| 1 (cold) | 366.9 | 200.2 MB |
| 2 | 213.4 | 149.1 MB |
| 3 | 152.2 | 137.3 MB |
| 4 | 168.6 | 149.5 MB |

Warm 152-213 ms, 137-149 MB, inside 125-225 ms and 175 MB. Caveat: lint (22-52 ms for one or two files per the
planners) did not run because the proof refused; a passing candidate adds it. The sum stays under 300 ms.

### Q3 PARTIAL. How often `step-due?` fires on Run 24

`seon.turn/step-due?` (private) is `forms-run > 0` and (`:seon.turn.loop/recording` entities nonempty, which are the
datoms of `seon.program/program-attributes` minus `seon.fn.cost`, or a proof stale). Replay by history: all datoms
of Run 24's branch in the txs of its 39 turns, filtered by `program-attributes`:

| tx | program attributes written |
|---|---|
| 536871339 | `:seon.ns/aliases 4, :seon.ns/requires 1, :seon.ns/source 2, definition-digest 2` |
| 536871343 | same shape (ns row) |
| 536871387 | `:seon.ns/aliases 5, :seon.ns/source 2` |
| 536871439 | `:seon.fn/*`, `:seon.fn/source 2`, `:seon.schema.admission/source 2` |
| 536871447 | ns row, `:seon.ns/requires 2` |
| 536871488 | ns row |
| 536871497 | `:seon.fn/calls`, `:seon.fn/source 2` |

7 transactions in 7 of 39 turns (the hybrid says about 6). Five of the seven are ns-row writes and one is the
function: the agent edited the ns form six times for one function, which is Run 24's O1. The stale-proof arm
(`seon.test/stale`) is not replayed: it would add requests only where a program write left a proof without a result,
which is the same requests (the proofs reach the program). So 7 is a floor and about 7-8 the expectation: 7 x 175 ms is
1.2 s, the hybrid's order of magnitude holds.

### Q4 PARTIAL. The six requires produce the corrupted ns form; the order differs from the claim

Two checks.

(a) Run 24's recorded history of `[:seon.ns/name seon.edit]` (`datahike.api/history` on the branch, txs 536871316-497):

| tx | `:seon.ns/requires` | `:seon.ns/source` `:require` clause after |
|---|---|---|
| 339 | + `seon.edit` | `[rewrite-clj.zip :as z] [seon.error.refusal :as error] [seon.edit]` |
| 343 | + `seon.sci.reader` | `... [seon.edit] [seon.sci.reader]` |
| 387 | (aliases +1) | `... [seon.edit] [seon.sci.reader] [seon.sci.reader :as reader]` |
| 447 | **- `seon.edit`, - `seon.sci.reader`** | `... [seon.edit] [seon.sci.reader] [seon.sci.reader]` (the `:as reader` spec dropped) |
| 488 | + `seon.sci.reader` | `... [seon.edit] [seon.sci.reader] [seon.sci.reader] [seon.sci.reader :as reader]` |

Final fact: `[rewrite-clj.zip seon.error.refusal seon.sci.reader]`; final text: a self-require and three `seon.sci.reader`
specs. So the fact **did** hold `seon.edit` when the text was first edited (tx 339) and lost it at tx 447; the text
never did. The hybrid's order (the cycle refusal removes it from the fact after the text was edited) is right
about "after", but "the fact omits `seon.edit`" is only true from tx 447.

(b) Replay on my own agent `narrow` (its own branch, HEAD's `seon.edit` ns row), namespace `seon.edit`:

```clojure
(require 'seon.edit)
(require 'seon.sci.reader)
(require '[seon.sci.reader :as reader])
(require 'seon.sci.reader)
```

245.7 ms. After the request, `(seon.db/pull (seon.db/db) [:seon.ns/source :seon.ns/requires ...] [:seon.ns/name 'seon.edit])`
answered requires `[rewrite-clj.zip seon.edit seon.error.refusal seon.sci.reader]` (self-require in the fact) and a
clause `[rewrite-clj.zip :as z] [seon.error.refusal :as error] [seon.edit] [seon.sci.reader :as reader]`. The
self-require reproduces on HEAD in one request. The bare-then-aliased duplicate of the history did not (here
the bare `[seon.sci.reader]` was subsumed by the aliased spec), and it is the later `seon.sci.reader` re-require
across requests that produced the extra specs in Run 24.

Consequence for P3: the cause stands (text is appended from live bindings by `seon.sci.eval/declared-source`,
never derived from the fact) and so does the fix. Its red proof should replay the history order (three requests
across two ns writes), not only six forms in one request; the one-request version already shows the self-require.

### Q5 REFUTED. Evaluating `(ns ...)` does not repair the stored ns form

On `narrow`, after Q4(b):

```clojure
(ns seon.edit
  "Pure structural and exact source transformations. ..."
  (:require [rewrite-clj.zip :as z]
            [seon.error.refusal :as error]
            [seon.sci.reader :as reader]))
```

105.8 ms. Reading the row again, `:seon.ns/requires` is `[rewrite-clj.zip seon.error.refusal seon.sci.reader]`
(self removed) but `:seon.ns/source` ends

```
[seon.error.refusal :as error]
[seon.edit]
[seon.sci.reader]
[seon.sci.reader :as reader]))
```

It grew: the correct form was evaluated and the stored text appended another `[seon.sci.reader]` and kept
`[seon.edit]`. `declared-source` only appends the libspecs of what a context adds and removes aliases or refers
it no longer holds; no path replaces the clause. `proven-files` on this branch (delta
`#{[:seon.ns/name seon.edit]}`, 76 ms) still refuses `:not-equivalent`, 21 offending identities; the same
refusal as before the `(ns ...)`, and as on the evidence branch with a one-identity delta (84 ms). The advice
in hybrid 1.7 ("corrects it by evaluating `(ns seon.edit (:require ...))`, which the installed path already
carries") is false at HEAD, and `a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` proves only the clean
case (a require added to a clean ns).

Consequence for P3: the derivation (clause rebuilt from the row's requires, aliases and refers, self excluded) is
what makes an `(ns ...)` evaluation authoritative; the fix as described does it (text becomes a function of the
fact, and the fact is correct after the cycle refusal), but its red proof must include "after evaluating a clean
`(ns ...)` over a corrupted row, the written ns form equals the derived clause", and the help must not teach the
`(ns ...)` repair until that proof is green. Without it, P3 can be green while the agent's only repair still fails.

### Q6 REPRODUCED. `my.program/source` of a namespace shows declarations only

`(my.program/source 'seon.edit)` on `narrow`, 81.9 ms:
`{:seon.ns/name seon.edit :seon.program/tests [] :seon.program/functions [seon.edit/actual-edit ... seon.edit/zipper-edit]}`.
No `:seon.ns/source` and no requires. `my.program/source` dispatches a simple symbol to `namespace-declarations`.

### Q7 REFUTED. The installed rule is that no wake reopens a finished agent (load-bearing for D6)

`seon.turn/next-agent-work` at HEAD:

```clojure
;; AN ENDED TASK CLOSED THE AGENT: no wake reopens it
(admission-closed? db agent-id) nil
```

and its docstring: "An agent whose task ended (`task-end`, recorded by `end-task-call`) has its admission closed and is
given no work, whatever wakes it." `seon.turn/end-task-call` writes `:seon.agent/admission-closed-tx` and the attempt's
end once ("a task's end is recorded once"). The task name `seon.turn/a-wake-reopens-a-finished-task`
(`resources/seon/tasks/seon.turn.edn`) is a **defect name**: its intent says "an agent whose task is done, capped or
given back is reopened by the next message ... Done when ... no later wake opens a turn for it". Its tagged test,
`seon.turn-continue-test/a-done-agent-opens-no-work-turn`, asserts `(nil? (turn/next-agent-work ...))` "a wake opens no work
turn" and "the model was not called again". That test is red on HEAD because the end does not close admission
(`turn-reds-triage.md`: "3 model turns, expected 1; `:seon.agent/admission-closed-tx` nil"), not because wakes
should reopen. The hybrid read the task name as installed behavior.

Consequence for D6 (main moves between the verdict and the merge): "message wake of the same attempt" is the
behavior this task and three red tests are removing. It also contradicts the end being written once on the attempt
and squash-merged to main with its branch deleted. D6 must be reverted to Opus's relaunch, which already exists:
`seon.cluster.agent/failed-pass!` plus `relaunch-reason` (dial `:seon.config.run/relaunches`, default 2) writes
`:seon.task.attempt/relaunch` with the refusal as reason and the end commit, `seon.task/ready` admits the task again and
the next attempt forks the end commit. The hybrid itself keeps `relaunch-reason` and the dial for `:capped`/`:stalled`.
Keep that arm and delete only `fixable-refusals`: a merge refused for a **declared** reason after a `:done` end
(main moved) relaunches while the dial lasts. The cost is small: a fresh attempt of a `:running` task proves first and
"an already-green attempt ends :done with no model call" (`seon.turn/a-fresh-attempt-is-work-without-a-message`), and P2's
`prove-turn` records the verdict against the new head, so a relaunch whose candidate is still accepted ends `:done`
with 0 model calls. P4's second red proof becomes `a-merge-refused-after-main-moved-relaunches-and-ends-done-without-a-model-call`.

### Q8 PARTIAL. Readers of `:satisfied`

`rg ":satisfied"` over `src` and `resources` (HEAD), producers and readers:

| File | Where | Role |
|---|---|---|
| `src/seon/context.clj` | `step-status` (200-219), `task-finished?` (428), the step render (698) | producer; two readers of `#{:done :satisfied}` |
| `src/seon/turn.clj` | `task-end` (2151-2161), `prove-turn` docstring (5282), `next-agent-work`/`stalled?` docstrings (2238, 2266) | reader of the verdict |
| `src/seon/task.clj` | `ready-rules` second `leaf-done` rule (949), `done?` docstring (1026) | reader |
| `src/seon/cluster/agent.clj` | `next-actions` delete-branch arm (2105-2106), `close!` (2270-2271) | readers |
| `resources/seon/schemas/` | `seon.task.edn` (78, 98), `seon.task.attempt.edn` (4), `seon.context.edn` (76, 85, 96) | enum members |
| `test/seon/schema/datahike_parity.edn` | | member list |
| tests | `context_test.clj` 442, 460, 461 (table rows), `db_flow_test.clj` 356-377, 379-394, 1294-1330 | readers |

The hybrid names three readers besides `leaf-done`; there are six more (`task-finished?` and the step render in
`context.clj`, the `next-actions` arm in `agent.clj`, the schema members, the parity file, and the test rows).
Two extra facts that change the plan:

- Default holds **0** attempts ended `:satisfied` (query `[?a :seon.task.attempt/end :satisfied]`: `#{}`; ends on main:
  `{:done 1 :error 1}`), so D4's "enum value kept historical until a reset" is unnecessary on default; the member can go in P4.
- `a-task-satisfied-at-the-base-opens-no-model-turn` (db_flow_test 356, an **Implement** step green at its fork point) is
  not in P4's list of converting tests; only 379, 1252, 1292 are. It needs the Implement variant of the no-op merge too.

### Q9 PARTIAL. A run request cannot carry a named input today

`seon.test/run` takes `:seon.test/request` (`resources/seon/schemas/seon.test.edn`, line 314): execution,
recording-connection, policy, identities, namespaces, changed, flags and limits. There is no `:seon.test/task` and no
open key. A test body is the Var's `:test` function with no arguments (`seon.test.runner/run-vars!`). The seam for
inputs is `seon.batch/world-members`, whose members include `:seon.task/id`; a function declaring `:seon.task/id` has it
filled from `seon.batch/*world*`. But a test member's world is built in `seon.test/bounded-result` with `world/world`
over cluster name, connection, SCI context and environment only, and its comment says "no requester member (agent,
task, connection, store) is supplied to it"; `seon.test/a-test-world-has-no-agent` is a filed task on
`clean-request-handle`. So P6 needs: a new optional request key `:seon.test/task` (schema `seon.test.edn`), carried
through custody to `bounded-result`, which adds `:seon.task/id` to the member world; and the reach digest of a
template test must include the task's content digest (hybrid 3, row 6). P6's files gain
`resources/seon/schemas/seon.test.edn` and `src/seon/test.clj` (already listed) and the schema file.
Open risk, not probed: the member branch is a clean world with "program rows only" plus the execution's differing
rows, so a handoff test sees the step task's declaration only if task declarations are program rows there. The P6 red proof is the place to find out.

### Q10 PARTIAL. `proven-task-file` and derived children

`seon.cluster.source/proven-task-file` compares, for each written task, the declaration the file holds
(`seon.task/file-declarations` then `seon.task/declaration`) with `was` (before) and `now` (after); a task whose file
declaration is neither is `:conflict`, not `:not-equivalent`; `:not-equivalent` is the read-back of the written candidate.
So derived children absent from the file would refuse as `:conflict` before text is written. At HEAD there are **no derived
children**: `rg ":chain"` finds no `:seon.task/chain` in `seon.task` or the schema; `file-declarations` reads
children only from entries. D11's decision (explicit children, one conversion script) stands; its reason is
only a hypothetical, and the "29 conversions" are the 17 in Q12.

### Q11 REPRODUCED. The Datahike fence exists, and it is a whole-head fence

`reference-code/datahike` at gitlink `c9e57c6f`: `datahike.writing/transact!` throws `:transaction/stale-basis` unless
`(= expected-basis-t (:max-tx old))`; `datahike.writing/merge-writer!` calls it unchanged;
`datahike.versioning/merge!` documents "The arg-map form `{:parents :tx-data :tx-meta :datahike/expected-basis-t}` shares
`transact!`'s basis fence". `seon.db/transact!` forwards the key. The existing test
`seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage` asserts a merge with `:parents` and a stale `:max-tx`
is refused (not run by me). Caveat for use: the fence compares **the whole head's `:max-tx`**, so any write to main (a
message, a run, another agent's merge) stales it. On a quiet default `:max-tx` did not move in 5 s (536871373), but root's
`db<->flow` writes to main on every end, so using it on every merge would convert unrelated writes into refusals.
`declarations-held-tx` on the touched digests is the right default; the full fence is only for a merge whose judge read
beyond the touched identities. This matches the hybrid's wording; I record it as a constraint on P4.

### Q12 PARTIAL. The count of defects with proofs

`resources/seon/tasks/seon.turn.edn` holds **50** tasks. By `seon.task/task-edges` on default: 17 carry proofs (a deftest
tagging them), 33 carry none. All 17 name at least one owner that resolves to a live function, test or task; for one
(`the-test-step-writes-nothing-on-main`) two of its cited symbols (`seon.turn/record-reached-red!`, `seon.turn/reached-red`)
are marked "(deleted)" in its own text. Red status is not readable on main: `seon.test.runner/latest-results` on main
answers nothing for those 17 (no recorded run there), so "carry a red tagged proof" is unverified, and the triage
document (`turn-reds-triage.md`) is the only evidence of reds. The "29" could not be reproduced from any query I ran.
D7's claim that no defect needs interface work is not contradicted by the 17 I read; the 33 untagged defects are
exactly where Prove adds its reported-input row, so they are the Prove steps' real work, not "conversions".

## Phase check: red proofs and deletions

| Phase | Red proof exists or will exist? | Deletions sit in the commit that converts the last caller? | Corrections |
|---|---|---|---|
| P1 | will exist (`seon.cluster.merge-test/the-judge-answers-what-the-merge-refuses`; `test/seon/cluster/merge_test.clj` exists) | no deletions | add: an empty delta is not a refusal in `merge-refusals` (moved from P4); otherwise P2 leaves every no-op step `:working` (judge says "changes nothing the head lacks") |
| P2 | will exist (`seon.context-test/a-step-is-done-only-when-its-merge-would-accept`; `context_test.clj` exists) | `unmet`, `reds`, private `designed-red?`/`contract-gaps`/`base-results` have callers only in `src/seon/context.clj` and `seon.turn/record-broken!` (5438-5480), plus tests `context_test` 438-626 and `test_failure_facts_test` 185/202; all in P2's commit, so yes | the file list misses `resources/seon/schemas/seon.context.edn` (`:unmet`, `:step-observation`, `:step-status`, `:step-verdict`, `:step`), `resources/seon/schemas/seon.turn.loop.edn` (`:seon.turn.loop/broken`), `resources/seon/schemas/seon.render.walk.edn` (`step-observation`), `test/seon/cluster/db_flow_test.clj` (369, `step-verdict`) and the table rows 442-461; `task-finished?`/step render `:satisfied` arms (428, 698) and `task-end` (2161) go here, not P4 |
| P3 | will exist (`seon.cluster.write-back-test/repl-requires-never-corrupt-the-written-ns-form`) | `declared-source`'s append is replaced in the same commit | `seon.fn/runtime-require-specs` is `defn-` private: it must be made public or moved to the one owner both files call (a visibility change in `src/seon/fn.clj`); red proof adds Q5's case; replay the history order of Q4(a) |
| P4 | first proof will exist (`a-prove-step-whose-product-main-holds-merges-up-to-date`); **second cannot** (Q7) | `fixable-refusals`, `refused-merge-relaunch`: sole callers are `failed-pass!` (agent.clj 2229-2230), converted in P4: yes. `:satisfied` in `task.clj` 949, `agent.clj` 2106/2270/2271: last caller after P2 has stopped producing it, in P4: yes | D6 reverts to relaunch, so `refused-merge-relaunch` is kept and generalized, only `fixable-refusals` is deleted; also convert db_flow_test 356; drop the enum members now (0 live datoms) |
| P5 | will exist (`seon.task-test/a-step-reads-its-templates-instructions-beneath-its-subjects-goal`; `test/seon/task_test.clj` exists; `src/seon/step.clj`, `resources/seon/tasks/seon.step.edn` do not yet) | no deletions | the conversion script's input is 17 tasks with proofs plus 33 without, not 29 |
| P6 | will exist (`seon.step-test/prove-leaves-its-successor-red-proofs`; file new) | none | files add `resources/seon/schemas/seon.test.edn`; Q9 |

## Phases that must change

1. **D6 and P4:** revert D6 to relaunch under the dial; keep `refused-merge-relaunch`, delete `fixable-refusals`;
   rewrite P4's second proof (relaunch, no model call, `:done` against the new head).
2. **P3:** the `(ns ...)` repair is false today; the derivation must make it true, and the proof must show it before the help teaches it.
3. **P1/P2/P4 order:** the empty-delta acceptance moves into P1 (so P2's judge does not strand no-op steps); P2 takes the context/turn `:satisfied` arms and the three schema files; P4 takes `task.clj`, `agent.clj` and the enum.
4. **P6:** adds the request key and the member world's `:seon.task/id`.
5. **Hybrid text:** 1.1's attribution of `compare-branch` to `accept-merge!`; Q12's 29; D4's historical enum.

## Findings outside the assignment

- The `(ns ...)` eval on `narrow` ran a **test step of 1,593 ms** for 9 tests (`:seon.cost/stages :test-step`) with two reaching tests red from `seon.edit-test`; the same request totals 1,726 ms with 867 MB step allocation. An ordinary operation over one second; it belongs to `seon.turn/a-test-step-running-one-tiny-test-costs-half-a-second` (filed).
- `seon.context/step-verdict` on the evidence branch is 16 ms, as the planners measured.
- The probes ran as root in JVM mode (the bridge refuses JVM mode for any other agent, `JVM mode is root's`); they only read.
