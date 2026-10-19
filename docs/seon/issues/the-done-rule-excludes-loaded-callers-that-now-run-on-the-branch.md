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
