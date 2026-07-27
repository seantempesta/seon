---
type: issue
status: open
severity: blocker
tags: [issue, test, agent]
---

# Give the run state-machine property one database per trial

## Problem

`transitions-agree-with-the-model` in `test/seon/cluster/run-test` cannot pass
under any implementation of the six `seon.cluster.run` transitions. The pure
model resets to `{:runs {} :pointers {} :receipts {}}` at the top of every
`clojure.test.check` trial and every shrink step, but `with-model-database`
creates the connection OUTSIDE `tc/quick-check`, so one database carries every
trial's durable facts. From trial 2 onward the oracle is reasoning about an
empty world while the database still holds trial 1's runs, and the two
requirements contradict each other:

- the oracle says `[:open "r1" "a1"]` must COMMIT, because its model has no
  `r1`;
- `open-call`'s sealed contract says it must REFUSE, because `r1` exists.

The suite is byte-sealed, so the implementation lane could not repair it.

## Evidence

Run at `ba5cb0c1e` (`bin/test seon.cluster.run-test seon.flow.loop-test` →
`Ran 10 tests containing 59 assertions. 1 failures, 0 errors.`). Every printed
disagreement carries an empty model:

```text
ORACLE DISAGREEMENT {:command [:receipt r1 0 :error],
                     :expected :refuse,
                     :actual :seon.cluster.run-test/committed,
                     :model {:runs {}, :pointers {}, :receipts {}}}

```

The shrunk counterexample is the single command `[[:open "r1" "a1"]]` — a
one-command sequence that can only fail on leaked state.

`[:receipt "r1" 0 :error]` is decisive: the test's own `execute!` transacts
that receipt map directly, touching none of the six transitions. It commits
because the leaked `r1` resolves its lookup ref, while the oracle demands a
refusal. No change to `seon.cluster.run` can affect that outcome.

The falsifier: `tmp/run-property-isolation-probe.clj` re-runs the SAME
generators, oracle, `model-apply`, `execute!` and invariants resolved out of
the sealed namespace, changing exactly one thing — the database is created per
trial:

```text
PER-TRIAL-DATABASE PROPERTY RESULT: {:pass? true, :num-tests 60, :seed 20260727}

```

60/60 with the seeded generator. The transitions agree with the model; the
harness leaks state.

## Owner

The contract author of `test/seon/cluster/run_test.clj`. The fix is one
structural move — put `with-model-database` (and the agent seeding it already
does) INSIDE `prop/for-all`, so the database's lifetime matches the model's.
Nothing in `src/seon/cluster/run.cljc` changes.

## Acceptance

- `bin/test seon.cluster.run-test` reports 6 tests, 0 failures, 0 errors.
- Each trial and each shrink step starts from an empty database, so a shrunk
  counterexample is reproducible on its own.
- `tmp/run-property-isolation-probe.clj` is deleted once the sealed suite
  itself carries the isolation.
