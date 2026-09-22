---
type: landing-note
status: shelved pending agent-platform track 1.3d commits 1–3
created: 2026-09-22
owner: lane B4 fixture on the open store
tags: [agent-platform, b4, fixture, datahike, evidence]
---

# B4 fixture on the open store — shelved evidence

## Ruling and disposition

Plan commit `ffb1c4872` (from the Astra review `2b1f1911d`) moved this fixture
conversion to commit 4 of the six-commit track in README §4 row 1.3d. Commits
1–3 first install the agent execution seam: overridden/affected acquisition in
`seon.sci.eval`, the one candidate lifecycle entrance, and destination-branch
publication. The fixture must call that entrance; a runner/fast custody bridge
would copy the agent mechanism on the test side and is not authorized.

The implementation work is therefore shelved at
`tmp/b4-c1-partial-2026-09-22.patch`. No production/test implementation commit
was made. Apply and revise the patch only after track 1.3d commits 1–3 exist.

## What the partial implementation established

The partial replaced the copied canonical base with a Datahike branch from the
captured execution commit in the supplied store. The branch connection carried
the captured projection, and `fork-cluster-ctx` used the hosting context handed
through fixture custody. It deleted the copied-base, `Held`, retry/acquire/release,
prewarm and machinery-only test paths. File-backed publication helpers remained
for the platform tier.

Datahike's verified boundary was `reference-code/datahike/src/datahike/versioning.cljc`:
`branch!` creates a head plus roster entry with copy-on-write secondary indices;
`commit-as-db` resolves the immutable commit. A branch isolates datoms, schema,
history, connection and writes. It does not isolate blobs, the branch roster,
GC reachability, OS locks, JVM Vars/classes or SCI objects.

## Timing evidence

Surface: MCP `eval_clj`, JVM mode, cluster `default`, explicit connection from
`(seon.cluster.boot/connection "default")`, hosting projection from the running
instance, writes confined to leased fixture branches. The timing regression
ran eleven branch acquisitions from captured execution commit
`6ab2a129-8457-56d3-a644-3d824bd08398`.

Form (abridged only by replacing the report collector):

```clojure
(do
  (require 'seon.test.fixture-timing-test :reload)
  (let [connection (seon.cluster.boot/connection "default")
        context (:seon.sci.eval/ctx
                 (get @seon.operator.runtime/running-instances "default"))
        projection (:seon.schema/projection context)
        reports (atom [])]
    (seon.schema/call-with-projection
     projection
     #(seon.db/call-with-custody
       {:seon.db/connection connection}
       (fn []
         (binding [clojure.test/report (fn [event] (swap! reports conj event))]
           (clojure.test/test-vars
            [#'seon.test.fixture-timing-test/published-fixture-acquisition-is-bounded])))))
    (select-keys (frequencies (map :type @reports)) [:pass :fail :error])))
```

Observed value:

```clojure
{:first-ms 41.643375
 :later-p50-ms 34.881417
 :later-samples-ms
 [34.985875 46.919292 35.083042 34.881417 34.100042
  34.859333 34.037083 35.041292 37.954916 34.093458]}
;; report frequencies: {:pass 14}
```

An earlier successful run measured `36.924541` ms first and `34.990292` ms
later p50. The recorded acceptance numbers for the track are the later complete
run: **41.64 ms first, 34.88 ms later p50**. This proves live JVM branch-fixture
cost and the regression assertions; it does not prove the not-yet-installed
candidate lifecycle entrance, publication-to-branch, runner recording, cold boot,
or platform execution.

## Expected refusal before commit 4

Focused command:

```text
bin/test-fast --paths test/seon/test_support.clj \
  test/seon/test_support_test.clj \
  test/seon/test/fixture_timing_test.clj src/seon/test.clj -- \
  seon.test-support-test seon.test.fixture-timing-test
```

The pre-1.3d launcher invoked host test Vars without an execution connection.
The partial fixture refused at `with-branched-database` with:

```text
The canonical fixture requires an explicit execution connection.
```

Result: 14 executed, 82 assertions, 3 failures and 10 errors. This is the
intended ordering signal: track 1.3d commits 1–3 must supply the agent lifecycle
entrance before the fixture caller converts. Adding custody to `test-fast` or
the old runner would create the rejected parallel test mechanism.

The same run's published copied base also reproduced the motivating stale-copy
failure: projection construction named retired
`seon.error/facet-counts-agree?`. An isolated HEAD-plus-partial worktree loaded
`(require 'seon.test-support 'seon.test)` successfully; the shared tree load was
separately blocked during the error-facets lane by
`seon.effect.clj:558` resolving absent `error/declared-output-validators`.
Neither foreign owner was edited.

## Changed files in the shelved work

- `test/seon/test_support.clj`
- `test/seon/test_support_test.clj`
- `test/seon/test/fixture_timing_test.clj`
- `test/seon/test/runner_test.clj`
- `test/seon/test_preparation_test.clj`
- `test/seon/incremental_publication_test.clj`
- `src/seon/test.clj` was temporarily edited to remove base prewarming; that B4
  hunk was superseded while other lanes advanced the file and is not included in
  the shelved patch. The current dirty hunk in that file belongs to another lane.

The partial diff was 118 insertions and 978 deletions before shelving. No
`src/seon/test/fast.clj`, `src/seon/test/runner.clj`, or `seon.effect.clj` edit
was made. The disposable verification worktree was removed; no owned shell or
child process remains.
