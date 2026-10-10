---
title: Interpreted test cost — astra-test-run-cost
date: 2026-10-10
status: ownership-boundary handoff
---

# Interpreted test cost — astra-test-run-cost, 2026-10-10

The runner constructs a clean request world, acquires a member branch, resolves its interpreted namespace, runs the body and records its result. The first fix belongs at program acquisition and namespace installation: reuse proven program derivations through SCI forks and the existing central cache.

## Outcome and ownership boundary

**Not fixed. No after-fix numbers.** The assignment explicitly stops this lane at a held owner. At diagnosis, `src/seon/sci/eval.clj` was claimed and dirty under interp-fidelity; the assignment also reserved `src/seon/test.clj` to it. `test/seon/test_support.clj` and `src/my/test.clj` belong to assign-proofs. No production or test file was edited. The exact owner changes and proposed tagged proof below are a handoff, not a tested implementation.

While this report was prepared, interp-fidelity landed `a582e40e3`, released its ledger claims, and root bumped SCI in `9a23b8ff2` and restarted default. That release is recorded here rather than describing the file as still ledger-held. This lane did not take over the separately assigned files or message another lane.

The original JVM was PID 77124, started 11:20:49.939 UTC, archive `340462543b03a2b67e7f1e246baa67a2cbc986c1`. The final JVM was PID 91746, started 11:56:33.116 UTC, archive `9a23b8ff23b7df1b4c789a6bbd628cc8209bfc2e`. The observations below are not a controlled before/after comparison across that restart.

Existing task: `seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte`. Merge costs also belong to `seon.cluster.source/accepting-a-one-function-merge-allocates-hundreds-of-megabytes`.

## Measurements

All probes used MCP SCI mode, cluster default, agent astra-test-run-cost. No JVM source reload, shared-source mutation, merge acceptance, worktree, or provider run. Temporary target/candidate/member branches were released in finally blocks. Allocation is decimal MB on the calling thread; worker-thread allocations are separate. `seon.profile/measure` call counts, GC and ranked self times are JVM-window counters and can include concurrent work. Its self times must not be summed as exclusive elapsed across threads.

| Operation on original JVM | ms | calling-thread MB | armed calls | reads |
|---|---:|---:|---:|---:|
| Exact initial my.test/check | 1,043.08 | 527.079 | 250,746 | 843 |
| Same check, cached green result; no execution | 18.28 | 17.148 | 1,992 | 34 |
| Forced interpreted seon.test/run, one body | 576.97 | 472.318 | 96,924 | 742 |
| One-function prepare-merge!, one proof | 1,405.99 | 1,444.946 | 254,300 | 2,227 |

Exact initial form:

```clojure
(seon.profile/measure
 (fn []
   (my.test/check
    {:seon.test/changed ['seon.id-test/a-host-object-has-no-identity]})))
```

Initial result handle `result/e81a359edefa4`, recorded test run `9383a39b3331`: executed 1, reused 0, pass 1, fail/error 0. Member acquisition 181.319 ms, resolution 242.199 ms, run stage 26.194 ms, release 1.025 ms. Body's own measurement: 24.585 ms, 6.514 MB, 60 calls. Thus even calling-thread overhead alone is about 81 times body allocation.

Forced result `result/e8518d0bfd1ad`, run `e290ef04f515`: executed 1, reused 0, green. Acquisition 88.016 ms, resolution 109.487 ms, run stage 14.194 ms. Body: 13.185 ms, 6.513 MB, 60 calls. `my.test/check` in this archive does not forward `:seon.test/rerun?`; forced measurements therefore call its public owner `seon.test/run` explicitly. A repeated green-result lookup is not evidence of a faster execution.

One-function merge: disposable target and candidate; synthetic contracted callee changes from `[] 1` to `[] (inc 0)`; one proof asserts its value. `seon.cluster.source/prepare-merge!` returned green with exactly that function in expected digests, run `7df32695cd98`, executed 1/reused 0. Nested test run reported 1,139 ms. The probe retired the returned merge branch and released candidate and target. Setup and cleanup are outside the 1,405.99 ms measurement.

Merge hot work: 9,576 host-Var copies; 279 installed/read source rows; 1,776 pull-logical calls (237 ms self); 354 vector queries (68 ms); 5 transact-call calls (491 ms); 5 declaration-content-key calls (104 ms). The full setup + measurement form was 1,886 ms and 1,858 MB; these are not the prepare-only numbers.

Raw reproducible forms and envelopes: [astra-test-run-cost-probes.json](astra-test-run-cost-probes.json).

## Attribution and falsified hypotheses

The forced test measured 4,633 calls to `seon.sci.eval/copy-host-var`, 87 to `seon.sci.kernel/mark-installed!`, and zero calls to `seon.sci.eval/derive-base-ctx` or `seon.sci.eval/acquire-program!`. The whole compiled base is already cached. Adding another whole-base cache alone does not address this reproduction.

Separate stage measurements, executed synchronously for allocation attribution, returned a valid SCI test Var:

| Stage | ms | MB | calls | reads | host copies | installed rows |
|---|---:|---:|---:|---:|---:|---:|
| clean-request-handle | 361.56 | 212.599 | 18,876 | 71 | 1,441 | 1 |
| acquire-context! for its member | 101.61 | 243.106 | 8,893 | 57 | 1,441 | 1 |
| interpreted resolve-test | 148.79 | 257.128 | 53,802 | 537 | 1,751 | 90 |

A repeat to obtain self-time attribution: request 329.60 ms/149.799 MB, member 164.78 ms/246.999 MB, resolution 171.06 ms/255.504 MB. Counts stayed 1,441 + 1,441 + 1,751 host copies and 1 + 1 + 90 installed rows. Stage allocation totals differ from normal `run` because normal resolution/body run on a worker and `measure` only counts its calling thread.

Resolution performed 483 pull-logical calls (51 ms self), 54 vector queries (14 ms), 90 reader/install-row calls, and 51 interpreted wrapper installations. `enter-namespace!` invokes `interpret-source-namespaces!`, which selects every function and test row of the source namespace closure. The selected test requires `seon.test-support`; this loads the support namespace even though this particular test never invokes its fixture.

**with-database was called zero times.** The test body is only an identity refusal assertion. Ordinary `with-database` already delegates to `with-branched-database`, which acquires a branch of the captured commit; only its explicit fresh-store option creates a store. Do not replace this with another fixture database cache. Its remaining expense is the same acquisition owner.

Request-world comparison found an inherited program difference from the loaded publication: `my.program/match-row`, plus namespaces user and my.agents.root. This lane did not author that difference. `clean-request-handle` unconditionally starts from `base-ctx candidate`, reapplying the same override; the member then repeats one row's interpretation and 1,441 host copies. The profile supports repeated overlay work, not a claim that all 4,633 copies are the entire compiled program.

The forced run made four transact-call calls, 269 ms self over their window: admission, request overlay, member fork metadata, completion. A supported injected writer measured admission separately at 43.86 ms, 0.096 MB calling-thread allocation, 5,045 calls, 26 reads. That hook was invoked only once: `commit-results!` calls the writer directly, so this is **not** a measurement of completion. Completion-only allocation remains unmeasured. Source shows `commit-results!` derives reach digests again after the request already derived them; whether they hit content-valid cached work needs its own isolated probe.

## Patch handoff, in owner order

1. **seon.test/clean-request-handle:** replace the unconditional `source (sci.eval/base-ctx candidate)` with reuse of the execution's acquired **program-only** base, then retain the existing `fork-cluster-ctx` call to install the receiving connection, projection and environment. The input is the held `:seon.sci.eval/base-ctx` or `:my.program/base-ctx`, not the agent's live private context. Require positive program-current evidence against candidate; use the acquisition owner for a stale base. Delete the stale comment claiming inherited SCI callers cannot observe a fork's redefinition once the existing isolation regression proves that on the installed fork. Keep clean operational facts and resource release unchanged. This removes one repeated overlay/namespace installation; it does not by itself prove the target budget.

2. **seon.sci.eval/base-ctx, fork-cluster-ctx and acquired-database?:** make the already-derived overlay reusable by its actual inputs, in `seon.cache`, instead of reconstructing it from the loaded base at each member. Key by loaded program identity, candidate declaration content, projection, and arm dials. Keep the cached value program-only; a fork receives its own kernel holders, connection, environment and error recorder through the existing fork owner. Preserve changed-callee behavior, deletion, host-bound declarations and mutable private-state separation. The observed member repeat is the next cost regression to eliminate. Do not simply remove connection/generation from revision comparison: the dependency explicitly rejects equal empty revision maps from unrelated connections. Any replacement identity must use proven content or the dependency's commit/attribute revision guarantee.

3. **seon.sci.eval/interpret-source-namespaces!:** eliminate repeated source retrieval/analysis of the same namespace closure on every test member. Read the required rows once by namespace and declared selectors, retain immutable derivations by namespace/definition content, and fork an eligible prepared program context before running a body. A namespace has macros, top-level initialization and mutable defs: caching a fully initialized test namespace and merely SCI-forking it would share contained atoms/objects. Such a cache is not correct without separating reusable code from per-context initialization. Keep source-order semantics from interp-fidelity's landing. This is a design boundary; the options below price it.

4. **seon.test.runner/commit-results!:** separately measure completion and its repeated reach derivations before changing it. Retain admission and actual-exit recording; “one result transaction” is not permission to remove concurrency reservations or termination evidence. Reuse already-proven reach data only with writer-side basis checks. No code change prescribed for this unisolated part yet.

Dependency seams read: SCI `sci.core/fork` at pinned `c05455ff6c69d4be2c72b20d5cecc930c269dac7` shares immutable environment structure and isolates Var bindings; it does not deep-copy arbitrary root objects. Datahike `datahike.db/advance-cache-context` and `datahike.query/source-context-unchanged?` at `cb3bce514507ecc96d36b6957ad10f6e892f51cf` distinguish attribute revision content from same-connection reuse. `seon.cache/lookup` already provides weighed, bounded, single-derivation reuse; no new registry or cache atom is needed.

## Design options for namespace reuse

Estimates are engineering effort, not measured runtime guarantees.

| Option | Estimated work | Guarantee / sacrifice |
|---|---|---|
| Reuse acquired program base and batch/cache immutable namespace reads first **(recommended)** | Two bounded 15-minute slices plus reaching proofs | Preserves per-context initialization. Removes measured repeated overlay work; may not reach 100 MB alone. |
| Separate analyzed code from per-context initialization in the SCI owner | 1–2 hours design/probe, then small implementation slices | Reuses analysis while keeping atoms and effects per context; requires dependency seam validation and fidelity tests. |
| Cache only proven pure prepared namespace contexts | 45–90 minutes plus classification/isolation proofs | Can cheaply fork eligible contexts; adds eligibility policy and leaves impure namespaces at existing cost. |

Do not silently choose a shared initialized test context just to meet the performance number.

## Red proof and completion limit

The task's allocation requirement is <100,000,000 bytes, not a wall-clock assertion. The measured body uses about 6.5 MB; this gives over 15 times that observed body allowance for fork/selection/recording while rejecting the 472 MB warm execution. The work law is independent of total program size; a stronger follow-up should compare acquisition work under unrelated program growth.

The post-restart same-evaluation measured predicate returned:
`{:ran-one? true :green-body? true :within-allocation? false :max-bytes 100000000}`
at 2,238.76 ms, 1,694.699 MB, 150,241 calls, 1,443 reads. It is a red cost observation, **not a registered or committed deftest**, and not an after-fix result.

Proposed owner proof, quoted task metadata, with imports into the owner's existing test namespace:

```clojure
(deftest ^{:seon/tasks #{'seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte}}
  one-interpreted-test-has-bounded-allocation
  (let [execution (assoc (support/execution-handle nil)
                         :seon.agent/id "astra-test-run-cost")
        measured
        (profile/measure
         #(sut/run
           {:seon.test/execution execution
            :seon.test/recording-connection (:seon.db/connection execution)
            :seon.test/policy :named
            :seon.test/rerun? true
            :seon.test/changed ['seon.id-test/a-host-object-has-no-identity]}))
        result (:seon.profile/value measured)]
    (is (= 1 (:seon.test/executed-count result)))
    (is (true? (:seon.test/passed? result)))
    (is (< (:seon.profile/alloc-bytes measured) 100000000))))
```

This is proposed code, not a claim that its fixture or archive execution was proven. It needs red-before/green-after execution through the owner gate, plus a worker-allocation or work-count assertion so resolution work cannot simply move off the measured thread.

Relevant existing correctness proofs include
`seon.test.overlay-world-test/a-request-world-reuses-the-executions-acquired-program`,
`seon.test.overlay-world-test/an-agent-branch-request-runs-its-candidate-on-a-clean-world`,
`seon.sci.context-reads-test/a-branch-redefinition-of-a-loaded-test-runs-under-its-own-bound`,
and the new `seon.sci.eval-test/a-test-namespace-def-reads-the-def-above-it-in-the-file`.
They were not run by this documentation-only lane. Root's interp-fidelity landing lists pre-existing reds in acquisition/branch-execution/resolution; this report does not reinterpret those as passes.

## Health, verification and cleanup

Initial runtime: faulted, 7 open faults, 34 errored receipts; tools present and usable. This lane's malformed exploratory resolution call omitted class-loader and was refused; the corrected probe ran. Its error was attributed to compiled `seon.profile/measure` and copied as a core fault: the existing task `seon.error.refusal/an-interpreted-callback-is-blamed-on-the-compiled-function-calling-it` covers that class. A typo using seon.store instead of seon.cluster.store failed before execution. Both failures and corrections are retained in raw evidence.

An attempted read of pre-restart result handles failed after the external restart lost live values; the cost predicate was rerun within one evaluation. Do not depend on those handles to reproduce this report; raw forms and envelopes are retained locally.

Full `clj-kondo --lint src script test --cache false`: 7,587 ms, **0 errors, 1,479 warnings**, exit 2. Warnings are not a clean zero-finding pass. No production/test diff; net source/test lines 0. No functional proof or installed improvement is claimed. Evidence-only commit goes through `bin/lane commit`; docs are not program inputs, so no throwaway boot is required. The final lane call requested retirement of its own branch `:agent-9996d823c28d` and returned `:my.agent/retiring? true` in 38 ms / 2.6 MB; deletion is deferred until that run exits and was not independently queried. No other lane's files or sessions were changed.
