---
title: Idle test-step cost after selection repair
type: research
status: measured
created: 2026-10-15
---

# Idle test-step cost

Lane `test-step-cost`; SCI agent `test-step-idle`. Default runs
`49f7baccef743fd230dd43aa92c48cf57f0796a1`, pid 44152, started 02:03:17 UTC.
This follows [the selection investigation](test-step-cost.md).
All live probes used this lane's own SCI branch and its connection.

## Cause and repair

The idle step passes its recording's empty entity set to
`seon.test/request-changes`. The owner nevertheless issues six collection-bound
Datalog queries over before/after, then `seon.test/definition-digests` issues four
more queries for the resulting empty symbol set. The empty inputs do not make
these query plans empty: they still do index work over the program attributes.

The decisive contrast on the same database: an empty change allocated **62.90 MB**,
whereas supplying one unchanged function entity allocated **4.10 MB**. Thus the
empty case's cost is not proportional to the number of changed definitions or
tests; the empty collection loses the useful bound on the index work.

Both owners now use `seon.db/pull-many` on the identities they already hold.
Request changes pull exactly the supplied entity ids from both database values,
including reverse namespace-component refs. Definition digests pull the explicit
function/test lookup refs. Missing refs retain the dependency's nil semantics.
The existing behavior comparison, namespace binding-loss rule and schema seeds
remain. There is no empty-input exemption, new cache or change to test eligibility.

The library seam is `datahike.pull-api/pull-many`, pin
`62f94893faf929c9ee37f4188dd88983495c960a`: input-aligned eager results,
well-formed missing refs yield nil, malformed refs remain errors.
The complete existing contracts remain in place; Malli pin
`dd13ff81139159778edb0a67b491eccc0baa991d`.

## Same-input complete producer measurements

The parent callable was retained as `test.step.idle/parent-changes` before
installing the two changed definitions on the agent branch. Each pair runs the
complete `seon.test/request-changes` on the same held database and entity set,
through `seon.profile/measure`. Calls are deltas of the current request's sink,
not JVM-wide counters. Decimal MB.

| Entities | Parent allocation / calls / ms | Change allocation / calls / ms | Returned data |
| --- | --- | --- | --- |
| Empty, first warm pair | 43.377 MB / 752 / 34.79 | 0.290 MB / 226 / 0.73 | both empty vectors |
| Empty, repeat | 62.901 MB / 752 / 49.63 | 0.290 MB / 226 / 0.68 | both empty vectors |
| One unchanged function | 4.105 MB / 752 / 4.05 | 0.432 MB / 230 / 1.07 | both empty vectors |

The repeated empty pair reduces allocation **99.54%** and calls **69.95%**.
The first interpreted invocation of the changed source additionally acquired its
source/context (7.99 MB / 8.89 ms in that probe); subsequent ordinary calls use
the installed definition. The regression checks the first value too, then measures
a repeated idle call, excluding SCI's one-time source lookup from its query count.

Exact forms and replies: [test-step-idle-evidence.json](test-step-idle-evidence.json).
Reproduction form (run after saving the parent callable and installing the change
on the same own branch):

```clojure
(let [db (seon.db/db)
      e (seon.db/q '[:find ?e . :where [?e :seon.fn/sym test.step.idle/amount]] db)
      measure
      (fn [f entities]
        (let [sink (:seon.batch/sink seon.batch/*world*)
              before (seon.batch/executed sink)
              m (seon.profile/measure #(f db db entities))
              calls (merge-with - (seon.batch/executed sink) before)]
          (assoc (select-keys m [:seon.profile/value :seon.profile/alloc-bytes
                                :seon.profile/ms])
                 :probe/calls (reduce + (vals calls)))))]
  (mapv (fn [entities]
          {:entities entities
           :parent (measure parent-changes entities)
           :change (measure seon.test/request-changes entities)})
        [#{} #{e}]))
```

## Whole-step attribution limit

Root reported an idle test-step stage of 274.4 ms and a one-test redefinition
request of 1,624 ms. On this lane's own one-test branch, separate probes measured
branch-test discovery at 16.22 MB / 10.35 ms, stale checking at 4.44 MB / 4.16 ms,
and empty request-change discovery at 58.33 MB / 32.15 ms. A direct full parent
step returned nil while allocating 165.74 MB / 113.37 ms; another own-sink step
sample allocated 76.40 MB / 62.47 ms with queries dominating its self time.
Cache conditions and the branch's definitions differ; these are not additive
attribution of root's exact 274.4 ms.

This commit proves the full changed producer, not a measured replacement of the
installed compiled step. Default's compiled platform still calls its parent
definitions until root reloads. The candidate JVM checks exercise the changed
compiled owners. Branch discovery and stale-result validation remain; this is
not a claim that all idle overhead has become zero.

C14 is a **different mechanism**, the global-cell scope inference identified in
`seon.profile/live-cost-scope-comes-from-the-executed-cell`. It is not responsible
for the empty collection queries and is not changed in this slice.

## Proof

- Red candidate `0c016d74c8a3d8894517b2860f49b07f7e6c30f8`, run
  `93b73ed587da`: 3 pass / 1 fail / 0 errors, 7,163 ms. Empty recording made
  10 query calls, expected zero.
- The same small SCI regression was red (10 calls) on the parent and green with
  the changed branch definitions; latest green reply `result/c1e91fc34956f`.
- Candidate `5a6a6bb8eb051e6166bd5cb7dd5a2810b0fdbb6d`, run
  `dd9ec1374c06`: seven tests, 47 pass / 0 fail / 0 errors, 15,310 ms.
  The final regression additionally checks a removed namespace alias and a schema
  entity; the final lane check runs it with those additions.
- The added alias case rejected candidate `c943d710ed57705428076b51d65a5af8a611569f`,
  run `c44d44f4fb66` (48 pass / 1 fail): a reverse component pull returns its single
  owner as a map, not a sequence. Live pull `result/e6add132f4773` confirmed
  `{:seon.ns/_aliases {:seon.ns/name seon.test}}`. The reader now consumes that
  declared component-owner shape directly. No type fallback was added.
- Full-tree `clj-kondo --lint src script test --cache false`: zero errors,
  1,747 warnings, 12,718 ms before the final test-only additions.

The four filed tests returned by `seon.test/reaching` for both changed owners:

- `seon.test-failure-facts-test/a-recorded-test-renders-its-sites-and-changed-dependencies`
- `seon.test-failure-facts-test/explicit-namespace-completion-commits-with-membership-unknown`
- `seon.test-failure-facts-test/what-made-it-red-names-changed-functions`
- `seon.test.selection-test/definition-content-agrees-across-exploratory-branches`

All four passed. The new recording regression and the two previous selection/
narrowing regressions also passed. No pre-existing red was excluded from this
changed-owner set.

## Foreign boundaries and operating evidence

The edit hook still selects retired agent `test-step-cost` from the lane name.
It returned admission-closed rather than applying these definitions. I explicitly
installed the changed forms through MCP as `test-step-idle`, using rewrite-clj
to extract the exact top-level forms, and used the required host-bound candidate
checks. The hook also reported its contract-compilation advisory
`:malli.core/invalid-schema`; the successful candidate publication/boot/check,
not that advisory, is the evidence.

Other lanes held schema and runtime files. None was edited or used as a reason
to stop; no foreign session was resumed or messaged. Default was already faulted
(20 agent-error signatures, 179 errored receipts, 23 open faults). No restart,
reset, JVM-mode evaluation or root-agent evaluation was performed.
