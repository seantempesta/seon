---
title: Independent branch-runtime review 4 — Astra
type: research
created: 2026-10-20
status: NOT SAFE
---

# Independent branch-runtime review 4 — Astra

**NOT SAFE under the requested whole-runtime guarantee.** The candidate boots and the targeted fixes work, but method removal and preference writes still mutate sibling and parent runtimes, and new or open callers can hand a branch derivation to a compiled cache owner and receive another runtime's result. A loaded-code task also gets the right answer and a green proof but cannot finish, because test selection still excludes its loaded callers. These are concrete correctness failures outside the three exceptions the owner explicitly accepted for this review.

Reviewed source: `342e143571377dc4fd4aa0ba9df8c5e4c989958c`, also the archive default was running at the start. This is a judgement of that revision, not a claim that the restart operation itself is known to crash. Lane `review4-astra` read no review-4 report from the other reviewer and contacted no other lane. All experimental code and mutations were private-index dangling commits, run through disposable `bin/seon check --at` JVMs. Default was never restarted or reloaded and received no SCI request as root. The only SCI work on default was the explicitly requested sentinel through this reviewer's own agents.

## Authorities and baseline

Read AGENTS.md, [branch-runtime](../2026-10-19/branch-runtime.md), [first review](../2026-10-19/runtime-review.md), [re-review](../2026-10-19/runtime-rereview.md), [closed-runtime hybrid](../2026-10-19/closed-runtime-hybrid.md), both [review 3 Opus](runtime-review3-opus.md) and [review 3 Astra](runtime-review3-astra.md), [defn-perf](defn-perf.md), and the requested Git history since `2416e7476`. Read the affected runtime/cache/schema/database diffs, including the six named fixes. The SCI pin is `20ef88d7ea08813a5ab8172467672356c8b6e9b7`; the relevant seams are `sci.core/fork`, `sci.core/bind-root!`, `sci.impl.multimethods/multi-fn-add-method-var-impl`, SCI's copied core mutators, and `sci.impl.fns/in-defining-ctx`.

Default initially reported PID 6516, start `2026-10-19T22:43:49.935Z`, boot readiness 22,049 ms, 4 GB heap cap, roughly 944 MB live heap and 281 MB of the 512 MB cache budget. MCP `runtime_status` was **faulted**: seven open faults, seven agent-error signatures, 71 errored receipts, four malformed contracts and three unowned namespaces. Those are baseline health findings, not introduced by these probes.

## Blockers, ranked

### 1. Method removal and preference writes still escape a fork

The existing [multimethod issue](../../../seon/issues/a-multimethod-change-mutates-sibling-runtimes.md) explicitly notes the uncovered mutators. This review reproduces them; it does not file a duplicate. The accepted-exceptions list in this assignment does not include these writes.

Define a multimethod in an acquired parent, then make two forks through `seon.sci.eval/fork-cluster-ctx`. Change only the first fork. Observe `[changed-fork sibling parent]`:

| Edit in first fork | Observation | Expected | Actual |
|---|---|---|---|
| `(remove-method mm :a)` | `(mm :a)`, with default returning 0 and `:a` returning 1 | `[0 1 1]` | `[0 0 0]` |
| `(remove-all-methods mm)` | `(count (methods mm))` | `[0 1 1]` | `[0 0 0]` |
| `(prefer-method mm :a :b)` | `(prefers mm)` | `[{:a #{:b}} {} {}]` | `[{:a #{:b}} {:a #{:b}} {:a #{:b}}]` |

The SCI fix copies a MultiFn for symbolic `defmethod` and protocol extension. These three operations still invoke host functions on the shared MultiFn value. They do not use the Var-aware mutation owner. No JVM Var was redefined by the probe. The requirement covers agent-authored multimethods even though production `src` has no `defmulti`.

Red: `seon.runtime-review4-astra-test/method-removals-and-preferences-are-local`, candidate `5905a1194779b047ca3a2be7195dcbd639c8266a`, run `3a1a3dbda06d`, three failing assertions. Method addition and protocol extension separately remain green at `[2 1 1]`.

### 2. Handed-deriver coupling misses new and open callers

[Filed issue](../../../seon/issues/handed-deriver-coupling-misses-new-and-open-callers.md).

The loaded `seon.bootstrap/groups` reproduction is fixed. A newly supplied caller still leaves `seon.health/derived` compiled: its leaders were derived from the loaded program, before this caller existed. On the same committed database, a branch callback returns 2 and compiled main's callback returns 1. Expected `[branch main]` is `[2 1]` in either order; actual is **`[2 2]` branch-first and `[1 1]` main-first**. New keys separate the two orders.

Red: `seon.runtime-review4-astra-test/a-new-caller-hands-its-own-derivation`, same candidate and run as blocker 1. The callback is allowed by the installed contract. The branch's deriver is demonstrably identical to the compiled root. Its executing runtime, including newly authored callers, must determine the derivation's key.

The actual MCP path reproduces the same failure. An agent changes loaded `seon.review3-fixture/h` from 1 to 2 and defines contracted `seon.batch/review4-handed`, which calls `seon.health/derived` with `(fn [_] (seon.review3-fixture/h))`. Subsequent MCP calls and compiled calls on that branch database again return `[2 2]` and `[1 1]`, expected `[2 1]`. Setup returned both Vars without errors; its sole finding was the ordinary auto-required namespace notice. Candidate `99ea74a0b1a6997da19abbe21512281d180f2654`, run `abc4d259ee31`, red `seon.runtime-review4-astra-test/an-agent-new-caller-hands-its-own-derivation` (4,042 ms). Thus this is not an artifact of a non-settling test helper.

A loaded open caller also fails. Its callback invokes `((resolve 'seon.review3-fixture/h-dynamic))`; only `h-dynamic` changes from 1 to 2. Both orders again produce `[2 2]` / `[1 1]`, expected `[2 1]`. Candidate `1ad2412a1468f855b64bc0b71af875641c1cc043`, run `1544594d6d98`, red `seon.runtime-review4-astra-test/an-open-caller-hands-its-own-derivation`. Here the caller exists in the leaders, but the deriver's selection intersects leaders with **closed** reach; an open caller's dynamically resolved dependency is absent from that set. The fix's premise is therefore incomplete even for a loaded program. This counterexample belongs with the same handed-deriver decision issue, not a new cache workaround.

### 3. The live task path rejects a green proof through loaded callers

[Filed issue](../../../seon/issues/the-done-rule-excludes-loaded-callers-that-now-run-on-the-branch.md). The stronger chain probe changes loaded `seon.review3-fixture/h`, used by loaded `g` and `f`, from 1 to 2. The original proof asserts `(= 2 (seon.review3-fixture/f))`. The local provider changes only the helper. Expected: one model call, green proof, task done. Actual: **the proof passes, but the task makes three model calls and caps out**. The grade records zero platform and agent errors; the initial run took 1,518 ms for the task, with 54 ms form evaluation and 41.4 MB attributed form allocation.

Candidate `b62d872c01132158a7eabfa15da90f435de2a16f`, run `a73268583c96`: zero passing/one failing assertion, zero errors, 7,494 ms. Diagnostic candidate `c343c0356702593cc534bb6cf549b0d6108df785`, run `6fbb7a5e8e33`, captures the done refusal: `:seon.source/test-evidence-error`, offending `seon.review3-fixture/h`, “A replaced function reaches no green test of the named run.” The judged proof has one passing assertion, zero failures and zero errors, runs interpreted, and the changed helper is the only merge identity.

`seon.fn/source-callers` still excludes core declarations loaded from `src` when walking branch callers. Its premise predates the whole-runtime implementation. The new runtime interprets these callers, but `seon.fn/selection-walk` and `seon.cluster.source/uncovered-functions` still classify their proof as platform-only. The diagnostic selection is exactly `{:seon.fn/tests [], :seon.fn/platform-tests [review4.chain/proves-it]}`; all three loaded functions have `:seon.fn/host-bound? false`, and their stored call edges are present. This rules out missing analysis or a genuinely host-bound caller. Candidate `755a8fb0c21e32e6ee2df7fc8f602948dacae377`, run `d9b4717c5400`, repeats the failure and captures those facts. The same chain harness completes in one call when its functions were authored through the REPL, as measured below. This is a completion defect at the selection owner, not a bad answer or a reason to weaken the done rule.

A counterfactual candidate deletes only that obsolete `:core`/`src` exclusion from `seon.fn/source-callers`. The exact loaded-chain proof turns green: candidate `eefc1b538da250f5b1e7230283ded89aca9cfa2c`, run `0d2f83d83368`, one passing assertion, zero failures/errors, task done after one model call. This identifies the selector as the cause; it is a disposable experiment, not a production fix or a claim of broad validation. Two intermediate diagnostic candidates did not boot because the probe omitted its `seon.test` require; after that probe-only correction, the unchanged production candidate remained red and the counterfactual was green.

## Earlier blockers and review-3 probes replayed

Untouched HEAD's focused suite: run `567974e8d8bc`, 26 test vars, **82 pass, one fail, zero errors**, 14,822 ms. The sole failure is the explicitly accepted predicate gap. Historical probes were replayed from their saved source, with obsolete removed-report calls omitted and output paths redirected. Their wrapper tests often assert only that execution completed; the saved actual values below are the evidence.

Historical candidate `77f42972283194ef130a14c62412e8773377e7c6`, run `45a86dde1f7d`: 28 vars, 31 pass, one fail, zero errors, 15,074 ms. Two long historical vars were then explicitly selected in run `3a1a3dbda06d` and both passed. The historical red is the generic synthetic split-cache control, distinguished below from the repaired production owner.

| Earlier finding | Expected | Actual and conclusion |
|---|---|---|
| F1, value holding functions | Direct and `families` both call branch `missing-models` | Both return `[{:seon.config.ai/model "BRANCH"}]`; main nil. |
| F2, host-bound caller | Loaded behavior under the stated host-bound exception | Changed `words` returns `["BRANCH"]`; compiled `parsed` still cites `seon.db/q`. Fresh interpretation would not. Explicit exception unchanged. |
| F3, macro through database | Branch 22 cold/warm; main 3 | 22/22 and 3; installed MCP changed-macro proof green. |
| F3, non-settling `test-support/evaluate` | Distinguish this helper from the agent entrance | Direct macro 33, caller 3, compiled root retained. Existing helper limitation, unchanged; MCP settles correctly. |
| F4/F4b, defns/imports | Both requests return; imports preserved | Requests returned in 527/215 ms; both namespace import sets unchanged. |
| F5, failed production | No partial destination installation | Unresolved source throws; `[a z h]` remains `[1 1 2]`, snapshot equal. Explicit `h` change preceded production. |
| R1/F7, cached config | Branch 777, compiled 4, either order | `[777 4]` branch-first, `[4 777]` main-first in evaluation order. |
| R2, escaped cache derivations | Direct/closure/lazy/thread/future use branch entries | `[10 20 [30] 40 50]`; all five main-key entries nil. |
| R3, macro reads changed value | 3 buckets gives bucket 2 | Database path 2; MCP same and next requests `[3 2]`. |
| R4, unused host-bound report | Deleted path and consumers | Remains deleted; F2 is the stated exception, not an unconsumed refusal. |
| R5, launch defonces | Retain launch observations after disk edit | Source/dependency digests match JVM despite appended bytes in disposable archive. Strengthened installed proof green. |
| R6, compositions and proof strength | Correct overlays, continuation, fork-of-fork, reverse order | Installed generator/every-entrance/whole-delta rows green; mutation results below. |
| R7, inherited open value | Child's value holds child's helper | Installed open-value and two-step rows green. |
| R8, reload and adoption | Source reload, live evaluator, correct head and cold derivation | Five vars, 25 passing assertions, zero errors; run `18aa8d87b426`, 8,650 ms. |
| Review 3 Astra 1, projection cache | Branch `[]`, main original clauses, either order | Exact in both orders on the same held projection. |
| Review 3 Astra 2, method addition/protocol extension | `[2 1 1]` | Both acquired-fork probes return `[2 1 1]`; remaining mutators are blocker 1. |
| Review 3 Astra 3, production invocation cache | Branch writer and reader agree, cold and with main entry | Both cold entries identical; main-present case `[:branch :branch]`. |
| Review 3 Astra 3, synthetic split cache | `[2 2]` | Still `[2 nil]` cold and `[2 1]` after main writes. The fixture uses an arbitrary split owner without the repaired single-keyer design. This is a remaining generality/proof limit, not a claim that `seon.render/invocation` still fails. |
| Review 3 Astra 4, eager initializer | Branches/continuation/main `[[2 2 2] [3 3 3] [3 3 3] [1 1 1]]` | Exact. |
| Review 3 Astra 5, open macro | Direct helper, expansion, caller `[2 2 2]` | Exact in synthetic and loaded-fixture probes. |
| Review 3 Opus N1, loaded handed derivation | Branch 0 groups; main 8, either order | Exact; branch deriver now analysed. New caller is blocker 2. |
| Review 3 Opus N2, value census | Only open values re-evaluated on change | 412 value, eight macro, 4,501 function rows. Twelve values re-evaluated, then twelve on continuation; measured 448/65 ms. No new process-state copy identified. |
| Review 3 Opus N3, closure under another context | Defining context only when none is already bound | Own/raw-thread answers `"(BRANCH BRANCH)"`; main-bound closure/lazy value answers `"(a 1)"`. Accepted latent gap reproduced. |
| Review 3 record/protocol/multimethod invocation | Child/parent/main/repetition `[[3 3] [2 2] [1 1] [3 3] [1 1]]` | Exact; does not prove mutation isolation. |
| Review 3 host dispatch | No JVM global dispatch change | SCI refuses `print-method`; host protocol attempt fails; JVM printing/datafy unchanged. |
| Review 3 two macro branches | Bucket 2 with three buckets, 3 with five | Both correct, including repeated observation order. |
| Predicate gap | Branch's rejecting predicate would validate false | Actual true; accepted held task, not counted as a new blocker. |

## Mutation sensitivity

Fourteen independent private-index mutations were exercised. Every selected proof was green on unmutated HEAD; every mutant went red through an assertion, not a boot failure. The seven initial mutations cover the earlier regressions, four target the newest fixes, and the last three replay the remaining exact review-3 mutation shapes.

In this table, `runtime/` means `seon.sci.runtime-test/`, `eval/` means `seon.sci.eval-test/`, and `cost/` means `seon.cost-estimate-test/`.

| Mutation | Proofs | Candidate / run | Result |
|---|---|---|---|
| Do not install runtime cache entries | `runtime/a-composed-runtime-answers-what-its-own-source-answers`, `eval/a-runtime-derivation-is-keyed-by-its-own-program-through-every-entrance` | `c18ac53b4e26` / `8b6e10832cca` | 4 failing assertions, zero errors |
| Key by latest own changes, losing inherited delta | `runtime/a-derivation-is-keyed-by-its-runtimes-whole-delta` | `37d0e38b6c01` / `f70333e3e7b5` | 2 failing assertions, zero errors |
| Drop macro-reached changes from the analysis key | `eval/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers` | `d15dde27a877` / `22c79a255083` | 1 failing assertions, zero errors |
| Give launch defonces runtime value rows | `eval/a-changed-runtime-keeps-the-process-launch-observations` | `3b6234133369` / `d1b0a055f7d7` | 1 failing assertions, zero errors |
| Do not install earlier roots in the production fork | `runtime/a-composed-runtime-answers-what-its-own-source-answers`, `runtime/a-value-is-produced-after-its-whole-reach` | `26580b3380fb` / `ffb602419576` | 2 failing assertions, zero errors |
| Drop transitive initializer dependencies | `runtime/a-value-is-produced-after-its-whole-reach` | `cbd305f96806` / `047f3b40152a` | 1 failing assertions, zero errors |
| Do not select open macros | `runtime/an-open-macro-expands-with-its-runtime` | `d02407e6b8c2` / `acbec40b481e` | 1 failing assertions, zero errors |
| Remove handed-deriver coupling from the reach index | `eval/a-derivation-handed-to-a-deriver-is-keyed-by-its-runtime-in-either-order` | `88f25b90b31e` / `7d424746e92e` | 2 failing assertions, zero errors |
| Use a bare projection-cache key | `eval/a-projection-derivation-is-keyed-by-its-runtime-in-either-order` | `b66d6f7c1b4b` / `178833425d95` | 2 failing assertions, zero errors |
| Remove shared initial analysis binding | `cost/a-first-defn-inherits-the-shared-program-analyses` | `1d0b554e0dee` / `39bbc8a6877b` | 1 failing assertions, zero errors |
| Restore the split render invocation writer/reader | `eval/a-cache-kinds-writer-and-reader-key-it-alike-on-a-branch` | `89345f478308` / `1e6212f840e4` | 2 failing assertions, zero errors |
| Never select macro rows (review 3 mb) | `eval/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers`, `eval/an-agent-changed-value-a-macro-reads-is-expanded-by-its-callers`, `eval/a-branch-changed-macro-is-expanded-by-its-callers` | `57f7db8ceb23` / `f91d3caadcf4` | 4 failing assertions, zero errors |
| Evaluate an open value only once (review 3 md) | `runtime/a-composed-runtime-answers-what-its-own-source-answers`, `runtime/an-open-value-holds-the-functions-of-its-runtime`, `eval/a-branch-change-is-live-through-a-value-holding-functions` | `7eb03aafb276` / `e252fc891c9e` | 2 failing assertions, zero errors |
| Produce roots in name order (review 3 me) | `runtime/a-composed-runtime-answers-what-its-own-source-answers`, `eval/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers` | `f4e283030330` / `b1214ebbf622` | 2 failing assertions, zero errors |

The two review-3 survivors now fail: the macro-key mutation serves the first branch's expansion to the second; the defonce mutation fails the **no-runtime-row** assertion. SCI's bound `defonce` is still a no-op, so that latter proof guards exclusion from the planner, not a newly observed changed launch value. Removing shared initial binding causes **1,726** `seon.sci.runtime/analysed` calls on a fresh definition, where the installed proof requires zero. The keyer/coupling/projection mutants restore their documented wrong answers.

These proofs are useful and sensitive, not complete. The differential generator supplies its reach graph and does not vary value definitions; standalone cheap-fork tests exercise `seon.sci.runtime/bind`, rather than proving every cost of the production planner. The newly found mutator, retention and new/open-callback cases remain outside the installed green suite.


## Live task path and costs

The **newly authored functions** chain probe uses `seon.task/chain!` through `seon.competency/launch!`, an actual work agent and db<->flow, and a local fixture provider. Before launch, main defines contracted `h → 1`, contracted `caller → (h)`, and an unchanged red proof expecting `(caller)` to return 2. The one model reply changes only `h` to 2. It does not rewrite the proof. The task ends **done**, the original proof is green, one model call, zero wasted calls, zero agent errors and zero platform errors. Its two forms are namespace selection and the definition. The combined evidence run also repeats this positive control: candidate `eb31f9ed2f693beb8e5350b31dfb37902af69e05`, run `e85dc0956ef0`, seven vars/four passing and eight failing assertions, zero errors; its six red vars are the three handed-derivation variants, remaining method mutators, method-table retention and the loaded chain.

Candidate `ef78822c1ca49d3549afea87343cc7cf92bc0b08`, run `39419765a2e2`: the chain probe plus the three named done/merge-rule tests passed **29 assertions**, zero failures/errors, 7,504 ms overall. The rule tests were `seon.cluster.source-test/done-refusals-judges-every-task-alike`, `seon.cluster.source-test/done-and-merge-judge-one-member-list`, and `seon.cluster.source-test/done-and-merge-judge-a-red-reaching-test-the-same-way`.

The task grade records **778 ms**, 59 ms form evaluation and 36.3 MB attributed form allocation. Setup, launch and observation together cost **2,970.66 ms**, 464.74 MB on the measuring thread, 509,823 instrumented calls and 3,194 reads. The JVM-window allocation was about 2,100 MB, explicitly approximate and inclusive of unrelated work; it is not the task's retained heap or attributed allocation. The measured wait owner `seon.competency/await-ends` accounts for 2,079 ms; transaction calls account for 547 ms. Thus this ordinary helper-change task gets the right answer at sub-second task duration, while setup remains expensive. This proves the newly authored-function slice. The loaded-function chain above demonstrates that live-run completion is not generally correct; callbacks, method mutations and the accepted predicate gap impose additional limits. All fixture graphs and the chain branch were released by the production test helpers.

The same measurement body ran on `716cc7527` and reviewed HEAD, on separate disposable JVMs: candidates `2c3ca6136d1fc6dfba8c59bcddb2067033dbaa49` / `89d18508bd6455d6cf43f1f9af8394db933dddd2`, runs `c57b33d552c3` / `f31d9996095e`. Only the two new-only profiling tests were omitted from the copied test file on **both** revisions: their references to newer private runtime functions prevented the first baseline candidate from publishing. That failed setup ran no tests; it is not a measured baseline or a HEAD boot failure. The measured operations are unchanged. Allocation/call/read counts are the primary comparison; other lanes ran concurrently, so wall times are observations, not a controlled latency attribution.

Each cell is **716cc7527 → reviewed HEAD**:

| Operation | Allocated MB | Calls | Reads | Wall ms |
|---|---:|---:|---:|---:|
| `unchanged-request` | 18.4 → 20.9 | 6,572 → 7,292 | 46 → 46 | 21.5 → 24.3 |
| `fresh-agent-first-request` | 54.3 → 66.0 | 7,627 → 8,362 | 45 → 45 | 64.6 → 72.5 |
| `first-defn-on-a-branch` | 222.7 → 261.8 | 21,153 → 22,896 | 413 → 409 | 644.2 → 468.1 |
| `new-def` | 92.2 → 116.0 | 19,476 → 21,367 | 348 → 344 | 97.6 → 114.6 |
| `unmap-one-def` | 130.8 → 100.9 | 18,689 → 20,486 | 321 → 317 | 111.3 → 84.4 |
| `redefine-one-form` | 327.2 → 413.2 | 50,132 → 57,904 | 356 → 353 | 245.2 → 325.6 |
| `reuse-one-unchanged-test` | 28.9 → 33.1 | 7,961 → 8,828 | 58 → 58 | 29.8 → 30.4 |
| `current-state-with-task` | 215.9 → 222.7 | 38,148 → 40,842 | 209 → 219 | 116.2 → 134.7 |
| `seon-db-nine-forms` | 984.3 → 1,005.1 | 106,859 → 114,348 | 1,563 → 1,853 | 765.2 → 831.6 |
| `hub-docstring-change` | 256.4 → 266.8 | 58,852 → 60,579 | 379 → 382 | 211.0 → 235.0 |
| `hub-behavior-change` | 892.8 → 1,764.3 | 198,443 → 280,333 | 1,283 → 1,285 | 943.3 → 1,370.2 |
| `hub-change-sequence` | 3,773.0 → 4,905.9 | 640,101 → 763,168 | 5,815 → 6,098 | 3,808.1 → 4,386.1 |
| `cold-stale-merge-members` | 3,891.2 → 4,353.1 | 778,909 → 858,381 | 15 → 15 | 4,690.6 → 3,805.7 |
| `merge-one-function` | 124.1 → 130.6 | 19,812 → 21,761 | 190 → 190 | 185.5 → 200.3 |
| `similar-by-name-warm` | 27.1 → 30.8 | 7,984 → 8,999 | 60 → 61 | 31.2 → 38.0 |
| `unresolved-symbol-request` | 33.8 → 36.3 | 14,544 → 15,499 | 66 → 66 | 34.5 → 48.6 |

The fixed closed-runtime gate passes: its allocation cap is 5,053 MB. Two HEAD observations were 4,920.2 and 4,905.9 MB, leaving **2.63% and 2.91% headroom**. Against the parent measured in this review, the paired sequence allocation is **1.300258×**, calls **1.192×**, reads **1.049×**. This is only 0.975 MB above an exact 1.3× comparison; it is at the boundary, not evidence of a robust margin. The fixed-cap pass is clear, while a strict paired 1.3× allocation pass is not. The ordinary-estimate rows still carry their pre-existing accepted overruns.

The cold stale-member row selects 164 tests on the baseline and 167 on HEAD, so its aggregate includes a changed member population. Allocated MB follows the test table’s units; the cache capacity below is a decimal byte budget.

A separate test defines nothing, warms each form, then measures five unchanged-program requests. Medians:

| Form | MB baseline → HEAD | Calls baseline → HEAD | Reads baseline → HEAD | Wall ms baseline → HEAD |
|---|---:|---:|---:|---:|
| `nil` | 16.1 → 18.6 | 6,316 → 7,004 | 42 → 42 | 19.7 → 23.1 |
| `(seon.print/form-text '(a 1))` | 16.6 → 19.0 | 6,342 → 7,030 | 42 → 42 | 22.6 → 22.3 |
| `function-count query` | 17.4 → 19.8 | 6,377 → 7,070 | 43 → 43 | 18.3 → 24.0 |


**Ordinary unchanged work is still heavier than `716cc7527`:** about 10.9% more calls and 14–16% more allocated MB in these no-definition probes, with unchanged read counts. This is consistent with review 3, rather than a new regression isolated to the latest fixes. Wall-time medians alone cannot attribute the live default slowdown.

The independent five-request profile also replays the third review-3 cost probe: candidates `9705ab650cb3954b0b5aa71b33fd2765ec891ac5` / `989578b9cdbe2e669e377c5be99c36c3a06f4de6`, runs `b1986bd1b156` / `ce0fd25f27f9`. HEAD adds 505 `seon.db/loaded-builder`, 505 `seon.db/content-projection`, 500 `seon.cache/lookup`, 500 `seon.cache/join`, and 1,950 `seon.schema.internal/projection-cache-value` calls. Reads stay 365 across the five requests. This reproduces the existing [projection hit-path issue](../../../seon/issues/the-projection-builder-hit-path-adds-work-to-unchanged-requests.md); it is not filed again. The profile includes settling and observer calls and is distinct from each request's own cost handle. Its wall time includes waits and is not the basis for the regression claim.

### Sentinel on default, as this reviewer's own agents

The first form on `review4-astra` was `(seon.context/current-state)`. The sentinel used fresh agents `review4-astra-sentinel-defn` and `review4-astra-sentinel-warm`, namespace `sentinel.scratch`, the exact first-definition form `(defn sentinel-probe-inc [x] (inc x))`, and seven `(+ 1 2)` requests with the first two discarded. Request costs were read from their result handles outside the measured request.

| Probe | This review | Comparison to defn-perf |
|---|---|---|
| Fresh first definition | 605.1 ms; 20,307 calls; 202 reads; 226.3 MB summed attributed allocation; one transaction | Fixed result 590 ms, 22,014 calls, 266.8 MB. Large original first-definition regression stays removed. |
| Warm, five samples | 76.1, 54.0, 66.5, 63.9, 66.5 ms; median 66.5 ms | Prior documented median 21.7 ms; this live-store observation is slower, not a controlled causal comparison. |
| Warm work counts | 7,025 calls, 39 reads each; roughly 26–28 MB summed allocation; median settle 44.8 ms | Prior 6,913 calls/42 reads. Counts are near the fixed version; wall time includes current live-store/concurrent work. |

The first-definition cost handle was `result/ce5b749277272`. Its stages were evaluate 97.3, custody 139.3, derive 50.9, settle 114.7 and test-step 144.1 ms. The form's 34.6 MB is not the whole request's allocation. All three owned agents were asked to retire through their own SCI contexts; each acknowledged `:my.agent/retiring? true`. Their unmerged/history state may retain the branches; no foreign resource was stopped or deleted.

## New retention finding

[Filed: superseded method tables](../../../seon/issues/forked-method-updates-retain-superseded-method-tables.md). Repeatedly fork an acquired context and replace its one method, keeping only the newest context. After 200 updates, the current method count is one and answers 200; parent still answers 0. However, the current environment's `:sci/multi-fns` ownership map holds **200 MultiFns**, not one. SCI's fork renews generations while retaining the map; each copied write adds another strong key. Thus the 199 superseded tables and their functions cannot be collected while this context lives.

Candidate `b76ec541697bb08cfb7ff0638f096589e4f6713c`, run `2b4997cbe95c`: the three ordinary semantic assertions pass and the retained-table bound fails. This is a newly confirmed retention risk outside the 512 MB cache budget. It does not establish retained bytes or an observed OOM; I rank it below the wrong-answer/cross-branch-mutation blockers. The same standalone run repeats both correctness failures (three vars, three passing/five failing assertions, zero errors, 5,655 ms).

## Budget pressure, boot and accepted limitations

The ordered cost/schema/config/pressure run (`45da211e3d43`) executed 94 vars, **11,699 passing and 21 failing assertions, zero errors**, 51,777 ms. The pressure probe itself passed: **5,743 analyses for 5,743 distinct rewritten digests**, plus the 1,000 original definitions, with correct answers `[0 0 1 2]`. Analysis evictions rose **0 → 871**, misses **1,784 → 8,527**; cache weight **508,904,471 → 508,285,992 bytes** under the **512,000,000-byte** cap. This sample did not repeat a scenario digest, so it does not reproduce review 3's duplicate count or establish that the documented eviction issue is fixed. It does prove eviction under the real budget with correct answers. The whole-program cache's cost remains a residency-dependent limit, not an infinite once-per-content guarantee.

The five red vars are the same already reported in review 3: `seon.config-test/the-default-document-has-one-canonical-complete-location`, `seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms`, `seon.schema-test/canonical-reference-values-use-the-pull-collection-grammar`, `seon.schema-test/every-predicate-schema-declares-what-it-accepts`, and `seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector`. Existing tasks are `seon.schema/reds-on-head-1988560d1-in-schema-and-config-tests` and `seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests`. The long incremental/full projection test was excluded, as in the earlier pressure run; no claim of a green schema/config suite is made. An initial namespace-only pressure invocation also excluded the long pressure var; the ordered run explicitly named it and executed it.

The [unbounded projection holder](../../../seon/issues/projection-query-derivations-grow-outside-the-cache-budget.md) remains: two batches of 1,000 query clauses grow the real `clojure.core.cache.BasicCache` **764 → 1,764 → 2,764 entries**, while central cache weight stays **185,623,961 bytes**. The batches take **17.74/15.19 ms**, about **10.3 MB allocation each**. Retained bytes were not measured, and no OOM was induced. This is an existing risk outside the central budget, not a new root cause filed twice.

**Bootability has positive evidence.** Besides the five reload/adoption tests, untouched reviewed HEAD booted a clone of default's actual live store and passed the composition and first-definition sharing proofs: run `e156ad02a218`, two vars/four passing assertions, 4,465 ms. The clone copied **68 pointers**, spent **21,812 ms cloning**, reached readiness in **16,619 ms**, and the complete check took **65,655 ms**. Default retained PID 6516 and its original start instant, including the final handoff status check; its baseline health remains faulted. No reviewed production candidate hung, crashed or OOMed in these finite experiments. This operational evidence does not erase the correctness counterexamples.

The predicate gap remains acceptable for this restart decision because the owner explicitly accepted it as a held task. It still prevents claiming full branch-local contract validation; post-merge checks remain necessary. N3 and the approximately 65 ms cross-lineage cold adoption derivation are likewise explicit accepted limitations. Neither acceptance extends to the three reproduced blockers above.

## Evidence, verification and handoff

[Probe patch](runtime-review4-astra-probes.patch) preserves the new regression probes and chain experiment. The old replay sources remain reproducible from the prior reviews' evidence patch and the candidate identities here. Raw data and check logs are retained under this lane's scratch directory; all decisive values are quoted in this report. The exact final patch is candidate `3f2d35ed285dcf8febc4ec626aaa51fa523afd1b`, run `1267235f3104`: seven vars, four passing/eight failing assertions, zero errors, 26,845 ms. It repeats the same six red vars and the passing newly authored-chain control. Its only difference from the previous combined candidate is clarification of the two fixture docstrings.

Reproduction: create a private index from reviewed `342e14357`, apply the evidence patch to that index with `git apply --cached --unidiff-zero`, write its tree and a dangling commit, then run `bin/seon check --at <candidate>` with the named proof. The patch adds review-only fixture/test namespaces; it is not installed source. `tmp/runtime-review4-astra/out/` must exist for its measurements. `R4_LABEL=base` or `head` selects the cost output names. Both chain variants are preserved under separate test namespaces. No default restart or reload is part of reproduction.

Final `clj-kondo --lint src script test --cache false` completed in **24,409 ms**, **zero errors**, 1,869 existing warnings. An earlier run also had zero errors (9,427 ms). This is not a zero-warning claim. The docs-only commit requests eight individually named positive baseline proofs: the composed-runtime and whole-delta tests in `seon.sci.runtime-test`; the every-entrance, handed-deriver, projection-deriver, writer/reader and acquired-method tests in `seon.sci.eval-test`; and `seon.cost-estimate-test/a-first-defn-inherits-the-shared-program-analyses`. Their commit check's result and run identity are recorded by `bin/lane` in the commit evidence. The red review probes are carried as evidence, not accepted as a green implementation.

Foreign boundary: the five schema/config red vars named above and the accepted predicate red are pre-existing. Dirty `reference-code/http-kit`, `reference-code/konserve`, the two deleted historical evidence patches, `tmp/test-data-assertions/proofs.edn`, and unrelated untracked files were not edited, staged, restored or used as candidate source. No shared source/test file was changed by this review. The final source/resources/test/dependency/operator comparison to the reviewed revision was empty; intervening documentation commits do not change this judgement. Owned check JVMs terminate through `bin/seon check`; raw evidence is retained, and owned live agents' retirement requests were acknowledged.
