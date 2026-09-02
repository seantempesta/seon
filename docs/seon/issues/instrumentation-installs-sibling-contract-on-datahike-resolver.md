---
type: issue
status: open
severity: blocker
tags: [issue, schema, instrumentation, datahike, class/p1]
---

# Keep sibling Datahike resolver contracts on their own Vars

## Problem

The instrumented `seon.schema.datahike/resolve-datahike-form` Var applies the
two-argument `resolve-datahike-form-in` contract to its one-argument call. This
blocks ordinary transaction encoding in a freshly started cluster: a valid
Malli form is refused as though it were the projection argument of the sibling
function.

The persistent test-evidence writer exposed the defect only after it correctly
handed its transaction to the live process holding the operator store. It must
not bypass schema validation or the store holder to work around this failure.

## Evidence — 2026-09-02

The isolated operator root `tmp/lane-gate-evidence-root` was initialized and
cluster `evidence` was restarted from the current publication. Calling the
one-argument function in that live JVM:

```clojure
(seon.schema.datahike/resolve-datahike-form
 [:symbol {:seon.db/identity true :seon.search/index :symbol}])
```

reported:

```clojure
{:seon.error/kind :seon.instrument/contract-violated
 :seon.error/message
 "seon.schema.datahike/resolve-datahike-form-in violated its contract (invalid-input): must be a parseable, EDN-readable Malli form"
 :seon.error/offending-args
 [[:symbol {:seon.db/identity true :seon.search/index :symbol}]]}
```

The declared owners are distinct at
`src/seon/schema/datahike.clj:70-78` (`resolve-datahike-form-in`, projection +
form) and `src/seon/schema/datahike.clj:80-87` (`resolve-datahike-form`, form
only). Their Var metadata retains the distinct source contracts, so the drift
occurs when runtime instrumentation installs or resolves the wrapper, not in
these declarations.

The same error prevents `seon.test.runner/commit-results!` from recording a
completion on `:test-results`. Restarting the isolated cluster reproduced it,
falsifying stale loaded code as the cause.

## Owner

The program-graph instrumentation owner that associates declared contracts
with Clojure Vars. `seon.schema.datahike` is the first observed victim, not the
place to add a bypass.

## Acceptance

- Each of the two resolver Vars enforces its own declared arity and input
  contract after a fresh publication and cluster start.
- The one-argument probe above returns the resolved form.
- A transaction whose schema contains the probe form commits through the
  ordinary instrumented database path.
- A regression uses two same-namespace sibling functions with distinct
  contracts and proves instrumentation cannot cross-install their wrappers.
