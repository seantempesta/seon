---
type: research
status: complete
tags: [testing, agents, datahike, delegation]
---

# Delegation precondition P2 proof

## Dependency ledger

- Clojure 1.12.5 `clojure.test/report` and `run-tests`, grounded in
  `reference-code/clojure/src/clj/clojure/test.clj`: the dynamically bound
  reporter sees begin-test-var, pass, fail, error, and summary events.
- Datahike at `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`,
  grounded in `reference-code/datahike/src/datahike/db/transaction.cljc`: one
  transaction resolves the test, namespace, run, result, and component failure
  tempids and enforces the namespace-owner unique-value ref.
- First-party owners: `bin/test` is the JVM gate boundary;
  `seon.test.runner` captures and commits result facts;
  `seon.cluster.agent/owner-of` resolves namespace ownership.

## Landed shapes

Normal `bin/test` retains its original require-and-run path. The sink exists
only when `--result-cluster NAME` or `SEON_TEST_RESULT_CLUSTER=NAME` is
explicit, and both the script and effectful owner refuse `default`.

Each opted-in invocation commits:

- one `:seon.test.run` carrying identity, instant, and Git SHA;
- one stable `:seon.test` row per discovered deftest, referencing its
  `:seon.ns` row;
- one `:seon.test.result` referencing that test and run with `:pass` or
  `:fail`; and
- for a failing result, one component `:seon.test.failure` referenced by the
  result and carrying the failure message.

## Live proof

The final boundary was run into project-local
`tmp/test-result-proof-final-20260729/`:

```sh
bin/test --result-cluster delegation-proof-final \
  --result-root tmp/test-result-proof-final-20260729 \
  seon.test-runner-failure-fixture
```

It ran two tests, reported one assertion failure, and exited 1. Reopening the
same named cluster, assigning the fixture namespace by ordinary transaction,
and querying the result graph returned:

```clojure
{:owner "test-fixture-owner",
 :join
 #{["seon.test-runner-failure-fixture/failing-example"
    "test-fixture-owner"
    :fail
    "the deliberate broken-test evidence\nexpected: (= 5 (+ 2 2))\nactual: (not (= 5 4))"
    #inst "2026-07-29T01:11:35.277-00:00"
    "d6bf329ae6a29830271a203f3a4dbdfde3138146"]}}
```

This is the required live join:
test result → exact test → namespace → assigned agent. The fixture file does
not match the full gate's `_test` discovery suffix and runs only when selected
explicitly.
