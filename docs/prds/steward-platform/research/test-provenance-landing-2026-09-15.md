---
type: research
status: active
date: 2026-09-15
tags: [test, provenance, steward-platform]
---

# Test provenance — slice 1

**RESET NEEDED — `131fa2a56`.** The default cluster retained the earlier
`:db.unique/identity` constraint on `:seon.test.run/program-digest`. A live
installed-schema probe found that constraint on default and no constraint on
current-src. Fresh roots and canonical fixtures use the corrected schema.
The lane did not stop, refork, or reseed default.

Items 1 and 2 are implemented in `131fa2a56` and `0d1f72cd0`. The success
predicate and durable-recording exit contract are the third commit.

## Authority and dependency ledger

Read AGENTS.md end to end, the stewards draft (including §10–11), the steward-platform
README and working edge, and data-audit-a's subject-bound success and test
destination evidence. The owner's explicit lane assignment authorizes this
slice despite the program README labelling the wider ideas unapproved.

- Datahike's writer supplies its current database to `:db.fn/call`:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
  `seon.test.runner/record-tx` remains the sole result transaction builder.
- `force-branch!` checks the expected head and warns that live connections
  become stale: `reference-code/datahike/src/datahike/versioning.cljc:323`.
  `seon.cluster.source/record-results!` uses a private branch, commits through
  `commit-results!`, advances current-src with the existing guard, and releases
  its connection. The generated JVM request invokes this same path.
- `seon.program/canonical-row` owns program attributes; result attributes are
  outside that ownership. The fingerprint extends the source snapshot identity
  only for changed canonical program facts, comparing the current database with
  its sealed basis. Datahike history/since finds the affected rows.

## Storage and preservation rule

Every recorded result links to one immutable run row. Its digest, basis, and
branch describe the tested program, never the recording destination. The
coordinator captures the prepared base's provenance before running tests and
retains its Git SHA. Agent-local runs capture their database program before
execution; Git SHA is absent when no Git identity was supplied.

Every canonical gate selection records, including explicit namespaces and
platform-only runs. The fast iteration path does not record: it has no isolated
published base and is explicitly not durable gate evidence.

Full publication carries run rows and latest results from the expected
current-src commit, not its older :db population base. All original fingerprints
remain unchanged, including for changed or removed definitions. Consequently,
unchanged definitions retain their last evidence; changed programs cannot use
that evidence to certify their new digest. Incremental publication already
forks the expected current-src commit and preserves non-program attributes.

## Initial observations

Default answered MCP JVM and runtime-status probes. At basis 536877865 it had
one recorded test result and no installed :seon.test/run attribute. No lifecycle
operation was performed on default.

The first fast run exposed a pulled-ref output-contract mismatch and excessive
work in the first fingerprint implementation. The ref contract now accepts
Datahike's pulled reference map. Fingerprinting compares only rows touched since
the source seal. A nested snapshot regression copied a hand-selected subset of
runner files; its fixture now copies the tested source/resource roots together.

## Verification

- Item 1 fast snapshot: 2 tests, 14 assertions, zero failures/errors.
- Item 1 isolated selected-path gate: 2 tests, 16 assertions, zero
  failures/errors, exit 0. Snapshot HEAD `a3264ffc473becd00961e847cf5d4cb6e3da880b`;
  preparation 47 seconds, coordinator/tests 43 seconds. Successful root removed.
- Direct MCP fingerprint capture on default at basis 536878827 returned run
  `1d9c5f4f090e`, branch `:cluster-default`, digest
  `005f1b4d968bb7f8521f2af6d2a0046bfa3db3af2ae0de23699cb232a65b7f20`.
  Capture took 4723.310375 ms under concurrent gates; this read did not record a
  test result. The earlier unscoped MCP call exceeded its 20-second bound.
- During commit separation I temporarily removed `verified?` while the source
  test still referenced it. That blocked static analysis and adoption. The
  source test now checks stored provenance directly and has no dependency on
  the later predicate. Manual `bin/seon init --dev default` exited 0 and printed
  `development cluster converged`, publishing commit
  `6aa97ee2-809b-563c-906e-a7e446c3b3b9`, digest
  `8d09e147ea1095f90ccb0b5a8e43b0f8125077a24b22032e24a9ee29a29c1755`.
  No reset or refork was performed. Intermediate source must remain analyzable.
- The older full fast snapshot finished with 64 tests / 639 assertions,
  6 failures, 0 errors. All six were the nested concurrent-runner fixture
  passing directories to `--paths`, which accepts files only. The fixture now
  tests its inherited snapshot directly with an explicit disposable evidence
  cluster. The provenance, full-rebuild, and loop checks in that run passed.

Final combined and platform gates remain pending.

## Destination verification

- Focused fast snapshot: 16 tests / 119 assertions, zero failures/errors.
- Isolated provenance + source gate: 16 tests / 123 assertions, zero test
  failures/errors. Recording initially returned a generic prepl rejection;
  the generated request now returns the underlying recording diagnostic.
- Adoption then printed `development cluster converged` at source commit
  `6aa982f9-11a6-567d-bee6-ee59bf261de7`, digest
  `98211049a9143c5a9f6e9ddf504d65dafa845e25cf108e7f76c64bc839b30bd0`.
- The next explicit gate persisted three linked results to current-src. A
  live MCP query read run `ff5ff6f7e603`, tested program digest
  `772ce1e7640d381636024bdc69e1964f1f2b0dd327efb99d8f0616b43823e094`;
  the stored counts included the gate's one acquisition error honestly.
- That gate hit the existing [parallel published-base acquisition issue](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md):
  run `run.Xi1afN`, base `a553e20e58e20a5f86490f73867be8f6dcc900aed1a8d43b1a39f45c7cdfa948`,
  missing key `480d52e9-c6f5-406e-971b-5957ec295eee` during Konserve migration /
  tiered sync / Datahike connect. Isolated confirmation passed; no earlier
  worker-global drift was detected. The deleting actor remains unknown.
- [The operator status reader still queries the retired branch](../../../seon/issues/operator-test-status-still-reads-retired-results-branch.md).
  It is outside this lane's owned source paths; the issue specifies its regression.

The two later generated-form MCP probes exceeded their 20-second bound.
The canonical gate's successful write and subsequent direct database query,
rather than those timed-out probes, establish live destination evidence.

The immediate narrow gate recheck passed: 3 tests / 27 assertions, zero
failures/errors, successful recording, exit 0 (coordinator 35 seconds).

## Success predicate and restart continuation

`verified?` requires a source-bearing subject, a linked run with the supplied
program digest, positive assertions, and zero failures/errors. Missing subjects
and missing evidence return false; database refusals remain error values.
A live query over run `ff5ff6f7e603` returned passing=true, errored=false,
absent=false in 21 ms. Recording errors now prevent both exit 0 and advancement
of the green selection basis; the error kind and message follow the full tally.

The app restart terminated the earlier combined invocations; their partial
results are not gates. Their logs exposed launcher fixtures omitting the newly
landed `bin/_test-slot`. The owned fixture builder and selected-path fixture now
copy the complete bin directory. Subsequent verification runs sequentially,
respecting the runner's machine-wide slots. No foreign process was operated.

The durable-exit check exposed an existing explicit-result-root bug: the
coordinator always received `seon.operator.root=<run-root>`, overriding the
requested result root at boot. The launcher now supplies the selected operator
root (including the existing data/clusters spelling), and the concurrent-gate
fixture prepares each explicit root with the canonical publication helper.
An explicitly selected cluster still requires a published operator root;
missing source is a recording refusal, now reflected in the process exit.

Latest manual adoption exited 0 and printed `development cluster converged`:
source commit `6aa98c42-b52a-57e3-993d-b7bf512de8dd`, digest
`5de229463576e785951ea4e1e6a8e2080f5f3686966232c36c23fabb7a3136a8`.
The later installed-schema probe showed that convergence did not remove the
old uniqueness constraint; the reset requirement above supersedes the earlier
assumption that adoption alone was sufficient. Earlier retries encountered source changes and
one expected-head refusal; none was an unresolved-name analysis failure.

The nested launcher probe also exposed [a copied parent run claim](../../../seon/issues/nested-test-snapshot-overwrites-its-fresh-run-claim.md).
That snapshot-population defect is recorded separately from the destination fix.

The first final isolated gate ran 64 tests / 630 assertions with zero failures
and one fixture error: `populate-published-operator-root!` passed a File to
`reidentify!`, whose armed contract requires a string. The canonical helper now
passes `(str store)`; its real cached-base branch is covered by the concurrent
launcher regression. `test/seon/test_support.clj` is the additional fixture path.

The subsequent affected fast gate passed 46 tests / 289 assertions. The isolated
four-namespace gate passed 64 tests / 639 assertions at HEAD
`be7d4be584aea7d257fb1edce75bb898166bfcc6` (325 seconds coordinator time).
The platform gate passed 85 tests / 514 assertions at HEAD
`466f562e6cc83574cdcaa2ebd5f0874ffe57e298` (166 seconds coordinator time,
166 seconds slot wait). Both isolated gates recorded successfully and exited 0.
Every invocation used one worker; test invocations were sequential.

## Concurrent result publication — narrowed boundary

The [recording-refusal issue](../../../seon/issues/test-result-recording-refuses-after-branch-head-change.md)
records a real platform recording refusal at HEAD `466f562e6`. It differs from
the expected stale-publication diagnostics printed by the source regression.
`record-results!` reads current-src, forks a private branch, commits through
`commit-results!`, then calls `force-branch!` with that expected head.
Datahike compares the expected and actual commit IDs at
`reference-code/datahike/src/datahike/versioning.cljc:369–377`; either another
result publication or a program publication in that interval can refuse the
recording. The responsible operation in the reported gates is not identified.
No evidence establishes a common cause with the separately reported 30-second
prepl silence refusal.

The real-store preservation regression now interleaves a complete incremental
source publication after the actual result transaction and before its head
advance. It checks the exact expected/actual IDs, the surviving newer head,
absence of the unpublished run, and scratch retirement. An explicit second
recording of the same completion must succeed while retaining the newer source
digest and the original tested provenance. The wrapper injects only the
interleaving; both transactions and both branch operations are real Datahike.
The focused fast recheck passed 14 tests / 116 assertions, zero failures/errors.
Its recording conflict reported expected commit
`6aa9983d-8d9d-57a1-813b-0e88114803d4` and actual commit
`6aa9983f-b82e-56ff-92f8-18edab9687b1`; the explicit reapplication passed.

This narrows the concurrency defect; it does not eliminate contention or add
automatic retries. The guard remains necessary to prevent source loss, and a
refused recording now makes the gate nonzero. A future repair must coordinate
all source/result head advances at the existing branch authority, or rebase
the result transaction under a declared execution bound. Removing the guard
or repeating an already-built candidate would overwrite newer source/evidence.

## Fresh live schema proof

`bin/seon --root tmp/test-provenance-proof-root init` published commit
`6aa996a8-dd8e-5471-9090-de9dae5487d2`, digest
`0d9f99bb27f3e7d1fc5198a8e3c61e106626a71a767fb9d87c46eb9449490f5d`.
`start test-provenance` booted a fresh instrumented cluster. The first init
attempt hit the existing dependency-cache diagnostic issue; the prescribed
`clj-kondo --lint "$(clojure -Spath)" --dependencies --skip-lint --copy-configs`
exited 0, and the later normal operator init completed. No operator internals
were launched separately.

The canonical `juniper_fixture_2026_09_06.clj` installer exceeded the MCP
20-second observation bound. A subsequent explicit database query confirmed
Juniper entity 39460 and all four fixture orders; this is seed evidence, not
proof that the installer's final graph transition completed. Fresh installed
`:seon.test.run/program-digest` had only string/cardinality-one storage facets,
with no uniqueness constraint.

The following JVM form, through MCP with explicit root and cluster, completed
in 1227 ms. It returned two results with 8 passes / 0 failures / 0 errors,
run IDs `6c9fcccb2a2d` and `9c5d136d20c0`, bases 536871045 and 536871046,
branch `:cluster-test-provenance`, and the same tested digest
`08f3e31fcb5e710badf002b6605732edc70765d5b18c40a245d78cd5bc39a4e8`.
`verified?` returned true for that test and false for `absent/test`.

```clojure
(do
  (load-file "test/seon/id_test.clj")
  (let [connection (seon.operator/connection "test-provenance")
        projection (seon.schema/projection-from-database @connection)]
    (seon.schema/call-with-projection
     projection
     (fn []
       (let [test-var (ns-resolve 'seon.id-test
                                  'data-shape-and-explicit-length-determine-identity)
             results [(seon.test/run test-var connection)
                      (seon.test/run test-var connection)]
             runs (mapv #(seon.db/pull @connection '[*]
                                      (get-in % [:seon.test/run :db/id])) results)
             digest (:seon.test.run/program-digest (second runs))]
         {:seon.test/results results
          :seon.test/run-rows runs
          :seon.test/verified
          (seon.test/verified? @connection
            "seon.id-test/data-shape-and-explicit-length-determine-identity" digest)
          :seon.test/absent (seon.test/verified? @connection "absent/test" digest)})))))
```

The scratch cluster was stopped with its root-scoped `bin/seon down`; status
then reported 0/0 clusters alive and no orphan JVMs. Its root was deleted.

## Reproduction commands

The final gate snapshots HEAD plus exactly these owned inputs. Commands run
sequentially, with one worker; the platform invocation follows the named gate.

```bash
paths=(src/seon/test/runner.clj src/seon/test.clj
       resources/seon/schemas/seon.test.edn resources/seon/schemas/seon.test.run.edn
       src/seon/cluster/source.clj bin/test
       test/seon/test_provenance_test.clj test/seon/test_runner_test.clj
       test/seon/test_support.clj test/seon/cluster/source_test.clj)
SEON_TEST_WORKERS=1 bin/test-fast --paths "${paths[@]}" -- seon.cluster.source-test
SEON_TEST_WORKERS=1 bin/test --paths "${paths[@]}" -- \
  seon.test-provenance-test seon.cluster.source-test \
  seon.test-runner-test seon.loop-proof-test
SEON_TEST_WORKERS=1 bin/test --paths "${paths[@]}" --platform
```

Final named gate after the race regression: **64 tests / 648 assertions /
0 failures / 0 errors / exit 0**, including successful persistent recording.
Snapshot HEAD `5ffc491ae833c8d3431ac1183d9bd8f8cc6e3b84`; coordinator time
343 seconds. The runner removed successful root `run.IY0C0d`.

Final platform recheck: **85 tests / 523 assertions / 0 failures / 0 errors /
exit 0**, successful persistent recording, the same snapshot HEAD. Coordinator
time 168 seconds; slot wait and cached-base preparation were both 0 seconds.
The runner removed successful root `run.zKx13K`. This includes
`selected-paths-overlay-head-for-preparation-and-every-worker` with its complete
bin-directory fixture and the nine new head-conflict assertions.

All lane shells completed. The fresh proof root and lane scratch logs were
removed after recording the evidence here. No default lifecycle operation,
foreign session operation, worktree, or foreign-file edit was used.
