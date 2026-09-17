# The fault-committing path could not mint the failing function's identity

2026-09-16, bounded fix lane (`steward-platform`). Evidence measured in
process on the canonical fixture under armed contracts
(`clojure -M:test` with `seon.test.arm/initialize-contracts!`, the same
arming `bin/test-fast` uses) and with `bin/test-fast`.

## What was reported and what it actually was

Two pieces of evidence were handed to this lane as one suspected defect:

1. `src/seon/db.clj:2398` wrote a STRING into `:seon.error/exception-class`,
   which `resources/seon/schemas/seon.error.edn:273` declares `:symbol`.
2. `seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace`
   was red at HEAD: the writer refused the fault recording transaction with
   `:datahike/write-rejected {:kind :transaction/validation-rejected}`, so
   `:db-after` was nil and the steward wake never arrived.

They are NOT the same defect. (1) sits on the `seon.db/diff` refusal path
and is never the datom the writer rejected; (2) is its own defect on the
fault-committing path, and the rejected datom was read directly.

## The offending datom

The rejection is `seon.db`'s own whole-entity validator
(`:datahike/validate-report`, `src/seon/db.clj:3178`), whose refusal value
the writer's log line drops. Read from the returned refusal value instead:

```clojure
"seon.db/transact! refused transaction data at [44330 :seon.schema.admission/source]:
 expected the required key :seon.schema.admission/source with either :core or :agent,
 got a map missing :seon.schema.admission/source.
 Entity: #:seon.fn{:sym \"my.agents.agent-a/broken\"}."
;; :seon.db/entity-value #:seon.fn{:ns 44328, :sym "my.agents.agent-a/broken"}
;; :seon.error/diagnostic-expected [:enum :core :agent]
;; :seon.error/diagnostic-cause :malli.core/missing-key
```

`seon.error/recording` minted the failing function's program identity with
`program/canonical-row` only — `{:seon.fn/sym … :seon.fn/ns …}`. `:seon.fn/fn`
REQUIRES `:seon.schema.admission/source`
(`resources/seon/schemas/seon.fn.edn:117`), and the whole-entity validator
rebuilds the row from the resulting datoms, so EVERY fault naming a function
the program graph had no row for refused its entire transaction: no fault
fact, no occurrence, no steward wake, and the only signal a single
`:transaction/validation-rejected` log line — the project's recurring
"absence of signal reads as health" shape, here as a refusal whose evidence
never reaches the reader.

The same class made `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`
red for `my.error-graph/raise`. That test carried a second, independent
fixture defect: a hand-written `[:db/add "error-graph-cluster"
:seon.cluster/name "error-graph"]` row missing the required
`:seon.cluster/config`, refused the same way.

## The fix, at the root

`seon.error/function-identity-call` (`src/seon/error.clj:1388`) is a
`:db.fn/call` that decides at the MID-TRANSACTION database:

- the identity already exists → it emits nothing (asserting an admission
  source here would OVERWRITE the publication's answer for every core
  function, which is not this seam's decision to make);
- the identity is absent → it mints a complete `:seon.fn/fn` row whose
  `:seon.schema.admission/source` is the one its namespace declares, and
  `:agent` when the namespace declares none, because a name no publication
  admitted was learned from a running agent.

That is the owner law applied literally: the seam does not pre-read an
answer the writer will re-decide; it hands the decision to the authority.

`src/seon/db.clj:2402` now builds the symbol where the class name is
obtained — `(symbol (.getName (class cause)))` — matching the sibling write
at `src/seon/error.clj:577`. NOTE: `src/seon/db.clj` came under a concurrent
lane's edits while this lane worked, so that one-line change is NOT in this
lane's commit; it is left in the working tree for the holder to carry.

## Every write of the two class attributes

Derived by grep over `src/` and `resources/` (2026-09-16):

- `:seon.error/exception-class` (declared `:symbol`): `src/seon/error.clj:577`
  (`(symbol class-name)`, correct), `src/seon/db.clj:2402` (fixed here).
- `:seon.error/throwable-class` (declared `[:string {:min 1}]`):
  `src/seon/error.clj:576`, `src/seon/bootstrap_drive.clj:365`,
  `src/seon/render/web.clj:2228` — all `.getName`, all correct for a string
  attribute. `src/seon/error.clj:292` symbolizes the class name INSIDE the
  signature digest, which is not a datom.
- Not attributes at all, so out of this class: `:seon.db/exception-class`
  (`src/seon/db.clj:148`), `:seon.maintenance/exception-class`
  (`src/seon/maintenance.clj:70`), `:seon.dev.mcp/exception-class`
  (`src/seon/cluster.clj:324`).

THE DUPLICATE IS THE AUDIT'S, not this lane's: `:seon.error/exception-class`
and `:seon.error/throwable-class` are two attributes for one fact, one a
symbol and one the "historical string evidence" its own docstring names.
Dissolving them is a separate slice; this lane only made the writes correct.

## Regression

`seon.error-test/a-fault-mints-the-failing-functions-identity-without-inventing-its-admission`
is the one class regression: a fault carrying a real Java class
(`java.lang.IllegalStateException`) whose function has NO program row is
accepted, stores `:seon.error/exception-class` as the SYMBOL
`java.lang.IllegalStateException`, mints a complete `:seon.fn/fn` row, derives
the namespace's steward — and a second fault against the same function, after
a publication admitted it as `:core`, leaves that `:core` untouched.
