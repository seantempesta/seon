---
type: issue
status: superseded
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

## Disposition — superseded 2026-09-03

The proposed cross-installation defect was falsified on a fresh publication.
The two exact qualified symbols have distinct contracts in all three relevant
places:

- the source Vars at `src/seon/schema/datahike.clj:70-87`;
- the database-derived
  `:seon.schema.projection/function-contracts` map; and
- Malli's function-schema registry, whose nested keys are exact namespace and
  Var-name symbols (`reference-code/malli/src/malli/core.cljc:3061-3097`).

`seon.instrument/collect-contracts!` walks one concrete Var at a time and
hands that same Var to `malli.instrument/-collect!`
(`src/seon/instrument.clj:468-537`). Malli derives the namespace and name from
that Var's metadata and resolves it with `find-var`; there is no prefix,
regular-expression, or string search in the association
(`reference-code/malli/src/malli/instrument.clj:18-50`).

The historical diagnostic is not evidence that the outer Var owns the
sibling's contract. In an isolated fresh cluster, the outer Var is wrapped
with its own one-argument contract and a projection-handed call returns the
input form. Deliberately removing only that outer wrapper reproduces the issue
text exactly: the unwrapped body calls `resolve-datahike-form-in` at
`src/seon/schema/datahike.clj:85-87`, and the still-wrapped sibling truthfully
reports its own contract. The historical outer-wrapper state was not retained,
so its cause cannot now be attributed more narrowly.

The naked JVM probe is also outside the operation's required schema world.
`:seon.schema/definition` reaches `seon.schema/malli-form?`, whose registry
requires a handed projection (`src/seon/schema.clj:988-1001,1075-1090`). With
no projection it returns false and the outer Var reports its own contract.
That known environment defect is already owned by
`docs/seon/issues/malli-form-predicate-resolves-the-declaration-population-itself.md`;
it is not evidence of sibling association drift.

Live verification in isolated root `tmp/lane-sibling-contract-root`, cluster
`sibling-contract`, established:

- the projection-handed one-argument call returned
  `[:symbol {:seon.db/identity true :seon.search/index :symbol}]`;
- an ordinary `seon.db/transact!` using that installed schema committed at
  basis transaction `536870965`; and
- the focused baseline passed 28 tests / 180 assertions before the regression.

`prefix-related-sibling-vars-keep-their-own-contracts` now fixes the missing
class coverage: two same-namespace functions whose names have a prefix
relationship carry incompatible arities and schemas, and each violation must
name the exact called Var. No production change is justified by the evidence.
