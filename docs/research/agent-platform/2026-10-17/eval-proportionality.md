---
type: research
status: owning document for the eval-request cost work; revised in place as stages are fixed
basis: default's JVM at 553c9d5d5 (pid 56939), 2026-10-17, own-branch agents eval-trace-1 to -6 through MCP eval_clj; throwaway check JVMs for parent-vs-change
---

# What one eval request costs, stage by stage

The owner asked why simple operations can take the system down. One eval request runs
read → evaluate → record (settle: commit, writer) → test step → print, plus custody and
fork before and context render after. For each stage: what a one-form change must do,
what that is proportional to, what it costs today, the gap and its producer.

Figures are the request's own cost map (`:seon.repl/cost-result`, `:seon.cost/stages` in
ms, `:seon.cost/step`, `:seon.cost/commit`, `:seon.cost/writer`) and `seon.profile/measure`.
Allocation is the calling thread's unless marked JVM-wide.

## End-to-end measure: `seon.cost-estimate-test/estimates`

Each fix is judged on these rows (`seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`,
one throwaway check JVM each, measured requests through MCP `eval_clj`). Parent is HEAD c6777da6b with
`src/seon/sci/eval.clj` from before 36c192be9; change is HEAD c6777da6b.

| row | estimate MB / calls / reads / ms | parent | change (36c192be9) | stages it lands in | task |
|---|---|---|---|---|---|
| `:seon-db-nine-forms` (9 unchanged `seon.db` forms) | 80 / 12,000 / 150 / 400 | 8,851 / 929,662 / 23,740 / 3,797 | 5,857 / 587,548 / 22,905 / 2,073; **871 / 93,090 / 1,358 / under 1,200** with the arm skip (below) | derive (now dominant), evaluate | `seon.repl/re-evaluating-nine-seon-db-forms-allocates-nine-gigabytes` |
| `:redefine-one-form` | 24 / 4,000 / 60 | 289.5 / 41,028 / 316 | 289.8 (unchanged) | settle, test step, unattributed | `seon.repl/redefining-one-form-costs-three-hundred-megabytes` |
| `:current-state-with-task` | 24 / 3,000 / 100 | 193.9 / 37,832 / 211 | 193.4 | context | `seon.context/one-agents-current-state-costs-hundreds-of-megabytes` |
| `:new-def` | 16 / 2,500 / 40 | 145.8 / 17,245 / 225 | 143.8; 111.1 / 15,558 / 202 after 70ba7304b and d0a465cc7; **63.7 / 13,972 / 203** after the overlay fold and the projection fold (section below) | test step, settle, derive | `seon.repl/a-new-def-costs-a-hundred-forty-megabytes` |
| `:merge-one-function` | 48 / 8,000 / 200 | 104.4 / 28,305 | 117.0 | merge | `seon.cluster.source/accepting-a-one-function-merge-allocates-hundreds-of-megabytes` |
| `:fresh-agent-first-request` | 12 / 1,500 / 30 | 106.3 / 8,112 / 63 | 104.3 | custody (fork), settle | `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` |
| `:reuse-one-unchanged-test` | 8 / 1,500 / 25 | 26.4 / 9,083 / 72 | 27.6 | test step | `my.test/reusing-one-unchanged-test-costs-nine-thousand-calls` |
| `:unchanged-request` (`nil`) | 4 / 500 / 10 | 17.0 / 6,704 / 44 | 17.1 | settle, unattributed | `seon.repl/an-unchanged-request-costs-seventeen-megabytes-and-six-thousand-calls` |

A ninth row, `:hub-docstring-change` (`seon.db/as-of-key` with one more docstring sentence; estimate 30 MB / 4,000 /
60 / 150 ms, overrun 221.6 MB / 56,529 / 305 under task `seon.repl/a-docstring-only-change-to-a-hub-function-costs-hundreds-of-megabytes`),
waits as `docs/research/agent-platform/2026-10-17/eval-trace-3-docstring-row.patch` (against ed12f110a) while lane cost-wave1b holds the
test file; its numbers come from the same request measured alone on a running agent, not after the table's earlier rows.

The nine-forms row's recorded overrun is lowered to the change's measure in the same commit as this
table, so the ratchet (1.5x) now refuses a return to the parent's 8.9 GB.

Re-evaluating a definition the branch already holds no longer arms a closure walk (lane eval-trace-2):
`seon.sci.arm/arm!` skips `seon.sci.eval/reinterpret-inherited-callers!`, `seon.sci.eval/interpret-affected!` and
`rearm-dependents!` when the form's source, docstring aside, is the source the branch holds for the symbol
(`behavior-held?`): every caller already reaches that behavior. The form's source reaches arming on the form's
world (`:seon.batch/source`, set by `seon.batch/evaluate-form`); that one line is in a file lane cost-wave1 holds,
so it waits as `docs/research/agent-platform/2026-10-17/eval-trace-2-form-source.patch` (with the overrun lowered
to the change's measure). Measured with the patch applied, both on 056b43883 in throwaway check JVMs:

| nine-forms row | MB | calls | reads | ms |
|---|---|---|---|---|
| parent 056b43883 | 5,697.3 | 582,136 | 22,905 | 2,562 |
| change (arm skip + form source) | **871.4** | **93,090** | **1,358** | under 1,200 |

What remains of the row (cost map of the same request, first and second time): derive 561 / 489 ms, evaluate
205 / 125 ms, settle 55 / 30 ms, test step 3 ms; the step thread allocates 805 / 686 MB. Derive, the clj-kondo
analysis and digest of nine forms of a 6,500-line namespace, is now the stage that dominates: about 55 ms per form
against an estimate of a few.

## Reference requests (default 553c9d5d5, warm, own agent branch, no task)

| request | ms | MB | calls | reads | settle | test-step | unattributed | derive | evaluate |
|---|---|---|---|---|---|---|---|---|---|
| `nil` (3rd) | 44 | 25 | 6,828 | 40 | 29.4 | 2.9 | 8.6 | 0.0 | 1.1 |
| first new `(def d1 {:a 1})` | 136-403 | 139-424 | 14,658-16,027 | 159 | 61.3 | 299.9 | 21.0 | 15.4 | 2.9 |
| second new `def` | 154 | 165 | 16,499 | 183 | 68.9 | 51.9 | 18.4 | 9.8 | 2.6 |
| redefining a `def` | 88 | 82 | 15,395 | 144 | 35.3 | 24.1 | 13.9 | 9.6 | 2.4 |
| new contracted 1-line `defn` | 237 | 270 | 61,219 | 291 | 89.7 | 56.0 | 50.8 | 15.0 | 21.0 |
| redefining it | 157 | 172 | 41,449 | 211 | 57.8 | 31.0 | 39.0 | 12.0 | 14.0 |
| `(defn seon.db/as-of-key ...)`, fresh branch, before 36c192be9 | 16,251 | 21,261 | 4,851,867 | 22,429 | 522.6 | 13,479.2 | 685.6 | 79.4 | 1,388.6 |

Settle's recording transaction for `nil`: commit 9.5 CPU ms / 6.2 MB, writer 5.4 CPU ms /
6.7 MB. For the new `defn`: commit 33.2 / 23.3 MB, writer 32.4 / 36.5 MB. For the hub `defn`
the writer ran 72 transactions (1,067 MB), the test runs' writes.

## Stage table

| stage | must do for a 1-form change | proportional to | measured | gap | producer | status |
|---|---|---|---|---|---|---|
| custody, fork | take the branch head, fork the SCI context | constant (pointer) | 0.5 ms, 0.3 ms | none | — | ok |
| read | read the form once | the text | 0.0-0.1 ms | none | — | ok |
| derive | clj-kondo analysis of the changed definition | the form | 10-15 ms on a def/defn (kondo itself 7 ms), 79 ms on the hub defn | about 2x on a def | `seon.fn/runtime-analysis-batch` | open, small |
| evaluate: hub defn | bind the changed Var; its compiled callers interpret on first call | the callers bound (lazy), never whole test namespaces | 930 MB, 1,208 ms, 239,492 calls (`seon.sci.eval/interpret-affected!` alone, check JVM); 1.08-1.20 GB in the request | 13 test namespaces entered and interpreted whole, 427 rows, 8 transactions, 15 refusals recorded | `seon.sci.eval/interpret-overlay!` interpreted value definitions of unentered test namespaces eagerly | **fixed 36c192be9**: 197 MB, 137 ms, 85,073 calls, 0 test namespaces; end to end the nine-forms row 8,851 to 5,857 MB, 3,797 to 2,073 ms |
| evaluate: hub defn, remainder | as above | the callers bound | 197 MB / 137 ms for the 2,338-member closure; a second defn of the same name re-walks it: 87 ms / 211 MB | the walk and 2,308 lazy-root binds are proportional to the whole closure, not the callers called | `seon.fn/reverse-closure`, `seon.sci.eval/bind-lazy-members!` | task `seon.sci.eval/promoting-a-hubs-closure-is-sub-second` |
| evaluate: a hub defn the branch already holds | nothing: no caller's behavior changes | the form | each of nine `seon.db` forms walked its closure (about 200 MB each) | all of the walk | `seon.sci.arm/arm!` armed every redefinition | **fixed by eval-trace-2** once `eval-trace-2-form-source.patch` lands: nine-forms row 5,697 to 871 MB |
| evaluate: ordinary defn | compile, arm the contract | the form and its contract | 14-21 ms | modest | contract arming | open, small |
| settle (record) | one transaction of the request's facts (evaluation, call, answered t; a definition's entity) | datoms written (tens) | `nil` 29 ms (commit+writer 15 CPU ms, 13 MB); defn 58-90 ms, writer 14-37 MB | about half of `nil`'s settle is outside commit and writer; a defn's writer allocates 30+ MB for tens of datoms | not isolated: `seon.turn/settle-evaluated!`, `seon.db/commit-database`; the sentinel's settle regression (+10 ms since e9bcf36d2) | open: bisect per `sentinel-1cb582f0d.md` |
| test step, nothing reached | find that no test reaches the change | the change's reverse edges | 24-56 ms warm, 300 ms on a branch's first new def (step allocation up to 376 MB) | 10-100x | reach derivation over the program (`seon.test.runner/tests-reaching`, `seon.test/host-exclusions`; bebd51673 cut the destructive reach) | tasks `seon.turn/a-new-definition-request-costs-hundreds-of-megabytes`, `seon.test.runner/reach-on-an-uncommitted-value-costs-the-change-not-the-program` |
| test step, hub docstring-only defn | find that no test observes the change | the form | 10,824.7 ms, 111 tests run, request 9,846 MB / 11,072 ms; the next `nil` 7,768 MB / 20.8 s | all of it | the member walk was seeded by `seon.context/change` (identity diff, definition digest) while reach keys on the behavior digest | **fixed by eval-trace-3**: `seon.cluster.source/merge-members` seeds only what changed behavior since the merge base (`seon.test/observable-changes`, the predicate `seon.test/branch-delta` already used), for the step, the done rule and the gate at once: test step 9.7 ms, no test; request 221.6 MB / 283 ms; the next `nil` 58.2 MB (check JVM, same probe, parent ed12f110a, which carries eval-trace-2's arm skip). Remainder (derive 86, unattributed 75, settle 61, evaluate 39 ms) in task `seon.repl/a-docstring-only-change-to-a-hub-function-costs-hundreds-of-megabytes` |
| test step, hub behavior change | run the tests the change reaches | the selected tests' own cost | 11,279 ms test step, step thread 4.1 GB, request 9.9-10.3 GB, for 137 reaching tests; 130 end in "Uncaught exception, not in assertion." and "Isolation requires a fresh owned branch.", thrown from SCI-interpreted frames of `seon.cluster.agent/acquire-once!` | a harness that runs interpreted platform code: the hub's closure binds `seon.cluster.agent/acquire-once!` to a lazy interpreted root on the agent's branch | `seon.sci.eval/interpret-affected!`, `seon.sci.eval/bind-lazy-members!` | open, reproduced in a check JVM (eval-trace-3), task `seon.test.runner/an-agents-reaching-tests-error-on-the-harness-after-a-hub-defn` |
| test step, first `seon.test/stale` after boot | read each member's recorded result at its reach digest | the members and their reach | 5,352 MB / 2,166 ms for 140 members (3,474 cold `seon.db/behavior-digest`, about 1.5 MB each); warm 0.3 ms | the per-definition constant | `seon.program/without-docstring`, `seon.program/source-resolver-names` (two parses per definition), `seon.db/declaration-value` | open: task `seon.db/a-cold-behavior-digest-allocates-a-megabyte-and-a-half` |
| print | print the shown value | the shown value | 1-3 ms (61 ms with the hub's test report) | none | — | ok |
| unattributed | nothing: every ms belongs to a stage | — | 8.6 ms (`nil`) to 51 ms (defn), 686 ms (hub) | all of it | includes the context re-acquisition after the branch's program moved (`seon.cluster.agent/acquire-context!` → `seon.sci.eval/program-context` → `seon.sci.eval/base-ctx`, 18-20 ms) | open: attribute it to a stage first |
| per-request growth | none | constant per request | calls grow by 16 per warm request | grows with the agent's history | `seon.cluster.registry/fork-point` walks first-parent history | task `seon.cluster.registry/the-fork-point-is-walked-for-on-every-request` |
| context (`seon.context/current-state`) | render the agent's attachments | what is shown | 19 ms / 28 MB, 19 reads, agent without a task | none without a task; 1.1 s / 1 GB reported with a task, not reproduced here | — | open: measure with a task |

## One new def, by stage (lane new-def-overhead, 2026-10-18)

`(def x {:a 1})` in a fresh namespace on an own agent branch of default (HEAD d24ad58e7), the 13th such def:
171 MB on the turn thread (thread allocation sampled every 0.2 ms with its stack, 743 samples), writer 32 MB,
commit 16 MB; stages settle 75 ms, test step 70 ms, unattributed 23 ms, derive 12 ms, evaluate 5 ms. What one new
form with no caller and no reaching test must cost: one analysis, one entity of about 13 datoms, one transaction,
no test; about 16 MB.

| stage (turn thread unless named) | must do | measured MB | producer | status |
|---|---|---|---|---|
| test step: re-acquire the program context | advance the held context by one row | 64 | `seon.cluster.agent/acquire-context!` → `seon.sci.eval/program-context` | part fixed, rest open |
| — its lazy binds | nothing for a def with no callers | 30 (16 warm, 88 cold measured alone) | `seon.sci.eval/bind-lazy-members!` queried with an empty member list: Datahike runs `[?sym ...]` bound to `[]` as a scan of every function | **fixed 70ba7304b**: `:new-def` 136.5 → 117.6 MB, `:fresh-agent-first-request` 85.1 → 66.1 MB |
| — its overlay plan | the rows that differ from the loaded commit | 18-22 | `seon.sci.eval/derive-overlay-plan` read every function digest (`seon.sci.eval/function-digests`, 7,541) and every admission datom twice (`seon.sci.eval/authored-rows`, 18,495 for 18 agent rows) per program commit | **fixed by lane new-def-2**: `seon.sci.eval/overlay-plan` folds the plan its branch last held forward by the plan rows that differ since that commit (`seon.sci.eval/fold-overlay-plan`, `seon.db/datoms-diff` over identity, digest, admission and host binding); a branch's first plan folds from the loaded commit's empty plan, so there is one derivation. Equal to the old derivation on every one of 40 commits of an agent branch of default (SCI probe). `:new-def` 107.4 → 92.9 MB, `:fresh-agent-first-request` 65.0 → 49.3, `:unmap-one-def` 183.0 → 119.9, `:redefine-one-form` 111.5 → 93.9 (check JVMs, parent 9cc484c70) |
| test step: step members | one contribution for one new name | 48 | `seon.cluster.source/merge-members` → `seon.test/reaching-selection` on the new commit: 22 MB cold, 1 MB warm | open |
| pass: contract measures | nothing for a value definition | 21 | `seon.turn.batch/uncontracted-measures` built a judged `seon.db/with` value for a def `seon.cluster.source/contract-gaps` never judges | **fixed d0a465cc7** (`seon.fn/contract-carrier?`): `:new-def` 116.6 → 111.1 MB, 17,068 → 15,558 calls |
| every new database value: projection | nothing: a row without `:seon.fn/spec` adds no contract | 12-22 per value (speculative report 12 MB / 7 ms; a carried value 20 MB / 20 ms) | a def writes `:seon.fn/sym` and `:seon.schema.admission/source`, both in `seon.schema/projection-attributes`, so `seon.db/declaration-content-key` passed over every function row and admission datom again: once for the writer's report, once per in-transaction read (`seon.turn/receipt-settle-call`, `seon.turn/close-folded-call`), about 9 MB each | **fixed by lane new-def-2**: a speculative value folds from its connection's latest committed value (`seon.db/hold-basis!`) by the declaration datoms that separate them (`seon.db/folded-projection`, `seon.db/datoms-diff`, about 1.5 MB); `seon.schema/advance-projection` answers the basis's own projection when no row changed, so its entry names the value and no content key is read. `:new-def` 93.6 → 63.7 MB, `:unmap-one-def` 123.2 → 74.8, calls lower on every row (check JVMs, parent 76d4f4a68) |
| writer | one transaction of the request's facts | 32-48 (JFR, one def on default) | of it, `seon.db/declaration-content-key` 19-29 MB (the row above, now gone); the rest is `seon.db/write-owned-values-error` (23 MB sampled, spread over Datahike slices, Malli validators and stored-file probes) and `seon.fn/reconcile-tx-in` under `seon.fn/reconcile-call` (15-19 MB, `seon.fn/normalized-index-entity` 10 MB) | open: owned-value validation and reconcile normalization are the writer's remainder |

The empty-collection scan is general: 105 `src` queries take a `[?x ...]` input, and Datahike (raw `d/q`) scans their
clauses when it is empty (9.3 MB / 11 ms on default); only some callers skip the call.

`:unmap-one-def` rose from 102.8-109.9 MB (5099c7e16, d24ad58e7) to 178.6-197.3 MB at 50e879526 and later, back to
back on throwaway check JVMs; `seon.error/nearest-names`, that commit's only `src` change, is not called by the
request, so the mechanism is open; its overrun records the measured high.

## The gigabyte defn (fixed in 36c192be9)

`(defn seon.db/as-of-key ...)` on a fresh agent branch: the evaluation alone took 664-1,273
ms and allocated 1.08-1.20 GB (three fresh agents). It does not reproduce on the same branch
a second time (87 ms / 211 MB), and entering `seon.db` costs 8 MB, so the cost is the first
redefinition of a hub on a branch.

`seon.sci.batch` arming calls `seon.sci.eval/interpret-affected!` with the changed symbol.
Its reverse closure (`seon.fn/reverse-closure`) on default: 2,339 functions in 300
namespaces, 2,479 tests. 2,308 are interpretable functions and get lazy roots; 23 are value
definitions or macros, interpreted at once; 17 of those sit in test namespaces
(`seon.flow-test/test-environment`, `seon.effect-test/test-environment`, ...). Interpreting
a test namespace's value definition entered that namespace, and entering a test namespace
interprets every row it holds: 13 `seon.sci.eval/interpret-source-namespaces!` calls, 427
`seon.sci.eval/install-row!`, 430 reads of stored source, 8 transactions inside the
evaluation and 15 acquisition refusals recorded (`seon.flow-test`'s go-loop and
`flow-monitor` tests cannot interpret). JFR allocation sampling of the eval thread agreed:
`seon.db/with-declarations`, `seon.db/q`, `seon.db/pull-logical` under
`seon.sci.eval/enter-namespace!` and `seon.sci.eval/install-caller-host-bindings!`.

Clojure does not re-run a namespace on a redefinition, and Seon already interprets a test
namespace from its rows when it is entered, against the Vars the context then holds. So the
closure's members in an unentered test namespace are left to that entry; one predicate
(`unentered-test?` in `seon.sci.eval/interpret-overlay!`) now serves this and the
added-function rule that already deferred them.

`seon.profile/measure` around `interpret-affected!` of `seon.db/as-of-key` on the canonical
fixture, same test body, parent and change each in a throwaway check JVM:

| | bytes | ms | calls | reads | test namespaces interpreted |
|---|---|---|---|---|---|
| parent | 930,239,272 | 1,208 | 239,492 | 3,281 | 13 |
| change | 197,294,816 | 137 | 85,073 | 2,395 | 0 |

Proof: `seon.sci.branch-execution-test/a-redefinition-leaves-an-unentered-test-namespace-to-its-entry`.

## Other findings

- An agent that entered `seon.fn-test` (after `seon.flow-test`) in one request refuses every
  later form, `(+ 1 2)` included, with "SCI program acquisition refused a declaration.":
  task `seon.sci.eval/entering-a-test-namespace-with-a-refused-row-refuses-every-later-form`.
- `bin/seon check` once failed building the classpath with a `HashMap$Node` cast in
  tools.deps' parallel resolution; the retry passed. Not filed: one occurrence.

## Order of the remaining gaps

1. Re-evaluating unchanged hub definitions (nine-forms row, 5.9 GB): fixed in `seon.sci.arm/arm!` (eval-trace-2), live once its `seon.batch` patch lands; the row's remainder is derive (about 55 ms per form).
2. Test step of a hub change (13.5 s, 12.6 GB): a docstring-only change now selects nothing (eval-trace-3: 9,846 to
   222 MB, 11.1 s to 0.28 s). A behavior change still runs its 137 reaching tests, which error on interpreted
   platform code (reproduced in a check JVM); the first `seon.test/stale` after boot costs 1.5 MB per reached definition.
   Largest remaining, and it can exhaust the 4 GB heap with concurrent work.
3. Test step with nothing reached (24-300 ms): reach from the change's reverse edges.
4. Settle (29 ms for `nil`, half outside commit and writer) and unattributed time.
5. Closure walk and lazy binds proportional to the whole closure (137 ms / 197 MB per hub defn).
