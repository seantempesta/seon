---
title: Check allocation owners and admission revision reuse
type: research
status: measured
created: 2026-10-08
---

# Check allocation: owner evidence and first slice

Seon's test admission already carries the tested program's attribute-revision digest, but discards Datahike's valid speculative revisions at the writer and scans program history instead. Reuse the existing revision comparison at admission, including the writer's in-transaction value; no new cache, counter, evaluator, or instrumentation policy.

## Slice: admission follows the attributes it reads

The smallest composition is Datahike's `datahike.db/speculative-cache-context` through the existing `seon.test.runner/program-revisions` and `seon.test/admit-run`. The fork preserves the connection/generation/conservative identity and untouched attribute revisions; earlier effective program writes receive fresh revisions. Compare only a present tested digest. Unknown/mismatched revisions retain the authoritative history path. No claim that equal missing witnesses prove anything.

Cost changes from scanning the histories of every program attribute to selecting and hashing their held revision entries: O(program attribute count), independent of datom/history population. The rejected alternative is caching the history query, which retains that scan on every basis change. Instrumentation remains fully enabled. The current `unchanged-through-t` input remains compatible; a basis comparison alone is not the new proof.

The existing proof `seon.test.admission-digest-test/an-admission-confirms-the-tested-digest-without-deriving-the-program` is converted from a global observation atom and stale test identity to the real admitted writer entrance. It covers empty/result-only prior writes with zero history calls, and prior program writes with present/missing revision evidence refusing admission. Additional existing admission/replay and selection proofs will run by Var through the candidate gate.

Initial health: default PID 61758, start 2026-10-08T11:01:39.584Z, source archive `6cc5dbbb406d27850820525fd727aca278d8a9fe`; faulted with memory-pressure refusals, live heap about 0.91 GB, central cache weight 507/512 MB. No restart, reset or instrumentation change. First SCI form was `(seon.context/current-state)` as agent `check-alloc-astra`. Its automatic step named existing reds `bt.slug-test/slug-lowers` and `live.text-test/slug-joins-lower-case-words`.

Default's initialized JFR stack depth stayed 64 after `JFR.configure stackdepth=256`; a throwaway `bin/seon check` used `JAVA_TOOL_OPTIONS=-XX:FlightRecorderOptions=stackdepth=256` with profile recording and allocation throttle off. Initial default throttled owner weights are too coarse (one sample weighed 672 MB) for a trustworthy small-owner ranking. Dense samples and the throwaway window are the ranking evidence. JFR weights are statistical allocation estimates, not retained heap or exact per-owner counters. The complete MCP forms/envelopes are in [measurements.json](measurements.json); the streaming aggregator is [AllocationOwners.java](AllocationOwners.java).

## Ranked allocation and expected work

All GB below are decimal weighted allocation samples, not retained memory. The enclosing test window includes worker threads and check bookkeeping, not JVM boot. Windows are bracketed by samples whose stacks include `seon.test/run`; some truncated stacks cannot be assigned. `CALLER` skips the database/cache/instrumentation plumbing to name its first visible caller. These disjoint buckets differ from inclusive stacks, which must not be added.

| Rank | Owner | First two-test window GB | Candidate GB | Expected work and next boundary |
|---|---|---:|---:|---|
| 1 | `seon.test.runner/program-written-since?` | 1.089 | no samples | A held revision comparison for an unchanged program, O(number of program attributes); history only when revision evidence cannot decide. This slice removes the history call from the matching speculative case. |
| 2 | `seon.test.runner/reach-attribute` | 0.778 | 0.773 | O(requested dependency closure) for a cold small check; unchanged acquisition reuses content witnesses. Today an attribute miss reads its entire range, including all callers. Demand acquisition or a proven branch-lineage witness needs an owner decision. |
| 3 | `seon.cluster.instruction/toolkit-namespaces` | 0.325 | 0.059 | Read/reuse the selected toolkit identities, not the complete population on each acquisition. Variability makes the first sample an opportunity ceiling, not guaranteed savings. |
| 4 | `seon.render.walk/acquire-entity` | 0.141 | 0.072 | Read the shown entity and requested fields; rendering and context costs must be measured separately from member execution. |
| 5 | `seon.test/digest-differences` | 0.107 | 0.106 | Read changed declaration identities between held values, O(changes), instead of both complete digest populations. |
| 6 | `seon.reconcile/current-identity-facts` | 0.078 | 0.076 | Work follows the proposed identities and actual writes. |
| 7 | `seon.reconcile/first-assertion-transactions` | 0.076 | 0.078 | Work follows the actual proposed assertions, not another complete world construction. |

The lower-level nearest frames show `seon.db/datoms->data` at **0.737 GB** in the first window and **0.732 GB** in the candidate, substantially inside reach acquisition. `seon.db/declaration-content-key` remains **0.154–0.155 GB**; it overlaps logical caller buckets and is not another additive row. First-window unattributed allocation is **0.357 GB**; candidate **0.297 GB**. No attribution is inferred from missing frames.

The expected small-check budget is the assignment's tens of MB and sub-second runtime: cheap branch acquisition, requested members, one recording transaction. The measured remaining 2 GB is not justified by calling it cold. This note connects that finding to existing W-27/W-28, EN-7, W-25 and LR-BRANCHREACH; it does not open a duplicate issue index.

## Samples, controls and correction

| Sample / source archive | Allocation GB | Test time ms | Evidence |
|---|---:|---:|---|
| Two executed ID tests, parent `3cde7ceaf142792de231aa29ed2bafe452ad6b85` | 3.710 | 1915 | [first deep window](deep-window-owners.txt), run `2edf3acf936b`, 35 assertions green |
| Same two tests, candidate `dc62f3b65029995ecebdab7ccfb4505cc5728d8b`, parent `c497d16ddc4a4e0ba8dafc1dae5d38657399908a` | 2.137 | 1435 | [candidate deep window](deep-after-window-owners.txt), run `23046564792a`, 35 assertions green |
| Fresh unmodified HEAD `2331d3ce04002e2775bfb3e39c716d5f779d4b7c` | 2.243 | 1193 | [later parent window](deep-parent-window-owners.txt), run `97605090c09d`, 35 assertions green |

**Correction to the preliminary comparison:** the later unmodified HEAD also has no visible history scan. Therefore 3.710 → 2.137 GB is not a controlled patch effect, and 1.575 GB/request is not claimed as savings. Concurrent SCI changes landed between archives; existing basis shortcuts can already avoid this conditional admission scan. The change's causal proof is the same-transaction admission regression, whose parent invokes history and whose candidate does not. The repeated reach cost is stable at 0.773–0.778 GB. A direct installed `program-written-since?` call on default returned false in **1536.84 ms**, allocating **3,364,351,512 bytes on its thread**; that establishes the expensive fallback itself, separately from sampled request attribution.

Default's dense docstring-only request was `(in-ns 'scratch.check-alloc-astra)` followed by `(defn bump "Return an integer plus one." {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))`. Forms reported 0/24 ms; the enclosing window sampled **2.401 GB**, including the automatic step and its existing foreign reds. [Default defn owner weights](default-defn-owners.txt) leave **1.829 GB unattributed** at the process's fixed depth 64. Visible nearest owners include `seon.db/vector-query-call` 0.151 GB, `seon.db/declaration-content-key` 0.084 GB and `seon.db/write-owned-values-error` 0.025 GB. This is not a complete defn owner ranking and not pure evaluator allocation. The original throttled default two-test sample was 3.150 GB and the original defn sample 1.295 GB, but coarse weights make those unsuitable for precise comparisons. A later reused check was 17.45 ms with 0.694 GB in its enclosing automatic-step window; reused and executed checks are not compared as equivalent work.

Deep windows (UTC): first 11:28:55.914–11:28:57.826; candidate 11:42:28.547–11:42:29.979; later parent 11:46:02.084–11:46:03.274. JFR still reports truncated stacks at depth 256. The aggregator retains the truncation count, largest sample, exact time bounds, nearest/caller/inclusive owners, threads and allocation leaf classes so missing evidence is visible.

## Dependency guarantee and reproduction

Datahike fork pin `86c3301a00de8c49b2690083b2b1e69677bf0c8d`: `datahike.db/speculative-cache-context` preserves connection, generation, conservative and untouched attribute revisions, and changes witnesses for effective writes. `datahike.db.transaction/in-transaction-db` supplies the writer's effective candidate. This is a maintained-fork seam, not an upstream API claim. The live probe of an empty `seon.db/with` confirmed an uncommitted candidate with those same revisions; installed `program-revisions` returned nil solely because it required committed context. Equal present witnesses are sufficient; missing evidence remains unknown. Malli pin `418df4417827b66846b799b5e87267a344dffc29`: `malli.core/validator` and `malli.core/-instrument` continue to enforce the contracts. The non-nil return branch is tightened to connection-id, generation UUID, conservative UUID and attribute→UUID map.

For a disposable profile, after creating the output directory:

```sh
JAVA_TOOL_OPTIONS='-XX:FlightRecorderOptions=stackdepth=256 -XX:StartFlightRecording=settings=profile,filename=/Users/sean/src/seon/tmp/check-alloc-astra/deep,dumponexit=true,jdk.ObjectAllocationSample#throttle=off' bin/seon check --test seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol --test seon.id-test/identity-is-a-function-of-the-values-structure
java docs/research/agent-platform/2026-10-08/check-alloc/AllocationOwners.java RECORDING 'seon.test$run'
java docs/research/agent-platform/2026-10-08/check-alloc/AllocationOwners.java RECORDING '' FIRST_UTC LAST_UTC
```

The second aggregation includes all threads inside the observed request window, because worker stacks do not retain the initiating thread's `run` frame. Recordings also contain boot; including the entire file would falsely assign boot allocation to a check. The operator starts helper JVMs as well: select the recording whose `jdk.JVMInformation` names the actual test JVM. Raw recordings remain disposable under `tmp/check-alloc-astra`; the checked-in aggregates and full MCP envelopes preserve the findings. No shared cache was cleared and no instrumentation was disabled.

## Proof and remaining slices

The parent with only the revised regression failed exactly the two zero-history assertions: run `89327c9b7e54`, 6 passed/2 failed/0 errors, 3756 ms ([red proof](red-proof.txt)). Unchanged and result-only admission succeeded, but each called history once. Prior program writes refused admission both with and without revision evidence. The candidate's four focused admission/selection proofs passed 26 assertions in 4386 ms. That intermediate run included a temporary named return-schema experiment; the final two-test candidate above booted/published/adopted with the final tuple contract and no schema-resource change. Final commit-gate evidence is recorded below.

The source-edit SCI hook refuses nilable authored return contracts, including this API's existing nil return. A named-schema experiment met the same policy and was removed. This is a branch-edit proof gap, not a reason to change the API or relax instrumentation; platform proof uses the explicitly authorized committed-candidate publication/adoption/throwaway gate. No uncommitted source was loaded into default's JVM. Default's foreign automatic reds remain `bt.slug-test/slug-lowers` and `live.text-test/slug-joins-lower-case-words`; no broad-suite pass is claimed.

Next owner decision, in increasing scope:

1. **Demand reach acquisition (recommended):** acquire nodes for the requested closure and keep existing content-keyed node/SCC composition. Targets the **0.77 GB** bucket; O(closure) cold, exact reverse dependencies still authoritative. Requires a card covering unknown nodes, retargeted calls and cyclic dependencies, then bounded implementation/proof slices. Savings are an upper bound until measured.
2. **Branch-lineage witness:** improve the maintained Datahike seam so an unchanged captured branch shares exact content revisions after source-cache eviction. Can avoid repeated **0.77 GB** acquisition, but a truly cold closure still reads full attributes. Requires dependency research/proofs; never remove connection/generation keys without replacement authority.
3. **Changed-datom derivation:** advance persistent declaration/reach projections from authoritative transaction deltas. Covers reach plus the overlapping **0.155 GB** declaration-key work and **0.106 GB** digest comparison. Several cross-owner slices; requires decisions about multi-transaction lineage, eviction and speculative-to-committed reuse.

A separate narrow follow-up can carry one already computed digest difference into world construction (**0.106 GB** observed ceiling), after proving the two consumers compare the same immutable values. Toolkit/context acquisition is variable and should be independently profiled before a savings promise. EN-7's test-world reuse remains an architectural owner: do not replace these scans with another world/cache/runner.

## Landing gate

Full requested lint: `clj-kondo --lint src script test --cache false`, **6600 ms, 0 errors, 1493 warnings**, exit 2 for warnings. This is zero errors, not zero findings; no warning was suppressed. Source delta is **+1 net line** (14 added/13 removed); test delta **+3 net lines** (39 added/36 removed), including deletion of a global observation atom. Research evidence is additional documentation. Claimed source owners are `src/seon/test.clj`, `src/seon/test/runner.clj`, and `test/seon/test/admission_digest_test.clj`.

Foreign boundary: `branch-tests` owned `src/seon/sci/eval.clj` initially and landed changes while the measurements ran; `cx-1` holds context/turn files. Other dirty files/submodule content are untouched. The candidate gate includes only this lane's named paths on committed HEAD. A successful candidate gate is not default installation evidence. Another operator replaced default during this lane: final observation is PID 78227, start 2026-10-08T11:49:51.713Z, archive `ade6e305919add23890098150bab1f5753618b62`. This lane issued no restart/reset. Health remains faulted; last live heap was 0.64 GB, with existing pressure/arming/render findings. The new archive does not contain this source slice.

### Matched admission benchmark

A second parent/candidate pair shares **the identical parent `ade6e305919add23890098150bab1f5753618b62`**. Parent candidate `7fefbec71cf8ec62381167991345a37a2e495dc6` includes only the new regression; fixed candidate `b5d6f368cff993d2bfa2662ed9f4bcc034ae1dbb` adds the two source changes. Both run the same four admission cases. Parent: 6 passed/2 failed, **5138 ms**; fixed: 8 passed/0 failed, **4347 ms**. The failures are exactly the unwanted history calls, not correctness failures masked by changed assertions.

Samples with an `admit-run` frame total **2.093 GB → 1.932 GB** ([parent](admission-parent-owners.txt), [fixed](admission-after-owners.txt)), a sampled **0.161 GB** decrease. The remaining **1.926 GB** in `program-written-since?` belongs to the required changed/unknown evidence fallback, which this slice deliberately retains. This four-case regression includes fixtures and refusal cases; its elapsed time is not the latency of an ordinary unchanged admission. Truncated stacks make these observed-frame totals incomplete. Additional aggregates restricted to `seon.test-support/armed-calls` isolate visible frames from the two unchanged-program cases ([parent](admission-unchanged-parent-owners.txt), [fixed](admission-unchanged-after-owners.txt)); sampling does not replace the deterministic **one → zero history calls per case** proof.

The candidate's publication/adoption took **2448/552 ms**, boot **50334 ms**, ready **42704 ms**, total gate **65848 ms**. These slow acquisition gates are the explicitly requested platform check, not sub-second success; existing W-22/PB-17 and W-27/W-28 cover the still excessive boot/projection/test work. No new JVM was launched while another owned probe/test JVM was running.

### Pre-existing reds and cleanup boundary

The first commit gate ran ten named Vars on candidate `dcd76ac5aab02af69c31b80b2fd0523c364cdb31`: **102 assertions passed, 1 failed, 1 error, 8075 ms**. It correctly refused the commit. The two red Vars were repeated on **unmodified HEAD `ade6e305919add23890098150bab1f5753618b62`**, run `b52a37796348`: **12 passed, 1 failed, 1 error, 3677 ms**, with the same causes ([parent evidence](foreign-reds.txt), [candidate evidence](candidate-reds.txt)):

- `seon.test-test/recording-distinguishes-run-replay-from-a-new-event`: a read-refusal assertion expects `:seon.db/invalid-read-error`, but the writer answers `:seon.db.write/validation-refusal`.
- `seon.test-test/resolution-follows-admitted-source-and-acquisition`: the test passes the absent legacy `:seon.program/row` to `seon.program/declaration-row`, whose map contract refuses nil. The retired-row defect class is already recorded by `seon.sci.branch-execution-test/branch-proof-expects-a-retired-evaluation-row`.

These tests and their owners were not edited. The next gate names the eight other relevant Vars individually, using the owner's explicit authorization for pre-existing reds. No broad acceptance is claimed.

All profiling JVM PIDs 71784, 76608, 77395, 78111 and 79296 had exited; current default reported no JFR recordings. Retiring the owned scratch branch with `(my.agent/retire-branch! {:seon.store/branch :agent-60ca10519381})` as `check-alloc-astra` returned the bare error `{:seon.error/message "run transition refused: admission-closed"}`. This is the existing `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` defect; successful retirement is not claimed. The uncertain branch is preserved for root to resolve. No other lane's session, branch, root or file was modified.
