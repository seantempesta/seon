---
title: "Independent branch-runtime review #3 — Astra"
type: research
created: 2026-10-20
status: NOT SAFE
---

# Independent branch-runtime review #3 — Astra

**NOT SAFE to restart default onto the reviewed HEAD as satisfying the owner's whole-runtime requirement.** Five new root causes produce wrong answers or cross-branch mutation. The known predicate gap also violates that requirement. These are correctness blockers even though the candidate boots and the earlier targeted regressions mostly pass.

Reviewed source: `06614f6a8ab4b6771280cbf8a1428349b0eb838b`. Lane `review3-astra`, independent of the other reviewer; no other review was read, no other lane was contacted or changed. The five new counterexamples had been reproduced and written before HEAD later advanced by a docs-only commit. The other review file was not opened; Git incidentally displayed the other lane's commit subject during the HEAD check, and none of its claims were used here. Default was neither restarted nor reloaded, and received no SCI request. All evaluation experiments used disposable check JVMs and private-index dangling commits. Only this report, its evidence patch and issue documents are committed.

## What was reviewed

Read AGENTS.md and these authorities end to end: [branch-runtime](../2026-10-19/branch-runtime.md), [first review](../2026-10-19/runtime-review.md), [re-review](../2026-10-19/runtime-rereview.md), and [closed-runtime hybrid](../2026-10-19/closed-runtime-hybrid.md), including P0–P7's corrected status and deferred predicate mechanism 1. Read the requested Git history since `2416e7476` and the affected runtime, cache, schema and database changes. The principal cuts are `04d6075ab`, `8c63f2b8d`, `bf54c1179`, `7df8dbb21`, `66ca5108b`, `1988560d1`, `f1145501b`, `57fa7fa7d`, `c23e1f807`, `1bcb3bf65`, `eb7a77b5f`, and `57224cfc8`.

Library seams checked at their pinned revisions: SCI `33039c6e49b47096a68d2964db9be382525bf75e` (`sci.core/fork`, `sci.core/bind-root!`, `sci.impl.fns/escaping`, `sci.impl.fns/in-defining-ctx`, `sci.impl.multimethods/multi-fn-add-method-impl`); Malli `dd13ff81139159778edb0a67b491eccc0baa991d` (`malli.core/validator` and the predicate-resolution caller); Datahike `ccba220be1556a5f9b7fde2fc7620d0e0c019e0e` (branch/value interfaces and the Seon database caller).

Initial default observation: archive `a92ba5b944f049247e32e8af458ef7fb6e3b4870`, PID 11460, start `2026-10-19T11:31:32.378Z`. `bin/seon status` and MCP `runtime_status` reported **faulted**, seven open faults, seven agent-error signatures, 63 errored receipts, four malformed contracts and four unowned namespaces. Cache was approximately 507.35/512 MB; live heap approximately 1.67 GB under the 4 GB cap. That is pre-existing default health, not evidence that HEAD has passed or failed these probes.

## Blockers, ranked

### 1. A branch can supply main's query derivation, and main can supply the branch's

[Issue: shared projection derivations](../../../seon/issues/a-branch-and-compiled-code-share-projection-derivations.md).

Change `seon.schema.datahike/lower-where` to return `[]` on a disposable branch. Call `seon.db/lowered-where` in that branch and in main with the **same held projection** and fresh valid clauses. Passing a parent value is deliberate: the requirement allows arbitrary compositions; the installed real-path test already passes a parent's database to a child's runtime.

| Order | Expected `[branch main]` | Actual |
|---|---|---|
| Branch derives first | `[[] clauses]` | `[[] []]` |
| Main derives first | `[[] clauses]` | `[clauses clauses]` |

`seon.schema.internal/projection-cache-value` uses `clojure.core.cache.wrapped` on the projection's holder. Its key omits the executing program. P7's `seon.cache` bindings do not reach that cache. This is a direct production-owner reproduction, not a hypothetical race. Red: `seon.runtime-review3-astra-extra-test/projection-cache-holds-the-executing-program`, run `ab364de6a99e`.

### 2. A method write changes sibling and parent runtimes

[Issue: shared multimethod mutation](../../../seon/issues/a-multimethod-change-mutates-sibling-runtimes.md).

In an acquired context, define a multimethod whose `:a` method returns 1. Fork it twice through `seon.sci.eval/fork-cluster-ctx`, then redefine that method to return 2 in the first fork. Calls in `[first-fork sibling parent]` return **`[2 2 2]`**, expected **`[2 1 1]`**. The raw SCI fork control gives the same result.

SCI isolates Var bindings but shares the mutable `clojure.lang.MultiFn` value; `sci.impl.multimethods/multi-fn-add-method-impl` mutates it with `.addMethod`. No JVM Var was redefined by the test. Production `src` has no `defmulti`, but agents may define one, and the requested guarantee covers their programs. Red: `seon.runtime-review3-astra-real-test/a-multimethod-change-is-local-to-an-acquired-fork`, run `45b51eefd740`.

The corresponding protocol case also fails: `extend-type` of an inherited record in one acquired fork changes both sibling and parent methods, again `[2 2 2]` instead of `[2 1 1]`. Red: `seon.runtime-review3-astra-real-test/a-protocol-extension-is-local-to-an-acquired-fork`, candidate `52780d2fa7a3058a29482e52eed847a6b06f7b98`, run `be988cc070d1`. It reaches the same shared method-table owner.

### 3. A cache writer and reader in one branch use different keys

[Issue: cache writer/reader split](../../../seon/issues/a-branch-cache-writer-and-reader-use-different-keys.md).

Change only `seon.render/invocation-weight` to return 2048. On the acquired branch, `seon.render/retain-invocation!` writes through the runtime-keyed SCI cache entry; `seon.render/invocation` remains compiled and reads the bare key. Immediately reading the returned entry gives **`nil`**. If main already held that key, writing a branch entry then reading returns **`[:branch :main]`**, expected **`[:branch :branch]`**.

The minimal control expects `[2 2]` for write/read: actual `[2 nil]` cold, `[2 1]` after main fills its key. All these calls use `seon.cache`; this differs from blocker 1. Static function reach cannot establish that two halves of the cache owner's protocol may safely use different keys. Reds: the two cache tests in `seon.runtime-review3-astra-new-test`, and `seon.runtime-review3-astra-real-test/a-branch-cache-reader-cannot-read-the-entry-it-replaced`; runs `062ec5294c34` and `45b51eefd740`.

### 4. An eager value initializer executes a partly installed runtime

[Issue: transitive initializer order](../../../seon/issues/a-value-initializer-runs-before-its-transitive-branch-callees.md).

A small loaded source fixture declares `h → 1`, `g → (h)`, `f → (g)`, and `(def a-value (f))`. Change `h` to 2, then to 3. The functions are contracted; the index and normal database replacement derive their facts.

| Runtime and observation `[a-value (f) (g)]` | Expected | Actual |
|---|---|---|
| Branch with `h → 2` | `[2 2 2]` | `[1 2 2]` |
| Branch with `h → 3` | `[3 3 3]` | `[1 3 3]` |
| Continuation acquired from the first branch to the second | `[3 3 3]` | `[1 3 3]` |
| Main | `[1 1 1]` | `[1 1 1]` |

`seon.sci.eval/dependency-order` follows ordinary call edges for a value, but only macro dependencies for a function. It installs `f` before evaluating `a-value`, while `g` still has its compiled root. Later installing `g` cannot repair the already computed value. Red: `seon.runtime-review3-astra-real-test/a-loaded-value-initializer-executes-the-whole-branch`, run `45b51eefd740`; a minimal production-binder control is also red.

### 5. A loaded open macro still expands against main

[Issue: open macro](../../../seon/issues/an-open-macro-expands-with-the-loaded-runtime.md).

The loaded fixture's macro calls `((resolve 'seon.review3-fixture/h))`, and an ordinary function expands that macro. Change `h` from 1 to 2. `[direct-helper macro-expansion caller]` is **`[2 1 1]`**, expected **`[2 2 2]`**. The stored macro is explicitly `:seon.fn/host-bound? false`, `:seon.fn/macro? true`.

The macro due rule requires membership in `reached`, but that index contains closed names. An open macro therefore stays compiled. R3's fix works for its closed macro; it does not cover this case. Red: `seon.runtime-review3-astra-real-test/a-loaded-open-macro-executes-the-whole-branch`, run `45b51eefd740`; a minimal production-binder control is also red.

### 6. The known predicate gap is not acceptable under the requested criterion

The installed red `seon.sci.eval-test/a-branch-changed-predicate-decides-its-branch-projection` still fails: after a branch changes `seon.task/symbol-name?` to reject every input, its carried projection validates `"a-task"` as **true**, expected **false**. This was the only red in the initial 16-test focused run (`796bbf8180e2`: 52 passing assertions, one failing assertion, zero errors).

The owning task is already `seon.schema/a-branch-changed-predicate-is-seen-only-after-merge`. The hybrid explicitly accepted mechanism 2 as an interim stage and deferred mechanism 1. I do not mistake that staging decision for an unnoticed regression. But the question now asks whether the whole-runtime requirement holds, and it does not: validation is part of execution, and a branch's tests can validate against main's predicate. No immediate JVM crash follows from this example; it remains a correctness blocker for the requested assurance.

## Earlier blockers replayed on HEAD

The old probe source was copied from `tmp/runtime-rereview/`, with its obsolete removed-report call omitted and output redirected to this lane. Candidate `bf5e6ee7e4702fb0e6975737994ac3b10f8cfcd7`, run `be38f753c79a`: ten probes, 12,634 ms. These old probes often assert only that they ran; **the actual saved values below, not their green wrapper assertions, are the evidence**. Installed regression assertions were separately run on untouched HEAD.

| Earlier item | Expected now | Actual on HEAD / assessment |
|---|---|---|
| F1, value holding functions | `families` reaches branch `missing-models` | Direct and through `families`: `[{:seon.config.ai/model "BRANCH"}]`; main result nil. Fixed for this probe; eager initializer blocker 4 remains. |
| F2, host-bound caller | Under the owner's stated exception, host-bound `parsed` keeps loaded code | Branch `words` is `["BRANCH"]`; `parsed` still cites `seon.db/q` (`true`, fresh interpretation would give `false`). Unchanged, expressly accepted exception. |
| F3, changed macro via database | Branch bucket 22 warm/cold; main 3 | 22, 22; main 3 before/after. Installed MCP changed-macro regression green. |
| F3, low-level `test-support/evaluate` | Its non-settling behavior must be distinguished from MCP | Direct macro 33, caller 3, still compiled. The same old helper limitation remains; no claim that this entrance now settles program writes. |
| F4, ordinary defns hanging | Each MCP call answers its value | Calls answered in 310 ms and 176 ms; installed MCP regression green. |
| F4b, synthetic own-class imports | Stored imports unchanged | Both namespace import sets unchanged, 9 and 9. |
| F5, half-bound failure | An analysis exception changes no produced destination roots or snapshot | Injected unresolved symbol: `[a z h]` remains `[1 1 2]` (the explicit `h` edit precedes binding), snapshot equal. Three assertions green; failed production had no partial destination install. |
| R1, cached effective configuration | Either order: branch 777, compiled main 4 | Branch-first `[777 4]`; main-first `[4 777]` in evaluation order. Fixed for this owner. New cache blockers 1 and 3 remain. |
| R2, escaped derivation | Direct/closure/lazy/thread/future give branch values without main-key entries | `[10 20 [30] 40 50]`; every corresponding main cache entry nil. Fixed for this probe. |
| R3, macro reading a changed value | `buckets=3`, `bucket(5)=2` | Database: 3 and 2. MCP same request and next request: `[3 2]`. Fixed for this closed macro; blocker 5 remains. |
| R4, unused host-bound report | Deleted report and every consumer | Deleted by `c23e1f807`; no current callable report exists to replay. F2 is now the explicit exception, not a purported reported refusal. |
| R5, launch defonces | Branch keeps bytes actually loaded despite later disk edits | On the disposable archive, changed profile source and deps bytes; branch retained original launch digests `f9f55c7e…` and `d075ebfe…`, matching JVM. Value census: 410 rows, 12 re-evaluated, down from 476/72 in re-review. |
| R6, insufficient proof | Real entrances and each changed mechanism tested | Overlay, continuation, fork, MCP, alias change, two-step composition and whole-delta tests green. Three mutations below go red. Universal completeness still disproved by new cases. |
| R7, inherited open value | Child value holds child's helper | `an-open-value-holds-the-functions-of-its-runtime` green, as were the two-step and whole-delta rows in `seon.sci.runtime-test`. |
| R8, cached program classes/reload | Source reload replaces roots and evaluator stays live | Five named reload/adoption/continuation tests green: 25 assertions, run `545d1514a0d6`, 6,971 ms. Source reload and evaluator self-reload occurred only in that throwaway JVM. |

The generator exercises two branches, a fork of a fork, overlapping edits, reverse observation order, macros and namespace alias changes against a fresh-SCI oracle. The additional real-path regression covers `base-ctx`, `acquire!`, `fork-cluster-ctx`, and MCP `arm!` work. A record implementing a protocol and a multimethod whose methods call a changed helper gave `[[3 3] [2 2] [1 1] [3 3] [1 1]]` across child/parent/main/repeated orders: method invocation follows the executing context. That green control does **not** establish method-table mutation isolation (blocker 2).

## Mutation checks: installed proofs are sensitive, but incomplete

Each mutation changed only `src/seon/sci/eval.clj` in a private-index dangling commit; no shared source was edited. The named tests were green on unmutated HEAD.

| Mutation | Candidate | Named installed test(s) | Actual |
|---|---|---|---|
| Remove final runtime cache-entry binding: `bound (merge entries produced)` → `bound produced` | `70c9778a9d8b77d6e712e356a269323e3f4eb59d` | Generator and `a-runtime-derivation-is-keyed-by-its-own-program-through-every-entrance` | Both red, four failures, run `ee06cf5d453e`; main receives 777, branch receives 4, child receives parent's 777. |
| Key cache entries by current `own` changes, dropping inherited changes | `687f09d42b4743b181dee305e2993258157ef6fc` | `a-derivation-is-keyed-by-its-runtimes-whole-delta` | Two failures (both orders), run `6f439ffd6e2a`; 2029 and 2039 contaminate each other. |
| Do not bind each produced root into the production fork before producing later roots | `d898e4cf5e62ad060e87aeeb240e6519c0ce0639` | Generator | Red, run `20c509c5e2d6`. |

The generator still supplies its own reach graph, never changes value definitions, and uses values that hold functions rather than execute transitive calls during initialization. Its macro performs no runtime lookup; its cache shape combines lookup and derivation in one function. The cheap-branch and 100-branch tests call `seon.sci.runtime/bind`, whereas production calls `seon.sci.eval/bind-analysed!`; their allocation assertions alone do not prove the production planner's complexity. These are concrete proof limits, not reasons to discard the useful mutation results.

## Cost, budget pressure and restart evidence

The **same HEAD cost-test implementation** ran on both source revisions, in separate check JVMs. The first pair used `e949aa50b4fbc72cbc98c346140dba2b1b5a9c74` (HEAD, run `30659c272fdb`) and `9aefbeb8d648e27de764c9f978883e20f08e73bc` (parent `716cc7527`, run `8752267f7584`). The second pair reversed revision order: `7f09f23db9501d929e43e71592ecf9f32c825f3e` and `93723720cd384cecbcbd8c51eec33cdf03ebf663`, runs `aaa58143a716` and `b3d23199996a`. Only test code changed in those candidates. Allocated MB below follows the table’s `:alloc-mb` units; cache capacity later is the configured decimal byte budget.

First pair; each cell is **716cc7527 → HEAD**:

| Operation | Allocated MB | Instrumented calls | Reads | Wall ms |
|---|---:|---:|---:|---:|
| `unchanged-request` | 18.2 → 20.4 | 6,466 → 7,185 | 46 → 46 | 29.9 → 23.8 |
| `fresh-agent-first-request` | 54.3 → 65.4 | 7,505 → 8,242 | 45 → 45 | 98.0 → 81.8 |
| `new-def` | 93.4 → 114.3 | 19,285 → 20,861 | 348 → 348 | 98.2 → 138.9 |
| `unmap-one-def` | 90.1 → 100.4 | 18,522 → 19,903 | 321 → 321 | 108.7 → 94.0 |
| `redefine-one-form` | 330.6 → 411.1 | 49,823 → 56,539 | 356 → 356 | 264.0 → 507.4 |
| `reuse-one-unchanged-test` | 29.8 → 32.5 | 7,854 → 8,701 | 58 → 58 | 28.2 → 53.8 |
| `current-state-with-task` | 208.0 → 222.4 | 37,939 → 40,322 | 209 → 218 | 118.6 → 181.6 |
| `seon-db-nine-forms` | 983.8 → 1,001.5 | 106,004 → 113,418 | 1,563 → 1,845 | 961.7 → 1,092.7 |
| `hub-docstring-change` | 250.1 → 277.4 | 58,576 → 60,207 | 379 → 380 | 227.0 → 305.9 |
| `hub-behavior-change` | 905.8 → 1,736.3 | 196,762 → 279,051 | 1,283 → 1,288 | 1,111.6 → 1,735.4 |
| `hub-change-sequence` | 3,724.8 → 4,876.5 | 635,638 → 755,046 | 5,830 → 6,127 | 4,833.7 → 5,620.6 |
| `cold-stale-merge-members` | 4,310.4 → 4,371.1 | 823,882 → 845,032 | 15 → 15 | 5,235.5 → 4,639.4 |
| `merge-one-function` | 121.2 → 131.2 | 19,682 → 21,230 | 190 → 190 | 190.0 → 280.0 |
| `similar-by-name-warm` | 26.7 → 30.4 | 7,874 → 9,036 | 60 → 61 | 31.7 → 33.6 |
| `unresolved-symbol-request` | 33.8 → 35.3 | 14,443 → 15,332 | 66 → 66 | 36.3 → 34.4 |

**The installed gate passes; its headroom is small.** `:hub-change-sequence` is bounded at 5,053 MB, 837,784 calls and 7,579 reads. HEAD measured 4,876.5 MB then 4,943.1 MB: **3.49% then 2.17% allocation headroom**. The gate also passed in the schema/config pressure run. It compares against the recorded 3,886.9 MB baseline. The freshly measured parent was 3,724.8 then 3,727.9 MB, so the actual paired ratios were **1.309× and 1.326×**, above the advertised 1.3× comparison despite remaining below the fixed cap. Calls grew approximately 1.19×, reads 1.051×. The hub row alone nearly doubles, but the accepted rule explicitly judges the sequence rather than moving work between rows.

To distinguish unchanged main-code work from requests after a program edit, a second test made **no definitions or redefinitions**, warmed each form once and measured five requests. First-pair medians:

| Unchanged-program form | MB baseline → HEAD | Calls baseline → HEAD | Reads | Wall ms baseline → HEAD |
|---|---:|---:|---:|---:|
| `nil` | 16.2 → 18.4 | 6,210 → 6,908 | 42 → 42 | 19.1 → 20.1 |
| `(seon.print/form-text '(a 1))` | 16.6 → 18.8 | 6,236 → 6,934 | 42 → 42 | 18.0 → 21.4 |
| Function-count `seon.db/q` | 17.4 → 19.6 | 6,271 → 6,974 | 43 → 43 | 20.5 → 22.1 |

The second pair confirms the increase: `nil` 6,195 → 6,908 calls and 16.2 → 18.7 MB; small-form printing 6,221 → 6,934 calls and 16.6 → 19.2 MB; count query 6,256 → 6,974 calls and 17.3 → 20.0 MB. Thus **ordinary unchanged work did get heavier**, by about 11% in calls and 13–16% in allocation. Wall-time medians move with concurrent work and are not a precise latency regression proof.

Profile evidence over five `nil` requests adds 505 calls of `seon.db/loaded-builder`, 500 cache lookups/joins and 505 claims, plus connection-state validation. The baseline read the present builder digest before entering that function; the new owner enters it on every lookup. This explains a concrete part of the extra work, not every allocated byte. Filed separately as [projection builder hit-path cost](../../../seon/issues/the-projection-builder-hit-path-adds-work-to-unchanged-requests.md). The existing desired-cost tasks and accepted overruns remain; no estimate was loosened.

**The 512 MB pressure issue reproduces.** The installed `seon.sci.runtime-test/a-hundred-branches-analyse-each-distinct-digest-once` was green alone in the initial runtime run, then red after the cost/schema/config population: **5,774 analyses for 5,743 distinct digests**, run `633f909c2828`. The ordered measurement namespace runs after schema/config by the runner’s name ordering; it repeats the exact scenario and records the cache around it:

| Measurement | Before | After |
|---|---:|---:|
| Configured cache cap, bytes | 512,000,000 | 512,000,000 |
| Cache weighed bytes | 505,671,061 | 511,996,081 |
| Analysis evictions | 0 | 543 |
| Analysis misses | 1,746 | 8,531 |
| Analysis derivation ms, cumulative | 673 | 853 |

That run made **5,785 analyses for 5,743 distinct rewritten digests** (42 repeated), plus the 1,000 original definitions. The actual runtime answers remained `[0 0 1 2]`. Candidate `f1967b452d4364d23c897ac8b5e1bb1f668bee83`, run `e7a60e6790dd`, named probe `seon.zz-runtime-review3-astra-pressure-test/analyses-under-budget-pressure`. This confirms the [existing eviction issue](../../../seon/issues/analyses-are-evicted-from-a-full-cache-and-analysed-again.md), not a new wrong-key cause. Bounded eviction worked; the universal “each distinct digest once” assertion did not. No OOM or wrong answer was observed here; repeated analysis remains a cost/proof defect.

The pressure suites also reproduce five previously filed reds, outside the changed runtime mechanism: `seon.config-test/the-default-document-has-one-canonical-complete-location`; `seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms`; `seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector`; `seon.schema-test/canonical-reference-values-use-the-pull-collection-grammar`; `seon.schema-test/every-predicate-schema-declares-what-it-accepts`. They are carried by `seon.schema/reds-on-head-1988560d1-in-schema-and-config-tests` and `seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests`. Their boundary is explicitly listed here; none was silently treated as a runtime pass. The declared-long incremental/full projection test was excluded from these suites.

A separate **pre-existing retention risk** is confirmed outside Caffeine’s budget: the projection’s `clojure.core.cache.BasicCache` grows with unique query clauses. Two batches of 1,000 valid `lowered-where` inputs grow that real holder **891 → 1,891 → 2,891 entries**, while central weighed bytes stay **312,188,873** throughout under the **512,000,000** cap. The batches take 14.52/10.37 ms and allocate about 10.3 MB each; retained bytes were not measured and no OOM was induced. The basic holder already exists on `716cc7527`. This is a newly confirmed memory-accounting risk, not a P7 regression or an observed crash. Filed as [projection query retention outside the budget](../../../seon/issues/projection-query-derivations-grow-outside-the-cache-budget.md), candidate `1990f95ed0559d663c3e789f62c9a787f3bcadb2`, run `2dbfa3faf8a1`.

**Boot and adoption: positive but narrower evidence.** Untouched HEAD passed the source reload, evaluator self-reload, old-branch HEAD, stale host-bound refusal locality and predecessor/cold-continuation tests (five tests, 25 assertions, run `545d1514a0d6`). A separate `bin/seon check --at 06614f6a8 --live-shape` booted a clone of default’s actual live store and passed the real-entrance and launch-observation regressions (two tests, six assertions, run `14dbf56332fd`). It copied 63 branch pointers, cloned in 18,424 ms, reached readiness in 47,292 ms, ran the tests in 3,705 ms and finished in 89,201 ms. The live JVM was only asked to copy store pointers; all publication, adoption and tests ran on the disposable clone. No new boot failure or OOM was observed. This supports operational bootability, not the exact-runtime claim disproved above.

## Reproduction and verification boundaries

The [probe patch](runtime-review3-astra-probes.patch) preserves the new falsifiers and controls. Its source/test additions were used only inside candidate archives; they are not installed by this docs commit. Original raw values and check logs are under `tmp/runtime-review3-astra/`; the important values and run identities are quoted here so evidence survives that directory.

The consolidated evidence object is `1f7c0685081775bcf7d889cc6514c0288f38f7de` (parent `06614f6a8`). Recreate it from the preserved patch in a disposable checkout or private index; do not apply it to the shared working tree. Create `tmp/runtime-review3-astra/out` at the repository root before running its probes. The counterexamples were executed in these separate candidates:

```sh
bin/seon check --at 172f18537a2df6df97adbabe25e6bf4aec5da6c3 --test seon.runtime-review3-astra-new-test
bin/seon check --at 8dbf87ca612fe7caf807884e51ecaa1424773758 --test seon.runtime-review3-astra-extra-test
bin/seon check --at 84f7fb4dd309dca3ff587987c73b8dc85a3047dc --test seon.runtime-review3-astra-real-test
bin/seon check --at 52780d2fa7a3058a29482e52eed847a6b06f7b98 --test seon.runtime-review3-astra-real-test/a-protocol-extension-is-local-to-an-acquired-fork
```

For cost repetition, use the three `review3-*` cost tests in the patch with `R3_LABEL=head` or `base`. For the baseline, copy the complete candidate `test/seon/cost_estimate_test.clj` onto `716cc7527` in a dangling commit, so both revisions run the same sequence. The profile uses `seon.profile/measure` over five warmed MCP requests; the cost rows use the request’s own retained cost handle.

All own check JVMs were run sequentially. Other lanes' concurrent checks make wall time unsuitable for a precise speed claim; allocation, instrumented call counts and reads are the primary comparisons. No hangs or OOMs were observed in these finite probes. That is not proof that every program terminates or stays within memory.

`clj-kondo --lint src script test --cache false`: **0 errors**, 1,861 warnings, 8,650 ms. Unrelated existing worktree changes, deleted historical patch files and modified submodules were not included in any candidate. Invalid initial probe candidates (one syntax error, one incomplete baseline test transplant) were rejected before tests and corrected; they are not counted as HEAD failures.

At the final check, default still had PID 11460 and the same start instant. Source, tests, resources, operator code and dependency pins were unchanged from `06614f6a8` through the subsequent docs-only HEAD. No shared source or test file was edited; source/test line delta is **0/0**. The evidence patch applies cleanly to the reviewed index, and this report’s local links resolve.

The docs-only commit uses `bin/lane commit review3-astra` with these previously green tests named individually: `seon.sci.runtime-test/a-composed-runtime-answers-what-its-own-source-answers`, `seon.sci.runtime-test/a-derivation-is-keyed-by-its-runtimes-whole-delta`, `seon.sci.eval-test/a-runtime-derivation-is-keyed-by-its-own-program-through-every-entrance`, `seon.sci.eval-test/an-agent-change-is-live-through-its-macro-callers-and-keeps-namespace-rows`, and `seon.sci.eval-test/a-changed-runtime-keeps-the-process-launch-observations`. No accepted-red option is used. The intentionally red falsifiers remain documented evidence, not installed tests hidden from a claimed green source change.
