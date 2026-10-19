# First definition on a branch: measured work and proposed patch

Status: applied by lane runtime-fix3 onto `959ec1a91` (the commit that carries this line), proofs red on HEAD and green with the exactness rows; re-measured there: first-defn 1,192 -> 269 MB, 80,872 -> 21,972 calls, 1,200 -> 516 ms, every other cost row within 1%.

2026-10-20, lane `defn-perf`. Default remained on `ac55beffa4840f73b3fea3350b89d8b7b1dd5215`; it was never restarted or loaded with uncommitted source. All default probes used JVM mode with root and cluster explicit. Patch base: `f9dbede9a` (its runtime source is the reported ac55beffa source). The only submitted files are this evidence and [defn-perf.patch](defn-perf.patch). Runtime-fix3 owns the overlapping source; this patch is not installed in the shared tree.

Remaining limits: the desired first-defn estimate is still red, and the live-store hub-change bound fails on both parent and candidate. The focused template check is green; the live-store cost gate is not.

The wrong work has two owners. `seon.sci.eval/bind-analysed!` analyses and binds all 861 open functions on the first changed branch, although every branch needs the same definitions. `seon.sci.eval/plan-identities` performs six scalar identity reads per changed entity, including entities that are not functions, namespaces or tests. The proposed composition binds the shared open analyses in `seon.sci.eval/acquire-program!` and uses `seon.db/pull-many` for identities. No new cache, registry or evaluator is introduced.

## Expected cost and remaining failure

A fresh agent's first `(defn sentinel-probe-inc [x] (inc x))` requires a Datahike branch pointer, an SCI fork, one form's analysis and contract processing, about thirty program datoms and the changed name's reaching tests. A new unreferenced name has no reaching test. The cost should follow that form and its reach, not the number of open functions or earlier changes on main.

The added `:first-defn-on-a-branch` row in `seon.cost-estimate-test/estimates` budgets **150 ms, 32 MB, 4,000 calls and 80 reads**, by combining the existing fresh-agent and new-definition operations with modest headroom: 12 + 16 MB, 1,500 + 2,500 calls, 30 + 40 reads and 75 + 60 ms. It is measured before the table modifies main. The desired estimate still fails: the paired candidate is **590 ms, 266.8 MB attributed allocation, 22,014 calls, 409 reads**. This patch removes the demonstrated repeated work; it does not claim to finish the remaining cost problem. The existing task `seon.repl/the-first-requests-on-a-new-agent-branch-are-sub-second` carries the overrun. The accepted-overrun values are reduced to the candidate's measured allocation/calls/reads, rather than preserving the parent's much larger allowance.

The first request still spends 121.7 ms in custody, 132.4 ms in test-step, 58.5 ms in settle and 32.9 ms in derive in the template check. Open value initializers and historical overlay derivation remain. The estimate stays visible as red in the desired-cost table; the accepted-overrun test checks that this remaining debt does not grow.

## What the reported 805 ms and 257,109 calls do

Root's reported sentinel was 1,511 ms, 257,109 calls and 10,982 reads; evaluate was 805 ms. Repeating the first-definition operation after default's analyses were cached gave 398.5 ms, 206,164 calls and 10,956 reads, with evaluate 39.7 ms, custody 107.9, derive 16.3, settle 74.6 and test-step 118.1. This separates the cold analysis cost from the live store's persistent read amplification.

The loaded runtime contains 4,491 function rows, 7 macros and 410 value rows. Of the functions, **861 are open**, requiring runtime name resolution. The cold check's first request calls `seon.sci.runtime/analyse` 861 times and `seon.sci.runtime/analysed` 1,722 times: two binding rounds, the second finding the analyses cached. A later fresh branch still performs 861 lookups and bindings. `seon.sci.eval/runtime-rows!` and `seon.sci.eval/reach-index` have zero calls during those measured requests. The evidence does not support whole-program row pulling or reach-index reconstruction as the 805 ms cause.

There are 11 open values in this loaded revision (the earlier review counted 12):

- `seon.effect/error-output-validators`
- `seon.instrument/loaded-var-generator`
- `seon.instrument/preparing-wrapper-class-prefix`
- `seon.problems/families`
- `seon.test.runner/loaded-identity`
- `seon.test.runner/var-generator`
- `seon.test/acquire-context!`
- `seon.test/attempt-writers`
- `seon.test/release-context!`
- `seon.test/routing`
- `seon.test/seed-cluster-facts!`

Their evaluation is retained when the runtime changes. The patch seeds the shared program snapshot with an empty changes map so acquiring it does not replay these initializers. Existing changed-value, macro and process-observation tests prove the retained behavior.

Default's overlay identity calculation sees **1,397 changed entities** against the loaded source commit. Its result names 530 functions, 88 namespaces and 183 tests. The old implementation performs `1,397 × 3 identity attributes × 2 database values = 8,382` scalar reads. An observed first request makes 9,891 `seon.sci.eval/entity-value` calls overall: the remaining 1,509 are in place derivation. This is historical-delta work, not the single new definition's size. It accounts for much of the persistent call amplification; the raw 14× comparison also spans different runtime implementations and stores, so it is not attributed wholly to analysis.

A read-only pair on the *same held immutable default database values* returned equal identity maps:

| Identity operation | ms | allocated bytes | calls | reads |
|---|---:|---:|---:|---:|
| Scalar `plan-identities` | 150.70 | 100,713,840 | 120,561 | 8,382 |
| Two `pull-many` operations | 72.03 | 40,746,744 | 119 | 2 |

This patch leaves identity discovery proportional to the changed entities. It removes scalar instrumentation/read overhead; it does not eliminate the historical delta itself. The two pulls read the before and after values, preserving additions, removals and identity changes. Missing identities are omitted just as before.

Exact default probe, with output handle `ee91bbe427449`:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      loaded (#'seon.sci.eval/loaded-program database)
      entities (into #{}
                     (comp (mapcat #(apply concat
                                     (seon.db/datoms-diff loaded database :aevt [%])))
                           (map first))
                     @#'seon.sci.eval/plan-attributes)
      old (seon.profile/measure
           #(#'seon.sci.eval/plan-identities loaded database))
      batched (seon.profile/measure
               #(let [attributes [:seon.fn/sym :seon.ns/name :seon.test/sym]
                      rows (mapcat (fn [basis]
                                     (seon.db/pull-many basis attributes (vec entities)))
                                   [loaded database])]
                  (into {} (map (fn [attribute]
                                  [attribute (into #{} (keep attribute) rows)]))
                        attributes)))
      figures #(select-keys % [:seon.profile/ms :seon.profile/alloc-bytes
                              :seon.profile/calls :seon.profile/reads])]
  {:entities (count entities)
   :same (= (:seon.profile/value old) (:seon.profile/value batched))
   :names (update-vals (:seon.profile/value old) count)
   :before (figures old) :after (figures batched)})
```

The exact first-definition request is also committed in `seon.cost-estimate-test/first-defn-profile` in the patch. It runs through `seon.mcp`, uses `seon.profile/measure`, reads the request's own cost handle and records selected per-function call counts. Profile counters from an outer default JVM measurement also include concurrent flow activity; only the request's own cost is used for headline request totals.

## Paired throwaway checks

Parent with probe: `79f9d95d0e7e568bf2daceb605e72ea1a5ec7a00`, run `3bdfc4e2362c`. Candidate with identical probe: `7ebb129e30c1fbf0aa3fabbe066ac6d6b3a9a48d`, run `d5d7c992eaff`. Both were sequential `bin/seon check --at SHA --test seon.cost-estimate-test/first-defn-profile` runs, with no overlapping test JVM owned by this lane. Each uses three fresh agents, then a fourth agent with two warmups and five arithmetic requests. Other lanes may work concurrently; allocation and calls support the wall-clock result.

| Operation | Parent ms | Candidate ms | Parent calls | Candidate calls | Parent reads | Candidate reads |
|---|---:|---:|---:|---:|---:|---:|
| First fresh branch, cold analysis | 1,231.6 | 590.0 | 81,239 | 22,014 | 413 | 409 |
| Second fresh branch | 176.4 | 168.1 | 28,708 | 17,072 | 221 | 199 |
| Third fresh branch | 154.3 | 135.0 | 28,679 | 17,307 | 221 | 199 |
| Warm arithmetic, median of five | 21.8 | 21.7 | 7,034 | 6,913 | 42 | 42 |

| First request stage | Parent ms | Candidate ms |
|---|---:|---:|
| evaluate | 781.7 | 150.0 |
| custody | 110.1 | 121.7 |
| derive | 27.4 | 32.9 |
| settle | 66.1 | 58.5 |
| test-step | 153.6 | 132.4 |

Form allocation is **482.4 → 30.9 MB**. Separately attributed step allocation is **656.0 → 186.7 MB**. Adding the cost value's form, step, entrance, writer and commit allocation gives **1,188.9 → 266.8 MB**. These are allocations, not retained heap or RSS. The next two fresh agents allocate about **176.6 → 139.5/139.9 MB**. The headline sentinel's form-only allocation must not be compared to the cost table's aggregate allocation.

**Cost moved, not erased:** the shared context now acquires the 861 analyses before the first measured request. Its cache reports **572 ms and 16,090,368 retained bytes**, with 861 misses before the first request and no additional hits or misses after it. Parent cold analysis reports 648 ms during the request. The check's ready time was 9,057 → 11,182 ms, so this is not evidence of cheaper boot. Reuse is the benefit: one shared program context, with the same roots inherited by its forks, rather than an analysis/cache-lookup pass on each changed fork. A new shared program context still pays for binding the open functions; cached analysis content is reused across contexts. Closed functions remain compiled. Open functions in main now use the shared interpreted roots too.

## Live-store verification boundary

The final candidate also booted on a clone of default's live store, with 62 branch pointers, via `bin/seon check --at 49cf24b5c668f9d68c399f45c484a586ad70c610 --live-shape` and the same 20-test selection. Run `851ea554fa09` took 89,227 ms overall, including 17,873 ms cloning, 19,760 ms to ready and 28,451 ms testing. It passed 79 assertions and failed one cost-table assertion, with no errors. The first-defn row passed its reduced accepted overrun; both new regressions and the selected runtime behavior tests passed.

The two red cost rows are `:unmap-one-def` (162.5 MB against 153.9 MB) and `:hub-change-sequence` (5,119.67 MB against 5,053 MB). A parent live-store run of the cost gate, `af9aad745dba`, also fails those rows: 153.9 MB versus the floating threshold 153.89999999999998, and 5,099.23 MB versus 5,053 MB. It additionally fails the new first-defn allowance: 1,234.2 MB and 80,932 calls versus 400.2 MB and 33,021 calls after the existing 1.5× tolerance. These reds are not converted into passes or given wider allowances.

Repeating the parent with the identical 20-test selection, run `c3fd6f07ad88`, gives 74 passing assertions and six failures: the two intended new regressions and the cost gate. The hub row is again red at 5,068.33 MB; unmap is below its bound in that run. Thus the hub overrun predates this patch. The 162.5 MB unmap observation is retained as a failed measurement; it did not reproduce in the subsequent candidate run below, so no stable unmap regression is established. The fresh-defn call allowance fails on the parent even after analysis is warm (39,349 calls versus 33,021).

Repeating the candidate with the same 20 tests, run `a517888e64a9`, again passes 79 assertions with one failed cost-table assertion and no errors. Only the hub sequence is red, at 5,086.19 MB versus 5,053 MB; unmap and first-defn pass. Across the paired live runs, the hub measurements range from 5,068.33–5,099.23 MB on the parent and 5,086.19–5,119.67 MB on the candidate. The evidence establishes a pre-existing bound failure and does not establish a stable improvement or regression for that sequence.

The clone's candidate first-defn profile was 219.3 ms, 18,347 calls and 217 reads after the other selected first-defn test. That is a warm-analysis observation, not the cold paired comparison above. Publishing the candidate onto the cloned store also changes its loaded baseline: the check does not preserve default's 1,397-entity gap. Therefore the batched identity improvement on that large gap is proven by the same-immutable-values default probe, while the endpoint gain and runtime semantics are proven by the throwaway requests. No exact patched-default replacement for root's original 1,511 ms sentinel is claimed.

## Warm settle

The reported +14 ms does not reproduce consistently. Three warmed default requests were 51.8, 71.1 and 55.4 ms overall, with settle **36.8, 43.9 and 37.9 ms**. Median settle 37.9 ms is close to the 2026-10-17 baseline's 37.4 ms. Commit CPU was 13.69/11.49/11.24 ms and writer CPU 6.30/10.03/7.91 ms. The paired isolated checks give median settle 11.1 → 11.0 ms. No warm-settle fix is claimed. Existing [projection-builder hit-path evidence](../../../seon/issues/the-projection-builder-hit-path-adds-work-to-unchanged-requests.md) records additional unchanged-request work; it does not establish a stable 14 ms cause here.

## Library seams and proof

- SCI at `33039c6e49b47096a68d2964db9be382525bf75e`: `sci.core/fork`, with Seon's existing inherited binding copy, and `sci.core/bind-root!`. Inputs are the shared context and analysed roots; forks inherit the shared roots, later changed roots remain in their runtime. The existing program acquisition/cache owns recomputation.
- Datahike at `ccba220be1556a5f9b7fde2fc7620d0e0c019e0e`: `datahike.pull-api/pull-many`, reached through `seon.db/pull-many`; same immutable database and identity attribute selector, aligned results, absent entities represented as nil. Two reads of changed entities replace six instrumented scalar reads per entity.
- Malli at `dd13ff81139159778edb0a67b491eccc0baa991d`: `malli.core/validator` and `malli.core/-instrument`. Both changed functions retain their complete existing contracts. The candidate is published and booted with their consumers by `bin/seon check`.

Parent with final tests: `9d4f8272612f9061aea59487331021604118a685`. Final patch candidate: `49cf24b5c668f9d68c399f45c484a586ad70c610`. The final test-only changes after the paired measurement read reply items by content, correct the main-runtime comment and ratchet the accepted allocation/call/read overrun down; source is identical to the measured candidate.

Red proof, run `a8dc5e656760`: two tests, four passing assertions and five failing assertions, zero errors. `seon.cost-estimate-test/a-first-defn-inherits-the-shared-program-analyses` sees 1,722 analysis lookups on the parent. `seon.sci.eval-test/overlay-identities-read-two-projections-of-the-changed-entities` sees 6 and 120 scalar reads for 1 and 20 changed definitions, and zero batched pulls. Both check the resulting values as well as work.

Focused green run `cfbcfdfc82c8` on the measured source: **20 tests, 80 assertions, zero failures/errors**, 22,904 ms test time, 49,611 ms check total. The tests below reach both changes; long tests are named individually. `seon.sci.runtime-test` contains the pure runtime-analysis tests.

```sh
bin/seon check --at 49cf24b5c668f9d68c399f45c484a586ad70c610 \
  --test seon.cost-estimate-test/first-defn-profile \
  --test seon.cost-estimate-test/a-first-defn-inherits-the-shared-program-analyses \
  --test seon.cost-estimate-test/no-ordinary-operation-grows-past-its-accepted-overrun \
  --test seon.sci.eval-test/overlay-identities-read-two-projections-of-the-changed-entities \
  --test seon.sci.runtime-test \
  --test seon.sci.eval-test/a-changed-runtime-keeps-the-process-launch-observations \
  --test seon.sci.eval-test/a-branch-changed-macro-is-expanded-by-its-callers \
  --test seon.sci.eval-test/a-branch-changed-value-a-macro-reads-is-expanded-by-its-callers \
  --test seon.sci.eval-test/an-agent-changed-value-a-macro-reads-is-expanded-by-its-callers \
  --test seon.sci.eval-test/a-branch-derivation-is-keyed-by-its-runtime-in-either-order \
  --test seon.sci.eval-test/a-runtime-derivation-is-keyed-by-its-own-program-through-every-entrance \
  --test seon.sci.eval-test/no-src-function-a-change-can-reach-runs-its-compiled-var \
  --test seon.sci.eval-test/no-agent-work-writes-a-program-context
```

Candidate archive lint: `clj-kondo --lint src script test --cache false --parallel`, **6,523 ms, zero errors, 1,864 warnings**. Warnings are not presented as zero findings. Earlier prototype failures were in this lane's new fixture (an unregistered namespace) and measurement order (measuring the first defn after the cost table had changed main); both were corrected, then the green run above proved them. Two exploratory default requests returned errors before correction: an invented profile binding (`ea6d713551087`) and passing a map where base-ctx requires a database (`e4bc992ece8e2`). Neither loaded uncommitted source.

Additional context checks on the final candidate, run `b55ec4e70250`, executed 12 tests with 58 passing assertions and three failures across two tests. The cost gate and both new regressions passed. The same selection on the parent, `b87dc32ffb3a`, has 52 passes and nine failures across five tests. The two additional red tests predate this patch:

- `seon.sci.eval-test/a-program-context-weighs-its-own-declarations`: parent measured one-function overlay retained size 920,416 bytes against an estimate of 186,176 bytes; candidate measured **90,488 bytes**, while the unchanged estimate now slightly exceeds the test's 2× allowance (180,976 bytes). Its five-second bound also fails: 11,030 ms parent, 6,900 ms candidate. The base-context weight assertion passes on both. The measurement shows the shared-root retention benefit, but the existing weight estimator/test is not green and is not loosened here.
- `seon.sci.eval-test/an-unchanged-program-context-is-derived-once-and-reused`: both revisions return a different context object from the one retained at the expected base key. This is an existing identity/cache assertion failure.

The additional green behavior checks are `seon.sci.eval-test/an-overlay-plan-reads-the-branch-digests-by-index-range`, `seon.sci.eval-test/a-work-branch-interprets-exactly-its-head-against-the-loaded-commit`, `seon.sci.eval-test/a-fresh-fork-of-an-unchanged-program-acquires-from-the-base`, `seon.sci.eval-test/branches-without-program-changes-add-no-overlay-entry`, `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`, `seon.sci.eval-test/a-program-derived-from-its-predecessor-equals-its-cold-derivation` and `seon.sci.eval-test/a-program-context-does-not-depend-on-who-derives-it`. Their names, rather than the entire eval-test namespace with pre-existing reds, define the additional check selection.

Final candidate green check, `cb232211c9b0`: **10 tests, 50 assertions, zero failures/errors**, 27,219 ms test time and 57,197 ms check total. This is the final candidate's reduced accepted-overrun gate, both new regressions and the seven additional green context checks listed above; the two reproduced pre-existing context reds are excluded by var name.

The desired-cost table was also run explicitly: `seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`, run `13e2c506b3c4`, is red as intended for the remaining debt. Its new first-defn row observes **512.6 ms, 264.3 MB, 22,380 calls and 409 reads** against the 150 ms / 32 MB / 4,000 calls / 80 reads estimate (the existing wall-time slack makes the enforced wall threshold 450 ms). The other 14 existing operation rows also exceed their desired estimates. This is separate from the green accepted-overrun gate; the target has not been silently weakened.

The patch applies to committed HEAD `47f16e5e6` using a private index (`git apply --cached --check`); no shared source or shared index was changed.

Patch size: source +17/−11 lines (net +6); tests +73/−5 (net +68).

The broad tiers and integration with runtime-fix3 are outside this patch-only assignment. Default had seven open recorded faults, four malformed contracts and 63 errors in the installed health summary at entry; no claim is made that it is healthy. Shared foreign changes, including runtime-fix3's `src/seon/sci/runtime.clj`, are excluded from every candidate; HEAD plus this patch is the verification boundary.
