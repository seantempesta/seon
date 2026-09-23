---
type: research
status: measured diagnosis; fix design requires independent review
created: 2026-09-23
---

# Error constructor cost: repeated wrapper selection dominates

The constructor already preserves flat observations and whole causes. Keep that data flow and its armed contract; remove repeated world selection at the wrapper owner, using A1's already specified context-owned contracts.

## Finding and decision

**The E17 regression is real. Most added time is the two armed wrappers' per-call projection selection, not error normalization or validator compilation.** The four-argument constructor re-enters its own armed map arity (`src/seon/error/refusal.clj:118`). The parent literal entered neither wrapper.

The [cut landing](../../prds/agent-platform/landing/lane-sol-error-constructor-2026-09-23.md) measured 7.66875 → 11.34458 µs (+47.9%). This investigation reproduces 6.650604 → 9.859875 µs (+48.26%), a 3.209271 µs increment on the same JVM with longer warm samples. These are different measurement sessions, not an assertion that the historical 3.67583 µs can be decomposed exactly from today's medians.

**Recommended fix: A1-2's retained host/context wrapper, at the common instrumentation owner.** Do not change the 191 call sites or special-case diagnostic. Keep the constructor's two entries, merge order, both validations and profiling. An isolated valid-arity prototype with that wrapper costs **7.904229 µs**, **18.85% above the contemporaneous parent**, below 7.980725 µs (parent × 1.20). It is also below the historical parent's 9.2025 µs threshold, but the paired comparison is the meaningful result. Margin is narrow: this is design evidence, not a production performance pass.

**Needs review; not the one-seam obvious-fix exception.** A1-2 changes contract ownership across JVM/SCI contexts. README §3 and A1-2 explicitly require the two-generation direct/indirect/host proof before removing selection. That proof remains a dependency, not permission to delete the scan now. This report supplies a measured implementation target; it does not claim that the context guarantee or fix is installed.

## Basis and exact verification boundary

- Branch: `refactor/agent-platform`; checkout HEAD at inspection `318932ec9cbc266c76ce31049c5ef432e573e610`.
- Both status entrances worked. Default PID 48902, start 2026-09-23T18:07:01.344Z, source archive `ce73846828a5cc32798ef630b5a574f777646f30`, publication off. Source citations for instrumentation below refer to that **archive revision**, not foreign working-tree changes. Constructor and database-view cited lines also match the inspected checkout.
- All evaluations used MCP `eval_clj`, explicit root `/Users/sean/src/seon`, cluster `default`, JVM mode, private session `astra-error-cost`, namespace `probe.error-constructor-cost`. Only throwaway definitions were changed; no default Var, configuration, database program row, or foreign session was edited. Normal wrapper counters and MCP oversized-result storage are tool side effects; “read-only” is not a claim of zero allocation or zero telemetry.
- The installed diagnostic is armed: root class `clojure.lang.AFunction$1`, original `seon.error.refusal$diagnostic`; distinct objects. Its complete installed schema has both the authored map and positional arities, each returning `:seon.error/base` (`refusal.clj:104`, `:106`).
- Parent body: `c07e89393415d140900c3b36d6faa40442301ed8`; post-cut body: `55f2a989a`. Reused the landing's exact source copies and qualification probe, under our own namespace. The copies' database-view entry functions are unarmed; shared live callees are armed. This isolates the body delta, as the original landing did. It does **not** measure a fully installed database-view wrapper, actual agent SCI execution, browser paint, or a whole test workload.
- Input is one immutable database value with copied config `:keep-history? false`; the live connection is not altered. Fixed `Date(0)` is used for component and equality probes. No GC forcing, cache clearing, store acquisition, restart, adoption, extra JVM or worktree.
- Foreign boundaries: dirty instrument/cluster/fn/issue/test owners and Malli fork were read, never repaired. The working-tree Malli checkout is `56394c54e34415a333d6281c4bfddf7122d02bd5`; the committed gitlink is `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`. The running archive and its loaded callables are the measured authority. No shared-tree load failure blocked this diagnosis; no archive build or test request was necessary for a documentation-only lane.
- Historical status reported 16 error signatures, 34 errored receipts, one failed run and unknown failed-test evidence. These are not a clean-runtime claim. Its historical >1 s boot/test profiles were not operations initiated here and are not attributed to this constructor.

## Measured breakdown

Seven alternating-order rounds after 2,000 warm calls per case. Final confirmation: 2,000 calls/sample; units µs/call. Setup, schema lookup/compilation and equality are outside timed loops. All returned envelopes were inspected. Raw forms/envelopes: `tmp/astra-error-cost/{breakdown,confirm,equal}.json`; setup files: `setup.clj`, `candidate-site.clj`. Executed core forms are committed below because only this Markdown may be committed.

| Case | Median µs |
|---|---:|
| Parent database-view body | 6.650604 |
| Cut database-view body | 9.859875 |
| Armed diagnostic, positional entry (includes map entry) | 3.213604 |
| Original positional body (still calls armed map entry) | 1.288375 |
| Armed map entry | 0.933667 |
| Original map body, no Throwable | 0.020813 |
| Header map + merge of actual refusal members | 0.365917 |
| Input + output validators, positional entry | 0.250458 |
| Input + output validators, map entry | 0.255708 |
| Projection selection with wrapper's compilation binding, four args | 1.390542 |
| Same, one map arg | 0.478542 |
| Date allocation alone | 0.016729 |
| Retained-contract prototype, positional constructor | 1.205792 |
| Same prototype at copied database-view site | 7.904229 |

Accounting is **differential, not an additive CPU profile**: isolated operations change allocation/JIT context and independent medians need not sum. The approximately 1.869 µs projection-selection pair accounts for about 58% of the 3.209 µs body increment. The validators total 0.506 µs, merge 0.366 µs; remaining roughly 0.47 µs includes argument sequences/vectors, arity dispatch, apply/catch shells, profiling, map destructuring and measurement interaction. Do not call that residual “validation” or pretend it has finer measured attribution.

The wrapper increments are independently visible: armed positional minus original positional ≈1.925 µs; armed map minus original map ≈0.913 µs. Original positional is **not bare construction**: its self call still resolves to the live armed map entry. The whole constructor's 3.214 µs closely matches the whole site's 3.209 µs increment.

| Round | Parent | Cut | Retained-contract prototype |
|---|---:|---:|---:|
| 1 | 6.650604 | 9.937188 | 7.912125 |
| 2 | 6.748042 | 9.962354 | 8.179771 |
| 3 | 6.875667 | 9.859875 | 8.263813 |
| 4 | 6.615105 | 9.904375 | 7.852666 |
| 5 | 6.676167 | 9.772291 | 7.904229 |
| 6 | 6.617229 | 9.829604 | 7.860729 |
| 7 | 6.618812 | 9.829980 | 7.783042 |
| Median | 6.650604 | 9.859875 | 7.904229 |

An earlier seven-round confirmation at 1,000 calls/sample found 6.697500 / 9.911541 / 7.924625 µs (+18.32% candidate/parent). A preliminary generic rest-argument prototype was 8.107791 vs 6.672416 µs (+21.51%): **not passing**. The final prototype captures the original body once and uses direct outer invokes for the two measured arities. The experiment changed both together; their individual savings are not separately established. Do not assume scan deletion with arbitrary wrapper shape has enough margin.

Memory observation via current-thread `ThreadMXBean.getThreadAllocatedBytes`, 1,000 calls/case: parent 36,687.712 B/call, cut 49,807.688 B/call, candidate 40,895.688 B/call. This is allocated bytes, not retained heap or a GC profile; other shared callees dominate absolute allocation. Candidate removes approximately 8,912 B/call relative to the cut.

Measurement correction: the first exploratory “supply1” passed a Var to `-f->original` and omitted the wrapper's compilation binding, measuring nested instrumentation instead (7.464 µs). Rejected. The table uses the exact binding and private call shown below. A tool hook also refused saving a standalone probe lacking its REPL namespace dependencies; no source was changed and exact forms were retained in JSON/this document instead.

## Owner and dependency evidence

1. **Constructor:** `src/seon/error/refusal.clj:107` returns the observation unchanged when no Throwable exists. Lines 118–121 build three headers, merge members last, and call diagnostic again. There is no key normalization, clock read, digesting, database read or projection acquisition in this body. Existing cause/stack work at lines 109–116 is not entered by this refusal.
2. **Caller:** `src/seon/db.clj:2737` supplies its Date before entering the constructor. Expected-shape digest and evidence construction at lines 2742–2746 exist in both copied versions; they contribute to the approximately 6.65 µs baseline, not the added constructor cost. Date allocation likewise exists in the parent.
3. **Wrapper selection:** archive `src/seon/instrument.clj:551` tests every argument's metadata, direct projection member and environment member; it allocates candidate vectors and invokes request-member repeatedly. At line 784 every armed call binds compilation mode and performs this scan, even when none of the arguments carries a projection. Cost is O(argument count × candidate paths), paid twice here. This is the dominant avoidable work.
4. **Validation already retained:** archive `instrument.clj:638` obtains the retained contract; line 669 invokes Malli's `-instrument` during wrapper construction. Archive `instrument.clj:744` retains boot-wrapper; compilation is not normally repeated per constructor call. “Compile once at arm time” alone would fix nothing here.
5. **Dependency guarantee:** pinned Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, `reference-code/malli/src/malli/core.cljc:2202`, constructs input/output validators before returning the invocation closure; the closure checks input before body and output afterward. This is upstream behavior too: locally available `origin/master` `e878083f385f35669eb2024366800e438d1aee49`, same file at line 2219. No network claim of current upstream HEAD. Inputs are compiled contract, original callable, report policy/options; recomputation belongs to changed contract/context installation. Per invocation cost follows argument/output shape, never the store or whole program.
6. **Profiling:** `src/seon/profile.clj:112` times each invocation and updates its cell. It stays in both candidate entries. No contract or profile cell is disabled to get the reported result.

## Smallest justified fix and acceptance

Apply the existing [A1-2 design](../../prds/agent-platform/plan/lane-a1-projection-carried.md), row “Per-context installation replaces host projection selection,” subject to its prerequisite proof. The constructor stays as-is: **zero changes at all 191 callers and no constructor-only bypass**. The common wrapper captures its retained contract, original callable and error policy once. Host calls invoke the host wrapper; context calls invoke their context wrapper. Remove the per-invocation supplied-projection branch only after B2's affected-caller path preserves indirect contract ownership.

Use the existing Malli compiled wrapper and C1 profile cell; do not add a second schema cache or cache the result by argument shape. Retain direct outer invocation for common arities at the general wrapper seam if the generic rest wrapper fails the paired threshold; this must be general arity handling, never a diagnostic-symbol conditional. Keep a fallback through Malli for unsupported arities so invalid arity still produces the declared diagnostic rather than an unhandled raw ArityException. The prototype demonstrates valid arities only, not that fallback or all generic function shapes.

Complexity: constructor O(member count), validation O(declared argument/output shape), Throwable work O(cause links + frames) unchanged. Target wrapper work is O(invocation/validation), with zero per-call world discovery; compilation/ownership changes occur on the affected context/contract installation. No full-program work or per-call registry compilation is introduced. The simplest rejected alternative is a constructor map-assembly optimization: even eliminating all 0.366 µs cannot recover the required approximately 1.88 µs. Removing one self wrapper alone saves at most about 0.91 µs in this measurement, also insufficient. Disarming or weakening either arity is prohibited by README §7.

Before integration, the implementation lane must:

1. Prove A1's two distinct contract generations under direct, indirect and host calls, including a changed callee under an unchanged caller. If it fails, retain selection and report the failing path; this document is not an exemption.
2. Keep exact constructor schema, members-last collisions, supplied timestamp identity, optional message behavior and full Throwable cause/frame behavior. Here three samples (plain, nested cause, explicit message with cause) were whole-value equal between installed map entry and both candidate entries; header overrides and Date identity also held. That is sampled evidence, not exhaustive coverage.
3. Prove invalid input prevents body entry, invalid output refuses, invalid arity remains declared, and both `:panic`/`:record` paths retain their error policy. The prototype did not exercise these failures.
4. Adopt and re-arm through the canonical boundary, then repeat the identical parent/cut/fix probe with 5+ warm alternating rounds. Require paired fix/parent ≤1.20; a narrow microbenchmark pass is not an adoption proof. Run one focused `seon.test/run` request on the lane branch for affected instrumentation/constructor behaviors. Orchestrator owns platform/cold integration.
5. Report allocation and wrapper identity alongside time; preserve the constructor's reaching tests. No blanket suite or caller sweep is justified.

Review must rule on the A1/B2 dependency and smallest general invocation shape before implementation. No independent review was launched by this bounded diagnosis assignment; no source/test implementation was requested or made.

## Executed component and candidate forms

The landing's source-qualification setup was reused with target namespace changed to `probe.error-constructor-cost`, names `parent-database-view` and `head-database-view`, and `before-call`/`after-call` closures over the immutable copied input. The candidate-site setup changes only the qualified diagnostic reference in that copied head body to the following throwaway callable. It never changes a default Var. `compiled-wrapper` is called through its original only for **construction**; returned calls run its unchanged input/output/guard validation and failure shell. The original constructor body is called **inside** this armed wrapper, never as the proposed public entry.

```clojure
(do
 (def diagnostic-candidate
   (let [body (malli.instrument/-f->original @#'seon.error.refusal/diagnostic)] (fn ([observation]
        (body observation))
       ([at layer operation members]
        (diagnostic-candidate (merge {:seon.error/at at :seon.error/layer layer :seon.error/operation operation} members))))))
 (let [projection (seon.schema/projection-from-database @(seon.cluster.boot/connection "default"))
       original diagnostic-candidate
       compiled ((malli.instrument/-f->original @#'seon.instrument/compiled-wrapper)
                 projection 'seon.error.refusal/diagnostic
                 (:malli/schema (meta #'seon.error.refusal/diagnostic))
                 original {} (:seon.instrument/policy (meta @#'seon.error.refusal/diagnostic)))
       cell (seon.profile/cell {:seon.profile/sym 'probe.error-constructor-cost/diagnostic-candidate
                               :seon.profile/scope :seon.profile/host})]
   (def diagnostic-candidate
     (seon.profile/with-cell cell
       (fn ([observation] (seon.profile/timed (compiled observation))) ([at layer operation members] (seon.profile/timed (compiled at layer operation members))))))
   {:value (diagnostic-candidate (java.util.Date. 0) :x/y 'a/b {})
    :cell-identity (:seon.profile/sym cell)}))
```

The two explicit arities are a benchmark specialization, not permission to ship a constructor-specific wrapper. Empty caps suffice for these valid inputs only; production uses the full existing caps/policy. Final confirmation form:

```clojure
(let [at (java.util.Date. 0)
      observation (assoc (after-call) :seon.error/at at)
      members (dissoc observation :seon.error/at :seon.error/layer :seon.error/operation)
      layer (:seon.error/layer observation) operation (:seon.error/operation observation)
      f @#'seon.error.refusal/diagnostic
      original (malli.instrument/-f->original f)
      projection (seon.schema/projection-from-database @(seon.cluster.boot/connection "default"))
      compiled (malli.registry/schema (:seon.schema.projection/registry projection) 'seon.error.refusal/diagnostic)
      arities (mapv malli.core/-function-info (malli.core/-function-schema-arities compiled))
      validators (mapv (fn [info] [(malli.core/validator (:input info)) (malli.core/validator (:output info))]) arities)
      [in1 out1] (first validators) [in4 out4] (second validators)
      args1 [observation] args4 [at layer operation members]
      cases [[:candidate-site candidate-call] [:candidate4 #(diagnostic-candidate at layer operation members)] [:parent before-call] [:head after-call]
             [:armed1 #(f observation)] [:armed4 #(f at layer operation members)]
             [:original1 #(original observation)] [:original4 #(original at layer operation members)]
             [:merge #(merge {:seon.error/at at :seon.error/layer layer :seon.error/operation operation} members)]
             [:date #(java.util.Date.)]
             [:validate1 #(do (in1 args1) (out1 observation))]
             [:validate4 #(do (in4 args4) (out4 observation))]
             [:supply1 #(binding [seon.instrument/*compiling-contract* true] (#'seon.instrument/supplied-projection args1))]
             [:supply4 #(binding [seon.instrument/*compiling-contract* true] (#'seon.instrument/supplied-projection args4))]]
      measure (fn [f] (let [t (System/nanoTime)] (dotimes [_ 2000] (f)) (/ (- (System/nanoTime) t) 2e6)))
      _ (doseq [[_ f] cases] (dotimes [_ 2000] (f)))
      rounds (mapv (fn [i] (into {} (map (fn [[k f]] [k (measure f)]) (if (even? i) cases (reverse cases))))) (range 7))]
  {:unit :us-per-call :n 2000 :rounds rounds :medians (into {} (for [[k _] cases] [k (nth (sort (map k rounds)) 3)]))})
```

Raw confirmation envelope reports 552 ms; other benchmark evaluations were 233, 295, 317 and 318 ms; setup 9 ms each, candidate construction 3 ms, equality/allocation 51 ms. All individual initiated REPL operations were sub-second. Shell reads/status likewise completed sub-second. No initiated operation exceeded ten seconds.

## Landing scope

Scoped citation check: **1 document, 0 failures, 72 ms**; staged whitespace check passed. The repository-wide Markdown hook reported 46 existing findings outside this document (including historical dependency pins); that is not a repository-wide pass. The first scoped check required staging the new document, then exposed one local line reference, corrected before the successful check.

Only this research document is committed. Source net **0**, test net **0**. Temporary probe artifacts remain under `tmp/astra-error-cost/`; no owned background process or disposable root was created. No push. The existing constructor-cost finding in the cut landing is extended here, not duplicated as a new issue class. The production regression remains open until the reviewed fix, adoption and paired proof land.
