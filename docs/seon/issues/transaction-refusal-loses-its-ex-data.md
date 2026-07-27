---
type: issue
status: open
severity: blocker
tags: [issue, database, testing]
---

# A refused transaction needs value-based classification at the transact wrapper

## Problem

Datahike's writer runs a transaction on its own thread. A rejected transaction
reaches the caller as a wrapper `ex-info` whose `ex-data` is empty, followed by
an `ExecutionException`, followed by the original throwable. The refusing
transition's `ex-data` is therefore recoverable, but a caller that inspects only
the outer throwable—or only its immediate cause—misclassifies every refusal.

The fresh tree does not yet have the N3 transact wrapper that walks this cause
chain and returns a flat error value. Until it does, a correct fence can still
be counted as equivalent to an unrelated transaction failure by tests that
treat every throw as a refusal.

## Evidence

Probe D in
`docs/prds/sci-execution-runtime/research/n3-plan-2026-07-27.md` §8 walked the
complete cause chain:

```text
:REFUSAL-CHAIN
 [{:class clojure.lang.ExceptionInfo, :ex-data {}}
  {:class java.util.concurrent.ExecutionException, :ex-data nil}
  {:class clojure.lang.ExceptionInfo,
   :ex-data {:seon.error/kind :probe/refused, :probe/rule :ineligible}}]

:CAS-CHAIN    [... {:ex-data {:error :transact/cas, ...}}]
:SCHEMA-VIOLATION-CHAIN [... {:ex-data {:error :transact/schema, ...}}]
:ASYNC-REFUSAL-CHAIN    [... {:ex-data #:probe{:rule :async}}]
```

The transition's own data is intact at the third link. Datahike's
`throwable-promise` deref
(`reference-code/datahike/src/datahike/tools.cljc:93-107`) wraps the
`ExecutionException` in a fresh `ex-info` with empty data; it does not discard
the original throwable or its data. Datahike's own CAS and schema rejections
are equally distinguishable by value. No fork change or message parsing is
required.

## Impact

`test/seon/cluster/run_test.clj` currently catches the outer exception:

```clojure
(catch Exception e (or (ex-data e) {::opaque (ex-message e)}))
```

The outer `(ex-data e)` is `{}`, which is truthy, so the `::opaque` branch is
dead. The state-machine property then compares only with `::committed`; a
schema error, uninstalled attribute, or other unpredicted failure can count as
the expected refusal and leave the suite green for the wrong reason.

## Owner

The N3 transaction boundary: `seon.cluster.store/transact!` and its pure
cause-chain classifier. The wrapper walks to the deepest non-empty `ex-data`,
returns the transition's refusal as a flat `:seon.error` value, classifies
Datahike rejections such as `:transact/cas` and `:transact/schema`, and treats
an unclassifiable failure as a core fault.

No Datahike fork change, sentinel transaction shape, or message parsing belongs
in this fix.

## Acceptance

- `seon.cluster.store/transact!` returns the transaction report on commit and
  never throws a classifiable transaction refusal into the run loop.
- Its cause-chain walk returns the deepest non-empty `ex-data`, preserving the
  refusing transition's own map and distinguishing Datahike rejection data.
- An unclassifiable transaction failure is a
  `:seon.db/unknown-failure` and follows the development panic / production
  degradation policy.
- The dead `::opaque` branch is gone, and at least one N2 property asserts the
  specific refusal rule predicted by the model while independently proving the
  database was unchanged.

## Notes

Corrected 2026-07-27 from the complete cause-chain evidence in the N3 plan §8.
The `datahike` and `clojure-testing` skills carry the same cause-chain guidance.

## Triage 2026-07-27

- **N3-OWNED.** The N3 transaction-boundary contract owns
  `seon.cluster.store/transact!` and the cause-chain classifier; fresh
  `src/seon/cluster/store.clj:317-345` still has no application transaction
  wrapper, and `test/seon/cluster/run_test.clj:73-81` still catches only the
  outer exception data.
