---
title: Test-step cost, C13 and C14
type: research
status: measured
created: 2026-10-15
---

# Test-step cost, C13 and C14

Lane `test-step-cost`, 2026-10-15. Live archive `350b05df782dc833c93cef95cd87f6ca93ee6ce1`,
default JVM pid 13509, started 01:37:11 UTC. All live evaluations used SCI agent
`test-step-cost`, branch `:agent-6957d4e4a1ea`; no root or JVM-mode evaluation.

## Cause and change

The automatic step already supplies the current request's changed identities through
`seon.turn/run-reaching` to a named `seon.test/run`. Nevertheless,
`seon.test/select` derived changes since the preceding run, scanned historical
program attributes, constructed an as-of program, and walked that extra change set.
The named request's candidates came from its supplied identities anyway. Historical
changes also prevented the existing content-digest comparison from deciding reuse.

The change makes history a dependency only of incremental selection and explicitly
requested `:seon.test/since-previous-run?` narrowing. Removed-file discovery belongs
only to incremental selection. Named requests select their supplied reach and reuse
only when the existing recorded/current reach digests match. No new cache or mechanism.

This is a reduction of unnecessary selection work, not a claim that all of C13 is
resolved. The unchanged contract is `:seon.test.selection/request` to
`:seon.test.selection/result` or its existing errors; Malli's
`malli.core/-instrument` still checks it. Changed-reach execution and unchanged
green/red reuse have behavior regressions.

## Measurements

All allocation below is the calling thread's allocation from `seon.profile/measure`,
unless explicitly identified as the reply's broader step cost. Decimal MB.
Raw exact forms and replies: [test-step-cost-evidence.json](test-step-cost-evidence.json).

| Probe | Parent | Change |
| --- | ---: | ---: |
| Complete selection, same held DB/request, first paired comparison | 923.85 MB, 591.86 ms | 4.16 MB, 3.92 ms |
| Complete selection, second comparison, own sink calls | 1,119.71 MB; 7,998 calls; 969.02 ms | 4.14 MB; 1,562 calls; 5.70 ms |
| Historical change derivations in that request | 1 | 0 |
| Returned selected memberships and provenance | identical | identical |

The second sequence was change → parent → change. Its first change call allocated
26.52 MB and took 23.20 ms, still 1,562 own-sink calls and zero historical derivations.
This avoids presenting a warmed-only comparison as the whole distribution.
The last pair reduces allocation 99.63% and own-sink calls 80.47%.

`seon.profile/measure`'s call/read rankings are JVM-wide. Other agents ran during some
probes. The final call counts above instead subtract `seon.batch/executed` snapshots
of this request's own sink. Allocation is thread-local; wall times are observations,
not test thresholds. The regression similarly counts only its own sink.

Initial full-request reproductions, before the change:

- One new contracted add-tax function, one deftest, one call: request 1,805.6 ms;
  test-step stage 1,111.5 ms; reply step 1,460.8 MB / 1,081.6 CPU ms;
  test body 16.4 ms / 7.4666 MB; writer 5 transactions / 341.65 MB.
- A second new function/test pair: request 1,622.2 ms; test-step stage 1,296.3 ms;
  reply step 2,282.2 MB / 1,185.3 CPU ms; body 15.5 ms / 7.4666 MB;
  writer 5 transactions / 333.39 MB.

The reply's step allocation is not solely the test-step stage. Do not add these
overlapping totals or attribute its entire allocation to selection.

A later automatic request on default still cost 2,697.9 ms, test-step 2,130.3 ms,
step 2,836.2 MB, body 15.4 ms / 7.4669 MB, writer 660.77 MB. This is NOT an installed
whole-request improvement: default still runs the old compiled platform, and the
lane branch now carries the selection edit and its tests. The paired measurement
above is end-to-end for the complete selection stage, using the old callable and
the branch's changed callable on exactly the same immutable input. The isolated
candidate checks prove the changed compiled path. Root's next batched reload is
needed to measure the ordinary automatic request through the installed platform.

### Reproduction of the paired selection measurement

Before redefining `seon.test/select`, retain its callable as `parent-select` and
the request value as `selection-probe` in the scratch namespace. The recorded
request uses the own branch's held database and an earlier own-branch basis:

```clojure
{:seon.db/db held-database
 :seon.test.run/cluster [:seon.cluster/name "default"]
 :seon.test.run/policy :named
 :seon.test.run/evaluator :interpreted
 :seon.test/changed ['test.step.cost.probe/add-tax-cold]
 :seon.test.run/change-basis-t earlier-basis}
```

Then run the following on the same agent after the branch definition is installed.
The evidence file includes the exact executed form and complete values.

```clojure
(let [measure
      (fn [f]
        (let [sink (:seon.batch/sink seon.batch/*world*)
              before (seon.batch/executed sink)
              m (seon.profile/measure #(f selection-probe))
              after (seon.batch/executed sink)
              calls (merge-with - after before)]
          (assoc (select-keys m [:seon.profile/alloc-bytes
                                :seon.profile/ms :seon.profile/value])
                 :probe/calls (reduce + (vals calls))
                 :probe/history-calls
                 (transduce
                  (comp (filter #(= 'seon.test/changed-definition-symbols
                                    (ffirst %)))
                        (map val))
                  + 0 calls))))]
  (mapv (fn [[label f]] [label (measure f)])
        [[:change seon.test/select]
         [:parent parent-select]
         [:change seon.test/select]]))
```

## Remaining C13 costs

The following independent probes locate further whole-program work. They are not
additive attribution of the full request:

- `seon.db/entities-changed-after` on `:seon.fn/calls`, with basis one transaction
  behind the current database, returned an empty set but allocated **282.66 MB** and
  took **138.59 ms**. The corresponding doc attribute cost 31.85 MB / 25.51 ms.
  `seon.test.runner/reach-attribute` uses this owner when advancing its content revision.
- `seon.test/request-changes` with identical before/after values and an empty entity
  set allocated **61.78 MB**, took **33.64 ms**, and made 10 reads. Its queries with
  empty collection bindings still cause index work. An empty symbol query allocated
  11.72 MB versus 0.54 MB for the one supplied entity.
- Warm `seon.test.runner/reach-declarations`: 2.34 MB / 3.65 ms.
  One-test stale check: 4.57 MB / 6.69 ms. One-function reach: 5.21 MB / 4.94 ms.
- The own-branch idle `seon.turn/test-step` probe cost 19.97 MB / 16.47 ms with no
  recording input, versus 63.40 MB / 34.05 ms with an empty recording input.

The Datahike pin is `62f94893faf929c9ee37f4188dd88983495c960a`.
`seon.db/entities-changed-after` supplies an attribute index range to
`datahike.db.interface/batch-search` on history and filters transaction numbers
afterward. It is proportional to historical datoms of that attribute, not recent
changed entities. The dependency exposes index slices; its database diff scans the
two EAVT sequences. I found no native structural index diff to compose here.

Existing tasks retained because this slice does not complete them:
`seon.test/changed-definition-symbols-scans-whole-attributes-since-the-last-run`,
`seon.test/reach-attribute-advances-from-the-recording-report`,
`seon.test/one-test-run-repeats-whole-program-setup`, and
`seon.test/a-first-deftest-in-a-new-namespace-costs-seconds-of-test-step`.

## C14: scope is inferred from another context

The flag is wrong for the reproduced platform calls; their reverse closure is not
being interpreted on this agent. Both before the selection edit and afterward,
none of the five reported platform symbols was in this agent snapshot's
`:seon.sci.eval/interpreted`.

`seon.profile/with-cell` captures only symbol and digest in the request sink key.
`seon.profile/cost-scope` later looks up **all live JVM cells** at that key and
returns context if any context cell exists. The global cell registry contained
both host and context cells for `seon.db/datoms` with the identical digest
`850dd16635d3a52937b225fe1d2214fa79c6bad6a894cf06d95dad9af2a0450b`.
Thus another context changes the classification of this request's host calls.

`seon.batch/cost-diff` then maps that inferred context scope to interpreted; a
missing same-scope sample yields new. This is separate from its existing task
about showing an unchanged digest as new.

Exact final proof, result `result/e6000b6ac9a97`: platform-interpreted is the empty
set and cells contains both scopes.

```clojure
(let [snapshot @(:seon.sci.kernel/program-snapshot (seon.sci.kernel/world-ctx))
      interpreted (:seon.sci.eval/interpreted snapshot)
      symbols '#{seon.db/datoms seon.db/with-declarations
                 seon.test.runner/reach-declarations
                 seon.test.runner/reach-attribute seon.fn/declared-callers}
      digest (seon.db/q
              '[:find ?d . :where [?e :seon.fn/sym seon.db/datoms]
                                     [?e :seon.program/definition-digest ?d]]
              (seon.db/db))]
  {:probe/platform-interpreted (into #{} (filter symbols) interpreted)
   :probe/cells (mapv #(select-keys % [:seon.profile/sym :seon.profile/digest
                                      :seon.profile/scope])
                     (seon.profile/cells-of ['seon.db/datoms digest]))})
```

Filed `seon.profile/live-cost-scope-comes-from-the-executed-cell`. Its producer fix
must carry the executed cell's scope into the sink observation, preserve both
scopes when both run, and delete global inference. This slice does not change
profile keys or their consumers.

## Verification and limits

- Red check on parent plus new test: candidate `14cca79f9ccac40b14537db6fe61ac3af31d4980`,
  run `993bd49ff73e`: 5 pass / 1 fail / 0 errors. The historical derivation count
  was 1 when the regression expected 0.
- Green candidate `e50de5b9f9e46b84fbb435768fca413f6dd61210`, run `643a744609c8`:
  four tests, 22 pass / 0 fail / 0 errors, 11,199 ms total.
  New regression, since-previous-run narrowing, unchanged red reuse, and observed
  script-test eligibility all passed.
- The regression was then changed to read its own sink instead of JVM-wide counters;
  its automatic branch test passed.
- Full `clj-kondo --lint src script test --cache false`: 0 errors, 1,744 existing
  warnings, 8,966 ms. Final repeat after the test edits: 0 errors, 1,745 warnings,
  9,542 ms; the shared checkout advanced during the lane.

### Foreign verification boundaries

The eight tests returned by `seon.test/reaching` for the selector ran on candidate
`d88bad0318343b69fc36e5d4067bb545f3defa28`, run `c36c8ab799cd`:
39 pass / 2 fail / 4 errors, 61,677 ms. Three passed:

- `seon.test.one-request-test/evidence-earned-by-another-loaded-program-is-never-reused`
- `seon.test.scoped-first-run-test/a-first-incremental-request-naming-a-change-selects-only-its-reach`
- `seon.test.selection-test/fileless-sci-tests-use-the-same-selection`

Every red reproduced without this slice on its exact parent
`cd5f0280299312d5cc8c3dab32e7221b5a825459`, run `5e07d09446b1`:
21 pass / 2 fail / 4 errors, 72,884 ms. These are pre-existing boundaries, not passes:

- `seon.cluster.publication-reuse-test/recorded-result-resolves-after-one-file-publication`:
  fixture `populate-input!` promises nil but returns a map.
- `seon.test.one-request-test/a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs`:
  the next request has neither the expected executed count nor passed value.
- `seon.test.selection-test/a-proof-request-runs-only-its-proofs`,
  `seon.test.selection-test/named-selection-reuses-green-members-by-reachable-content`, and
  `seon.test.selection-test/selection-derives-bases-obligations-and-exact-symbol-reach`:
  fixture run admission reports program mismatch, because the tested value has no commit.

The named-selection test's later Var-redefinition assertion assumed historical change
discovery prevents computing a changed test's reach digest. This slice removes that
obsolete assertion and its Var redefinition, retaining its selected/reused data assertions.
Those assertions remain unreached behind the pre-existing fixture failure. The current
digest is now the decision for named requests.

### Probe friction and corrections

Two attempted branch-local `seon.config/apply!` writes failed with missing evaluation
lookup refs, request ids `c95bdbef865a` / `d89e049af155`; configuration was not changed.
Filed `seon.config/a-branch-config-apply-references-an-unrecorded-evaluation`.
A scratch def holding the selection request produced a definition-digest validation
refusal (request `625da3803fd3`) while its runtime value remained available.
Neither error is treated as a successful write. Their request and error identities are
recorded here; the raw JSON retains the paired measurements, C14 probe, and saved failures.

An early direct test-step probe mistakenly used the cluster context-source's main
connection. It only read, but its 2,036 MB / 1,478 ms result is excluded from the
own-branch measurements. Correct subsequent probes replace that connection with
the current world's own connection. Scratch symbol/argument mistakes were corrected;
their failed replies remain in the raw evidence.

Default was already faulted (11 warnings, 19 agent errors, 178 errored receipts,
21 open faults). No restart, reset, foreign file edit, or foreign session action
was performed.
