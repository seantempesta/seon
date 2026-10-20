---
type: issue
status: open
severity: blocker
created: 2026-10-20
tags: [issue, test-selection, task-chain, branch-runtime, review4-astra]
---

# The done rule excludes loaded callers that now run on the branch

On `342e14357`, a real work agent changes a loaded helper, its unchanged task proof observes the correct new answer and passes, but the done rule refuses the change as uncovered. The agent repeats the correct definition until its call cap. The runtime and the test selector disagree about which code a branch executes.

The loaded fixture is `seon.review3-fixture/f → seon.review3-fixture/g → seon.review3-fixture/h`, with `h` returning 1. The unchanged proof `review4.chain/proves-it` asserts `(= 2 (seon.review3-fixture/f))`. Launch an ordinary one-task chain through `seon.task/chain!` and `seon.competency/launch!`, using a local fixture provider whose only answer is:

```clojure
(in-ns 'seon.review3-fixture)
(defn h {:malli/schema [:=> [:cat] :int]} [] 2)
```

Expected: one model call, proof green, task done. Actual: proof green, three model calls, task capped. No platform or agent errors are recorded. `seon.cluster.source/done-refusals` returns `:seon.source/test-evidence-error`, offending `seon.review3-fixture/h`, with message “A replaced function reaches no green test of the named run.” The judged run contains the original proof with one passing assertion, zero failures and zero errors; the only merge identity is the changed helper.

Candidate `b62d872c01132158a7eabfa15da90f435de2a16f`, run `a73268583c96`, proves the failure. Diagnostic candidate `c343c0356702593cc534bb6cf549b0d6108df785`, run `6fbb7a5e8e33`, captures the refusal and the green interpreted proof. An otherwise identical chain over newly authored functions completes in one call, so the provider and chain harness can finish the task.

The cause is `seon.fn/source-callers`: its `:branch` query excludes core declarations in `src` files, on the obsolete premise that every loaded source caller runs compiled on every branch. `seon.fn/selection-walk` therefore classifies the transitive proof as platform-only. `seon.cluster.source/contribution` and `seon.cluster.source/uncovered-functions` use the branch tests to establish coverage. The new runtime does interpret these callers and produces the correct answer, but that evidence cannot satisfy the old selection rule.

Selection evidence: candidate `755a8fb0c21e32e6ee2df7fc8f602948dacae377`, run `d9b4717c5400`, returns `{:seon.fn/tests [], :seon.fn/platform-tests [review4.chain/proves-it]}`. Each of `h`, `g` and `f` has `:seon.fn/host-bound? false`; the stored calls form the complete `proof → f → g → h` path. No missing call edge explains the refusal.

A counterfactual candidate deletes only that obsolete `:core`/`src` exclusion from `seon.fn/source-callers`. The exact loaded-chain proof turns green: candidate `eefc1b538da250f5b1e7230283ded89aca9cfa2c`, run `0d2f83d83368`, one passing assertion, zero failures/errors, task done after one model call. This identifies the selector as the cause; it is a disposable experiment, not a production fix or a claim of broad validation. Two intermediate diagnostic candidates did not boot because the probe omitted its `seon.test` require; after that probe-only correction, the unchanged production candidate remained red and the counterfactual was green.

This is a live-run completion defect, not a wrong numeric result or evidence that the done rule accepts red work. The correction belongs to the selection owner and its interpretation of the actual runtime, not an exemption from coverage. The review made no production change.

[Independent review](../../research/agent-platform/2026-10-20/runtime-review4-astra.md) and [reproducible probe patch](../../research/agent-platform/2026-10-20/runtime-review4-astra-probes.patch). The red proof is `seon.runtime-review4-chain-test/a-chain-fixes-its-helper-and-proves-its-unchanged-caller`.

## Fix attempt, lane done-callers (2026-10-20): correct, but a hub change then runs ~167 tests per eval request

[Patch](../../research/agent-platform/2026-10-20/done-callers.patch), not committed. The selection walk
(`seon.fn/selection-walk`, via `seon.fn/nearest-tests`) goes on through every caller that is not host-bound;
`::platform-tests` and its consumers (`seon.cluster.source/contribution`, `seon.cluster.source/merge-members`,
`my.test/check-platform`) are deleted; `:seon.source/deferred` keeps only the host-isolated tests it held before
fd373b0c1. `seon.fn/reverse-closure` keeps excluding loaded `src` callers: it feeds
`seon.sci.eval/interpret-overlay!`, whose lazy roots would override the analysed bindings
`seon.sci.eval/bind-analysed!` gives those callers (the 2,345-caller re-rooting fd373b0c1 removed). The regression is
`seon.competency-grade-test/a-chain-fixes-a-loaded-helper-and-proves-its-unchanged-caller` over the loaded fixture
`seon.fixture.loaded-chain` (`f` -> `g` -> `h`).

Proof, check JVMs: the regression is red on HEAD 0698330fb plus the test alone (candidate aa254fc31, run
3dbeda0e526a: `:capped`, 3 model calls) and green with the patch (candidate run 6794c03b900c, 7 vars, 35 assertions:
the regression, `a-launch-grades-each-chain-run-from-its-branch`, `seon.cluster.source-test/done-refusals-judges-every-task-alike`,
`done-and-merge-judge-one-member-list`, `done-and-merge-judge-a-red-reaching-test-the-same-way`, the converted hub
rows); nine `seon.fn-test` selection rows green (run 121878aacbed).

Cost, why it was not committed. Selection for `seon.db/as-of-key`: before 1 branch test + 166 deferred to the merge
(105 ms, 13.3 MB); after 167 branch tests, 0 deferred (17.8 ms, 8.8 MB). The step now runs those 167 tests after each
eval request that changes the hub. Measured once each in a check JVM through `seon.cost-estimate-test`'s `measured`,
HEAD -> patch: `:hub-behavior-change` 1,775 -> 1,519 MB, 279,156 -> 229,363 calls, 1,285 -> 1,023 reads, **3,197 ->
11,615 ms**; `:hub-change-sequence` 4,958 -> 4,574 MB, 9,783 -> 14,730 ms. The attributed allocation and calls fall while wall time grows 3.6x,
consistent with the added reaching run being outside the request's attribution (not yet profiled by function).
`seon.cost-estimate-test/closed-runtime-rows-grow-past-no-accepted-overrun` is green on HEAD 8066f1239 in 30.6 s
(run 702c319fff5b) and does not exit within its 120 s bound on the patch (run 9b7d55662823).

Options for the owner: (a) accept the in-step cost of a hub change (~167 reaching tests, about 11.6 s) and re-baseline
the gate and the `:hub-behavior-change` row; (b) the done rule needs one green reaching test per replaced function
(`seon.cluster.source/uncovered-functions`), so the step could run the nearest tests and leave the rest to the merge
as now, while counting a green test observed through loaded callers as coverage; (c) make the step's reaching run
of a hub change cheaper at its producer (reuse by reach digest across the sequence) before widening it.
